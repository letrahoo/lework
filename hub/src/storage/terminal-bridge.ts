import crypto from 'node:crypto'
import { execFile } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { promisify } from 'node:util'
import type { PermissionOption, SessionMode } from '@qprc/protocol'
import { augmentedPathEnv, findExecutable } from '../platform/index.ts'

const execFileAsync = promisify(execFile)

export interface ClaudeLiveSessionMeta {
  pid: number
  sessionId: string
  cwd: string
  name?: string
  status?: string
  waitingFor?: string | null
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
  it2Bin?: string | null
  tmuxBin?: string | null
  tmuxSocketPaths?: string[]
}

type TerminalTarget =
  | { kind: 'iterm2'; id: string; tty: string }
  | { kind: 'tmux'; id: string; tty: string; socketPath?: string }

interface CachedTopology {
  fetchedAt: number
  byTty: Map<string, TerminalTarget>
}

interface AgyTtyProcess {
  pid: number
  tty: string
  args: string
  cwd: string
}

interface CachedAgyProcesses {
  fetchedAt: number
  list: AgyTtyProcess[]
}

const defaultRunCommand: TerminalCommandRunner = async (bin, args, timeoutMs, env) => {
  const { stdout } = await execFileAsync(bin, args, {
    timeout: timeoutMs,
    encoding: 'utf8',
    ...(env ? { env } : {}),
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
  private readonly homeDir: string
  private readonly runCmd: TerminalCommandRunner
  private readonly pidAlive: (pid: number) => boolean
  private readonly opts: TerminalBridgeOptions

  constructor(opts: TerminalBridgeOptions = {}) {
    this.opts = opts
    this.homeDir = opts.homeDir ?? os.homedir()
    this.runCmd = opts.runCommand ?? defaultRunCommand
    this.pidAlive = opts.isPidAlive ?? defaultIsPidAlive
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
          updatedAt?: number
        }
        if (typeof raw.pid !== 'number' || typeof raw.sessionId !== 'string') continue
        if (!this.pidAlive(raw.pid)) continue
        out.set(raw.sessionId, {
          pid: raw.pid,
          sessionId: raw.sessionId,
          cwd: raw.cwd ?? '',
          name: typeof raw.name === 'string' && raw.name.trim() ? raw.name.trim() : undefined,
          status: raw.status,
          waitingFor: raw.waitingFor,
          updatedAt: raw.updatedAt,
        })
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
    const target = await this.findTarget(params)
    if (!target) {
      const hostAppHint =
        params.claudeRuntime?.pid ? await this.detectParentHostApp(params.claudeRuntime.pid) : undefined
      return { targetReachable: false, hostAppHint }
    }

    const lines = await this.readScreenTail(target, 32)
    if (!lines || lines.length === 0) {
      return { targetReachable: false }
    }

    const pendingPermission = parseTerminalPermissionPrompt(params.nativeId, lines)
    const liveStatusText = pendingPermission ? undefined : parseLiveSpinnerLine(lines)
    const liveMode = parseTerminalSessionMode(lines)

    return {
      targetReachable: true,
      liveStatusText,
      liveMode,
      pendingPermission,
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
  ): Promise<boolean> {
    const target = await this.findTarget(params)
    if (!target) return false

    const key =
      optionId === null
        ? '\u001b'
        : optionId.startsWith('tty_key:')
          ? optionId.slice('tty_key:'.length)
          : optionId.includes('reject') || optionId.includes('deny')
            ? '2'
            : '1'

    await this.sendRawText(target, key)

    // Verify if the TUI prompt dismissed immediately on digit keystroke; if still visible, confirm with Enter
    if (key !== '\u001b') {
      await new Promise(r => setTimeout(r, 280))
      const afterLines = await this.readScreenTail(target, 14)
      if (afterLines && parseTerminalPermissionPrompt(params.nativeId, afterLines) !== undefined) {
        await this.sendRawText(target, '\r')
      }
    }
    return true
  }

  private async findTarget(params: {
    nativeId: string
    engineId: string
    cwd?: string
    title?: string
    claudeRuntime?: ClaudeLiveSessionMeta
  }): Promise<TerminalTarget | null> {
    const topo = await this.getTopology()
    if (topo.byTty.size === 0) return null

    if (params.engineId === 'claude') {
      // Strict session isolation: ONLY bind to a terminal when we have a verified live PID
      // whose sessionId matches `nativeId` and whose OS tty matches an active iTerm2/tmux pane.
      const rt = params.claudeRuntime
      if (!rt?.pid || rt.sessionId !== params.nativeId) {
        return null
      }
      if (!this.pidAlive(rt.pid)) {
        return null
      }
      const tty = await this.getProcessTty(rt.pid)
      if (tty && topo.byTty.has(tty)) {
        return topo.byTty.get(tty)!
      }
      return null
    }

    if (params.engineId === 'agy') {
      return this.findAgyTarget(params.nativeId, params.cwd, topo)
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
    if (reachable.length === 0) return null

    // 1. Exact `--conversation <nativeId>` match on command line
    const byExplicitFlag = reachable.find(p => p.args.includes(nativeId))
    if (byExplicitFlag) {
      return topo.byTty.get(byExplicitFlag.tty) ?? null
    }

    // 2. Unambiguous 1-to-1 working directory match among live TTY-attached `agy` CLI processes
    if (sessionCwd) {
      const targetReal = safeRealpath(sessionCwd)
      const byCwd = reachable.filter(p => p.cwd && safeRealpath(p.cwd) === targetReal)
      if (byCwd.length === 1) {
        return topo.byTty.get(byCwd[0]!.tty) ?? null
      }
    }
    return null
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
        if (cmd.includes('--hub') || cmd.includes('Antigravity Helper')) continue
        const firstToken = cmd.split(/\s+/)[0] ?? ''
        if (path.basename(firstToken) !== 'agy') continue
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
    try {
      const pidCsv = candidates.map(c => String(c.pid)).join(',')
      const lsofOut = await this.runCmd('lsof', ['-a', '-d', 'cwd', '-Fn', '-p', pidCsv], 1_800)
      let currentPid = 0
      for (const line of lsofOut.split('\n')) {
        if (line.startsWith('p')) {
          currentPid = Number(line.slice(1).trim()) || 0
        } else if (line.startsWith('n') && currentPid > 0) {
          cwdByPid.set(currentPid, line.slice(1).trim())
        }
      }
    } catch {
      // ignore lsof failure
    }

    const list: AgyTtyProcess[] = candidates.map(c => ({
      ...c,
      cwd: cwdByPid.get(c.pid) ?? '',
    }))
    this.agyProcCache = { fetchedAt: now, list }
    return list
  }

  private async getProcessTty(pid: number): Promise<string | null> {
    try {
      const stdout = await this.runCmd('ps', ['-o', 'tty=', '-p', String(pid)], 1_500)
      const raw = stdout.trim()
      if (!raw || raw === '??' || raw === '?') return null
      return raw.startsWith('/dev/') ? raw : `/dev/${raw}`
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

  private async detectParentHostApp(pid: number): Promise<string | undefined> {
    let cur = pid
    for (let depth = 0; depth < 5 && cur > 1; depth++) {
      try {
        const out = await this.runCmd('ps', ['-o', 'ppid=,command=', '-p', String(cur)], 1_200)
        const m = out.trim().match(/^(\d+)\s+(.+)$/)
        if (!m) break
        const ppid = Number(m[1]) || 0
        const cmd = m[2]!
        if (/GoLand\.app/i.test(cmd)) return 'GoLand'
        if (/DataGrip\.app/i.test(cmd)) return 'DataGrip'
        if (/IntelliJ IDEA\.app/i.test(cmd)) return 'IntelliJ IDEA'
        if (/Android Studio\.app/i.test(cmd)) return 'Android Studio'
        if (/Cursor\.app/i.test(cmd)) return 'Cursor'
        if (/Visual Studio Code\.app|Code\.app/i.test(cmd)) return 'VS Code'
        if (/Terminal\.app/i.test(cmd)) return 'macOS Terminal'
        cur = ppid
      } catch {
        break
      }
    }
    return undefined
  }

  private async getTopology(): Promise<CachedTopology> {
    const now = Date.now()
    if (this.topologyCache && now - this.topologyCache.fetchedAt < 4_000) {
      return this.topologyCache
    }
    if (this.topologyLoading) return this.topologyLoading

    this.topologyLoading = (async () => {
      const byTty = new Map<string, TerminalTarget>()
      const env = augmentedPathEnv(process.env)

      // 1. Prefer tmux pane mapping first.
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
              ? ['-u', '-S', sock, 'list-panes', '-a', '-F', '#{pane_id}|#{pane_tty}']
              : ['-u', 'list-panes', '-a', '-F', '#{pane_id}|#{pane_tty}']
            const stdout = await this.runCmd(tmuxBin, args, 2_000, env)
            for (const line of stdout.split('\n')) {
              const trimmed = line.trim()
              if (!trimmed) continue
              const sepIdx = trimmed.includes('|') ? trimmed.indexOf('|') : trimmed.indexOf('\t')
              if (sepIdx <= 0) continue
              const paneId = trimmed.slice(0, sepIdx).trim()
              const paneTty = trimmed.slice(sepIdx + 1).trim()
              if (paneId && paneTty && !byTty.has(paneTty)) {
                byTty.set(paneTty, { kind: 'tmux', id: paneId, tty: paneTty, socketPath: sock })
              }
            }
          } catch {
            // ignore dead socket or inactive server
          }
        }
      }

      // 2. iTerm2 session mapping via `it2`
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

      const next: CachedTopology = { fetchedAt: Date.now(), byTty }
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
    const env = augmentedPathEnv(process.env)
    if (target.kind === 'iterm2') {
      const it2Bin = this.resolveIt2Bin()
      if (!it2Bin) throw new Error('it2 CLI not available')
      await this.runCmd(it2Bin, ['session', 'send', text, '-s', target.id], 2_500, env)
    } else {
      const tmuxBin = this.resolveTmuxBin()
      if (!tmuxBin) throw new Error('tmux not available')
      const keyArg = text === '\r' ? 'Enter' : text === '\u001b' ? 'Escape' : text
      const prefix = target.socketPath ? ['-u', '-S', target.socketPath] : ['-u']
      await this.runCmd(tmuxBin, [...prefix, 'send-keys', '-t', target.id, keyArg], 2_500, env)
    }
  }
}

/**
 * Extracts Claude Code / AGY live spinner status lines such as:
 *   `· Sketching… (2m 1s · ↓ 1.0k tokens)`
 *   `✻ Thinking… (18s · ↑ 420 tokens)`
 *   `⣷ Running command...`
 */
export function parseLiveSpinnerLine(lines: string[]): string | undefined {
  for (let i = lines.length - 1; i >= 0; i--) {
    const trimmed = lines[i]?.trim() ?? ''
    if (!trimmed) continue
    if (trimmed.startsWith('─') || trimmed.startsWith('╌') || trimmed.startsWith('❯') || trimmed.startsWith('>')) {
      continue
    }
    if (
      /^[·✻✽✢✳✶⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏●⏺⣷⣯⣟⡿⢿⣻⣽⣾]\s+\S+/.test(trimmed) &&
      (trimmed.includes('…') ||
        trimmed.includes('...') ||
        trimmed.includes('tokens') ||
        /\(\d+[ms]/.test(trimmed))
    ) {
      return trimmed.replace(/\s+/g, ' ').slice(0, 120)
    }
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
    if (APPROVAL_QUESTION_RE.test(lines[i] ?? '')) {
      questionIdx = i
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
  let topBoxIdx = Math.max(0, questionIdx - 12)
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
      if (clean && !clean.startsWith('Quoted text in this command') && !clean.startsWith('(permissions.')) {
        codePreviewLines.push(clean)
      }
    } else {
      contextHeaders.push(raw)
    }
  }

  const headerPart = contextHeaders.slice(0, 2).join(' · ')
  const cmdPart = codePreviewLines.join(' ').slice(0, 200)
  const fullTitle = [headerPart, cmdPart].filter(Boolean).join('\n') || 'Agent 请求执行审批'

  const hash = crypto
    .createHash('sha1')
    .update(`${nativeId}|${fullTitle}|${options.map(o => o.name).join(',')}`)
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
