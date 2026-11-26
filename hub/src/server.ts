import crypto from 'node:crypto'
import fs from 'node:fs'
import http from 'node:http'
import https from 'node:https'
import os from 'node:os'
import path from 'node:path'
import { WebSocketServer, WebSocket } from 'ws'
import { z } from 'zod'
import {
  ErrorCode,
  Methods,
  PROTOCOL_VERSION,
  RpcRequest,
  type AgentEvent,
  type HubInfo,
  type MethodName,
  type ProfileInfo,
  type PromptImageAttachment,
  type ProviderConfigInput,
  type ReasoningEffort,
  type SessionMeta,
  type SessionMode,
  type SlaveInfo,
  type Workspace,
} from '@lework/protocol'
import { loadOrCreateConfig, type HubConfig } from './config.ts'
import { BonjourAdvertiser, collectLanIpv4Addresses, collectTailscaleIpv4Addresses } from './bonjour.ts'
import { DeviceFlowAuth } from './auth/device-flow-auth.ts'
import { PairingTicketManager } from './auth/pair-manager.ts'
import { TsnetManager } from './auth/tsnet-manager.ts'
import { NotificationDispatcher } from './notifications/notifier.ts'
import { AgyEngineAdapter } from './engines/agy-adapter.ts'
import { ClaudeCodeAdapter } from './engines/claude-adapter.ts'
import { CodexEngineAdapter } from './engines/codex-adapter.ts'
import { DshEngineAdapter } from './engines/dsh-adapter.ts'
import { DiagnosticEchoAdapter } from './engines/echo-adapter.ts'
import { PiEngineAdapter } from './engines/pi-adapter.ts'
import type { EngineAdapter } from './engines/types.ts'
import { detectPlatform } from './platform/index.ts'
import { ModelRegistry } from './registry/model-registry.ts'
import { HubRpcError, resolveAllowedWorkspacePath } from './security/path-guard.ts'
import {
  attestProof,
  loadOrCreateHubTls,
  shortFingerprint,
  tokenAttestKey,
  tokenKid,
  type HubTlsMaterial,
} from './security/tls.ts'
import { SessionManager } from './session-manager.ts'
import type { HostTaskInspector } from './storage/host-history.ts'
import {
  getManagedWorktreesRoot,
  inspectWorkspaceGitStatus,
  listWorkspaceDirectory,
  readWorkspaceFile,
  readWorkspaceGitDiff,
  searchWorkspaceFiles,
} from './storage/workspace-fs.ts'
import { UsageService } from './usage/index.ts'
import { HUB_VERSION } from './version.ts'

interface ClientConn {
  id: number
  ws: WebSocket
  remoteAddr: string
  connectedAtMs: number
  clientLabel: string | null
  authenticated: boolean
  isAlive: boolean
  subscribedSessions: Set<string>
  helloTimer: NodeJS.Timeout | null
}

export interface HubServerOptions {
  config: HubConfig
  adapters?: EngineAdapter[]
  hostInspector?: HostTaskInspector
  usageService?: UsageService
  tsnetManager?: TsnetManager
  notificationDispatcher?: NotificationDispatcher
  /** TLS identity; defaults to the persistent self-signed identity under `~/.lework/tls`. */
  tls?: HubTlsMaterial
}

const HELLO_DEADLINE_MS = 10_000
const MAX_PREAUTH_CONNECTIONS = 32
const MAX_REMOTE_SLAVES = 32
const WS_MAX_PAYLOAD = 4 * 1024 * 1024
const AUTH_FAILURE_DELAY_MS = 400

const SlaveRegistration = z.object({
  token: z.string().min(8).max(256),
  slave: z.object({
    id: z.string().regex(/^[A-Za-z0-9_.:-]{1,64}$/),
    name: z.string().max(80),
    platform: z.enum(['darwin', 'linux', 'win32', 'other']),
    engines: z.array(z.string().max(32)).max(32),
  }),
})

export class HubServer {
  private readonly httpServer: http.Server
  private readonly wss: WebSocketServer
  private readonly clients = new Set<ClientConn>()
  private nextClientConnId = 1
  private readonly remoteSlaves = new Map<string, SlaveInfo>()
  private readonly sessionManager: SessionManager
  private readonly registry: ModelRegistry
  private readonly usageService: UsageService
  private readonly deviceAuth: DeviceFlowAuth
  private readonly pairManager = new PairingTicketManager()
  private readonly tsnetManager: TsnetManager
  private readonly notifier: NotificationDispatcher
  private readonly config: HubConfig
  private bonjour: BonjourAdvertiser | null = null
  private wsHeartbeatTimer: NodeJS.Timeout | null = null
  private boundPort = 0
  private readonly tls: HubTlsMaterial

