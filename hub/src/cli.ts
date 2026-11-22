import crypto from 'node:crypto'
import fs from 'node:fs'
import https from 'node:https'
import os from 'node:os'
import path from 'node:path'
import type { TLSSocket } from 'node:tls'
import { fileURLToPath } from 'node:url'
import qrcode from 'qrcode-terminal'
import { WebSocket } from 'ws'
import type { HubRole } from '@lework/protocol'
import { collectLanIpv4Addresses, collectTailscaleIpv4Addresses } from './bonjour.ts'
import { loadOrCreateConfig } from './config.ts'
import { encodePairingDeepLink, type QrPairingPayload } from './auth/pair-manager.ts'
import { IssuedTokenStore } from './auth/token-store.ts'
import { TsnetManager, type TsnetStatusSnapshot } from './auth/tsnet-manager.ts'
import { NotificationDispatcher, type NotificationConfig } from './notifications/notifier.ts'
import { defaultDataDir, ensureUserCliLinks, generateServiceDescriptor, listLanAddresses } from './platform/index.ts'
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

function parseStringFlag(args: string[], flag: string): string | undefined {
  const idx = args.indexOf(flag)
  if (idx >= 0 && args[idx + 1]) {
    return args[idx + 1]!.trim() || undefined
  }
  return undefined
}

function callLocalHubAdmin<T>(opts: {
  port: number
  expectedFp: string
  token: string
  method: 'GET' | 'POST'
  pathname: string
  body?: unknown
}): Promise<T | null> {
  return new Promise(resolve => {
    const req = https.request(
      {
        host: '127.0.0.1',
        port: opts.port,
        method: opts.method,
        path: opts.pathname,
        rejectUnauthorized: false,
        timeout: 4_000,
        headers: {
          authorization: `Bearer ${opts.token}`,
          ...(opts.body !== undefined ? { 'content-type': 'application/json' } : {}),
        },
      },
      res => {
        const sock = res.socket as TLSSocket
        const raw = sock.getPeerCertificate?.()?.raw
        const fp = raw ? crypto.createHash('sha256').update(raw).digest('hex') : ''
        if (fp !== opts.expectedFp || (res.statusCode ?? 500) >= 400) {
          res.resume()
          resolve(null)
          return
        }
        let buf = ''
        res.setEncoding('utf8')
        res.on('data', c => (buf += c))
        res.on('end', () => {
          try {
            resolve(JSON.parse(buf) as T)
          } catch {
            resolve(null)
          }
        })
      },
    )
    req.on('error', () => resolve(null))
    req.on('timeout', () => {
      req.destroy()
      resolve(null)
    })
    if (opts.body !== undefined) req.write(JSON.stringify(opts.body))
    req.end()
  })
}

function updateStoredConfig(patch: Record<string, unknown>): void {
  const cfgPath = path.join(defaultDataDir(), 'config.json')
  let stored: Record<string, unknown> = {}
  try {
    stored = JSON.parse(fs.readFileSync(cfgPath, 'utf8')) as Record<string, unknown>
  } catch {
    stored = {}
  }
  Object.assign(stored, patch)
  fs.mkdirSync(path.dirname(cfgPath), { recursive: true, mode: 0o700 })
  fs.writeFileSync(cfgPath, JSON.stringify(stored, null, 2) + '\n', { mode: 0o600 })
  fs.chmodSync(cfgPath, 0o600)
}

