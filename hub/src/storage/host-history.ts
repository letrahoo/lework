import { execFile } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { promisify } from 'node:util'
import zlib from 'node:zlib'
import type {
  AgentEvent,
  EngineModelSpec,
  ReasoningEffort,
  SessionMeta,
  SessionMode,
} from '@qprc/protocol'
import { findExecutable } from '../platform/index.ts'
import { TerminalBridge, type ClaudeLiveSessionMeta } from './terminal-bridge.ts'

const execFileAsync = promisify(execFile)

interface HostTranscriptEntry {
  meta: SessionMeta
  filePath: string
  nativeId: string
}

interface CachedTranscript {
  mtimeMs: number
  size: number
  events: AgentEvent[]
  extraWorkspaces: string[]
  isRunning: boolean
  turnStartedAt?: number
  queuedPrompts?: string[]
  lastUserInputTs?: number
  recentUserTexts?: string[]
}

interface WatchedHostSession {
  sessionId: string
  filePath: string
  refs: number
  lastEmittedSeq: number
  lastState: string
  lastLiveStatus?: string
  lastQueuedKey: string
  lastTerminalPollAt: number
  terminalPolling: boolean
  activePermRequestId: string | null
  activePermEvent: Extract<AgentEvent, { type: 'permission.requested' }> | null
  watcher: fs.FSWatcher | null
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
  private transcriptCache = new Map<string, CachedTranscript>()
  private watched = new Map<string, WatchedHostSession>()
  private watchPollTimer: ReturnType<typeof setInterval> | null = null
  private globalScanTimer: ReturnType<typeof setInterval> | null = null
  private lastKnownStates = new Map<string, string>()
  private liveCallbacks?: HostLiveCallbacks
  private readonly homeDir: string
  private readonly terminalBridge: TerminalBridge
  private claudeRuntimeMap = new Map<string, ClaudeLiveSessionMeta>()
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
      return {
        defaultMode: 'auto',
        defaultEffort: 'high',
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
        .slice(0, 12)
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
          name: `${claudeCfg.model} (本机当前)`,
          providerId: claudeCfg.usesLocalProxy ? 'cpa_local' : 'sub_claude',
          efforts: stdEfforts,
          defaultEffort: claudeCfg.effort,
        })
      }
      return combined
    }

    if (engineId === 'codex') {
      const fromCache = this.readCodexModelsCache(codexEfforts)
      if (fromCache.length > 0) return fromCache
      return [
        { id: 'gpt-6.2-sol', name: 'GPT-6.2-Sol', providerId: 'sub_codex', efforts: codexEfforts, defaultEffort: 'high' },
        { id: 'gpt-6.2-astra', name: 'GPT-6.2-Astra', providerId: 'sub_codex', efforts: codexEfforts, defaultEffort: 'high' },
        { id: 'gpt-6.1-sol', name: 'GPT-6.1-Sol', providerId: 'sub_codex', efforts: codexEfforts, defaultEffort: 'medium' },
      ]
    }

    if (engineId === 'agy') {
      return await this.loadAgyModels()
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
   * Scans real historical and currently-running tasks across Claude Code, Codex, and AGY on this computer.
   */
  discoverHostSessions(force = false): SessionMeta[] {
    const now = Date.now()
    if (!force && now - this.lastScanAt < 4_000 && this.hostIndex.size > 0) {
      return [...this.hostIndex.values()]
        .map(v => v.meta)
        .sort((a, b) => b.updatedAt - a.updatedAt)
    }

    const next = new Map<string, HostTranscriptEntry>()
    this.scanCodexSessions(next)
    this.scanClaudeSessions(next)
    this.scanAgySessions(next)
    this.scanDshSessions(next)

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
      const isActive = entry.meta.state === 'running' || entry.meta.state === 'waiting_permission'
      entry.meta = {
        ...entry.meta,
        cwd: mergedWorkspaces[0] ?? entry.meta.cwd,
        workspaces: mergedWorkspaces.length > 0 ? mergedWorkspaces : entry.meta.workspaces,
        lastSeq: prev.meta.lastSeq > 0 ? prev.meta.lastSeq : entry.meta.lastSeq,
        liveStatusText: isActive ? (prev.meta.liveStatusText ?? entry.meta.liveStatusText) : undefined,
        turnStartedAt: isActive ? (prev.meta.turnStartedAt ?? entry.meta.turnStartedAt) : undefined,
      }
    }

    this.hostIndex = next
    this.lastScanAt = now

    return [...next.values()]
      .map(v => v.meta)
      .sort((a, b) => b.updatedAt - a.updatedAt)
  }

  getHostSession(sessionId: string): HostTranscriptEntry | undefined {
    if (!this.hostIndex.has(sessionId)) {
      this.discoverHostSessions(true)
    }
    return this.hostIndex.get(sessionId)
  }

  readHostSessionEvents(sessionId: string): { session: SessionMeta; events: AgentEvent[] } | null {
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
    if (cached && cached.mtimeMs === st.mtimeMs && cached.size === st.size) {
      events = cached.events
      extraWorkspaces = cached.extraWorkspaces
      parsedRunning = cached.isRunning
      turnStartedAt = cached.turnStartedAt
      queuedPrompts = cached.queuedPrompts
      lastUserInputTs = cached.lastUserInputTs
      recentUserTexts = cached.recentUserTexts
    } else {
      const parsed: {
        events: AgentEvent[]
        workspaces: string[]
        isRunning: boolean
        turnStartedAt?: number
        queuedPrompts?: string[]
        lastUserInputTs?: number
        recentUserTexts?: string[]
      } =
        entry.meta.engineId === 'codex'
          ? this.parseCodexTranscript(sessionId, entry.filePath)
          : entry.meta.engineId === 'claude'
            ? this.parseClaudeTranscript(sessionId, entry.filePath)
            : entry.meta.engineId === 'agy'
              ? this.parseAgyTranscript(sessionId, entry.filePath)
              : entry.meta.engineId === 'dsh'
                ? this.parseDshTranscript(sessionId, entry.filePath)
                : { events: [], workspaces: [], isRunning: false, turnStartedAt: undefined }
      events = parsed.events
      extraWorkspaces = parsed.workspaces
      parsedRunning = parsed.isRunning
      turnStartedAt = parsed.turnStartedAt
      queuedPrompts = parsed.queuedPrompts
      lastUserInputTs = parsed.lastUserInputTs
      recentUserTexts = parsed.recentUserTexts
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
      })
    }

    let liveState: SessionMeta['state'] = 'idle'
    if (entry.meta.engineId === 'claude') {
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

    const activeWatch = this.watched.get(sessionId)
    if (activeWatch?.activePermEvent) {
      liveState = 'waiting_permission'
    }

    // Only surface queuedPrompts when the session is actively running / awaiting permission.
    // Idle sessions cannot have pending queued execution.
    const isSessionBusy = liveState === 'running' || liveState === 'waiting_permission'
    if (!isSessionBusy) {
      queuedPrompts = undefined
    } else if (entry.meta.engineId === 'agy') {
      queuedPrompts = resolveAgyQueuedPrompts(
        this.homeDir,
        entry.nativeId,
        lastUserInputTs ?? 0,
        recentUserTexts ?? [],
      )
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
      state: liveState,
      liveStatusText: activeWatch?.lastLiveStatus ?? entry.meta.liveStatusText,
      turnStartedAt: liveState === 'running' ? (turnStartedAt ?? Math.floor(st.mtimeMs)) : undefined,
      queuedPrompts: queuedPrompts && queuedPrompts.length > 0 ? queuedPrompts : undefined,
      updatedAt: Math.floor(st.mtimeMs),
      lastSeq: events.length > 0 ? events[events.length - 1]!.seq : -1,
    }
    entry.meta = updatedMeta
    return { session: updatedMeta, events }
  }

  getActiveHostPermission(sessionId: string): Extract<AgentEvent, { type: 'permission.requested' }> | null {
    return this.watched.get(sessionId)?.activePermEvent ?? null
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
    )
    if (!sent) {
      throw new Error('未找到对应的本机 iTerm2 或 tmux 终端窗口，请在电脑终端确认')
    }

    const w = this.watched.get(sessionId)
    const resolvedId = w?.activePermRequestId ?? requestId
    if (w) {
      w.activePermRequestId = null
      w.activePermEvent = null
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
    setTimeout(() => this.pollWatchedSession(sessionId), 400)
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
    const entry = this.hostIndex.get(sessionId)
    if (!snap || !entry) return

    const record: WatchedHostSession = {
      sessionId,
      filePath: entry.filePath,
      refs: 1,
      lastEmittedSeq: snap.session.lastSeq,
      lastState: snap.session.state,
      lastLiveStatus: snap.session.liveStatusText,
      lastQueuedKey: (snap.session.queuedPrompts ?? []).join('\u0000'),
      lastTerminalPollAt: 0,
      terminalPolling: false,
      activePermRequestId: null,
      activePermEvent: null,
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
    setTimeout(() => void this.pollTerminalForWatched(sessionId, true), 30)
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
        const key = `${s.state}:${s.updatedAt}:${s.title}:${qKey}`
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
    const nextQueuedKey = (session.queuedPrompts ?? []).join('\u0000')
    if (session.state !== w.lastState || nextQueuedKey !== w.lastQueuedKey) {
      w.lastState = session.state
      w.lastQueuedKey = nextQueuedKey
      this.liveCallbacks?.onSessionUpdated(session)
    }

    const now = Date.now()
    const minIntervalMs =
      session.state === 'idle' && !w.activePermRequestId ? 6_000 : 1_400
    if (now - w.lastTerminalPollAt >= minIntervalMs) {
      void this.pollTerminalForWatched(sessionId, false)
    }
  }

  private async pollTerminalForWatched(sessionId: string, forcePush: boolean): Promise<void> {
    const w = this.watched.get(sessionId)
    const entry = this.hostIndex.get(sessionId)
    if (!w || !entry || w.terminalPolling) return
    w.terminalPolling = true
    w.lastTerminalPollAt = Date.now()

    try {
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
        if (claudeRt.status === 'idle' && !claudeRt.waitingFor && !w.activePermRequestId && !forcePush) {
          return
        }
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

      // Guaranteed closure invariant: when Claude's runtime metadata explicitly reports
      // `waitingFor === 'permission prompt'`, NEVER leave the mobile UI without an actionable or explanatory banner!
      if (!perm && claudeRt?.waitingFor === 'permission prompt') {
        if (inspected?.targetReachable) {
          perm = {
            requestId: `tty_perm_fallback_${entry.nativeId.slice(0, 10)}`,
            title: 'Agent 请求执行确认',
            options: [
              { id: 'tty_key:1', name: '1. 允许执行', kind: 'allow_once' },
              { id: 'tty_key:2', name: '2. 始终允许', kind: 'allow_always' },
              { id: 'tty_key:3', name: '3. 拒绝', kind: 'reject_once' },
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

      if (perm) {
        entry.meta = {
          ...entry.meta,
          state: 'waiting_permission',
          mode: liveMode,
          liveStatusText: '等待执行审批…',
        }
        if (w.activePermRequestId !== perm.requestId || forcePush) {
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
          this.liveCallbacks?.onEvents([permEv])
        }
        if (w.lastState !== 'waiting_permission' || w.lastLiveStatus !== entry.meta.liveStatusText || forcePush) {
          w.lastState = 'waiting_permission'
          w.lastLiveStatus = entry.meta.liveStatusText
          this.liveCallbacks?.onSessionUpdated(entry.meta)
        }
        return
      }

      // If a terminal permission prompt was previously open and is now dismissed (e.g. approved on Mac)
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

      const spinner = inspected?.liveStatusText
      const nextState: SessionMeta['state'] =
        engineId === 'claude'
          ? claudeRt?.waitingFor === 'permission prompt'
            ? 'waiting_permission'
            : claudeRt?.status === 'running' || claudeRt?.status === 'busy' || Boolean(spinner)
              ? 'running'
              : 'idle'
          : Boolean(spinner) || entry.meta.state === 'running'
            ? 'running'
            : 'idle'

      if (
        spinner !== w.lastLiveStatus ||
        nextState !== w.lastState ||
        liveMode !== entry.meta.mode ||
        forcePush
      ) {
        w.lastLiveStatus = spinner
        w.lastState = nextState
        entry.meta = {
          ...entry.meta,
          state: nextState,
          mode: liveMode,
          liveStatusText: nextState === 'running' ? spinner : undefined,
        }
        this.liveCallbacks?.onSessionUpdated(entry.meta)
      }
    } finally {
      w.terminalPolling = false
    }
  }

  private readCodexModelsCache(efforts: ReasoningEffort[]): EngineModelSpec[] {
    const p = path.join(this.homeDir, '.codex', 'models_cache.json')
    if (!fs.existsSync(p)) return []
    try {
      const data = JSON.parse(fs.readFileSync(p, 'utf8')) as {
        models?: Array<{ slug?: string; id?: string; display_name?: string }>
      }
      const out: EngineModelSpec[] = []
      for (const m of (data.models ?? []).slice(0, 15)) {
        const id = m.slug ?? m.id
        if (!id) continue
        out.push({
          id,
          name: m.display_name ?? id,
          providerId: 'sub_codex',
          efforts,
          defaultEffort: 'high',
        })
      }
      return out
    } catch {
      return []
    }
  }

  private async loadAgyModels(): Promise<EngineModelSpec[]> {
    if (this.agyModelsCache) return this.agyModelsCache
    if (this.agyLoading) return this.agyLoading
    this.agyLoading = (async () => {
      const bin = findExecutable('agy')
      if (!bin) return this.fallbackAgyModels()
      try {
        const { stdout } = await execFileAsync(bin, ['models'], {
          timeout: 8_000,
          maxBuffer: 512 * 1024,
          encoding: 'utf8',
        })
        const grouped = new Map<string, { name: string; efforts: Set<ReasoningEffort> }>()
        for (const line of stdout.split(/\r?\n/)) {
          const match = line.match(/^([A-Za-z0-9_.-]{1,128})\t([^\t]{1,256})$/)
          if (!match) continue
          const wireId = match[1]!
          const rawLabel = match[2]!
          const suffixMatch = wireId.match(/-(low|medium|high|xhigh|max)$/)
          const effort = suffixMatch?.[1] as ReasoningEffort | undefined
          const baseId = effort ? wireId.slice(0, -(effort.length + 1)) : wireId
          const cleanName = rawLabel.replace(/\s*\((Low|Medium|High|XHigh|Max)\)\s*$/i, '').trim()
          const item = grouped.get(baseId) ?? { name: cleanName, efforts: new Set<ReasoningEffort>() }
          if (effort) item.efforts.add(effort)
          grouped.set(baseId, item)
        }
        const specs: EngineModelSpec[] = [...grouped.entries()].map(([id, info]) => {
          const efforts = [...info.efforts]
          return {
            id,
            name: info.name,
            providerId: 'sub_agy',
            efforts: efforts.length > 0 ? efforts : undefined,
            defaultEffort: efforts.includes('high') ? 'high' : efforts[0],
          }
        })
        this.agyModelsCache = specs.length > 0 ? specs : this.fallbackAgyModels()
        return this.agyModelsCache
      } catch {
        this.agyModelsCache = this.fallbackAgyModels()
        return this.agyModelsCache
      } finally {
        this.agyLoading = null
      }
    })()
    return this.agyLoading
  }

  private fallbackAgyModels(): EngineModelSpec[] {
    const eff: ReasoningEffort[] = ['low', 'medium', 'high']
    return [
      { id: 'gemini-4-carbon', name: 'Gemini 4 Carbon', providerId: 'sub_agy', efforts: eff, defaultEffort: 'high' },
      { id: 'gemini-4-argon', name: 'Gemini 4 Argon', providerId: 'sub_agy', efforts: eff, defaultEffort: 'high' },
      { id: 'claude-opus-5-5', name: 'Claude Opus 5.5', providerId: 'sub_agy', efforts: eff, defaultEffort: 'medium' },
      { id: 'claude-sonnet-5-5', name: 'Claude Sonnet 5.5', providerId: 'sub_agy', efforts: eff, defaultEffort: 'medium' },
    ]
  }

  private scanCodexSessions(out: Map<string, HostTranscriptEntry>): void {
    const codexDir = path.join(this.homeDir, '.codex')
    const indexFile = path.join(codexDir, 'session_index.jsonl')
    const sessionsDir = path.join(codexDir, 'sessions')
    if (!fs.existsSync(sessionsDir)) return

    const titlesById = new Map<string, string>()
    if (fs.existsSync(indexFile)) {
      try {
        const lines = fs.readFileSync(indexFile, 'utf8').trim().split('\n').slice(-80)
        for (const line of lines) {
          try {
            const obj = JSON.parse(line) as { id?: string; thread_name?: string }
            if (obj.id && obj.thread_name) titlesById.set(obj.id, obj.thread_name)
          } catch {
            // ignore
          }
        }
      } catch {
        // ignore
      }
    }

    const rolloutFiles = collectRecentFiles(sessionsDir, f => f.endsWith('.jsonl'), 20)
    for (const f of rolloutFiles) {
      try {
        const idMatch = path.basename(f.path).match(/([0-9a-f]{8}-[0-9a-f-]{27})\.jsonl$/i)
        const nativeId = idMatch?.[1] ?? path.basename(f.path, '.jsonl')
        const sessionId = `host:codex:${nativeId}`
        const headAndTail = readFirstAndLastLines(f.path, 10, 10)
        const wsCandidates: string[] = []
        for (const l of [...headAndTail.first, ...headAndTail.last]) {
          try {
            const o = JSON.parse(l) as {
              type?: string
              payload?: { cwd?: string; writable_roots?: string[] }
            }
            if (typeof o.payload?.cwd === 'string') {
              wsCandidates.push(o.payload.cwd)
            }
            if (Array.isArray(o.payload?.writable_roots)) {
              wsCandidates.push(...o.payload.writable_roots.filter((r): r is string => typeof r === 'string'))
            }
          } catch {
            // ignore
          }
        }
        const workspaces = mergeUniqueWorkspaces(wsCandidates)
        const cwd = workspaces[0] ?? this.homeDir
        let startedCount = 0
        let completedCount = 0
        for (const l of headAndTail.last) {
          if (l.includes('"task_started"')) startedCount++
          if (l.includes('"task_complete"')) completedCount++
        }
        const recentlyModified = Date.now() - f.mtimeMs < 90_000
        const isRunning = recentlyModified && startedCount > completedCount

        const codexDefaults = readCodexLocalSettings(this.homeDir)
        const title = titlesById.get(nativeId) ?? `Codex · ${path.basename(cwd)}`
        out.set(sessionId, {
          nativeId,
          filePath: f.path,
          meta: {
            id: sessionId,
            engineId: 'codex',
            cwd,
            workspaces: workspaces.length > 0 ? workspaces : [cwd],
            title,
            state: isRunning ? 'running' : 'idle',
            createdAt: Math.floor(f.birthtimeMs || f.mtimeMs),
            updatedAt: Math.floor(f.mtimeMs),
            lastSeq: 0,
            model: codexDefaults.model,
            reasoningEffort: codexDefaults.effort,
            mode: codexDefaults.defaultMode,
            source: 'host',
          },
        })
      } catch {
        // ignore
      }
    }
  }

  private scanClaudeSessions(out: Map<string, HostTranscriptEntry>): void {
    const projectsDir = path.join(this.homeDir, '.claude', 'projects')
    if (!fs.existsSync(projectsDir)) return
    this.claudeRuntimeMap = this.terminalBridge.readClaudeRuntimeMap()
    const claudeDefaults = readClaudeLocalSettings(this.homeDir)

    const files = collectRecentFiles(projectsDir, f => f.endsWith('.jsonl'), 24)
    for (const f of files) {
      try {
        const nativeId = path.basename(f.path, '.jsonl')
        const sessionId = `host:claude:${nativeId}`
        const slug = path.basename(path.dirname(f.path))
        const { first, last } = readFirstAndLastLines(f.path, 20, 10)
        let promptTitle = ''
        let customTitle = ''
        let model: string | undefined
        const cwds: string[] = []
        for (const l of [...first, ...last]) {
          try {
            const o = JSON.parse(l) as {
              type?: string
              customTitle?: string
              cwd?: string
              message?: { role?: string; model?: string; content?: unknown }
            }
            if (typeof o.cwd === 'string' && o.cwd.trim()) {
              cwds.push(o.cwd.trim())
            }
            if (o.type === 'custom-title' && typeof o.customTitle === 'string' && o.customTitle.trim()) {
              customTitle = o.customTitle.trim()
            }
            if (!promptTitle && o.type === 'user' && o.message?.content) {
              const txt = extractClaudeText(o.message.content)
              if (txt && !txt.startsWith('<') && !txt.startsWith('Base directory for this skill:')) {
                promptTitle = txt.replace(/\s+/g, ' ').trim().slice(0, 48)
              }
            }
            if (o.message?.model) model = o.message.model
          } catch {
            // ignore
          }
        }
        const rt = this.claudeRuntimeMap.get(nativeId)
        const title = rt?.name || customTitle || promptTitle
        if (!title) continue
        if (rt?.cwd) cwds.unshift(rt.cwd)
        const workspaces = mergeUniqueWorkspaces(
          cwds.length > 0 ? cwds : [decodeClaudeProjectSlug(slug)],
        )
        const primaryCwd = workspaces[0] ?? decodeClaudeProjectSlug(slug)

        let state: SessionMeta['state'] = 'idle'
        if (rt) {
          // Trust `~/.claude/sessions/*.json` explicitly when interactive CLI is alive
          if (rt.waitingFor === 'permission prompt') {
            state = 'waiting_permission'
          } else if (rt.status === 'running' || rt.status === 'busy') {
            state = 'running'
          } else {
            state = 'idle'
          }
        } else if (Date.now() - f.mtimeMs < 45_000 && isClaudeTailActivelyRunning(last)) {
          state = 'running'
        }

        out.set(sessionId, {
          nativeId,
          filePath: f.path,
          meta: {
            id: sessionId,
            engineId: 'claude',
            cwd: primaryCwd,
            workspaces,
            title,
            state,
            liveStatusText: state === 'waiting_permission' ? '等待终端执行审批…' : undefined,
            createdAt: Math.floor(f.birthtimeMs || f.mtimeMs),
            updatedAt: Math.floor(Math.max(f.mtimeMs, rt?.updatedAt ?? 0)),
            lastSeq: 0,
            model: model ?? claudeDefaults.model,
            reasoningEffort: claudeDefaults.effort,
            mode: claudeDefaults.defaultMode,
            source: 'host',
          },
        })
      } catch {
        // ignore
      }
    }
  }

  private scanAgySessions(out: Map<string, HostTranscriptEntry>): void {
    const agyRoot = path.join(this.homeDir, '.gemini', 'antigravity-cli')
    const brainDir = path.join(agyRoot, 'brain')
    if (!fs.existsSync(brainDir)) return
    const agyHistoryMap = readAgyHistoryIndex(path.join(agyRoot, 'history.jsonl'))

    let entries: fs.Dirent[] = []
    try {
      entries = fs.readdirSync(brainDir, { withFileTypes: true })
    } catch {
      return
    }
    const candidates: Array<{ convId: string; path: string; mtimeMs: number; birthtimeMs: number }> = []
    for (const d of entries) {
      if (!d.isDirectory()) continue
      const tp = path.join(brainDir, d.name, '.system_generated', 'logs', 'transcript.jsonl')
      try {
        const st = fs.statSync(tp)
        candidates.push({ convId: d.name, path: tp, mtimeMs: st.mtimeMs, birthtimeMs: st.birthtimeMs })
      } catch {
        continue
      }
    }
    candidates.sort((a, b) => b.mtimeMs - a.mtimeMs)
    for (const c of candidates.slice(0, 20)) {
      try {
        const { first, last } = readFirstAndLastLines(c.path, 25, 6)
        let firstPromptTitle = ''
        const inferredPaths: string[] = []
        for (const l of [...first, ...last]) {
          try {
            const o = JSON.parse(l) as {
              type?: string
              created_at?: string
              content?: string
              tool_calls?: Array<{ args?: Record<string, unknown>; arguments?: Record<string, unknown> }>
            }
            if (!firstPromptTitle && o.type === 'USER_INPUT' && typeof o.content === 'string') {
              const reqs = extractAgyUserRequests(o.content)
              if (reqs.length > 0) {
                firstPromptTitle = reqs[0]!.replace(/\s+/g, ' ').trim().slice(0, 48)
              }
            }
            if (Array.isArray(o.tool_calls)) {
              for (const tc of o.tool_calls) {
                const argsObj = normalizeAgyArgs(tc.args ?? tc.arguments)
                extractProjectRootFromToolInput(argsObj, inferredPaths)
              }
            }
          } catch {
            // ignore
          }
        }

        const hist = agyHistoryMap.get(c.convId)
        const title = hist?.customTitle || firstPromptTitle || `AGY Session ${c.convId.slice(0, 8)}`
        const workspaces = mergeUniqueWorkspaces([
          ...(hist?.workspaces ?? []),
          ...inferredPaths,
        ])
        const primaryCwd = workspaces[0] ?? path.join(this.homeDir, 'work')

        const isRunning = Date.now() - c.mtimeMs < 75_000 && isAgyTailActivelyRunning(last)
        const sessionId = `host:agy:${c.convId}`
        let queued: string[] = []
        if (isRunning && hist && hist.prompts.length > 0) {
          const cached = this.transcriptCache.get(sessionId)
          if (cached && cached.mtimeMs === c.mtimeMs && cached.lastUserInputTs !== undefined) {
            queued = filterAgyQueuedFromHistory(
              hist.prompts,
              cached.lastUserInputTs,
              cached.recentUserTexts ?? [],
            )
          } else {
            const latestInput = findLatestAgyUserInputFromTail(c.path)
            queued = filterAgyQueuedFromHistory(hist.prompts, latestInput.ts, latestInput.texts)
          }
        }

        out.set(sessionId, {
          nativeId: c.convId,
          filePath: c.path,
          meta: {
            id: sessionId,
            engineId: 'agy',
            cwd: primaryCwd,
            workspaces: workspaces.length > 0 ? workspaces : [primaryCwd],
            title,
            state: isRunning ? 'running' : 'idle',
            queuedPrompts: queued.length > 0 ? queued : undefined,
            createdAt: Math.floor(c.birthtimeMs || c.mtimeMs),
            updatedAt: Math.floor(c.mtimeMs),
            lastSeq: 0,
            reasoningEffort: 'high',
            mode: 'auto',
            source: 'host',
          },
        })
      } catch {
        // ignore
      }
    }
  }

  private scanDshSessions(out: Map<string, HostTranscriptEntry>): void {
    const sessionsDir = path.join(this.homeDir, '.dsh', 'sessions')
    if (!fs.existsSync(sessionsDir)) return

    const files = collectRecentFiles(
      sessionsDir,
      f => path.basename(f) === 'session.v4.jsonl.zstd',
      24,
    )
    for (const f of files) {
      try {
        const lines = readDshZstdLines(f.path)
        if (lines.length === 0) continue

        let nativeId = path.basename(path.dirname(f.path))
        let cwd = path.join(this.homeDir, 'work')
        let createdAt = Math.floor(f.birthtimeMs || f.mtimeMs)
        let delegationDepth = 0
        let isSeeded = false
        let title = ''
        let firstUserPrompt = ''
        let model: string | undefined
        let turnOpen = false

        for (const l of lines) {
          try {
            const o = JSON.parse(l) as {
              type?: string
              id?: string
              cwd?: string
              createdAt?: number
              delegationDepth?: number
              isSeeded?: boolean
              data?: {
                title?: string
                route?: { model?: string }
                source?: { kind?: string }
                content?: Array<{ type?: string; text?: string }>
              }
            }
            if (o.type === 'session') {
              if (typeof o.id === 'string') nativeId = o.id
              if (typeof o.cwd === 'string' && o.cwd.trim()) cwd = o.cwd.trim()
              if (typeof o.createdAt === 'number') createdAt = Math.floor(o.createdAt)
              if (typeof o.delegationDepth === 'number') delegationDepth = o.delegationDepth
              if (o.isSeeded === true) isSeeded = true
            } else if (o.type === 'session/title' && typeof o.data?.title === 'string' && o.data.title.trim()) {
              title = o.data.title.trim()
            } else if (!firstUserPrompt && o.type === 'user/message' && o.data?.source?.kind === 'user') {
              const txt = (o.data.content ?? [])
                .map(c => c.text ?? '')
                .join('\n')
                .replace(/\s+/g, ' ')
                .trim()
              if (txt && !txt.startsWith('<')) firstUserPrompt = txt.slice(0, 48)
            } else if (o.type === 'request/header' && typeof o.data?.route?.model === 'string') {
              model = o.data.route.model
            } else if (o.type === 'turn/start') {
              turnOpen = true
            } else if (o.type === 'turn/end') {
              turnOpen = false
            }
          } catch {
            // ignore line
          }
        }

        if (delegationDepth > 0 || isSeeded) continue
        const resolvedTitle = title || firstUserPrompt
        if (!resolvedTitle) continue

        const workspaces = mergeUniqueWorkspaces([cwd])
        const primaryCwd = workspaces[0] ?? cwd
        const isRunning = Date.now() - f.mtimeMs < 90_000 && turnOpen
        const sessionId = `host:dsh:${nativeId}`

        out.set(sessionId, {
          nativeId,
          filePath: f.path,
          meta: {
            id: sessionId,
            engineId: 'dsh',
            cwd: primaryCwd,
            workspaces: workspaces.length > 0 ? workspaces : [primaryCwd],
            title: resolvedTitle,
            state: isRunning ? 'running' : 'idle',
            createdAt,
            updatedAt: Math.floor(f.mtimeMs),
            lastSeq: 0,
            model,
            reasoningEffort: 'medium',
            mode: 'default',
            source: 'host',
          },
        })
      } catch {
        // ignore
      }
    }
  }

  private parseCodexTranscript(
    sessionId: string,
    filePath: string,
  ): { events: AgentEvent[]; workspaces: string[]; isRunning: boolean; turnStartedAt?: number } {
    const events: AgentEvent[] = []
    const workspaces: string[] = []
    const openTools = new Set<string>()
    let activeTurn = false
    let turnStartedAt: number | undefined
    const lines = readFullSessionLines(filePath)
    let seq = 0
    for (const line of lines) {
      try {
        const o = JSON.parse(line) as {
          timestamp?: string
          type?: string
          payload?: {
            type?: string
            id?: string
            call_id?: string
            cwd?: string
            writable_roots?: string[]
            role?: string
            name?: string
            arguments?: string
            input?: string
            output?: string
            content?: Array<{ type?: string; text?: string }>
          }
        }
        const ts = o.timestamp ? Date.parse(o.timestamp) || Date.now() : Date.now()
        if (typeof o.payload?.cwd === 'string') {
          workspaces.push(o.payload.cwd)
        }
        if (Array.isArray(o.payload?.writable_roots)) {
          workspaces.push(...o.payload.writable_roots.filter((r): r is string => typeof r === 'string'))
        }
        if (o.type === 'event_msg' && o.payload?.type === 'task_started') {
          activeTurn = true
          turnStartedAt = ts
        } else if (o.type === 'event_msg' && o.payload?.type === 'task_complete') {
          activeTurn = false
        }

        if (o.type === 'response_item' && o.payload?.type === 'message') {
          const role = o.payload.role
          const text = (o.payload.content ?? [])
            .map(c => c.text ?? '')
            .join('\n')
            .trim()
          if (!text) continue
          if (
            role === 'user' &&
            !text.startsWith('# AGENTS.md') &&
            !text.startsWith('<environment_context>') &&
            !text.startsWith('<INSTRUCTIONS>') &&
            !text.startsWith('<skill>')
          ) {
            activeTurn = true
            turnStartedAt = ts
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.user',
              messageId: o.payload.id ?? `u_${seq}`,
              text,
            })
          } else if (role === 'assistant') {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.assistant.delta',
              messageId: o.payload.id ?? `a_${seq}`,
              text,
            })
          }
        } else if (
          o.type === 'response_item' &&
          (o.payload?.type === 'function_call' || o.payload?.type === 'custom_tool_call')
        ) {
          const callId = o.payload.call_id ?? `cx_tc_${seq}`
          const rawName = o.payload.name ?? 'tool'
          const rawArgs = o.payload.arguments ?? o.payload.input ?? ''
          const normalized = classifyRawToolCall(rawName, rawArgs)
          openTools.add(callId)
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'tool.call',
            toolCallId: callId,
            title: normalized.title,
            kind: normalized.kind,
            status: 'in_progress',
            input: normalized.input?.slice(0, 1600),
          })
        } else if (
          o.type === 'response_item' &&
          (o.payload?.type === 'function_call_output' || o.payload?.type === 'custom_tool_call_output')
        ) {
          const callId = o.payload.call_id
          if (callId && openTools.has(callId)) {
            openTools.delete(callId)
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'tool.update',
              toolCallId: callId,
              status: 'completed',
              output: typeof o.payload.output === 'string' ? o.payload.output.slice(0, 1600) : undefined,
            })
          }
        }
      } catch {
        // ignore
      }
    }
    return { events, workspaces, isRunning: activeTurn || openTools.size > 0, turnStartedAt }
  }

  private parseClaudeTranscript(
    sessionId: string,
    filePath: string,
  ): {
    events: AgentEvent[]
    workspaces: string[]
    isRunning: boolean
    turnStartedAt?: number
    queuedPrompts?: string[]
  } {
    const events: AgentEvent[] = []
    const workspaces: string[] = []
    const openTools = new Set<string>()
    const queued: string[] = []
    let isRunning = false
    let turnStartedAt: number | undefined
    const lines = readFullSessionLines(filePath)
    let seq = 0
    for (const line of lines) {
      try {
        const o = JSON.parse(line) as {
          type?: string
          subtype?: string
          operation?: string
          isMeta?: boolean
          content?: unknown
          cwd?: string
          uuid?: string
          timestamp?: string
          attachment?: {
            type?: string
            prompt?: string
            source_uuid?: string
            origin?: { kind?: string }
          }
          message?: {
            id?: string
            role?: string
            stop_reason?: string | null
            usage?: { input_tokens?: number; output_tokens?: number }
            content?: unknown
          }
        }
        if (typeof o.cwd === 'string' && o.cwd.trim()) {
          workspaces.push(o.cwd.trim())
        }
        const ts = o.timestamp ? Date.parse(o.timestamp) || Date.now() : Date.now()
        if (o.type === 'queue-operation') {
          if (o.operation === 'enqueue' && typeof o.content === 'string') {
            const cleanQ = o.content.trim()
            if (cleanQ && !cleanQ.startsWith('<')) {
              queued.push(cleanQ)
            }
          } else if (o.operation === 'dequeue' || o.operation === 'remove' || o.operation === 'clear') {
            if (o.operation === 'clear') {
              queued.length = 0
            } else if (typeof o.content === 'string' && o.content.trim()) {
              const targetIdx = queued.indexOf(o.content.trim())
              if (targetIdx >= 0) queued.splice(targetIdx, 1)
              else if (queued.length > 0) queued.shift()
            } else if (queued.length > 0) {
              queued.shift()
            }
          }
          continue
        }
        // Claude Code 2.1.x mid-turn steering: queued follow-ups absorbed during an active turn are
        // removed from `queue-operation` (`reason: "absorbed_mid_turn"`) and recorded as `attachment.queued_command`
        // rather than `type: "user"`. Surface them right in place as `message.user` just like Claude's Shell UI!
        if (o.type === 'attachment' && o.attachment?.type === 'queued_command') {
          const qcPrompt = typeof o.attachment.prompt === 'string' ? o.attachment.prompt.trim() : ''
          if (qcPrompt && !qcPrompt.startsWith('<') && o.attachment.origin?.kind !== 'task-notification') {
            const qIdx = queued.indexOf(qcPrompt)
            if (qIdx >= 0) queued.splice(qIdx, 1)
            isRunning = true
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.user',
              messageId: o.uuid ?? o.attachment.source_uuid ?? `u_qc_${seq}`,
              text: qcPrompt,
            })
          }
          continue
        }
        if (o.type === 'api-request') {
          isRunning = true
          if (!turnStartedAt) turnStartedAt = ts
          continue
        }
        if (o.type === 'system' && o.subtype === 'turn_duration') {
          isRunning = false
          turnStartedAt = undefined
          continue
        }
        if (o.type === 'user' && o.message?.role === 'user') {
          isRunning = true
          if (!turnStartedAt) turnStartedAt = ts
          if (Array.isArray(o.message.content)) {
            for (const item of o.message.content as Array<Record<string, unknown>>) {
              if (item.type === 'tool_result' && typeof item.tool_use_id === 'string') {
                const callId = item.tool_use_id
                if (openTools.has(callId)) {
                  openTools.delete(callId)
                  const outText = extractClaudeToolResultText(item.content)
                  events.push({
                    seq: seq++,
                    sessionId,
                    ts,
                    type: 'tool.update',
                    toolCallId: callId,
                    status: item.is_error === true ? 'failed' : 'completed',
                    output: outText ? outText.slice(0, 1600) : undefined,
                  })
                }
              }
            }
          }
          const txt = extractClaudeText(o.message.content)
          if (
            txt &&
            !o.isMeta &&
            !txt.startsWith('<') &&
            !txt.startsWith('Base directory for this skill:') &&
            !txt.startsWith('Output token limit hit. Resume directly')
          ) {
            const qIdx = queued.indexOf(txt.trim())
            if (qIdx >= 0) queued.splice(qIdx, 1)
            turnStartedAt = ts
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.user',
              messageId: o.uuid ?? `u_${seq}`,
              text: txt,
            })
          }
        } else if (
          o.type !== 'api-request-blob' &&
          o.message?.role === 'assistant' &&
          Array.isArray(o.message.content)
        ) {
          const msgId = o.message.id ?? o.uuid ?? `a_${seq}`
          let sawToolUse = false
          for (const part of o.message.content as Array<Record<string, unknown>>) {
            if (part.type === 'thinking' && typeof part.thinking === 'string' && part.thinking.trim()) {
              events.push({
                seq: seq++,
                sessionId,
                ts,
                type: 'reasoning.delta',
                messageId: `${msgId}_think`,
                text: part.thinking.trim().slice(0, 1800),
              })
            } else if (part.type === 'text' && typeof part.text === 'string' && part.text.trim()) {
              events.push({
                seq: seq++,
                sessionId,
                ts,
                type: 'message.assistant.delta',
                messageId: msgId,
                text: part.text,
              })
            } else if (part.type === 'tool_use' && typeof part.name === 'string') {
              sawToolUse = true
              if (part.input && typeof part.input === 'object') {
                extractProjectRootFromToolInput(part.input as Record<string, unknown>, workspaces)
              }
              const rawInput = part.input ? JSON.stringify(part.input) : undefined
              const normalized = classifyRawToolCall(part.name, rawInput)
              const callId = String(part.id ?? `tc_${seq}`)
              openTools.add(callId)
              events.push({
                seq: seq++,
                sessionId,
                ts,
                type: 'tool.call',
                toolCallId: callId,
                title: normalized.title,
                kind: normalized.kind,
                status: 'in_progress',
                input: normalized.input?.slice(0, 1600),
              })
            }
          }
          if (o.message.usage && (o.message.usage.input_tokens || o.message.usage.output_tokens)) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'usage',
              inputTokens: o.message.usage.input_tokens,
              outputTokens: o.message.usage.output_tokens,
            })
          }
          isRunning = sawToolUse || o.message.stop_reason === 'tool_use'
          if (!isRunning) turnStartedAt = undefined
        }
      } catch {
        // ignore
      }
    }
    return {
      events,
      workspaces,
      isRunning: isRunning || openTools.size > 0,
      turnStartedAt,
      queuedPrompts: queued.length > 0 ? queued : undefined,
    }
  }

  private parseAgyTranscript(
    sessionId: string,
    filePath: string,
  ): {
    events: AgentEvent[]
    workspaces: string[]
    isRunning: boolean
    turnStartedAt?: number
    lastUserInputTs?: number
    recentUserTexts?: string[]
  } {
    const events: AgentEvent[] = []
    const workspaces: string[] = []
    let pendingToolIds: string[] = []
    let isRunning = false
    let turnStartedAt: number | undefined
    let lastUserInputTs = 0
    const recentUserTexts: string[] = []
    const lines = readFullSessionLines(filePath)
    let seq = 0
    for (const line of lines) {
      try {
        const o = JSON.parse(line) as {
          step_index?: number
          type?: string
          status?: string
          created_at?: string
          content?: string
          thinking?: string
          input_tokens?: number
          output_tokens?: number
          tool_calls?: Array<{ id?: string; name?: string; arguments?: unknown; args?: unknown }>
        }
        const ts = o.created_at ? Date.parse(o.created_at) || Date.now() : Date.now()
        if (o.type === 'USER_INPUT' && typeof o.content === 'string') {
          const reqs = extractAgyUserRequests(o.content)
          if (reqs.length > 0) {
            isRunning = true
            turnStartedAt = ts
            lastUserInputTs = ts
            reqs.forEach((txt, idx) => {
              recentUserTexts.push(txt)
              if (recentUserTexts.length > 16) recentUserTexts.shift()
              events.push({
                seq: seq++,
                sessionId,
                ts,
                type: 'message.user',
                messageId: idx === 0 ? `u_${o.step_index ?? seq}` : `u_${o.step_index ?? seq}_${idx}`,
                text: txt,
              })
            })
          } else if (!o.content.trim().startsWith('<SYSTEM_MESSAGE>') && !o.content.trim().startsWith('<CONTEXT_SUMMARY>')) {
            isRunning = true
            turnStartedAt = ts
          }
        } else if (o.type === 'PLANNER_RESPONSE') {
          pendingToolIds = []
          if (typeof o.thinking === 'string' && o.thinking.trim()) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'reasoning.delta',
              messageId: `th_${o.step_index ?? seq}`,
              text: o.thinking.trim().slice(0, 1800),
            })
          }
          const hasTools = Array.isArray(o.tool_calls) && o.tool_calls.length > 0
          if (Array.isArray(o.tool_calls)) {
            for (const tc of o.tool_calls) {
              if (tc.name) {
                const cleanArgsObj = normalizeAgyArgs(tc.args ?? tc.arguments)
                extractProjectRootFromToolInput(cleanArgsObj, workspaces)
                const rawInput = cleanArgsObj ? JSON.stringify(cleanArgsObj) : undefined
                const normalized = classifyRawToolCall(tc.name, rawInput)
                const callId = tc.id ?? `tc_${o.step_index ?? seq}_${tc.name}`
                pendingToolIds.push(callId)
                events.push({
                  seq: seq++,
                  sessionId,
                  ts,
                  type: 'tool.call',
                  toolCallId: callId,
                  title: normalized.title,
                  kind: normalized.kind,
                  status: 'in_progress',
                  input: normalized.input?.slice(0, 1600),
                })
              }
            }
          }
          if (typeof o.content === 'string' && o.content.trim()) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.assistant.delta',
              messageId: `a_${o.step_index ?? seq}`,
              text: o.content.trim(),
            })
          }
          if (o.input_tokens || o.output_tokens) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'usage',
              inputTokens: o.input_tokens,
              outputTokens: o.output_tokens,
            })
          }
          isRunning = hasTools
          if (!isRunning) turnStartedAt = undefined
        } else if (
          (o.type === 'GENERIC' || o.type === 'TOOL_RESPONSE') &&
          typeof o.content === 'string'
        ) {
          isRunning = true
          const callId = pendingToolIds.shift()
          if (callId) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'tool.update',
              toolCallId: callId,
              status: o.status === 'ERROR' ? 'failed' : 'completed',
              output: o.content.slice(0, 1600),
            })
          }
        }
      } catch {
        // ignore
      }
    }
    return {
      events,
      workspaces,
      isRunning: isRunning || pendingToolIds.length > 0,
      turnStartedAt,
      lastUserInputTs,
      recentUserTexts,
    }
  }

  private parseDshTranscript(
    sessionId: string,
    filePath: string,
  ): { events: AgentEvent[]; workspaces: string[]; isRunning: boolean; turnStartedAt?: number } {
    const events: AgentEvent[] = []
    const workspaces: string[] = []
    const openTools = new Set<string>()
    let turnOpen = false
    let turnStartedAt: number | undefined
    const lines = readDshZstdLines(filePath)
    let seq = 0

    for (const line of lines) {
      try {
        const o = JSON.parse(line) as {
          type?: string
          seq?: number
          time?: number
          cwd?: string
          data?: {
            turn?: number
            callId?: string
            name?: string
            arguments?: string
            id?: string
            source?: { kind?: string }
            content?: Array<{ type?: string; text?: string }>
            message?: {
              role?: string
              toolCallId?: string
              content?: Array<{ type?: string; text?: string }>
            }
            reason?: {
              kind?: string
              error?: { message?: string }
            }
          }
        }
        const ts = typeof o.time === 'number' ? o.time : Date.now()
        if (o.type === 'session' && typeof o.cwd === 'string' && o.cwd.trim()) {
          workspaces.push(o.cwd.trim())
        } else if (o.type === 'turn/start') {
          turnOpen = true
          turnStartedAt = ts
        } else if (o.type === 'turn/end') {
          turnOpen = false
          turnStartedAt = undefined
          if (o.data?.reason?.kind === 'error' && o.data.reason.error?.message) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'error',
              message: o.data.reason.error.message.slice(0, 500),
            })
          }
        } else if (o.type === 'user/message' && o.data?.source?.kind === 'user') {
          const txt = (o.data.content ?? [])
            .map(c => c.text ?? '')
            .join('\n')
            .trim()
          if (txt && !txt.startsWith('<')) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.user',
              messageId: o.data.id ?? `u_${o.seq ?? seq}`,
              text: txt,
            })
          }
        } else if (o.type === 'assistant/message' && Array.isArray(o.data?.message?.content)) {
          const msgId = `a_${o.seq ?? seq}`
          for (const part of o.data!.message!.content!) {
            if (part.type === 'reasoning' && typeof part.text === 'string' && part.text.trim()) {
              events.push({
                seq: seq++,
                sessionId,
                ts,
                type: 'reasoning.delta',
                messageId: `${msgId}_think`,
                text: part.text.trim().slice(0, 1800),
              })
            } else if (part.type === 'text' && typeof part.text === 'string' && part.text.trim()) {
              events.push({
                seq: seq++,
                sessionId,
                ts,
                type: 'message.assistant.delta',
                messageId: msgId,
                text: part.text.trim(),
              })
            }
          }
        } else if (o.type === 'tool/call' && typeof o.data?.name === 'string') {
          const callId = o.data.callId ?? `dsh_tc_${o.seq ?? seq}`
          const rawArgs = o.data.arguments
          if (rawArgs) {
            try {
              const parsedArgs = JSON.parse(rawArgs) as Record<string, unknown>
              extractProjectRootFromToolInput(parsedArgs, workspaces)
            } catch {
              // ignore
            }
          }
          const normalized = classifyRawToolCall(o.data.name, rawArgs)
          openTools.add(callId)
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'tool.call',
            toolCallId: callId,
            title: normalized.title,
            kind: normalized.kind,
            status: 'in_progress',
            input: normalized.input?.slice(0, 1600),
          })
        } else if (o.type === 'tool/result' && o.data?.message?.toolCallId) {
          const callId = o.data.message.toolCallId
          if (openTools.has(callId)) {
            openTools.delete(callId)
            const outText = (o.data.message.content ?? [])
              .map(c => c.text ?? '')
              .join('\n')
              .trim()
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'tool.update',
              toolCallId: callId,
              status: 'completed',
              output: outText ? outText.slice(0, 1600) : undefined,
            })
          }
        }
      } catch {
        // ignore
      }
    }
    return { events, workspaces, isRunning: turnOpen || openTools.size > 0, turnStartedAt }
  }
}

