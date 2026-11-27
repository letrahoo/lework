import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { Buffer } from 'node:buffer'
import type { SlashCommandInfo, SlashCommandSource } from '@lework/protocol'
import { isSensitivePath } from '../security/path-guard.ts'

interface CommandSeed {
  name: string
  description: string
  inputHint?: string
  source: SlashCommandSource
}

const AGY_BUILTIN_COMMANDS: readonly CommandSeed[] = [
  {
    name: 'plan',
    description: '先梳理执行方案，确认后再动手改代码',
    inputHint: '任务描述',
    source: 'builtin',
  },
  {
    name: 'compact',
    description: '压缩当前对话上下文，释放窗口并保留核心进度',
    inputHint: '可选重点说明',
    source: 'builtin',
  },
  {
    name: 'goal',
    description: '设定清晰目标持续自主推进，直到完整达标',
    inputHint: '目标描述',
    source: 'builtin',
  },
  {
    name: 'boost',
    description: '启用深度思考、多视角核验与严格自查',
    inputHint: '复杂问题或需求',
    source: 'builtin',
  },
  {
    name: 'grill-me',
    description: '通过连续追问先对齐方案细节，再开始实施',
    inputHint: '待讨论的方案或需求',
    source: 'builtin',
  },
  {
    name: 'swarm',
    description: '启动多智能体并行协作，分工攻克复杂任务',
    inputHint: '协作目标',
    source: 'workflow',
  },
  {
    name: 'teamwork',
    description: '组织多代理团队分工协作完成大型任务',
    inputHint: '项目或任务说明',
    source: 'workflow',
  },
  {
    name: 'run-workflow',
    description: '通过工作流脚本批量并行编排多步代理任务',
    inputHint: '工作流目标',
    source: 'workflow',
  },
  {
    name: 'schedule',
    description: '设置定时提醒或周期性自动巡检任务',
    inputHint: '时间与执行内容',
    source: 'builtin',
  },
  {
    name: 'browser',
    description: '调用浏览器搜索、访问网页或验证页面交互',
    inputHint: '网址或操作目标',
    source: 'builtin',
  },
  {
    name: 'learn',
    description: '把当前总结出的规范或经验沉淀为长期习惯',
    inputHint: '要记住的规则',
    source: 'builtin',
  },
]

const CLAUDE_BUILTIN_COMMANDS: readonly CommandSeed[] = [
  {
    name: 'compact',
    description: '压缩当前对话上下文，保留关键结论继续推进',
    inputHint: '可选保留重点',
    source: 'builtin',
  },
  {
    name: 'plan',
    description: '先分析并给出实施计划，暂不修改文件',
    inputHint: '任务描述',
    source: 'builtin',
  },
  {
    name: 'review',
    description: '审查当前代码改动与潜在边界问题',
    inputHint: '可选 PR 或文件范围',
    source: 'builtin',
  },
  {
    name: 'init',
    description: '分析仓库结构并生成或更新项目协作说明',
    source: 'builtin',
  },
]

// Codex app-server `turn/start` sends user text verbatim without built-in slash command dispatch,
// so we expose only discovered installed skills plus local Lework shortcuts instead of non-functional placeholders.
const CODEX_BUILTIN_COMMANDS: readonly CommandSeed[] = []

const AGY_LOCALIZED_HINTS = new Map<string, CommandSeed>(
  AGY_BUILTIN_COMMANDS.map(c => [c.name.toLowerCase(), c]),
)
const CLAUDE_LOCALIZED_HINTS = new Map<string, CommandSeed>(
  CLAUDE_BUILTIN_COMMANDS.map(c => [c.name.toLowerCase(), c]),
)
const CODEX_LOCALIZED_HINTS = new Map<string, CommandSeed>(
  CODEX_BUILTIN_COMMANDS.map(c => [c.name.toLowerCase(), c]),
)

