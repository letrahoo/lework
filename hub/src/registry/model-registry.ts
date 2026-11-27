import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import type {
  EngineInfo,
  EngineStatus,
  ProfileInfo,
  ProviderAdapterKind,
  ProviderConfigInput,
  ProviderInfo,
  WireApi,
} from '@lework/protocol'
import type { ResolvedTitleModelCandidate } from '../storage/session-title.ts'
import { defaultDataDir } from '../platform/index.ts'
import {
  createProviderAdapters,
  defaultEnginesForWireApis,
  isEngineCompatibleWithWireApis,
  isExternalProviderId,
  isLoopbackUrl,
  normalizeBaseUrlForAnthropic,
  normalizeBaseUrlForOpenAi,
  normalizeProviderBaseUrl,
  sanitizeEnabledEngines,
  toPublicProviderInfo,
  type ProviderAdapter,
  type ProviderProbeResult,
  type StoredProviderConfig,
} from './provider-adapters.ts'

export interface ExternalEngineModelEntry {
  id: string
  providerId: string
  providerName: string
}

export interface ResolvedProviderConnection {
  providerId: string
  name: string
  adapter: ProviderAdapterKind
  baseUrl: string
  apiKey?: string
  wireApis: WireApi[]
}

interface PersistedProvidersFile {
  version: 1
  providers: StoredProviderConfig[]
}

const DEFAULT_CPA_WIRE_APIS: WireApi[] = [
  'anthropic-messages',
  'openai-responses',
  'openai-chat',
]

function sanitizeProviderId(rawId: string | undefined, name: string, existingIds: Set<string>): string {
  const explicit = rawId?.trim()
  if (explicit) {
    if (explicit.startsWith('sub_')) {
      throw new Error('Provider ID 不能以 sub_ 开头（保留给原生引擎订阅）')
    }
    if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/.test(explicit)) {
      throw new Error('Provider ID 仅支持字母、数字、点、下划线与连字符')
    }
    return explicit
  }
  const slug = name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 24)
  const base = slug ? `prov_${slug}` : 'prov_custom'
  if (!existingIds.has(base)) return base
  return `${base}_${crypto.randomBytes(2).toString('hex')}`
}

function normalizeModelList(raw?: string[], max = 80): string[] | undefined {
  if (!raw || raw.length === 0) return undefined
  const seen = new Set<string>()
  const out: string[] = []
  for (const item of raw) {
    const clean = item.trim()
    if (!clean || seen.has(clean)) continue
    seen.add(clean)
    out.push(clean)
  }
  return out.length > 0 ? out.slice(0, max) : undefined
}

export class ModelRegistry {
  private readonly profilesPath: string
  private readonly providersPath: string
  private readonly adapters: Record<ProviderAdapterKind, ProviderAdapter>
  private profiles: ProfileInfo[] = []
  private providers: StoredProviderConfig[] = []
  private readonly probeStates = new Map<string, ProviderProbeResult>()

  constructor(
    private readonly hubId: string,
    private readonly cpaEndpoint: string,
    defaultCwd?: string,
  ) {
    const dir = defaultDataDir()
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
    this.profilesPath = path.join(dir, 'profiles.json')
    this.providersPath = path.join(dir, 'providers.json')
    this.adapters = createProviderAdapters()
    this.providers = this.loadOrInitProviders()
    this.profiles = this.loadOrInitProfiles(defaultCwd)
  }

  private createDefaultCpaProvider(): StoredProviderConfig {
    let baseUrl = this.cpaEndpoint.trim() || 'http://127.0.0.1:8317'
    try {
      baseUrl = normalizeProviderBaseUrl(baseUrl)
    } catch {
      baseUrl = 'http://127.0.0.1:8317'
    }
    return {
      id: 'cpa_local',
      name: 'CPA',
      adapter: 'cpa',
      baseUrl,
      wireApis: [...DEFAULT_CPA_WIRE_APIS],
      enabledEngines: ['claude', 'codex', 'pi'],
      enabled: true,
      builtIn: true,
    }
  }