function collectRecentFiles(
  rootDir: string,
  predicate: (p: string) => boolean,
  limit: number,
): Array<{ path: string; mtimeMs: number; birthtimeMs: number }> {
  const found: Array<{ path: string; mtimeMs: number; birthtimeMs: number }> = []
  const stack = [rootDir]
  while (stack.length > 0) {
    const dir = stack.pop()!
    let entries: fs.Dirent[]
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true })
    } catch {
      continue
    }
    for (const e of entries) {
      const full = path.join(dir, e.name)
      if (e.isDirectory()) {
        stack.push(full)
      } else if (e.isFile() && predicate(full)) {
        try {
          const st = fs.statSync(full)
          found.push({ path: full, mtimeMs: st.mtimeMs, birthtimeMs: st.birthtimeMs })
        } catch {
          // ignore
        }
      }
    }
  }
  found.sort((a, b) => b.mtimeMs - a.mtimeMs)
  return found.slice(0, limit)
}

function readFirstAndLastLines(
  filePath: string,
  firstCount: number,
  lastCount: number,
): { first: string[]; last: string[] } {
  const st = fs.statSync(filePath)
  const chunkSize = Math.min(st.size, 48 * 1024)
  const fd = fs.openSync(filePath, 'r')
  try {
    const headBuf = Buffer.alloc(chunkSize)
    fs.readSync(fd, headBuf, 0, chunkSize, 0)
    const first = headBuf.toString('utf8').split('\n').filter(Boolean).slice(0, firstCount)

    if (st.size <= chunkSize) {
      const all = headBuf.toString('utf8').split('\n').filter(Boolean)
      return { first, last: all.slice(-lastCount) }
    }
    const tailBuf = Buffer.alloc(chunkSize)
    fs.readSync(fd, tailBuf, 0, chunkSize, st.size - chunkSize)
    const last = tailBuf.toString('utf8').split('\n').slice(1).filter(Boolean).slice(-lastCount)
    return { first, last }
  } finally {
    fs.closeSync(fd)
  }
}