  constructor(opts: HubServerOptions) {
    this.config = opts.config
    this.tls = opts.tls ?? loadOrCreateHubTls({ commonName: `lework-${opts.config.hubId}` })
    this.deviceAuth = new DeviceFlowAuth({ hubId: this.config.hubId })
    this.tsnetManager =
      opts.tsnetManager ??
      new TsnetManager({
        hubName: this.config.name,
        hubPort: this.config.port,
        controlUrl: this.config.tsnetControlUrl,
        autoStart: this.config.tsnetEnabled,
      })
    const defaultCwd = this.discoverWorkspaces()[0]?.path
    this.registry = new ModelRegistry(this.config.hubId, this.config.cpaEndpoint, defaultCwd)

    const adapters = opts.adapters ?? [
      new ClaudeCodeAdapter(),
      new PiEngineAdapter(this.config.cpaEndpoint, this.registry),
      new CodexEngineAdapter(),
      new AgyEngineAdapter(),
      new DshEngineAdapter(),
      new DiagnosticEchoAdapter(),
    ]

    this.sessionManager = new SessionManager(
      this.effectiveWorkspaceRoots(),
      adapters,
      {
        onEvent: ev => this.broadcastSessionEvent(ev),
        onSessionUpdated: meta => this.broadcastSessionUpdated(meta),
      },
      this.registry,
      opts.hostInspector,
    )

    const sessionSnapshotProvider = (sessionId: string) => {
      try {
        const snap = this.sessionManager.subscribeSnapshot(sessionId, -1)
        return { session: snap.session, events: snap.events }
      } catch {
        return null
      }
    }

    if (opts.usageService) {
      opts.usageService.setSessionSnapshotProvider(sessionSnapshotProvider)
      this.usageService = opts.usageService
    } else {
      this.usageService = new UsageService({
        cpaEndpoint: this.config.cpaEndpoint,
        cpaManagementKey: this.config.cpaManagementKey,
        getCpaEndpoint: () => this.registry.getCpaEndpoint(),
        getCpaApiKey: () => this.registry.getCpaApiKey(),
        getSessionSnapshot: sessionSnapshotProvider,
      })
    }

    this.notifier =
      opts.notificationDispatcher ??
      new NotificationDispatcher({
        getConfig: () => {
          if (this.config.notifications) return this.config.notifications
          try {
            return loadOrCreateConfig().notifications
          } catch {
            return undefined
          }
        },
        getHubInfo: () => this.getHubInfo(),
        getSessionMeta: sid => this.sessionManager.getSessionMeta(sid),
        getActiveSubscriberCount: sid => this.countSubscribers(sid),
      })

    // TLS terminates here (end-to-end): relays / SSH tunnels only forward ciphertext.
    this.httpServer = https.createServer(
      { key: this.tls.key, cert: this.tls.cert, minVersion: 'TLSv1.2' },
      (req, res) => {
        void this.handleHttp(req, res)
      },
    )

    this.wss = new WebSocketServer({ noServer: true, maxPayload: WS_MAX_PAYLOAD })
    this.httpServer.on('upgrade', (req, socket, head) => {
      const url = new URL(req.url ?? '/', 'https://localhost')
      // Browsers always send Origin on WebSocket upgrades; native clients don't. Rejecting it
      // blocks cross-site WebSocket hijacking / DNS-rebinding from malicious web pages.
      if (req.headers.origin) {
        socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n')
        return
      }
      if (url.pathname === '/v1/ws') {
        const preauth = [...this.clients].filter(c => !c.authenticated).length
        if (preauth >= MAX_PREAUTH_CONNECTIONS) {
          socket.end('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n')
          return
        }
        this.wss.handleUpgrade(req, socket, head, ws => {
          this.handleConnection(ws, req.socket.remoteAddress ?? 'unknown')
        })
        return
      }
      if (url.pathname === '/v1/slave-ws') {
        this.wss.handleUpgrade(req, socket, head, ws => {
          this.handleSlaveConnection(ws)
        })
        return
      }
      socket.destroy()
    })
  }

  /** SHA-256 (hex) of this Hub's TLS leaf certificate — what clients pin. */
  get tlsFingerprint(): string {
    return this.tls.fingerprint
  }

  private effectiveWorkspaceRoots(): string[] {
    return [...new Set([...this.config.workspaceRoots, getManagedWorktreesRoot()])]
  }

  getHubInfo(): HubInfo {
    return {
      id: this.config.hubId,
      name: this.config.name,
      role: this.config.role,
      platform: detectPlatform(),
      version: HUB_VERSION,
    }
  }

  async start(): Promise<{ port: number; host: string }> {
    await new Promise<void>((resolve, reject) => {
      this.httpServer.once('error', reject)
      this.httpServer.listen(this.config.port, this.config.host, () => {
        this.httpServer.removeListener('error', reject)
        resolve()
      })
    })
    const addr = this.httpServer.address()
    const port = typeof addr === 'object' && addr ? addr.port : this.config.port
    this.boundPort = port
    this.bonjour = new BonjourAdvertiser({
      name: this.config.name,
      hubId: this.config.hubId,
      host: this.config.host,
      port,
      shortFingerprint: shortFingerprint(this.tls.fingerprint),
    })
    this.bonjour.start()
    await this.tsnetManager.startIfConfigured(port)
    if (!this.wsHeartbeatTimer) {
      this.wsHeartbeatTimer = setInterval(() => {
        for (const c of this.clients) {
          if (c.ws.readyState !== WebSocket.OPEN) continue
          if (!c.isAlive) {
            this.logWs(
              `#${c.id} heartbeat timeout client=${c.clientLabel ?? 'unauth'} remote=${c.remoteAddr}; terminating`,
              true,
            )
            c.ws.terminate()
            continue
          }
          c.isAlive = false
          try {
            c.ws.ping()
          } catch {
            // ignore broken socket
          }
        }
      }, 20_000)
      this.wsHeartbeatTimer.unref()
    }
    return { port, host: this.config.host }
  }

  async stop(): Promise<void> {
    if (this.wsHeartbeatTimer) {
      clearInterval(this.wsHeartbeatTimer)
      this.wsHeartbeatTimer = null
    }
    this.notifier.dispose()
    this.bonjour?.stop()
    this.bonjour = null
    await this.tsnetManager.stop()
    for (const c of this.clients) {
      try {
        c.ws.close()
      } catch {
        // ignore
      }
    }
    this.clients.clear()
    await this.sessionManager.closeAll()
    await new Promise<void>(resolve => {
      this.wss.close(() => {
        this.httpServer.close(() => resolve())
      })
    })
  }

