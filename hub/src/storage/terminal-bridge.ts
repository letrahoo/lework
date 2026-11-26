import crypto from 'node:crypto'
import { execFile } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { promisify } from 'node:util'
import type { PermissionOption, SessionMode } from '@lework/protocol'
import { augmentedPathEnv, findExecutable } from '../platform/index.ts'

const execFileAsync = promisify(execFile)

export interface ClaudeLiveSessionMeta {
  pid: number
  sessionId: string
  cwd: string
  name?: string
  status?: string
  waitingFor?: string | null
  entrypoint?: string
  updatedAt?: number
}

export interface DetectedTerminalPermission {
  requestId: string
  title: string
  options: PermissionOption[]
}

export interface LiveTerminalInspection {
  targetReachable: boolean
  hostAppHint?: string
  liveStatusText?: string
  liveMode?: SessionMode
  pendingPermission?: DetectedTerminalPermission
  hasCancelHint?: boolean
}

export type TerminalCommandRunner = (
  bin: string,
  args: string[],
  timeoutMs: number,
  env?: NodeJS.ProcessEnv,
) => Promise<string>

export interface TerminalBridgeOptions {
  homeDir?: string
  runCommand?: TerminalCommandRunner
  isPidAlive?: (pid: number) => boolean
  selfPid?: number
  it2Bin?: string | null
  tmuxBin?: string | null
  lsofBin?: string | null
  tmuxSocketPaths?: string[]
}

type TerminalTarget =
  | { kind: 'iterm2'; id: string; tty: string }
  | { kind: 'tmux'; id: string; tty: string; socketPath?: string; inCopyMode?: boolean }

interface CachedTopology {
  fetchedAt: number
  it2FetchedAt: number
  byTty: Map<string, TerminalTarget>
}

interface AgyTtyProcess {
  pid: number
  tty: string
  args: string
  cwd: string
  presenceConvIds: string[]
  openConvIds: string[]
}

export type TerminalPromptDeliveryResult =
  | { delivered: true }
  | { delivered: false; reason: 'not_running' }
  | { delivered: false; reason: 'unreachable_terminal'; hostAppHint?: string }

interface CachedAgyProcesses {
  fetchedAt: number
  list: AgyTtyProcess[]
}

const defaultRunCommand: TerminalCommandRunner = async (bin, args, timeoutMs, env) => {
  const { stdout } = await execFileAsync(bin, args, {
    timeout: timeoutMs,
    maxBuffer: 4 * 1024 * 1024,
    encoding: 'utf8',
    env: env ?? augmentedPathEnv(process.env),
  })
  return stdout
}