function readFullSessionLines(filePath: string): string[] {
  const st = fs.statSync(filePath)
  const maxBytes = 24 * 1024 * 1024
  const fd = fs.openSync(filePath, 'r')
  try {
    const offset = Math.max(0, st.size - maxBytes)
    const len = st.size - offset
    const buf = Buffer.alloc(len)
    fs.readSync(fd, buf, 0, len, offset)
    const lines = buf.toString('utf8').split('\n')
    const clean = offset > 0 ? lines.slice(1) : lines
    return clean.filter(Boolean)
  } finally {
    fs.closeSync(fd)
  }
}

function readDshZstdLines(filePath: string): string[] {
  const compressed = fs.readFileSync(filePath)
  const raw = zlib.zstdDecompressSync(compressed).toString('utf8')
  return raw.split('\n').filter(Boolean)
}

export function extractAgyUserRequests(rawContent: string): string[] {
  if (!rawContent) return []
  if (rawContent.includes('<USER_REQUEST>')) {
    const out: string[] = []
    const re = /<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/g
    let m: RegExpExecArray | null
    while ((m = re.exec(rawContent)) !== null) {
      const inner = (m[1] ?? '').trim()
      if (inner) out.push(inner)
    }
    if (out.length > 0) return out
    // Fallback when `transcript.jsonl` truncated a very long prompt before `</USER_REQUEST>`
    const idx = rawContent.indexOf('<USER_REQUEST>')
    const fallback = rawContent
      .slice(idx + '<USER_REQUEST>'.length)
      .replace(/<ADDITIONAL_METADATA>[\s\S]*$|<USER_SETTINGS_CHANGE>[\s\S]*$|<SYSTEM_MESSAGE>[\s\S]*$/g, '')
      .trim()
    return fallback ? [fallback] : []
  }

  const trimmed = rawContent
    .replace(/<ADDITIONAL_METADATA>[\s\S]*$|<USER_SETTINGS_CHANGE>[\s\S]*$/g, '')
    .trim()
  if (
    !trimmed ||
    trimmed.startsWith('<SYSTEM_MESSAGE>') ||
    trimmed.startsWith('<CONTEXT_SUMMARY>')
  ) {
    return []
  }
  return [trimmed]
}

