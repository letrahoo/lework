import crypto from 'node:crypto'
import path from 'node:path'
import {
  ErrorCode,
  type AgentEvent,
  type AgentEventBody,
  type CheckpointInfo,
  type EngineInfo,
  type ModelOption,
  type ReasoningEffort,
  type SessionMeta,
  type SessionMode,
} from '@lework/protocol'
import type { EngineAdapter, EngineSession } from './engines/types.ts'
import { SleepInhibitor } from './platform/index.ts'
import type { ModelRegistry } from './registry/model-registry.ts'
import { HubRpcError, resolveAllowedWorkspacePath } from './security/path-guard.ts'
import { HostTaskInspector } from './storage/host-history.ts'
import { CheckpointManager } from './storage/workspace-fs.ts'

const MAX_EVENTS_PER_SESSION = 5_000

interface ManagedSession {
  meta: SessionMeta
  handle: EngineSession
  events: AgentEvent[]
  baseSeq: number
}

export interface SessionManagerCallbacks {
  onEvent: (event: AgentEvent) => void
  onSessionUpdated: (meta: SessionMeta) => void
}

export class SessionManager {
  private readonly adapters = new Map<string, EngineAdapter>()
  private readonly sessions = new Map<string, ManagedSession>()
  private readonly sleepInhibitor = new SleepInhibitor()
  private readonly checkpoints = new CheckpointManager()
  private readonly hostInspector: HostTaskInspector

  constructor(
    private readonly workspaceRoots: string[],
    adapters: EngineAdapter[],
    private readonly callbacks: SessionManagerCallbacks,
    private readonly registry?: ModelRegistry,
    customHostInspector?: HostTaskInspector,
  ) {
    const inspectorCallbacks = {
      onEvents: (events: AgentEvent[]) => {
        for (const ev of events) {
          // Only broadcast host-tailed events if not superseded by a Hub-managed session
          if (!this.sessions.has(ev.sessionId)) {
            this.callbacks.onEvent(ev)
          }
        }
      },
      onSessionUpdated: (meta: SessionMeta) => {
        if (!this.sessions.has(meta.id)) {
          this.callbacks.onSessionUpdated(meta)
        }
      },
    }
    if (customHostInspector) {
      customHostInspector.setCallbacks(inspectorCallbacks)
      this.hostInspector = customHostInspector
    } else {
      this.hostInspector = new HostTaskInspector(inspectorCallbacks)
    }
    for (const adapter of adapters) {
      this.adapters.set(adapter.id, adapter)
    }
  }

  async listEngines(): Promise<EngineInfo[]> {
    const cpaModels = this.registry?.getCachedCpaModels() ?? []
    const out: EngineInfo[] = []
    for (const adapter of this.adapters.values()) {
      const [status, models] = await Promise.all([
        adapter.probe(),
        this.hostInspector.getModelsForEngine(adapter.id, cpaModels),
      ])
      const hostDefaults = this.hostInspector.getEngineHostDefaults(adapter.id)
      out.push({
        id: adapter.id,
        displayName: adapter.displayName,
        kind: adapter.kind,
        status,
        capabilities: adapter.capabilities(),
        models,
        defaultModel: hostDefaults.defaultModel,
        defaultMode: hostDefaults.defaultMode,
        defaultEffort: hostDefaults.defaultEffort,
        supportedModes: hostDefaults.supportedModes,
      })
    }
    return out
  }

  listSessions(engineId?: string): SessionMeta[] {
    const hubSessions = [...this.sessions.values()].map(s => s.meta)
    const hubIds = new Set(hubSessions.map(s => s.id))
    const hostSessions = this.hostInspector
      .discoverHostSessions()
      .filter(s => !hubIds.has(s.id))

    const merged = [...hubSessions, ...hostSessions]
    const filtered = engineId ? merged.filter(s => s.engineId === engineId) : merged

    return filtered.sort((a, b) => {
      const aActive = a.state === 'running' || a.state === 'waiting_permission' ? 1 : 0
      const bActive = b.state === 'running' || b.state === 'waiting_permission' ? 1 : 0
      if (aActive !== bActive) return bActive - aActive
      return b.updatedAt - a.updatedAt
    })
  }