  async buildStatus() {
    await this.registry.refreshProviders()
    const engines = await this.sessionManager.listEngines()
    const installedSet = new Set(engines.filter(e => e.status.installed).map(e => e.id))
    const providers = this.registry.listProviders(engines)
    const hub = this.getHubInfo()
    const selfSlave: SlaveInfo = {
      id: hub.id,
      name: `${hub.name} (${hub.role})`,
      platform: hub.platform,
      online: true,
      engines: [...installedSet],
      lastSeenAt: Date.now(),
    }
    const slaves = [selfSlave, ...this.remoteSlaves.values()]
    return {
      hub,
      slaves,
      engines,
      providers,
      profiles: this.registry.listProfiles(),
    }
  }

  discoverWorkspaces(): Workspace[] {
    const out: Workspace[] = []
    const seen = new Set<string>()
    const addDir = (p: string) => {
      try {
        const real = resolveAllowedWorkspacePath(p, this.config.workspaceRoots)
        if (seen.has(real)) return
        const st = fs.statSync(real)
        if (!st.isDirectory()) return
        seen.add(real)
        const id = `ws_${crypto.createHash('sha1').update(real).digest('hex').slice(0, 10)}`
        out.push({ id, name: path.basename(real) || real, path: real })
      } catch {
        // ignore out-of-root or inaccessible directories
      }
    }

    addDir(process.cwd())

    const home = os.homedir()
    let realHome = home
    try {
      realHome = fs.realpathSync(home)
    } catch {
      // ignore
    }
    const isHomeRoot = (r: string): boolean => {
      if (r === home || r === realHome) return true
      try {
        return fs.realpathSync(r) === realHome
      } catch {
        return false
      }
    }
    const projectParentDirs = this.config.workspaceRoots.filter(r => !isHomeRoot(r))

    for (const parent of projectParentDirs) {
      try {
        const entries = fs.readdirSync(parent, { withFileTypes: true })
        for (const e of entries.slice(0, 40)) {
          if (e.name.startsWith('.')) continue
          if (e.isDirectory() || e.isSymbolicLink()) {
            addDir(path.join(parent, e.name))
          }
        }
      } catch {
        // ignore
      }
    }

    for (const root of this.config.workspaceRoots) {
      addDir(root)
    }
    return out.slice(0, 35)
  }

  private isValidToken(token: string): boolean {
    if (this.deviceAuth.verifySessionToken(token)) return true
    if (token.startsWith('lework_dt.') && this.deviceAuth.issued.isActive(token)) return true
    return timingSafeEqualStr(token, this.config.token)
  }

  private isAdminToken(token: string): boolean {
    return Boolean(token) && timingSafeEqualStr(token, this.config.token)
  }

  private issueDeviceToken(sub: string, name: string): string {
    const token = `lework_dt.${crypto.randomBytes(32).toString('base64url')}`
    const expiresAt = Date.now() + 30 * 24 * 3600 * 1000
    this.deviceAuth.issued.record(token, sub, name, expiresAt)
    return token
  }

