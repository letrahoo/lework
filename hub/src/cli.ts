import { spawn, spawnSync } from 'node:child_process'
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
import { HUB_VERSION } from './version.ts'
import { encodePairingDeepLink, type QrPairingPayload } from './auth/pair-manager.ts'
import { IssuedTokenStore } from './auth/token-store.ts'
import { TsnetManager, type TsnetStatusSnapshot } from './auth/tsnet-manager.ts'
import { NotificationDispatcher, type NotificationConfig } from './notifications/notifier.ts'
import {
  defaultDataDir,
  detectPlatform,
  ensureUserCliLinks,
  generateServiceDescriptor,
  listLanAddresses,
  resolveSshServerAddress,
} from './platform/index.ts'
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
    const explicitEndpoint = parseStringFlag(args, '--endpoint')
    const explicitHost = parseStringFlag(args, '--host')
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

    const prioritizedUrls: string[] = []
    if (explicitEndpoint) {
      prioritizedUrls.push(explicitEndpoint.replace(/^http:\/\//i, 'https://').replace(/\/+$/, ''))
    }
    if (explicitHost) {
      prioritizedUrls.push(`https://${explicitHost.replace(/^https?:\/\//i, '').replace(/\/+$/, '')}:${cfg.port}`)
    }
    const sshIp = resolveSshServerAddress()
    if (sshIp) {
      prioritizedUrls.push(`https://${sshIp}:${cfg.port}`)
    }

    const fallbackUrls: string[] = [...prioritizedUrls]
    for (const ip of collectLanIpv4Addresses()) fallbackUrls.push(`https://${ip}:${cfg.port}`)
    const cleanHost = (cfg.name || os.hostname())
      .replace(/\.local\.?$/i, '')
      .replace(/[^A-Za-z0-9_-]/g, '-')
      .replace(/-+/g, '-')
      .replace(/^-|-$/g, '')
    if (cleanHost && detectPlatform() === 'darwin') {
      fallbackUrls.push(`https://${cleanHost}.local:${cfg.port}`)
    }
    for (const tsIp of collectTailscaleIpv4Addresses()) fallbackUrls.push(`https://${tsIp}:${cfg.port}`)
    for (const r of cfg.relayUrls ?? []) fallbackUrls.push(r.replace(/^http:\/\//i, 'https://'))
    if (fallbackUrls.length === 0) fallbackUrls.push(`https://127.0.0.1:${cfg.port}`)

    const mergedRaw = liveTicket?.endpoints?.length
      ? [...prioritizedUrls, ...liveTicket.endpoints]
      : fallbackUrls
    const endpoints = [...new Set(mergedRaw.filter(Boolean))]
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

    console.log('Scan with Lework Mobile App OR paste Deep Link into Lework Desktop to pair this host:')
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
    console.log(`\nDesktop / CLI Pairing Link (复制粘贴至桌面端「粘贴配对链接连接」):\n  ${deepLink}`)
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
            version: HUB_VERSION,
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

  if (sub === 'config') {
    const action = args[1] ?? 'show'
    if (action === 'show') {
      console.log(`Config File : ${path.join(defaultDataDir(), 'config.json')}`)
      console.log(`  Hub ID    : ${cfg.hubId}`)
      console.log(`  Name      : ${cfg.name}`)
      console.log(`  Role      : ${cfg.role}`)
      console.log(`  Listen    : ${cfg.host}:${cfg.port}`)
      console.log(`  Tailscale : ${cfg.tsnetEnabled ? 'enabled' : 'disabled'}${cfg.tsnetControlUrl ? ` (${cfg.tsnetControlUrl})` : ''}`)
      console.log('  Workspaces:')
      for (const w of cfg.workspaceRoots) {
        console.log(`    - ${w}`)
      }
      if (cfg.relayUrls?.length) {
        console.log('  Relays:')
        for (const r of cfg.relayUrls) console.log(`    - ${r}`)
      }
      return
    }
    if (action === 'set-name') {
      const nextName = args.slice(2).join(' ').trim()
      if (!nextName) {
        console.error('Usage: lework config set-name <display-name>')
        process.exit(1)
      }
      updateStoredConfig({ name: nextName })
      console.log(`Updated host display name to "${nextName}". Restart hubd if running.`)
      return
    }
    if (action === 'add-workspace') {
      const rawDir = args[2]?.trim()
      if (!rawDir) {
        console.error('Usage: lework config add-workspace <directory>')
        process.exit(1)
      }
      const absDir = path.resolve(rawDir)
      if (!fs.existsSync(absDir) || !fs.statSync(absDir).isDirectory()) {
        console.error(`Directory does not exist: ${absDir}`)
        process.exit(1)
      }
      const realDir = fs.realpathSync(absDir)
      const nextRoots = [...new Set([...cfg.workspaceRoots, realDir])]
      updateStoredConfig({ workspaceRoots: nextRoots })
      console.log(`Added workspace root: ${realDir}`)
      return
    }
    if (action === 'remove-workspace') {
      const rawDir = args[2]?.trim()
      if (!rawDir) {
        console.error('Usage: lework config remove-workspace <directory>')
        process.exit(1)
      }
      const absDir = path.resolve(rawDir)
      const realDir = fs.existsSync(absDir) ? fs.realpathSync(absDir) : absDir
      const nextRoots = cfg.workspaceRoots.filter(w => w !== absDir && w !== realDir)
      updateStoredConfig({ workspaceRoots: nextRoots })
      console.log(`Removed workspace root: ${realDir}`)
      return
    }
    if (action === 'add-relay') {
      const rawUrl = args[2]?.trim()
      if (!rawUrl || !/^https?:\/\//i.test(rawUrl)) {
        console.error('Usage: lework config add-relay <https://host:port>')
        process.exit(1)
      }
      const normalized = rawUrl.replace(/^http:\/\//i, 'https://').replace(/\/+$/, '')
      const nextRelays = [...new Set([...(cfg.relayUrls ?? []), normalized])]
      updateStoredConfig({ relayUrls: nextRelays })
      console.log(`Added relay endpoint: ${normalized}`)
      return
    }
    if (action === 'clear-relays') {
      updateStoredConfig({ relayUrls: [] })
      console.log('Cleared custom relay endpoints.')
      return
    }
    console.log(
      'Usage: lework config <show | set-name <name> | add-workspace <dir> | remove-workspace <dir> | add-relay <https://...> | clear-relays>',
    )
    process.exit(1)
  }

  if (sub === 'service') {
    const action = args[1] ?? 'status'
    const role: 'master' | 'slave' | 'standalone' = roleOverride ?? cfg.role
    const desc = generateServiceDescriptor({ role, repoRoot })

    if (desc.platform === 'linux') {
      const unit = desc.unitOrLabel
      const runSysctl = (sysArgs: string[], inherit = true) =>
        spawnSync('systemctl', ['--user', ...sysArgs], {
          stdio: inherit ? 'inherit' : 'pipe',
          encoding: 'utf8',
        })

      if (action === 'install') {
        fs.mkdirSync(path.dirname(desc.pathHint), { recursive: true, mode: 0o755 })
        fs.writeFileSync(desc.pathHint, desc.content, { mode: 0o644 })
        console.log(`Wrote systemd user service: ${desc.pathHint}`)
        runSysctl(['daemon-reload'])
        const en = runSysctl(['enable', '--now', unit])
        if (en.status !== 0) {
          console.error(`Failed to enable/start ${unit}. Check 'systemctl --user status ${unit}'.`)
          process.exit(en.status ?? 1)
        }
        const user = os.userInfo().username
        const linger = spawnSync('loginctl', ['show-user', user, '-p', 'Linger', '--value'], {
          encoding: 'utf8',
        })
        if (linger.stdout?.trim() !== 'yes') {
          const tryEnable = spawnSync('loginctl', ['enable-linger', user], { encoding: 'utf8' })
          if (tryEnable.status === 0) {
            console.log(`Enabled systemd linger for "${user}" (hubd will keep running after SSH logout).`)
          } else {
            console.log(
              `\nTip: To keep hubd running after SSH logout on this Linux Devbox, run:\n  sudo loginctl enable-linger ${user}`,
            )
          }
        }
        console.log(`Lework background service (${unit}) is installed and running.`)
        return
      }
      if (action === 'uninstall') {
        runSysctl(['disable', '--now', unit], false)
        if (fs.existsSync(desc.pathHint)) fs.unlinkSync(desc.pathHint)
        runSysctl(['daemon-reload'], false)
        console.log(`Removed systemd user service: ${unit}`)
        return
      }
      if (action === 'start' || action === 'stop' || action === 'restart' || action === 'status') {
        const extra = action === 'status' ? ['--no-pager'] : []
        const res = runSysctl([action, unit, ...extra])
        process.exit(res.status ?? 0)
      }
      if (action === 'logs') {
        const follow = !args.includes('--no-follow')
        const jArgs = ['--user', '-u', unit, '-n', '80', ...(follow ? ['-f'] : ['--no-pager'])]
        const child = spawn('journalctl', jArgs, { stdio: 'inherit' })
        await new Promise<void>(resolve => child.on('exit', () => resolve()))
        return
      }
    } else if (desc.platform === 'darwin') {
      const label = desc.unitOrLabel
      const uid = typeof process.getuid === 'function' ? process.getuid() : 501
      const domain = `gui/${uid}`

      if (action === 'install') {
        fs.mkdirSync(path.dirname(desc.pathHint), { recursive: true, mode: 0o755 })
        fs.mkdirSync(path.join(os.homedir(), 'Library', 'Logs'), { recursive: true, mode: 0o755 })
        fs.writeFileSync(desc.pathHint, desc.content, { mode: 0o644 })
        spawnSync('launchctl', ['bootout', `${domain}/${label}`], { stdio: 'ignore' })
        const boot = spawnSync('launchctl', ['bootstrap', domain, desc.pathHint], { stdio: 'inherit' })
        if (boot.status !== 0) {
          spawnSync('launchctl', ['load', '-w', desc.pathHint], { stdio: 'inherit' })
        }
        spawnSync('launchctl', ['kickstart', '-k', `${domain}/${label}`], { stdio: 'inherit' })
        console.log(`Installed and started macOS LaunchAgent: ${label}`)
        return
      }
      if (action === 'uninstall') {
        spawnSync('launchctl', ['bootout', `${domain}/${label}`], { stdio: 'ignore' })
        if (fs.existsSync(desc.pathHint)) fs.unlinkSync(desc.pathHint)
        console.log(`Uninstalled macOS LaunchAgent: ${label}`)
        return
      }
      if (action === 'start' || action === 'restart') {
        const res = spawnSync('launchctl', ['kickstart', '-k', `${domain}/${label}`], { stdio: 'inherit' })
        process.exit(res.status ?? 0)
      }
      if (action === 'stop') {
        const res = spawnSync('launchctl', ['kill', 'SIGTERM', `${domain}/${label}`], { stdio: 'inherit' })
        process.exit(res.status ?? 0)
      }
      if (action === 'status') {
        const res = spawnSync('launchctl', ['print', `${domain}/${label}`], { stdio: 'inherit' })
        process.exit(res.status ?? 0)
      }
      if (action === 'logs') {
        const logFile = path.join(os.homedir(), 'Library', 'Logs', `${label}.log`)
        const errFile = path.join(os.homedir(), 'Library', 'Logs', `${label}.err.log`)
        const files = [logFile, errFile].filter(f => fs.existsSync(f))
        if (files.length === 0) {
          console.log(`No log files found yet at ${logFile}`)
          return
        }
        const follow = !args.includes('--no-follow')
        const child = spawn('tail', ['-n', '80', ...(follow ? ['-f'] : []), ...files], { stdio: 'inherit' })
        await new Promise<void>(resolve => child.on('exit', () => resolve()))
        return
      }
    }

    console.log('Usage: lework service <install|uninstall|start|stop|restart|status|logs> [--role master|slave]')
    process.exit(1)
  }

  if (sub === 'service-config') {
    const role: 'master' | 'slave' | 'standalone' = cfg.role
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
    'Usage: lework <serve|service|config|status|engines|slaves|pair|tsnet|notify|install-cli|token rotate|tokens|slave-token|join|service-config> [--role master|slave] [--port N]',
  )
}

void main()
