import crypto from 'node:crypto'
import fs from 'node:fs'
import https from 'node:https'
import type net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import type { TLSSocket } from 'node:tls'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { WebSocket } from 'ws'
import { PROTOCOL_VERSION } from '@lework/protocol'

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-tsnet-pair-'))
process.env.LEWORK_HOME = dataDir

const { HubServer } = await import('../src/server.ts')
const { DiagnosticEchoAdapter } = await import('../src/engines/echo-adapter.ts')
const { TsnetManager } = await import('../src/auth/tsnet-manager.ts')
const { encodePairingDeepLink } = await import('../src/auth/pair-manager.ts')
const { IssuedTokenStore } = await import('../src/auth/token-store.ts')
const { attestProof, tokenAttestKey, tokenKid } = await import('../src/security/tls.ts')
const { buildMdnsAnnouncementPacket, collectTailscaleIpv4Addresses, isLeworkMdnsQuery } =
  await import('../src/bonjour.ts')

const ADMIN_TOKEN = 'admin-pair-token-0123456789'

interface HttpRes {
  status: number
  body: any
  fingerprint: string
}

function request(
  port: number,
  method: string,
  pathname: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<HttpRes> {
  return new Promise((resolve, reject) => {
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

function openWsAndHello(port: number, token: string): Promise<any> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`wss://127.0.0.1:${port}/v1/ws`, { rejectUnauthorized: false })
    ws.once('open', () => {
      ws.send(
        JSON.stringify({
          jsonrpc: '2.0',
          id: 1,
          method: 'hub.hello',
          params: {
            protocolVersion: PROTOCOL_VERSION,
            token,
            client: { name: 'test-phone', version: '0.5.0', platform: 'ios' },
          },
        }),
      )
    })
    ws.once('message', raw => {
      const msg = JSON.parse(String(raw))
      ws.close()
      resolve(msg)
    })
    ws.once('error', reject)
  })
}

describe('Tailscale same-account tsnet handshake & single-use QR pairing', () => {
  let server: InstanceType<typeof HubServer>
  let port = 0
  let simulatedSameUserPeer: {
    sameUser: boolean
    loginName: string
    displayName: string
    nodeName: string
    tailscaleIp: string
  } | null = null

  beforeAll(async () => {
    const fakeTsnet = new TsnetManager({ dataDir, binaryPath: null, autoStart: false })
    fakeTsnet.verifyInboundSocket = async (_sock: net.Socket) => simulatedSameUserPeer
    fakeTsnet.getStatus = async () => ({
      available: true,
      mode: 'tsnet',
      backendState: 'Running',
      loginName: 'owner@example.com',
      displayName: 'Owner',
      tailscaleIps: ['100.88.12.34'],
      peers: [],
    })

    server = new HubServer({
      config: {
        hubId: 'hub_mesh_test',
        name: 'MeshHost',
        role: 'slave',
        host: '127.0.0.1',
        port: 0,
        token: ADMIN_TOKEN,
        workspaceRoots: [dataDir],
        relayUrls: ['https://relay.example:64450'],
        cpaEndpoint: 'http://127.0.0.1:8317',
      },
      adapters: [new DiagnosticEchoAdapter()],
      tsnetManager: fakeTsnet,
    })
    port = (await server.start()).port
  })

  afterAll(async () => {
    await server.stop()
  })

  it('rejects /v1/auth/tailscale when socket is not a verified same-account Tailscale peer', async () => {
    simulatedSameUserPeer = null
    const disc = await request(port, 'GET', '/v1/auth/discover')
    expect(disc.status).toBe(200)
    expect(disc.body.tailscaleSameUser).toBe(false)

    const nonce = crypto.randomBytes(16).toString('hex')
    const denied = await request(port, 'POST', '/v1/auth/tailscale', { nonce, deviceName: 'Intruder' })
    expect(denied.status).toBe(403)
  })

  it('auto-handshakes same-account Tailscale peer, attests TLS cert, and issues revocable token', async () => {
    simulatedSameUserPeer = {
      sameUser: true,
      loginName: 'owner@example.com',
      displayName: 'Owner',
      nodeName: 'iphone-16-pro',
      tailscaleIp: '100.88.99.10',
    }

    const disc = await request(port, 'GET', '/v1/auth/discover')
    expect(disc.status).toBe(200)
    expect(disc.body.tailscaleSameUser).toBe(true)
    expect(disc.body.tailscaleUser).toBe('Owner')

    const nonce = crypto.randomBytes(16).toString('hex')
    const hs = await request(port, 'POST', '/v1/auth/tailscale', { nonce, deviceName: 'iPhone 16 Pro' })
    expect(hs.status).toBe(200)
    expect(hs.body.ok).toBe(true)
    expect(hs.body.accessToken).toMatch(/^lework_dt\./)
    expect(hs.body.fingerprint).toBe(hs.fingerprint)
    expect(hs.body.proof).toBe(attestProof(tokenAttestKey(hs.body.accessToken), hs.fingerprint, nonce))
    expect(hs.body.endpoints).toContain(`https://100.88.12.34:${port}`)
    expect(hs.body.endpoints).toContain('https://relay.example:64450')

    // Issued token authenticates WebSocket JSON-RPC
    const hello = await openWsAndHello(port, hs.body.accessToken)
    expect(hello.result.hub.id).toBe('hub_mesh_test')

    // Issued token also works with /v1/auth/attest on future reconnects
    const nonce2 = crypto.randomBytes(16).toString('hex')
    const att = await request(port, 'POST', '/v1/auth/attest', {
      nonce: nonce2,
      kid: tokenKid(hs.body.accessToken),
    })
    expect(att.status).toBe(200)
    expect(att.body.proof).toBe(attestProof(tokenAttestKey(hs.body.accessToken), hs.fingerprint, nonce2))

    // Revoking via IssuedTokenStore immediately invalidates the device token
    const store = new IssuedTokenStore()
    expect(store.revoke(tokenKid(hs.body.accessToken))).toBe(true)
    const afterRevoke = await openWsAndHello(port, hs.body.accessToken)
    expect(afterRevoke.error).toBeDefined()
  })

  it('mints single-use QR pairCode that succeeds once and rejects replay', async () => {
    const unauthCreate = await request(port, 'POST', '/v1/auth/pair/create')
    expect(unauthCreate.status).toBe(401)

    const created = await request(port, 'POST', '/v1/auth/pair/create', undefined, {
      authorization: `Bearer ${ADMIN_TOKEN}`,
    })
    expect(created.status).toBe(200)
    expect(created.body.pairCode).toMatch(/^lwp_[0-9a-f]{12}_/)

    const deepLink = encodePairingDeepLink({
      v: 2,
      hubId: created.body.hub.id,
      name: created.body.hub.name,
      role: created.body.hub.role,
      urls: created.body.endpoints,
      url: created.body.endpoints[0],
      fp: created.body.fingerprint,
      pairCode: created.body.pairCode,
    })
    expect(deepLink.startsWith('lework://pair?d=')).toBe(true)

    const nonce = crypto.randomBytes(16).toString('hex')
    const redeemed = await request(port, 'POST', '/v1/auth/pair', {
      pairCode: created.body.pairCode,
      nonce,
      deviceName: 'My iPhone',
    })
    expect(redeemed.status).toBe(200)
    expect(redeemed.body.accessToken).toMatch(/^lework_dt\./)
    expect(redeemed.body.proof).toBe(
      attestProof(tokenAttestKey(redeemed.body.accessToken), redeemed.fingerprint, nonce),
    )

    // Replaying the same single-use pairCode must fail!
    const replayed = await request(port, 'POST', '/v1/auth/pair', {
      pairCode: created.body.pairCode,
      nonce: crypto.randomBytes(16).toString('hex'),
    })
    expect(replayed.status).toBe(401)
  })
})

