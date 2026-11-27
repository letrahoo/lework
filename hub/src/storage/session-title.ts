import path from 'node:path'
import type { AgentEvent, SessionMeta, WireApi } from '@lework/protocol'
import {
  normalizeBaseUrlForAnthropic,
  normalizeBaseUrlForOpenAi,
} from '../registry/provider-adapters.ts'
import { parseStagedImagePromptHeader } from './attachment-store.ts'
import type { AutoTitleSource } from './session-metadata.ts'

export interface ResolvedTitleModelCandidate {
  providerId: string
  providerName: string
  baseUrl: string
  apiKey?: string
  wireApis: WireApi[]
  modelId: string
}

export interface SessionTitleSignals {
  scope?: string
  primaryPath: string
  candidateModules: string[]
  touchedFiles: string[]
  userPrompts: string[]
  planHints: string[]
  assistantSummary?: string
  signalCount: number
}

export interface GeneratedSessionTitle {
  title: string
  source: AutoTitleSource
  signalCount: number
}

const GENERIC_DIR_NAMES = new Set([
  'users',
  'home',
  'root',
  'tmp',
  'var',
  'opt',
  'etc',
  'work',
  'personal',
  'projects',
  'project',
  'repos',
  'repo',
  'code',
  'codes',
  'my-projects',
  'developer',
  'workspace',
  'workspaces',
  'desktop',
  'documents',
  'downloads',
])

const BOILERPLATE_PATH_SEGMENTS = new Set([
  'src',
  'source',
  'sources',
  'app',
  'apps',
  'shared',
  'common',
  'commonmain',
  'jvmmain',
  'androidmain',
  'iosmain',
  'commontest',
  'jvmtest',
  'androidtest',
  'iostest',
  'kotlin',
  'java',
  'scala',
  'swift',
  'objc',
  'ts',
  'js',
  'tsx',
  'jsx',
  'com',
  'dev',
  'org',
  'io',
  'net',
  'cn',
  'lib',
  'libs',
  'pkg',
  'packages',
  'package',
  'internal',
  'cmd',
  'bin',
  'main',
  'index',
  'mod',
  'module',
  'modules',
  'test',
  'tests',
  'spec',
  'specs',
  '__tests__',
  'fixtures',
  'dist',
  'build',
  'out',
  'target',
  'scripts',
  'script',
  'assets',
  'resources',
  'res',
  'public',
  'static',
  'components',
  'component',
  'utils',
  'util',
  'helpers',
  'helper',
  'types',
  'type',
  'models',
  'model',
  'views',
  'view',
  'pages',
  'page',
  'node_modules',
  '.git',
  '.github',
])

const GENERIC_FILLER_MODULES = new Set([
  '通用',
  '默认',
  '其他',
  '其它',
  '全局',
  '综合',
  '杂项',
  '未分类',
  '模块',
  '项目',
  '功能',
  '无',
  'general',
  'misc',
  'other',
  'default',
  'common',
  'global',
  'none',
  'null',
  'n/a',
  'root',
  'src',
  'main',
  'index',
])

const ACK_TOKEN_SOURCE =
  '(?:好的?|行|嗯|可以|没问题|确认|同意|继续|开始|推进|执行|继续推进|开始吧|继续吧|收到|明白|了解|谢谢|感谢|棒|赞|ok|okay|yes|yep|yeah|sure|go|go ahead|continue|proceed|lgtm|approved?|thanks?|thx|done|y|n|\\d+)'

const ACK_ONLY_PROMPT_RE = new RegExp(
  `^${ACK_TOKEN_SOURCE}(?:[，,、。！!.\\s]+${ACK_TOKEN_SOURCE}){0,3}[。！!.,，\\s]*$`,
  'i',
)

