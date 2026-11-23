import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import {
  ErrorCode,
  type AgentEvent,
  type AgentEventBody,
  type CheckpointInfo,
  type EngineInfo,
  type ModelOption,
  type PromptImageAttachment,
  type ReasoningEffort,
  type SessionMeta,
  type SessionMode,
  type SessionWorktreeInfo,
} from '@lework/protocol'
import type { EngineAdapter, EngineSession, StagedPromptImage } from './engines/types.ts'
import { SleepInhibitor } from './platform/index.ts'
import type { ModelRegistry } from './registry/model-registry.ts'
import { HubRpcError, resolveAllowedWorkspacePath } from './security/path-guard.ts'
import {
  buildTerminalImagePrompt,
  linkSessionAttachmentAlias,
  removeSessionAttachments,
  stagePromptImagesForSession,
  sweepExpiredArchivedAttachments,
} from './storage/attachment-store.ts'
import { HostTaskInspector, TerminalApprovalError } from './storage/host-history.ts'
import { SessionMetadataStore } from './storage/session-metadata.ts'
import {
  CheckpointManager,
  cleanupSessionWorktree,
  createSessionWorktree,
  getManagedWorktreesRoot,
  restoreSessionWorktreeIfNeeded,
} from './storage/workspace-fs.ts'

const MAX_EVENTS_PER_SESSION = 5_000

interface QueuedTurnItem {
  turnId: string
  rawText: string
  displayUserText: string
  stagedImages: StagedPromptImage[]
  modelId?: string
  reasoningEffort?: ReasoningEffort
  mode?: SessionMode
}

