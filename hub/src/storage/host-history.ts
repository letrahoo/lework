import fs from 'node:fs'
import os from 'node:os'
import type {
  AgentEvent,
  EngineModelSpec,
  ReasoningEffort,
  SessionCancelQueuedResult,
  SessionMeta,
  SessionMode,
  SubagentSummary,
} from '@lework/protocol'
import {
  extractAgyUserRequests,
  filterAgyQueuedFromHistory,
  inferAgySubagentActivity,
  parseAgyTranscript,
  resolveAgyQueuedPrompts,
  resolveAgySubagentEntry,
  scanAgySessions,
} from './host-history/agy-history.ts'
import {
  inferClaudeSubagentActivity,
  parseClaudeTranscript,
  resolveClaudeSubagentEntry,
  scanClaudeSessions,
} from './host-history/claude-history.ts'
import { parseCodexTranscript, scanCodexSessions } from './host-history/codex-history.ts'
import {
  classifyRawToolCall,
  mergeUniqueWorkspaces,
  readFirstAndLastLines,
  type CachedTranscript,
  type HostTranscriptEntry,
  type ParsedHostTranscript,
} from './host-history/common.ts'
import { parseDshTranscript, scanDshSessions } from './host-history/dsh-history.ts'
import {
  loadAgyModelsFromCli,
  readAgyLocalSettings,
  readClaudeLocalSettings,
  readCodexLocalSettings,
  readCodexModelsCache,
  resolveAgyWireModel,
} from './host-history/engine-settings.ts'
import {
  TerminalApprovalError,
  TerminalBridge,
  type ClaudeLiveSessionMeta,
} from './terminal-bridge.ts'
import type { SessionMetadataStore } from './session-metadata.ts'

export { classifyRawToolCall, extractAgyUserRequests, filterAgyQueuedFromHistory, TerminalApprovalError }

const CANCEL_WATERMARK_TTL_MS = 90_000
const CANCEL_POLL_GRACE_MS = 1_800
const CANCEL_SETTLE_WINDOW_MS = 3_000
const AGY_TERMINAL_IDLE_FILE_MS = 4_000
const TERMINAL_ACTIVE_LEASE_MS = 3_500
const LIVE_STATUS_HOLD_MS = 3_200

interface HostCancelWatermark {
  cancelledAt: number
  graceUntil: number
  mtimeMs: number
  size: number
  lastSeq: number
}

interface WatchedHostSession {
  sessionId: string
  filePath: string
  refs: number
  lastEmittedSeq: number
  lastState: string
  lastLiveStatus?: string
  lastNonEmptyLiveStatus?: string
  lastNonEmptyLiveStatusAt: number
  terminalActiveUntil: number
  lastQueuedKey: string
  lastSubagentsKey: string
  lastTerminalPollAt: number
  terminalPolling: Promise<void> | null
  activePermRequestId: string | null
  activePermEvent: Extract<AgentEvent, { type: 'permission.requested' }> | null
  pendingPromptUntil: number
  pendingPromptSeqBaseline: number
  consecutiveIdleTerminalPolls: number
  watcher: fs.FSWatcher | null
}

function buildSubagentsKey(subagents?: SubagentSummary[]): string {
  if (!subagents || subagents.length === 0) return ''
  return subagents
    .map(sa => `${sa.id}:${sa.state}:${sa.statusText ?? ''}:${sa.updatedAt}`)
    .join('\u0000')
}

export interface HostLiveCallbacks {
  onEvents: (events: AgentEvent[]) => void
  onSessionUpdated: (session: SessionMeta) => void
}

export interface HostTaskInspectorOptions {
  homeDir?: string
  terminalBridge?: TerminalBridge
  callbacks?: HostLiveCallbacks
}

export class HostTaskInspector {
  private agyModelsCache: EngineModelSpec[] | null = null
  private agyLoading: Promise<EngineModelSpec[]> | null = null
  private hostIndex = new Map<string, HostTranscriptEntry>()
  private subagentIndex = new Map<string, HostTranscriptEntry>()
  private transcriptCache = new Map<string, CachedTranscript>()
  private watched = new Map<string, WatchedHostSession>()
  private watchPollTimer: ReturnType<typeof setInterval> | null = null
  private globalScanTimer: ReturnType<typeof setInterval> | null = null
  private lastKnownStates = new Map<string, string>()
  private liveCallbacks?: HostLiveCallbacks
  private metaStore?: SessionMetadataStore
  private readonly homeDir: string
  private readonly terminalBridge: TerminalBridge
  private claudeRuntimeMap = new Map<string, ClaudeLiveSessionMeta>()
  private lastTerminalModelKey = new Map<string, string>()
  private suppressedHostQueued = new Map<string, Set<string>>()
  private readonly cancelledWatermarks = new Map<string, HostCancelWatermark>()
  private lastScanAt = 0

  constructor(callbacksOrOpts?: HostLiveCallbacks | HostTaskInspectorOptions) {
    if (callbacksOrOpts && 'onEvents' in callbacksOrOpts && 'onSessionUpdated' in callbacksOrOpts) {
      this.liveCallbacks = callbacksOrOpts
      this.homeDir = os.homedir()
      this.terminalBridge = new TerminalBridge({ homeDir: this.homeDir })
    } else {
      const opts = (callbacksOrOpts ?? {}) as HostTaskInspectorOptions
      this.liveCallbacks = opts.callbacks
      this.homeDir = opts.homeDir ?? os.homedir()
      this.terminalBridge = opts.terminalBridge ?? new TerminalBridge({ homeDir: this.homeDir })
    }
  }

  setCallbacks(callbacks: HostLiveCallbacks): void {
    this.liveCallbacks = callbacks
  }

  setMetaStore(metaStore: SessionMetadataStore): void {
    this.metaStore = metaStore
  }

  getHomeDir(): string {
    return this.homeDir
  }

  getEngineHostDefaults(engineId: string): {
    defaultModel?: string
    defaultMode: SessionMode
    defaultEffort: ReasoningEffort
    supportedModes: SessionMode[]
  } {
    if (engineId === 'claude') {
      const cfg = readClaudeLocalSettings(this.homeDir)
      return {
        defaultModel: cfg.model,
        defaultMode: cfg.defaultMode,
        defaultEffort: cfg.effort,
        supportedModes: ['auto', 'acceptEdits', 'plan', 'default', 'bypassPermissions'],
      }
    }
    if (engineId === 'codex') {
      const cfg = readCodexLocalSettings(this.homeDir)
      return {
        defaultModel: cfg.model,
        defaultMode: cfg.defaultMode,
        defaultEffort: cfg.effort,
        supportedModes: ['default', 'acceptEdits', 'plan', 'bypassPermissions'],
      }
    }
    if (engineId === 'agy') {
      const agyCfg = readAgyLocalSettings(this.homeDir, this.agyModelsCache ?? undefined)
      return {
        defaultModel: agyCfg.model,
        defaultMode: agyCfg.defaultMode,
        defaultEffort: agyCfg.effort,
        supportedModes: ['auto', 'plan', 'default'],
      }
    }
    return {
      defaultMode: 'default',
      defaultEffort: 'medium',
      supportedModes: ['default', 'acceptEdits', 'plan', 'auto'],
    }
  }

