import fs from 'node:fs'
import path from 'node:path'
import { defaultDataDir } from '../platform/index.ts'

/**
 * Token pricing rates in USD per 1,000,000 (1M) tokens.
 */
export interface ModelPricingRate {
  input: number
  output: number
  cacheWrite: number
  cacheRead: number
}

interface CatalogEntry {
  patterns: string[]
  rate: ModelPricingRate
}

/**
 * Built-in pricing catalog ordered from most specific to broadest family match.
 * Rates are USD per 1M tokens.
 */
const BUILTIN_PRICING_CATALOG: CatalogEntry[] = [
  // --- Anthropic Claude Opus ---
  // Opus 4.5 / 4.6 / 5.x ($5 / $25, 1.25x cache write, 0.1x cache read)
  {
    patterns: [
      'claude-opus-5',
      'opus-5',
      'claude-opus-4-6',
      'claude-opus-4.6',
      'opus-4-6',
      'opus-4.6',
      'claude-opus-4-5',
      'claude-opus-4.5',
      'opus-4-5',
      'opus-4.5',
    ],
    rate: { input: 5, output: 25, cacheWrite: 6.25, cacheRead: 0.5 },
  },
  // Opus 4 / 4.1 ($15 / $75, 1.25x cache write, 0.1x cache read)
  {
    patterns: [
      'claude-opus-4-1',
      'claude-opus-4.1',
      'opus-4-1',
      'opus-4.1',
      'claude-opus-4',
      'claude-4-opus',
      'opus-4',
    ],
    rate: { input: 15, output: 75, cacheWrite: 18.75, cacheRead: 1.5 },
  },

  // --- Anthropic Claude Sonnet ---
  // Sonnet 3.7 / 4 / 4.5 / 4.6 / 5.x ($3 / $15, 1.25x cache write, 0.1x cache read)
  {
    patterns: [
      'claude-sonnet-5',
      'sonnet-5',
      'claude-sonnet-4-6',
      'claude-sonnet-4.6',
      'claude-sonnet-4-5',
      'claude-sonnet-4.5',
      'claude-sonnet-4',
      'claude-4-sonnet',
      'sonnet-4',
      'claude-3-7-sonnet',
      'claude-3.7-sonnet',
      'claude-sonnet-3-7',
      'sonnet-3-7',
      'sonnet-3.7',
      'claude-3-5-sonnet',
      'sonnet',
    ],
    rate: { input: 3, output: 15, cacheWrite: 3.75, cacheRead: 0.3 },
  },

  // --- Anthropic Claude Haiku ---
  // Haiku 4.5 ($1 / $5)
  {
    patterns: ['claude-haiku-4-5', 'claude-haiku-4.5', 'haiku-4-5', 'haiku-4.5', 'claude-haiku-4'],
    rate: { input: 1, output: 5, cacheWrite: 1.25, cacheRead: 0.1 },
  },
  // Haiku 3.5 ($0.80 / $4)
  {
    patterns: ['claude-3-5-haiku', 'claude-3.5-haiku', 'claude-haiku-3-5', 'haiku-3-5', 'haiku-3.5', 'haiku'],
    rate: { input: 0.8, output: 4, cacheWrite: 1.0, cacheRead: 0.08 },
  },

  // --- OpenAI GPT-5 / GPT-6 / Codex / o-series / GPT-4.1 ---
  {
    patterns: ['codex-mini', 'gpt-5.1-codex-mini', 'gpt-5-codex-mini'],
    rate: { input: 1.5, output: 6, cacheWrite: 1.5, cacheRead: 0.375 },
  },
  {
    patterns: ['gpt-5-mini', 'gpt-5.1-mini'],
    rate: { input: 0.25, output: 2, cacheWrite: 0.25, cacheRead: 0.025 },
  },
  {
    patterns: ['gpt-5-nano'],
    rate: { input: 0.05, output: 0.4, cacheWrite: 0.05, cacheRead: 0.005 },
  },
  {
    patterns: ['gpt-5.4', 'gpt-5-4'],
    rate: { input: 2.5, output: 15, cacheWrite: 2.5, cacheRead: 0.25 },
  },
  {
    patterns: ['gpt-5.2', 'gpt-5-2'],
    rate: { input: 1.75, output: 14, cacheWrite: 1.75, cacheRead: 0.175 },
  },
  {
    patterns: ['gpt-5.1', 'gpt-5-1', 'gpt-5'],
    rate: { input: 1.25, output: 10, cacheWrite: 1.25, cacheRead: 0.125 },
  },
  {
    patterns: ['gpt-6.2', 'gpt-6-2', 'gpt-6.1', 'gpt-6-1', 'gpt-6'],
    rate: { input: 2.5, output: 15, cacheWrite: 2.5, cacheRead: 0.25 },
  },
  {
    patterns: ['o4-mini'],
    rate: { input: 1.1, output: 4.4, cacheWrite: 1.1, cacheRead: 0.275 },
  },
  {
    patterns: ['o3-mini'],
    rate: { input: 1.1, output: 4.4, cacheWrite: 1.1, cacheRead: 0.55 },
  },
  {
    patterns: ['o3'],
    rate: { input: 2, output: 8, cacheWrite: 2, cacheRead: 0.5 },
  },
  {
    patterns: ['gpt-4.1-nano', 'gpt-4-1-nano'],
    rate: { input: 0.1, output: 0.4, cacheWrite: 0.1, cacheRead: 0.025 },
  },
  {
    patterns: ['gpt-4.1-mini', 'gpt-4-1-mini'],
    rate: { input: 0.4, output: 1.6, cacheWrite: 0.4, cacheRead: 0.1 },
  },
  {
    patterns: ['gpt-4.1', 'gpt-4-1', 'gpt-4o'],
    rate: { input: 2, output: 8, cacheWrite: 2, cacheRead: 0.5 },
  },

  // --- Google Gemini ---
  {
    patterns: [
      'gemini-2.5-flash',
      'gemini-2-5-flash',
      'gemini-3-flash',
      'gemini-4-flash',
      'gemini-4-hydrogen',
      'gemini-flash',
    ],
    rate: { input: 0.3, output: 2.5, cacheWrite: 0.3, cacheRead: 0.03 },
  },
  {
    patterns: ['gemini-2.5-pro', 'gemini-2-5-pro'],
    rate: { input: 1.25, output: 10, cacheWrite: 1.25, cacheRead: 0.125 },
  },
  {
    patterns: ['gemini-4', 'gemini-3', 'gemini'],
    rate: { input: 2, output: 12, cacheWrite: 2, cacheRead: 0.2 },
  },

  // --- DeepSeek ---
  {
    patterns: ['deepseek-reasoner', 'deepseek-r1'],
    rate: { input: 0.55, output: 2.19, cacheWrite: 0.55, cacheRead: 0.14 },
  },
  {
    patterns: ['deepseek-chat', 'deepseek-v3', 'deepseek'],
    rate: { input: 0.27, output: 1.1, cacheWrite: 0.27, cacheRead: 0.07 },
  },
]