interface ManagedSession {
  meta: SessionMeta
  autoTitle: string
  handle: EngineSession
  events: AgentEvent[]
  baseSeq: number
  queuedTurns: QueuedTurnItem[]
  closed?: boolean
  nativeId?: string
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
  private readonly metaStore = new SessionMetadataStore()
  private readonly hostInspector: HostTaskInspector
  private archivedAttachmentSweepTimer: ReturnType<typeof setInterval> | null = null

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
          if (!this.isSupersededByHubSession(ev.sessionId)) {
            this.callbacks.onEvent(ev)
          }
        }
      },
      onSessionUpdated: (meta: SessionMeta) => {
        if (!this.isSupersededByHubSession(meta.id)) {
          this.callbacks.onSessionUpdated(this.metaStore.applyToMeta(meta))
        }
      },
    }
    if (customHostInspector) {
      customHostInspector.setCallbacks(inspectorCallbacks)
      customHostInspector.setMetaStore(this.metaStore)
      this.hostInspector = customHostInspector
    } else {
      this.hostInspector = new HostTaskInspector(inspectorCallbacks)
      this.hostInspector.setMetaStore(this.metaStore)
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

  private releaseIdleHostWrapperIfDiskAdvanced(
    sessionId: string,
    diskLastSeq?: number,
    diskState?: SessionMeta['state'],
  ): void {
    if (!sessionId.startsWith('host:')) return
    const managed = this.sessions.get(sessionId)
    if (!managed) return
    if (
      managed.meta.state === 'running' ||
      managed.meta.state === 'waiting_permission' ||
      managed.queuedTurns.length > 0
    ) {
      return
    }
    const diskProgressed =
      (typeof diskLastSeq === 'number' && diskLastSeq > managed.meta.lastSeq) ||
      diskState === 'running' ||
      diskState === 'waiting_permission'
    if (diskProgressed) {
      managed.closed = true
      this.sessions.delete(sessionId)
      void managed.handle?.close().catch(() => undefined)
      this.hostInspector.retainWatch(sessionId)
    }
  }

  private isSupersededByHubSession(hostOrHubSessionId: string): boolean {
    if (this.sessions.has(hostOrHubSessionId)) return true
    if (!hostOrHubSessionId.startsWith('host:')) return false
    for (const m of this.sessions.values()) {
      if (m.nativeId && `host:${m.meta.engineId}:${m.nativeId}` === hostOrHubSessionId) {
        return true
      }
    }
    return false
  }

  private decorateManagedMeta(managed: ManagedSession): SessionMeta {
    return this.metaStore.applyToMeta({ ...managed.meta, title: managed.autoTitle })
  }

  listSessions(engineId?: string): SessionMeta[] {
    const hubSessions = [...this.sessions.values()].map(s => this.decorateManagedMeta(s))
    const hostSessions = this.hostInspector
      .discoverHostSessions()
      .filter(s => !this.isSupersededByHubSession(s.id))
      .map(s => this.metaStore.applyToMeta(s))

    const merged = [...hubSessions, ...hostSessions]
    const filtered = engineId ? merged.filter(s => s.engineId === engineId) : merged

    return filtered.sort((a, b) => {
      const aActive = !a.archived && (a.state === 'running' || a.state === 'waiting_permission') ? 1 : 0
      const bActive = !b.archived && (b.state === 'running' || b.state === 'waiting_permission') ? 1 : 0
      if (aActive !== bActive) return bActive - aActive
      return b.updatedAt - a.updatedAt
    })
  }

  async createSession(params: {
    engineId: string
    cwd: string
    workspaces?: string[]
    useWorktree?: boolean
    worktreeBranch?: string
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

    const allowedRoots = [...new Set([...this.workspaceRoots, getManagedWorktreesRoot()])]
    const requestedCwd = resolveAllowedWorkspacePath(params.cwd, allowedRoots)
    const sessionId = params.fixedSessionId ?? `sess_${crypto.randomBytes(8).toString('hex')}`
    const shortId = sessionId.replace(/^sess_/, '').slice(0, 6) || crypto.randomBytes(3).toString('hex')

    let realCwd = requestedCwd
    let worktreeInfo: SessionWorktreeInfo | undefined
    if (params.useWorktree && !params.fixedSessionId?.startsWith('host:')) {
      const createdWt = createSessionWorktree({
        cwd: requestedCwd,
        workspaceRoots: allowedRoots,
        requestedBranch: params.worktreeBranch,
        shortId,
      })
      realCwd = createdWt.sessionCwd
      worktreeInfo = createdWt.worktree
    }

    const resolvedWorkspaces = [realCwd]
    for (const extra of params.workspaces ?? []) {
      try {
        const w = resolveAllowedWorkspacePath(extra, allowedRoots)
        if (!resolvedWorkspaces.includes(w) && w !== requestedCwd) resolvedWorkspaces.push(w)
      } catch {
        // ignore invalid extra workspace
      }
    }
    const now = Date.now()
    const seed = params.seedEvents ?? []
    const lastSeedSeq = seed.length > 0 ? seed[seed.length - 1]!.seq : -1

    const displayFolder = worktreeInfo ? path.basename(worktreeInfo.baseRepoPath) : path.basename(realCwd)
    const initialAutoTitle = params.title?.trim() || `${adapter.displayName} · ${displayFolder}`
    const meta: SessionMeta = {
      id: sessionId,
      engineId: adapter.id,
      cwd: realCwd,
      workspaces: resolvedWorkspaces,
      ...(worktreeInfo ? { worktree: worktreeInfo } : {}),
      title: initialAutoTitle,
      state: 'idle',
      createdAt: now,
      updatedAt: now,
      lastSeq: lastSeedSeq,
      source: params.fixedSessionId?.startsWith('host:') ? 'host' : 'hub',
      ...(params.modelId ? { model: params.modelId } : {}),
      ...(params.reasoningEffort ? { reasoningEffort: params.reasoningEffort } : {}),
      ...(params.mode ? { mode: params.mode } : {}),
    }

    if (worktreeInfo) {
      this.metaStore.setWorktree(sessionId, worktreeInfo, { baseMeta: meta })
    }

    const managed: ManagedSession = {
      meta,
      autoTitle: initialAutoTitle,
      handle: null as unknown as EngineSession,
      events: [...seed],
      baseSeq: seed[0]?.seq ?? 0,
      queuedTurns: [],
      ...(params.resumeNativeId ? { nativeId: params.resumeNativeId } : {}),
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
        onNativeSessionLinked: nativeId => {
          const clean = nativeId.trim()
          if (clean) {
            managed.nativeId = clean
            const aliasId = `host:${adapter.id}:${clean}`
            this.metaStore.linkAlias(sessionId, aliasId)
            linkSessionAttachmentAlias(sessionId, aliasId)
          }
        },
      })
      managed.handle = handle
    } catch (err) {
      this.sessions.delete(sessionId)
      if (worktreeInfo) {
        this.metaStore.setWorktree(sessionId, undefined)
        cleanupSessionWorktree(worktreeInfo)
      }
      throw new HubRpcError(
        ErrorCode.EngineError,
        err instanceof Error ? err.message : String(err),
      )
    }

    managed.meta = this.decorateManagedMeta(managed)
    this.callbacks.onSessionUpdated(managed.meta)
    return managed.meta
  }

  async prompt(
    sessionId: string,
    text: string,
    overrides?: {
      images?: PromptImageAttachment[]
      modelId?: string
      reasoningEffort?: ReasoningEffort
      mode?: SessionMode
    },
  ): Promise<{ turnId: string }> {
    const rawImages = overrides?.images ?? []
    if (!text.trim() && rawImages.length === 0) {
      throw new HubRpcError(ErrorCode.InvalidParams, 'Prompt text or image attachment is required.')
    }

    const turnId = `turn_${crypto.randomBytes(6).toString('hex')}`
    const stagedImages = stagePromptImagesForSession(sessionId, turnId, rawImages)
    const displayUserText = text.trim() || `（已发送 ${stagedImages.length} 张图片）`

    let managed = this.sessions.get(sessionId)

    // Automatically resurrect missing / archived-cleaned worktree checkouts before executing a follow-up turn
    const persistedWt = managed?.meta.worktree ?? this.metaStore.get(sessionId)?.worktree
    if (persistedWt && (persistedWt.cleanedUp || !fs.existsSync(path.join(persistedWt.worktreePath, '.git')))) {
      const desiredCwd = managed?.meta.cwd
      const restored = restoreSessionWorktreeIfNeeded(persistedWt, desiredCwd)
      const aliasId =
        managed?.nativeId && !sessionId.startsWith('host:')
          ? `host:${managed.meta.engineId}:${managed.nativeId}`
          : undefined
      this.metaStore.setWorktree(sessionId, restored.worktree, {
        hostAliasId: aliasId,
        nativeId: managed?.nativeId,
        baseMeta: managed ? { ...managed.meta, title: managed.autoTitle } : undefined,
      })
      if (managed) {
        managed.meta.cwd = restored.sessionCwd
        managed.meta.worktree = restored.worktree
        if (restored.restored && managed.meta.state === 'idle') {
          // Reopen engine session handle against the newly recreated worktree inode
          const prevEvents = [...managed.events]
          const prevNativeId = managed.nativeId
          const prevMeta = { ...managed.meta }
          await managed.handle.close().catch(() => {})
          this.sessions.delete(sessionId)
          await this.createSession({
            engineId: prevMeta.engineId,
            cwd: restored.sessionCwd,
            workspaces: prevMeta.workspaces,
            title: managed.autoTitle,
            modelId: prevMeta.model,
            reasoningEffort: prevMeta.reasoningEffort,
            mode: prevMeta.mode,
            fixedSessionId: sessionId,
            resumeNativeId: prevNativeId,
            seedEvents: prevEvents,
          })
          managed = this.sessions.get(sessionId)
          if (managed) {
            managed.meta.worktree = restored.worktree
            managed.meta = this.decorateManagedMeta(managed)
          }
        } else {
          managed.meta = this.decorateManagedMeta(managed)
          this.callbacks.onSessionUpdated(managed.meta)
        }
      }
    }

    const canProbeLiveTerminal =
      sessionId.startsWith('host:') &&
      (!managed || (managed.meta.state !== 'running' && managed.meta.state !== 'waiting_permission'))

    if (canProbeLiveTerminal) {
      const hostLoaded = this.hostInspector.readHostSessionEvents(sessionId)
      const hostEntry = this.hostInspector.getHostSession(sessionId)
      if (!managed && (!hostLoaded || !hostEntry)) {
        throw new HubRpcError(ErrorCode.NotFound, `Host session "${sessionId}" not found`)
      }

      if (hostLoaded && hostEntry) {
        // First: if this `host:*` conversation is actively open in a reachable local `tmux` / `iTerm2`
        // terminal, deliver the prompt directly into that live terminal so computer & phone stay in sync!
        const terminalPromptText = buildTerminalImagePrompt(stagedImages, text)
        let deliveredToTerminal = false
        try {
          deliveredToTerminal = await this.hostInspector.tryPromptLiveHostSession(
            sessionId,
            terminalPromptText,
            displayUserText,
            {
              modelId: overrides?.modelId,
              reasoningEffort: overrides?.reasoningEffort,
            },
          )
        } catch (err) {
          if (err instanceof TerminalApprovalError) {
            throw new HubRpcError(ErrorCode.Conflict, err.message)
          }
          throw err
        }

        if (!deliveredToTerminal && hostLoaded.session.engineId === 'agy') {
          // Guardrail: never spawn a shadow `agy --conversation <id>` process if an interactive `agy`
          // terminal already holds `presence/<id>.lock` (spawning a duplicate deadlocks on the lock!).
          const lockCheck = await this.hostInspector.isAgyLockedInInteractiveTerminal(hostEntry.nativeId)
          if (lockCheck.locked) {
            deliveredToTerminal = await this.hostInspector.tryPromptLiveHostSession(
              sessionId,
              terminalPromptText,
              displayUserText,
              {
                modelId: overrides?.modelId,
                reasoningEffort: overrides?.reasoningEffort,
              },
            )
            if (!deliveredToTerminal) {
              const where = lockCheck.hostAppHint ? `${lockCheck.hostAppHint} 窗口` : '电脑其他终端窗口'
              throw new HubRpcError(
                ErrorCode.Conflict,
                `该 AGY 会话正在 ${where} 中打开并占用会话锁；请在 tmux 或 iTerm2 中运行以实现手机双向同步，或先关闭电脑上的该会话窗口。`,
              )
            }
          }
        }

        if (deliveredToTerminal) {
          if (overrides?.modelId || overrides?.reasoningEffort || overrides?.mode) {
            this.metaStore.setSessionPrefs(
              sessionId,
              {
                model: overrides.modelId,
                reasoningEffort: overrides.reasoningEffort,
                mode: overrides.mode,
              },
              {
                filePath: hostEntry.filePath,
                nativeId: hostEntry.nativeId,
                baseMeta: hostEntry.meta,
              },
            )
          }
          if (managed) {
            this.sessions.delete(sessionId)
            void managed.handle.close().catch(() => {})
            this.hostInspector.retainWatch(sessionId)
          }
          try {
            const summary = displayUserText.replace(/\s+/g, ' ').trim().slice(0, 18)
            this.checkpoints.create(
              sessionId,
              hostLoaded.session.cwd,
              summary ? `对话前自动快照 · ${summary}` : '对话前自动快照',
              { deduplicateUnchanged: true },
            )
          } catch {
            // Non-git directories gracefully skip checkpointing
          }
          return { turnId }
        }

        if (!managed) {
          const decoratedHost = this.metaStore.applyToMeta(hostLoaded.session)
          await this.createSession({
            engineId: hostLoaded.session.engineId,
            cwd: hostLoaded.session.cwd,
            workspaces: hostLoaded.session.workspaces,
            title: hostLoaded.session.title,
            modelId: overrides?.modelId ?? decoratedHost.model,
            reasoningEffort: overrides?.reasoningEffort ?? decoratedHost.reasoningEffort,
            mode: overrides?.mode ?? decoratedHost.mode,
            fixedSessionId: sessionId,
            resumeNativeId: hostEntry.nativeId,
            seedEvents: hostLoaded.events,
          })
          managed = this.sessions.get(sessionId)
        }
      }
    }
    if (!managed) {
      throw new HubRpcError(ErrorCode.NotFound, `Session "${sessionId}" not found`)
    }
    if (managed.meta.state === 'waiting_permission') {
      throw new HubRpcError(ErrorCode.Conflict, '当前会话正在等待权限确认，请先完成审批后再发送。')
    }
    if (overrides?.modelId || overrides?.reasoningEffort || overrides?.mode) {
      const hostAliasId =
        managed.nativeId && !sessionId.startsWith('host:')
          ? `host:${managed.meta.engineId}:${managed.nativeId}`
          : undefined
      const hostEntry = sessionId.startsWith('host:')
        ? this.hostInspector.getHostSession(sessionId)
        : hostAliasId
          ? this.hostInspector.getHostSession(hostAliasId)
          : undefined
      this.metaStore.setSessionPrefs(
        sessionId,
        {
          model: overrides.modelId,
          reasoningEffort: overrides.reasoningEffort,
          mode: overrides.mode,
        },
        {
          hostAliasId,
          filePath: hostEntry?.filePath,
          nativeId: hostEntry?.nativeId ?? managed.nativeId,
          baseMeta: hostEntry?.meta ?? managed.meta,
        },
      )
    }

    if (managed.meta.state === 'running') {
      if (managed.queuedTurns.length >= 8) {
        throw new HubRpcError(ErrorCode.Conflict, '待执行消息队列已满（最多 8 条），请等待当前轮次完成。')
      }
      managed.queuedTurns.push({
        turnId,
        rawText: text,
        displayUserText,
        stagedImages,
        modelId: overrides?.modelId,
        reasoningEffort: overrides?.reasoningEffort,
        mode: overrides?.mode,
      })
      managed.meta.queuedPrompts = managed.queuedTurns.map(q => q.displayUserText)
      managed.meta.updatedAt = Date.now()
      managed.meta = this.decorateManagedMeta(managed)
      this.callbacks.onSessionUpdated(managed.meta)
      return { turnId }
    }

    await this.applyTurnOverrides(managed, {
      modelId: overrides?.modelId,
      reasoningEffort: overrides?.reasoningEffort,
      mode: overrides?.mode,
    })

    this.startManagedTurn(managed, {
      turnId,
      rawText: text,
      displayUserText,
      stagedImages,
    })

    return { turnId }
  }

  private async applyTurnOverrides(
    managed: ManagedSession,
    overrides: {
      modelId?: string
      reasoningEffort?: ReasoningEffort
      mode?: SessionMode
    },
  ): Promise<void> {
    if (overrides.modelId && overrides.modelId !== managed.meta.model) {
      if (managed.handle.setModel) {
        await managed.handle.setModel(overrides.modelId).catch(() => undefined)
      }
      managed.meta.model = overrides.modelId
    }
    if (overrides.reasoningEffort && overrides.reasoningEffort !== managed.meta.reasoningEffort) {
      if (managed.handle.setEffort) {
        await managed.handle.setEffort(overrides.reasoningEffort).catch(() => undefined)
      }
      managed.meta.reasoningEffort = overrides.reasoningEffort
    }
    if (overrides.mode && overrides.mode !== managed.meta.mode) {
      if (managed.handle.setMode) {
        await managed.handle.setMode(overrides.mode).catch(() => undefined)
      }
      managed.meta.mode = overrides.mode
    }
  }

  private startManagedTurn(managed: ManagedSession, item: QueuedTurnItem): void {
    const { turnId, rawText, displayUserText, stagedImages } = item
    const sessionId = managed.meta.id

    const previews = stagedImages
      .map(i => i.previewBase64)
      .filter((v): v is string => Boolean(v && v.length > 0))

    this.appendEvent(managed, {
      type: 'message.user',
      messageId: `u_${turnId}`,
      text: displayUserText,
      ...(stagedImages.length > 0 ? { imageCount: stagedImages.length } : {}),
      ...(previews.length > 0 ? { imagePreviews: previews } : {}),
    })
    if (managed.meta.lastSeq <= 2 && managed.autoTitle.includes(' · ')) {
      managed.autoTitle =
        displayUserText.slice(0, 48).replace(/\s+/g, ' ').trim() || managed.autoTitle
      managed.meta = this.decorateManagedMeta(managed)
      this.callbacks.onSessionUpdated(managed.meta)
    }

    // Auto-snapshot working tree right before launching a new turn so the user can always roll back
    try {
      const summary = displayUserText.replace(/\s+/g, ' ').trim().slice(0, 18)
      this.checkpoints.create(
        sessionId,
        managed.meta.cwd,
        summary ? `对话前自动快照 · ${summary}` : '对话前自动快照',
        { deduplicateUnchanged: true },
      )
    } catch {
      // Non-git directories gracefully skip checkpointing
    }

    managed.meta.state = 'running'
    managed.meta.turnStartedAt = Date.now()
    managed.meta = this.decorateManagedMeta(managed)
    this.callbacks.onSessionUpdated(managed.meta)

    this.sleepInhibitor.acquire()
    void managed.handle
      .prompt(turnId, rawText, stagedImages.length > 0 ? stagedImages : undefined)
      .catch(err => {
        const msg = err instanceof Error ? err.message : String(err)
        this.appendEvent(managed, { type: 'error', message: msg })
        this.appendEvent(managed, { type: 'session.state', state: 'idle', detail: msg })
      })
      .finally(() => {
        this.sleepInhibitor.release()
        if (managed.closed || !this.sessions.has(sessionId)) {
          return
        }
        const nextQueued = managed.queuedTurns.shift()
        managed.meta.queuedPrompts =
          managed.queuedTurns.length > 0 ? managed.queuedTurns.map(q => q.displayUserText) : undefined
        if (nextQueued) {
          void this.applyTurnOverrides(managed, {
            modelId: nextQueued.modelId,
            reasoningEffort: nextQueued.reasoningEffort,
            mode: nextQueued.mode,
          }).finally(() => {
            if (!managed.closed && this.sessions.has(sessionId)) {
              this.startManagedTurn(managed, nextQueued)
            }
          })
        } else if (managed.meta.state === 'running') {
          managed.meta.state = 'idle'
          managed.meta.turnStartedAt = undefined
          managed.meta = this.decorateManagedMeta(managed)
          this.callbacks.onSessionUpdated(managed.meta)
        } else {
          managed.meta = this.decorateManagedMeta(managed)
          this.callbacks.onSessionUpdated(managed.meta)
        }
      })
  }

  getSessionMeta(sessionId: string): SessionMeta | undefined {
    const managed = this.sessions.get(sessionId)
    if (managed) return this.decorateManagedMeta(managed)
    if (sessionId.startsWith('host:')) {
      const hostMeta = this.hostInspector.getHostSession(sessionId)?.meta
      return hostMeta ? this.metaStore.applyToMeta(hostMeta) : undefined
    }
    return undefined
  }

  /**
   * Sweeps attachment files ONLY for archived sessions (`archived === true`) whose archive age
   * exceeds 7 days (default). Active conversations are NEVER cleaned!
   */
  sweepArchivedAttachments(now = Date.now(), retentionMs?: number): number {
    return sweepExpiredArchivedAttachments(this.metaStore, retentionMs, now)
  }

  async cancel(sessionId: string): Promise<void> {
    const managed = this.sessions.get(sessionId)
    if (!managed && sessionId.startsWith('host:')) {
      const ok = await this.hostInspector.cancelLiveHostSession(sessionId)
      if (!ok) {
        throw new HubRpcError(ErrorCode.NotFound, `Active terminal for "${sessionId}" not found`)
      }
      return
    }
    const req = this.requireSession(sessionId)
    req.queuedTurns = []
    req.meta.queuedPrompts = undefined
    await req.handle.cancel()
    req.meta.state = 'idle'
    req.meta.turnStartedAt = undefined
    req.meta = this.decorateManagedMeta(req)
    this.callbacks.onSessionUpdated(req.meta)
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

  async setModel(
    sessionId: string,
    modelId: string,
    opts?: {
      reasoningEffort?: ReasoningEffort
      mode?: SessionMode
    },
  ): Promise<SessionMeta> {
    const cleanModel = modelId.trim()
    if (!cleanModel) {
      throw new HubRpcError(ErrorCode.InvalidParams, 'Model ID is required.')
    }

    const managed = this.sessions.get(sessionId)
    const hostAliasId =
      managed?.nativeId && !sessionId.startsWith('host:')
        ? `host:${managed.meta.engineId}:${managed.nativeId}`
        : undefined
    const hostEntry = sessionId.startsWith('host:')
      ? this.hostInspector.getHostSession(sessionId)
      : hostAliasId
        ? this.hostInspector.getHostSession(hostAliasId)
        : undefined

    if (!managed && !hostEntry) {
      throw new HubRpcError(ErrorCode.NotFound, `Session "${sessionId}" not found`)
    }

    this.metaStore.setSessionPrefs(
      sessionId,
      {
        model: cleanModel,
        reasoningEffort: opts?.reasoningEffort,
        mode: opts?.mode,
      },
      {
        hostAliasId,
        filePath: hostEntry?.filePath,
        nativeId: hostEntry?.nativeId ?? managed?.nativeId,
        baseMeta: hostEntry?.meta ?? managed?.meta,
      },
    )

    if (managed) {
      if (!managed.handle.setModel) {
        throw new HubRpcError(ErrorCode.EngineError, 'Engine does not support changing models.')
      }
      await managed.handle.setModel(cleanModel)
      managed.meta.model = cleanModel
      if (opts?.reasoningEffort) {
        await managed.handle.setEffort?.(opts.reasoningEffort)
        managed.meta.reasoningEffort = opts.reasoningEffort
      }
      if (opts?.mode) {
        await managed.handle.setMode?.(opts.mode)
        managed.meta.mode = opts.mode
      }
      managed.meta.updatedAt = Date.now()
      managed.meta = this.decorateManagedMeta(managed)
      this.callbacks.onSessionUpdated(managed.meta)
      return managed.meta
    }

    if (sessionId.startsWith('host:')) {
      await this.hostInspector
        .trySwitchLiveHostModel(
          sessionId,
          cleanModel,
          opts?.reasoningEffort ?? hostEntry!.meta.reasoningEffort,
        )
        .catch(() => false)
    }

    const updated = this.metaStore.applyToMeta({
      ...hostEntry!.meta,
      model: cleanModel,
      ...(opts?.reasoningEffort ? { reasoningEffort: opts.reasoningEffort } : {}),
      ...(opts?.mode ? { mode: opts.mode } : {}),
      updatedAt: Date.now(),
    })
    this.callbacks.onSessionUpdated(updated)
    return updated
  }

  renameSession(sessionId: string, title: string): SessionMeta {
    const managed = this.sessions.get(sessionId)
    const hostAliasId =
      managed?.nativeId && !sessionId.startsWith('host:')
        ? `host:${managed.meta.engineId}:${managed.nativeId}`
        : undefined
    const hostEntry = sessionId.startsWith('host:')
      ? this.hostInspector.getHostSession(sessionId)
      : hostAliasId
        ? this.hostInspector.getHostSession(hostAliasId)
        : undefined

    if (!managed && !hostEntry) {
      throw new HubRpcError(ErrorCode.NotFound, `Session "${sessionId}" not found`)
    }

    const baseMeta = hostEntry?.meta ?? (managed ? { ...managed.meta, title: managed.autoTitle } : undefined)
    this.metaStore.rename(sessionId, title, {
      hostAliasId,
      filePath: hostEntry?.filePath,
      nativeId: hostEntry?.nativeId ?? managed?.nativeId,
      baseMeta,
    })

    if (managed) {
      managed.meta = this.decorateManagedMeta(managed)
      this.callbacks.onSessionUpdated(managed.meta)
      return managed.meta
    }
    const updated = this.metaStore.applyToMeta(hostEntry!.meta)
    this.callbacks.onSessionUpdated(updated)
    return updated
  }

  archiveSession(sessionId: string, archived: boolean): SessionMeta {
    const managed = this.sessions.get(sessionId)
    const hostAliasId =
      managed?.nativeId && !sessionId.startsWith('host:')
        ? `host:${managed.meta.engineId}:${managed.nativeId}`
        : undefined
    const hostEntry = sessionId.startsWith('host:')
      ? this.hostInspector.getHostSession(sessionId)
      : hostAliasId
        ? this.hostInspector.getHostSession(hostAliasId)
        : undefined

    if (!managed && !hostEntry) {
      throw new HubRpcError(ErrorCode.NotFound, `Session "${sessionId}" not found`)
    }

    const baseMeta = hostEntry?.meta ?? (managed ? { ...managed.meta, title: managed.autoTitle } : undefined)
    this.metaStore.setArchived(sessionId, archived, {
      hostAliasId,
      filePath: hostEntry?.filePath,
      nativeId: hostEntry?.nativeId ?? managed?.nativeId,
      baseMeta,
    })

    const existingWorktree =
      managed?.meta.worktree ??
      this.metaStore.get(sessionId)?.worktree ??
      (hostAliasId ? this.metaStore.get(hostAliasId)?.worktree : undefined)
    if (archived && existingWorktree && !existingWorktree.cleanedUp) {
      const cleanup = cleanupSessionWorktree(existingWorktree)
      if (cleanup.cleanedUp) {
        const updatedWt: SessionWorktreeInfo = { ...existingWorktree, cleanedUp: true }
        if (managed) {
          managed.meta.worktree = updatedWt
        }
        this.metaStore.setWorktree(sessionId, updatedWt, {
          hostAliasId,
          filePath: hostEntry?.filePath,
          nativeId: hostEntry?.nativeId ?? managed?.nativeId,
          baseMeta,
        })
      }
    }

    this.sweepArchivedAttachments()

    if (managed) {
      managed.meta = this.decorateManagedMeta(managed)
      this.callbacks.onSessionUpdated(managed.meta)
      return managed.meta
    }
    const updated = this.metaStore.applyToMeta(hostEntry!.meta)
    this.callbacks.onSessionUpdated(updated)
    return updated
  }

  async closeSession(sessionId: string): Promise<void> {
    const managed = this.requireSession(sessionId)
    managed.closed = true
    managed.queuedTurns = []
    managed.meta.queuedPrompts = undefined
    await managed.handle.close()
    managed.meta.state = 'closed'
    managed.meta.updatedAt = Date.now()
    this.callbacks.onSessionUpdated(managed.meta)
    this.sessions.delete(sessionId)
    const aliasId =
      managed.nativeId && !sessionId.startsWith('host:')
        ? `host:${managed.meta.engineId}:${managed.nativeId}`
        : undefined
    removeSessionAttachments(sessionId, aliasId)
  }

  private resolveSessionWorkspaceCwd(sessionId: string): string {
    const allowedRoots = [...new Set([...this.workspaceRoots, getManagedWorktreesRoot()])]
    const managed = this.sessions.get(sessionId)
    if (managed) {
      return resolveAllowedWorkspacePath(managed.meta.cwd, allowedRoots)
    }
    if (sessionId.startsWith('host:')) {
      const hostEntry = this.hostInspector.getHostSession(sessionId)
      if (hostEntry?.meta.cwd) {
        return resolveAllowedWorkspacePath(hostEntry.meta.cwd, allowedRoots)
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
    let managed = this.sessions.get(sessionId)
    if (managed && sessionId.startsWith('host:')) {
      const hostProbe = this.hostInspector.readHostSessionEvents(sessionId)
      if (hostProbe) {
        this.releaseIdleHostWrapperIfDiskAdvanced(
          sessionId,
          hostProbe.session.lastSeq,
          hostProbe.session.state,
        )
        managed = this.sessions.get(sessionId)
      }
    }
    if (managed) {
      managed.meta = this.decorateManagedMeta(managed)
      return sliceSessionWindow(managed.meta, managed.events, afterSeq, managed.baseSeq, opts)
    }

    if (sessionId.startsWith('host:')) {
      const hostData = this.hostInspector.readHostSessionEvents(sessionId)
      if (hostData) {
        const baseSeq = hostData.events[0]?.seq ?? 0
        const decoratedSession = this.metaStore.applyToMeta(hostData.session)
        const sliced = sliceSessionWindow(decoratedSession, hostData.events, afterSeq, baseSeq, opts)
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
    this.sweepArchivedAttachments()
    if (!this.archivedAttachmentSweepTimer) {
      this.archivedAttachmentSweepTimer = setInterval(
        () => {
          this.sweepArchivedAttachments()
        },
        6 * 60 * 60 * 1_000,
      )
      this.archivedAttachmentSweepTimer.unref?.()
    }
  }

  stopGlobalHostMonitor(): void {
    this.hostInspector.stopAllWatches()
    if (this.archivedAttachmentSweepTimer) {
      clearInterval(this.archivedAttachmentSweepTimer)
      this.archivedAttachmentSweepTimer = null
    }
  }

  async closeAll(): Promise<void> {
    this.stopGlobalHostMonitor()
    this.sleepInhibitor.dispose()
    const all = [...this.sessions.values()]
    this.sessions.clear()
    await Promise.allSettled(all.map(s => s.handle?.close()))
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
      managed.meta = this.decorateManagedMeta(managed)
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
    managed.meta = this.decorateManagedMeta(managed)
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
  // Walk backward up to 160 events so normal turns keep user question + assistant answer together
  // without ballooning a giant multi-hundred-tool turn into an oversized first-frame payload.
  const floor = Math.max(0, initialIdx - 160)
  for (let i = initialIdx; i >= floor; i--) {
    if (events[i]?.type === 'message.user') {
      return i
    }
  }
  return initialIdx
}