  async getModelsForEngine(engineId: string, cpaModels: string[]): Promise<EngineModelSpec[]> {
    const stdEfforts: ReasoningEffort[] = ['low', 'medium', 'high']
    const codexEfforts: ReasoningEffort[] = ['low', 'medium', 'high', 'xhigh']

    if (engineId === 'claude') {
      const claudeCfg = readClaudeLocalSettings(this.homeDir)
      const subModels: EngineModelSpec[] = [
        {
          id: 'claude-sonnet-4-5',
          name: 'Claude Sonnet 4.5',
          providerId: 'sub_claude',
          efforts: stdEfforts,
          defaultEffort: 'medium',
        },
        {
          id: 'claude-opus-4-6',
          name: 'Claude Opus 4.6',
          providerId: 'sub_claude',
          efforts: stdEfforts,
          defaultEffort: 'high',
        },
      ]
      const proxyModels: EngineModelSpec[] = cpaModels
        .filter(m => !m.includes('image'))
        .slice(0, 60)
        .map(m => ({
          id: m,
          name: `${m} (Proxy)`,
          providerId: 'cpa_local',
          efforts: stdEfforts,
          defaultEffort: 'medium' as const,
        }))
      const combined = [...subModels, ...proxyModels]
      if (claudeCfg.model && !combined.some(m => m.id === claudeCfg.model)) {
        combined.unshift({
          id: claudeCfg.model,
          name: claudeCfg.model,
          providerId: claudeCfg.usesLocalProxy ? 'cpa_local' : 'sub_claude',
          efforts: stdEfforts,
          defaultEffort: claudeCfg.effort,
        })
      }
      return combined
    }

    if (engineId === 'codex') {
      const fromCache = readCodexModelsCache(this.homeDir, codexEfforts)
      if (fromCache.length > 0) return fromCache
      return [
        { id: 'gpt-6.2-sol', name: 'GPT-6.2-Sol', providerId: 'sub_codex', efforts: codexEfforts, defaultEffort: 'high' },
        { id: 'gpt-6.2-astra', name: 'GPT-6.2-Astra', providerId: 'sub_codex', efforts: codexEfforts, defaultEffort: 'high' },
        { id: 'gpt-6.1-sol', name: 'GPT-6.1-Sol', providerId: 'sub_codex', efforts: codexEfforts, defaultEffort: 'medium' },
      ]
    }

    if (engineId === 'agy') {
      if (this.agyModelsCache) return this.agyModelsCache
      if (this.agyLoading) return this.agyLoading
      this.agyLoading = loadAgyModelsFromCli().then(specs => {
        this.agyModelsCache = specs
        this.agyLoading = null
        return specs
      })
      return this.agyLoading
    }

    if (engineId === 'pi') {
      const list = cpaModels.filter(m => !m.includes('image')).slice(0, 18)
      if (list.length > 0) {
        return list.map(id => ({
          id,
          name: id,
          providerId: 'cpa_local',
          efforts: stdEfforts,
          defaultEffort: 'medium' as const,
        }))
      }
      return [
        { id: 'claude-opus-4-6-thinking', name: 'claude-opus-4-6-thinking', providerId: 'cpa_local', efforts: stdEfforts, defaultEffort: 'high' },
        { id: 'gpt-6.2-sol', name: 'gpt-6.2-sol', providerId: 'cpa_local', efforts: stdEfforts, defaultEffort: 'medium' },
      ]
    }

    if (engineId === 'dsh') {
      return [
        { id: 'deepseek-chat', name: 'DeepSeek V3 Chat', providerId: 'sub_dsh', efforts: ['medium'], defaultEffort: 'medium' },
        { id: 'deepseek-reasoner', name: 'DeepSeek R1 Reasoner', providerId: 'sub_dsh', efforts: ['medium', 'high'], defaultEffort: 'high' },
      ]
    }

    return [
      { id: 'mock-fast', name: 'Simulator Standard', efforts: stdEfforts, defaultEffort: 'medium' },
    ]
  }

  /**
   * Scans real historical and currently-running tasks across Claude Code, Codex, AGY, and DSH on this computer.
   */
  discoverHostSessions(force = false): SessionMeta[] {
    const now = Date.now()
    if (!force && now - this.lastScanAt < 4_000 && this.hostIndex.size > 0) {
      return [...this.hostIndex.values()]
        .map(v => v.meta)
        .sort((a, b) => b.updatedAt - a.updatedAt)
    }

    const next = new Map<string, HostTranscriptEntry>()
    const nextSubagents = new Map<string, HostTranscriptEntry>()
    scanCodexSessions(this.homeDir, next)
    this.claudeRuntimeMap = this.terminalBridge.readClaudeRuntimeMap()
    scanClaudeSessions(this.homeDir, this.claudeRuntimeMap, next, nextSubagents)
    scanAgySessions(this.homeDir, this.transcriptCache, next, this.agyModelsCache ?? undefined, nextSubagents)
    scanDshSessions(this.homeDir, next)

    // Retain any archived or custom-renamed host sessions even if newer tasks pushed them
    // past the top-20 mtime scan window, without ever modifying or deleting host transcript files.
    if (this.metaStore) {
      for (const { sessionId, record } of this.metaStore.pinnedHostEntries()) {
        if (next.has(sessionId)) continue
        if (record.filePath && record.snapshotMeta && fs.existsSync(record.filePath)) {
          const fallbackNativeId =
            record.nativeId ?? sessionId.split(':').slice(2).join(':')
          next.set(sessionId, {
            nativeId: fallbackNativeId,
            filePath: record.filePath,
            meta: { ...record.snapshotMeta, id: sessionId, state: 'idle' },
          })
        }
      }
    }

    // Reconcile active cancel watermarks BEFORE queued suppression & prev metadata copy so a
    // cancelled session never bounces back to `running` on global scans or `session.list` refreshes.
    for (const [id, entry] of next) {
      if (this.isCancelWatermarkActiveForDiscovery(id, entry, now)) {
        entry.meta = {
          ...entry.meta,
          state: 'idle',
          liveStatusText: undefined,
          turnStartedAt: undefined,
          queuedPrompts: undefined,
        }
      }
    }

    // Apply host-side queued prompt suppression and clear stale suppressions once idle
    for (const [id, entry] of next) {
      const isActive = entry.meta.state === 'running' || entry.meta.state === 'waiting_permission'
      if (!isActive) {
        this.suppressedHostQueued.delete(id)
      } else if (entry.meta.queuedPrompts?.length) {
        entry.meta = {
          ...entry.meta,
          queuedPrompts: this.filterSuppressedQueuedPrompts(id, entry.meta.queuedPrompts),
        }
      }
    }

    // Preserve rich runtime/transcript metadata already parsed for watched or cached sessions
    for (const [id, entry] of next) {
      const prev = this.hostIndex.get(id)
      if (!prev) continue
      const isRich = this.watched.has(id) || this.transcriptCache.has(id)
      if (!isRich) continue
      const mergedWorkspaces = mergeUniqueWorkspaces([
        ...(prev.meta.workspaces ?? []),
        ...(entry.meta.workspaces ?? []),
      ])
      const activeWatch = this.watched.get(id)
      const hasCancelWm = this.cancelledWatermarks.has(id)
      let preservedState = entry.meta.state
      if (activeWatch && !hasCancelWm) {
        if (activeWatch.activePermEvent) {
          preservedState = 'waiting_permission'
        } else if (
          preservedState === 'idle' &&
          (activeWatch.terminalActiveUntil > now || activeWatch.pendingPromptUntil > now)
        ) {
          preservedState = 'running'
        }
      }
      const isActive = preservedState === 'running' || preservedState === 'waiting_permission'
      entry.meta = {
        ...entry.meta,
        state: preservedState,
        cwd: mergedWorkspaces[0] ?? entry.meta.cwd,
        workspaces: mergedWorkspaces.length > 0 ? mergedWorkspaces : entry.meta.workspaces,
        model: prev.meta.model ?? entry.meta.model,
        reasoningEffort: prev.meta.reasoningEffort ?? entry.meta.reasoningEffort,
        lastSeq: prev.meta.lastSeq > 0 ? prev.meta.lastSeq : entry.meta.lastSeq,
        liveStatusText: isActive ? (prev.meta.liveStatusText ?? entry.meta.liveStatusText) : undefined,
        turnStartedAt: isActive ? (prev.meta.turnStartedAt ?? entry.meta.turnStartedAt) : undefined,
      }
    }

    this.hostIndex = next
    this.subagentIndex = nextSubagents
    this.lastScanAt = now

    return [...next.values()]
      .map(v => v.meta)
      .sort((a, b) => b.updatedAt - a.updatedAt)
  }

