import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { ProfileInfo, ProviderInfo } from '@lework/protocol'
import { defaultDataDir } from '../platform/index.ts'

const LOCAL_CPA_CONFIG_PATH =
  process.env.LEWORK_CPA_CONFIG ?? path.join(os.homedir(), 'personal', 'local-reverse-proxy', 'config.yaml')

export class ModelRegistry {
  private cachedCpaKey: string | null = null
  private cachedCpaModels: string[] = []
  private readonly profilesPath: string
  private profiles: ProfileInfo[] = []

  constructor(
    private readonly hubId: string,
    private readonly cpaEndpoint: string,
    defaultCwd?: string,
  ) {
    const dir = defaultDataDir()
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
    this.profilesPath = path.join(dir, 'profiles.json')
    this.profiles = this.loadOrInitProfiles(defaultCwd)
  }

  getCpaApiKey(): string | undefined {
    if (process.env.LEWORK_CPA_KEY) return process.env.LEWORK_CPA_KEY
    if (this.cachedCpaKey) return this.cachedCpaKey
    try {
      if (fs.existsSync(LOCAL_CPA_CONFIG_PATH)) {
        const raw = fs.readFileSync(LOCAL_CPA_CONFIG_PATH, 'utf8')
        const match = raw.match(/api-keys:\s*\n\s*-\s*["']?([^"'\s\n]+)["']?/m)
        if (match?.[1]) {
          this.cachedCpaKey = match[1]
          return this.cachedCpaKey
        }
      }
    } catch {
      // ignore
    }
    return undefined
  }

  async probeProviders(installedEngines: Set<string>): Promise<ProviderInfo[]> {
    const cpaResult = await this.fetchCpaModels()
    return [
      {
        id: 'cpa_local',
        name: `CLIProxyAPI (${this.cpaEndpoint.replace(/^https?:\/\//, '')})`,
        type: 'endpoint',
        baseUrl: this.cpaEndpoint,
        wireApi: 'anthropic-messages',
        healthy: cpaResult.healthy,
        models: cpaResult.models,
      },
      {
        id: 'sub_claude',
        name: 'Claude Code 内置订阅',
        type: 'subscription',
        healthy: installedEngines.has('claude'),
        models: ['claude-sonnet-4-5', 'claude-opus-4-1'],
      },
      {
        id: 'sub_codex',
        name: 'OpenAI Codex 内置订阅',
        type: 'subscription',
        healthy: installedEngines.has('codex'),
        models: ['gpt-5-codex', 'o3'],
      },
      {
        id: 'sub_agy',
        name: 'Google Antigravity 官方订阅 (禁止反代)',
        type: 'subscription',
        healthy: installedEngines.has('agy'),
        models: ['gemini-2.5-pro', 'claude-sonnet-4-5'],
      },
      {
        id: 'sub_dsh',
        name: 'DeepSeek Harness 本机配置',
        type: 'subscription',
        healthy: installedEngines.has('dsh'),
        models: ['deepseek-chat', 'deepseek-reasoner'],
      },
    ]
  }

  getCachedCpaModels(): string[] {
    return this.cachedCpaModels
  }

  listProfiles(): ProfileInfo[] {
    return [...this.profiles]
  }

  upsertProfile(profile: ProfileInfo): ProfileInfo[] {
    const idx = this.profiles.findIndex(p => p.id === profile.id)
    if (idx >= 0) {
      this.profiles[idx] = profile
    } else {
      if (this.profiles.length >= 64) {
        throw new Error('Too many profiles (max 64)')
      }
      this.profiles.push(profile)
    }
    try {
      fs.writeFileSync(this.profilesPath, JSON.stringify(this.profiles, null, 2) + '\n', {
        encoding: 'utf8',
        mode: 0o600,
      })
      fs.chmodSync(this.profilesPath, 0o600)
    } catch {
      // ignore
    }
    return this.listProfiles()
  }

