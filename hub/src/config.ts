import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { HubRole } from '@lework/protocol'
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
  /** Separate credential that Slaves use to register with this Master (never the admin token). */
  slaveJoinToken?: string
  cpaEndpoint: string
  cpaManagementKey?: string
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
    port: overrides.port ?? stored.port ?? Number(process.env.LEWORK_PORT ?? 64450),
    token: overrides.token ?? stored.token ?? process.env.LEWORK_TOKEN ?? crypto.randomBytes(24).toString('base64url'),
    // $HOME is intentionally not a default root; credential paths are additionally hard-denied in path-guard.
    workspaceRoots: overrides.workspaceRoots ?? stored.workspaceRoots ?? (defaultRoots.length > 0 ? defaultRoots : [os.homedir()]),
    relayUrls: overrides.relayUrls ?? stored.relayUrls ?? envRelays,
    masterUrl: overrides.masterUrl ?? stored.masterUrl,
    slaveJoinToken: overrides.slaveJoinToken ?? stored.slaveJoinToken ?? crypto.randomBytes(24).toString('base64url'),
    cpaEndpoint: overrides.cpaEndpoint ?? stored.cpaEndpoint ?? 'http://127.0.0.1:8317',
    cpaManagementKey:
      overrides.cpaManagementKey ??
      stored.cpaManagementKey ??
      (process.env.LEWORK_CPA_MANAGEMENT_KEY?.trim() || undefined),
  }

  try {
    fs.writeFileSync(cfgPath, JSON.stringify(cfg, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 })
    fs.chmodSync(cfgPath, 0o600)
    fs.chmodSync(dir, 0o700)
  } catch {
    // ignore if read-only in test env
  }
  return cfg
}
