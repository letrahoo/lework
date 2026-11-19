import path from 'node:path'
import { fileURLToPath } from 'node:url'
import qrcode from 'qrcode-terminal'
import { WebSocket } from 'ws'
import type { HubRole } from '@qprc/protocol'
import { loadOrCreateConfig } from './config.ts'
import { generateServiceDescriptor, listLanAddresses } from './platform/index.ts'
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
    const server = new HubServer({ config: cfg })
    const { port, host } = await server.start()
    const lanIps = listLanAddresses()
    console.log(`lework hubd (${cfg.role}) listening on ${host}:${port}`)
    console.log(`  Hub ID : ${cfg.hubId} (${cfg.name})`)
    console.log(`  Token  : ${cfg.token}`)
    console.log(`  WS URL : ws://127.0.0.1:${port}/v1/ws`)
    for (const ip of lanIps) {
      console.log(`  LAN URL: ws://${ip}:${port}/v1/ws`)
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
    console.log(`  Token    : ${cfg.token}`)
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
    const payload = JSON.stringify({
      v: 1,
      hubId: cfg.hubId,
      name: cfg.name,
      role: cfg.role,
      wsUrl: `ws://${lanIp}:${cfg.port}/v1/ws`,
      token: cfg.token,
    })
    console.log('Scan or paste into the mobile app:')
    console.log(`  URL   : ws://${lanIp}:${cfg.port}/v1/ws`)
    console.log(`  Token : ${cfg.token}\n`)
    qrcode.generate(payload, { small: true })
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
    if (!masterUrl) {
      console.error('Usage: hub join <ws://master-host:64448/v1/slave-ws> [--token <master-token>]')
      process.exit(1)
    }
    const tokenIdx = args.indexOf('--token')
    const token = tokenIdx >= 0 && args[tokenIdx + 1] ? args[tokenIdx + 1]! : cfg.token
    const server = new HubServer({ config: cfg })
    const status = await server.buildStatus()
    const selfSlave = status.slaves[0]!
    const ws = new WebSocket(masterUrl)
    await new Promise<void>((resolve, reject) => {
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

  console.log('Usage: hub <serve|status|engines|slaves|pair|join|service-config> [--role master|slave] [--port N]')
}

void main()