describe('Cross-platform UDP mDNS & Tailscale IP helpers', () => {
  it('encodes RFC 6762 mDNS response and matches _lework._tcp queries', () => {
    const resp = buildMdnsAnnouncementPacket({
      instanceName: 'Lework Hub (linux-worker)',
      hostFqdn: 'linux-worker.local',
      port: 64450,
      ips: ['192.168.1.50'],
      txt: ['v=1', 'hubId=hub_1'],
    })
    expect(resp.readUInt16BE(2)).toBe(0x8400)
    expect(resp.readUInt16BE(6)).toBe(4) // PTR + SRV + TXT + A
    // Announcements have QR=1 so isLeworkMdnsQuery must ignore them
    expect(isLeworkMdnsQuery(resp)).toBe(false)

    // Construct a minimal DNS query (QR=0, QDCOUNT=1) for `_lework._tcp.local`
    const qHeader = Buffer.alloc(12)
    qHeader.writeUInt16BE(0, 0)
    qHeader.writeUInt16BE(0, 2)
    qHeader.writeUInt16BE(1, 4)
    const qBody = Buffer.from([
      0x07, ...Buffer.from('_lework'),
      0x04, ...Buffer.from('_tcp'),
      0x05, ...Buffer.from('local'),
      0x00,
      0x00, 0x0c, // PTR
      0x00, 0x01, // IN
    ])
    expect(isLeworkMdnsQuery(Buffer.concat([qHeader, qBody]))).toBe(true)

    const tsIps = collectTailscaleIpv4Addresses({
      tailscale0: [
        {
          address: '100.101.102.103',
          netmask: '255.255.255.255',
          family: 'IPv4',
          mac: '00:00:00:00:00:00',
          internal: false,
          cidr: '100.101.102.103/32',
        },
      ],
      en0: [
        {
          address: '192.168.1.20',
          netmask: '255.255.255.0',
          family: 'IPv4',
          mac: '00:00:00:00:00:00',
          internal: false,
          cidr: '192.168.1.20/24',
        },
      ],
    })
    expect(tsIps).toEqual(['100.101.102.103'])
  })
})
