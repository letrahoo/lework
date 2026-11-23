import { spawnSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { HubRole } from '@lework/protocol'
import type { NotificationConfig } from './notifications/notifier.ts'
import { defaultDataDir, detectPlatform } from './platform/index.ts'

function isSerialLikeHostname(raw: string): boolean {
  const candidate = raw.trim().replace(/\.local$/i, '')
  return /^[A-Z0-9]{8,16}$/.test(candidate) && /[A-Z]/.test(candidate) && /[0-9]/.test(candidate)
}

function mapMacHardwareModel(rawModel?: string): string | undefined {
  const model = rawModel?.trim() ?? ''
  if (!model) return undefined
  const lower = model.toLowerCase()
  if (lower.startsWith('macbookpro')) return 'MacBook Pro'
  if (lower.startsWith('macbookair')) return 'MacBook Air'
  if (lower.startsWith('macstudio')) return 'Mac Studio'
  if (lower.startsWith('macmini')) return 'Mac mini'
  if (lower.startsWith('imac')) return 'iMac'
  if (lower.startsWith('macpro')) return 'Mac Pro'
  return undefined
}

function readDarwinCommand(bin: string, args: string[]): string | undefined {
  try {
    const res = spawnSync(bin, args, { encoding: 'utf8', timeout: 1_500 })
    if (res.status === 0) {
      const out = (res.stdout ?? '').trim()
      return out || undefined
    }
  } catch {
    // ignore command errors on restricted environments
  }
  return undefined
}

/**
 * Resolves a human-friendly default Hub name when creating a fresh config.
 * Preserves readable hostnames while replacing serial-number-like macOS hostnames
 * (e.g. MDM-assigned `AB12CD34EF`) with ComputerName or hardware model label.
 */
export function resolveDefaultHostName(opts?: {
  hostname?: string
  platform?: NodeJS.Platform
  computerName?: string
  hwModel?: string
}): string {
  const rawHost = (opts?.hostname !== undefined ? opts.hostname : os.hostname()).trim()
  const platform = opts?.platform ?? process.platform

  if (rawHost && !isSerialLikeHostname(rawHost)) {
    return rawHost
  }

  if (platform === 'darwin') {
    const shouldProbeSystem =
      process.platform === 'darwin' && opts?.computerName === undefined && opts?.hwModel === undefined

    const computerName = (
      opts?.computerName !== undefined
        ? opts.computerName
        : shouldProbeSystem
          ? readDarwinCommand('/usr/sbin/scutil', ['--get', 'ComputerName']) ?? ''
          : ''
    ).trim()
    if (computerName && !isSerialLikeHostname(computerName)) {
      return computerName
    }

    const hwModel = (
      opts?.hwModel !== undefined
        ? opts.hwModel
        : shouldProbeSystem
          ? readDarwinCommand('/usr/sbin/sysctl', ['-n', 'hw.model']) ?? ''
          : ''
    ).trim()
    const mappedModel = mapMacHardwareModel(hwModel)
    if (mappedModel) {
      return mappedModel
    }

    return 'My Mac'
  }

  return rawHost || 'Lework Hub'
}

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
  /** Optional embedded Tailscale tsnet mesh configuration. */
  tsnetEnabled?: boolean
  tsnetControlUrl?: string
  /** Optional pluggable offline push / webhook notification configuration. */
  notifications?: NotificationConfig
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
  const home = os.homedir()
  const defaultRoots = [
    path.join(home, 'Projects'),
    path.join(home, 'Developer'),
    path.join(home, 'code'),
    path.join(home, 'my-projects'),
    path.join(home, 'personal'),
    path.join(home, 'work'),
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

  const fallbackRoot = process.cwd() !== home ? process.cwd() : path.join(home, 'Projects')
  const cfg: HubConfig = {
    hubId: overrides.hubId ?? stored.hubId ?? `hub_${crypto.randomBytes(6).toString('hex')}`,
    name: overrides.name ?? stored.name ?? resolveDefaultHostName(),
    role: overrides.role ?? stored.role ?? (platform === 'linux' ? 'master' : 'slave'),
    host: overrides.host ?? stored.host ?? '0.0.0.0',
    port: overrides.port ?? stored.port ?? Number(process.env.LEWORK_PORT ?? 64450),
    token: overrides.token ?? stored.token ?? process.env.LEWORK_TOKEN ?? crypto.randomBytes(24).toString('base64url'),
    // $HOME is never used as a default root; credential paths are additionally hard-denied in path-guard.
    workspaceRoots:
      overrides.workspaceRoots ??
      stored.workspaceRoots ??
      (defaultRoots.length > 0 ? defaultRoots : [fallbackRoot]),
    relayUrls: overrides.relayUrls ?? stored.relayUrls ?? envRelays,
    masterUrl: overrides.masterUrl ?? stored.masterUrl,
    slaveJoinToken: overrides.slaveJoinToken ?? stored.slaveJoinToken ?? crypto.randomBytes(24).toString('base64url'),
    tsnetEnabled: overrides.tsnetEnabled ?? stored.tsnetEnabled,
    tsnetControlUrl: overrides.tsnetControlUrl ?? stored.tsnetControlUrl ?? (process.env.LEWORK_TSNET_CONTROL_URL?.trim() || undefined),
    notifications: overrides.notifications ?? stored.notifications,
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