  isSubagentSession(sessionId: string): boolean {
    if (this.hostIndex.has(sessionId)) return false
    return this.subagentIndex.has(sessionId) || sessionId.startsWith('host:claude-subagent:')
  }

  getDiscoveredSubagents(sessionId: string): SubagentSummary[] | undefined {
    return this.hostIndex.get(sessionId)?.meta.subagents
  }

  private parseTranscriptForEntry(sessionId: string, entry: HostTranscriptEntry): ParsedHostTranscript {
    const claudeCfg =
      entry.meta.engineId === 'claude' ? readClaudeLocalSettings(this.homeDir) : undefined
    return entry.meta.engineId === 'codex'
      ? parseCodexTranscript(sessionId, entry.filePath)
      : entry.meta.engineId === 'claude'
        ? parseClaudeTranscript(sessionId, entry.filePath, claudeCfg?.env)
        : entry.meta.engineId === 'agy'
          ? parseAgyTranscript(sessionId, entry.filePath, this.agyModelsCache ?? undefined)
          : entry.meta.engineId === 'dsh'
            ? parseDshTranscript(sessionId, entry.filePath)
            : { events: [], workspaces: [], isRunning: false, turnStartedAt: undefined }
  }

  private hasPostCancelNewActivity(
    wm: HostCancelWatermark,
    events: AgentEvent[],
    parsedRunning: boolean,
    mtimeMs: number,
    now: number,
  ): boolean {
    const lastSeq = events.length > 0 ? events[events.length - 1]!.seq : -1
    if (lastSeq <= wm.lastSeq) return false
    const newEvents = events.filter(ev => ev.seq > wm.lastSeq)
    // A brand-new user prompt written after cancel always initiates a fresh turn immediately.
    if (newEvents.some(ev => ev.type === 'message.user')) {
      return true
    }
    // Sustained running activity after the settle window (e.g. a non-interrupted process
    // continuing to emit planner/tool steps > 3s after cancel) also supersedes the watermark.
    if (
      parsedRunning &&
      now - wm.cancelledAt > CANCEL_SETTLE_WINDOW_MS &&
      mtimeMs > wm.mtimeMs + CANCEL_SETTLE_WINDOW_MS &&
      newEvents.some(
        ev =>
          ev.type === 'tool.call' ||
          ev.type === 'reasoning.delta' ||
          ev.type === 'message.assistant.delta',
      )
    ) {
      return true
    }
    return false
  }

  private isCancelWatermarkActiveForDiscovery(
    sessionId: string,
    entry: HostTranscriptEntry,
    now: number,
  ): boolean {
    const wm = this.cancelledWatermarks.get(sessionId)
    if (!wm) return false
    if (now - wm.cancelledAt > CANCEL_WATERMARK_TTL_MS) {
      this.cancelledWatermarks.delete(sessionId)
      return false
    }
    if (entry.meta.engineId === 'claude') {
      const rt = this.claudeRuntimeMap.get(entry.nativeId)
      if (
        rt &&
        now >= wm.graceUntil &&
        (rt.status === 'running' || rt.status === 'busy' || rt.waitingFor === 'permission prompt') &&
        (rt.updatedAt ?? 0) > wm.cancelledAt
      ) {
        this.cancelledWatermarks.delete(sessionId)
        return false
      }
    }
    let mtimeMs = wm.mtimeMs
    let size = wm.size
    try {
      const st = fs.statSync(entry.filePath)
      mtimeMs = st.mtimeMs
      size = st.size
    } catch {
      return true
    }
    if (mtimeMs <= wm.mtimeMs && size === wm.size) {
      return true
    }
    const cached = this.transcriptCache.get(sessionId)
    let events: AgentEvent[]
    let parsedRunning: boolean
    if (cached && cached.mtimeMs === mtimeMs && cached.size === size) {
      events = cached.events
      parsedRunning = cached.isRunning
    } else {
      const parsed = this.parseTranscriptForEntry(sessionId, entry)
      events = parsed.events
      parsedRunning = parsed.isRunning
      this.transcriptCache.set(sessionId, {
        mtimeMs,
        size,
        events: parsed.events,
        extraWorkspaces: parsed.workspaces,
        isRunning: parsed.isRunning,
        turnStartedAt: parsed.turnStartedAt,
        queuedPrompts: parsed.queuedPrompts,
        lastUserInputTs: parsed.lastUserInputTs,
        recentUserTexts: parsed.recentUserTexts,
        model: parsed.model,
        reasoningEffort: parsed.reasoningEffort,
      })
    }
    if (this.hasPostCancelNewActivity(wm, events, parsedRunning, mtimeMs, now)) {
      this.cancelledWatermarks.delete(sessionId)
      return false
    }
    return true
  }

  getHostSession(sessionId: string): HostTranscriptEntry | undefined {
    const direct = this.hostIndex.get(sessionId) ?? this.subagentIndex.get(sessionId)
    if (direct) return direct

    this.discoverHostSessions(true)
    const afterScan = this.hostIndex.get(sessionId) ?? this.subagentIndex.get(sessionId)
    if (afterScan) return afterScan

    const fallback =
      resolveClaudeSubagentEntry(this.homeDir, sessionId) ??
      resolveAgySubagentEntry(this.homeDir, sessionId)
    if (fallback) {
      this.subagentIndex.set(sessionId, fallback)
      return fallback
    }
    return undefined
  }