async function main(): Promise<void> {
  const args = process.argv.slice(2)
  const sub = args[0] ?? 'status'

  if (sub === 'install-cli') {
    const linked = ensureUserCliLinks(repoRoot)
    if (linked.length > 0) {
      console.log('Installed global CLI commands:')
      for (const p of linked) console.log(`  - ${p}`)
    } else {
      console.log('No CLI links updated (check permissions on ~/.local/bin).')
    }
    return
  }

  const roleOverride = parseRoleFlag(args)
  const portOverride = parsePortFlag(args)
  const cfg = loadOrCreateConfig({
    ...(roleOverride ? { role: roleOverride } : {}),
    ...(portOverride ? { port: portOverride } : {}),
  })

  if (sub === 'serve') {
    if (!process.env.VITEST && !process.env.LEWORK_HOME) {
      ensureUserCliLinks(repoRoot)
    }
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
    const tls = loadOrCreateHubTls({ commonName: `lework-${cfg.hubId}` })
    const liveTicket = await callLocalHubAdmin<{
      pairCode: string
      expiresInSec: number
      endpoints: string[]
      fingerprint: string
      hub: { id: string; name: string; role: string }
    }>({
      port: cfg.port,
      expectedFp: tls.fingerprint,
      token: cfg.token,
      method: 'POST',
      pathname: '/v1/auth/pair/create',
    })

    const fallbackUrls: string[] = []
    for (const ip of collectLanIpv4Addresses()) fallbackUrls.push(`https://${ip}:${cfg.port}`)
    const cleanHost = (cfg.name || os.hostname())
      .replace(/\.local\.?$/i, '')
      .replace(/[^A-Za-z0-9_-]/g, '-')
      .replace(/-+/g, '-')
      .replace(/^-|-$/g, '')
    if (cleanHost) fallbackUrls.push(`https://${cleanHost}.local:${cfg.port}`)
    for (const tsIp of collectTailscaleIpv4Addresses()) fallbackUrls.push(`https://${tsIp}:${cfg.port}`)
    for (const r of cfg.relayUrls ?? []) fallbackUrls.push(r.replace(/^http:\/\//i, 'https://'))
    if (fallbackUrls.length === 0) fallbackUrls.push(`https://127.0.0.1:${cfg.port}`)

    const endpoints = liveTicket?.endpoints?.length ? liveTicket.endpoints : fallbackUrls
    const primaryUrl = endpoints[0] ?? `https://127.0.0.1:${cfg.port}`
    const payload: QrPairingPayload = {
      v: 2,
      hubId: cfg.hubId,
      name: cfg.name,
      role: cfg.role,
      urls: endpoints,
      url: primaryUrl,
      fp: tls.fingerprint,
      ...(liveTicket ? { pairCode: liveTicket.pairCode } : { token: cfg.token }),
    }
    const deepLink = encodePairingDeepLink(payload)

    console.log('Scan with Lework App (or iOS Camera) to pair this host:')
    for (const ep of endpoints) {
      console.log(`  Endpoint    : ${ep}`)
    }
    if (liveTicket) {
      console.log(`  Pair Code   : ${liveTicket.pairCode}  (single-use, expires in ${liveTicket.expiresInSec}s)`)
    } else {
      console.log(`  Token       : ${cfg.token}  (hubd offline — static fallback token)`)
    }
    console.log(`  Fingerprint : ${shortFingerprint(tls.fingerprint)}  (App 「主机指纹」应与此一致)\n`)
    qrcode.generate(deepLink, { small: true })
    console.log(`\nDeep Link: ${deepLink}`)
    return
  }

  if (sub === 'tsnet') {
    const action = args[1] ?? 'status'
    const controlUrl = parseStringFlag(args, '--control-url')
    const authKey = parseStringFlag(args, '--auth-key')
    const tls = loadOrCreateHubTls({ commonName: `lework-${cfg.hubId}` })

    const printStatus = (st: TsnetStatusSnapshot) => {
      console.log(`Tailscale Mesh (${st.mode}):`)
      console.log(`  State      : ${st.backendState}`)
      if (st.loginName) console.log(`  Account    : ${st.displayName ? `${st.displayName} <${st.loginName}>` : st.loginName}`)
      if (st.selfHostName) console.log(`  Node       : ${st.selfHostName}${st.selfDnsName ? ` (${st.selfDnsName})` : ''}`)
      if (st.tailscaleIps.length > 0) console.log(`  Mesh IPs   : ${st.tailscaleIps.join(', ')}`)
      if (st.controlUrl) console.log(`  Control    : ${st.controlUrl}`)
      if (st.authUrl) console.log(`  Login URL  : ${st.authUrl}`)
      if (st.error) console.log(`  Error      : ${st.error}`)
      if (st.peers.length > 0) {
        console.log('  Same-Account Peers:')
        for (const p of st.peers) {
          console.log(`    - [${p.online ? 'ONLINE ' : 'OFFLINE'}] ${p.hostName} (${p.tailscaleIp})`)
        }
      }
    }

    if (action === 'status') {
      const remote = await callLocalHubAdmin<TsnetStatusSnapshot>({
        port: cfg.port,
        expectedFp: tls.fingerprint,
        token: cfg.token,
        method: 'GET',
        pathname: '/v1/tsnet/status',
      })
      if (remote) {
        printStatus(remote)
        return
      }
      const mgr = new TsnetManager({
        hubName: cfg.name,
        hubPort: cfg.port,
        controlUrl: controlUrl ?? cfg.tsnetControlUrl,
        autoStart: false,
      })
      const st = await mgr.getStatus()
      await mgr.stop()
      printStatus(st)
      return
    }

    if (action === 'login') {
      updateStoredConfig({
        tsnetEnabled: true,
        ...(controlUrl ? { tsnetControlUrl: controlUrl } : {}),
      })
      const remote = await callLocalHubAdmin<TsnetStatusSnapshot>({
        port: cfg.port,
        expectedFp: tls.fingerprint,
        token: cfg.token,
        method: 'POST',
        pathname: '/v1/tsnet/login',
        body: { controlUrl, authKey },
      })
      if (remote) {
        printStatus(remote)
        if (remote.authUrl && remote.backendState !== 'Running') {
          console.log('\nOpen the URL above (or scan QR) with the SAME Tailscale account used on your phone:\n')
          qrcode.generate(remote.authUrl, { small: true })
          console.log('\nWaiting for Tailscale login confirmation (Ctrl+C to exit)...')
          for (let i = 0; i < 90; i++) {
            await new Promise(r => setTimeout(r, 2_000))
            const cur = await callLocalHubAdmin<TsnetStatusSnapshot>({
              port: cfg.port,
              expectedFp: tls.fingerprint,
              token: cfg.token,
              method: 'GET',
              pathname: '/v1/tsnet/status',
            })
            if (cur?.backendState === 'Running') {
              console.log('\nTailscale same-account mesh is ONLINE!\n')
              printStatus(cur)
              return
            }
          }
        }
        return
      }

      const mgr = new TsnetManager({
        hubName: cfg.name,
        hubPort: cfg.port,
        controlUrl: controlUrl ?? cfg.tsnetControlUrl,
        authKey,
        autoStart: true,
      })
      const st = await mgr.startLogin({ controlUrl, authKey })
      printStatus(st)
      if (st.authUrl && st.backendState !== 'Running') {
        console.log('\nOpen the URL above (or scan QR) with the SAME Tailscale account used on your phone:\n')
        qrcode.generate(st.authUrl, { small: true })
        console.log('\nWaiting for Tailscale login confirmation (Ctrl+C to exit)...')
        for (let i = 0; i < 90; i++) {
          await new Promise(r => setTimeout(r, 2_000))
          const cur = await mgr.getStatus()
          if (cur.backendState === 'Running') {
            console.log('\nTailscale same-account mesh is ONLINE!\n')
            printStatus(cur)
            await mgr.stop()
            return
          }
        }
      }
      await mgr.stop()
      return
    }

    if (action === 'logout') {
      updateStoredConfig({ tsnetEnabled: false })
      const remote = await callLocalHubAdmin<TsnetStatusSnapshot>({
        port: cfg.port,
        expectedFp: tls.fingerprint,
        token: cfg.token,
        method: 'POST',
        pathname: '/v1/tsnet/logout',
      })
      if (remote) {
        printStatus(remote)
        return
      }
      const mgr = new TsnetManager({ hubName: cfg.name, hubPort: cfg.port, autoStart: false })
      const st = await mgr.logout()
      await mgr.stop()
      printStatus(st)
      return
    }

    console.log('Usage: hub tsnet <status|login|logout> [--control-url <url>] [--auth-key <key>]')
    process.exit(1)
  }

  if (sub === 'notify') {
    const action = args[1] ?? 'status'
    const secret = parseStringFlag(args, '--secret')
    const tls = loadOrCreateHubTls({ commonName: `lework-${cfg.hubId}` })

    if (action === 'status') {
      const n = cfg.notifications
      if (!n?.enabled) {
        console.log('Offline Notifications: DISABLED')
        console.log('  Enable Bark   : lework notify bark https://api.day.app/<YOUR_KEY>')
        console.log('  Enable Feishu : lework notify feishu https://open.feishu.cn/open-apis/bot/v2/hook/<ID> [--secret <SEC>]')
        console.log('  Enable Webhook: lework notify webhook https://example.com/hook [--secret <SEC>]')
        return
      }
      console.log(`Offline Notifications: ENABLED (${n.provider})`)
      if (n.url) console.log(`  Endpoint          : ${n.url}`)
      if (n.telegramChatId) console.log(`  Telegram Chat ID  : ${n.telegramChatId}`)
      console.log(`  Approval Grace    : ${n.approvalDelayMs ?? 12_000}ms (1500ms when phone offline)`)
      console.log(`  Min Turn Duration : ${n.minTurnDurationMs ?? 15_000}ms`)
      return
    }

    if (action === 'bark' || action === 'feishu' || action === 'webhook') {
      const targetUrl = args[2]?.trim()
      if (!targetUrl || !/^https?:\/\//i.test(targetUrl)) {
        console.error(`Usage: lework notify ${action} <https://...> [--secret <secret>]`)
        process.exit(1)
      }
      const nextCfg: NotificationConfig = {
        enabled: true,
        provider: action,
        url: targetUrl,
        ...(secret ? { secret } : {}),
      }
      updateStoredConfig({ notifications: nextCfg })
      console.log(`Saved ${action} notification channel. Run 'lework notify test' to verify push delivery.`)
      return
    }

    if (action === 'telegram') {
      const botToken = args[2]?.trim()
      const chatId = args[3]?.trim()
      if (!botToken || !chatId) {
        console.error('Usage: lework notify telegram <botToken> <chatId>')
        process.exit(1)
      }
      const nextCfg: NotificationConfig = {
        enabled: true,
        provider: 'telegram',
        telegramBotToken: botToken,
        telegramChatId: chatId,
      }
      updateStoredConfig({ notifications: nextCfg })
      console.log(`Saved Telegram notification channel. Run 'lework notify test' to verify push delivery.`)
      return
    }

    if (action === 'disable') {
      updateStoredConfig({
        notifications: cfg.notifications ? { ...cfg.notifications, enabled: false } : { enabled: false, provider: 'webhook' },
      })
      console.log('Offline notifications disabled.')
      return
    }

    if (action === 'test') {
      const liveRes = await callLocalHubAdmin<{ ok: boolean; error?: string }>({
        port: cfg.port,
        expectedFp: tls.fingerprint,
        token: cfg.token,
        method: 'POST',
        pathname: '/v1/notify/test',
        body: {},
      })
      const res =
        liveRes ??
        (await new NotificationDispatcher({
          getConfig: () => cfg.notifications,
          getHubInfo: () => ({
            id: cfg.hubId,
            name: cfg.name,
            role: cfg.role,
            platform: 'darwin',
            version: '0.6.4',
          }),
          getSessionMeta: () => undefined,
          getActiveSubscriberCount: () => 0,
        }).sendTestNotification())
      if (res.ok) {
        console.log('Test notification delivered!')
      } else {
        console.error(`Test notification failed: ${res.error ?? 'unknown error'}`)
        process.exit(1)
      }
      return
    }

    console.log('Usage: lework notify <status | bark <url> | feishu <url> [--secret S] | telegram <token> <chatId> | webhook <url> [--secret S] | test | disable>')
    process.exit(1)
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
      if (rows.length === 0) console.log('No issued device tokens.')
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
    'Usage: lework <serve|status|engines|slaves|pair|tsnet|notify|install-cli|token rotate|tokens|slave-token|join|service-config> [--role master|slave] [--port N]',
  )
}

void main()