const KEYWORD_MODULE_RULES: Array<{ re: RegExp; module: string; weight: number }> = [
  { re: /侧边栏|sidebar|sessiondrawer|drawer/i, module: 'sidebar', weight: 4 },
  { re: /自动标题|会话标题|重命名|session-?title|rename/i, module: 'session', weight: 3 },
  { re: /全文搜索|会话搜索|fts5|search-?index|in-?session\s*find/i, module: 'search', weight: 4 },
  { re: /tsnet|tailscale|配对|pair|oauth|证书|tls|指纹|auth/i, module: 'auth', weight: 3 },
  { re: /protocol-?schema|协议|ProtocolModels|json-?schema/i, module: 'protocol', weight: 4 },
  { re: /worktree|工作树|独立分支/i, module: 'worktree', weight: 4 },
  { re: /checkpoint|快照|git\s*diff|代码变更/i, module: 'diff', weight: 3 },
  { re: /subagent|子任务|子代理|swarm/i, module: 'subagent', weight: 4 },
  { re: /artifact|产物|plan-?steps|执行步骤/i, module: 'artifacts', weight: 4 },
  { re: /usage|用量|额度|quota|token\s*消耗/i, module: 'usage', weight: 4 },
  { re: /provider|模型服务|cpa|model-?registry|模型选择/i, module: 'provider', weight: 3 },
  { re: /composer|输入框|草稿|draft|slash\s*command|斜杠命令/i, module: 'composer', weight: 4 },
  { re: /timeline|时间线|消息渲染|markdown|mermaid|latex/i, module: 'timeline', weight: 4 },
  { re: /terminal-?bridge|tmux|iterm|终端桥接/i, module: 'terminal', weight: 4 },
  { re: /approval|权限审批|待审批|permission/i, module: 'approval', weight: 4 },
  { re: /changelog|发版|release|dmg|更新提醒|updater/i, module: 'release', weight: 3 },
  { re: /\bagy\b|antigravity/i, module: 'agy', weight: 3 },
  { re: /\bclaude\b/i, module: 'claude', weight: 2 },
  { re: /\bcodex\b/i, module: 'codex', weight: 2 },
  { re: /\bandroid\b|apk/i, module: 'android', weight: 3 },
  { re: /\bios\b|swift|xcode|签名/i, module: 'ios', weight: 3 },
  { re: /desktop|桌面端|macos/i, module: 'desktop', weight: 2 },
  { re: /\bhub\b|守护进程|websocket|rpc/i, module: 'hub', weight: 2 },
]

function normalizeSlashes(raw: string): string {
  return raw.replace(/\\/g, '/').replace(/\/+/g, '/').replace(/\/+$/, '') || '/'
}

function isHomeOrGenericPath(normalizedPath: string, homeDir?: string): boolean {
  if (!normalizedPath || normalizedPath === '/') return true
  if (homeDir) {
    const normHome = normalizeSlashes(homeDir)
    if (normalizedPath === normHome) return true
  }
  if (/^\/(?:Users|home)\/[^/]+$/i.test(normalizedPath)) return true
  const base = path.posix.basename(normalizedPath).toLowerCase()
  return GENERIC_DIR_NAMES.has(base)
}

export function inferProjectScope(meta: SessionMeta, homeDir?: string): { scope?: string; primaryPath: string } {
  const candidates = [
    meta.worktree?.baseRepoPath,
    ...(meta.workspaces ?? []),
    meta.cwd,
  ]
    .map(p => (typeof p === 'string' ? p.trim() : ''))
    .filter(Boolean)

  for (const raw of candidates) {
    const norm = normalizeSlashes(raw)
    if (isHomeOrGenericPath(norm, homeDir)) continue
    let base = path.posix.basename(norm)
    // Strip managed worktree suffixes if someone passed a worktree checkout path directly
    base = base.replace(/[-_](?:wt|worktree)[-_][a-z0-9-]+$/i, '')
    base = base.replace(/\s+/g, '-').replace(/^-+|-+$/g, '')
    if (!base || GENERIC_DIR_NAMES.has(base.toLowerCase())) continue
    return {
      scope: base.slice(0, 18),
      primaryPath: norm,
    }
  }

  return {
    scope: undefined,
    primaryPath: candidates[0] ? normalizeSlashes(candidates[0]) : '',
  }
}

function cleanUserPromptText(raw: string): string {
  const strippedImages = parseStagedImagePromptHeader(raw, false).cleanText
  const cleaned = strippedImages
    .replace(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/gi, '$1')
    .replace(/<(?:SYSTEM_MESSAGE|ADDITIONAL_METADATA|USER_SETTINGS_CHANGE|command-message|local-command-[a-z-]+)>[\s\S]*?<\/[A-Za-z0-9_-]+>/gi, ' ')
    .replace(/<[^>]{1,80}>/g, ' ')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/https?:\/\/\S+/g, ' ')
    .replace(/\r\n/g, '\n')
    .trim()
  return cleaned
}