  private loadOrInitProviders(): StoredProviderConfig[] {
    if (fs.existsSync(this.providersPath)) {
      try {
        const parsed = JSON.parse(fs.readFileSync(this.providersPath, 'utf8')) as
          | Partial<PersistedProvidersFile>
          | StoredProviderConfig[]
        const rawList = Array.isArray(parsed)
          ? parsed
          : Array.isArray(parsed?.providers)
            ? parsed.providers
            : null
        if (rawList) {
          const valid: StoredProviderConfig[] = []
          const seen = new Set<string>()
          for (const item of rawList) {
            if (!item || typeof item !== 'object') continue
            if (typeof item.id !== 'string' || !item.id.trim() || item.id.startsWith('sub_') || seen.has(item.id)) {
              continue
            }
            if (typeof item.baseUrl !== 'string' || !item.baseUrl.trim()) continue
            const adapter: ProviderAdapterKind = item.adapter === 'cpa' ? 'cpa' : 'generic'
            const wireApis: WireApi[] =
              Array.isArray(item.wireApis) && item.wireApis.length > 0
                ? (item.wireApis.filter((w): w is WireApi =>
                    w === 'openai-chat' || w === 'openai-responses' || w === 'anthropic-messages' || w === 'gemini',
                  ))
                : [...DEFAULT_CPA_WIRE_APIS]
            const safeWireApis: WireApi[] = wireApis.length > 0 ? wireApis : ['openai-chat']
            try {
              const normalizedUrl = normalizeProviderBaseUrl(item.baseUrl)
              seen.add(item.id)
              valid.push({
                id: item.id.trim(),
                name: (typeof item.name === 'string' && item.name.trim()) ? item.name.trim().slice(0, 48) : item.id.trim(),
                adapter,
                baseUrl: normalizedUrl,
                ...(typeof item.apiKey === 'string' && item.apiKey.trim() ? { apiKey: item.apiKey.trim() } : {}),
                ...(typeof item.apiKeyEnv === 'string' && item.apiKeyEnv.trim() ? { apiKeyEnv: item.apiKeyEnv.trim() } : {}),
                wireApis: safeWireApis,
                enabledEngines: sanitizeEnabledEngines(item.enabledEngines, safeWireApis),
                enabled: item.enabled !== false,
                ...(item.builtIn ? { builtIn: true } : {}),
                ...(normalizeModelList(item.manualModels, 80) ? { manualModels: normalizeModelList(item.manualModels, 80) } : {}),
                ...(normalizeModelList(item.selectedModels, 120) ? { selectedModels: normalizeModelList(item.selectedModels, 120) } : {}),
              })
            } catch {
              // ignore malformed persisted entry
            }
          }
          return valid
        }
      } catch {
        // fallback to default seed
      }
    }
    return [this.createDefaultCpaProvider()]
  }

  private persistProviders(): void {
    try {
      const payload: PersistedProvidersFile = {
        version: 1,
        providers: this.providers,
      }
      const tmp = `${this.providersPath}.tmp.${process.pid}`
      fs.writeFileSync(tmp, JSON.stringify(payload, null, 2) + '\n', {
        encoding: 'utf8',
        mode: 0o600,
      })
      fs.chmodSync(tmp, 0o600)
      fs.renameSync(tmp, this.providersPath)
    } catch {
      // ignore in read-only test environments
    }
  }

  private getProviderConfig(providerId: string): StoredProviderConfig | undefined {
    return this.providers.find(p => p.id === providerId)
  }

  private getAdapter(kind: ProviderAdapterKind): ProviderAdapter {
    return this.adapters[kind] ?? this.adapters.generic
  }

  private resolveKeyForConfig(config: StoredProviderConfig): string | undefined {
    return this.getAdapter(config.adapter).resolveApiKey(config)
  }

  getCpaEndpoint(): string {
    const cpa = this.providers.find(p => p.id === 'cpa_local') ?? this.providers.find(p => p.adapter === 'cpa')
    return cpa?.baseUrl ?? this.cpaEndpoint
  }

  getCpaApiKey(): string | undefined {
    const cpa = this.providers.find(p => p.id === 'cpa_local') ?? this.providers.find(p => p.adapter === 'cpa')
    if (cpa) {
      return this.resolveKeyForConfig(cpa)
    }
    return this.adapters.cpa.resolveApiKey(this.createDefaultCpaProvider())
  }

