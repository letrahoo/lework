import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import qrcode from 'qrcode-terminal'
import { WebSocket } from 'ws'
import type { HubRole } from '@qprc/protocol'
import { loadOrCreateConfig } from './config.ts'
import { IssuedTokenStore } from './auth/token-store.ts'
import { defaultDataDir, generateServiceDescriptor, listLanAddresses } from './platform/index.ts'
import { loadOrCreateHubTls, shortFingerprint } from './security/tls.ts'
import { HubServer } from './server.ts'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '../..')

function parseRoleFlag(args: string[]): HubRole | undefined {
  const idx = args.indexOf('--role')
  if (idx >= 0 && args[idx + 1]) {
    const r = args[idx + 1]
    if (r === 'master' || r === 'slave' || r === 'standalone') return r
  }
  return undefined
}

function parsePortFlag(args: string[]): number | undefined {
  const idx = args.indexOf('--port')
  if (idx >= 0 && args[idx + 1]) {
    const n = Number(args[idx + 1])
    if (Number.isInteger(n) && n > 0 && n < 65536) return n
  }
  return undefined
}

async function main(): Promise<void> {
  const args = process.argv.slice(2)
  const sub = args[0] ?? 'status'
  const roleOverride = parseRoleFlag(args)
  const portOverride = parsePortFlag(args)
  const cfg = loadOrCreateConfig({
    ...(roleOverride ? { role: roleOverride } : {}),
    ...(portOverride ? { port: portOverride } : {}),
  })

  if (sub === 'serve') {
    process.on('unhandledRejection', err => {
      console.error('[hubd] unhandled rejection:', err instanceof Error ? err.message : err)
    })
    const server = new HubServer({ config: cfg })
    const { port, host } = await server.start()
    const lanIps = listLanAddresses()
    console.log(`Lework hubd (${cfg.role}) listening on https://${host}:${port}`)
    console.log(`  Hub ID      : ${cfg.hubId} (${cfg.name})`)
    console.log(`  Fingerprint : ${shortFingerprint(server.tlsFingerprint)} (sha256 ${server.tlsFingerprint})`)
    console.log('  Token       : (hidden — run `hub pair` to display)')
    for (const ip of lanIps) {
      console.log(`  LAN URL     : https://${ip}:${port}`)
    }
    const shutdown = async () => {
      console.log('\nShutting down hubd...')
      await server.stop()
      process.exit(0)
    }
    process.on('SIGINT', () => void shutdown())
    process.on('SIGTERM', () => void shutdown())
    return
  }

  if (sub === 'status') {
    const server = new HubServer({ config: cfg })
    const status = await server.buildStatus()
    const lanIps = listLanAddresses()
    console.log(`Hub: ${status.hub.name} [${status.hub.id}]`)
    console.log(`  Role     : ${status.hub.role}`)
    console.log(`  Platform : ${status.hub.platform}`)
    console.log(`  Version  : ${status.hub.version}`)
    console.log(`  Listen   : ${cfg.host}:${cfg.port}`)
    console.log(`  LAN IPs  : ${lanIps.join(', ') || 'none'}`)
    console.log(`  Token    : ${cfg.token.slice(0, 4)}…  (run \`hub pair\` to display)`)
    console.log(`  TLS FP   : ${shortFingerprint(loadOrCreateHubTls({ commonName: `lework-${cfg.hubId}` }).fingerprint)}`)
    console.log('\nProviders:')
    for (const p of status.providers) {
      console.log(`  - [${p.healthy ? 'ONLINE ' : 'OFFLINE'}] ${p.name} (${p.type})`)
    }
    console.log('\nProfiles:')
    for (const prof of status.profiles) {
      console.log(`  - ${prof.name} [engine=${prof.engineId}, mode=${prof.mode}]`)
    }
    return
  }

  if (sub === 'engines') {
    const server = new HubServer({ config: cfg })
    const status = await server.buildStatus()
    console.log('Engines on this host:')
    for (const e of status.engines) {
      const badge = e.status.ready ? 'READY    ' : e.status.installed ? 'INSTALLED' : 'MISSING  '
      const ver = e.status.version ? ` (${e.status.version})` : ''
      console.log(`  [${badge}] ${e.id.padEnd(8)} · ${e.displayName}${ver}`)
      if (e.status.detail) {
        console.log(`              ${e.status.detail}`)
      }
    }
    return
  }

  if (sub === 'slaves') {
    const server = new HubServer({ config: cfg })
    const status = await server.buildStatus()
    console.log('Registered Slaves:')
    for (const s of status.slaves) {
      console.log(`  - ${s.id} · ${s.name} [${s.platform}] engines=[${s.engines.join(', ')}]`)
    }
    return
  }

  if (sub === 'pair') {
    const lanIp = listLanAddresses()[0] ?? '127.0.0.1'
    const tls = loadOrCreateHubTls({ commonName: `lework-${cfg.hubId}` })
    const payload = JSON.stringify({
      v: 2,
      hubId: cfg.hubId,
      name: cfg.name,
      role: cfg.role,
      url: `https://${lanIp}:${cfg.port}`,
      fp: tls.fingerprint,
      token: cfg.token,
    })
    console.log('Scan or paste into the mobile app (keep this secret):')
    console.log(`  URL         : https://${lanIp}:${cfg.port}`)
    console.log(`  Token       : ${cfg.token}`)
    console.log(`  Fingerprint : ${shortFingerprint(tls.fingerprint)}  (App 「主机指纹」应与此一致)\n`)
    qrcode.generate(payload, { small: true })
    return
  }

  if (sub === 'token') {
    // hub token rotate — invalidates the static access token (all paired phones must re-pair)
    if (args[1] !== 'rotate') {
      console.log('Usage: hub token rotate')
      process.exit(1)
    }
    const cfgPath = path.join(defaultDataDir(), 'config.json')
    const stored = JSON.parse(fs.readFileSync(cfgPath, 'utf8')) as Record<string, unknown>
    stored.token = crypto.randomBytes(24).toString('base64url')
    fs.writeFileSync(cfgPath, JSON.stringify(stored, null, 2) + '\n', { mode: 0o600 })
    fs.chmodSync(cfgPath, 0o600)
    console.log('Access token rotated. Restart hubd, then run `hub pair` to re-pair your phone.')
    return
  }

  if (sub === 'tokens') {
    // hub tokens [list] | revoke <kid> | revoke-all   — OAuth-issued device tokens
    const store = new IssuedTokenStore()
    const action = args[1] ?? 'list'
    if (action === 'list') {
      const rows = store.list()
      if (rows.length === 0) console.log('No OAuth-issued tokens.')
      for (const r of rows) {
        console.log(
          `  ${r.kid}  ${r.name} (${r.sub})  issued ${new Date(r.issuedAt).toISOString()}  expires ${new Date(r.expiresAt).toISOString()}`,
        )
      }
      return
    }
    if (action === 'revoke' && args[2]) {
      console.log(store.revoke(args[2]) ? `Revoked ${args[2]}` : `No token with kid ${args[2]}`)
      return
    }
    if (action === 'revoke-all') {
      console.log(`Revoked ${store.revokeAll()} token(s).`)
      return
    }
    console.log('Usage: hub tokens [list | revoke <kid> | revoke-all]')
    process.exit(1)
  }

  if (sub === 'slave-token') {
    const tls = loadOrCreateHubTls({ commonName: `lework-${cfg.hubId}` })
    console.log('On the slave machine run:')
    console.log(
      `  LEWORK_JOIN_TOKEN=${cfg.slaveJoinToken} hub join wss://<this-host>:${cfg.port}/v1/slave-ws --fingerprint ${tls.fingerprint}`,
    )
    return
  }

  if (sub === 'service-config') {
    const role: 'master' | 'slave' = cfg.role === 'master' ? 'master' : 'slave'
    const desc = generateServiceDescriptor({ role, repoRoot })
    console.log(`# Target path (${desc.platform}): ${desc.pathHint}\n`)
    console.log(desc.content)
    return
  }

  if (sub === 'join') {
    const masterUrl = args[1]
    const fpIdx = args.indexOf('--fingerprint')
    const expectedFp = fpIdx >= 0 ? (args[fpIdx + 1] ?? '').toLowerCase().replace(/[^0-9a-f]/g, '') : ''
    const token = process.env.LEWORK_JOIN_TOKEN ?? ''
    if (!masterUrl || !masterUrl.startsWith('wss://') || expectedFp.length !== 64 || !token) {
      console.error(
        'Usage: LEWORK_JOIN_TOKEN=<join-token> hub join <wss://master-host:64450/v1/slave-ws> --fingerprint <sha256>\n' +
          '       (run `hub slave-token` on the master to get both values)',
      )
      process.exit(1)
    }
    const server = new HubServer({ config: cfg })
    const status = await server.buildStatus()
    const s0 = status.slaves[0]!
    const selfSlave = { id: s0.id, name: s0.name, platform: s0.platform, engines: s0.engines }
    // Self-signed master certificate: CA validation is replaced by fingerprint pinning, and the
    // join token is only sent after the pinned fingerprint has been verified.
    const ws = new WebSocket(masterUrl, { rejectUnauthorized: false })
    await new Promise<void>((resolve, reject) => {
      ws.once('upgrade', res => {
        const sock = res.socket as import('node:tls').TLSSocket
        const raw = sock.getPeerCertificate?.()?.raw
        const fp = raw ? crypto.createHash('sha256').update(raw).digest('hex') : ''
        if (fp !== expectedFp) {
          ws.terminate()
          reject(new Error(`Master certificate fingerprint mismatch (got ${fp || 'none'})`))
        }
      })
      ws.once('open', () => {
        ws.send(JSON.stringify({ token, slave: selfSlave }))
      })
      ws.once('message', raw => {
        console.log(`Registered Slave ${selfSlave.id} with Master: ${String(raw)}`)
        resolve()
      })
      ws.once('error', reject)
    })
    ws.close()
    return
  }

  console.log(
    'Usage: hub <serve|status|engines|slaves|pair|token rotate|tokens|slave-token|join|service-config> [--role master|slave] [--port N]',
  )
}

void main()
