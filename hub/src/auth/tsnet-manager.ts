import { execFile, spawn, type ChildProcess } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import readline from 'node:readline'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { defaultDataDir, findExecutable } from '../platform/index.ts'

const execFileAsync = promisify(execFile)
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '../../..')

export interface TsnetPeerCandidate {
  hostName: string
  dnsName: string
  tailscaleIp: string
  online: boolean
  isLeworkNode: boolean
  localProxyUrl?: string
}

export interface TsnetStatusSnapshot {
  available: boolean
  mode: 'tsnet' | 'system-tailscale' | 'unavailable'
  backendState: string
  authUrl?: string
  controlUrl?: string
  selfHostName?: string
  selfDnsName?: string
  tailscaleIps: string[]
  loginName?: string
  displayName?: string
  peers: TsnetPeerCandidate[]
  error?: string
}

export interface VerifiedTailscalePeer {
  sameUser: boolean
  loginName: string
  displayName: string
  nodeName: string
  tailscaleIp: string
}

export interface TsnetManagerOptions {
  dataDir?: string
  hubName?: string
  hubPort?: number
  controlUrl?: string
  authKey?: string
  binaryPath?: string | null
  autoStart?: boolean
  systemTailscaleCli?: string | null
  execCli?: (
    file: string,
    args: string[],
    opts: { timeout: number; encoding: 'utf8' },
  ) => Promise<{ stdout: string; stderr?: string }>
}

export function isTailscaleIp(rawIp: string | undefined): boolean {
  if (!rawIp) return false
  const ip = rawIp.replace(/^::ffff:/i, '').trim()
  if (ip.toLowerCase().startsWith('fd7a:115c:a1e0:')) return true
  const parts = ip.split('.').map(Number)
  if (parts.length !== 4 || parts.some(n => !Number.isInteger(n) || n < 0 || n > 255)) return false
  // Tailscale CGNAT range 100.64.0.0/10 (100.64.0.0 - 100.127.255.255)
  return parts[0] === 100 && parts[1]! >= 64 && parts[1]! <= 127
}

function normalizeRemoteIp(addr: string | undefined): string {
  if (!addr) return ''
  return addr.replace(/^::ffff:/i, '').trim()
}

export function resolveTsnetBinaryPath(custom?: string | null): string | null {
  if (custom !== undefined) return custom
  const envBin = process.env.LEWORK_TSNET_BIN?.trim()
  if (envBin && fs.existsSync(envBin)) return envBin
  const candidates = [
    path.join(repoRoot, 'tsnet-bridge', 'bin', 'lework-tsnet'),
    path.join(defaultDataDir(), 'bin', 'lework-tsnet'),
    findExecutable('lework-tsnet'),
  ]
  for (const c of candidates) {
    if (c && fs.existsSync(c)) return c
  }
  return null
}

function resolveSystemTailscaleCli(): string | null {
  return findExecutable('tailscale', [
    '/Applications/Tailscale.app/Contents/MacOS/Tailscale',
    '/opt/homebrew/bin/tailscale',
    '/usr/local/bin/tailscale',
    '/usr/bin/tailscale',
    '/usr/sbin/tailscale',
    '/snap/bin/tailscale',
  ])
}

function hasAuthenticatedTsnetState(stateDir: string): boolean {
  try {
    const raw = fs.readFileSync(path.join(stateDir, 'tailscaled.state'), 'utf8')
    const parsed = JSON.parse(raw) as Record<string, unknown>
    return typeof parsed['_current-profile'] === 'string' && parsed['_current-profile'].length > 0
  } catch {
    return false
  }
}

export class TsnetManager {
  private readonly dataDir: string
  private readonly stateDir: string
  private readonly hubName: string
  private hubPort: number
  private controlUrl: string | undefined
  private authKey: string | undefined
  private readonly binaryPath: string | null
  private readonly autoStart: boolean
  private readonly customSystemCli: string | null | undefined
  private readonly execCli: (
    file: string,
    args: string[],
    opts: { timeout: number; encoding: 'utf8' },
  ) => Promise<{ stdout: string; stderr?: string }>