  async createSession(params: {
    engineId: string
    cwd: string
    workspaces?: string[]
    title?: string
    providerId?: string
    modelId?: string
    reasoningEffort?: ReasoningEffort
    mode?: SessionMode
    fixedSessionId?: string
    resumeNativeId?: string
    seedEvents?: AgentEvent[]
  }): Promise<SessionMeta> {
    const adapter = this.adapters.get(params.engineId)
    if (!adapter) {
      throw new HubRpcError(ErrorCode.EngineUnavailable, `Unknown engine "${params.engineId}"`)
    }

    // Enforce compliance & provider/model guardrails BEFORE probing local CLI binaries so invalid
    // provider combinations deterministically reject with InvalidParams on any host.
    let extraEnv: NodeJS.ProcessEnv = {}
    if (this.registry) {
      try {
        extraEnv = this.registry.resolveSessionEnv(params.engineId, params.providerId, params.modelId)
      } catch (err) {
        throw new HubRpcError(
          ErrorCode.InvalidParams,
          err instanceof Error ? err.message : String(err),
        )
      }
    }

    const status = await adapter.probe()
    if (!status.ready) {
      throw new HubRpcError(
        ErrorCode.EngineUnavailable,
        status.detail ?? `Engine "${adapter.displayName}" is not ready`,
      )
    }

    const realCwd = resolveAllowedWorkspacePath(params.cwd, this.workspaceRoots)
    const resolvedWorkspaces = [realCwd]
    for (const extra of params.workspaces ?? []) {
      try {
        const w = resolveAllowedWorkspacePath(extra, this.workspaceRoots)
        if (!resolvedWorkspaces.includes(w)) resolvedWorkspaces.push(w)
      } catch {
        // ignore invalid extra workspace
      }
    }
    const sessionId = params.fixedSessionId ?? `sess_${crypto.randomBytes(8).toString('hex')}`
    const now = Date.now()
    const seed = params.seedEvents ?? []
    const lastSeedSeq = seed.length > 0 ? seed[seed.length - 1]!.seq : -1

    const meta: SessionMeta = {
      id: sessionId,
      engineId: adapter.id,
      cwd: realCwd,
      workspaces: resolvedWorkspaces,
      title: params.title?.trim() || `${adapter.displayName} · ${path.basename(realCwd)}`,
      state: 'idle',
      createdAt: now,
      updatedAt: now,
      lastSeq: lastSeedSeq,
      source: params.fixedSessionId?.startsWith('host:') ? 'host' : 'hub',
      ...(params.modelId ? { model: params.modelId } : {}),
      ...(params.reasoningEffort ? { reasoningEffort: params.reasoningEffort } : {}),
      ...(params.mode ? { mode: params.mode } : {}),
    }

    const managed: ManagedSession = {
      meta,
      handle: null as unknown as EngineSession,
      events: [...seed],
      baseSeq: seed[0]?.seq ?? 0,
    }
    this.sessions.set(sessionId, managed)

    try {
      const handle = await adapter.openSession({
        sessionId,
        cwd: realCwd,
        providerId: params.providerId,
        modelId: params.modelId,
        reasoningEffort: params.reasoningEffort,
        mode: params.mode,
        resumeNativeId: params.resumeNativeId,
        env: extraEnv,
        emit: body => this.appendEvent(managed, body),
        onModelsChanged: (currentModel, models) => {
          this.updateModels(managed, currentModel, models)
        },
      })
      managed.handle = handle
    } catch (err) {
      this.sessions.delete(sessionId)
      throw new HubRpcError(
        ErrorCode.EngineError,
        err instanceof Error ? err.message : String(err),
      )
    }

    this.callbacks.onSessionUpdated(managed.meta)
    return managed.meta
  }

