import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { ProviderAdapterKind, ProviderInfo, WireApi } from '@lework/protocol'
import { defaultDataDir } from '../platform/index.ts'

export interface StoredProviderConfig {
  id: string
  name: string
  adapter: ProviderAdapterKind
  baseUrl: string
  apiKey?: string
  apiKeyEnv?: string
  wireApis: WireApi[]
  enabledEngines: string[]
  enabled: boolean
  builtIn?: boolean
  manualModels?: string[]
}

export interface ProviderProbeResult {
  healthy: boolean
  authStatus?: 'authenticated' | 'required' | 'unknown'
  models: string[]
  latencyMs?: number
  error?: string
}

export interface ProviderAdapter {
  readonly kind: ProviderAdapterKind
  resolveApiKey(config: StoredProviderConfig): string | undefined
  probe(config: StoredProviderConfig): Promise<ProviderProbeResult>
}

export const NON_CHAT_MODEL_RE =
  /(?:^|[-_/.:])(image|imagen|dall-e|dalle|embedding|embeddings|embed|whisper|tts|rerank|reranker|moderation|transcribe|transcription|speech|audio-preview|veo|sora|flux|midjourney|sdxl|stable-diffusion)(?:$|[-_/.:])/i

export const ALLOWED_EXTERNAL_ENGINES = ['claude', 'codex', 'pi', 'dsh'] as const

export function isExternalProviderId(providerId?: string | null): boolean {
  const trimmed = providerId?.trim()
  return Boolean(trimmed && !trimmed.startsWith('sub_'))
}

export function isChatModelId(modelId: string): boolean {
  const trimmed = modelId.trim()
  if (!trimmed) return false
  return !NON_CHAT_MODEL_RE.test(trimmed)
}

export function normalizeProviderBaseUrl(rawUrl: string): string {
  const trimmed = rawUrl.trim()
  if (!trimmed) {
    throw new Error('模型服务地址不能为空')
  }
  let parsed: URL
  try {
    parsed = new URL(trimmed)
  } catch {
    throw new Error(`模型服务地址格式无效：${trimmed}`)
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('模型服务地址必须使用 http:// 或 https://')
  }
  return parsed.toString().replace(/\/+$/, '')
}

export function isLoopbackUrl(baseUrl: string): boolean {
  try {
    const host = new URL(baseUrl).hostname.toLowerCase()
    return host === '127.0.0.1' || host === 'localhost' || host === '::1' || host === '[::1]'
  } catch {
    return false
  }
}

export function normalizeBaseUrlForAnthropic(baseUrl: string): string {
  const clean = baseUrl.replace(/\/+$/, '')
  return clean.replace(/\/v1$/i, '')
}

export function normalizeBaseUrlForOpenAi(baseUrl: string): string {
  const clean = baseUrl.replace(/\/+$/, '')
  if (/\/v\d+(?:beta\d*)?$/i.test(clean)) return clean
  return `${clean}/v1`
}

export function defaultEnginesForWireApis(wireApis: WireApi[]): string[] {
  const out: string[] = []
  if (wireApis.includes('anthropic-messages')) {
    out.push('claude')
  }
  if (wireApis.includes('openai-responses') || wireApis.includes('openai-chat')) {
    out.push('codex')
  }
  if (
    wireApis.includes('openai-chat') ||
    wireApis.includes('openai-responses') ||
    wireApis.includes('anthropic-messages')
  ) {
    out.push('pi')
  }
  return out
}

export function isEngineCompatibleWithWireApis(engineId: string, wireApis: WireApi[]): boolean {
  if (engineId === 'agy') return false
  if (engineId === 'claude') {
    return wireApis.includes('anthropic-messages')
  }
  if (engineId === 'codex') {
    return wireApis.includes('openai-responses') || wireApis.includes('openai-chat')
  }
  if (engineId === 'pi' || engineId === 'dsh') {
    return (
      wireApis.includes('openai-chat') ||
      wireApis.includes('openai-responses') ||
      wireApis.includes('anthropic-messages')
    )
  }
  return false
}

export function sanitizeEnabledEngines(rawEngines: string[] | undefined, wireApis: WireApi[]): string[] {
  if (!rawEngines) {
    return defaultEnginesForWireApis(wireApis)
  }
  const allowed = new Set<string>(ALLOWED_EXTERNAL_ENGINES)
  const seen = new Set<string>()
  const out: string[] = []
  for (const raw of rawEngines) {
    const id = raw.trim().toLowerCase()
    if (!id || id === 'agy' || !allowed.has(id) || seen.has(id)) continue
    seen.add(id)
    out.push(id)
  }
  return out
}