  readHostSessionEvents(
    sessionId: string,
    opts?: { includeTerminalLease?: boolean },
  ): { session: SessionMeta; events: AgentEvent[] } | null {
    const entry = this.getHostSession(sessionId)
    if (!entry) return null

    let st: fs.Stats
    try {
      st = fs.statSync(entry.filePath)
    } catch {
      return null
    }

    const cached = this.transcriptCache.get(sessionId)
    let events: AgentEvent[]
    let extraWorkspaces: string[]
    let parsedRunning: boolean
    let turnStartedAt: number | undefined
    let queuedPrompts: string[] | undefined
    let lastUserInputTs: number | undefined
    let recentUserTexts: string[] | undefined
    let parsedModel: string | undefined
    let parsedEffort: ReasoningEffort | undefined
    if (cached && cached.mtimeMs === st.mtimeMs && cached.size === st.size) {
      events = cached.events
      extraWorkspaces = cached.extraWorkspaces
      parsedRunning = cached.isRunning
      turnStartedAt = cached.turnStartedAt
      queuedPrompts = cached.queuedPrompts
      lastUserInputTs = cached.lastUserInputTs
      recentUserTexts = cached.recentUserTexts
      parsedModel = cached.model
      parsedEffort = cached.reasoningEffort
    } else {
      const parsed = this.parseTranscriptForEntry(sessionId, entry)
      events = parsed.events
      extraWorkspaces = parsed.workspaces
      parsedRunning = parsed.isRunning
      turnStartedAt = parsed.turnStartedAt
      queuedPrompts = parsed.queuedPrompts
      lastUserInputTs = parsed.lastUserInputTs
      recentUserTexts = parsed.recentUserTexts
      parsedModel = parsed.model
      parsedEffort = parsed.reasoningEffort
      this.transcriptCache.set(sessionId, {
        mtimeMs: st.mtimeMs,
        size: st.size,
        events,
        extraWorkspaces,
        isRunning: parsedRunning,
        turnStartedAt,
        queuedPrompts,
        lastUserInputTs,
        recentUserTexts,
        model: parsedModel,
        reasoningEffort: parsedEffort,
      })
    }

    const isSubagent = this.isSubagentSession(sessionId)
    let liveState: SessionMeta['state'] = 'idle'
    if (isSubagent) {
      // Preserve the same terminal evidence as the parent strip, including on live file updates.
      if (entry.meta.updatedAt !== Math.floor(st.mtimeMs) || (cached && cached.size !== st.size)) {
        const claude = entry.meta.engineId === 'claude'
        const { last } = readFirstAndLastLines(entry.filePath, 0, claude ? 20 : 12)
        const activity = (claude ? inferClaudeSubagentActivity : inferAgySubagentActivity)(last, st.mtimeMs)
        liveState = activity.state
        entry.meta.liveStatusText = liveState === 'running' ? activity.statusText : undefined
      } else {
        liveState = entry.meta.state === 'running' && Date.now() - st.mtimeMs >= 90_000 ? 'idle' : entry.meta.state
      }
    } else if (entry.meta.engineId === 'claude') {
      this.claudeRuntimeMap = this.terminalBridge.readClaudeRuntimeMap()
      const rt = this.claudeRuntimeMap.get(entry.nativeId)
      if (rt) {
        if (rt.waitingFor === 'permission prompt') {
          liveState = 'waiting_permission'
        } else if (rt.status === 'running' || rt.status === 'busy') {
          liveState = 'running'
        } else {
          // Explicit idle/waiting in `~/.claude/sessions/*.json` ALWAYS wins over .jsonl mtime
          liveState = 'idle'
        }
      } else {
        // Non-interactive `-p` session without `~/.claude/sessions/*.json`: tight 45s window
        liveState = Date.now() - st.mtimeMs < 45_000 && parsedRunning ? 'running' : 'idle'
      }
    } else {
      liveState = Date.now() - st.mtimeMs < 75_000 && parsedRunning ? 'running' : 'idle'
    }

    const lastSeq = events.length > 0 ? events[events.length - 1]!.seq : -1
    const activeWatch = this.watched.get(sessionId)
    if (activeWatch && activeWatch.pendingPromptUntil > 0) {
      if (lastSeq > activeWatch.pendingPromptSeqBaseline || Date.now() > activeWatch.pendingPromptUntil) {
        activeWatch.pendingPromptUntil = 0
      } else if (liveState === 'idle') {
        liveState = 'running'
      }
    }
    if (
      opts?.includeTerminalLease !== false &&
      activeWatch &&
      liveState === 'idle' &&
      activeWatch.terminalActiveUntil > Date.now()
    ) {
      liveState = 'running'
    }
    if (activeWatch?.activePermEvent) {
      liveState = 'waiting_permission'
    }

    const wm = this.cancelledWatermarks.get(sessionId)
    if (wm) {
      const now = Date.now()
      let watermarkCleared = false
      if (now - wm.cancelledAt > CANCEL_WATERMARK_TTL_MS) {
        this.cancelledWatermarks.delete(sessionId)
        watermarkCleared = true
      } else if (this.hasPostCancelNewActivity(wm, events, parsedRunning, st.mtimeMs, now)) {
        this.cancelledWatermarks.delete(sessionId)
        watermarkCleared = true
      } else if (entry.meta.engineId === 'claude') {
        const rt = this.claudeRuntimeMap.get(entry.nativeId)
        if (
          rt &&
          now >= wm.graceUntil &&
          (rt.status === 'running' || rt.status === 'busy' || rt.waitingFor === 'permission prompt') &&
          (rt.updatedAt ?? 0) > wm.cancelledAt
        ) {
          this.cancelledWatermarks.delete(sessionId)
          watermarkCleared = true
        }
      }
      if (!watermarkCleared) {
        liveState = 'idle'
      }
    }

    // Only surface queuedPrompts when the session is actively running / awaiting permission.
    // Idle sessions cannot have pending queued execution.
    const isSessionBusy = liveState === 'running' || liveState === 'waiting_permission'
    if (!isSessionBusy) {
      queuedPrompts = undefined
      this.suppressedHostQueued.delete(sessionId)
    } else {
      if (entry.meta.engineId === 'agy') {
        queuedPrompts = resolveAgyQueuedPrompts(
          this.homeDir,
          entry.nativeId,
          lastUserInputTs ?? 0,
          recentUserTexts ?? [],
        )
      }
      queuedPrompts = this.filterSuppressedQueuedPrompts(sessionId, queuedPrompts)
    }

    const mergedWorkspaces = mergeUniqueWorkspaces([
      ...(entry.meta.workspaces ?? [entry.meta.cwd]),
      ...extraWorkspaces,
    ])
    const primaryCwd = mergedWorkspaces[0] ?? entry.meta.cwd

    const updatedMeta: SessionMeta = {
      ...entry.meta,
      cwd: primaryCwd,
      workspaces: mergedWorkspaces,
      model: parsedModel ?? entry.meta.model,
      reasoningEffort: parsedEffort ?? entry.meta.reasoningEffort,
      state: liveState,
      liveStatusText:
        liveState === 'running' || liveState === 'waiting_permission'
          ? (activeWatch?.lastLiveStatus ?? entry.meta.liveStatusText)
          : undefined,
      turnStartedAt: liveState === 'running' ? (turnStartedAt ?? Math.floor(st.mtimeMs)) : undefined,
      queuedPrompts: queuedPrompts && queuedPrompts.length > 0 ? queuedPrompts : undefined,
      updatedAt: Math.floor(st.mtimeMs),
      lastSeq,
    }
    entry.meta = updatedMeta
    return { session: updatedMeta, events }
  }

  getActiveHostPermission(sessionId: string): Extract<AgentEvent, { type: 'permission.requested' }> | null {
    return this.watched.get(sessionId)?.activePermEvent ?? null
  }

  async isAgyLockedInInteractiveTerminal(
    nativeId: string,
  ): Promise<{ locked: boolean; hostAppHint?: string }> {
    return this.terminalBridge.checkAgyPresenceLock(nativeId)
  }

  private resolveLiveTerminalModelArg(
    engineId: string,
    modelId: string,
    effort?: ReasoningEffort,
  ): string {
    if (engineId === 'agy') {
      const resolved = resolveAgyWireModel(modelId, effort, this.agyModelsCache ?? undefined)
      return resolved.wireModel ?? modelId
    }
    return modelId
  }

  async trySwitchLiveHostModel(
    sessionId: string,
    modelId: string,
    effort?: ReasoningEffort,
  ): Promise<boolean> {
    const entry = this.getHostSession(sessionId)
    if (!entry) return false
    const engineId = entry.meta.engineId
    if (engineId !== 'claude' && engineId !== 'agy') return false

    const modelArg = this.resolveLiveTerminalModelArg(engineId, modelId, effort)
    if (this.lastTerminalModelKey.get(sessionId) === modelArg) {
      return true
    }

    if (engineId === 'claude') {
      this.claudeRuntimeMap = this.terminalBridge.readClaudeRuntimeMap()
    }
    const claudeRt = engineId === 'claude' ? this.claudeRuntimeMap.get(entry.nativeId) : undefined

    const switched = await this.terminalBridge.sendModelSwitchToSession(
      {
        nativeId: entry.nativeId,
        engineId,
        cwd: entry.meta.cwd,
        title: entry.meta.title,
        claudeRuntime: claudeRt,
      },
      modelArg,
    )
    if (switched) {
      this.lastTerminalModelKey.set(sessionId, modelArg)
      entry.meta = {
        ...entry.meta,
        model: modelId,
        ...(effort ? { reasoningEffort: effort } : {}),
        updatedAt: Date.now(),
      }
    }
    return switched
  }