  async prompt(
    sessionId: string,
    text: string,
    overrides?: { modelId?: string; reasoningEffort?: ReasoningEffort; mode?: SessionMode },
  ): Promise<{ turnId: string }> {
    let managed = this.sessions.get(sessionId)
    if (!managed && sessionId.startsWith('host:')) {
      const hostLoaded = this.hostInspector.readHostSessionEvents(sessionId)
      const hostEntry = this.hostInspector.getHostSession(sessionId)
      if (!hostLoaded || !hostEntry) {
        throw new HubRpcError(ErrorCode.NotFound, `Host session "${sessionId}" not found`)
      }
      await this.createSession({
        engineId: hostLoaded.session.engineId,
        cwd: hostLoaded.session.cwd,
        workspaces: hostLoaded.session.workspaces,
        title: hostLoaded.session.title,
        modelId: overrides?.modelId ?? hostLoaded.session.model,
        reasoningEffort: overrides?.reasoningEffort,
        mode: overrides?.mode,
        fixedSessionId: sessionId,
        resumeNativeId: hostEntry.nativeId,
        seedEvents: hostLoaded.events,
      })
      managed = this.sessions.get(sessionId)
    }
    if (!managed) {
      throw new HubRpcError(ErrorCode.NotFound, `Session "${sessionId}" not found`)
    }
    if (managed.meta.state === 'running' || managed.meta.state === 'waiting_permission') {
      throw new HubRpcError(ErrorCode.Conflict, 'Session is already processing a turn.')
    }
    if (overrides?.modelId && managed.handle.setModel) {
      await managed.handle.setModel(overrides.modelId).catch(() => undefined)
      managed.meta.model = overrides.modelId
    }
    if (overrides?.reasoningEffort) {
      managed.meta.reasoningEffort = overrides.reasoningEffort
    }
    if (overrides?.mode) {
      managed.meta.mode = overrides.mode
    }

    const turnId = `turn_${crypto.randomBytes(6).toString('hex')}`
    this.appendEvent(managed, {
      type: 'message.user',
      messageId: `u_${turnId}`,
      text,
    })
    if (managed.meta.lastSeq <= 2 && managed.meta.title.includes(' · ')) {
      managed.meta.title = text.slice(0, 48).replace(/\s+/g, ' ').trim() || managed.meta.title
      this.callbacks.onSessionUpdated(managed.meta)
    }

    // Auto-snapshot working tree right before launching a new turn so the user can always roll back
    try {
      const summary = text.replace(/\s+/g, ' ').trim().slice(0, 18)
      this.checkpoints.create(
        sessionId,
        managed.meta.cwd,
        summary ? `对话前自动快照 · ${summary}` : '对话前自动快照',
        { deduplicateUnchanged: true },
      )
    } catch {
      // Non-git directories gracefully skip checkpointing
    }

    this.sleepInhibitor.acquire()
    void managed.handle
      .prompt(turnId, text)
      .catch(err => {
        const msg = err instanceof Error ? err.message : String(err)
        this.appendEvent(managed, { type: 'error', message: msg })
        this.appendEvent(managed, { type: 'session.state', state: 'error', detail: msg })
      })
      .finally(() => {
        this.sleepInhibitor.release()
      })

    return { turnId }
  }

  async cancel(sessionId: string): Promise<void> {
    const managed = this.requireSession(sessionId)
    await managed.handle.cancel()
  }

  async respondPermission(
    sessionId: string,
    requestId: string,
    optionId: string | null,
  ): Promise<void> {
    const managed = this.sessions.get(sessionId)
    if ((!managed || requestId.startsWith('tty_perm_')) && sessionId.startsWith('host:')) {
      try {
        await this.hostInspector.respondHostPermission(sessionId, requestId, optionId)
        return
      } catch (err) {
        throw new HubRpcError(
          ErrorCode.InvalidParams,
          err instanceof Error ? err.message : String(err),
        )
      }
    }
    if (!managed) {
      throw new HubRpcError(ErrorCode.NotFound, `Session "${sessionId}" not found`)
    }
    try {
      await managed.handle.respondPermission(requestId, optionId)
    } catch (err) {
      throw new HubRpcError(
        ErrorCode.InvalidParams,
        err instanceof Error ? err.message : String(err),
      )
    }
  }

  async setModel(sessionId: string, modelId: string): Promise<SessionMeta> {
    const managed = this.requireSession(sessionId)
    if (!managed.handle.setModel) {
      throw new HubRpcError(ErrorCode.EngineError, 'Engine does not support changing models.')
    }
    await managed.handle.setModel(modelId)
    managed.meta.model = modelId
    managed.meta.updatedAt = Date.now()
    this.callbacks.onSessionUpdated(managed.meta)
    return managed.meta
  }