  private proc: ChildProcess | null = null
  private controlPort = 0
  private secret = ''
  private starting: Promise<boolean> | null = null
  private stopped = false

  constructor(opts: TsnetManagerOptions = {}) {
    this.dataDir = opts.dataDir ?? defaultDataDir()
    this.stateDir = path.join(this.dataDir, 'tsnet')
    this.hubName = opts.hubName ?? os.hostname()
    this.hubPort = opts.hubPort ?? 64450
    this.controlUrl = opts.controlUrl
    this.authKey = opts.authKey
    this.binaryPath = resolveTsnetBinaryPath(opts.binaryPath)
    // Auto-start embedded tsnet only when explicitly enabled, authKey set, or an authenticated profile exists
    this.autoStart =
      opts.autoStart ?? (hasAuthenticatedTsnetState(this.stateDir) || Boolean(this.authKey))
    this.customSystemCli = opts.systemTailscaleCli
    this.execCli = opts.execCli ?? ((file, args, execOpts) => execFileAsync(file, args, execOpts))
  }

  hasEmbeddedBinary(): boolean {
    return Boolean(this.binaryPath)
  }

  async startIfConfigured(hubPort?: number): Promise<void> {
    if (hubPort && hubPort > 0) this.hubPort = hubPort
    if (!this.autoStart || !this.binaryPath) return
    await this.ensureDaemonRunning()
  }

  async ensureDaemonRunning(): Promise<boolean> {
    if (this.stopped || !this.binaryPath) return false
    if (this.proc && this.controlPort > 0) return true
    if (this.starting) return this.starting

    this.starting = this.spawnDaemon()
    try {
      return await this.starting
    } finally {
      this.starting = null
    }
  }

  private async spawnDaemon(): Promise<boolean> {
    if (!this.binaryPath) return false
    fs.mkdirSync(this.stateDir, { recursive: true, mode: 0o700 })
    const secret = crypto.randomBytes(24).toString('hex')
    const safeHostLabel =
      this.hubName
        .replace(/\.local\.?$/i, '')
        .replace(/[^A-Za-z0-9-]+/g, '-')
        .replace(/-+/g, '-')
        .replace(/^-|-$/g, '')
        .toLowerCase()
        .slice(0, 48) || 'host'
    const args = [
      '--state-dir',
      this.stateDir,
      '--hostname',
      `lework-hub-${safeHostLabel}`,
      '--upstream',
      `127.0.0.1:${this.hubPort}`,
      '--hub-port',
      String(this.hubPort),
    ]
    if (this.controlUrl) args.push('--control-url', this.controlUrl)
    if (this.authKey) args.push('--auth-key', this.authKey)

    return await new Promise<boolean>(resolve => {
      let settled = false
      const child = spawn(this.binaryPath!, args, {
        env: { ...process.env, LEWORK_TSNET_SECRET: secret },
        stdio: ['ignore', 'pipe', 'pipe'],
      })
      const timer = setTimeout(() => {
        if (!settled) {
          settled = true
          try {
            child.kill('SIGTERM')
          } catch {
            // ignore
          }
          resolve(false)
        }
      }, 10_000)
      timer.unref?.()

      const rl = readline.createInterface({ input: child.stdout! })
      rl.on('line', line => {
        if (settled) return
        try {
          const msg = JSON.parse(line.trim()) as { ready?: boolean; controlPort?: number }
          if (msg.ready && typeof msg.controlPort === 'number' && msg.controlPort > 0) {
            settled = true
            clearTimeout(timer)
            this.proc = child
            this.controlPort = msg.controlPort
            this.secret = secret
            resolve(true)
          }
        } catch {
          // ignore non-json log line
        }
      })

      child.stderr?.on('data', () => {
        // drain stderr
      })

      child.on('exit', () => {
        if (this.proc === child) {
          this.proc = null
          this.controlPort = 0
          this.secret = ''
        }
        if (!settled) {
          settled = true
          clearTimeout(timer)
          resolve(false)
        }
      })
    })
  }