  /**
   * Attempts to inject a mobile-sent prompt directly into an active `tmux` / `iTerm2` CLI session
   * (`host:agy:*` or `host:claude:*`) so computer terminal and mobile UI stay 100% in sync.
   * Returns `true` if delivered to a live terminal, `false` if the session is closed on the host
   * (so `SessionManager` may safely resume it headlessly), or throws `TerminalApprovalError` if
   * blocked by an active approval menu or an unreachable IDE terminal window.
   */
  async tryPromptLiveHostSession(
    sessionId: string,
    terminalPromptText: string,
    displayUserText: string,
    modelOverride?: {
      modelId?: string
      reasoningEffort?: ReasoningEffort
    },
  ): Promise<boolean> {
    const entry = this.getHostSession(sessionId)
    if (!entry) return false
    const engineId = entry.meta.engineId
    if (engineId !== 'claude' && engineId !== 'agy') {
      return false
    }

    if (engineId === 'claude') {
      this.claudeRuntimeMap = this.terminalBridge.readClaudeRuntimeMap()
    }
    const claudeRt = engineId === 'claude' ? this.claudeRuntimeMap.get(entry.nativeId) : undefined

    if (modelOverride?.modelId) {
      const targetArg = this.resolveLiveTerminalModelArg(
        engineId,
        modelOverride.modelId,
        modelOverride.reasoningEffort ?? entry.meta.reasoningEffort,
      )
      const currentArg =
        this.lastTerminalModelKey.get(sessionId) ??
        (entry.meta.model
          ? this.resolveLiveTerminalModelArg(engineId, entry.meta.model, entry.meta.reasoningEffort)
          : undefined)
      if (targetArg !== currentArg) {
        const switched = await this.terminalBridge
          .sendModelSwitchToSession(
            {
              nativeId: entry.nativeId,
              engineId,
              cwd: entry.meta.cwd,
              title: entry.meta.title,
              claudeRuntime: claudeRt,
            },
            targetArg,
          )
          .catch(() => false)
        if (switched) {
          this.lastTerminalModelKey.set(sessionId, targetArg)
          await new Promise(r => setTimeout(r, 250))
        }
      }
    }

    const delivery = await this.terminalBridge.sendPromptToSession(
      {
        nativeId: entry.nativeId,
        engineId,
        cwd: entry.meta.cwd,
        title: entry.meta.title,
        claudeRuntime: claudeRt,
      },
      terminalPromptText,
    )

    if (!delivery.delivered) {
      if (delivery.reason === 'unreachable_terminal') {
        const where = delivery.hostAppHint ? `${delivery.hostAppHint} 内置终端` : '非 tmux / iTerm2 终端窗口'
        throw new TerminalApprovalError(
          `该会话正在电脑的 ${where} 中打开，暂不支持直接写入终端输入；请在 tmux 或 iTerm2 中运行以实现双向实时同步，或新建会话。`,
        )
      }
      return false
    }

    const snap = this.readHostSessionEvents(sessionId)
    const wasBusy =
      snap?.session.state === 'running' ||
      snap?.session.state === 'waiting_permission' ||
      entry.meta.state === 'running' ||
      entry.meta.state === 'waiting_permission'

    // Clear any prior cancel watermark now that a fresh prompt has been delivered to the terminal.
    this.cancelledWatermarks.delete(sessionId)

    const w = this.watched.get(sessionId)
    const now = Date.now()
    if (w) {
      w.pendingPromptUntil = now + 5_000
      w.pendingPromptSeqBaseline = snap?.session.lastSeq ?? entry.meta.lastSeq
      w.consecutiveIdleTerminalPolls = 0
      w.lastState = 'running'
      if (!wasBusy) {
        w.lastLiveStatus = '已同步至电脑终端，正在响应…'
      }
    }

    if (wasBusy) {
      const suppressed = this.suppressedHostQueued.get(sessionId)
      if (suppressed) {
        suppressed.delete(normalizeQueuedPromptKey(displayUserText))
        if (suppressed.size === 0) {
          this.suppressedHostQueued.delete(sessionId)
        }
      }
    }

    const nextQueued = wasBusy
      ? [...new Set([...(entry.meta.queuedPrompts ?? []), displayUserText])]
      : entry.meta.queuedPrompts

    entry.meta = {
      ...entry.meta,
      state: 'running',
      liveStatusText: wasBusy ? entry.meta.liveStatusText : '已同步至电脑终端，正在响应…',
      turnStartedAt: entry.meta.turnStartedAt ?? now,
      queuedPrompts: nextQueued && nextQueued.length > 0 ? nextQueued : undefined,
      updatedAt: now,
    }
    if (w) {
      w.lastQueuedKey = (entry.meta.queuedPrompts ?? []).join('\u0000')
    }
    this.liveCallbacks?.onSessionUpdated(entry.meta)

    for (const delayMs of [180, 520, 1_200, 2_500]) {
      const t = setTimeout(() => {
        if (!this.watched.has(sessionId)) return
        this.pollWatchedSession(sessionId)
        if (delayMs === 520 || delayMs === 2_500) {
          void this.pollTerminalForWatched(sessionId, false)
        }
      }, delayMs)
      t.unref?.()
    }

    return true
  }

  async cancelLiveHostSession(sessionId: string): Promise<boolean> {
    const entry = this.getHostSession(sessionId)
    if (!entry) return false
    const engineId = entry.meta.engineId
    if (engineId !== 'claude' && engineId !== 'agy') return false
    if (engineId === 'claude') {
      this.claudeRuntimeMap = this.terminalBridge.readClaudeRuntimeMap()
    }
    const claudeRt = engineId === 'claude' ? this.claudeRuntimeMap.get(entry.nativeId) : undefined
    const ok = await this.terminalBridge.sendCancelToSession({
      nativeId: entry.nativeId,
      engineId,
      cwd: entry.meta.cwd,
      title: entry.meta.title,
      claudeRuntime: claudeRt,
    })
    if (ok) {
      const snap = this.readHostSessionEvents(sessionId)
      const now = Date.now()
      let mtimeMs = now
      let size = 0
      try {
        const st = fs.statSync(entry.filePath)
        mtimeMs = st.mtimeMs
        size = st.size
      } catch {
        // ignore stat failure
      }
      const lastSeq = snap?.session.lastSeq ?? entry.meta.lastSeq
      this.cancelledWatermarks.set(sessionId, {
        cancelledAt: now,
        graceUntil: now + CANCEL_POLL_GRACE_MS,
        mtimeMs,
        size,
        lastSeq,
      })

      const w = this.watched.get(sessionId)
      if (w) {
        w.pendingPromptUntil = 0
        w.consecutiveIdleTerminalPolls = 0
        if (w.activePermRequestId) {
          const prevPermId = w.activePermRequestId
          w.activePermRequestId = null
          w.activePermEvent = null
          this.liveCallbacks?.onEvents([
            {
              seq: Math.max(0, lastSeq),
              sessionId,
              ts: now,
              type: 'permission.resolved',
              requestId: prevPermId,
              optionId: null,
              outcome: 'cancelled',
            },
          ])
        }
        w.lastState = 'idle'
        w.lastLiveStatus = undefined
        w.lastNonEmptyLiveStatus = undefined
        w.lastNonEmptyLiveStatusAt = 0
        w.terminalActiveUntil = 0
        w.lastQueuedKey = ''
      }

      entry.meta = {
        ...entry.meta,
        state: 'idle',
        liveStatusText: undefined,
        turnStartedAt: undefined,
        queuedPrompts: undefined,
        updatedAt: now,
      }
      this.liveCallbacks?.onSessionUpdated(entry.meta)

      for (const delayMs of [350, 1_100, 2_200]) {
        const t = setTimeout(() => {
          if (!this.watched.has(sessionId)) return
          this.pollWatchedSession(sessionId)
          void this.pollTerminalForWatched(sessionId, false, true)
        }, delayMs)
        t.unref?.()
      }
    }
    return ok
  }