  async closeSession(sessionId: string): Promise<void> {
    const managed = this.requireSession(sessionId)
    await managed.handle.close()
    managed.meta.state = 'closed'
    managed.meta.updatedAt = Date.now()
    this.callbacks.onSessionUpdated(managed.meta)
    this.sessions.delete(sessionId)
  }

  private resolveSessionWorkspaceCwd(sessionId: string): string {
    const managed = this.sessions.get(sessionId)
    if (managed) {
      return resolveAllowedWorkspacePath(managed.meta.cwd, this.workspaceRoots)
    }
    if (sessionId.startsWith('host:')) {
      const hostEntry = this.hostInspector.getHostSession(sessionId)
      if (hostEntry?.meta.cwd) {
        return resolveAllowedWorkspacePath(hostEntry.meta.cwd, this.workspaceRoots)
      }
    }
    throw new HubRpcError(ErrorCode.NotFound, `Session "${sessionId}" not found`)
  }

  listCheckpoints(sessionId: string): CheckpointInfo[] {
    const cwd = this.resolveSessionWorkspaceCwd(sessionId)
    return this.checkpoints.list(sessionId, cwd)
  }

  createCheckpoint(sessionId: string, label?: string): CheckpointInfo {
    const cwd = this.resolveSessionWorkspaceCwd(sessionId)
    return this.checkpoints.create(sessionId, cwd, label)
  }

  restoreCheckpoint(sessionId: string, checkpointId: string): void {
    const cwd = this.resolveSessionWorkspaceCwd(sessionId)
    this.checkpoints.restore(sessionId, cwd, checkpointId)
  }

  subscribeSnapshot(
    sessionId: string,
    afterSeq: number,
    opts?: { beforeSeq?: number; limit?: number },
  ): {
    session: SessionMeta
    events: AgentEvent[]
    truncated: boolean
    earliestSeq: number
    hasMoreBefore: boolean
  } {
    const managed = this.sessions.get(sessionId)
    if (managed) {
      return sliceSessionWindow(managed.meta, managed.events, afterSeq, managed.baseSeq, opts)
    }

    if (sessionId.startsWith('host:')) {
      const hostData = this.hostInspector.readHostSessionEvents(sessionId)
      if (hostData) {
        const baseSeq = hostData.events[0]?.seq ?? 0
        const sliced = sliceSessionWindow(hostData.session, hostData.events, afterSeq, baseSeq, opts)
        const activePerm = opts?.beforeSeq === undefined ? this.hostInspector.getActiveHostPermission(sessionId) : null
        if (activePerm) {
          sliced.events = [...sliced.events, activePerm]
        }
        return sliced
      }
    }

    throw new HubRpcError(ErrorCode.NotFound, `Session "${sessionId}" not found`)
  }

  retainHostWatch(sessionId: string): void {
    if (sessionId.startsWith('host:') && !this.sessions.has(sessionId)) {
      this.hostInspector.retainWatch(sessionId)
    }
  }

  async syncHostTerminalNow(sessionId: string): Promise<void> {
    if (sessionId.startsWith('host:') && !this.sessions.has(sessionId)) {
      await this.hostInspector.syncTerminalNow(sessionId)
    }
  }

  releaseHostWatch(sessionId: string): void {
    if (sessionId.startsWith('host:')) {
      this.hostInspector.releaseWatch(sessionId)
    }
  }

  startGlobalHostMonitor(): void {
    this.hostInspector.startGlobalMonitor()
  }

  stopGlobalHostMonitor(): void {
    this.hostInspector.stopAllWatches()
  }

  async closeAll(): Promise<void> {
    this.hostInspector.stopAllWatches()
    this.sleepInhibitor.dispose()
    const all = [...this.sessions.values()]
    this.sessions.clear()
    await Promise.allSettled(all.map(s => s.handle.close()))
  }

  private requireSession(sessionId: string): ManagedSession {
    const managed = this.sessions.get(sessionId)
    if (!managed) {
      throw new HubRpcError(ErrorCode.NotFound, `Session "${sessionId}" not found`)
    }
    return managed
  }