const defaultIsPidAlive = (pid: number): boolean => {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

function safeRealpath(p: string): string {
  try {
    return fs.realpathSync(p)
  } catch {
    return path.resolve(p)
  }
}

export class TerminalBridge {
  private topologyCache: CachedTopology | null = null
  private topologyLoading: Promise<CachedTopology> | null = null
  private agyProcCache: CachedAgyProcesses | null = null
  private readonly pidTtyCache = new Map<number, string | null>()
  private readonly homeDir: string
  private readonly runCmd: TerminalCommandRunner
  private readonly pidAlive: (pid: number) => boolean
  private readonly selfPid: number
  private readonly opts: TerminalBridgeOptions

  constructor(opts: TerminalBridgeOptions = {}) {
    this.opts = opts
    this.homeDir = opts.homeDir ?? os.homedir()
    this.runCmd = opts.runCommand ?? defaultRunCommand
    this.pidAlive = opts.isPidAlive ?? defaultIsPidAlive
    this.selfPid = opts.selfPid ?? process.pid
  }

  private resolveIt2Bin(): string | null {
    if (this.opts.it2Bin !== undefined) return this.opts.it2Bin
    const custom = path.join(this.homeDir, '.config', 'iterm2', 'it2')
    if (fs.existsSync(custom)) return custom
    return findExecutable('it2')
  }

  private resolveTmuxBin(): string | null {
    if (this.opts.tmuxBin !== undefined) return this.opts.tmuxBin
    return findExecutable('tmux')
  }

  private resolveLsofBin(): string | null {
    if (this.opts.lsofBin !== undefined) return this.opts.lsofBin
    return findExecutable('lsof', ['/usr/sbin/lsof', '/usr/bin/lsof']) ?? 'lsof'
  }

  /**
   * Reads `~/.claude/sessions/*.json` (ignoring `.key` files) for live PID, custom session name,
   * and exact runtime state (`busy` / `running` / `idle` / `permission prompt`).
   */
  readClaudeRuntimeMap(): Map<string, ClaudeLiveSessionMeta> {
    const out = new Map<string, ClaudeLiveSessionMeta>()
    const dir = path.join(this.homeDir, '.claude', 'sessions')
    if (!fs.existsSync(dir)) return out

    let files: string[] = []
    try {
      files = fs.readdirSync(dir).filter(f => f.endsWith('.json'))
    } catch {
      return out
    }

    for (const f of files) {
      try {
        const raw = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')) as {
          pid?: number
          sessionId?: string
          cwd?: string
          name?: string
          status?: string
          waitingFor?: string | null
          entrypoint?: string
          updatedAt?: number
        }
        if (typeof raw.pid !== 'number' || typeof raw.sessionId !== 'string') continue
        if (!this.pidAlive(raw.pid)) continue
        const entrypoint =
          typeof raw.entrypoint === 'string' && raw.entrypoint.trim() ? raw.entrypoint.trim() : undefined
        const nextMeta: ClaudeLiveSessionMeta = {
          pid: raw.pid,
          sessionId: raw.sessionId,
          cwd: raw.cwd ?? '',
          name: typeof raw.name === 'string' && raw.name.trim() ? raw.name.trim() : undefined,
          status: raw.status,
          waitingFor: raw.waitingFor,
          entrypoint,
          updatedAt: raw.updatedAt,
        }
        const existing = out.get(raw.sessionId)
        if (existing) {
          const existingIsSdk = Boolean(existing.entrypoint && /^sdk\b/i.test(existing.entrypoint))
          const nextIsSdk = Boolean(entrypoint && /^sdk\b/i.test(entrypoint))
          // Prefer a real interactive CLI process over a headless SDK/ACP subprocess.
          if (!existingIsSdk && nextIsSdk) continue
        }
        out.set(raw.sessionId, nextMeta)
      } catch {
        // ignore malformed json
      }
    }
    return out
  }

  async inspectSessionTerminal(params: {
    nativeId: string
    engineId: string
    cwd?: string
    title?: string
    claudeRuntime?: ClaudeLiveSessionMeta
  }): Promise<LiveTerminalInspection | null> {
    if (process.platform !== 'darwin' && process.platform !== 'linux') return null
    let target = await this.findTarget(params)
    if (!target && this.topologyCache) {
      // Refresh terminal pane topology once in case a new tmux pane / iTerm2 tab opened seconds ago
      this.topologyCache = null
      target = await this.findTarget(params)
    }
    if (!target) {
      let hostAppHint: string | undefined
      if (params.claudeRuntime?.pid) {
        const isSdk = Boolean(
          params.claudeRuntime.entrypoint && /^sdk\b/i.test(params.claudeRuntime.entrypoint),
        )
        if (!isSdk) {
          const ancestry = await this.inspectProcessAncestry(params.claudeRuntime.pid)
          if (!ancestry.ownedBySelf) {
            hostAppHint = ancestry.hostAppHint
          }
        }
      }
      return { targetReachable: false, hostAppHint }
    }

    const lines = await this.readScreenTail(target, 45)
    if (!lines || lines.length === 0) {
      return { targetReachable: false }
    }

    const pendingPermission = parseTerminalPermissionPrompt(params.nativeId, lines)
    const liveStatusText = pendingPermission ? undefined : parseLiveSpinnerLine(lines)
    const liveMode = parseTerminalSessionMode(lines)
    const hasCancelHint = pendingPermission ? false : parseTerminalCancelHint(lines)

    return {
      targetReachable: true,
      liveStatusText,
      liveMode,
      pendingPermission,
      hasCancelHint,
    }
  }

  async sendPermissionKeystroke(
    params: {
      nativeId: string
      engineId: string
      cwd?: string
      title?: string
      claudeRuntime?: ClaudeLiveSessionMeta
    },
    optionId: string | null,
    expectedRequestId: string,
  ): Promise<boolean> {
    const target = await this.findTarget(params)
    if (!target) return false

    // SECURITY: never type arbitrary text into a live terminal. Re-read the screen right before
    // sending and require that the prompt currently displayed is the one the phone approved
    // (same `tty_perm_` hash), and that the chosen key is one of that prompt's numbered options.
    const screen = await this.readScreenTail(target, 45)
    const livePrompt = screen ? parseTerminalPermissionPrompt(params.nativeId, screen) : undefined
    if (!livePrompt) {
      throw new TerminalApprovalError('电脑终端上的这条审批已经结束或不在当前屏幕，已同步最新状态')
    }
    if (livePrompt.requestId !== expectedRequestId) {
      throw new TerminalApprovalError('电脑终端上的审批内容已变化，已为你同步最新选项，请再点一次确认')
    }
    const key = resolveApprovalKey(livePrompt, optionId)
    if (key === null) {
      throw new TerminalApprovalError('无效的审批选项')
    }

    await this.sendRawText(target, key)

    // Verify if the TUI prompt dismissed immediately on digit keystroke; if the exact same prompt
    // (including any `1 of N` queue counter) is still visible, confirm selection with Enter.
    if (key !== '\u001b') {
      await new Promise(r => setTimeout(r, 280))
      const afterLines = await this.readScreenTail(target, 25)
      const afterPrompt = afterLines ? parseTerminalPermissionPrompt(params.nativeId, afterLines) : undefined
      if (
        afterPrompt &&
        afterPrompt.requestId === livePrompt.requestId &&
        afterPrompt.title === livePrompt.title
      ) {
        await this.sendRawText(target, '\r')
      }
    }
    return true
  }

  /**
   * Delivers a user prompt directly into a verified live `tmux` or `iTerm2` pane running
   * the exact `claude` or `agy` CLI session (`nativeId`), so computer terminal & mobile UI
   * stay 100% synchronized without spawning a conflicting background process.
   */
  async sendPromptToSession(
    params: {
      nativeId: string
      engineId: string
      cwd?: string
      title?: string
      claudeRuntime?: ClaudeLiveSessionMeta
    },
    rawText: string,
  ): Promise<TerminalPromptDeliveryResult> {
    if (process.platform !== 'darwin' && process.platform !== 'linux') {
      return { delivered: false, reason: 'not_running' }
    }
    if (params.engineId !== 'claude' && params.engineId !== 'agy') {
      return { delivered: false, reason: 'not_running' }
    }

    const cleanText = sanitizeTerminalPromptText(rawText)
    if (!cleanText) {
      throw new TerminalApprovalError('发送内容不能为空')
    }

    // Force fresh PID/TTY verification right before injecting into any pane so we never
    // type into a bare shell if the CLI process just exited milliseconds earlier.
    this.agyProcCache = null
    if (params.claudeRuntime?.pid) {
      this.pidTtyCache.delete(params.claudeRuntime.pid)
    }

    let target = await this.findTarget(params)
    if (!target && this.topologyCache) {
      // Refresh terminal pane topology once in case a new tmux pane / iTerm2 tab opened seconds ago
      this.topologyCache = null
      target = await this.findTarget(params)
    }
    if (!target) {
      // Check whether the conversation is actively open in an unreachable terminal/IDE window
      if (params.engineId === 'claude') {
        const rt = params.claudeRuntime
        if (rt?.pid && rt.sessionId === params.nativeId && this.pidAlive(rt.pid)) {
          // Never mistake Hub's own `claude-agent-acp` / `claude-agent-sdk` child process
          // (or any headless non-TTY SDK subprocess) for an external interactive terminal!
          if (rt.entrypoint && /^sdk\b/i.test(rt.entrypoint)) {
            return { delivered: false, reason: 'not_running' }
          }
          const ancestry = await this.inspectProcessAncestry(rt.pid)
          if (ancestry.ownedBySelf) {
            return { delivered: false, reason: 'not_running' }
          }
          const tty = await this.getProcessTty(rt.pid)
          if (!tty) {
            return { delivered: false, reason: 'not_running' }
          }
          return { delivered: false, reason: 'unreachable_terminal', hostAppHint: ancestry.hostAppHint }
        }
        return { delivered: false, reason: 'not_running' }
      }

      const agyProcs = await this.getAgyTtyProcesses()
      const liveUnreachable = this.matchAgyProcess(params.nativeId, params.cwd, agyProcs)
      if (liveUnreachable && this.pidAlive(liveUnreachable.pid)) {
        const ancestry = await this.inspectProcessAncestry(liveUnreachable.pid)
        if (ancestry.ownedBySelf) {
          return { delivered: false, reason: 'not_running' }
        }
        return { delivered: false, reason: 'unreachable_terminal', hostAppHint: ancestry.hostAppHint }
      }
      return { delivered: false, reason: 'not_running' }
    }

    const screen = await this.readScreenTail(target, 35)
    if (!screen || screen.length === 0) {
      return { delivered: false, reason: 'unreachable_terminal' }
    }
    const activePerm = parseTerminalPermissionPrompt(params.nativeId, screen)
    if (activePerm) {
      throw new TerminalApprovalError('当前电脑终端正在等待执行审批，请先在手机或电脑上完成审批后再发送消息')
    }

    await this.sendPromptPayload(target, cleanText)
    return { delivered: true }
  }

  /**
   * Sends an interactive `/model <id>` command into a verified, idle live `tmux` / `iTerm2`
   * session (`claude` or `agy`) when the user switches the model from the mobile App.
   * Strict allowlist prevents any non-identifier characters from ever reaching the terminal.
   */
  async sendModelSwitchToSession(
    params: {
      nativeId: string
      engineId: string
      cwd?: string
      title?: string
      claudeRuntime?: ClaudeLiveSessionMeta
    },
    modelCommandArg: string,
  ): Promise<boolean> {
    if (process.platform !== 'darwin' && process.platform !== 'linux') return false
    if (params.engineId !== 'claude' && params.engineId !== 'agy') return false
    const cleanModel = modelCommandArg.trim()
    if (!/^[A-Za-z0-9._:@-]{1,96}$/.test(cleanModel)) {
      return false
    }

    this.agyProcCache = null
    if (params.claudeRuntime?.pid) {
      this.pidTtyCache.delete(params.claudeRuntime.pid)
    }

    let target = await this.findTarget(params)
    if (!target && this.topologyCache) {
      this.topologyCache = null
      target = await this.findTarget(params)
    }
    if (!target) return false

    const screen = await this.readScreenTail(target, 35)
    if (!screen || screen.length === 0) return false
    if (parseTerminalPermissionPrompt(params.nativeId, screen)) return false
    if (parseLiveSpinnerLine(screen)) return false

    await this.sendPromptPayload(target, `/model ${cleanModel}`)
    return true
  }

  async sendCancelToSession(params: {
    nativeId: string
    engineId: string
    cwd?: string
    title?: string
    claudeRuntime?: ClaudeLiveSessionMeta
  }): Promise<boolean> {
    if (process.platform !== 'darwin' && process.platform !== 'linux') return false
    this.agyProcCache = null
    if (params.claudeRuntime?.pid) {
      this.pidTtyCache.delete(params.claudeRuntime.pid)
    }
    let target = await this.findTarget(params)
    if (!target && this.topologyCache) {
      this.topologyCache = null
      target = await this.findTarget(params)
    }
    if (!target) return false
    await this.sendRawText(target, '\u001b')
    return true
  }

  async checkAgyPresenceLock(nativeId: string): Promise<{ locked: boolean; hostAppHint?: string }> {
    if (process.platform !== 'darwin' && process.platform !== 'linux') {
      return { locked: false }
    }
    this.agyProcCache = null
    const procs = await this.getAgyTtyProcesses()
    const holder = procs.find(p => p.presenceConvIds.includes(nativeId) && this.pidAlive(p.pid))
    if (holder) {
      const ancestry = await this.inspectProcessAncestry(holder.pid)
      if (!ancestry.ownedBySelf) {
        return { locked: true, hostAppHint: ancestry.hostAppHint }
      }
    }
    const lockFile = path.join(this.homeDir, '.gemini', 'antigravity-cli', 'presence', `${nativeId}.lock`)
    if (!fs.existsSync(lockFile)) return { locked: false }
    let out = ''
    const lsofBin = this.resolveLsofBin()
    if (lsofBin) {
      try {
        // Do NOT pass `-b` with an explicit file path on macOS: `lsof -b <file>` fails with
        // `Resource temporarily unavailable` during vnode/stat lookups.
        out = await this.runCmd(lsofBin, ['-w', '-Fp', lockFile], 2_000, augmentedPathEnv(process.env))
      } catch (err) {
        const partial = (err as { stdout?: unknown } | null)?.stdout
        if (typeof partial === 'string') out = partial
      }
    }
    for (const line of out.split('\n')) {
      if (!line.startsWith('p')) continue
      const pid = Number(line.slice(1).trim())
      if (pid > 0 && this.pidAlive(pid)) {
        // Never treat Hub's own persistent `agy --input-format stream-json` worker (or any
        // descendant of this Hub process) as an external interactive terminal holding the lock.
        const ancestry = await this.inspectProcessAncestry(pid)
        if (ancestry.ownedBySelf) continue
        return { locked: true, hostAppHint: ancestry.hostAppHint }
      }
    }
    return { locked: false }
  }

  private async findTarget(params: {
    nativeId: string
    engineId: string
    cwd?: string
    title?: string
    claudeRuntime?: ClaudeLiveSessionMeta
  }): Promise<TerminalTarget | null> {
    if (params.engineId === 'claude') {
      // Strict session isolation: ONLY bind to a terminal when we have a verified live PID
      // whose sessionId matches `nativeId` and whose OS tty matches an active tmux/iTerm2 pane.
      const rt = params.claudeRuntime
      if (!rt?.pid || rt.sessionId !== params.nativeId) {
        return null
      }
      if (!this.pidAlive(rt.pid)) {
        this.pidTtyCache.delete(rt.pid)
        return null
      }
      const tty = await this.getProcessTty(rt.pid)
      if (!tty) return null

      // Fast path: check lightweight tmux topology first without touching iTerm2 Python API (`it2`)!
      const tmuxTopo = await this.getTopology(false)
      if (tmuxTopo.byTty.has(tty)) {
        return tmuxTopo.byTty.get(tty)!
      }
      const fullTopo = await this.getTopology(true)
      return fullTopo.byTty.get(tty) ?? null
    }

    if (params.engineId === 'agy') {
      const tmuxTopo = await this.getTopology(false)
      const inTmux = await this.findAgyTarget(params.nativeId, params.cwd, tmuxTopo)
      if (inTmux) return inTmux
      const fullTopo = await this.getTopology(true)
      return this.findAgyTarget(params.nativeId, params.cwd, fullTopo)
    }

    return null
  }

  private matchAgyProcess(
    nativeId: string,
    sessionCwd: string | undefined,
    candidates: AgyTtyProcess[],
  ): AgyTtyProcess | null {
    if (candidates.length === 0) return null

    // 1. Exact active TUI presence lock (`~/.gemini/antigravity-cli/presence/<convId>.lock`) — authoritative!
    const byPresence = candidates.find(p => p.presenceConvIds.includes(nativeId))
    if (byPresence) return byPresence

    // 2. Exact `--conversation <nativeId>` match on command line (when not holding another conversation's lock)
    const byExplicitFlag = candidates.find(
      p => p.args.includes(nativeId) && (p.presenceConvIds.length === 0 || p.presenceConvIds.includes(nativeId)),
    )
    if (byExplicitFlag) return byExplicitFlag

    // 3. Unambiguous open SQLite conversation database (`conversations/<convId>.db`)
    const byOpenDb = candidates.filter(p => p.openConvIds.includes(nativeId))
    if (byOpenDb.length === 1) return byOpenDb[0]!

    // 4. Fallback: unambiguous 1-to-1 working directory match among `agy` processes that do NOT
    // hold an active presence lock for a different conversation (prevents closed historical
    // conversations in the same repo from colliding with a live terminal session!).
    if (sessionCwd) {
      const targetReal = safeRealpath(sessionCwd)
      const byCwd = candidates.filter(
        p => p.presenceConvIds.length === 0 && p.cwd && safeRealpath(p.cwd) === targetReal,
      )
      if (byCwd.length === 1) {
        return byCwd[0]!
      }
    }
    return null
  }

  private async findAgyTarget(
    nativeId: string,
    sessionCwd: string | undefined,
    topo: CachedTopology,
  ): Promise<TerminalTarget | null> {
    const procs = await this.getAgyTtyProcesses()
    const reachable = procs.filter(p => topo.byTty.has(p.tty))
    const matched = this.matchAgyProcess(nativeId, sessionCwd, reachable)
    return matched ? (topo.byTty.get(matched.tty) ?? null) : null
  }

  private async getAgyTtyProcesses(): Promise<AgyTtyProcess[]> {
    const now = Date.now()
    if (this.agyProcCache && now - this.agyProcCache.fetchedAt < 3_000) {
      return this.agyProcCache.list
    }

    const candidates: Array<{ pid: number; tty: string; args: string }> = []
    try {
      const stdout = await this.runCmd('ps', ['-axo', 'pid=,tty=,command='], 1_800)
      for (const rawLine of stdout.split('\n')) {
        const m = rawLine.trim().match(/^(\d+)\s+(\S+)\s+(.+)$/)
        if (!m) continue
        const pid = Number(m[1])
        const rawTty = m[2]!
        const cmd = m[3]!
        if (!pid || rawTty === '??' || rawTty === '?') continue
        if (
          cmd.includes('--hub') ||
          cmd.includes('Antigravity Helper') ||
          cmd.includes('--input-format') ||
          cmd.includes('stream-json') ||
          /\s(-p|--print|--prompt)(\s|$)/.test(cmd)
        ) {
          continue
        }
        const firstToken = cmd.split(/\s+/)[0] ?? ''
        const baseName = path.basename(firstToken)
        if (baseName !== 'agy' && !baseName.startsWith('agy.')) continue
        const tty = rawTty.startsWith('/dev/') ? rawTty : `/dev/${rawTty}`
        candidates.push({ pid, tty, args: cmd })
      }
    } catch {
      return []
    }

    if (candidates.length === 0) {
      this.agyProcCache = { fetchedAt: now, list: [] }
      return []
    }

    const cwdByPid = new Map<number, string>()
    const presenceByPid = new Map<number, Set<string>>()
    const openDbByPid = new Map<number, Set<string>>()
    const pidCsv = candidates.map(c => String(c.pid)).join(',')
    let lsofOut = ''
    const lsofBin = this.resolveLsofBin()
    if (lsofBin) {
      try {
        // `-b` prevents kernel blocks on sleeping external disks or Xcode CoreDevice FUSE mounts
        // for PID-scoped scans; `-n -P` skips DNS/port lookups and `-w` suppresses non-fatal
        // stat() warnings so lsof finishes in ~100ms instead of timing out!
        lsofOut = await this.runCmd(
          lsofBin,
          ['-b', '-n', '-P', '-w', '-Ffn', '-p', pidCsv],
          4_000,
          augmentedPathEnv(process.env),
        )
      } catch (err) {
        // On macOS, `lsof` exits with code 1 whenever any transient fd closes mid-scan while still
        // writing all valid process fds (`presence/*.lock`, `cwd`) to `stdout`!
        const partialStdout = (err as { stdout?: unknown } | null)?.stdout
        if (typeof partialStdout === 'string' && partialStdout.length > 0) {
          lsofOut = partialStdout
        }
      }
    }
    if (lsofOut) {
      let currentPid = 0
      let currentFd = ''
      for (const line of lsofOut.split('\n')) {
        if (line.startsWith('p')) {
          currentPid = Number(line.slice(1).trim()) || 0
          currentFd = ''
        } else if (line.startsWith('f')) {
          currentFd = line.slice(1).trim()
        } else if (line.startsWith('n') && currentPid > 0) {
          const filePath = line.slice(1).trim()
          if (currentFd === 'cwd' || (!currentFd && !cwdByPid.has(currentPid))) {
            cwdByPid.set(currentPid, filePath)
          }
          const presMatch = /[/\\]presence[/\\]([A-Za-z0-9_-]{6,64})\.lock$/.exec(filePath)
          if (presMatch?.[1]) {
            let set = presenceByPid.get(currentPid)
            if (!set) {
              set = new Set()
              presenceByPid.set(currentPid, set)
            }
            set.add(presMatch[1])
          }
          const dbMatch = /[/\\]conversations[/\\]([A-Za-z0-9_-]{6,64})\.db$/.exec(filePath)
          if (dbMatch?.[1]) {
            let set = openDbByPid.get(currentPid)
            if (!set) {
              set = new Set()
              openDbByPid.set(currentPid, set)
            }
            set.add(dbMatch[1])
          }
        }
      }
    }

    const list: AgyTtyProcess[] = candidates.map(c => ({
      ...c,
      cwd: cwdByPid.get(c.pid) ?? '',
      presenceConvIds: [...(presenceByPid.get(c.pid) ?? [])],
      openConvIds: [...(openDbByPid.get(c.pid) ?? [])],
    }))
    this.agyProcCache = { fetchedAt: now, list }
    return list
  }

  private async getProcessTty(pid: number): Promise<string | null> {
    if (this.pidTtyCache.has(pid)) {
      return this.pidTtyCache.get(pid) ?? null
    }
    try {
      const stdout = await this.runCmd('ps', ['-o', 'tty=', '-p', String(pid)], 1_500)
      const raw = stdout.trim()
      const tty = !raw || raw === '??' || raw === '?' ? null : raw.startsWith('/dev/') ? raw : `/dev/${raw}`
      this.pidTtyCache.set(pid, tty)
      return tty
    } catch {
      return null
    }
  }

  private discoverTmuxSocketPaths(): string[] {
    if (this.opts.tmuxSocketPaths !== undefined) {
      return this.opts.tmuxSocketPaths
    }
    const uid = typeof process.getuid === 'function' ? process.getuid() : null
    const candidateDirs = new Set<string>()
    if (uid !== null) {
      candidateDirs.add(`/private/tmp/tmux-${uid}`)
      candidateDirs.add(`/tmp/tmux-${uid}`)
      candidateDirs.add(path.join(os.tmpdir(), `tmux-${uid}`))
      if (process.env.TMUX_TMPDIR) {
        candidateDirs.add(path.join(process.env.TMUX_TMPDIR, `tmux-${uid}`))
      }
    }
    const seenReal = new Set<string>()
    const sockets: string[] = []
    const envTmuxSock = process.env.TMUX?.split(',')[0]?.trim()
    if (envTmuxSock && fs.existsSync(envTmuxSock)) {
      const rp = safeRealpath(envTmuxSock)
      seenReal.add(rp)
      sockets.push(rp)
    }
    for (const dir of candidateDirs) {
      if (!fs.existsSync(dir)) continue
      let names: string[] = []
      try {
        names = fs.readdirSync(dir)
      } catch {
        continue
      }
      for (const name of names) {
        try {
          const full = path.join(dir, name)
          const st = fs.statSync(full)
          if (!st.isSocket()) continue
          const rp = safeRealpath(full)
          if (!seenReal.has(rp)) {
            seenReal.add(rp)
            sockets.push(rp)
          }
        } catch {
          // ignore unreadable or transient socket entry
        }
      }
    }
    return sockets
  }

  private async inspectProcessAncestry(
    pid: number,
  ): Promise<{ ownedBySelf: boolean; hostAppHint?: string }> {
    if (pid === this.selfPid) {
      return { ownedBySelf: true }
    }
    let cur = pid
    for (let depth = 0; depth < 6 && cur > 1; depth++) {
      try {
        const out = await this.runCmd('ps', ['-o', 'ppid=,command=', '-p', String(cur)], 1_200)
        const m = out.trim().match(/^(\d+)\s+(.+)$/)
        if (!m) break
        const ppid = Number(m[1]) || 0
        const cmd = m[2]!
        if (ppid === this.selfPid) {
          return { ownedBySelf: true }
        }
        if (/GoLand\.app/i.test(cmd)) return { ownedBySelf: false, hostAppHint: 'GoLand' }
        if (/DataGrip\.app/i.test(cmd)) return { ownedBySelf: false, hostAppHint: 'DataGrip' }
        if (/IntelliJ IDEA\.app/i.test(cmd)) return { ownedBySelf: false, hostAppHint: 'IntelliJ IDEA' }
        if (/Android Studio\.app/i.test(cmd)) return { ownedBySelf: false, hostAppHint: 'Android Studio' }
        if (/Cursor\.app/i.test(cmd)) return { ownedBySelf: false, hostAppHint: 'Cursor' }
        if (/Visual Studio Code\.app|Code\.app/i.test(cmd)) {
          return { ownedBySelf: false, hostAppHint: 'VS Code' }
        }
        if (/Terminal\.app/i.test(cmd)) return { ownedBySelf: false, hostAppHint: 'macOS Terminal' }
        cur = ppid
      } catch {
        break
      }
    }
    return { ownedBySelf: false }
  }

  private async detectParentHostApp(pid: number): Promise<string | undefined> {
    return (await this.inspectProcessAncestry(pid)).hostAppHint
  }

  private async getTopology(includeIt2 = false): Promise<CachedTopology> {
    const now = Date.now()
    if (
      this.topologyCache &&
      now - this.topologyCache.fetchedAt < 10_000 &&
      (!includeIt2 || (this.topologyCache.it2FetchedAt > 0 && now - this.topologyCache.it2FetchedAt < 20_000))
    ) {
      return this.topologyCache
    }
    if (this.topologyLoading) return this.topologyLoading

    this.topologyLoading = (async () => {
      const byTty = new Map<string, TerminalTarget>()
      // Preserve recently fetched iTerm2 entries when only refreshing tmux panes
      if (!includeIt2 && this.topologyCache && now - this.topologyCache.it2FetchedAt < 20_000) {
        for (const [k, v] of this.topologyCache.byTty) {
          if (v.kind === 'iterm2') byTty.set(k, v)
        }
      }
      const env = augmentedPathEnv(process.env)

      // 1. Prefer lightweight tmux pane mapping first (~3ms C binary).
      // CRITICAL under macOS `launchd`: `launchd` sets `TMPDIR=/var/folders/.../T/` while user/ccmux
      // tmux servers often listen on `/private/tmp/tmux-<uid>/default`. Probe every discovered socket
      // explicitly with `tmux -S <socketPath>` so launchd daemons never miss live tmux servers!
      const tmuxBin = this.resolveTmuxBin()
      if (tmuxBin) {
        const socketPaths = this.discoverTmuxSocketPaths()
        const probeTargets: Array<string | undefined> =
          socketPaths.length > 0 ? socketPaths : [undefined]

        for (const sock of probeTargets) {
          try {
            // `-u` forces UTF-8 mode and `|` avoids C-locale control-char sanitization (`\t` -> `_`) under launchd!
            const args = sock
              ? ['-u', '-S', sock, 'list-panes', '-a', '-F', '#{pane_id}|#{pane_tty}|#{pane_in_mode}']
              : ['-u', 'list-panes', '-a', '-F', '#{pane_id}|#{pane_tty}|#{pane_in_mode}']
            const stdout = await this.runCmd(tmuxBin, args, 2_000, env)
            for (const line of stdout.split('\n')) {
              const trimmed = line.trim()
              if (!trimmed) continue
              const parts = trimmed.includes('|') ? trimmed.split('|') : trimmed.split('\t')
              const paneId = parts[0]?.trim()
              const paneTty = parts[1]?.trim()
              const inCopyMode = parts[2]?.trim() === '1'
              if (paneId && paneTty) {
                byTty.set(paneTty, { kind: 'tmux', id: paneId, tty: paneTty, socketPath: sock, inCopyMode })
              }
            }
          } catch {
            // ignore dead socket or inactive server
          }
        }
      }

      // 2. Lazy iTerm2 session mapping via `it2` (~300ms Python IPC — ONLY invoked when target TTY isn't in tmux!)
      let it2FetchedAt = this.topologyCache?.it2FetchedAt ?? 0
      if (includeIt2) {
        const it2Bin = this.resolveIt2Bin()
        if (it2Bin) {
          try {
            const stdout = await this.runCmd(it2Bin, ['session', 'list', '--json'], 2_500, env)
            const parsed = JSON.parse(stdout) as Array<{
              id?: string
              tty?: string
            }>
            for (const item of parsed) {
              if (!item.id || !item.tty) continue
              if (!byTty.has(item.tty)) {
                byTty.set(item.tty, { kind: 'iterm2', id: item.id, tty: item.tty })
              }
            }
          } catch {
            // iTerm2 not running or API disabled
          }
        }
        it2FetchedAt = Date.now()
      }

      const next: CachedTopology = { fetchedAt: Date.now(), it2FetchedAt, byTty }
      this.topologyCache = next
      return next
    })()

    try {
      return await this.topologyLoading
    } finally {
      this.topologyLoading = null
    }
  }

  private async readScreenTail(target: TerminalTarget, lines: number): Promise<string[] | null> {
    const env = augmentedPathEnv(process.env)
    try {
      if (target.kind === 'iterm2') {
        const it2Bin = this.resolveIt2Bin()
        if (!it2Bin) return null
        const stdout = await this.runCmd(
          it2Bin,
          ['session', 'read', '-s', target.id, '-n', String(lines)],
          2_000,
          env,
        )
        return stdout.split(/\r?\n/)
      } else {
        const tmuxBin = this.resolveTmuxBin()
        if (!tmuxBin) return null
        const prefix = target.socketPath ? ['-u', '-S', target.socketPath] : ['-u']
        const stdout = await this.runCmd(
          tmuxBin,
          [...prefix, 'capture-pane', '-p', '-t', target.id, '-S', `-${lines}`],
          2_000,
          env,
        )
        return stdout.split(/\r?\n/)
      }
    } catch {
      return null
    }
  }

  private async sendRawText(target: TerminalTarget, text: string): Promise<void> {
    // Only single approval digits, Enter or Escape may ever be injected.
    if (!/^[1-9]$/.test(text) && text !== '\r' && text !== '\u001b') {
      throw new TerminalApprovalError('Refusing to inject non-approval keystroke')
    }
    const env = augmentedPathEnv(process.env)
    if (target.kind === 'iterm2') {
      const it2Bin = this.resolveIt2Bin()
      if (!it2Bin) throw new Error('it2 CLI not available')
      await this.runCmd(it2Bin, ['session', 'send', text, '-s', target.id], 2_500, env)
    } else {
      const tmuxBin = this.resolveTmuxBin()
      if (!tmuxBin) throw new Error('tmux not available')
      const prefix = target.socketPath ? ['-u', '-S', target.socketPath] : ['-u']
      if (target.inCopyMode) {
        await this.runCmd(tmuxBin, [...prefix, 'send-keys', '-t', target.id, '-X', 'cancel'], 1_200, env).catch(
          () => undefined,
        )
      }
      const keyArgs = text === '\r' ? ['Enter'] : text === '\u001b' ? ['Escape'] : ['-l', text]
      await this.runCmd(tmuxBin, [...prefix, 'send-keys', '-t', target.id, ...keyArgs], 2_500, env)
    }
  }

  private async sendPromptPayload(target: TerminalTarget, cleanText: string): Promise<void> {
    const env = augmentedPathEnv(process.env)
    const isMultiline = cleanText.includes('\n')

    if (target.kind === 'iterm2') {
      const it2Bin = this.resolveIt2Bin()
      if (!it2Bin) throw new Error('it2 CLI not available')
      const body = isMultiline ? `\u001b[200~${cleanText}\u001b[201~` : cleanText
      await this.runCmd(it2Bin, ['session', 'send', body, '-s', target.id], 3_500, env)
      await new Promise(r => setTimeout(r, 120))
      await this.runCmd(it2Bin, ['session', 'send', '\r', '-s', target.id], 2_500, env)
      return
    }

    const tmuxBin = this.resolveTmuxBin()
    if (!tmuxBin) throw new Error('tmux not available')
    const prefix = target.socketPath ? ['-u', '-S', target.socketPath] : ['-u']
    if (target.inCopyMode) {
      await this.runCmd(tmuxBin, [...prefix, 'send-keys', '-t', target.id, '-X', 'cancel'], 1_200, env).catch(
        () => undefined,
      )
    }
    if (isMultiline) {
      const bufName = `lework_prompt_${crypto.randomBytes(4).toString('hex')}`
      try {
        await this.runCmd(tmuxBin, [...prefix, 'set-buffer', '-b', bufName, '--', cleanText], 3_000, env)
        await this.runCmd(tmuxBin, [...prefix, 'paste-buffer', '-d', '-p', '-b', bufName, '-t', target.id], 3_000, env)
      } finally {
        await this.runCmd(tmuxBin, [...prefix, 'delete-buffer', '-b', bufName], 1_000, env).catch(() => undefined)
      }
    } else {
      await this.runCmd(tmuxBin, [...prefix, 'send-keys', '-t', target.id, '-l', '--', cleanText], 3_000, env)
    }
    await new Promise(r => setTimeout(r, 120))
    await this.runCmd(tmuxBin, [...prefix, 'send-keys', '-t', target.id, 'Enter'], 2_500, env)
  }
}