  cancelQueuedHostPrompt(
    sessionId: string,
    opts?: { index?: number; all?: boolean },
  ): SessionCancelQueuedResult | null {
    const entry = this.getHostSession(sessionId)
    if (!entry) return null

    let current = [...(entry.meta.queuedPrompts ?? [])]
    if (current.length === 0) {
      const loaded = this.readHostSessionEvents(sessionId)
      if (loaded?.session.queuedPrompts?.length) {
        current = [...loaded.session.queuedPrompts]
      }
    }

    let removed: string[] = []
    let remaining: string[] = current
    if (opts?.all === true || opts?.index === undefined) {
      removed = current
      remaining = []
    } else if (opts.index >= 0 && opts.index < current.length) {
      removed = [current[opts.index]!]
      remaining = current.filter((_, i) => i !== opts.index)
    }

    if (removed.length > 0) {
      let suppressed = this.suppressedHostQueued.get(sessionId)
      if (!suppressed) {
        suppressed = new Set<string>()
        this.suppressedHostQueued.set(sessionId, suppressed)
      }
      for (const text of removed) {
        const key = normalizeQueuedPromptKey(text)
        if (key) suppressed.add(key)
      }

      entry.meta = {
        ...entry.meta,
        queuedPrompts: remaining.length > 0 ? remaining : undefined,
        updatedAt: Date.now(),
      }
      const w = this.watched.get(sessionId)
      if (w) {
        w.lastQueuedKey = remaining.join('\u0000')
      }
      this.liveCallbacks?.onSessionUpdated(entry.meta)

      return {
        ok: true,
        queuedPrompts: remaining,
        removedCount: removed.length,
        notice: '已从 Lework 排队列表移除；若消息已写入电脑终端待执行区，请在终端内按需撤销',
      }
    }

    return {
      ok: true,
      queuedPrompts: remaining,
      removedCount: 0,
    }
  }

  private filterSuppressedQueuedPrompts(
    sessionId: string,
    prompts: string[] | undefined,
  ): string[] | undefined {
    if (!prompts || prompts.length === 0) return undefined
    const suppressed = this.suppressedHostQueued.get(sessionId)
    if (!suppressed || suppressed.size === 0) return prompts
    const filtered = prompts.filter(p => !suppressed.has(normalizeQueuedPromptKey(p)))
    return filtered.length > 0 ? filtered : undefined
  }

  async respondHostPermission(
    sessionId: string,
    requestId: string,
    optionId: string | null,
  ): Promise<void> {
    const entry = this.getHostSession(sessionId)
    if (!entry) {
      throw new Error(`Host session "${sessionId}" not found`)
    }
    if (entry.meta.engineId === 'claude') {
      this.claudeRuntimeMap = this.terminalBridge.readClaudeRuntimeMap()
    }
    const claudeRt = entry.meta.engineId === 'claude' ? this.claudeRuntimeMap.get(entry.nativeId) : undefined
    const sent = await this.terminalBridge.sendPermissionKeystroke(
      {
        nativeId: entry.nativeId,
        engineId: entry.meta.engineId,
        cwd: entry.meta.cwd,
        title: entry.meta.title,
        claudeRuntime: claudeRt,
      },
      optionId,
      requestId,
    )
    if (!sent) {
      throw new Error('未找到对应的本机 iTerm2 或 tmux 终端窗口，请在电脑终端确认')
    }

    this.cancelledWatermarks.delete(sessionId)
    const w = this.watched.get(sessionId)
    const resolvedId = w?.activePermRequestId ?? requestId
    if (w) {
      w.activePermRequestId = null
      w.activePermEvent = null
      w.consecutiveIdleTerminalPolls = 0
      w.lastState = 'running'
    }
    this.liveCallbacks?.onEvents([
      {
        seq: Math.max(0, entry.meta.lastSeq),
        sessionId,
        ts: Date.now(),
        type: 'permission.resolved',
        requestId: resolvedId,
        optionId,
        outcome: optionId === null ? 'cancelled' : 'selected',
      },
    ])
    entry.meta = { ...entry.meta, state: 'running', liveStatusText: undefined }
    this.liveCallbacks?.onSessionUpdated(entry.meta)
    const t = setTimeout(() => {
      this.pollWatchedSession(sessionId)
      void this.pollTerminalForWatched(sessionId, true)
    }, 320)
    t.unref?.()
  }

  async syncTerminalNow(sessionId: string): Promise<void> {
    if (!this.watched.has(sessionId) || this.isSubagentSession(sessionId)) return
    await this.pollTerminalForWatched(sessionId, true)
  }

  retainWatch(sessionId: string): void {
    const existing = this.watched.get(sessionId)
    if (existing) {
      existing.refs++
      // Re-push active terminal permission or live status immediately for re-subscribing clients
      setTimeout(() => {
        if (existing.activePermEvent) {
          this.liveCallbacks?.onEvents([existing.activePermEvent])
        }
        void this.pollTerminalForWatched(sessionId, true)
      }, 40)
      return
    }
    const snap = this.readHostSessionEvents(sessionId)
    const entry = this.hostIndex.get(sessionId) ?? this.subagentIndex.get(sessionId)
    if (!snap || !entry) return

    const isSubagent = this.isSubagentSession(sessionId)
    const record: WatchedHostSession = {
      sessionId,
      filePath: entry.filePath,
      refs: 1,
      lastEmittedSeq: snap.session.lastSeq,
      lastState: snap.session.state,
      lastLiveStatus: snap.session.liveStatusText,
      lastNonEmptyLiveStatus: snap.session.liveStatusText,
      lastNonEmptyLiveStatusAt: snap.session.liveStatusText ? Date.now() : 0,
      terminalActiveUntil: 0,
      lastQueuedKey: (snap.session.queuedPrompts ?? []).join('\u0000'),
      lastSubagentsKey: buildSubagentsKey(snap.session.subagents),
      lastTerminalPollAt: 0,
      terminalPolling: null,
      activePermRequestId: null,
      activePermEvent: null,
      pendingPromptUntil: 0,
      pendingPromptSeqBaseline: snap.session.lastSeq,
      consecutiveIdleTerminalPolls: 0,
      watcher: null,
    }
    try {
      record.watcher = fs.watch(entry.filePath, { persistent: false }, () => {
        this.pollWatchedSession(sessionId)
      })
      record.watcher.on('error', () => {
        // Fallback interval continues polling if inode rotates
      })
    } catch {
      // Fallback interval handles environments without fs.watch
    }
    this.watched.set(sessionId, record)
    this.ensureWatchTimer()
    // Immediately inspect live terminal (e.g. `· Sketching…` or pending permission dialog)
    if (!isSubagent) {
      setTimeout(() => void this.pollTerminalForWatched(sessionId, true), 30)
    }
  }

