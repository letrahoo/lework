import { execFile } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { promisify } from 'node:util'
import type { EngineModelSpec, ReasoningEffort, SessionMode } from '@lework/protocol'
import { findExecutable } from '../../platform/index.ts'

const execFileAsync = promisify(execFile)

export function normalizeHostMode(raw: unknown, fallback: SessionMode = 'default'): SessionMode {
  if (typeof raw !== 'string') return fallback
  switch (raw.trim()) {
    case 'auto':
      return 'auto'
    case 'acceptEdits':
    case 'auto-edit':
    case 'auto_edit':
      return 'acceptEdits'
    case 'plan':
      return 'plan'
    case 'bypassPermissions':
    case 'dontAsk':
    case 'never':
    case 'yolo':
      return 'bypassPermissions'
    case 'default':
    case 'manual':
    case 'ask':
    case 'on-request':
      return 'default'
    default:
      return fallback
  }
}

export const REASONING_EFFORT_ORDER: readonly ReasoningEffort[] = [
  'low',
  'medium',
  'high',
  'xhigh',
  'max',
]

export function sortReasoningEfforts(efforts: Iterable<ReasoningEffort>): ReasoningEffort[] {
  const unique = new Set<ReasoningEffort>(efforts)
  return REASONING_EFFORT_ORDER.filter(level => unique.has(level))
}

export function normalizeHostEffort(raw: unknown, fallback: ReasoningEffort = 'medium'): ReasoningEffort {
  if (typeof raw !== 'string') return fallback
  switch (raw.trim().toLowerCase()) {
    case 'low':
      return 'low'
    case 'medium':
      return 'medium'
    case 'high':
      return 'high'
    case 'xhigh':
      return 'xhigh'
    case 'max':
      return 'max'
    default:
      return fallback
  }
}

export function resolveClaudeEffectiveModel(
  rawModel: string | undefined,
  env?: Record<string, string>,
): string | undefined {
  const m = rawModel?.trim()
  const lower = m?.toLowerCase().replace(/\[1m\]$/i, '') ?? ''
  if (env) {
    if (env.ANTHROPIC_MODEL?.trim() && (!m || lower === 'default')) {
      return env.ANTHROPIC_MODEL.trim()
    }
    if (lower === 'opus' || lower === 'opusplan') {
      return env.ANTHROPIC_DEFAULT_OPUS_MODEL?.trim() || env.ANTHROPIC_MODEL?.trim() || m
    }
    if (lower === 'sonnet' || !m || lower === 'default') {
      return (
        env.ANTHROPIC_DEFAULT_SONNET_MODEL?.trim() ||
        env.ANTHROPIC_MODEL?.trim() ||
        env.ANTHROPIC_DEFAULT_OPUS_MODEL?.trim() ||
        m
      )
    }
    if (lower === 'haiku') {
      return (
        env.ANTHROPIC_DEFAULT_HAIKU_MODEL?.trim() ||
        env.ANTHROPIC_SMALL_FAST_MODEL?.trim() ||
        env.ANTHROPIC_MODEL?.trim() ||
        m
      )
    }
  }
  return m || undefined
}

export function readClaudeLocalSettings(homeDir = os.homedir()): {
  model?: string
  rawModel?: string
  env?: Record<string, string>
  defaultMode: SessionMode
  effort: ReasoningEffort
  usesExternalAuth?: boolean
} {
  const settingsPath = path.join(process.env.CLAUDE_CONFIG_DIR ?? path.join(homeDir, '.claude'), 'settings.json')
  try {
    if (fs.existsSync(settingsPath)) {
      const parsed = JSON.parse(fs.readFileSync(settingsPath, 'utf8')) as {
        model?: string
        defaultMode?: string
        effortLevel?: string
        env?: Record<string, string>
        apiKeyHelper?: unknown
        permissions?: { defaultMode?: string }
      }
      const rawMode = parsed.permissions?.defaultMode ?? parsed.defaultMode
      const rawModel = typeof parsed.model === 'string' && parsed.model.trim() ? parsed.model.trim() : undefined
      return {
        model: resolveClaudeEffectiveModel(rawModel, parsed.env),
        rawModel,
        env: parsed.env,
        defaultMode: normalizeHostMode(rawMode, 'default'),
        effort: normalizeHostEffort(parsed.effortLevel, 'medium'),
        usesExternalAuth: Boolean(parsed.apiKeyHelper || parsed.env?.ANTHROPIC_BASE_URL || parsed.env?.ANTHROPIC_AUTH_TOKEN || parsed.env?.ANTHROPIC_API_KEY),
      }
    }
  } catch {
    // ignore
  }
  return { defaultMode: 'default', effort: 'medium' }
}