function builtinHintsForEngine(engineId: string): Map<string, CommandSeed> {
  switch (engineId) {
    case 'agy':
      return AGY_LOCALIZED_HINTS
    case 'claude':
      return CLAUDE_LOCALIZED_HINTS
    case 'codex':
      return CODEX_LOCALIZED_HINTS
    default:
      return new Map()
  }
}

const MAX_SCAN_ENTRIES = 128
const MAX_HEAD_BYTES = 4_096
const DISCOVERY_CACHE_TTL_MS = 10_000
const discoveryCache = new Map<string, { fetchedAt: number; commands: SlashCommandInfo[] }>()

function readSafeHeadUtf8(filePath: string, boundaryDir?: string): string | null {
  let realTarget: string
  try {
    realTarget = fs.realpathSync(filePath)
  } catch {
    return null
  }
  if (isSensitivePath(realTarget)) return null
  if (boundaryDir) {
    let realBoundary: string
    try {
      realBoundary = fs.realpathSync(boundaryDir)
    } catch {
      return null
    }
    const prefix = realBoundary.endsWith(path.sep) ? realBoundary : `${realBoundary}${path.sep}`
    if (realTarget !== realBoundary && !realTarget.startsWith(prefix)) {
      return null
    }
  }
  let fd: number | null = null
  try {
    // Reject FIFOs/sockets/devices BEFORE opening and also pass O_NONBLOCK so a TOCTOU swap
    // to a named pipe can never block the Hub event loop.
    if (!fs.lstatSync(realTarget).isFile()) return null
    const nonBlock = typeof fs.constants.O_NONBLOCK === 'number' ? fs.constants.O_NONBLOCK : 0
    fd = fs.openSync(realTarget, fs.constants.O_RDONLY | nonBlock)
    const stat = fs.fstatSync(fd)
    if (!stat.isFile()) return null
    const buf = Buffer.allocUnsafe(Math.min(stat.size, MAX_HEAD_BYTES))
    if (buf.length === 0) return ''
    const bytesRead = fs.readSync(fd, buf, 0, buf.length, 0)
    return buf.subarray(0, bytesRead).toString('utf8')
  } catch {
    return null
  } finally {
    if (fd !== null) {
      try {
        fs.closeSync(fd)
      } catch {
        // ignore
      }
    }
  }
}