function findLatestAgyUserInputFromTail(filePath: string): { ts: number; texts: string[] } {
  const st = fs.statSync(filePath)
  if (st.size === 0) return { ts: 0, texts: [] }
  const chunkSize = 128 * 1024
  const fd = fs.openSync(filePath, 'r')
  try {
    let pos = st.size
    let carry = ''
    let latestTs = 0
    const collectedTexts: string[] = []

    while (pos > 0) {
      const readLen = Math.min(chunkSize, pos)
      pos -= readLen
      const buf = Buffer.alloc(readLen)
      fs.readSync(fd, buf, 0, readLen, pos)
      const combined = buf.toString('utf8') + carry
      const lines = combined.split('\n')
      carry = pos > 0 ? (lines.shift() ?? '') : ''

      for (let i = lines.length - 1; i >= 0; i--) {
        const line = lines[i]?.trim()
        if (!line || !line.includes('"USER_INPUT"')) continue
        try {
          const o = JSON.parse(line) as { type?: string; created_at?: string; content?: string }
          if (o.type === 'USER_INPUT' && typeof o.content === 'string') {
            const reqs = extractAgyUserRequests(o.content)
            if (reqs.length > 0) {
              const ts = o.created_at ? Date.parse(o.created_at) || 0 : 0
              if (latestTs === 0) latestTs = ts
              collectedTexts.push(...reqs)
              if (collectedTexts.length >= 6 || (latestTs > 0 && latestTs - ts > 5_000)) {
                return { ts: latestTs, texts: collectedTexts }
              }
            }
          }
        } catch {
          // ignore malformed line
        }
      }

      if (latestTs > 0) {
        return { ts: latestTs, texts: collectedTexts }
      }
    }
    return { ts: latestTs, texts: collectedTexts }
  } finally {
    fs.closeSync(fd)
  }
}