/**
 * Strips terminal escape sequences and C0 control bytes (`ESC`, `Ctrl+C`, `Ctrl+D`, `NUL`, etc.)
 * while preserving Unicode text and line breaks (`\n`).
 */
export function sanitizeTerminalPromptText(raw: string): string {
  return raw
    .replace(/\r\n?/g, '\n')
    .replace(/\t/g, '  ')
    .replace(/[\x00-\x08\x0b-\x1f\x7f]/g, '')
    .trim()
}

export class TerminalApprovalError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'TerminalApprovalError'
  }
}

/**
 * Maps a phone decision onto a keystroke for the prompt currently on screen.
 * Returns `null` for anything that is not a legitimate option of that prompt.
 */
export function resolveApprovalKey(prompt: DetectedTerminalPermission, optionId: string | null): string | null {
  if (optionId === null) return '\u001b'
  const byId = prompt.options.find(o => o.id === optionId)
  if (byId) {
    const m = /^tty_key:([1-9])$/.exec(byId.id)
    return m ? m[1]! : null
  }
  // Legacy semantic ids (allow_once / reject_once …) → first option of the matching kind.
  // Anything else (unknown tty_key:*, free text) is refused.
  if (!/^[a-z_]{1,32}$/.test(optionId)) return null
  const wantReject = /reject|deny/.test(optionId)
  const wantAlways = /always|session/.test(optionId)
  const pick =
    prompt.options.find(o =>
      wantReject ? o.kind === 'reject_once' : wantAlways ? o.kind === 'allow_always' : o.kind === 'allow_once',
    ) ?? null
  const m = pick ? /^tty_key:([1-9])$/.exec(pick.id) : null
  return m ? m[1]! : null
}

