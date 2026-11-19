import crypto from 'node:crypto'
import fs from 'node:fs'
import https from 'node:https'
import os from 'node:os'
import path from 'node:path'
import type { TLSSocket } from 'node:tls'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { WebSocket } from 'ws'
import { ErrorCode, PROTOCOL_VERSION } from '@qprc/protocol'

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-sec-'))
process.env.QPRC_HOME = dataDir

const { HubServer } = await import('../src/server.ts')
const { DiagnosticEchoAdapter } = await import('../src/engines/echo-adapter.ts')
const { attestProof, tokenAttestKey, tokenKid } = await import('../src/security/tls.ts')
const { FeishuGateAuth } = await import('../src/auth/feishu-auth.ts')
const { IssuedTokenStore } = await import('../src/auth/token-store.ts')
const { isSensitivePath } = await import('../src/security/path-guard.ts')

const TOKEN = 'sec-admin-token-0123456789'
const JOIN = 'sec-join-token-0123456789'

interface HttpRes {
  status: number
  body: any
  fingerprint: string
}

function request(port: number, method: string, pathname: string, body?: unknown, headers: Record<string, string> = {}) {
  return new Promise<HttpRes>((resolve, reject) => {
    const req = https.request(
      { host: '127.0.0.1', port, method, path: pathname, rejectUnauthorized: false, headers, agent: false },
      res => {
        const sock = res.socket as TLSSocket
        const raw = sock.getPeerCertificate().raw
        const fingerprint = crypto.createHash('sha256').update(raw).digest('hex')
        let data = ''
        res.on('data', c => (data += c))
        res.on('end', () => {
          let parsed: any = data
          try {
            parsed = JSON.parse(data)
          } catch {
            // keep raw
          }
          resolve({ status: res.statusCode ?? 0, body: parsed, fingerprint })
        })
      },
    )
    req.on('error', reject)
    if (body !== undefined) req.write(JSON.stringify(body))
    req.end()
  })
}

function openWs(port: number, pathname: string, headers: Record<string, string> = {}): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`wss://127.0.0.1:${port}${pathname}`, { rejectUnauthorized: false, headers })
    ws.once('open', () => resolve(ws))
    ws.once('error', reject)
    ws.once('unexpected-response', (_req, res) => reject(new Error(`HTTP ${res.statusCode}`)))
  })
}

function nextMessage(ws: WebSocket): Promise<any> {
  return new Promise(resolve => ws.once('message', raw => resolve(JSON.parse(String(raw)))))
}

function waitClose(ws: WebSocket): Promise<number> {
  return new Promise(resolve => ws.once('close', code => resolve(code)))
}