  async stop(): Promise<void> {
    this.stopped = true
    if (this.proc) {
      const p = this.proc
      this.proc = null
      this.controlPort = 0
      this.secret = ''
      try {
        p.kill('SIGTERM')
      } catch {
        // ignore
      }
    }
  }

  private async callBridge<T>(pathname: string, method: 'GET' | 'POST' = 'GET', body?: unknown): Promise<T | null> {
    if (this.controlPort <= 0 || !this.secret) return null
    try {
      const res = await fetch(`http://127.0.0.1:${this.controlPort}${pathname}`, {
        method,
        headers: {
          authorization: `Bearer ${this.secret}`,
          ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
        },
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(8_000),
      })
      if (!res.ok) return null
      return (await res.json()) as T
    } catch {
      return null
    }
  }

  async getStatus(): Promise<TsnetStatusSnapshot> {
    let pendingEmbeddedSnap: {
      backendState: string
      authUrl?: string
      controlUrl?: string
      selfHostName?: string
      selfDnsName?: string
      tailscaleIps?: string[]
      loginName?: string
      displayName?: string
      peers?: TsnetPeerCandidate[]
      error?: string
    } | null = null

    if (this.binaryPath) {
      const hasAuthState = hasAuthenticatedTsnetState(this.stateDir)
      if (this.controlPort > 0 || hasAuthState) {
        await this.ensureDaemonRunning()
        const snap = await this.callBridge<{
          backendState: string
          authUrl?: string
          controlUrl?: string
          selfHostName?: string
          selfDnsName?: string
          tailscaleIps?: string[]
          loginName?: string
          displayName?: string
          peers?: TsnetPeerCandidate[]
          error?: string
        }>('/v1/status')
        if (snap && snap.backendState === 'Running') {
          return {
            available: true,
            mode: 'tsnet',
            backendState: snap.backendState,
            authUrl: snap.authUrl,
            controlUrl: snap.controlUrl,
            selfHostName: snap.selfHostName,
            selfDnsName: snap.selfDnsName,
            tailscaleIps: snap.tailscaleIps ?? [],
            loginName: snap.loginName,
            displayName: snap.displayName,
            peers: snap.peers ?? [],
            error: snap.error,
          }
        }
        if (snap && Boolean(snap.authUrl)) {
          pendingEmbeddedSnap = snap
        }
      }
    }

    // Fallback: inspect system `tailscale` daemon if installed & Running (never masked by an abandoned NeedsLogin state)
    const sys = await this.querySystemTailscaleStatus()
    if (sys && sys.backendState === 'Running') return sys

    if (pendingEmbeddedSnap) {
      return {
        available: true,
        mode: 'tsnet',
        backendState: pendingEmbeddedSnap.backendState,
        authUrl: pendingEmbeddedSnap.authUrl,
        controlUrl: pendingEmbeddedSnap.controlUrl,
        selfHostName: pendingEmbeddedSnap.selfHostName,
        selfDnsName: pendingEmbeddedSnap.selfDnsName,
        tailscaleIps: pendingEmbeddedSnap.tailscaleIps ?? [],
        loginName: pendingEmbeddedSnap.loginName,
        displayName: pendingEmbeddedSnap.displayName,
        peers: pendingEmbeddedSnap.peers ?? [],
        error: pendingEmbeddedSnap.error,
      }
    }

    if (this.binaryPath) {
      return {
        available: true,
        mode: 'tsnet',
        backendState: 'NeedsLogin',
        tailscaleIps: [],
        peers: [],
      }
    }
    if (sys) return sys

    return {
      available: false,
      mode: 'unavailable',
      backendState: 'Unavailable',
      tailscaleIps: [],
      peers: [],
    }
  }