/**
 * Extracts Claude Code / AGY live spinner status lines such as:
 *   `· Sketching… (2m 1s · ↓ 1.0k tokens)`
 *   `✻ Thinking… (18s · ↑ 420 tokens)`
 *   `⣷ Running command...`
 */
export function parseLiveSpinnerLine(lines: string[]): string | undefined {
  let contentRowsChecked = 0
  let totalRowsChecked = 0
  for (let i = lines.length - 1; i >= 0; i--) {
    const trimmed = lines[i]?.trim() ?? ''
    if (!trimmed) continue
    // Skip bottom composer border & footer hints
    if (
      trimmed.startsWith('─') ||
      trimmed.startsWith('╌') ||
      trimmed.startsWith('❯') ||
      trimmed.startsWith('>') ||
      trimmed.startsWith('? for shortcuts') ||
      trimmed.startsWith('esc to cancel') ||
      trimmed.startsWith('⏸') ||
      trimmed.startsWith('⏵⏵')
    ) {
      continue
    }

    totalRowsChecked++
    if (totalRowsChecked > 24) {
      break
    }

    // Never match background daemon badges (`(ctrl+o to expand)`) or past-tense completed summaries
    if (
      trimmed.includes('(ctrl+o to expand)') ||
      /^.\s+(Ran|Read|Edited|Explored|Created|Wrote|Searched|Fetched|Baked|Cooked|Worked|Thought\s+for)\b/i.test(
        trimmed,
      )
    ) {
      continue
    }

    // Skip AGY / CLI step-checklist tree rows (`└ ● Step title`, `○ Pending step`, `✓ Done step`)
    // without exhausting the content row budget so the live spinner header directly above them is found.
    if (/^[└├│]?\s*[●○✓✔✗✘⊘▸]\s+\S+/.test(trimmed)) {
      continue
    }

    contentRowsChecked++
    if (contentRowsChecked > 6) {
      break
    }

    // 1. Active braille spinner frames (`⣷ Running command...`, `⠇ 0/3 steps · Running command... 17s`)
    if (/^[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏⣷⣯⣟⡿⢿⣻⣽⣾]\s+\S+/.test(trimmed)) {
      return trimmed.replace(/\s+/g, ' ').slice(0, 120)
    }

    // 2. Claude Code active star spinner (`· Sketching… (2m 1s · ↓ 1.0k tokens)`, `✻ Thinking…`)
    if (
      /^[·✻✽✢✳✶]\s+\S+/.test(trimmed) &&
      (trimmed.includes('…') || trimmed.includes('...'))
    ) {
      return trimmed.replace(/\s+/g, ' ').slice(0, 120)
    }

    // Once we encounter finished assistant prose above the prompt box, there is no active bottom spinner
    break
  }
  return undefined
}