interface OverrideCache {
  filePath: string
  mtimeMs: number
  size: number
  checkedAt: number
  entries: Map<string, ModelPricingRate>
}

let overrideCache: OverrideCache | null = null

function loadUserPricingOverrides(dataDir?: string): Map<string, ModelPricingRate> {
  const dir = dataDir ?? defaultDataDir()
  const filePath = path.join(dir, 'pricing.json')
  const now = Date.now()

  if (overrideCache && overrideCache.filePath === filePath && now - overrideCache.checkedAt < 2_000) {
    return overrideCache.entries
  }

  let st: fs.Stats
  try {
    st = fs.statSync(filePath)
  } catch {
    overrideCache = { filePath, mtimeMs: -1, size: -1, checkedAt: now, entries: new Map() }
    return overrideCache.entries
  }

  if (
    overrideCache &&
    overrideCache.filePath === filePath &&
    overrideCache.mtimeMs === st.mtimeMs &&
    overrideCache.size === st.size
  ) {
    overrideCache.checkedAt = now
    return overrideCache.entries
  }

  const entries = new Map<string, ModelPricingRate>()
  try {
    const raw = JSON.parse(fs.readFileSync(filePath, 'utf8')) as Record<string, unknown>
    const source =
      raw && typeof raw === 'object' && raw.models && typeof raw.models === 'object' && !Array.isArray(raw.models)
        ? (raw.models as Record<string, unknown>)
        : raw
    if (source && typeof source === 'object') {
      for (const [key, val] of Object.entries(source)) {
        if (!val || typeof val !== 'object') continue
        const obj = val as Record<string, unknown>
        const input = typeof obj.input === 'number' && Number.isFinite(obj.input) && obj.input >= 0 ? obj.input : null
        const output =
          typeof obj.output === 'number' && Number.isFinite(obj.output) && obj.output >= 0 ? obj.output : null
        if (input === null || output === null) continue
        const cacheWrite =
          typeof obj.cacheWrite === 'number' && Number.isFinite(obj.cacheWrite) && obj.cacheWrite >= 0
            ? obj.cacheWrite
            : input
        const cacheRead =
          typeof obj.cacheRead === 'number' && Number.isFinite(obj.cacheRead) && obj.cacheRead >= 0
            ? obj.cacheRead
            : input * 0.1
        entries.set(key.trim().toLowerCase(), { input, output, cacheWrite, cacheRead })
      }
    }
  } catch {
    // ignore malformed user pricing.json
  }

  overrideCache = {
    filePath,
    mtimeMs: st.mtimeMs,
    size: st.size,
    checkedAt: now,
    entries,
  }
  return entries
}