function isSubstantiveUserPrompt(text: string): boolean {
  const compact = text.replace(/\s+/g, ' ').trim()
  if (compact.length < 3) return false
  if (ACK_ONLY_PROMPT_RE.test(compact)) return false
  if (compact.startsWith('（已发送') && compact.endsWith('张图片）')) return false
  if (/^网络已恢复，请基于刚才的进度继续/.test(compact)) return false
  return true
}

function collectPathsFromToolCall(ev: Extract<AgentEvent, { type: 'tool.call' }>, out: string[]): void {
  if (Array.isArray(ev.locations)) {
    for (const loc of ev.locations) {
      if (typeof loc === 'string' && loc.trim()) out.push(loc.trim())
    }
  }
  const rawInput = ev.input?.trim()
  if (rawInput && rawInput.startsWith('{')) {
    try {
      const obj = JSON.parse(rawInput) as Record<string, unknown>
      for (const key of ['Path', 'path', 'file_path', 'TargetFile', 'filePath', 'AbsolutePath']) {
        const val = obj[key]
        if (typeof val === 'string' && val.trim()) {
          out.push(val.trim())
        }
      }
    } catch {
      // ignore non-JSON tool input
    }
  }
  if (ev.title) {
    const fileMatches = ev.title.match(/(?:[A-Za-z0-9_.-]+\/)+[A-Za-z0-9_.-]+\.[A-Za-z0-9]{1,8}/g)
    if (fileMatches) out.push(...fileMatches)
  }
}

