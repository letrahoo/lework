import crypto from 'node:crypto'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { WebSocketServer, WebSocket } from 'ws'
import {
  ErrorCode,
  Methods,
  PROTOCOL_VERSION,
  RpcRequest,
  type AgentEvent,
  type HubInfo,
  type MethodName,
  type ProfileInfo,
  type ReasoningEffort,
  type SessionMeta,
  type SessionMode,
  type SlaveInfo,
  type Workspace,
} from '@qprc/protocol'
import type { HubConfig } from './config.ts'
import { BonjourAdvertiser } from './bonjour.ts'
import { FeishuGateAuth } from './auth/feishu-auth.ts'
import { AgyEngineAdapter } from './engines/agy-adapter.ts'
import { ClaudeCodeAdapter } from './engines/claude-adapter.ts'
import { CodexEngineAdapter } from './engines/codex-adapter.ts'
import { DshEngineAdapter } from './engines/dsh-adapter.ts'
import { DiagnosticEchoAdapter } from './engines/echo-adapter.ts'
import { PiEngineAdapter } from './engines/pi-adapter.ts'
import type { EngineAdapter } from './engines/types.ts'
import { detectPlatform } from './platform/index.ts'
import { ModelRegistry } from './registry/model-registry.ts'
import { HubRpcError } from './security/path-guard.ts'
import { SessionManager } from './session-manager.ts'
import type { HostTaskInspector } from './storage/host-history.ts'
import { listWorkspaceDirectory, readWorkspaceFile } from './storage/workspace-fs.ts'

const HUB_VERSION = '0.3.0'

interface ClientConn {
  ws: WebSocket
  authenticated: boolean
  subscribedSessions: Set<string>
}

export interface HubServerOptions {
  config: HubConfig
  adapters?: EngineAdapter[]
  hostInspector?: HostTaskInspector
}

export class HubServer {
  private readonly httpServer: http.Server
  private readonly wss: WebSocketServer
  private readonly clients = new Set<ClientConn>()
  private readonly remoteSlaves = new Map<string, SlaveInfo>()
  private readonly sessionManager: SessionManager
  private readonly registry: ModelRegistry
  private readonly feishuAuth: FeishuGateAuth
  private readonly config: HubConfig
  private bonjour: BonjourAdvertiser | null = null

  constructor(opts: HubServerOptions) {
    this.config = opts.config
    this.feishuAuth = new FeishuGateAuth()
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
      this.config.workspaceRoots,
      adapters,
      {
        onEvent: ev => this.broadcastSessionEvent(ev),
        onSessionUpdated: meta => this.broadcastSessionUpdated(meta),
      },
      this.registry,
      opts.hostInspector,
    )

    this.httpServer = http.createServer((req, res) => {
      void this.handleHttp(req, res)
    })