  async collectConnectableUrls(): Promise<string[]> {
    const port = this.boundPort || this.config.port
    const urls: string[] = []
    const pushUrl = (u: string) => {
      const normalized = u.replace(/^http:\/\//i, 'https://').replace(/\/+$/, '')
      if (normalized && !urls.includes(normalized)) urls.push(normalized)
    }
    if (this.config.host === '0.0.0.0' || this.config.host === '::') {
      for (const ip of collectLanIpv4Addresses()) {
        pushUrl(`https://${ip}:${port}`)
      }
      const cleanHost = (this.config.name || os.hostname())
        .replace(/\.local\.?$/i, '')
        .replace(/[^A-Za-z0-9_-]/g, '-')
        .replace(/-+/g, '-')
        .replace(/^-|-$/g, '')
      if (cleanHost) {
        pushUrl(`https://${cleanHost}.local:${port}`)
      }
    } else if (this.config.host) {
      pushUrl(`https://${this.config.host}:${port}`)
    }
    for (const tsIp of collectTailscaleIpv4Addresses()) {
      pushUrl(`https://${tsIp}:${port}`)
    }
    try {
      const ts = await this.tsnetManager.getStatus()
      for (const tsIp of ts.tailscaleIps) {
        if (!tsIp.includes(':')) {
          pushUrl(`https://${tsIp}:${port}`)
        }
      }
    } catch {
      // ignore
    }
    for (const r of this.config.relayUrls ?? []) {
      pushUrl(r)
    }
    return urls
  }

  /** Attestation key (SHA-256 of the bearer token) for a public key-id, if the token is valid. */
  private attestKeyForKid(kid: string): Buffer | null {
    if (kid === tokenKid(this.config.token)) return tokenAttestKey(this.config.token)
    return this.deviceAuth.issued.attestKeyForKid(kid)
  }

  private async handleHttp(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const url = new URL(req.url ?? '/', 'https://localhost')
    const json = (status: number, body: unknown) => {
      res.writeHead(status, {
        'content-type': 'application/json; charset=utf-8',
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff',
      })
      res.end(JSON.stringify(body))
    }
    // No CORS: requests from web pages (which always carry Origin on POST) are refused.
    if (req.method !== 'GET' && req.headers.origin) {
      json(403, { error: 'Forbidden' })
      return
    }
    if (req.method === 'GET' && (url.pathname === '/healthz' || url.pathname === '/v1/auth/discover')) {
      // Unauthenticated: reveal only what the login screen needs. Relay URLs are returned
      // after authentication (hub.hello) so an impostor cannot be "learned" from here.
      const oauth = this.deviceAuth.describe()
      const tsPeer =
        url.pathname === '/v1/auth/discover' ? await this.tsnetManager.verifyInboundSocket(req.socket) : null
      json(200, {
        ok: true,
        hub: this.getHubInfo(),
        oauthEnabled: oauth.enabled,
        oauthProvider: oauth.provider,
        oauthDisplayName: oauth.displayName,
        tailscaleSameUser: Boolean(tsPeer?.sameUser),
        tailscaleUser: tsPeer?.displayName,
        tsnetAvailable: this.tsnetManager.hasEmbeddedBinary(),
        wsPath: '/v1/ws',
        tls: true,
      })
      return
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/tailscale') {
      // Zero-touch Tailscale same-account handshake: verifies via `tsnet` / `tailscaled` LocalClient
      // WhoIs that the TCP peer is an untagged node belonging to the exact same Tailscale user.
      try {
        const parsed = JSON.parse((await readRequestBody(req)) || '{}') as {
          nonce?: unknown
          deviceName?: unknown
        }
        const nonce = typeof parsed.nonce === 'string' ? parsed.nonce : ''
        if (!/^[0-9a-f]{32,128}$/.test(nonce)) {
          json(400, { error: 'Bad request' })
          return
        }
        const tsPeer = await this.tsnetManager.verifyInboundSocket(req.socket)
        if (!tsPeer?.sameUser) {
          await delay(AUTH_FAILURE_DELAY_MS)
          json(403, { error: 'Tailscale same-account peer verification failed' })
          return
        }
        const peerLabel =
          typeof parsed.deviceName === 'string' && parsed.deviceName.trim()
            ? parsed.deviceName.trim().slice(0, 64)
            : tsPeer.nodeName
        const accessToken = this.issueDeviceToken(
          `tailscale:${tsPeer.loginName}`,
          `${tsPeer.displayName} (${peerLabel})`,
        )
        const endpoints = await this.collectConnectableUrls()
        console.log(
          `[lework-auth] ACCEPTED Tailscale same-account peer: user=${tsPeer.loginName} node=${peerLabel}`,
        )
        json(200, {
          ok: true,
          accessToken,
          user: {
            subject: `tailscale:${tsPeer.loginName}`,
            name: tsPeer.displayName,
            email: tsPeer.loginName,
          },
          fingerprint: this.tls.fingerprint,
          proof: attestProof(tokenAttestKey(accessToken), this.tls.fingerprint, nonce),
          hub: this.getHubInfo(),
          endpoints,
        })
      } catch {
        json(400, { error: 'Bad request' })
      }
      return
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/pair/create') {
      const auth = (req.headers.authorization ?? '').replace(/^Bearer\s+/i, '')
      if (!this.isAdminToken(auth)) {
        await delay(AUTH_FAILURE_DELAY_MS)
        json(401, { error: 'Unauthorized' })
        return
      }
      const { pairCode, expiresInSec } = this.pairManager.issueTicket()
      const endpoints = await this.collectConnectableUrls()
      json(200, {
        pairCode,
        expiresInSec,
        endpoints,
        fingerprint: this.tls.fingerprint,
        hub: this.getHubInfo(),
      })
      return
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/pair') {
      try {
        const parsed = JSON.parse((await readRequestBody(req)) || '{}') as {
          pairCode?: unknown
          token?: unknown
          nonce?: unknown
          deviceName?: unknown
        }
        const nonce = typeof parsed.nonce === 'string' ? parsed.nonce : ''
        if (!/^[0-9a-f]{32,128}$/.test(nonce)) {
          json(400, { error: 'Bad request' })
          return
        }
        const acceptedTicket =
          typeof parsed.pairCode === 'string' && this.pairManager.consumeTicket(parsed.pairCode)
        const acceptedStatic =
          !acceptedTicket && typeof parsed.token === 'string' && this.isAdminToken(parsed.token)
        if (!acceptedTicket && !acceptedStatic) {
          await delay(AUTH_FAILURE_DELAY_MS)
          json(401, { error: 'Invalid or expired pairing code' })
          return
        }
        const devLabel =
          typeof parsed.deviceName === 'string' && parsed.deviceName.trim()
            ? parsed.deviceName.trim().slice(0, 64)
            : 'Mobile App'
        const accessToken = this.issueDeviceToken('pair:qr', devLabel)
        const endpoints = await this.collectConnectableUrls()
        console.log(`[lework-auth] ACCEPTED QR pairing: device=${devLabel}`)
        json(200, {
          ok: true,
          accessToken,
          user: { subject: 'pair:qr', name: devLabel, email: '' },
          fingerprint: this.tls.fingerprint,
          proof: attestProof(tokenAttestKey(accessToken), this.tls.fingerprint, nonce),
          hub: this.getHubInfo(),
          endpoints,
        })
      } catch {
        json(400, { error: 'Bad request' })
      }
      return
    }
    if (url.pathname.startsWith('/v1/tsnet/') || url.pathname === '/v1/notify/test') {
      const auth = (req.headers.authorization ?? '').replace(/^Bearer\s+/i, '')
      if (!this.isAdminToken(auth)) {
        await delay(AUTH_FAILURE_DELAY_MS)
        json(401, { error: 'Unauthorized' })
        return
      }
      if (req.method === 'GET' && url.pathname === '/v1/tsnet/status') {
        json(200, await this.tsnetManager.getStatus())
        return
      }
      if (req.method === 'POST' && url.pathname === '/v1/tsnet/login') {
        const parsed = JSON.parse((await readRequestBody(req)) || '{}') as {
          controlUrl?: unknown
          authKey?: unknown
        }
        const status = await this.tsnetManager.startLogin({
          controlUrl: typeof parsed.controlUrl === 'string' ? parsed.controlUrl : undefined,
          authKey: typeof parsed.authKey === 'string' ? parsed.authKey : undefined,
        })
        json(200, status)
        return
      }
      if (req.method === 'POST' && url.pathname === '/v1/tsnet/logout') {
        json(200, await this.tsnetManager.logout())
        return
      }
      if (req.method === 'POST' && url.pathname === '/v1/notify/test') {
        json(200, await this.notifier.sendTestNotification())
        return
      }
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/attest') {
      // Token-bound TLS attestation: proves to a client holding the token that this TLS endpoint
      // (certificate fingerprint) belongs to the Hub that issued / knows the token.
      try {
        const parsed = JSON.parse((await readRequestBody(req)) || '{}') as { nonce?: unknown; kid?: unknown }
        const nonce = typeof parsed.nonce === 'string' ? parsed.nonce : ''
        const kid = typeof parsed.kid === 'string' ? parsed.kid : ''
        if (!/^[0-9a-f]{32,128}$/.test(nonce) || !/^[0-9a-f]{16}$/.test(kid)) {
          json(400, { error: 'Bad request' })
          return
        }
        const key = this.attestKeyForKid(kid)
        if (!key) {
          await delay(AUTH_FAILURE_DELAY_MS)
          json(404, { error: 'Unknown key' })
          return
        }
        json(200, {
          fingerprint: this.tls.fingerprint,
          proof: attestProof(key, this.tls.fingerprint, nonce),
        })
      } catch {
        json(400, { error: 'Bad request' })
      }
      return
    }
    if (req.method === 'POST' && (url.pathname === '/v1/auth/oauth/start' || url.pathname === '/v1/auth/feishu/start')) {
      try {
        const parsed = JSON.parse((await readRequestBody(req)) || '{}') as { codeChallenge?: unknown }
        const challenge = await this.deviceAuth.startDeviceFlow(
          typeof parsed.codeChallenge === 'string' ? parsed.codeChallenge : '',
        )
        json(200, challenge)
      } catch (err) {
        const msg = err instanceof Error ? err.message : ''
        const safe = /Too many|Invalid code challenge|not configured/.test(msg) ? msg : 'Login unavailable'
        json(/Too many/.test(msg) ? 429 : 400, { error: safe })
      }
      return
    }
    if (req.method === 'POST' && (url.pathname === '/v1/auth/oauth/poll' || url.pathname === '/v1/auth/feishu/poll')) {
      try {
        const parsed = JSON.parse((await readRequestBody(req)) || '{}') as { sid?: unknown; codeVerifier?: unknown }
        if (typeof parsed.sid !== 'string' || typeof parsed.codeVerifier !== 'string') {
          json(400, { status: 'expired', detail: 'Missing sid' })
          return
        }
        const pollRes = await this.deviceAuth.pollDeviceFlow(parsed.sid, parsed.codeVerifier)
        json(200, pollRes)
      } catch {
        json(500, { status: 'expired', detail: 'Login failed' })
      }
      return
    }
    if (req.method === 'GET' && url.pathname === '/v1/status') {
      const auth = (req.headers.authorization ?? '').replace(/^Bearer\s+/i, '')
      if (!auth || !this.isValidToken(auth)) {
        res.writeHead(401, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ error: 'Unauthorized' }))
        return
      }
      const status = await this.buildStatus()
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' })
      res.end(JSON.stringify(status))
      return
    }
    res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ error: 'Not Found' }))
  }

  private handleSlaveConnection(ws: WebSocket): void {
    let registeredId: string | null = null
    const deadline = setTimeout(() => {
      if (!registeredId) ws.close(1008, 'Registration timeout')
    }, HELLO_DEADLINE_MS)
    ws.on('message', raw => {
      if (registeredId) {
        // One registration per socket; subsequent frames are only heartbeats.
        const prev = this.remoteSlaves.get(registeredId)
        if (prev) this.remoteSlaves.set(registeredId, { ...prev, online: true, lastSeenAt: Date.now() })
        return
      }
      let parsed: z.infer<typeof SlaveRegistration>
      try {
        const res = SlaveRegistration.safeParse(JSON.parse(String(raw)))
        if (!res.success) {
          ws.close(1008, 'Invalid registration')
          return
        }
        parsed = res.data
      } catch {
        ws.close(1008, 'Invalid registration')
        return
      }
      // Slaves use a dedicated join credential — never the admin/app token.
      const joinToken = this.config.slaveJoinToken
      if (!joinToken || !timingSafeEqualStr(parsed.token, joinToken)) {
        setTimeout(() => ws.close(1008, 'Unauthorized slave token'), AUTH_FAILURE_DELAY_MS)
        return
      }
      const id = parsed.slave.id
      if (id === this.config.hubId) {
        ws.close(1008, 'Slave id collides with master')
        return
      }
      if (!this.remoteSlaves.has(id) && this.remoteSlaves.size >= MAX_REMOTE_SLAVES) {
        ws.close(1008, 'Too many slaves')
        return
      }
      clearTimeout(deadline)
      registeredId = id
      this.remoteSlaves.set(id, {
        ...parsed.slave,
        online: true,
        lastSeenAt: Date.now(),
      })
      ws.send(JSON.stringify({ ok: true, masterId: this.config.hubId }))
    })
    ws.on('close', () => {
      clearTimeout(deadline)
      if (registeredId) {
        const prev = this.remoteSlaves.get(registeredId)
        if (prev) {
          this.remoteSlaves.set(registeredId, { ...prev, online: false, lastSeenAt: Date.now() })
        }
      }
    })
  }

  private logWs(message: string, warn = false): void {
    const line = `${new Date().toISOString()} [lework-ws] ${message}`
    if (warn) {
      console.warn(line)
    } else {
      console.log(line)
    }
  }

  private handleConnection(ws: WebSocket, remoteAddr = 'unknown'): void {
    const conn: ClientConn = {
      id: this.nextClientConnId++,
      ws,
      remoteAddr,
      connectedAtMs: Date.now(),
      clientLabel: null,
      authenticated: false,
      isAlive: true,
      subscribedSessions: new Set(),
      helloTimer: null,
    }
    conn.helloTimer = setTimeout(() => {
      if (!conn.authenticated) {
        this.logWs(`#${conn.id} hello timeout remote=${conn.remoteAddr}; closing 1008`, true)
        ws.close(1008, 'Authentication timeout')
      }
    }, HELLO_DEADLINE_MS)
    this.clients.add(conn)
    this.logWs(`#${conn.id} open remote=${conn.remoteAddr} active=${this.clients.size}`)

    ws.on('pong', () => {
      conn.isAlive = true
    })
    ws.on('message', raw => {
      conn.isAlive = true
      void this.handleWsMessage(conn, String(raw))
    })

    ws.on('close', (code, reasonBuf) => {
      if (conn.helloTimer) clearTimeout(conn.helloTimer)
      for (const sid of conn.subscribedSessions) {
        this.sessionManager.releaseHostWatch(sid)
      }
      conn.subscribedSessions.clear()
      this.clients.delete(conn)
      const reason = Buffer.isBuffer(reasonBuf) ? reasonBuf.toString('utf8') : String(reasonBuf ?? '')
      const durMs = Math.max(0, Date.now() - conn.connectedAtMs)
      this.logWs(
        `#${conn.id} close code=${code} reason=${reason || 'none'} auth=${conn.authenticated} client=${conn.clientLabel ?? 'unauth'} durMs=${durMs} active=${this.clients.size}`,
      )
      if (this.clients.size === 0) {
        this.sessionManager.stopGlobalHostMonitor()
      }
    })
  }

  private async handleWsMessage(conn: ClientConn, raw: string): Promise<void> {
    let json: unknown
    try {
      json = JSON.parse(raw)
    } catch {
      this.sendRpcError(conn.ws, null, ErrorCode.ParseError, 'Invalid JSON')
      return
    }

    const parsedReq = RpcRequest.safeParse(json)
    if (!parsedReq.success) {
      const id =
        json && typeof json === 'object' && ('id' in json)
          ? ((json as { id?: string | number }).id ?? null)
          : null
      this.sendRpcError(conn.ws, id, ErrorCode.InvalidRequest, 'Invalid JSON-RPC 2.0 request')
      return
    }

    const { id, method, params } = parsedReq.data
    if (!Object.hasOwn(Methods, method)) {
      this.sendRpcError(conn.ws, id, ErrorCode.MethodNotFound, `Method not allowed: ${method}`)
      return
    }

    const methodName = method as MethodName
    if (!conn.authenticated && methodName !== 'hub.hello') {
      this.sendRpcError(conn.ws, id, ErrorCode.Unauthorized, 'Authenticate with hub.hello first.')
      return
    }

    const methodSpec = Methods[methodName]
    const parsedParams = methodSpec.params.safeParse(params ?? {})
    if (!parsedParams.success) {
      this.sendRpcError(conn.ws, id, ErrorCode.InvalidParams, parsedParams.error.message)
      return
    }

    try {
      const result = await this.dispatchMethod(conn, methodName, parsedParams.data)
      this.sendJson(conn.ws, { jsonrpc: '2.0', id, result })
    } catch (err) {
      if (err instanceof HubRpcError) {
        this.logWs(
          `#${conn.id} rpc error method=${methodName} code=${err.code} msg=${err.message.slice(0, 160)}`,
          true,
        )
        this.sendRpcError(conn.ws, id, err.code, err.message)
        if (methodName === 'hub.hello' && !conn.authenticated) {
          // Failed authentication: slow down guessing and drop the socket.
          setTimeout(() => conn.ws.close(1008, 'Unauthorized'), AUTH_FAILURE_DELAY_MS)
        }
      } else {
        const msg = err instanceof Error ? err.message : 'Internal error'
        this.logWs(`#${conn.id} rpc internal error method=${methodName} msg=${msg.slice(0, 160)}`, true)
        this.sendRpcError(conn.ws, id, ErrorCode.Internal, msg)
      }
    }
  }

  private async dispatchMethod(conn: ClientConn, method: MethodName, params: unknown): Promise<unknown> {
    switch (method) {
      case 'hub.hello': {
        const p = params as {
          protocolVersion: number
          token: string
          client?: { name?: string; version?: string; platform?: string }
        }
        if (p.protocolVersion !== PROTOCOL_VERSION) {
          throw new HubRpcError(
            ErrorCode.VersionMismatch,
            `Unsupported protocolVersion ${p.protocolVersion}; expected ${PROTOCOL_VERSION}`,
          )
        }
        if (!this.isValidToken(p.token)) {
          throw new HubRpcError(ErrorCode.Unauthorized, 'Invalid or expired access token')
        }
        conn.authenticated = true
        const cName = p.client?.name?.trim() || 'client'
        const cPlatform = p.client?.platform?.trim() || 'unknown'
        const cVer = p.client?.version?.trim() || '?'
        conn.clientLabel = `${cName}/${cPlatform}@${cVer}`.slice(0, 80)
        if (conn.helloTimer) {
          clearTimeout(conn.helloTimer)
          conn.helloTimer = null
        }
        this.logWs(`#${conn.id} hello ok client=${conn.clientLabel} protocol=${p.protocolVersion}`)
        this.sessionManager.startGlobalHostMonitor()
        return {
          protocolVersion: PROTOCOL_VERSION,
          hub: this.getHubInfo(),
          relayUrls: (this.config.relayUrls ?? []).map(u => u.replace(/^http:\/\//, 'https://')),
          endpoints: await this.collectConnectableUrls(),
          capabilities: [
            'engine.claude.v1',
            'engine.pi.v1',
            'engine.codex.v1',
            'engine.agy.v1',
            'engine.dsh.v1',
            'engine.mock.v1',
            'stream.seq-resume.v1',
            'approval.interactive.v1',
            'workspace.fs-readonly.v1',
            'workspace.checkpoint.v1',
            'workspace.worktree.v1',
            'usage.stats.v1',
            'session.organize.v1',
            'feature.artifacts.v1',
            'feature.search.v1',
          ],
        }
      }
      case 'hub.status':
        return await this.buildStatus()
      case 'slave.list': {
        const status = await this.buildStatus()
        return { slaves: status.slaves }
      }
      case 'profile.list': {
        return { profiles: this.registry.listProfiles() }
      }
      case 'profile.upsert': {
        const p = params as { profile: ProfileInfo }
        const profiles = this.registry.upsertProfile(p.profile)
        return { profiles }
      }
      case 'provider.list': {
        await this.registry.refreshProviders()
        const engines = await this.sessionManager.listEngines()
        const providers = this.registry.listProviders(engines)
        return { providers, engines }
      }
      case 'provider.upsert': {
        const p = params as { provider: ProviderConfigInput }
        try {
          const provider = await this.registry.upsertProvider(p.provider)
          const engines = await this.sessionManager.listEngines()
          const providers = this.registry.listProviders(engines)
          return { provider, providers, engines }
        } catch (err) {
          throw new HubRpcError(
            ErrorCode.InvalidParams,
            err instanceof Error ? err.message : String(err),
          )
        }
      }
      case 'provider.remove': {
        const p = params as { providerId: string }
        try {
          this.registry.removeProvider(p.providerId)
          const engines = await this.sessionManager.listEngines()
          const providers = this.registry.listProviders(engines)
          return { providers, engines }
        } catch (err) {
          throw new HubRpcError(
            ErrorCode.InvalidParams,
            err instanceof Error ? err.message : String(err),
          )
        }
      }
      case 'provider.probe': {
        const p = (params ?? {}) as { providerId?: string; provider?: ProviderConfigInput }
        try {
          if (p.provider) {
            const probed = await this.registry.probeProviderDraft(p.provider)
            const engines = await this.sessionManager.listEngines()
            const providers = this.registry.listProviders(engines)
            return { provider: probed, providers, engines }
          }
          await this.registry.refreshProviders(p.providerId)
          const engines = await this.sessionManager.listEngines()
          const providers = this.registry.listProviders(engines)
          const probed = p.providerId
            ? providers.find(item => item.id === p.providerId)
            : undefined
          return { ...(probed ? { provider: probed } : {}), providers, engines }
        } catch (err) {
          throw new HubRpcError(
            ErrorCode.InvalidParams,
            err instanceof Error ? err.message : String(err),
          )
        }
      }
      case 'engine.list': {
        await this.registry.refreshProviders()
        const engines = await this.sessionManager.listEngines()
        return { engines }
      }
      case 'workspace.list': {
        return { workspaces: this.discoverWorkspaces() }
      }
      case 'workspace.files': {
        const p = params as { path: string; query?: string; limit?: number }
        return searchWorkspaceFiles(p.path, this.effectiveWorkspaceRoots(), {
          query: p.query,
          limit: p.limit,
        })
      }
      case 'fs.list': {
        const p = params as { path: string }
        return listWorkspaceDirectory(p.path, this.effectiveWorkspaceRoots())
      }
      case 'fs.read': {
        const p = params as { path: string }
        return readWorkspaceFile(p.path, this.effectiveWorkspaceRoots())
      }
      case 'session.list': {
        const p = (params ?? {}) as { engineId?: string }
        return { sessions: this.sessionManager.listSessions(p.engineId) }
      }
      case 'session.search': {
        const p = params as {
          query: string
          sessionId?: string
          engineId?: string
          role?: 'all' | 'user' | 'assistant'
          cwd?: string
          includeArchived?: boolean
          limit?: number
          matchesPerSession?: number
        }
        return this.sessionManager.searchSessions(p)
      }
      case 'session.create': {
        const p = params as {
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
        }
        const session = await this.sessionManager.createSession(p)
        conn.subscribedSessions.add(session.id)
        return { session }
      }
      case 'session.prompt': {
        const p = params as {
          sessionId: string
          text: string
          images?: PromptImageAttachment[]
          providerId?: string
          modelId?: string
          reasoningEffort?: ReasoningEffort
          mode?: SessionMode
        }
        if (!conn.subscribedSessions.has(p.sessionId)) {
          conn.subscribedSessions.add(p.sessionId)
          this.sessionManager.retainHostWatch(p.sessionId)
        }
        return await this.sessionManager.prompt(p.sessionId, p.text, {
          images: p.images,
          providerId: p.providerId,
          modelId: p.modelId,
          reasoningEffort: p.reasoningEffort,
          mode: p.mode,
        })
      }
      case 'session.cancel': {
        const p = params as { sessionId: string }
        await this.sessionManager.cancel(p.sessionId)
        return { ok: true as const }
      }
      case 'session.cancelQueued': {
        const p = params as { sessionId: string; index?: number; all?: boolean }
        return this.sessionManager.cancelQueuedPrompt(p.sessionId, {
          index: p.index,
          all: p.all,
        })
      }
      case 'session.setModel': {
        const p = params as {
          sessionId: string
          providerId?: string
          modelId: string
          reasoningEffort?: ReasoningEffort
          mode?: SessionMode
        }
        const session = await this.sessionManager.setModel(p.sessionId, p.modelId, {
          providerId: p.providerId,
          reasoningEffort: p.reasoningEffort,
          mode: p.mode,
        })
        return { session }
      }
      case 'session.rename': {
        const p = params as { sessionId: string; title: string }
        const session = this.sessionManager.renameSession(p.sessionId, p.title)
        return { session }
      }
      case 'session.archive': {
        const p = params as { sessionId: string; archived: boolean }
        const session = this.sessionManager.archiveSession(p.sessionId, p.archived)
        return { session }
      }
      case 'session.close': {
        const p = params as { sessionId: string }
        conn.subscribedSessions.delete(p.sessionId)
        await this.sessionManager.closeSession(p.sessionId)
        return { ok: true as const }
      }
      case 'artifact.list': {
        const p = params as { sessionId: string }
        return {
          sessionId: p.sessionId,
          artifacts: this.sessionManager.listArtifacts(p.sessionId),
        }
      }
      case 'artifact.read': {
        const p = params as { sessionId: string; artifactId: string }
        return this.sessionManager.readArtifact(p.sessionId, p.artifactId)
      }
      case 'stream.subscribe': {
        const p = params as {
          sessionId: string
          afterSeq: number
          beforeSeq?: number
          aroundSeq?: number
          limit?: number
        }
        if (!conn.subscribedSessions.has(p.sessionId)) {
          conn.subscribedSessions.add(p.sessionId)
          this.sessionManager.retainHostWatch(p.sessionId)
        }
        const snap = this.sessionManager.subscribeSnapshot(p.sessionId, p.afterSeq, {
          beforeSeq: p.beforeSeq,
          aroundSeq: p.aroundSeq,
          limit: p.limit,
        })
        if (p.beforeSeq === undefined && p.aroundSeq === undefined) {
          // Non-blocking terminal inspection: return transcript immediately (~1-15ms) and push any
          // live terminal status / permission dialog asynchronously via WebSocket notifications.
          void this.sessionManager.syncHostTerminalNow(p.sessionId).catch(() => {})
        }
        return snap
      }
      case 'stream.unsubscribe': {
        const p = params as { sessionId: string }
        if (conn.subscribedSessions.delete(p.sessionId)) {
          this.sessionManager.releaseHostWatch(p.sessionId)
        }
        return { ok: true as const }
      }
      case 'approval.respond': {
        const p = params as { sessionId: string; requestId: string; optionId?: string | null }
        const normalizedOptionId = p.optionId ?? null
        try {
          await this.sessionManager.respondPermission(p.sessionId, p.requestId, normalizedOptionId)
          console.log(
            `[lework-approval] OK session=${p.sessionId} requestId=${p.requestId} option=${normalizedOptionId ?? 'cancel'}`,
          )
          return { ok: true as const }
        } catch (err) {
          console.warn(
            `[lework-approval] FAIL session=${p.sessionId} requestId=${p.requestId} option=${normalizedOptionId ?? 'cancel'}: ${err instanceof Error ? err.message : String(err)}`,
          )
          throw err
        }
      }
      case 'checkpoint.list': {
        const p = params as { sessionId: string }
        return { checkpoints: this.sessionManager.listCheckpoints(p.sessionId) }
      }
      case 'checkpoint.create': {
        const p = params as { sessionId: string; label?: string }
        return { checkpoint: this.sessionManager.createCheckpoint(p.sessionId, p.label) }
      }
      case 'checkpoint.restore': {
        const p = params as { sessionId: string; checkpointId: string }
        this.sessionManager.restoreCheckpoint(p.sessionId, p.checkpointId)
        return { ok: true as const }
      }
      case 'usage.summary': {
        const p = (params ?? {}) as { days?: number; engineId?: string }
        return this.usageService.getSummary(p)
      }
      case 'usage.quota': {
        return this.usageService.getQuota()
      }
      case 'usage.session': {
        const p = params as { sessionId: string }
        return this.usageService.getSessionUsage(p.sessionId)
      }
      case 'usage.proxy': {
        return await this.usageService.getProxyUsage()
      }
      case 'git.status': {
        const p = params as { path: string }
        return inspectWorkspaceGitStatus(p.path, this.effectiveWorkspaceRoots())
      }
      case 'git.diff': {
        const p = params as { path: string; filePath?: string; baseCommitSha?: string }
        return readWorkspaceGitDiff(p.path, this.effectiveWorkspaceRoots(), {
          filePath: p.filePath,
          baseCommitSha: p.baseCommitSha,
        })
      }
    }
  }

  private countSubscribers(sessionId: string): number {
    let count = 0
    for (const c of this.clients) {
      if (c.authenticated && c.subscribedSessions.has(sessionId) && c.ws.readyState === WebSocket.OPEN) {
        count++
      }
    }
    return count
  }

  private broadcastSessionEvent(event: AgentEvent): void {
    this.notifier.onEvent(event)
    const msg = {
      jsonrpc: '2.0',
      method: 'stream.event',
      params: { event },
    }
    for (const c of this.clients) {
      if (c.authenticated && c.subscribedSessions.has(event.sessionId)) {
        this.sendJson(c.ws, msg)
      }
    }
  }

  private broadcastSessionUpdated(session: SessionMeta): void {
    const msg = {
      jsonrpc: '2.0',
      method: 'session.updated',
      params: { session },
    }
    for (const c of this.clients) {
      if (c.authenticated) {
        this.sendJson(c.ws, msg)
      }
    }
  }

  private sendRpcError(ws: WebSocket, id: string | number | null, code: number, message: string): void {
    this.sendJson(ws, {
      jsonrpc: '2.0',
      id,
      error: { code, message },
    })
  }

  private sendJson(ws: WebSocket, payload: unknown): void {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload))
    }
  }
}

function timingSafeEqualStr(a: string, b: string): boolean {
  const bufA = Buffer.from(a, 'utf8')
  const bufB = Buffer.from(b, 'utf8')
  if (bufA.length !== bufB.length) return false
  return crypto.timingSafeEqual(bufA, bufB)
}

function delay(ms: number): Promise<void> {
  return new Promise(r => setTimeout(r, ms))
}

function readRequestBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let data = ''
    req.setEncoding('utf8')
    req.on('data', chunk => {
      data += chunk
      if (data.length > 64 * 1024) {
        req.destroy()
        reject(new Error('Request body too large'))
      }
    })
    req.on('end', () => resolve(data))
    req.on('error', reject)
  })
}