/**
 * Merges base process env with session-specific overrides.
 * Keys mapped to `undefined` in `overrides` are explicitly deleted from the returned env
 * so inherited host proxy variables never bleed into native subscription sessions.
 */
export function mergeSessionEnv(
  baseEnv: NodeJS.ProcessEnv,
  overrides?: NodeJS.ProcessEnv,
): NodeJS.ProcessEnv {
  const merged: NodeJS.ProcessEnv = { ...baseEnv }
  if (!overrides) return merged
  for (const [key, value] of Object.entries(overrides)) {
    if (value === undefined) {
      delete merged[key]
    } else {
      merged[key] = value
    }
  }
  return merged
}

function readCpaKeyFromConfigFiles(): string | undefined {
  const explicit = process.env.LEWORK_CPA_CONFIG?.trim()
  const candidates = explicit
    ? [explicit]
    : [
        path.join(defaultDataDir(), 'cpa-config.yaml'),
        path.join(os.homedir(), '.cli-proxy-api', 'config.yaml'),
        path.join(os.homedir(), '.config', 'cli-proxy-api', 'config.yaml'),
      ]

  for (const cfgPath of candidates) {
    try {
      if (!fs.existsSync(cfgPath)) continue
      const raw = fs.readFileSync(cfgPath, 'utf8')
      const match = raw.match(/api-keys:\s*\n\s*-\s*["']?([^"'\s\n]+)["']?/m)
      if (match?.[1]) {
        return match[1]
      }
    } catch {
      // ignore unreadable candidate config
    }
  }
  return undefined
}

function resolveEnvVarKey(envName?: string): string | undefined {
  const clean = envName?.trim()
  if (!clean) return undefined
  const val = process.env[clean]?.trim()
  return val || undefined
}

function extractModelsFromPayload(body: unknown, manualModels?: string[]): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  const push = (raw: unknown) => {
    if (typeof raw !== 'string') return
    const id = raw.trim()
    if (!id || seen.has(id) || !isChatModelId(id)) return
    seen.add(id)
    out.push(id)
  }

  if (body && typeof body === 'object') {
    const obj = body as {
      data?: Array<{ id?: unknown; name?: unknown }>
      models?: Array<{ id?: unknown; name?: unknown; model?: unknown }>
    }
    if (Array.isArray(obj.data)) {
      for (const item of obj.data) {
        push(item?.id ?? item?.name)
      }
    } else if (Array.isArray(obj.models)) {
      for (const item of obj.models) {
        push(item?.id ?? item?.model ?? item?.name)
      }
    }
  }

  for (const m of manualModels ?? []) {
    push(m)
  }
  return out.slice(0, 120)
}

function buildModelsUrl(baseUrl: string, adapter: ProviderAdapterKind): string {
  const clean = baseUrl.replace(/\/+$/, '')
  if (adapter === 'cpa') {
    const root = clean.replace(/\/v1$/i, '')
    return `${root}/v1/models`
  }
  if (/\/v\d+(?:beta\d*)?$/i.test(clean)) {
    return `${clean}/models`
  }
  return `${clean}/v1/models`
}

export class CpaProviderAdapter implements ProviderAdapter {
  readonly kind: ProviderAdapterKind = 'cpa'

  resolveApiKey(config: StoredProviderConfig): string | undefined {
    const direct = config.apiKey?.trim()
    if (direct) return direct
    const fromNamedEnv = resolveEnvVarKey(config.apiKeyEnv)
    if (fromNamedEnv) return fromNamedEnv
    const fromDefaultEnv = process.env.LEWORK_CPA_KEY?.trim()
    if (fromDefaultEnv) return fromDefaultEnv
    return readCpaKeyFromConfigFiles()
  }

  async probe(config: StoredProviderConfig): Promise<ProviderProbeResult> {
    const started = Date.now()
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), 2_500)
    timer.unref?.()
    try {
      const headers: Record<string, string> = {}
      const key = this.resolveApiKey(config)
      if (key) {
        headers.Authorization = `Bearer ${key}`
      }
      const res = await fetch(buildModelsUrl(config.baseUrl, 'cpa'), {
        headers,
        signal: ctl.signal,
      })
      const latencyMs = Math.max(0, Date.now() - started)
      if (res.status === 401 || res.status === 403) {
        return {
          healthy: false,
          authStatus: 'required',
          models: [],
          latencyMs,
          error: '访问密钥无效或未配置',
        }
      }
      if (res.ok) {
        const body = await res.json().catch(() => ({}))
        const models = extractModelsFromPayload(body, config.manualModels)
        return {
          healthy: models.length > 0,
          authStatus: 'authenticated',
          models,
          latencyMs,
          ...(models.length === 0 ? { error: '服务在线但未返回可用对话模型' } : {}),
        }
      }
      return {
        healthy: false,
        authStatus: 'unknown',
        models: [],
        latencyMs,
        error: `探测返回 HTTP ${res.status}`,
      }
    } catch (err) {
      const aborted = err instanceof Error && err.name === 'AbortError'
      return {
        healthy: false,
        models: [],
        error: aborted ? '连接超时' : '无法连接到服务地址',
      }
    } finally {
      clearTimeout(timer)
    }
  }
}