interface AgyHistoryInfo {
  customTitle?: string
  workspaces: string[]
  prompts: Array<{ text: string; ts: number }>
}

let cachedAgyHistoryPath = ''
let cachedAgyHistoryMtime = 0
let cachedAgyHistorySize = 0
let cachedAgyHistoryMap = new Map<string, AgyHistoryInfo>()

function readAgyHistoryIndex(historyFile: string): Map<string, AgyHistoryInfo> {
  if (!fs.existsSync(historyFile)) return new Map()
  try {
    const st = fs.statSync(historyFile)
    if (
      historyFile === cachedAgyHistoryPath &&
      st.mtimeMs === cachedAgyHistoryMtime &&
      st.size === cachedAgyHistorySize
    ) {
      return cachedAgyHistoryMap
    }
    const out = new Map<string, AgyHistoryInfo>()
    const raw = fs.readFileSync(historyFile, 'utf8')
    for (const line of raw.split('\n')) {
      if (!line.trim()) continue
      try {
        const o = JSON.parse(line) as {
          conversationId?: string
          workspace?: string
          display?: string
          timestamp?: number
          type?: string
        }
        if (!o.conversationId) continue
        const rec = out.get(o.conversationId) ?? { workspaces: [], prompts: [] }
        if (typeof o.workspace === 'string' && o.workspace.trim()) {
          rec.workspaces.push(o.workspace.trim())
        }
        if (o.type === 'slash_command' && typeof o.display === 'string') {
          const cmd = o.display.trim()
          if (cmd.startsWith('/rename ')) {
            const renamed = cmd.slice('/rename '.length).trim()
            if (renamed) rec.customTitle = renamed
          } else if (cmd === '/clear') {
            rec.prompts.length = 0
          }
        } else if (
          o.type !== 'slash_command' &&
          typeof o.display === 'string' &&
          typeof o.timestamp === 'number'
        ) {
          const trimmed = o.display.trim()
          if (trimmed && !trimmed.startsWith('/')) {
            rec.prompts.push({ text: trimmed, ts: o.timestamp })
          }
        }
        out.set(o.conversationId, rec)
      } catch {
        // ignore line
      }
    }
    cachedAgyHistoryPath = historyFile
    cachedAgyHistoryMtime = st.mtimeMs
    cachedAgyHistorySize = st.size
    cachedAgyHistoryMap = out
    return out
  } catch {
    return cachedAgyHistoryMap
  }
}

