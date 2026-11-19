import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { HubRole } from '@qprc/protocol'
import { defaultDataDir, detectPlatform } from './platform/index.ts'

export interface HubConfig {
  hubId: string
  name: string
  role: HubRole
  host: string
  port: number
  token: string
  workspaceRoots: string[]
  relayUrls?: string[]
  masterUrl?: string
  cpaEndpoint: string
}

export function loadOrCreateConfig(overrides: Partial<HubConfig> = {}): HubConfig {
  const dir = defaultDataDir()
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
  const cfgPath = path.join(dir, 'config.json')

  let stored: Partial<HubConfig> = {}
  if (fs.existsSync(cfgPath)) {
    try {
      stored = JSON.parse(fs.readFileSync(cfgPath, 'utf8')) as Partial<HubConfig>
    } catch {
      stored = {}
    }
  }

  const platform = detectPlatform()
  const defaultRoots = [
    path.join(os.homedir(), 'personal'),
    path.join(os.homedir(), 'work'),
    os.homedir(),
  ].filter(p => {
    try {
      return fs.statSync(p).isDirectory()
    } catch {
      return false
    }
  })

  const envRelays = process.env.LEWORK_RELAY_URLS
    ?.split(',')
    .map(s => s.trim())
    .filter(Boolean)

  const cfg: HubConfig = {
    hubId: overrides.hubId ?? stored.hubId ?? `hub_${crypto.randomBytes(6).toString('hex')}`,
    name: overrides.name ?? stored.name ?? os.hostname(),
    role: overrides.role ?? stored.role ?? (platform === 'linux' ? 'master' : 'slave'),
    host: overrides.host ?? stored.host ?? '0.0.0.0',
    port: overrides.port ?? stored.port ?? Number(process.env.QPRC_PORT ?? 64450),
    token: overrides.token ?? stored.token ?? process.env.QPRC_TOKEN ?? crypto.randomBytes(24).toString('base64url'),
    workspaceRoots: overrides.workspaceRoots ?? stored.workspaceRoots ?? defaultRoots,
    relayUrls: overrides.relayUrls ?? stored.relayUrls ?? envRelays,
    masterUrl: overrides.masterUrl ?? stored.masterUrl,
    cpaEndpoint: overrides.cpaEndpoint ?? stored.cpaEndpoint ?? 'http://127.0.0.1:8317',
  }

  try {
    fs.writeFileSync(cfgPath, JSON.stringify(cfg, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 })
  } catch {
    // ignore if read-only in test env
  }
  return cfg
}