function parseFrontmatterField(raw: string, key: string): string | undefined {
  const fmMatch = raw.match(/^---\r?\n([\s\S]*?)\r?\n---/)
  if (!fmMatch?.[1]) return undefined
  const lines = fmMatch[1].split(/\r?\n/)
  const prefix = `${key}:`
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!
    if (!line.toLowerCase().startsWith(prefix.toLowerCase())) continue
    const inline = line.slice(prefix.length).trim().replace(/^["']|["']$/g, '').trim()
    if (inline && inline !== '|' && inline !== '>') return inline
    const multi: string[] = []
    for (let j = i + 1; j < lines.length; j++) {
      const next = lines[j]!
      if (!/^\s+/.test(next)) break
      multi.push(next.trim())
    }
    const joined = multi.join(' ').trim()
    if (joined) return joined
  }
  return undefined
}

function cleanCommandName(raw: string): string {
  return raw.trim().replace(/^\/+/, '').replace(/\s+/g, '-').slice(0, 128)
}

function cleanDescription(raw: string): string {
  return raw.replace(/\s+/g, ' ').trim().slice(0, 320)
}

function scanSkillDirectory(dirPath: string, boundaryDir?: string): SlashCommandInfo[] {
  if (!dirPath || !fs.existsSync(dirPath)) return []
  let entries: fs.Dirent[]
  try {
    entries = fs.readdirSync(dirPath, { withFileTypes: true })
  } catch {
    return []
  }
  const out: SlashCommandInfo[] = []
  for (const entry of entries.slice(0, MAX_SCAN_ENTRIES)) {
    if (entry.name.startsWith('.')) continue
    if (!entry.isDirectory() && !entry.isSymbolicLink()) continue
    const skillMd = path.join(dirPath, entry.name, 'SKILL.md')
    try {
      const content = readSafeHeadUtf8(skillMd, boundaryDir)
      if (content === null) continue
      const name = cleanCommandName(parseFrontmatterField(content, 'name') || entry.name)
      const rawDesc = parseFrontmatterField(content, 'description')
      if (!name) continue
      out.push({
        name,
        description: cleanDescription(rawDesc || `调用扩展技能 ${name}`),
        inputHint: '补充需求（可选）',
        source: 'skill',
      })
    } catch {
      // ignore unreadable skill folder
    }
  }
  return out
}

function scanMarkdownCommandsDirectory(dirPath: string, boundaryDir?: string): SlashCommandInfo[] {
  if (!dirPath || !fs.existsSync(dirPath)) return []
  let entries: fs.Dirent[]
  try {
    entries = fs.readdirSync(dirPath, { withFileTypes: true })
  } catch {
    return []
  }
  const out: SlashCommandInfo[] = []
  for (const entry of entries.slice(0, MAX_SCAN_ENTRIES)) {
    if (entry.name.startsWith('.') || !entry.name.endsWith('.md')) continue
    const full = path.join(dirPath, entry.name)
    try {
      const content = readSafeHeadUtf8(full, boundaryDir)
      if (content === null) continue
      const stem = entry.name.slice(0, -3)
      const name = cleanCommandName(parseFrontmatterField(content, 'name') || stem)
      const rawDesc =
        parseFrontmatterField(content, 'description') ||
        content
          .split(/\r?\n/)
          .map(l => l.trim())
          .find(l => l.length > 0 && !l.startsWith('---') && !l.startsWith('#'))
      if (!name) continue
      out.push({
        name,
        description: cleanDescription(rawDesc || `执行自定义命令 /${name}`),
        inputHint: parseFrontmatterField(content, 'argument-hint') || undefined,
        source: 'skill',
      })
    } catch {
      // ignore
    }
  }
  return out
}

const SOURCE_ORDER: Record<SlashCommandSource, number> = {
  builtin: 0,
  workflow: 1,
  local: 2,
  skill: 3,
}

export function mergeSlashCommands(
  primary: SlashCommandInfo[],
  fallback: SlashCommandInfo[] = [],
  opts?: { localizeBuiltins?: boolean },
): SlashCommandInfo[] {
  const byKey = new Map<string, SlashCommandInfo>()
  const insert = (cmd: SlashCommandInfo, preferIncoming: boolean) => {
    const name = cleanCommandName(cmd.name)
    if (!name) return
    const key = name.toLowerCase()
    const localized = opts?.localizeBuiltins ? AGY_LOCALIZED_HINTS.get(key) : undefined
    const normalized: SlashCommandInfo = {
      name,
      description: cleanDescription(localized?.description || cmd.description || `执行 /${name}`),
      ...(localized?.inputHint || cmd.inputHint
        ? { inputHint: (localized?.inputHint || cmd.inputHint)!.trim().slice(0, 160) }
        : {}),
      source: localized?.source ?? cmd.source ?? 'skill',
    }
    if (preferIncoming || !byKey.has(key)) {
      byKey.set(key, normalized)
    }
  }

  for (const cmd of fallback) insert(cmd, false)
  for (const cmd of primary) insert(cmd, true)

  return [...byKey.values()].sort((a, b) => {
    const ao = SOURCE_ORDER[a.source ?? 'skill'] ?? 9
    const bo = SOURCE_ORDER[b.source ?? 'skill'] ?? 9
    if (ao !== bo) return ao - bo
    return a.name.localeCompare(b.name)
  })
}

export function parseAcpAvailableCommands(
  rawList: unknown,
  engineId: string,
): SlashCommandInfo[] {
  if (!Array.isArray(rawList)) return []
  const parsed: SlashCommandInfo[] = []
  for (const item of rawList) {
    if (!item || typeof item !== 'object') continue
    const obj = item as Record<string, unknown>
    const name = typeof obj.name === 'string' ? cleanCommandName(obj.name) : ''
    if (!name) continue
    const rawDesc = typeof obj.description === 'string' ? cleanDescription(obj.description) : ''
    const inputObj =
      obj.input && typeof obj.input === 'object' ? (obj.input as Record<string, unknown>) : null
    const inputHint =
      typeof inputObj?.hint === 'string' && inputObj.hint.trim()
        ? inputObj.hint.trim().slice(0, 160)
        : undefined
    const key = name.toLowerCase()
    const hints = builtinHintsForEngine(engineId)
    const knownSeed = hints.get(key)
    const source: SlashCommandSource = knownSeed
      ? knownSeed.source
      : engineId === 'agy' && (key === 'swarm' || key === 'run-workflow' || key === 'teamwork')
        ? 'workflow'
        : 'skill'
    parsed.push({
      name,
      description: rawDesc || `执行 /${name}`,
      ...(inputHint ? { inputHint } : {}),
      source,
    })
  }
  return mergeSlashCommands(parsed, [], { localizeBuiltins: engineId === 'agy' })
}

export function discoverEngineSlashCommands(
  engineId: string,
  opts?: { homeDir?: string; cwd?: string },
): SlashCommandInfo[] {
  const homeDir = opts?.homeDir ?? os.homedir()
  const cwd = opts?.cwd
  const cacheKey = `${engineId}\0${homeDir}\0${cwd ?? ''}`
  const now = Date.now()
  const cached = discoveryCache.get(cacheKey)
  if (cached && now - cached.fetchedAt < DISCOVERY_CACHE_TTL_MS) {
    return cached.commands
  }

  let result: SlashCommandInfo[] = []
  if (engineId === 'agy') {
    const skills = [
      ...scanSkillDirectory(path.join(homeDir, '.gemini', 'skills')),
      ...scanSkillDirectory(path.join(homeDir, '.agents', 'skills')),
      ...(cwd ? scanSkillDirectory(path.join(cwd, '.agents', 'skills'), cwd) : []),
    ]
    result = mergeSlashCommands([...AGY_BUILTIN_COMMANDS], skills, { localizeBuiltins: true })
  } else if (engineId === 'claude') {
    const claudeDir = process.env.CLAUDE_CONFIG_DIR ?? path.join(homeDir, '.claude')
    const extras = [
      ...scanMarkdownCommandsDirectory(path.join(claudeDir, 'commands')),
      ...scanSkillDirectory(path.join(claudeDir, 'skills')),
      ...(cwd ? scanMarkdownCommandsDirectory(path.join(cwd, '.claude', 'commands'), cwd) : []),
      ...(cwd ? scanSkillDirectory(path.join(cwd, '.claude', 'skills'), cwd) : []),
    ]
    result = mergeSlashCommands([...CLAUDE_BUILTIN_COMMANDS], extras)
  } else if (engineId === 'codex') {
    const codexDir = process.env.CODEX_HOME ?? path.join(homeDir, '.codex')
    const extras = [
      ...scanSkillDirectory(path.join(codexDir, 'skills')),
      ...scanSkillDirectory(path.join(homeDir, '.agents', 'skills')),
    ]
    result = mergeSlashCommands([...CODEX_BUILTIN_COMMANDS], extras)
  } else if (engineId === 'pi') {
    const extras = [
      ...scanSkillDirectory(path.join(homeDir, '.pi', 'agent', 'skills')),
      ...scanSkillDirectory(path.join(homeDir, '.agents', 'skills')),
    ]
    result = mergeSlashCommands([], extras)
  }

  if (discoveryCache.size > 64) {
    discoveryCache.clear()
  }
  discoveryCache.set(cacheKey, { fetchedAt: now, commands: result })
  return result
}