  getCpaStatus(): EngineStatus {
    return this.getExternalEngineStatus('pi')
  }

  private getEffectiveProviderModels(
    provider: StoredProviderConfig,
    probe: ProviderProbeResult | undefined,
  ): string[] {
    if (!probe?.healthy || probe.models.length === 0) return []
    if (!provider.selectedModels || provider.selectedModels.length === 0) {
      return probe.models
    }
    const selectedSet = new Set(provider.selectedModels)
    return probe.models.filter(m => selectedSet.has(m))
  }

  /**
   * Summarizes readiness across all enabled external providers usable by `engineId` (used by Pi Engine).
   * Preserves exact semantics of single-CPA probe:
   * - ready=true when >=1 external provider has available exposed chat models
   * - installed=true, ready=false, authStatus='required' when reachable provider returned 401/403
   * - installed=false, ready=false when no external provider is reachable
   */
  getExternalEngineStatus(engineId: string): EngineStatus {
    const matching = this.providers.filter(
      p => p.enabled && p.enabledEngines.includes(engineId) && isEngineCompatibleWithWireApis(engineId, p.wireApis),
    )
    let anyReady = false
    let anyAuthRequired = false
    for (const p of matching) {
      const state = this.probeStates.get(p.id)
      if (!state) continue
      const effective = this.getEffectiveProviderModels(p, state)
      if (effective.length > 0) {
        anyReady = true
      } else if (state.authStatus === 'required') {
        anyAuthRequired = true
      }
    }
    return {
      installed: anyReady || anyAuthRequired,
      ready: anyReady,
      authStatus: !anyReady && anyAuthRequired ? 'required' : undefined,
    }
  }

  getCachedCpaModels(): string[] {
    const cpa = this.providers.find(p => p.id === 'cpa_local')
    if (!cpa || !cpa.enabled) return []
    const state = this.probeStates.get(cpa.id)
    return [...this.getEffectiveProviderModels(cpa, state)]
  }

  getExternalModelsForEngine(engineId: string): ExternalEngineModelEntry[] {
    if (engineId === 'agy') return []
    const out: ExternalEngineModelEntry[] = []
    for (const provider of this.providers) {
      if (!provider.enabled) continue
      if (!provider.enabledEngines.includes(engineId)) continue
      if (!isEngineCompatibleWithWireApis(engineId, provider.wireApis)) continue
      const exposedModels = this.getEffectiveProviderModels(provider, this.probeStates.get(provider.id))
      for (const modelId of exposedModels) {
        out.push({
          id: modelId,
          providerId: provider.id,
          providerName: provider.name,
        })
      }
    }
    return out
  }

  resolveProviderConnection(providerId?: string): ResolvedProviderConnection | undefined {
    const target = providerId
      ? this.providers.find(p => p.id === providerId && p.enabled)
      : this.providers.find(p => p.enabled && this.probeStates.get(p.id)?.healthy) ??
        this.providers.find(p => p.enabled)
    if (!target) return undefined
    return {
      providerId: target.id,
      name: target.name,
      adapter: target.adapter,
      baseUrl: target.baseUrl,
      apiKey: this.resolveKeyForConfig(target),
      wireApis: [...target.wireApis],
    }
  }