    this.wss = new WebSocketServer({ noServer: true })
    this.httpServer.on('upgrade', (req, socket, head) => {
      const url = new URL(req.url ?? '/', 'http://localhost')
      if (url.pathname === '/v1/ws') {
        this.wss.handleUpgrade(req, socket, head, ws => {
          this.handleConnection(ws)
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
    this.bonjour = new BonjourAdvertiser({
      name: this.config.name,
      hubId: this.config.hubId,
      host: this.config.host,
      port,
    })
    this.bonjour.start()
    return { port, host: this.config.host }
  }

  async stop(): Promise<void> {
    this.bonjour?.stop()
    this.bonjour = null
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
    // Probe providers first so CPA models are cached before listEngines() builds catalogs
    const prelimEngines = await this.sessionManager.listEngines()
    const installedSet = new Set(prelimEngines.filter(e => e.status.installed).map(e => e.id))
    const providers = await this.registry.probeProviders(installedSet)
    const engines = await this.sessionManager.listEngines()
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
        const real = fs.realpathSync(p)
        if (seen.has(real)) return
        const st = fs.statSync(real)
        if (!st.isDirectory()) return
        seen.add(real)
        const id = `ws_${crypto.createHash('sha1').update(real).digest('hex').slice(0, 10)}`
        out.push({ id, name: path.basename(real) || real, path: real })
      } catch {
        // ignore
      }
    }

    addDir(process.cwd())

    const home = os.homedir()
    const projectParentDirs = [
      path.join(home, 'work'),
      path.join(home, 'personal'),
      ...this.config.workspaceRoots.filter(r => r !== home),
    ]

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
    if (this.feishuAuth.verifySessionToken(token)) return true
    return timingSafeEqualStr(token, this.config.token)
  }

  private async handleHttp(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const url = new URL(req.url ?? '/', 'http://localhost')
    if (req.method === 'GET' && (url.pathname === '/healthz' || url.pathname === '/v1/auth/discover')) {
      const feishuEnabled = this.feishuAuth.isConfigured()
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' })
      res.end(
        JSON.stringify({
          ok: true,
          hub: this.getHubInfo(),
          feishuEnabled,
          relayUrls: this.config.relayUrls ?? [],
          wsPath: '/v1/ws',
        }),
      )
      return
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/feishu/start') {
      try {
        const challenge = await this.feishuAuth.startDeviceFlow()
        res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify(challenge))
      } catch (err) {
        res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ error: err instanceof Error ? err.message : String(err) }))
      }
      return
    }
    if (req.method === 'POST' && url.pathname === '/v1/auth/feishu/poll') {
      try {
        const bodyStr = await readRequestBody(req)
        const parsed = JSON.parse(bodyStr || '{}') as { sid?: string }
        if (!parsed.sid) {
          res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' })
          res.end(JSON.stringify({ status: 'expired', detail: 'Missing sid' }))
          return
        }
        const pollRes = await this.feishuAuth.pollDeviceFlow(parsed.sid)
        res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify(pollRes))
      } catch (err) {
        res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ status: 'expired', detail: err instanceof Error ? err.message : String(err) }))
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
    ws.on('message', raw => {
      try {
        const msg = JSON.parse(String(raw)) as {
          token?: string
          slave?: SlaveInfo
        }
        if (!msg.token || !timingSafeEqualStr(msg.token, this.config.token)) {
          ws.close(1008, 'Unauthorized slave token')
          return
        }
        if (msg.slave?.id) {
          registeredId = msg.slave.id
          this.remoteSlaves.set(registeredId, {
            ...msg.slave,
            online: true,
            lastSeenAt: Date.now(),
          })
          ws.send(JSON.stringify({ ok: true, masterId: this.config.hubId }))
        }
      } catch {
        // ignore
      }
    })
    ws.on('close', () => {
      if (registeredId) {
        const prev = this.remoteSlaves.get(registeredId)
        if (prev) {
          this.remoteSlaves.set(registeredId, { ...prev, online: false, lastSeenAt: Date.now() })
        }
      }
    })
  }

  private handleConnection(ws: WebSocket): void {
    const conn: ClientConn = {
      ws,
      authenticated: false,
      subscribedSessions: new Set(),
    }
    this.clients.add(conn)

    ws.on('message', raw => {
      void this.handleWsMessage(conn, String(raw))
    })

    ws.on('close', () => {
      for (const sid of conn.subscribedSessions) {
        this.sessionManager.releaseHostWatch(sid)
      }
      conn.subscribedSessions.clear()
      this.clients.delete(conn)
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
    if (!(method in Methods)) {
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
        this.sendRpcError(conn.ws, id, err.code, err.message)
      } else {
        this.sendRpcError(
          conn.ws,
          id,
          ErrorCode.Internal,
          err instanceof Error ? err.message : 'Internal error',
        )
      }
    }
  }

  private async dispatchMethod(conn: ClientConn, method: MethodName, params: unknown): Promise<unknown> {
    switch (method) {
      case 'hub.hello': {
        const p = params as { protocolVersion: number; token: string }
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
        this.sessionManager.startGlobalHostMonitor()
        return {
          protocolVersion: PROTOCOL_VERSION,
          hub: this.getHubInfo(),
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
      case 'engine.list': {
        const engines = await this.sessionManager.listEngines()
        return { engines }
      }
      case 'workspace.list': {
        return { workspaces: this.discoverWorkspaces() }
      }
      case 'fs.list': {
        const p = params as { path: string }
        return listWorkspaceDirectory(p.path, this.config.workspaceRoots)
      }
      case 'fs.read': {
        const p = params as { path: string }
        return readWorkspaceFile(p.path, this.config.workspaceRoots)
      }
      case 'session.list': {
        const p = (params ?? {}) as { engineId?: string }
        return { sessions: this.sessionManager.listSessions(p.engineId) }
      }
      case 'session.create': {
        const p = params as {
          engineId: string
          cwd: string
          workspaces?: string[]
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
          modelId?: string
          reasoningEffort?: ReasoningEffort
          mode?: SessionMode
        }
        conn.subscribedSessions.add(p.sessionId)
        return await this.sessionManager.prompt(p.sessionId, p.text, {
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
      case 'session.setModel': {
        const p = params as { sessionId: string; modelId: string }
        const session = await this.sessionManager.setModel(p.sessionId, p.modelId)
        return { session }
      }
      case 'session.close': {
        const p = params as { sessionId: string }
        conn.subscribedSessions.delete(p.sessionId)
        await this.sessionManager.closeSession(p.sessionId)
        return { ok: true as const }
      }
      case 'stream.subscribe': {
        const p = params as { sessionId: string; afterSeq: number; beforeSeq?: number; limit?: number }
        const snap = this.sessionManager.subscribeSnapshot(p.sessionId, p.afterSeq, {
          beforeSeq: p.beforeSeq,
          limit: p.limit,
        })
        if (!conn.subscribedSessions.has(p.sessionId)) {
          conn.subscribedSessions.add(p.sessionId)
          this.sessionManager.retainHostWatch(p.sessionId)
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
        const p = params as { sessionId: string; requestId: string; optionId: string | null }
        await this.sessionManager.respondPermission(p.sessionId, p.requestId, p.optionId)
        return { ok: true as const }
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
    }
  }

  private broadcastSessionEvent(event: AgentEvent): void {
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