  releaseWatch(sessionId: string): void {
    const existing = this.watched.get(sessionId)
    if (!existing) return
    existing.refs--
    if (existing.refs <= 0) {
      try {
        existing.watcher?.close()
      } catch {
        // ignore
      }
      this.watched.delete(sessionId)
      if (this.watched.size === 0 && this.watchPollTimer) {
        clearInterval(this.watchPollTimer)
        this.watchPollTimer = null
      }
    }
  }

  startGlobalMonitor(): void {
    if (this.globalScanTimer) return
    this.globalScanTimer = setInterval(() => {
      const sessions = this.discoverHostSessions(true)
      for (const s of sessions) {
        const qKey = (s.queuedPrompts ?? []).join('|')
        const subKey = buildSubagentsKey(s.subagents)
        const key = `${s.state}:${s.updatedAt}:${s.title}:${qKey}:${subKey}`
        const prev = this.lastKnownStates.get(s.id)
        // Watched sessions are already polled every 350ms with full transcript + terminal state;
        // do not let header/tail global scans clobber their rich SessionMeta broadcasts.
        if (prev !== undefined && prev !== key && !this.watched.has(s.id)) {
          this.liveCallbacks?.onSessionUpdated(s)
        }
        this.lastKnownStates.set(s.id, key)
      }
    }, 2_500)
    this.globalScanTimer.unref()
  }

  stopAllWatches(): void {
    if (this.globalScanTimer) {
      clearInterval(this.globalScanTimer)
      this.globalScanTimer = null
    }
    if (this.watchPollTimer) {
      clearInterval(this.watchPollTimer)
      this.watchPollTimer = null
    }
    for (const w of this.watched.values()) {
      try {
        w.watcher?.close()
      } catch {
        // ignore
      }
    }
    this.watched.clear()
  }

  private ensureWatchTimer(): void {
    if (this.watchPollTimer) return
    this.watchPollTimer = setInterval(() => {
      for (const sid of this.watched.keys()) {
        this.pollWatchedSession(sid)
      }
    }, 350)
    this.watchPollTimer.unref()
  }

  private pollWatchedSession(sessionId: string): void {
    const w = this.watched.get(sessionId)
    if (!w) return
    const loaded = this.readHostSessionEvents(sessionId)
    if (!loaded) return

    const { session, events } = loaded
    if (session.lastSeq > w.lastEmittedSeq) {
      const delta = events.filter(ev => ev.seq > w.lastEmittedSeq)
      if (delta.length > 0) {
        w.lastEmittedSeq = delta[delta.length - 1]!.seq
        this.liveCallbacks?.onEvents(delta)
      }
    }
    if (this.isSubagentSession(sessionId)) {
      w.lastState = session.state
      return
    }
    const nextQueuedKey = (session.queuedPrompts ?? []).join('\u0000')
    const nextSubagentsKey = buildSubagentsKey(session.subagents)
    if (
      session.state !== w.lastState ||
      nextQueuedKey !== w.lastQueuedKey ||
      nextSubagentsKey !== w.lastSubagentsKey
    ) {
      w.lastState = session.state
      w.lastQueuedKey = nextQueuedKey
      w.lastSubagentsKey = nextSubagentsKey
      this.liveCallbacks?.onSessionUpdated(session)
    }

    const now = Date.now()
    const minIntervalMs =
      session.state === 'idle' && !w.activePermRequestId ? 6_000 : 1_400
    if (now - w.lastTerminalPollAt >= minIntervalMs) {
      void this.pollTerminalForWatched(sessionId, false)
    }
  }