export function filterAgyQueuedFromHistory(
  prompts: Array<{ text: string; ts: number }>,
  lastUserInputTs: number,
  recentUserTexts: string[],
): string[] {
  if (prompts.length === 0 || lastUserInputTs <= 0) return []
  const normRecent = new Set(
    recentUserTexts.map(t => t.replace(/\s+/g, ' ').trim()).filter(Boolean),
  )
  const out: string[] = []
  for (const p of prompts) {
    // `transcript.jsonl` `created_at` is ISO-8601 rounded down to whole seconds (.000Z),
    // while `history.jsonl` uses millisecond epoch timestamps.
    if (p.ts < lastUserInputTs) continue
    const normP = p.text.replace(/\s+/g, ' ').trim()
    if (!normP) continue
    if (p.ts <= lastUserInputTs + 2_500 && normRecent.has(normP)) continue
    out.push(p.text)
  }
  return out
}

function resolveAgyQueuedPrompts(
  homeDir: string,
  convId: string,
  lastUserInputTs: number,
  recentUserTexts: string[],
): string[] | undefined {
  const historyFile = path.join(homeDir, '.gemini', 'antigravity-cli', 'history.jsonl')
  const hist = readAgyHistoryIndex(historyFile).get(convId)
  if (!hist) return undefined
  const queued = filterAgyQueuedFromHistory(hist.prompts, lastUserInputTs, recentUserTexts)
  return queued.length > 0 ? queued : undefined
}