  private appendEvent(managed: ManagedSession, body: AgentEventBody): void {
    const seq = managed.meta.lastSeq + 1
    managed.meta.lastSeq = seq
    managed.meta.updatedAt = Date.now()

    if (body.type === 'session.state') {
      managed.meta.state = body.state
      managed.meta.turnStartedAt = body.state === 'running' ? (managed.meta.turnStartedAt ?? Date.now()) : undefined
      this.callbacks.onSessionUpdated(managed.meta)
    }

    const event = {
      ...body,
      seq,
      sessionId: managed.meta.id,
      ts: managed.meta.updatedAt,
    } as AgentEvent

    managed.events.push(event)
    if (managed.events.length > MAX_EVENTS_PER_SESSION) {
      managed.events.shift()
      managed.baseSeq = managed.events[0]?.seq ?? seq
    }

    this.callbacks.onEvent(event)
  }

  private updateModels(
    managed: ManagedSession,
    currentModel: string | undefined,
    models: ModelOption[],
  ): void {
    if (currentModel && !managed.meta.model) managed.meta.model = currentModel
    managed.meta.models = models
    managed.meta.updatedAt = Date.now()
    this.callbacks.onSessionUpdated(managed.meta)
  }
}

function sliceSessionWindow(
  session: SessionMeta,
  allEvents: AgentEvent[],
  afterSeq: number,
  baseSeq: number,
  opts?: { beforeSeq?: number; limit?: number },
): {
  session: SessionMeta
  events: AgentEvent[]
  truncated: boolean
  earliestSeq: number
  hasMoreBefore: boolean
} {
  if (allEvents.length === 0) {
    return {
      session,
      events: [],
      truncated: false,
      earliestSeq: 0,
      hasMoreBefore: false,
    }
  }

  // 1. Reverse pagination (`beforeSeq` provided) — loading older turns on scroll-up
  if (typeof opts?.beforeSeq === 'number') {
    const beforeSeq = opts.beforeSeq
    const older = allEvents.filter(ev => ev.seq < beforeSeq)
    if (older.length === 0) {
      return {
        session,
        events: [],
        truncated: false,
        earliestSeq: allEvents[0]!.seq,
        hasMoreBefore: false,
      }
    }
    const maxCount = opts.limit ?? 220
    let startIdx = Math.max(0, older.length - maxCount)
    startIdx = snapToTurnBoundary(older, startIdx)
    const page = older.slice(startIdx)
    const earliestSeq = page[0]?.seq ?? allEvents[0]!.seq
    const hasMoreBefore = startIdx > 0 || earliestSeq > allEvents[0]!.seq
    return {
      session,
      events: page,
      truncated: hasMoreBefore,
      earliestSeq,
      hasMoreBefore,
    }
  }

  // 2. Initial paged open (`afterSeq === -1` and `limit` specified)
  if (afterSeq === -1 && typeof opts?.limit === 'number' && allEvents.length > opts.limit) {
    let startIdx = Math.max(0, allEvents.length - opts.limit)
    startIdx = snapToTurnBoundary(allEvents, startIdx)
    const page = allEvents.slice(startIdx)
    const earliestSeq = page[0]?.seq ?? 0
    const hasMoreBefore = startIdx > 0
    return {
      session,
      events: page,
      truncated: hasMoreBefore,
      earliestSeq,
      hasMoreBefore,
    }
  }

  // 3. Catch-up (`afterSeq >= 0`) or unbounded snapshot
  const truncated = afterSeq >= 0 && afterSeq + 1 < baseSeq
  const events = allEvents.filter(ev => ev.seq > afterSeq)
  const earliestSeq = events[0]?.seq ?? allEvents[0]!.seq
  const hasMoreBefore = afterSeq === -1 ? earliestSeq > allEvents[0]!.seq : false
  return {
    session,
    events,
    truncated,
    earliestSeq,
    hasMoreBefore,
  }
}

function snapToTurnBoundary(events: AgentEvent[], initialIdx: number): number {
  if (initialIdx <= 0) return 0
  // Walk backward up to 600 events so a user question + assistant answer are never split mid-turn
  const floor = Math.max(0, initialIdx - 600)
  for (let i = initialIdx; i >= floor; i--) {
    if (events[i]?.type === 'message.user') {
      return i
    }
  }
  return initialIdx
}