  async startLogin(opts?: { controlUrl?: string; authKey?: string }): Promise<TsnetStatusSnapshot> {
    if (!this.binaryPath) {
      return {
        available: false,
        mode: 'unavailable',
        backendState: 'Unavailable',
        tailscaleIps: [],
        peers: [],
        error: 'lework-tsnet binary is not built yet (run ./scripts/build-tsnet.sh)',
      }
    }
    if (opts?.controlUrl !== undefined) this.controlUrl = opts.controlUrl
    if (opts?.authKey !== undefined) this.authKey = opts.authKey
    const started = await this.ensureDaemonRunning()
    if (!started) {
      return {
        available: true,
        mode: 'tsnet',
        backendState: 'Error',
        tailscaleIps: [],
        peers: [],
        error: 'Failed to launch lework-tsnet daemon',
      }
    }
    if (opts?.controlUrl !== undefined || opts?.authKey !== undefined) {
      await this.callBridge('/v1/configure', 'POST', {
        controlUrl: opts.controlUrl ?? '',
        authKey: opts.authKey ?? '',
      })
    }
    const snap = await this.callBridge<{
      backendState: string
      authUrl?: string
      controlUrl?: string
      selfHostName?: string
      selfDnsName?: string
      tailscaleIps?: string[]
      loginName?: string
      displayName?: string
      peers?: TsnetPeerCandidate[]
      error?: string
    }>('/v1/login', 'POST')
    return {
      available: true,
      mode: 'tsnet',
      backendState: snap?.backendState ?? 'NeedsLogin',
      authUrl: snap?.authUrl,
      controlUrl: snap?.controlUrl,
      selfHostName: snap?.selfHostName,
      selfDnsName: snap?.selfDnsName,
      tailscaleIps: snap?.tailscaleIps ?? [],
      loginName: snap?.loginName,
      displayName: snap?.displayName,
      peers: snap?.peers ?? [],
      error: snap?.error,
    }
  }

  async logout(): Promise<TsnetStatusSnapshot> {
    if (this.controlPort > 0) {
      await this.callBridge('/v1/logout', 'POST')
    }
    return this.getStatus()
  }

  /**
   * Cryptographically verifies whether an inbound socket to `hubd` belongs to an untagged Tailscale
   * node owned by the EXACT SAME Tailscale account as this Host:
   * - Path A: proxied by embedded `lework-tsnet` (`127.0.0.1:<localPort>` -> `/v1/whois?localPort=...`)
   * - Path B: system tailscaled's userspace netstack registered the loopback TCP socket with WhoIs.
   * - Path C: direct connection over system `tailscaled` (`100.64.0.0/10` / `fd7a:...` -> `tailscale whois --json <remoteIp>`)
   */
  async verifyInboundSocket(socket: net.Socket): Promise<VerifiedTailscalePeer | null> {
    const remoteIp = normalizeRemoteIp(socket.remoteAddress)
    const remotePort = socket.remotePort ?? 0
    if (!remoteIp || remotePort <= 0) return null

    if (remoteIp === '127.0.0.1' || remoteIp === '::1') {
      if (this.controlPort > 0) {
        const res = await this.callBridge<{
          found?: boolean
          sameUser?: boolean
          isTagged?: boolean
          loginName?: string
          displayName?: string
          nodeName?: string
          tailscaleIp?: string
        }>(`/v1/whois?localPort=${remotePort}`)
        if (res?.found && res.sameUser && !res.isTagged && res.loginName) {
          return {
            sameUser: true,
            loginName: res.loginName,
            displayName: res.displayName || res.loginName,
            nodeName: res.nodeName || 'tailscale-peer',
            tailscaleIp: res.tailscaleIp || '',
          }
        }
      }
      // Support macOS Tailscale.app NetworkExtension & Linux `--tun=userspace-networking`, where
      // `tailscaled`'s `netstack` dials `127.0.0.1:<hubPort>` from `127.0.0.1:<ephemeralPort>` and
      // registers `backendLocalIPPort -> clientRemoteIP` in `proxyMapper` (`tailscale whois --json --proto=tcp 127.0.0.1:<port>`).
      // Ordinary non-Tailscale local sockets are absent from `proxyMapper`, fail `whois`, and return null.
      return await this.verifyViaSystemTailscaleCli(remoteIp, remotePort, true)
    }

    if (isTailscaleIp(remoteIp)) {
      return await this.verifyViaSystemTailscaleCli(remoteIp, remotePort, false)
    }

    return null
  }