function normalizeAgyArgs(raw: unknown): Record<string, unknown> | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof v === 'string' && v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
      try {
        out[k] = JSON.parse(v)
        continue
      } catch {
        // keep as-is
      }
    }
    out[k] = v
  }
  return out
}

function extractProjectRootFromToolInput(
  args: Record<string, unknown> | undefined,
  sink: string[],
): void {
  if (!args) return
  const rawPath = (args.Path ?? args.path ?? args.file_path ?? args.Cwd ?? args.cwd) as unknown
  if (typeof rawPath !== 'string' || !rawPath.startsWith('/')) return
  const home = os.homedir()
  for (const bucket of ['work', 'personal']) {
    const prefix = path.join(home, bucket) + path.sep
    if (rawPath.startsWith(prefix)) {
      const rest = rawPath.slice(prefix.length)
      const repoName = rest.split(path.sep)[0]
      if (repoName && !repoName.startsWith('.')) {
        sink.push(path.join(home, bucket, repoName))
      }
      return
    }
  }
}

function mergeUniqueWorkspaces(candidates: string[]): string[] {
  const out: string[] = []
  const seenReal = new Set<string>()
  const home = os.homedir()
  const genericDirs = new Set([home, path.join(home, 'work'), path.join(home, 'personal')])

  const specific: string[] = []
  const generic: string[] = []

  for (const raw of candidates) {
    if (!raw) continue
    try {
      const real = fs.realpathSync(raw)
      if (!fs.statSync(real).isDirectory()) continue
      if (seenReal.has(real)) continue
      seenReal.add(real)
      if (genericDirs.has(real)) {
        generic.push(real)
      } else {
        specific.push(real)
      }
    } catch {
      // ignore missing path
    }
  }
  out.push(...specific)
  if (out.length === 0 && generic.length > 0) {
    out.push(generic[0]!)
  }
  return out
}