export class GenericProviderAdapter implements ProviderAdapter {
  readonly kind: ProviderAdapterKind = 'generic'

  resolveApiKey(config: StoredProviderConfig): string | undefined {
    const direct = config.apiKey?.trim()
    if (direct) return direct
    return resolveEnvVarKey(config.apiKeyEnv)
  }

  async probe(config: StoredProviderConfig): Promise<ProviderProbeResult> {
    const started = Date.now()
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), 3_500)
    timer.unref?.()
    try {
      const headers: Record<string, string> = {}
      const key = this.resolveApiKey(config)
      if (key) {
        headers.Authorization = `Bearer ${key}`
        if (config.wireApis.length === 1 && config.wireApis[0] === 'anthropic-messages') {
          headers['x-api-key'] = key
          headers['anthropic-version'] = '2023-06-01'
        }
      }
      const res = await fetch(buildModelsUrl(config.baseUrl, 'generic'), {
        headers,
        signal: ctl.signal,
      })
      const latencyMs = Math.max(0, Date.now() - started)
      if (res.status === 401 || res.status === 403) {
        return {
          healthy: false,
          authStatus: 'required',
          models: [],
          latencyMs,
          error: '访问密钥无效或未授权',
        }
      }
      if (res.ok) {
        const body = await res.json().catch(() => ({}))
        const models = extractModelsFromPayload(body, config.manualModels)
        return {
          healthy: models.length > 0,
          authStatus: 'authenticated',
          models,
          latencyMs,
          ...(models.length === 0 ? { error: '服务在线但未发现可用模型，可在设置中手动补充模型名' } : {}),
        }
      }
      // Some custom proxies don't implement /v1/models; fall back to manualModels when configured.
      if (
        (res.status === 404 || res.status === 405 || res.status === 501) &&
        config.manualModels &&
        config.manualModels.length > 0
      ) {
        const models = extractModelsFromPayload({}, config.manualModels)
        return {
          healthy: models.length > 0,
          authStatus: 'unknown',
          models,
          latencyMs,
        }
      }
      return {
        healthy: false,
        authStatus: 'unknown',
        models: [],
        latencyMs,
        error: `探测返回 HTTP ${res.status}`,
      }
    } catch (err) {
      const aborted = err instanceof Error && err.name === 'AbortError'
      return {
        healthy: false,
        models: [],
        error: aborted ? '连接超时' : '无法连接到服务地址',
      }
    } finally {
      clearTimeout(timer)
    }
  }
}

export function createProviderAdapters(): Record<ProviderAdapterKind, ProviderAdapter> {
  return {
    cpa: new CpaProviderAdapter(),
    generic: new GenericProviderAdapter(),
  }
}

export function toPublicProviderInfo(
  config: StoredProviderConfig,
  probe: ProviderProbeResult | undefined,
  hasResolvedKey: boolean,
): ProviderInfo {
  const models = config.enabled ? (probe?.models ?? []) : []
  const healthy = config.enabled ? Boolean(probe?.healthy && models.length > 0) : false
  return {
    id: config.id,
    name: config.name,
    type: 'endpoint',
    adapter: config.adapter,
    baseUrl: config.baseUrl,
    wireApi: config.wireApis[0],
    wireApis: [...config.wireApis],
    enabledEngines: [...config.enabledEngines],
    enabled: config.enabled,
    builtIn: Boolean(config.builtIn),
    hasApiKey: Boolean(config.apiKey?.trim() || hasResolvedKey),
    ...(config.apiKeyEnv ? { apiKeyEnv: config.apiKeyEnv } : {}),
    ...(config.manualModels && config.manualModels.length > 0 ? { manualModels: [...config.manualModels] } : {}),
    healthy,
    ...(config.enabled && probe?.authStatus ? { authStatus: probe.authStatus } : {}),
    ...(config.enabled && probe?.latencyMs !== undefined ? { latencyMs: probe.latencyMs } : {}),
    ...(config.enabled && probe?.error ? { error: probe.error } : {}),
    models,
  }
}