function normalizeRelativeFilePath(rawPath: string, primaryPath: string): string | null {
  const norm = normalizeSlashes(rawPath)
  if (!norm || norm === '/') return null
  if (primaryPath && norm.startsWith(primaryPath + '/')) {
    return norm.slice(primaryPath.length + 1)
  }
  if (!norm.startsWith('/')) {
    return norm.replace(/^\.\//, '')
  }
  return null
}

function extractModuleTokenFromFileStem(fileName: string): string | null {
  const stem = fileName.replace(/\.[^.]+$/, '')
  if (!stem || stem.length < 3) return null
  // Convert CamelCase like DesktopSidebar / SessionMetadataStore -> tokens
  const camelTokens = stem
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .toLowerCase()
    .split(/[-_.\s]+/)
    .filter(Boolean)

  const meaningful = camelTokens.filter(
    t =>
      t.length >= 3 &&
      !BOILERPLATE_PATH_SEGMENTS.has(t) &&
      ![
        'desktop',
        'mobile',
        'console',
        'manager',
        'service',
        'store',
        'state',
        'client',
        'server',
        'adapter',
        'handler',
        'controller',
        'dialog',
        'sheet',
        'panel',
        'view',
        'views',
        'screen',
        'widget',
        'card',
        'row',
        'item',
        'list',
        'helper',
        'common',
        'base',
        'impl',
        'info',
        'meta',
        'metadata',
        'data',
        'config',
        'settings',
        'test',
        'tests',
        'lework',
      ].includes(t),
  )

  if (meaningful.length > 0) {
    return meaningful[0]!
  }
  if (camelTokens.includes('settings')) return 'settings'
  if (camelTokens.includes('config')) return 'config'
  return null
}

function scoreModulesFromSignals(
  touchedFiles: string[],
  userPrompts: string[],
  scope: string | undefined,
): string[] {
  const scores = new Map<string, number>()
  const scopeLower = scope?.toLowerCase()
  const addScore = (rawMod: string, weight: number) => {
    const clean = rawMod.trim().toLowerCase().replace(/[^a-z0-9\u4e00-\u9fa5_-]+/g, '')
    if (!clean || clean.length < 2 || clean.length > 16) return
    if (clean === scopeLower) return
    if (BOILERPLATE_PATH_SEGMENTS.has(clean) || GENERIC_FILLER_MODULES.has(clean)) return
    scores.set(clean, (scores.get(clean) ?? 0) + weight)
  }

  for (const rel of touchedFiles) {
    const parts = rel.split('/').filter(Boolean)
    if (parts.length === 0) continue
    const dirs = parts.slice(0, -1)
    const fileName = parts[parts.length - 1]!

    // Score meaningful directory segments (deeper specific subdirectories score slightly higher than broad buckets)
    const usefulDirs: string[] = []
    for (const seg of dirs) {
      const lower = seg.toLowerCase()
      if (lower.startsWith('.')) continue
      if (lower === scopeLower) continue
      if (BOILERPLATE_PATH_SEGMENTS.has(lower)) continue
      usefulDirs.push(lower)
    }

    for (let i = 0; i < usefulDirs.length; i++) {
      const seg = usefulDirs[i]!
      const normalizedSeg = seg.replace(/-schema$/, '').replace(/-bridge$/, '')
      const weight = seg === 'ui' || seg === 'storage' ? 1.5 : 2.5 + i * 0.6
      addScore(normalizedSeg || seg, weight)
    }

    const stemToken = extractModuleTokenFromFileStem(fileName)
    if (stemToken) {
      addScore(stemToken, 2.2)
    }
  }

  const combinedText = userPrompts.join('\n')
  for (const rule of KEYWORD_MODULE_RULES) {
    if (rule.re.test(combinedText)) {
      addScore(rule.module, rule.weight)
    }
  }

  return [...scores.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([name]) => name)
    .slice(0, 5)
}

function extractAssistantSummary(events: AgentEvent[]): string | undefined {
  const byMsg = new Map<string, string>()
  const order: string[] = []
  for (const ev of events) {
    if (ev.type !== 'message.assistant.delta') continue
    if (!byMsg.has(ev.messageId)) {
      order.push(ev.messageId)
      byMsg.set(ev.messageId, ev.text)
    } else {
      byMsg.set(ev.messageId, (byMsg.get(ev.messageId) ?? '') + ev.text)
    }
  }
  for (let i = order.length - 1; i >= 0; i--) {
    const raw = byMsg.get(order[i]!)?.trim()
    if (!raw || raw.length < 10) continue
    const cleaned = raw
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/[#>*`]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
    if (cleaned.length >= 10) {
      return cleaned.slice(0, 260)
    }
  }
  return undefined
}

export function extractSessionTitleSignals(
  meta: SessionMeta,
  events: AgentEvent[],
  homeDir?: string,
): SessionTitleSignals {
  const { scope, primaryPath } = inferProjectScope(meta, homeDir)
  const rawPaths: string[] = []
  const substantiveUsers: string[] = []
  const planHints: string[] = []

  for (const ev of events) {
    if (ev.type === 'message.user') {
      const cleaned = cleanUserPromptText(ev.text)
      if (isSubstantiveUserPrompt(cleaned)) {
        substantiveUsers.push(cleaned)
      }
      const pathMatches = cleaned.match(/(?:[A-Za-z0-9_.-]+\/)+[A-Za-z0-9_.-]+\.[A-Za-z0-9]{1,8}/g)
      if (pathMatches) rawPaths.push(...pathMatches)
    } else if (ev.type === 'tool.call') {
      collectPathsFromToolCall(ev, rawPaths)
    } else if (ev.type === 'plan.updated' && ev.entries.length > 0) {
      planHints.length = 0
      for (const entry of ev.entries.slice(0, 5)) {
        const clean = entry.content.replace(/\s+/g, ' ').trim()
        if (clean) planHints.push(clean.slice(0, 60))
      }
    }
  }

  // Deduplicate relative file paths preserving order
  const seenFiles = new Set<string>()
  const touchedFiles: string[] = []
  for (const p of rawPaths) {
    const rel = normalizeRelativeFilePath(p, primaryPath)
    if (!rel || seenFiles.has(rel)) continue
    seenFiles.add(rel)
    touchedFiles.push(rel)
    if (touchedFiles.length >= 24) break
  }

  // Keep first substantive user prompt + up to 3 latest distinct ones
  const selectedPrompts: string[] = []
  if (substantiveUsers.length > 0) {
    selectedPrompts.push(substantiveUsers[0]!)
    const tail = substantiveUsers.slice(1).slice(-3)
    for (const t of tail) {
      if (!selectedPrompts.includes(t)) selectedPrompts.push(t)
    }
  }

  const assistantSummary = extractAssistantSummary(events)
  const candidateModules = scoreModulesFromSignals(touchedFiles, selectedPrompts, scope)
  const signalCount =
    substantiveUsers.length + Math.min(touchedFiles.length, 6) + (assistantSummary ? 1 : 0)

  return {
    scope,
    primaryPath,
    candidateModules,
    touchedFiles: touchedFiles.slice(0, 10),
    userPrompts: selectedPrompts,
    planHints,
    assistantSummary,
    signalCount,
  }
}

function stripLeadingPoliteNoise(text: string): string {
  let s = text
    .replace(/[`*_~]/g, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/(?:\/[A-Za-z0-9_.-]+){2,}/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()

  const prefixPatterns = [
    /^(?:你好|嗨|hi|hello|hey)[,，!！\s]*/i,
    /^(?:请|麻烦|帮忙|帮我|请你|能否|能不能|可以|想要?|我想|我们需要|我要|需要|现在|目前|顺便|接下来|先|再|然后|这里|这个|针对|关于|基于|根据)[，,\s]*/g,
    /^(?:please|can you|could you|help me|i want to|i need to|let's|we need to)\s+/i,
    /^(?:帮我|请你|麻烦|需要)?(?:看一下|看看|检查一下|分析一下|排查一下|梳理一下|实现一下|支持一下|修改一下|优化一下|调整一下|处理一下|做一下|搞一下)\s*/,
    /^(?:支持|实现|添加|新增|增加)(?:以下|下面|如下)?(?:两个|三个|几个|多个|\d+个)?(?:功能|需求|特性|能力)[：:\s]*/,
  ]

  for (let pass = 0; pass < 3; pass++) {
    const before = s
    for (const re of prefixPatterns) {
      s = s.replace(re, '').trim()
    }
    if (s === before) break
  }

  return s
}

function trimConciseClause(rawClause: string, maxLen: number): string {
  let s = stripLeadingPoliteNoise(rawClause)
  // Cut at natural sentence / explanatory tail delimiters
  s = s.split(/[。！？!?\n]/)[0]?.trim() ?? s
  s = s.replace(/[，,；;：:、](?:不然|否则|因为|比如|例如|我的初步想法|类似|这样可以|以便|用于|避免|防止|确保).*$/u, '').trim()
  s = s.replace(/（[^）]{6,}）|\([^)]{10,}\)/g, '').trim()
  s = s.replace(/^[0-9]+[.)、．]\s*/, '').trim()
  s = s.replace(/[-—–·:：,，。！？!?；;…\s]+$/g, '').trim()
  if (!s) return ''
  if (s.length <= maxLen) return s

  // Prefer cutting at a clause comma if it keeps a meaningful phrase
  const commaParts = s.split(/[，,、]/).map(p => p.trim()).filter(Boolean)
  if (commaParts.length > 1 && commaParts[0]!.length >= 4 && commaParts[0]!.length <= maxLen) {
    return commaParts[0]!
  }
  return s.slice(0, maxLen).replace(/[-—–·:：,，、\s]+$/g, '').trim()
}

function extractConciseFeatureFromPrompt(prompt: string, maxLen = 16): string {
  const lines = prompt
    .split(/\r?\n/)
    .map(l => l.trim())
    .filter(Boolean)

  // Detect numbered multi-item requests like:
  // "支持两个功能：\n1. 根据会话上下文自动更新会话标题...\n2. 按目录/项目粒度聚合会话..."
  const numberedItems: string[] = []
  for (const line of lines) {
    const m = line.match(/^(?:[-*•]|[0-9]+[.)、．])\s*(.+)$/)
    if (m?.[1]) {
      const clause = trimConciseClause(m[1], 10)
      if (clause.length >= 2) numberedItems.push(clause)
    }
  }
  if (numberedItems.length >= 2) {
    const first = numberedItems[0]!
    const second = numberedItems[1]!
    const combined = `${first}与${second}`
    if (combined.length <= maxLen) return combined
    const compactFirst = first.slice(0, Math.min(7, first.length))
    const compactSecond = second.slice(0, Math.min(7, second.length))
    return `${compactFirst}与${compactSecond}`.slice(0, maxLen)
  }

  const primaryLine =
    lines.find(l => trimConciseClause(l, maxLen).length >= 3) ?? lines[0] ?? prompt
  return trimConciseClause(primaryLine, maxLen)
}

export function sanitizeStructuredTitle(
  raw: string,
  opts?: {
    scope?: string
    module?: string
    maxLength?: number
  },
): string {
  const maxLength = opts?.maxLength ?? 34
  let line = raw
    .split(/\r?\n/)
    .map(l => l.trim())
    .find(Boolean) ?? ''

  line = line
    .replace(/^(?:标题|会话标题|推荐标题|title|session\s*title)\s*[:：]\s*/i, '')
    .replace(/^[-*•\d.)]+\s+/, '')
    .replace(/^[`"'「『【\[《]+|[`"'」』】\]》]+$/g, '')
    .replace(/[。！？!?；;….,]+$/g, '')
    .trim()

  if (!line) return ''

  const scopeHint = opts?.scope?.trim()
  let prefixScope: string | undefined
  let remainder = line

  // Preserve hyphenated project scope names (e.g. `gecko-sdk-...`) as a single first segment
  if (scopeHint) {
    const lowerRem = remainder.toLowerCase()
    const lowerScope = scopeHint.toLowerCase()
    if (lowerRem === lowerScope) {
      prefixScope = scopeHint
      remainder = ''
    } else {
      const sepMatch = remainder.slice(scopeHint.length).match(/^\s*[-—–·/|：:]\s*/)
      if (lowerRem.startsWith(lowerScope) && sepMatch) {
        prefixScope = scopeHint
        remainder = remainder.slice(scopeHint.length + sepMatch[0].length)
      }
    }
  }

  const rawParts = remainder
    ? remainder.split(/\s*[-—–·/|：:]\s*/).map(p => p.trim()).filter(Boolean)
    : []
  const parts: string[] = prefixScope ? [prefixScope, ...rawParts] : rawParts

  // Deduplicate adjacent equal parts & drop empty/generic filler middle segments
  const cleanedParts: string[] = []
  for (let i = 0; i < parts.length; i++) {
    const part = parts[i]!.replace(/^[`"'「『]+|[`"'」』]+$/g, '').trim()
    if (!part) continue
    const lower = part.toLowerCase()
    const prevLower = cleanedParts[cleanedParts.length - 1]?.toLowerCase()
    if (prevLower && lower === prevLower) continue
    const isMiddle = i > 0 && i < parts.length - 1
    if (isMiddle && GENERIC_FILLER_MODULES.has(lower)) continue
    cleanedParts.push(part)
  }

  if (cleanedParts.length === 0) return ''

  // Collapse >3 segments into 3 segments (<Scope>-<Module>-<Feature>)
  let finalParts = cleanedParts
  if (finalParts.length > 3) {
    finalParts = [finalParts[0]!, finalParts[1]!, finalParts.slice(2).join('')]
  }

  // Ensure the final feature segment has no trailing punctuation
  const lastIdx = finalParts.length - 1
  finalParts[lastIdx] = finalParts[lastIdx]!
    .replace(/[。！？!?；;….,，\s]+$/g, '')
    .trim()
  finalParts = finalParts.filter(Boolean)
  if (finalParts.length === 0) return ''

  let joined = finalParts.join('-')
  if (joined.length > maxLength) {
    if (finalParts.length >= 2) {
      const prefix = finalParts.slice(0, -1).join('-') + '-'
      const budget = maxLength - prefix.length
      if (budget >= 4) {
        joined = prefix + finalParts[finalParts.length - 1]!.slice(0, budget).replace(/[-—–·,，、\s]+$/g, '')
      } else {
        joined = joined.slice(0, maxLength).replace(/[-—–·,，、\s]+$/g, '')
      }
    } else {
      joined = joined.slice(0, maxLength).replace(/[-—–·,，、\s]+$/g, '')
    }
  }

  return joined.replace(/^-+|-+$/g, '').trim()
}

export function generateHeuristicSessionTitle(
  meta: SessionMeta,
  events: AgentEvent[],
  homeDir?: string,
): { title: string; signals: SessionTitleSignals } | null {
  const signals = extractSessionTitleSignals(meta, events, homeDir)
  const primaryPrompt =
    signals.userPrompts[signals.userPrompts.length - 1] ?? signals.userPrompts[0]
  const fallbackPrompt = signals.userPrompts[0]
  const rawFeatureSource =
    primaryPrompt ??
    signals.planHints[0] ??
    signals.assistantSummary

  if (!rawFeatureSource) return null

  const scope = signals.scope
  const topModule = signals.candidateModules[0]
  const prefixLen =
    (scope ? scope.length + 1 : 0) + (topModule ? topModule.length + 1 : 0)
  const featureBudget = Math.max(8, Math.min(16, 32 - prefixLen))

  let feature = extractConciseFeatureFromPrompt(rawFeatureSource, featureBudget)
  if ((!feature || feature.length < 2) && fallbackPrompt && fallbackPrompt !== rawFeatureSource) {
    feature = extractConciseFeatureFromPrompt(fallbackPrompt, featureBudget)
  }
  if (!feature || feature.length < 2) return null

  // Avoid repeating module name verbatim at start of feature if already identical
  const effectiveModule =
    topModule &&
    !GENERIC_FILLER_MODULES.has(topModule.toLowerCase()) &&
    feature.toLowerCase() !== topModule.toLowerCase()
      ? topModule
      : undefined

  const segments = [scope, effectiveModule, feature].filter((v): v is string => Boolean(v && v.trim()))
  const title = sanitizeStructuredTitle(segments.join('-'), {
    scope,
    module: effectiveModule,
    maxLength: 34,
  })
  if (!title) return null
  return { title, signals }
}

const TITLE_SYSTEM_PROMPT = [
  '你是编程助手的会话标题提炼引擎，请根据会话上下文输出一个极精简、高辨识度的多级标题。',
  '严格遵守以下规则：',
  '1. 优先输出 3 级格式：<项目Scope>-<项目模块>-<功能名称>（例如：lework-sidebar-项目分组与折叠、lework-hub-会话自动标题、gecko-sdk-离线包校验修复）。',
  '2. 如果任务是跨模块或全局性的，输出 2 级格式：<项目Scope>-<功能名称>（严禁编造“通用”“默认”“其他”等空洞模块名）。',
  '3. 若没有明确项目目录，可输出 <模块>-<功能名称> 或 <功能名称>。',
  '4. 总长度严格控制在 26 个字符以内；中文为主，项目名与技术模块名可用简洁英文。',
  '5. 只输出标题这一行本身，不要引号、不要“标题：”前缀、不要解释、不要末尾标点。',
].join('\n')

function buildLlmTitleUserMessage(signals: SessionTitleSignals, currentAutoTitle?: string): string {
  const lines: string[] = []
  if (signals.scope) {
    lines.push(`项目 Scope: ${signals.scope}`)
  }
  if (signals.primaryPath) {
    lines.push(`工作区路径: ${signals.primaryPath}`)
  }
  if (signals.candidateModules.length > 0) {
    lines.push(`候选模块: ${signals.candidateModules.join(', ')}`)
  }
  if (signals.touchedFiles.length > 0) {
    lines.push(`涉及文件:\n${signals.touchedFiles.slice(0, 8).map(f => `- ${f}`).join('\n')}`)
  }
  if (signals.userPrompts.length > 0) {
    lines.push(
      `用户诉求:\n${signals.userPrompts
        .slice(0, 4)
        .map((p, idx) => `${idx + 1}. ${p.replace(/\s+/g, ' ').trim().slice(0, 220)}`)
        .join('\n')}`,
    )
  }
  if (signals.planHints.length > 0) {
    lines.push(`执行计划: ${signals.planHints.slice(0, 4).join(' / ')}`)
  }
  if (signals.assistantSummary) {
    lines.push(`最新进展摘要: ${signals.assistantSummary.slice(0, 220)}`)
  }
  if (currentAutoTitle) {
    lines.push(`当前自动标题: ${currentAutoTitle}`)
  }
  lines.push('请输出更新后的精简多级会话标题（仅一行）：')
  return lines.join('\n')
}

export async function generateLlmSessionTitle(
  candidate: ResolvedTitleModelCandidate,
  signals: SessionTitleSignals,
  opts?: {
    currentAutoTitle?: string
    timeoutMs?: number
    signal?: AbortSignal
    fetchImpl?: typeof fetch
  },
): Promise<string | null> {
  const fetchFn = opts?.fetchImpl ?? fetch
  const userMessage = buildLlmTitleUserMessage(signals, opts?.currentAutoTitle)
  const ctl = new AbortController()
  const timeoutMs = opts?.timeoutMs ?? 7_000
  const timer = setTimeout(() => ctl.abort(), timeoutMs)
  timer.unref?.()
  const onExternalAbort = () => ctl.abort()
  opts?.signal?.addEventListener('abort', onExternalAbort, { once: true })

  try {
    let rawText: string | undefined
    const preferAnthropic =
      candidate.wireApis.length === 1 && candidate.wireApis[0] === 'anthropic-messages'
    const preferResponses =
      !preferAnthropic &&
      !candidate.wireApis.includes('openai-chat') &&
      candidate.wireApis.includes('openai-responses')

    if (preferAnthropic) {
      const res = await fetchFn(`${normalizeBaseUrlForAnthropic(candidate.baseUrl)}/v1/messages`, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'anthropic-version': '2023-06-01',
          ...(candidate.apiKey
            ? { 'x-api-key': candidate.apiKey, authorization: `Bearer ${candidate.apiKey}` }
            : {}),
        },
        body: JSON.stringify({
          model: candidate.modelId,
          max_tokens: 64,
          temperature: 0.2,
          system: TITLE_SYSTEM_PROMPT,
          messages: [{ role: 'user', content: userMessage }],
        }),
        signal: ctl.signal,
      })
      if (!res.ok) return null
      const data = (await res.json()) as { content?: Array<{ type?: string; text?: string }> }
      rawText = (data.content ?? [])
        .filter(b => b.type === 'text' && typeof b.text === 'string')
        .map(b => b.text)
        .join('\n')
    } else if (preferResponses) {
      const res = await fetchFn(`${normalizeBaseUrlForOpenAi(candidate.baseUrl)}/responses`, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          ...(candidate.apiKey ? { authorization: `Bearer ${candidate.apiKey}` } : {}),
        },
        body: JSON.stringify({
          model: candidate.modelId,
          instructions: TITLE_SYSTEM_PROMPT,
          input: userMessage,
          max_output_tokens: 64,
        }),
        signal: ctl.signal,
      })
      if (!res.ok) return null
      const data = (await res.json()) as {
        output_text?: string
        output?: Array<{ content?: Array<{ type?: string; text?: string }> }>
      }
      rawText =
        data.output_text ??
        (data.output ?? [])
          .flatMap(o => o.content ?? [])
          .filter(c => typeof c.text === 'string')
          .map(c => c.text)
          .join('\n')
    } else {
      const res = await fetchFn(`${normalizeBaseUrlForOpenAi(candidate.baseUrl)}/chat/completions`, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          ...(candidate.apiKey ? { authorization: `Bearer ${candidate.apiKey}` } : {}),
        },
        body: JSON.stringify({
          model: candidate.modelId,
          stream: false,
          temperature: 0.2,
          max_tokens: 64,
          messages: [
            { role: 'system', content: TITLE_SYSTEM_PROMPT },
            { role: 'user', content: userMessage },
          ],
        }),
        signal: ctl.signal,
      })
      if (!res.ok) return null
      const data = (await res.json()) as {
        choices?: Array<{ message?: { content?: string } }>
      }
      rawText = data.choices?.[0]?.message?.content
    }

    if (!rawText || !rawText.trim()) return null
    const sanitized = sanitizeStructuredTitle(rawText, {
      scope: signals.scope,
      module: signals.candidateModules[0],
      maxLength: 34,
    })
    return sanitized || null
  } catch {
    return null
  } finally {
    clearTimeout(timer)
    opts?.signal?.removeEventListener('abort', onExternalAbort)
  }
}