  resolveAutoTitleCandidate(): ResolvedTitleModelCandidate | undefined {
    const scoreModel = (modelId: string): number => {
      const lower = modelId.toLowerCase()
      if (
        /(?:flash-lite|haiku|gpt-4o-mini|gpt-4\.1-mini|gpt-5-mini|nano|mini|flash|deepseek-chat|qwen[-_a-z0-9]*turbo|glm-4-flash)/i.test(
          lower,
        )
      ) {
        return 0
      }
      if (/(?:opus|thinking|reasoner|\bo1\b|\bo3\b|\br1\b|pro-max)/i.test(lower)) {
        return 2
      }
      return 1
    }

    let best: { score: number; candidate: ResolvedTitleModelCandidate } | undefined
    for (const provider of this.providers) {
      if (!provider.enabled) continue
      const supportsTextApi = provider.wireApis.some(
        api => api === 'openai-chat' || api === 'openai-responses' || api === 'anthropic-messages',
      )
      if (!supportsTextApi) continue
      const models = this.getEffectiveProviderModels(provider, this.probeStates.get(provider.id))
      if (models.length === 0) continue
      const apiKey = this.resolveKeyForConfig(provider)
      for (const modelId of models) {
        const s = scoreModel(modelId)
        if (!best || s < best.score) {
          best = {
            score: s,
            candidate: {
              providerId: provider.id,
              providerName: provider.name,
              baseUrl: provider.baseUrl,
              apiKey,
              wireApis: [...provider.wireApis],
              modelId,
            },
          }
          if (s === 0) return best.candidate
        }
      }
    }
    return best?.candidate
  }

  listProviders(engines: EngineInfo[]): ProviderInfo[] {
    const externalInfos: ProviderInfo[] = this.providers.map(cfg => {
      const hasKey = Boolean(this.resolveKeyForConfig(cfg))
      return toPublicProviderInfo(cfg, this.probeStates.get(cfg.id), hasKey)
    })

    const subInfos: ProviderInfo[] = engines
      .filter(e => e.status.installed && ['claude', 'codex', 'agy', 'dsh'].includes(e.id))
      .map(e => ({
        id: `sub_${e.id}`,
        name: `${e.displayName} · 主机 CLI`,
        type: 'subscription' as const,
        healthy: e.status.authStatus === 'authenticated',
        ...(e.status.authStatus ? { authStatus: e.status.authStatus } : {}),
        models: (e.models ?? []).filter(m => !isExternalProviderId(m.providerId)).map(m => m.id),
      }))

    return [...externalInfos, ...subInfos]
  }

  async refreshProviders(targetProviderId?: string): Promise<void> {
    const targets = targetProviderId
      ? this.providers.filter(p => p.id === targetProviderId)
      : this.providers

    await Promise.all(
      targets.map(async provider => {
        if (!provider.enabled) {
          this.probeStates.set(provider.id, { healthy: false, models: [] })
          return
        }
        const res = await this.getAdapter(provider.adapter).probe(provider)
        this.probeStates.set(provider.id, res)
      }),
    )
  }

  async refreshCpaModels(): Promise<void> {
    await this.refreshProviders('cpa_local')
  }

  async upsertProvider(input: ProviderConfigInput): Promise<ProviderInfo> {
    const name = input.name.trim()
    if (!name) {
      throw new Error('请填写模型服务名称')
    }
    const baseUrl = normalizeProviderBaseUrl(input.baseUrl)
    const existingById = input.id ? this.getProviderConfig(input.id.trim()) : undefined
    const existingIds = new Set(this.providers.map(p => p.id))
    const id = existingById
      ? existingById.id
      : sanitizeProviderId(input.id, name, existingIds)

    const adapter: ProviderAdapterKind =
      input.adapter ?? existingById?.adapter ?? (id === 'cpa_local' ? 'cpa' : 'generic')

    const wireApis: WireApi[] =
      input.wireApis && input.wireApis.length > 0
        ? [...new Set(input.wireApis)]
        : existingById?.wireApis && existingById.wireApis.length > 0
          ? [...existingById.wireApis]
          : adapter === 'cpa'
            ? [...DEFAULT_CPA_WIRE_APIS]
            : ['openai-chat', 'openai-responses']

    const enabledEngines = sanitizeEnabledEngines(
      input.enabledEngines ?? (existingById ? existingById.enabledEngines : defaultEnginesForWireApis(wireApis)),
      wireApis,
    )

    let apiKey: string | undefined
    if (input.clearApiKey) {
      apiKey = undefined
    } else if (input.apiKey !== undefined) {
      apiKey = input.apiKey.trim() || undefined
    } else {
      apiKey = existingById?.apiKey
    }

    const apiKeyEnv =
      input.apiKeyEnv !== undefined
        ? (input.apiKeyEnv.trim() || undefined)
        : existingById?.apiKeyEnv

    const manualModels =
      input.manualModels !== undefined
        ? normalizeModelList(input.manualModels, 80)
        : existingById?.manualModels

    const selectedModels =
      input.selectedModels !== undefined
        ? normalizeModelList(input.selectedModels, 120)
        : existingById?.selectedModels

    const nextConfig: StoredProviderConfig = {
      id,
      name: name.slice(0, 48),
      adapter,
      baseUrl,
      ...(apiKey ? { apiKey } : {}),
      ...(apiKeyEnv ? { apiKeyEnv } : {}),
      wireApis,
      enabledEngines,
      enabled: input.enabled ?? existingById?.enabled ?? true,
      ...(existingById?.builtIn ? { builtIn: true } : {}),
      ...(manualModels ? { manualModels } : {}),
      ...(selectedModels ? { selectedModels } : {}),
    }

    const idx = this.providers.findIndex(p => p.id === id)
    if (idx >= 0) {
      this.providers[idx] = nextConfig
    } else {
      if (this.providers.length >= 32) {
        throw new Error('单台主机最多配置 32 个模型服务')
      }
      this.providers.push(nextConfig)
    }
    this.persistProviders()

    await this.refreshProviders(id)
    const hasKey = Boolean(this.resolveKeyForConfig(nextConfig))
    return toPublicProviderInfo(nextConfig, this.probeStates.get(id), hasKey)
  }