const APPROVAL_QUESTION_RE =
  /\b(Do you want to\s+.+\?|Would you like to\s+.+\?|Allow\s+.+\?|Approve\s+.+\?|Run\s+(this\s+)?command.*\?|requires?\s+(elevated\s+)?(user\s+)?(permission|approval)|run\s+outside\s+(the\s+)?sandbox)/i

function isExplicitApprovalOptionLabel(label: string): 'allow' | 'reject' | null {
  const clean = label.trim().toLowerCase()
  if (/^(yes\b|allow\b|approve\b|run\s+once\b|always\s+allow\b|允许|确认执行)/.test(clean)) {
    return 'allow'
  }
  if (/^(no\b|deny\b|reject\b|cancel\b|don't\s+allow\b|拒绝|取消)/.test(clean)) {
    return 'reject'
  }
  return null
}

/**
 * Parses interactive TUI permission prompts across Claude Code & AGY:
 * - `Do you want to (proceed|overwrite|edit|execute|fetch|use|create|allow|run)...?`
 * - `Would you like to proceed?` (Plan confirmation)
 * - `Allow ...?` / AGY sandbox elevation & command approval menus
 * Never false-matches ordinary Markdown numbered lists in assistant prose.
 */
export function parseTerminalPermissionPrompt(
  nativeId: string,
  lines: string[],
): DetectedTerminalPermission | undefined {
  let questionIdx = -1
  for (let i = lines.length - 1; i >= 0; i--) {
    const cur = (lines[i] ?? '').trim()
    const twoLine = i + 1 < lines.length ? `${cur} ${(lines[i + 1] ?? '').trim()}` : cur
    if (APPROVAL_QUESTION_RE.test(cur)) {
      questionIdx = i
      break
    }
    if (cur && APPROVAL_QUESTION_RE.test(twoLine) && !/^\s*[❯>•*]?\s*[1-9][.)]\s+/.test(lines[i + 1] ?? '')) {
      questionIdx = i + 1
      break
    }
  }

  // Fallback: detect bottom-anchored numbered approval menu (e.g. AGY / TUI where header phrasing varies)
  // STRICT GUARD: requires >=2 numbered items in the bottom 15 lines containing BOTH an explicit Allow/Yes
  // AND an explicit Deny/No option, so normal Markdown numbered lists are never matched.
  if (questionIdx < 0) {
    const tailStart = Math.max(0, lines.length - 15)
    let firstOptIdx = -1
    let sawAllow = false
    let sawReject = false
    for (let i = tailStart; i < lines.length; i++) {
      const m = (lines[i] ?? '').match(/^\s*[❯>•*]?\s*([1-9])[.)]\s+(.+?)\s*$/)
      if (m) {
        const role = isExplicitApprovalOptionLabel(m[2]!)
        if (role) {
          if (firstOptIdx < 0) firstOptIdx = i
          if (role === 'allow') sawAllow = true
          if (role === 'reject') sawReject = true
        }
      }
    }
    if (firstOptIdx > 0 && sawAllow && sawReject) {
      questionIdx = firstOptIdx - 1
    }
  }

  if (questionIdx < 0) return undefined

  const options: PermissionOption[] = []
  for (let i = questionIdx + 1; i < lines.length; i++) {
    const line = lines[i] ?? ''
    const m = line.match(/^\s*[❯>•*]?\s*([1-9])[.)]\s+(.+?)\s*$/)
    if (m) {
      const digit = m[1]!
      const label = m[2]!
      const lower = label.toLowerCase()
      const kind: PermissionOption['kind'] =
        lower.startsWith('no') ||
        lower.includes('deny') ||
        lower.includes('reject') ||
        lower.includes('cancel') ||
        lower.startsWith('拒绝')
          ? 'reject_once'
          : lower.includes("don't ask") ||
              lower.includes('always') ||
              lower.includes('for this session') ||
              lower.includes('during this session') ||
              lower.includes('allow all') ||
              lower.includes('始终')
            ? 'allow_always'
            : 'allow_once'
      // Skip free-text TUI input rows like `3. Type here to tell Claude what to do differently`
      if (/^type\s+here\b/i.test(label.trim())) {
        continue
      }
      options.push({
        id: `tty_key:${digit}`,
        name: `${digit}. ${label}`,
        kind,
      })
    }
  }
  if (options.length === 0) return undefined

  // Extract human context & command preview above `questionIdx`
  const contextHeaders: string[] = []
  const codePreviewLines: string[] = []
  let topBoxIdx = Math.max(0, questionIdx - 18)
  for (let i = questionIdx - 1; i >= 0; i--) {
    const t = (lines[i] ?? '').trim()
    if (t.startsWith('───') || t.startsWith('╭──') || t.startsWith('┌──')) {
      topBoxIdx = i + 1
      break
    }
  }
  for (let i = topBoxIdx; i <= questionIdx; i++) {
    const raw = (lines[i] ?? '').trim()
    if (!raw || raw.startsWith('╌') || raw.startsWith('───') || raw.startsWith('╭') || raw.startsWith('╰')) {
      continue
    }
    if (i === questionIdx && /^Do you want to proceed\??$/i.test(raw)) {
      continue
    }
    if (raw.startsWith('│') || raw.startsWith('┃')) {
      const clean = raw.replace(/^[│┃]\s?/, '').trim()
      if (
        clean &&
        !clean.startsWith('Quoted text in this command') &&
        !clean.startsWith('code on stdin cannot be checked') &&
        !clean.startsWith('(permissions.')
      ) {
        codePreviewLines.push(clean)
      }
    } else {
      contextHeaders.push(raw.replace(/\s{2,}/g, ' · '))
    }
  }

  const headerPart = contextHeaders.slice(0, 2).join(' · ')
  const cmdPart = codePreviewLines.join(' ').slice(0, 200)
  const fullTitle = [headerPart, cmdPart].filter(Boolean).join('\n') || 'Agent 请求执行审批'

  // Normalize volatile queue counters (`1 of 2` -> `1 of 3` when another subagent queues behind the
  // current prompt) so the active prompt's requestId does not churn while the user taps Confirm.
  const stableTitleForHash = [
    headerPart.replace(/(?:\s*·\s*|\s+)\d+\s+of\s+\d+\b/gi, '').trim(),
    cmdPart,
  ]
    .filter(Boolean)
    .join('\n')

  const hash = crypto
    .createHash('sha1')
    .update(`${nativeId}|${stableTitleForHash}|${options.map(o => o.name).join(',')}`)
    .digest('hex')
    .slice(0, 10)

  return {
    requestId: `tty_perm_${hash}`,
    title: fullTitle,
    options,
  }
}

/**
 * Parses the live permission mode displayed in the bottom status bar of Claude Code / AGY TUI:
 *   `⏸ plan mode on (shift+tab to cycle)`
 *   `⏵⏵ accept edits on`
 *   `⏵⏵ auto mode on`
 *   `⏵⏵ bypass permissions on`
 */
export function parseTerminalCancelHint(lines: string[]): boolean {
  const bottom = lines
    .map(l => l.trim())
    .filter(Boolean)
    .slice(-8)
    .join('\n')
    .toLowerCase()
  return /\besc\s+to\s+(cancel|interrupt)\b/.test(bottom)
}

export function parseTerminalSessionMode(lines: string[]): SessionMode | undefined {
  const bottom = lines.slice(-6).join('\n').toLowerCase()
  if (bottom.includes('plan mode on') || /⏸\s*plan\b/.test(bottom)) return 'plan'
  if (bottom.includes('bypass permissions on')) return 'bypassPermissions'
  if (bottom.includes('accept edits on')) return 'acceptEdits'
  if (bottom.includes('auto mode on') || /⏵⏵\s*auto\b/.test(bottom) || /\bauto\s*·\s*gemini\b/.test(bottom)) {
    return 'auto'
  }
  return undefined
}