export function shouldAttemptAutoTitleRefresh(opts: {
  hasCustomTitle: boolean
  existingAutoTitle?: string
  existingSource?: AutoTitleSource
  existingSignalCount?: number
  lastUpdatedAt?: number
  currentSignalCount: number
  hasLlmCandidate: boolean
  force?: boolean
  now?: number
}): { shouldRunHeuristic: boolean; shouldRunLlm: boolean } {
  if (opts.hasCustomTitle && !opts.force) {
    return { shouldRunHeuristic: false, shouldRunLlm: false }
  }
  if (opts.currentSignalCount <= 0) {
    return { shouldRunHeuristic: false, shouldRunLlm: false }
  }
  if (opts.force) {
    return { shouldRunHeuristic: true, shouldRunLlm: opts.hasLlmCandidate }
  }

  const now = opts.now ?? Date.now()
  const prevCount = opts.existingSignalCount ?? 0
  const hasExisting = Boolean(opts.existingAutoTitle?.trim())
  const evolvedSignificantly = opts.currentSignalCount >= prevCount + 2
  const cooldownElapsed = !opts.lastUpdatedAt || now - opts.lastUpdatedAt >= 30_000

  const shouldRunHeuristic =
    !hasExisting || (opts.existingSource !== 'llm' && evolvedSignificantly)

  const shouldRunLlm =
    opts.hasLlmCandidate &&
    (!hasExisting ||
      opts.existingSource !== 'llm' ||
      (evolvedSignificantly && cooldownElapsed))

  return { shouldRunHeuristic, shouldRunLlm }
}