  removeProvider(providerId: string): void {
    const clean = providerId.trim()
    if (!clean || clean.startsWith('sub_')) {
      throw new Error('不能移除引擎原生订阅来源')
    }
    const before = this.providers.length
    this.providers = this.providers.filter(p => p.id !== clean)
    this.probeStates.delete(clean)
    if (this.providers.length !== before) {
      this.persistProviders()
    }
  }

  async probeProviderDraft(input: ProviderConfigInput): Promise<ProviderInfo> {
    const name = input.name.trim() || '测试服务'
    const baseUrl = normalizeProviderBaseUrl(input.baseUrl)
    const existing = input.id ? this.getProviderConfig(input.id.trim()) : undefined
    const adapter: ProviderAdapterKind =
      input.adapter ?? existing?.adapter ?? (input.id === 'cpa_local' ? 'cpa' : 'generic')
    const wireApis: WireApi[] =
      input.wireApis && input.wireApis.length > 0
        ? [...new Set(input.wireApis)]
        : existing?.wireApis ?? [...DEFAULT_CPA_WIRE_APIS]
    const apiKey = input.clearApiKey
      ? undefined
      : input.apiKey !== undefined
        ? (input.apiKey.trim() || undefined)
        : existing?.apiKey
    const apiKeyEnv =
      input.apiKeyEnv !== undefined ? (input.apiKeyEnv.trim() || undefined) : existing?.apiKeyEnv
    const manualModels =
      input.manualModels !== undefined
        ? normalizeModelList(input.manualModels, 80)
        : existing?.manualModels

    const draft: StoredProviderConfig = {
      id: existing?.id ?? input.id?.trim() ?? 'probe_draft',
      name,
      adapter,
      baseUrl,
      ...(apiKey ? { apiKey } : {}),
      ...(apiKeyEnv ? { apiKeyEnv } : {}),
      wireApis,
      enabledEngines: sanitizeEnabledEngines(input.enabledEngines, wireApis),
      enabled: true,
      ...(manualModels ? { manualModels } : {}),
    }

    const res = await this.getAdapter(adapter).probe(draft)
    const hasKey = Boolean(this.resolveKeyForConfig(draft))
    return toPublicProviderInfo(draft, res, hasKey)
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

  private collectKnownExternalApiKeys(): Set<string> {
    const keys = new Set<string>()
    for (const p of this.providers) {
      const k = this.resolveKeyForConfig(p)
      if (k) keys.add(k)
    }
    return keys
  }

  /**
   * Computes child process environment variables for a given (engineId, providerId, modelId).
   * - Enforces compliance guardrails: AGY never accepts external proxy endpoints.
   * - Isolates native subscription sessions (`sub_*` or omitted) from inherited proxy env vars
   *   so switching back to official login never leaks `ANTHROPIC_BASE_URL` / `OPENAI_BASE_URL`.
   */
  resolveSessionEnv(
    engineId: string,
    providerId?: string,
    modelId?: string,
  ): NodeJS.ProcessEnv {
    if (engineId === 'agy') {
      if (isExternalProviderId(providerId)) {
        throw new Error('Compliance guardrail: AGY does not allow API proxy endpoints; use official subscription.')
      }
      return {}
    }

    if (isExternalProviderId(providerId)) {
      const provider = this.getProviderConfig(providerId!)
      if (!provider) {
        throw new Error(`未找到模型服务 "${providerId}"，请检查当前主机的模型服务配置。`)
      }
      if (!provider.enabled) {
        throw new Error(`模型服务 "${provider.name}" 已停用。`)
      }
      if (!provider.enabledEngines.includes(engineId) || !isEngineCompatibleWithWireApis(engineId, provider.wireApis)) {
        throw new Error(`模型服务 "${provider.name}" 未启用或不支持 ${engineId} 引擎。`)
      }

      const resolvedKey = this.resolveKeyForConfig(provider)
      const effectiveKey =
        resolvedKey ?? (isLoopbackUrl(provider.baseUrl) ? 'cpa-local-key' : undefined)
      const probeModels = this.probeStates.get(provider.id)?.models ?? []
      const chosenModel = modelId?.trim() || probeModels[0]

      if (engineId === 'claude') {
        return {
          ANTHROPIC_BASE_URL: normalizeBaseUrlForAnthropic(provider.baseUrl),
          ...(effectiveKey ? { ANTHROPIC_API_KEY: effectiveKey } : {}),
          // Explicitly neutralize ANTHROPIC_AUTH_TOKEN so a stale token in ~/.claude/settings.json
          // does not override or collide with the selected external provider's API key.
          ANTHROPIC_AUTH_TOKEN: '',
          ...(chosenModel ? { ANTHROPIC_MODEL: chosenModel } : {}),
        }
      }
      if (engineId === 'codex') {
        const wireApi = provider.wireApis.includes('openai-responses') ? 'responses' : 'chat'
        return {
          OPENAI_BASE_URL: normalizeBaseUrlForOpenAi(provider.baseUrl),
          ...(effectiveKey ? { OPENAI_API_KEY: effectiveKey } : {}),
          LEWORK_PROVIDER_NAME: provider.name,
          LEWORK_CODEX_WIRE_API: wireApi,
        }
      }
      return {}
    }

    // Native subscription route: explicitly strip inherited proxy overrides from Hub's shell env
    // while keeping genuine standalone official API keys (when no proxy base URL was set).
    const knownExternalKeys = this.collectKnownExternalApiKeys()
    if (engineId === 'claude') {
      const env: NodeJS.ProcessEnv = {
        ANTHROPIC_BASE_URL: undefined,
        ANTHROPIC_AUTH_TOKEN: undefined,
        ANTHROPIC_MODEL: undefined,
      }
      const inheritedKey = process.env.ANTHROPIC_API_KEY?.trim()
      if (
        process.env.ANTHROPIC_BASE_URL?.trim() ||
        (inheritedKey && knownExternalKeys.has(inheritedKey))
      ) {
        env.ANTHROPIC_API_KEY = undefined
      }
      return env
    }
    if (engineId === 'codex') {
      const env: NodeJS.ProcessEnv = {
        OPENAI_BASE_URL: undefined,
      }
      const inheritedKey = process.env.OPENAI_API_KEY?.trim()
      if (
        process.env.OPENAI_BASE_URL?.trim() ||
        (inheritedKey && knownExternalKeys.has(inheritedKey))
      ) {
        env.OPENAI_API_KEY = undefined
      }
      return env
    }
    return {}
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
    return [
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
        name: 'Claude Code · CPA',
        slaveId: this.hubId,
        engineId: 'claude',
        providerId: 'cpa_local',
        mode: 'auto',
        defaultCwd,
      },
      {
        id: 'prof_pi_cpa',
        name: 'Pi Engine (自建) · CPA',
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
  }
}