describe('Hub transport & auth security', () => {
  let server: InstanceType<typeof HubServer>
  let port = 0
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-sec-root-'))

  beforeAll(async () => {
    fs.writeFileSync(path.join(root, 'README.md'), 'hello')
    fs.writeFileSync(path.join(root, '.env'), 'SECRET=1')
    fs.writeFileSync(path.join(root, 'id_ed25519'), 'PRIVATE')
    server = new HubServer({
      config: {
        hubId: 'hub_sec',
        name: 'SecMac',
        role: 'slave',
        host: '127.0.0.1',
        port: 0,
        token: TOKEN,
        slaveJoinToken: JOIN,
        workspaceRoots: [root, dataDir],
        relayUrls: ['http://relay.example:64450'],
        cpaEndpoint: 'http://127.0.0.1:8317',
      },
      adapters: [new DiagnosticEchoAdapter()],
    })
    port = (await server.start()).port
  })

  afterAll(async () => {
    await server.stop()
  })

  it('serves TLS only, and discovery leaks no relay URLs', async () => {
    const res = await request(port, 'GET', '/v1/auth/discover')
    expect(res.status).toBe(200)
    expect(res.fingerprint).toBe(server.tlsFingerprint)
    expect(res.body.tls).toBe(true)
    expect(res.body.relayUrls).toBeUndefined()
  })

  it('token-bound attestation proves the certificate to token holders only', async () => {
    const nonce = crypto.randomBytes(16).toString('hex')
    const ok = await request(port, 'POST', '/v1/auth/attest', { nonce, kid: tokenKid(TOKEN) })
    expect(ok.status).toBe(200)
    expect(ok.body.fingerprint).toBe(ok.fingerprint)
    expect(ok.body.proof).toBe(attestProof(tokenAttestKey(TOKEN), ok.fingerprint, nonce))

    const unknown = await request(port, 'POST', '/v1/auth/attest', { nonce, kid: tokenKid('some-other-token') })
    expect(unknown.status).toBe(404)
    const bad = await request(port, 'POST', '/v1/auth/attest', { nonce: 'x', kid: 'y' })
    expect(bad.status).toBe(400)
  })

  it('refuses browser-originated requests (Origin) on WS upgrade and POST', async () => {
    await expect(openWs(port, '/v1/ws', { Origin: 'https://evil.example' })).rejects.toThrow()
    const post = await request(port, 'POST', '/v1/auth/feishu/start', {}, { Origin: 'https://evil.example' })
    expect(post.status).toBe(403)
  })

  it('closes the socket after a failed hello and survives prototype-key method names', async () => {
    const ws = await openWs(port, '/v1/ws')
    ws.send(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'toString', params: {} }))
    expect((await nextMessage(ws)).error.code).toBe(ErrorCode.MethodNotFound)
    const closed = waitClose(ws)
    ws.send(
      JSON.stringify({
        jsonrpc: '2.0',
        id: 2,
        method: 'hub.hello',
        params: { protocolVersion: PROTOCOL_VERSION, token: 'wrong-token-xxxx', client: { name: 't', version: '1', platform: 'x' } },
      }),
    )
    expect((await nextMessage(ws)).error.code).toBe(ErrorCode.Unauthorized)
    expect(await closed).toBe(1008)
  })

  it('returns relay URLs (upgraded to https) only after authentication; blocks secret files', async () => {
    const ws = await openWs(port, '/v1/ws')
    ws.send(
      JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'hub.hello',
        params: { protocolVersion: PROTOCOL_VERSION, token: TOKEN, client: { name: 't', version: '1', platform: 'x' } },
      }),
    )
    const hello = await nextMessage(ws)
    expect(hello.result.relayUrls).toEqual(['https://relay.example:64450'])

    const call = async (id: number, method: string, params: unknown) => {
      ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }))
      return nextMessage(ws)
    }
    expect((await call(2, 'fs.read', { path: path.join(root, 'README.md') })).result.content).toBe('hello')
    expect((await call(3, 'fs.read', { path: path.join(root, '.env') })).error.code).toBe(ErrorCode.PathNotAllowed)
    expect((await call(4, 'fs.read', { path: path.join(root, 'id_ed25519') })).error.code).toBe(ErrorCode.PathNotAllowed)
    expect((await call(5, 'fs.list', { path: dataDir })).error.code).toBe(ErrorCode.PathNotAllowed)
    ws.close()
  })

  it('slaves must use the dedicated join token and cannot impersonate the master', async () => {
    const withAdmin = await openWs(port, '/v1/slave-ws')
    const c1 = waitClose(withAdmin)
    withAdmin.send(JSON.stringify({ token: TOKEN, slave: { id: 's1', name: 'x', platform: 'linux', engines: [] } }))
    expect(await c1).toBe(1008)

    const impostor = await openWs(port, '/v1/slave-ws')
    const c2 = waitClose(impostor)
    impostor.send(JSON.stringify({ token: JOIN, slave: { id: 'hub_sec', name: 'x', platform: 'linux', engines: [] } }))
    expect(await c2).toBe(1008)

    const good = await openWs(port, '/v1/slave-ws')
    good.send(JSON.stringify({ token: JOIN, slave: { id: 'devbox_1', name: 'devbox', platform: 'linux', engines: ['claude'] } }))
    expect((await nextMessage(good)).ok).toBe(true)
    good.close()
  })
})

describe('OAuth-issued tokens', () => {
  it('are audience-bound and revocable', () => {
    fs.writeFileSync(
      path.join(dataDir, 'feishu-secret.json'),
      JSON.stringify({
        feishuAppId: 'cli_x',
        feishuAppSecret: 'y',
        allowedOpenIds: ['ou_owner'],
        hmacSecret: crypto.randomBytes(32).toString('base64url'),
      }),
      { mode: 0o644 },
    )
    const store = new IssuedTokenStore(path.join(dataDir, 'issued-test.json'))
    const auth = new FeishuGateAuth({ hubId: 'hub_a', issued: store })
    expect(fs.statSync(path.join(dataDir, 'feishu-secret.json')).mode & 0o077).toBe(0)

    const token = auth.signSessionToken({ openId: 'ou_owner', name: 'Owner', email: '' })
    expect(auth.verifySessionToken(token)?.openId).toBe('ou_owner')
    expect(store.attestKeyForKid(tokenKid(token))?.equals(tokenAttestKey(token))).toBe(true)

    const otherHub = new FeishuGateAuth({ hubId: 'hub_b', issued: store })
    expect(otherHub.verifySessionToken(token)).toBeNull()

    store.revoke(tokenKid(token))
    expect(auth.verifySessionToken(token)).toBeNull()
  })

  it('rejects device-flow start without a valid PKCE challenge', async () => {
    const auth = new FeishuGateAuth({ hubId: 'hub_a', issued: new IssuedTokenStore(path.join(dataDir, 'i2.json')) })
    await expect(auth.startDeviceFlow('not-a-hash')).rejects.toThrow(/Invalid code challenge/)
  })
})

describe('path guard', () => {
  it('flags credential files', () => {
    for (const p of ['/x/.env', '/x/.env.production', '/x/id_rsa', '/x/server.pem', `${os.homedir()}/.ssh/config`]) {
      expect(isSensitivePath(p)).toBe(true)
    }
    for (const p of ['/x/.env.example', '/x/id_rsa.pub', '/x/main.ts', '/x/README.md']) {
      expect(isSensitivePath(p)).toBe(false)
    }
  })
})