function decodeClaudeProjectSlug(slug: string): string {
  if (!slug.startsWith('-')) return os.homedir()
  const tokens = slug.slice(1).split('-').filter(Boolean)
  let current = '/'
  let i = 0
  while (i < tokens.length) {
    // Greedily match longest hyphenated directory name on disk
    let matched: string | null = null
    let matchedEnd = i
    for (let j = tokens.length; j > i; j--) {
      const candidateName = tokens.slice(i, j).join('-')
      const candidatePath = path.join(current, candidateName)
      try {
        if (fs.statSync(candidatePath).isDirectory()) {
          matched = candidatePath
          matchedEnd = j
          break
        }
      } catch {
        // continue trying shorter hyphen join
      }
    }
    if (matched) {
      current = matched
      i = matchedEnd
    } else {
      break
    }
  }
  return current !== '/' ? current : os.homedir()
}

function extractClaudeText(content: unknown): string {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .map((item: unknown) => {
        if (
          item &&
          typeof item === 'object' &&
          (item as { type?: unknown }).type === 'text' &&
          typeof (item as { text?: unknown }).text === 'string'
        ) {
          return (item as { text: string }).text
        }
        return ''
      })
      .filter(Boolean)
      .join('\n')
  }
  return ''
}

function extractClaudeToolResultText(content: unknown): string {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .map((item: unknown) => {
        if (item && typeof item === 'object' && typeof (item as { text?: unknown }).text === 'string') {
          return (item as { text: string }).text
        }
        return ''
      })
      .filter(Boolean)
      .join('\n')
  }
  return ''
}

export function classifyRawToolCall(
  rawName: string,
  rawInput?: string,
): {
  title: string
  kind: 'read' | 'edit' | 'delete' | 'move' | 'search' | 'execute' | 'think' | 'fetch' | 'other'
  input?: string
} {
  const short = rawName.replace(/^default_api:/, '')
  const lower = short.toLowerCase()

  // Detect Skill invocations either directly (`Skill`) or when reading `.../skills/<name>/SKILL.md`
  if (lower === 'skill' || lower === 'use_skill') {
    return { title: 'Skill', kind: 'other', input: rawInput }
  }
  if (rawInput) {
    const skillMatch = rawInput.match(/\/skills\/([A-Za-z0-9_.-]+)\/SKILL\.md/i)
    if (skillMatch?.[1]) {
      return {
        title: 'Skill',
        kind: 'other',
        input: JSON.stringify({ skill: skillMatch[1], raw: rawInput.slice(0, 600) }),
      }
    }
  }

  if (
    lower === 'bash' ||
    lower === 'run_command' ||
    lower === 'exec_command' ||
    lower === 'shell' ||
    lower === 'shell_command' ||
    lower === 'terminal'
  ) {
    return { title: 'Bash', kind: 'execute', input: rawInput }
  }

  if (lower === 'read' || lower === 'read_file' || lower === 'view_file') {
    return { title: 'Read', kind: 'read', input: rawInput }
  }

  if (
    lower === 'edit' ||
    lower === 'multiedit' ||
    lower === 'edit_file' ||
    lower === 'apply_patch' ||
    lower === 'replace_file_content'
  ) {
    return { title: 'Update', kind: 'edit', input: rawInput }
  }

  if (lower === 'write' || lower === 'write_file' || lower === 'create_file') {
    return { title: 'Write', kind: 'edit', input: rawInput }
  }

  if (lower === 'grep' || lower === 'glob' || lower === 'search' || lower === 'find_by_name' || lower === 'grep_search') {
    return { title: short, kind: 'search', input: rawInput }
  }

  if (lower === 'webfetch' || lower === 'websearch' || lower === 'search_web') {
    return { title: short, kind: 'fetch', input: rawInput }
  }

  if (lower === 'task' || lower === 'agent' || lower === 'invoke_subagent') {
    return { title: 'Task', kind: 'other', input: rawInput }
  }

  return { title: short, kind: 'other', input: rawInput }
}

function normalizeHostMode(raw: unknown, fallback: SessionMode = 'default'): SessionMode {
  if (typeof raw !== 'string') return fallback
  switch (raw.trim()) {
    case 'auto':
      return 'auto'
    case 'acceptEdits':
    case 'auto-edit':
    case 'auto_edit':
      return 'acceptEdits'
    case 'plan':
      return 'plan'
    case 'bypassPermissions':
    case 'dontAsk':
    case 'never':
    case 'yolo':
      return 'bypassPermissions'
    case 'default':
    case 'manual':
    case 'ask':
    case 'on-request':
      return 'default'
    default:
      return fallback
  }
}

function normalizeHostEffort(raw: unknown, fallback: ReasoningEffort = 'medium'): ReasoningEffort {
  if (typeof raw !== 'string') return fallback
  switch (raw.trim().toLowerCase()) {
    case 'low':
      return 'low'
    case 'medium':
      return 'medium'
    case 'high':
      return 'high'
    case 'xhigh':
      return 'xhigh'
    case 'max':
      return 'max'
    default:
      return fallback
  }
}

function readClaudeLocalSettings(homeDir = os.homedir()): {
  model?: string
  defaultMode: SessionMode
  effort: ReasoningEffort
  usesLocalProxy: boolean
} {
  const settingsPath = path.join(homeDir, '.claude', 'settings.json')
  try {
    if (fs.existsSync(settingsPath)) {
      const parsed = JSON.parse(fs.readFileSync(settingsPath, 'utf8')) as {
        model?: string
        defaultMode?: string
        effortLevel?: string
        env?: Record<string, string>
        permissions?: { defaultMode?: string }
      }
      const rawMode = parsed.permissions?.defaultMode ?? parsed.defaultMode
      const baseUrl = parsed.env?.ANTHROPIC_BASE_URL ?? ''
      return {
        model: typeof parsed.model === 'string' && parsed.model.trim() ? parsed.model.trim() : undefined,
        defaultMode: normalizeHostMode(rawMode, 'default'),
        effort: normalizeHostEffort(parsed.effortLevel, 'medium'),
        usesLocalProxy: baseUrl.includes('127.0.0.1') || baseUrl.includes('localhost'),
      }
    }
  } catch {
    // ignore
  }
  return { defaultMode: 'default', effort: 'medium', usesLocalProxy: false }
}

function readCodexLocalSettings(homeDir = os.homedir()): {
  model?: string
  defaultMode: SessionMode
  effort: ReasoningEffort
} {
  const tomlPath = path.join(homeDir, '.codex', 'config.toml')
  try {
    if (fs.existsSync(tomlPath)) {
      const raw = fs.readFileSync(tomlPath, 'utf8')
      const modelMatch = raw.match(/^\s*model\s*=\s*["']([^"']+)["']/m)
      const effortMatch = raw.match(/^\s*model_reasoning_effort\s*=\s*["']([^"']+)["']/m)
      const approvalMatch = raw.match(/^\s*approval_policy\s*=\s*["']([^"']+)["']/m)
      return {
        model: modelMatch?.[1]?.trim() || undefined,
        defaultMode: normalizeHostMode(approvalMatch?.[1], 'default'),
        effort: normalizeHostEffort(effortMatch?.[1], 'medium'),
      }
    }
  } catch {
    // ignore
  }
  return { defaultMode: 'default', effort: 'medium' }
}

function isClaudeTailActivelyRunning(tailLines: string[]): boolean {
  for (let i = tailLines.length - 1; i >= 0; i--) {
    try {
      const o = JSON.parse(tailLines[i]!) as {
        type?: string
        subtype?: string
        message?: { role?: string; stop_reason?: string | null }
      }
      if (o.type === 'system' && o.subtype === 'turn_duration') return false
      if (o.type === 'api-request') return true
      if (o.type === 'assistant' || o.message?.role === 'assistant') {
        return o.message?.stop_reason === 'tool_use'
      }
      if (o.type === 'user') return true
    } catch {
      // ignore non-json
    }
  }
  return false
}

function isAgyTailActivelyRunning(tailLines: string[]): boolean {
  for (let i = tailLines.length - 1; i >= 0; i--) {
    try {
      const o = JSON.parse(tailLines[i]!) as {
        type?: string
        status?: string
        tool_calls?: unknown[]
      }
      if (o.status === 'ERROR' || o.status === 'CANCELLED') return false
      if (o.type === 'PLANNER_RESPONSE') {
        return Array.isArray(o.tool_calls) && o.tool_calls.length > 0
      }
      if (o.type === 'USER_INPUT' || o.type === 'TOOL_RESPONSE' || o.type === 'GENERIC') {
        return true
      }
    } catch {
      // ignore
    }
  }
  return false
}
