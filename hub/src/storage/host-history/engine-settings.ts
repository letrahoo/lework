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

export function readClaudeLocalSettings(homeDir = os.homedir()): {
  model?: string
  defaultMode: SessionMode
  effort: ReasoningEffort
  usesLocalProxy: boolean
} {
  const settingsPath = path.join(homeDir, '.claude', 'settings.json')
  try {
    if (fs.existsSync(settingsPath)) {
      const parsed = JSON.parse(fs.readFileSync(settingsPath, 'utf8')) as {
        model?: string
        defaultMode?: string
        effortLevel?: string
        env?: Record<string, string>
        permissions?: { defaultMode?: string }
      }
      const rawMode = parsed.permissions?.defaultMode ?? parsed.defaultMode
      const baseUrl = parsed.env?.ANTHROPIC_BASE_URL ?? ''
      return {
        model: typeof parsed.model === 'string' && parsed.model.trim() ? parsed.model.trim() : undefined,
        defaultMode: normalizeHostMode(rawMode, 'default'),
        effort: normalizeHostEffort(parsed.effortLevel, 'medium'),
        usesLocalProxy: baseUrl.includes('127.0.0.1') || baseUrl.includes('localhost'),
      }
    }
  } catch {
    // ignore
  }
  return { defaultMode: 'default', effort: 'medium', usesLocalProxy: false }
}

export function readCodexLocalSettings(homeDir = os.homedir()): {
  model?: string
  defaultMode: SessionMode
  effort: ReasoningEffort
} {
  const tomlPath = path.join(homeDir, '.codex', 'config.toml')
  try {
    if (fs.existsSync(tomlPath)) {
      const raw = fs.readFileSync(tomlPath, 'utf8')
      const modelMatch = raw.match(/^\s*model\s*=\s*["']([^"']+)["']/m)
      const effortMatch = raw.match(/^\s*model_reasoning_effort\s*=\s*["']([^"']+)["']/m)
      const approvalMatch = raw.match(/^\s*approval_policy\s*=\s*["']([^"']+)["']/m)
      return {
        model: modelMatch?.[1]?.trim() || undefined,
        defaultMode: normalizeHostMode(approvalMatch?.[1], 'default'),
        effort: normalizeHostEffort(effortMatch?.[1], 'medium'),
      }
    }
  } catch {
    // ignore
  }
  return { defaultMode: 'default', effort: 'medium' }
}

export function readCodexModelsCache(homeDir: string, efforts: ReasoningEffort[]): EngineModelSpec[] {
  const p = path.join(homeDir, '.codex', 'models_cache.json')
  if (!fs.existsSync(p)) return []
  try {
    const data = JSON.parse(fs.readFileSync(p, 'utf8')) as {
      models?: Array<{ slug?: string; id?: string; display_name?: string }>
    }
    const out: EngineModelSpec[] = []
    for (const m of (data.models ?? []).slice(0, 15)) {
      const id = m.slug ?? m.id
      if (!id) continue
      out.push({
        id,
        name: m.display_name ?? id,
        providerId: 'sub_codex',
        efforts,
        defaultEffort: 'high',
      })
    }
    return out
  } catch {
    return []
  }
}

export function fallbackAgyModels(): EngineModelSpec[] {
  const eff: ReasoningEffort[] = ['low', 'medium', 'high']
  return [
    { id: 'gemini-4-carbon', name: 'Gemini 4 Carbon', providerId: 'sub_agy', efforts: eff, defaultEffort: 'high' },
    { id: 'gemini-4-argon', name: 'Gemini 4 Argon', providerId: 'sub_agy', efforts: eff, defaultEffort: 'high' },
    { id: 'claude-opus-5-5', name: 'Claude Opus 5.5', providerId: 'sub_agy', efforts: eff, defaultEffort: 'medium' },
    { id: 'claude-sonnet-5-5', name: 'Claude Sonnet 5.5', providerId: 'sub_agy', efforts: eff, defaultEffort: 'medium' },
  ]
}

export async function loadAgyModelsFromCli(): Promise<EngineModelSpec[]> {
  const bin = findExecutable('agy')
  if (!bin) return fallbackAgyModels()
  try {
    const { stdout } = await execFileAsync(bin, ['models'], {
      timeout: 8_000,
      maxBuffer: 512 * 1024,
      encoding: 'utf8',
    })
    const grouped = new Map<string, { name: string; efforts: Set<ReasoningEffort> }>()
    for (const line of stdout.split(/\r?\n/)) {
      const match = line.match(/^([A-Za-z0-9_.-]{1,128})\t([^\t]{1,256})$/)
      if (!match) continue
      const wireId = match[1]!
      const rawLabel = match[2]!
      const suffixMatch = wireId.match(/-(low|medium|high|xhigh|max)$/)
      const effort = suffixMatch?.[1] as ReasoningEffort | undefined
      const baseId = effort ? wireId.slice(0, -(effort.length + 1)) : wireId
      const cleanName = rawLabel.replace(/\s*\((Low|Medium|High|XHigh|Max)\)\s*$/i, '').trim()
      const item = grouped.get(baseId) ?? { name: cleanName, efforts: new Set<ReasoningEffort>() }
      if (effort) item.efforts.add(effort)
      grouped.set(baseId, item)
    }
    const specs: EngineModelSpec[] = [...grouped.entries()].map(([id, info]) => {
      const efforts = [...info.efforts]
      return {
        id,
        name: info.name,
        providerId: 'sub_agy',
        efforts: efforts.length > 0 ? efforts : undefined,
        defaultEffort: efforts.includes('high') ? 'high' : efforts[0],
      }
    })
    return specs.length > 0 ? specs : fallbackAgyModels()
  } catch {
    return fallbackAgyModels()
  }
}