export function readCodexLocalSettings(homeDir = os.homedir()): {
  usesExternalProvider?: boolean
  model?: string
  defaultMode: SessionMode
  effort: ReasoningEffort
} {
  const tomlPath = path.join(process.env.CODEX_HOME ?? path.join(homeDir, '.codex'), 'config.toml')
  try {
    if (fs.existsSync(tomlPath)) {
      const raw = fs.readFileSync(tomlPath, 'utf8')
      const modelMatch = raw.match(/^\s*model\s*=\s*["']([^"']+)["']/m)
      const effortMatch = raw.match(/^\s*model_reasoning_effort\s*=\s*["']([^"']+)["']/m)
      // Only root-level keys describe the default profile.
      const rootSettings = raw.split(/^\s*\[/m, 1)[0] ?? ''
      const sandbox = rootSettings.match(/^\s*sandbox_mode\s*=\s*["']([^"']+)["']/m)?.[1]
      const approvalMatch = rootSettings.match(/^\s*approval_policy\s*=\s*["']([^"']+)["']/m)
      return {
        usesExternalProvider: /^\s*(?:model_provider|profile)\s*=/m.test(rootSettings),
        model: modelMatch?.[1]?.trim() || undefined,
        defaultMode: approvalMatch?.[1] === 'never'
          ? (sandbox === 'workspace-write' || sandbox === 'danger-full-access' ? 'bypassPermissions' : 'plan')
          : 'default',
        effort: normalizeHostEffort(effortMatch?.[1], 'medium'),
      }
    }
  } catch {
    // ignore
  }
  return { defaultMode: 'default', effort: 'medium' }
}

export function readCodexModelsCache(homeDir: string, efforts: ReasoningEffort[]): EngineModelSpec[] {
  const p = path.join(process.env.CODEX_HOME ?? path.join(homeDir, '.codex'), 'models_cache.json')
  if (!fs.existsSync(p)) return []
  try {
    const data = JSON.parse(fs.readFileSync(p, 'utf8')) as {
      models?: Array<{ slug?: string; id?: string; display_name?: string }>
    }
    const sortedEfforts = sortReasoningEfforts(efforts)
    const out: EngineModelSpec[] = []
    for (const m of (data.models ?? []).slice(0, 15)) {
      const id = m.slug ?? m.id
      if (!id) continue
      out.push({
        id,
        name: m.display_name ?? id,
        providerId: 'sub_codex',
        efforts: sortedEfforts,
        defaultEffort: 'high',
      })
    }
    return out
  } catch {
    return []
  }
}

let cachedAgyCatalog: EngineModelSpec[] | null = null

export function getCachedAgyCatalog(): EngineModelSpec[] | undefined {
  return cachedAgyCatalog ?? undefined
}

export function setCachedAgyCatalog(models: EngineModelSpec[] | null): void {
  cachedAgyCatalog = models
}

export function parseAgyModelsCliOutput(stdout: string): EngineModelSpec[] {
  const grouped = new Map<
    string,
    { name: string; efforts: Set<ReasoningEffort>; firstEffort?: ReasoningEffort }
  >()
  for (const line of stdout.split(/\r?\n/)) {
    const match = line.match(/^([A-Za-z0-9_.-]{1,128})\t([^\t]{1,256})$/)
    if (!match) continue
    const wireId = match[1]!
    const rawLabel = match[2]!
    const suffixMatch = wireId.match(/-(low|medium|high|xhigh|max)$/)
    const effort = suffixMatch?.[1] as ReasoningEffort | undefined
    const baseId = effort ? wireId.slice(0, -(effort.length + 1)) : wireId
    const cleanName = rawLabel.replace(/\s*\((Low|Medium|High|XHigh|Max)\)\s*$/i, '').trim()
    const item = grouped.get(baseId) ?? {
      name: cleanName,
      efforts: new Set<ReasoningEffort>(),
      firstEffort: undefined,
    }
    if (effort) {
      if (!item.firstEffort) item.firstEffort = effort
      item.efforts.add(effort)
    }
    grouped.set(baseId, item)
  }
  return [...grouped.entries()].map(([id, info]) => {
    const efforts = sortReasoningEfforts(info.efforts)
    return {
      id,
      name: info.name,
      providerId: 'sub_agy',
      efforts: efforts.length > 0 ? efforts : undefined,
      defaultEffort: efforts.includes('high') ? 'high' : (info.firstEffort ?? efforts[0]),
    }
  })
}

export async function loadAgyModelsFromCli(): Promise<EngineModelSpec[]> {
  const bin = findExecutable('agy')
  if (!bin) return []
  try {
    const { stdout } = await execFileAsync(bin, ['models'], {
      timeout: 8_000,
      maxBuffer: 512 * 1024,
      encoding: 'utf8',
    })
    const specs = parseAgyModelsCliOutput(stdout)
    if (specs.length > 0) {
      cachedAgyCatalog = specs
      return specs
    }
    return []
  } catch {
    return []
  }
}

export function resolveAgyWireModel(
  rawModel: string | undefined,
  requestedEffort?: ReasoningEffort,
  catalog?: EngineModelSpec[],
): { wireModel?: string; wireEffort?: ReasoningEffort } {
  const trimmed = rawModel?.trim()
  if (!trimmed) {
    return { wireModel: undefined, wireEffort: requestedEffort }
  }

  const effectiveCatalog = catalog ?? cachedAgyCatalog ?? undefined
  const parsed = parseAgyModelString(trimmed, effectiveCatalog)
  const baseId = parsed.modelId ?? trimmed
  const effectiveRequestEffort = requestedEffort ?? parsed.effort

  if (effectiveCatalog && effectiveCatalog.length > 0) {
    const spec = effectiveCatalog.find(m => m.id.toLowerCase() === baseId.toLowerCase())
    if (spec) {
      if (!spec.efforts || spec.efforts.length === 0) {
        return { wireModel: spec.id, wireEffort: undefined }
      }
      const chosenEffort =
        effectiveRequestEffort && spec.efforts.includes(effectiveRequestEffort)
          ? effectiveRequestEffort
          : spec.defaultEffort && spec.efforts.includes(spec.defaultEffort)
            ? spec.defaultEffort
            : spec.efforts[0]!
      return {
        wireModel: `${spec.id}-${chosenEffort}`,
        wireEffort: chosenEffort,
      }
    }
  }

  const noEffortSuffix =
    baseId.includes('orchestrator') ||
    baseId.startsWith('gpt-oss') ||
    baseId.includes('@') ||
    /^claude-haiku/i.test(baseId)
  if (noEffortSuffix) {
    return { wireModel: baseId, wireEffort: undefined }
  }
  if (effectiveRequestEffort) {
    return {
      wireModel: `${baseId}-${effectiveRequestEffort}`,
      wireEffort: effectiveRequestEffort,
    }
  }
  return { wireModel: baseId, wireEffort: undefined }
}

export function parseAgyModelString(
  raw: string | undefined,
  catalog?: EngineModelSpec[],
): { modelId?: string; effort?: ReasoningEffort } {
  const trimmed = raw?.trim()
  if (!trimmed || trimmed.toLowerCase() === 'none') return {}

  let effort: ReasoningEffort | undefined
  let base = trimmed

  const parenMatch = base.match(/\s*\((low|medium|high|xhigh|max)\)\s*$/i)
  if (parenMatch?.[1]) {
    effort = parenMatch[1].toLowerCase() as ReasoningEffort
    base = base.slice(0, parenMatch.index).trim()
  } else {
    const suffixMatch = base.match(/-(low|medium|high|xhigh|max)$/i)
    if (suffixMatch?.[1]) {
      effort = suffixMatch[1].toLowerCase() as ReasoningEffort
      base = base.slice(0, -(suffixMatch[1].length + 1)).trim()
    }
  }

  if (catalog && catalog.length > 0) {
    const lowerBase = base.toLowerCase()
    const normKey = lowerBase.replace(/[\s.]+/g, '-')
    const exact = catalog.find(
      m =>
        m.id.toLowerCase() === lowerBase ||
        m.name.toLowerCase() === lowerBase ||
        m.id.toLowerCase().replace(/\./g, '-') === normKey,
    )
    if (exact) {
      return { modelId: exact.id, effort }
    }
  }

  const lower = base.toLowerCase()
  if (lower === 'orchestrator (carbon)' || lower === 'orchestrator') {
    return { modelId: 'gemini-pro-orchestrator', effort }
  }
  if (lower === 'orchestrator (neon)') {
    return { modelId: 'gemini-pro-orchestrator-neon', effort }
  }
  if (lower === 'orchestrator (argon)') {
    return { modelId: 'gemini-pro-orchestrator-argon', effort }
  }

  // Convert human-readable label (e.g. "Gemini 4 Carbon", "Claude Opus 5.5", "Gemini 3.8 Flash") to AGY wire ID
  let slug = lower.replace(/\s+/g, '-')
  slug = slug.replace(/^claude-(opus|sonnet|haiku)-(\d+)\.(\d+)$/, 'claude-$1-$2-$3')
  return { modelId: slug || undefined, effort }
}

export function readAgyLocalSettings(
  homeDir = os.homedir(),
  catalog?: EngineModelSpec[],
): {
  model?: string
  effort: ReasoningEffort
  defaultMode: SessionMode
} {
  const settingsPath = path.join(homeDir, '.gemini', 'antigravity-cli', 'settings.json')
  try {
    if (fs.existsSync(settingsPath)) {
      const parsed = JSON.parse(fs.readFileSync(settingsPath, 'utf8')) as {
        model?: string
        permissions?: { defaultMode?: string }
      }
      const resolved = parseAgyModelString(parsed.model, catalog)
      return {
        model: resolved.modelId,
        effort: resolved.effort ?? 'high',
        defaultMode: normalizeHostMode(parsed.permissions?.defaultMode, 'auto'),
      }
    }
  } catch {
    // ignore
  }
  return { effort: 'high', defaultMode: 'auto' }
}