  private async pollTerminalForWatched(
    sessionId: string,
    forcePush: boolean,
    forceInspect = forcePush,
  ): Promise<void> {
    const w = this.watched.get(sessionId)
    const entry = this.hostIndex.get(sessionId)
    if (!w || !entry) return
    if (w.terminalPolling) {
      await w.terminalPolling
      return
    }
    w.lastTerminalPollAt = Date.now()

    const task = (async () => {
      const engineId = entry.meta.engineId
      if (engineId !== 'claude' && engineId !== 'agy') {
        return
      }

      let claudeRt: ClaudeLiveSessionMeta | undefined
      if (engineId === 'claude') {
        this.claudeRuntimeMap = this.terminalBridge.readClaudeRuntimeMap()
        claudeRt = this.claudeRuntimeMap.get(entry.nativeId)
        if (!claudeRt) {
          if (w.activePermRequestId) {
            const prevId = w.activePermRequestId
            w.activePermRequestId = null
            w.activePermEvent = null
            this.liveCallbacks?.onEvents([
              {
                seq: Math.max(0, entry.meta.lastSeq),
                sessionId,
                ts: Date.now(),
                type: 'permission.resolved',
                requestId: prevId,
                optionId: null,
                outcome: 'selected',
              },
            ])
          }
          return
        }
        // Zero-overhead fast path: when Claude is idle with no pending permission prompt, skip screen capture on background ticks!
        if (claudeRt.status === 'idle' && !claudeRt.waitingFor && !w.activePermRequestId && !forceInspect) {
          return
        }
      } else if (
        engineId === 'agy' &&
        entry.meta.state === 'idle' &&
        !w.activePermRequestId &&
        w.pendingPromptUntil === 0 &&
        !forceInspect
      ) {
        return
      }

      const inspected = await this.terminalBridge.inspectSessionTerminal({
        nativeId: entry.nativeId,
        engineId,
        cwd: entry.meta.cwd,
        title: entry.meta.title,
        claudeRuntime: claudeRt,
      })
      if (!this.watched.has(sessionId)) return

      const liveMode = inspected?.liveMode ?? entry.meta.mode
      let perm = inspected?.pendingPermission
      const spinner = inspected?.liveStatusText
      const hasCancelHint = Boolean(inspected?.hasCancelHint)

      // Re-read canonical transcript state after async terminal inspection so an already-finished
      // (`idle`) transcript is never reverted back to `running`, and any post-cancel new user turn
      // clears the cancel watermark immediately.
      const freshTranscriptState =
        this.readHostSessionEvents(sessionId, { includeTerminalLease: false })?.session.state ?? entry.meta.state
      const nowPoll = Date.now()
      let activeWm = this.cancelledWatermarks.get(sessionId)
      if (activeWm && nowPoll - activeWm.cancelledAt > CANCEL_WATERMARK_TTL_MS) {
        this.cancelledWatermarks.delete(sessionId)
        activeWm = undefined
      }
      const inCancelGrace = Boolean(activeWm && nowPoll < activeWm.graceUntil)

      // Guaranteed closure invariant: when Claude's runtime metadata explicitly reports
      // `waitingFor === 'permission prompt'`, NEVER leave the mobile UI without an actionable or explanatory banner!
      // Suppress stale pre-cancel runtime metadata while a cancel watermark is still active.
      const suppressStaleClaudePerm = Boolean(
        activeWm && (inCancelGrace || (claudeRt?.updatedAt ?? 0) <= activeWm.cancelledAt),
      )
      if (!perm && claudeRt?.waitingFor === 'permission prompt' && !suppressStaleClaudePerm) {
        if (inspected?.targetReachable) {
          perm = {
            requestId: `tty_perm_fallback_${entry.nativeId.slice(0, 10)}`,
            title: 'Agent 请求执行确认\n终端提示内容较长，若点击未能自动确认请切回电脑终端查看详情。',
            options: [
              { id: 'tty_key:1', name: '1. 允许执行', kind: 'allow_once' },
              { id: 'tty_key:2', name: '2. 拒绝', kind: 'reject_once' },
            ],
          }
        } else {
          const appName = inspected?.hostAppHint
          perm = {
            requestId: `tty_unmanaged_${entry.nativeId.slice(0, 10)}`,
            title: appName
              ? `Agent 正在等待确认\n该会话在 ${appName} 内置终端中运行，暂不支持手机代点，请切回电脑 ${appName} 窗口确认。`
              : 'Agent 正在等待确认\n该会话不在 iTerm2 或 tmux 窗口中运行，暂不支持手机代点，请回电脑对应窗口确认。',
            options: [],
          }
        }
      }

      if (activeWm) {
        if (inCancelGrace) {
          perm = undefined
        } else {
          const claudeFreshlyActive =
            engineId === 'claude' &&
            Boolean(claudeRt) &&
            (claudeRt!.status === 'running' ||
              claudeRt!.status === 'busy' ||
              claudeRt!.waitingFor === 'permission prompt') &&
            (claudeRt!.updatedAt ?? 0) > activeWm.cancelledAt
          if (Boolean(perm) || Boolean(spinner) || hasCancelHint || claudeFreshlyActive) {
            this.cancelledWatermarks.delete(sessionId)
            activeWm = undefined
          }
        }
      }

      if (perm) {
        w.consecutiveIdleTerminalPolls = 0
        w.terminalActiveUntil = nowPoll + TERMINAL_ACTIVE_LEASE_MS
        w.lastNonEmptyLiveStatus = undefined
        w.lastNonEmptyLiveStatusAt = 0
        entry.meta = {
          ...entry.meta,
          state: 'waiting_permission',
          mode: liveMode,
          liveStatusText: '等待执行审批…',
        }
        if (w.activePermRequestId !== perm.requestId || forcePush) {
          const prevPermId = w.activePermRequestId
          w.activePermRequestId = perm.requestId
          const permEv: Extract<AgentEvent, { type: 'permission.requested' }> = {
            seq: Math.max(0, entry.meta.lastSeq),
            sessionId,
            ts: Date.now(),
            type: 'permission.requested',
            requestId: perm.requestId,
            title: perm.title,
            options: perm.options,
          }
          w.activePermEvent = permEv
          const outEvents: AgentEvent[] = []
          if (prevPermId && prevPermId !== perm.requestId) {
            outEvents.push({
              seq: Math.max(0, entry.meta.lastSeq),
              sessionId,
              ts: Date.now(),
              type: 'permission.resolved',
              requestId: prevPermId,
              optionId: null,
              outcome: 'cancelled',
            })
          }
          outEvents.push(permEv)
          this.liveCallbacks?.onEvents(outEvents)
        }
        if (w.lastState !== 'waiting_permission' || w.lastLiveStatus !== entry.meta.liveStatusText || forcePush) {
          w.lastState = 'waiting_permission'
          w.lastLiveStatus = entry.meta.liveStatusText
          this.liveCallbacks?.onSessionUpdated(entry.meta)
        }
        return
      }

      // If a terminal permission prompt was previously open and is now dismissed (e.g. approved or cancelled)
      if (w.activePermRequestId) {
        const prevId = w.activePermRequestId
        w.activePermRequestId = null
        w.activePermEvent = null
        this.liveCallbacks?.onEvents([
          {
            seq: Math.max(0, entry.meta.lastSeq),
            sessionId,
            ts: Date.now(),
            type: 'permission.resolved',
            requestId: prevId,
            optionId: null,
            outcome: activeWm ? 'cancelled' : 'selected',
          },
        ])
      }

      if (activeWm) {
        w.consecutiveIdleTerminalPolls = 0
        w.terminalActiveUntil = 0
        w.lastNonEmptyLiveStatus = undefined
        w.lastNonEmptyLiveStatusAt = 0
        const nextState: SessionMeta['state'] = 'idle'
        if (
          w.lastLiveStatus !== undefined ||
          nextState !== w.lastState ||
          liveMode !== entry.meta.mode ||
          forcePush
        ) {
          w.lastLiveStatus = undefined
          w.lastState = nextState
          w.lastQueuedKey = ''
          entry.meta = {
            ...entry.meta,
            state: nextState,
            mode: liveMode,
            liveStatusText: undefined,
            turnStartedAt: undefined,
            queuedPrompts: undefined,
          }
          this.liveCallbacks?.onSessionUpdated(entry.meta)
        }
        return
      }

      let terminalConfirmedIdle = false
      if (engineId === 'agy') {
        if (inspected?.targetReachable && !spinner && !hasCancelHint && w.pendingPromptUntil === 0) {
          w.consecutiveIdleTerminalPolls++
          if (freshTranscriptState === 'running' && w.consecutiveIdleTerminalPolls >= 2) {
            try {
              const st = fs.statSync(entry.filePath)
              if (Date.now() - st.mtimeMs >= AGY_TERMINAL_IDLE_FILE_MS) {
                terminalConfirmedIdle = true
                this.cancelledWatermarks.set(sessionId, {
                  cancelledAt: Date.now(),
                  graceUntil: 0,
                  mtimeMs: st.mtimeMs,
                  size: st.size,
                  lastSeq: entry.meta.lastSeq,
                })
              }
            } catch {
              // ignore
            }
          }
        } else {
          w.consecutiveIdleTerminalPolls = 0
        }
      }

      const nextState: SessionMeta['state'] =
        engineId === 'claude'
          ? claudeRt?.waitingFor === 'permission prompt'
            ? 'waiting_permission'
            : claudeRt?.status === 'running' || claudeRt?.status === 'busy' || Boolean(spinner) || hasCancelHint
              ? 'running'
              : 'idle'
          : terminalConfirmedIdle
            ? 'idle'
            : Boolean(spinner) || hasCancelHint || freshTranscriptState === 'running'
              ? 'running'
              : 'idle'

      // Hold a short lease when the live terminal explicitly confirms activity (`spinner` / `esc to cancel`)
      // so the 350ms file poll and 2.5s global scan do not bounce `running <-> idle` between terminal polls.
      if (nextState === 'running' && (Boolean(spinner) || hasCancelHint)) {
        w.terminalActiveUntil = nowPoll + TERMINAL_ACTIVE_LEASE_MS
      } else {
        w.terminalActiveUntil = 0
      }

      let effectiveLiveStatus: string | undefined
      if (nextState === 'running') {
        if (spinner) {
          w.lastNonEmptyLiveStatus = spinner
          w.lastNonEmptyLiveStatusAt = nowPoll
          effectiveLiveStatus = spinner
        } else if (
          w.lastNonEmptyLiveStatus &&
          nowPoll - w.lastNonEmptyLiveStatusAt < LIVE_STATUS_HOLD_MS
        ) {
          effectiveLiveStatus = w.lastNonEmptyLiveStatus
        } else {
          w.lastNonEmptyLiveStatus = undefined
          w.lastNonEmptyLiveStatusAt = 0
          effectiveLiveStatus = undefined
        }
      } else {
        w.lastNonEmptyLiveStatus = undefined
        w.lastNonEmptyLiveStatusAt = 0
        effectiveLiveStatus = undefined
      }

      if (
        effectiveLiveStatus !== w.lastLiveStatus ||
        nextState !== w.lastState ||
        liveMode !== entry.meta.mode ||
        forcePush
      ) {
        w.lastLiveStatus = effectiveLiveStatus
        w.lastState = nextState
        if (nextState === 'idle') {
          w.lastQueuedKey = ''
        }
        entry.meta = {
          ...entry.meta,
          state: nextState,
          mode: liveMode,
          liveStatusText: effectiveLiveStatus,
          ...(nextState === 'idle' ? { turnStartedAt: undefined, queuedPrompts: undefined } : {}),
        }
        this.liveCallbacks?.onSessionUpdated(entry.meta)
      }
    })()

    w.terminalPolling = task
    try {
      await task
    } finally {
      if (w.terminalPolling === task) {
        w.terminalPolling = null
      }
    }
  }
}

function normalizeQueuedPromptKey(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}