  /**
   * Computes child process environment variables for a given (engineId, providerId, modelId).
   * Enforces compliance guardrails (§4.3): AGY never receives proxy endpoint overrides.
   */
  resolveSessionEnv(
    engineId: string,
    providerId?: string,
    modelId?: string,
  ): NodeJS.ProcessEnv {
    if (engineId === 'agy') {
      if (providerId === 'cpa_local') {
        throw new Error('Compliance guardrail: AGY does not allow API proxy endpoints; use official subscription.')
      }
      return {}
    }

    if (providerId === 'cpa_local') {
      const apiKey = this.getCpaApiKey() ?? 'cpa-local-key'
      const chosenModel = modelId ?? this.cachedCpaModels[0]
      if (engineId === 'claude') {
        return {
          ANTHROPIC_BASE_URL: this.cpaEndpoint,
          ANTHROPIC_API_KEY: apiKey,
          ...(chosenModel ? { ANTHROPIC_MODEL: chosenModel } : {}),
        }
      }
      if (engineId === 'codex') {
        return {
          OPENAI_BASE_URL: `${this.cpaEndpoint}/v1`,
          OPENAI_API_KEY: apiKey,
        }
      }
    }
    return {}
  }

  private async fetchCpaModels(): Promise<{ healthy: boolean; models: string[] }> {
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), 2000)
    timer.unref?.()
    try {
      const headers: Record<string, string> = {}
      const key = this.getCpaApiKey()
      if (key) headers.Authorization = `Bearer ${key}`
      const res = await fetch(`${this.cpaEndpoint}/v1/models`, {
        headers,
        signal: ctl.signal,
      })
      clearTimeout(timer)
      if (res.ok) {
        const body = (await res.json()) as { data?: Array<{ id?: string }> }
        const models = (body.data ?? [])
          .map(item => (typeof item?.id === 'string' ? item.id : ''))
          .filter(Boolean)
          .slice(0, 40)
        if (models.length > 0) {
          this.cachedCpaModels = models
        }
        return { healthy: true, models: this.cachedCpaModels }
      }
      return { healthy: res.status === 401 || res.status === 403, models: this.cachedCpaModels }
    } catch {
      clearTimeout(timer)
      return { healthy: false, models: this.cachedCpaModels }
    }
  }

  private loadOrInitProfiles(defaultCwd?: string): ProfileInfo[] {
    const normalizeMode = (raw: unknown): ProfileInfo['mode'] => {
      switch (raw) {
        case 'auto':
          return 'auto'
        case 'acceptEdits':
        case 'auto-edit':
          return 'acceptEdits'
        case 'plan':
          return 'plan'
        case 'bypassPermissions':
        case 'yolo':
          return 'bypassPermissions'
        default:
          return 'default'
      }
    }
    if (fs.existsSync(this.profilesPath)) {
      try {
        const parsed = JSON.parse(fs.readFileSync(this.profilesPath, 'utf8')) as ProfileInfo[]
        if (Array.isArray(parsed) && parsed.length > 0) {
          return parsed.map(p => ({ ...p, mode: normalizeMode(p.mode) }))
        }
      } catch {
        // fallback to defaults
      }
    }
    const defaults: ProfileInfo[] = [
      {
        id: 'prof_claude_sub',
        name: 'Claude Code · 官方订阅',
        slaveId: this.hubId,
        engineId: 'claude',
        providerId: 'sub_claude',
        mode: 'auto',
        defaultCwd,
      },
      {
        id: 'prof_claude_cpa',
        name: 'Claude Code · CLIProxyAPI (:8317)',
        slaveId: this.hubId,
        engineId: 'claude',
        providerId: 'cpa_local',
        mode: 'auto',
        defaultCwd,
      },
      {
        id: 'prof_pi_cpa',
        name: 'Pi Engine (自建) · CLIProxyAPI',
        slaveId: this.hubId,
        engineId: 'pi',
        providerId: 'cpa_local',
        mode: 'default',
        defaultCwd,
      },
      {
        id: 'prof_codex_sub',
        name: 'OpenAI Codex · App Server',
        slaveId: this.hubId,
        engineId: 'codex',
        providerId: 'sub_codex',
        mode: 'default',
        defaultCwd,
      },
      {
        id: 'prof_agy_sub',
        name: 'Antigravity (AGY) · 官方登录',
        slaveId: this.hubId,
        engineId: 'agy',
        providerId: 'sub_agy',
        mode: 'auto',
        defaultCwd,
      },
      {
        id: 'prof_dsh_local',
        name: 'DeepSeek Harness · 本机通道',
        slaveId: this.hubId,
        engineId: 'dsh',
        providerId: 'sub_dsh',
        mode: 'default',
        defaultCwd,
      },
    ]
    return defaults
  }
}