  private getSystemCli(): string | null {
    if (this.customSystemCli !== undefined) return this.customSystemCli
    return resolveSystemTailscaleCli()
  }

  private async querySystemTailscaleStatus(): Promise<TsnetStatusSnapshot | null> {
    const cli = this.getSystemCli()
    if (!cli) return null
    try {
      const { stdout } = await this.execCli(cli, ['status', '--json'], { timeout: 6_000, encoding: 'utf8' })
      const parsed = JSON.parse(stdout) as {
        BackendState?: string
        Self?: { UserID?: number; HostName?: string; DNSName?: string; TailscaleIPs?: string[] }
        User?: Record<string, { LoginName?: string; DisplayName?: string }>
      }
      const selfUid = parsed.Self?.UserID
      const prof = selfUid !== undefined ? parsed.User?.[String(selfUid)] : undefined
      return {
        available: true,
        mode: 'system-tailscale',
        backendState: parsed.BackendState ?? 'Unknown',
        selfHostName: parsed.Self?.HostName,
        selfDnsName: parsed.Self?.DNSName?.replace(/\.$/, ''),
        tailscaleIps: parsed.Self?.TailscaleIPs ?? [],
        loginName: prof?.LoginName,
        displayName: prof?.DisplayName,
        peers: [],
      }
    } catch {
      return null
    }
  }

  private async resolveSystemSelfUserId(cli: string): Promise<number | null> {
    // System Tailscale can switch profiles between handshakes. Always read the current identity.
    const { stdout } = await this.execCli(cli, ['status', '--json'], { timeout: 6_000, encoding: 'utf8' })
    const st = JSON.parse(stdout) as { BackendState?: string; Self?: { UserID?: number } }
    const uid = st.Self?.UserID
    return st.BackendState === 'Running' && typeof uid === 'number' && Number.isSafeInteger(uid) && uid > 0 ? uid : null
  }

  private async verifyViaSystemTailscaleCli(
    remoteIp: string,
    remotePort: number,
    requirePortMatch: boolean,
  ): Promise<VerifiedTailscalePeer | null> {
    const cli = this.getSystemCli()
    if (!cli) return null
    try {
      const whoisTarget = requirePortMatch
        ? remoteIp.includes(':')
          ? `[${remoteIp}]:${remotePort}`
          : `${remoteIp}:${remotePort}`
        : remoteIp
      const whoisArgs = requirePortMatch ? ['whois', '--json', '--proto=tcp', whoisTarget] : ['whois', '--json', whoisTarget]
      const [selfUid, { stdout: whoisOut }] = await Promise.all([
        this.resolveSystemSelfUserId(cli),
        this.execCli(cli, whoisArgs, {
          timeout: 6_000,
          encoding: 'utf8',
        }),
      ])
      const who = JSON.parse(whoisOut) as {
        Node?: { ComputedName?: string; Name?: string; Tags?: string[]; Addresses?: string[] }
        UserProfile?: { ID?: number; LoginName?: string; DisplayName?: string }
      }
      const peerUid = who.UserProfile?.ID
      const isTagged = Array.isArray(who.Node?.Tags) && who.Node!.Tags!.length > 0
      if (!selfUid || !peerUid || selfUid !== peerUid || isTagged || !who.UserProfile?.LoginName) {
        return null
      }
      const resolvedPeerIp = isTailscaleIp(remoteIp)
        ? remoteIp
        : (who.Node?.Addresses ?? [])
            .map(a => a.split('/')[0]?.trim() ?? '')
            .find(a => isTailscaleIp(a)) || remoteIp
      if (!isTailscaleIp(resolvedPeerIp)) {
        return null
      }
      return {
        sameUser: true,
        loginName: who.UserProfile.LoginName,
        displayName: who.UserProfile.DisplayName || who.UserProfile.LoginName,
        nodeName: who.Node?.ComputedName || who.Node?.Name?.replace(/\.$/, '') || resolvedPeerIp,
        tailscaleIp: resolvedPeerIp,
      }
    } catch {
      return null
    }
  }
}