export function clearPricingOverrideCache(): void {
  overrideCache = null
}

function normalizeModelForPricing(model: string): string {
  return model
    .trim()
    .toLowerCase()
    .replace(/\[1m\]/g, '')
    .replace(/^anthropic[/:]/, '')
    .replace(/^openai[/:]/, '')
    .replace(/^google[/:]/, '')
    .trim()
}

/**
 * Resolves USD-per-1M-token pricing for a model ID using user overrides first,
 * followed by prefix/substring fuzzy matching against the built-in catalog.
 */
export function resolveModelPricing(model: string | undefined, dataDir?: string): ModelPricingRate | undefined {
  if (!model) return undefined
  const rawLower = model.trim().toLowerCase()
  if (!rawLower || rawLower === '<synthetic>' || rawLower === 'unknown') return undefined
  const normalized = normalizeModelForPricing(model)

  const overrides = loadUserPricingOverrides(dataDir)
  if (overrides.size > 0) {
    const exact = overrides.get(rawLower) ?? overrides.get(normalized)
    if (exact) return exact
    // Longest matching override key wins
    let bestKeyLen = -1
    let bestRate: ModelPricingRate | undefined
    for (const [pattern, rate] of overrides) {
      if ((rawLower.includes(pattern) || normalized.includes(pattern)) && pattern.length > bestKeyLen) {
        bestKeyLen = pattern.length
        bestRate = rate
      }
    }
    if (bestRate) return bestRate
  }

  for (const entry of BUILTIN_PRICING_CATALOG) {
    for (const pattern of entry.patterns) {
      if (normalized.startsWith(pattern) || normalized.includes(pattern)) {
        return entry.rate
      }
    }
  }
  return undefined
}

export function calculateTokenCostUsd(
  model: string | undefined,
  tokens: {
    inputTokens: number
    outputTokens: number
    cacheWriteTokens?: number
    cacheReadTokens?: number
  },
  dataDir?: string,
): number | undefined {
  const rate = resolveModelPricing(model, dataDir)
  if (!rate) return undefined
  const inTok = Math.max(0, tokens.inputTokens)
  const outTok = Math.max(0, tokens.outputTokens)
  const cwTok = Math.max(0, tokens.cacheWriteTokens ?? 0)
  const crTok = Math.max(0, tokens.cacheReadTokens ?? 0)
  return (inTok * rate.input + outTok * rate.output + cwTok * rate.cacheWrite + crTok * rate.cacheRead) / 1_000_000
}

export function roundUsd(cost: number | undefined): number | undefined {
  if (cost === undefined || !Number.isFinite(cost)) return undefined
  return Math.round(cost * 1_000_000) / 1_000_000
}

/**
 * Resolves the effective context window size (in tokens) for a session/model.
 * - Uses `reportedWindow` if > 0
 * - `1_000_000` for `[1m]` / `1m` / `gemini`
 * - `258_400` for codex / gpt-5 / gpt-6 if unreported
 * - `200_000` default for Claude and standard models
 */
export function resolveContextWindow(
  engineId: string,
  model?: string,
  reportedWindow?: number,
): number {
  if (typeof reportedWindow === 'number' && Number.isFinite(reportedWindow) && reportedWindow > 0) {
    return Math.floor(reportedWindow)
  }
  const m = (model ?? '').trim().toLowerCase()
  const eng = engineId.trim().toLowerCase()

  if (
    m.includes('[1m]') ||
    /(?:^|[-_:\s(])1m(?:$|[-_:\s)\]])/.test(m) ||
    m.includes('gemini')
  ) {
    return 1_000_000
  }

  if (m.includes('gpt-5') || m.includes('gpt-6') || m.includes('codex') || eng === 'codex') {
    return 258_400
  }

  if (eng === 'agy' && !m) {
    return 1_000_000
  }

  return 200_000
}
