import fs from 'node:fs'
import path from 'node:path'
import {
  ErrorCode,
  type AgentEvent,
  type ArtifactContentResult,
  type ArtifactInfo,
  type ArtifactKind,
  type ArtifactSource,
} from '@lework/protocol'
import { HubRpcError } from '../security/path-guard.ts'

const MAX_TEXT_ARTIFACT_BYTES = 128 * 1024
const MAX_IMAGE_ARTIFACT_BYTES = 1536 * 1024

const SENSITIVE_ARTIFACT_NAME_RE =
  /^(id_(rsa|dsa|ecdsa|ed25519)(_sk)?|\.netrc|\.npmrc|\.pypirc|\.git-credentials|\.?credentials(\.[\w.-]+)?|auth\.json|oauth_creds\.json|\.env(\.(?!example$|sample$|template$)[\w.-]+)?)$|\.(pem|key|p12|pfx|keystore|jks|kdbx)$/i

const ALLOWED_AGY_EXT_MIME: Record<string, string> = {
  '.md': 'text/markdown',
  '.markdown': 'text/markdown',
  '.txt': 'text/plain',
  '.html': 'text/html',
  '.htm': 'text/html',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.json': 'application/json',
  '.ts': 'text/typescript',
  '.tsx': 'text/tsx',
  '.js': 'text/javascript',
  '.kt': 'text/x-kotlin',
  '.py': 'text/x-python',
  '.go': 'text/x-go',
  '.rs': 'text/x-rust',
  '.sql': 'application/sql',
  '.yaml': 'application/yaml',
  '.yml': 'application/yaml',
}

const IMAGE_EXTS = new Set(['.png', '.jpg', '.jpeg', '.webp'])
const CODE_EXTS = new Set(['.json', '.ts', '.tsx', '.js', '.kt', '.py', '.go', '.rs', '.sql', '.yaml', '.yml', '.svg'])

export interface ArtifactSessionContext {
  sessionId: string
  engineId: string
  nativeId?: string
  homeDir: string
  events: AgentEvent[]
}

interface VirtualArtifactRecord {
  info: ArtifactInfo
  content: string
}

function isSafeSingleSegment(segment: string): boolean {
  if (!segment || segment.length > 240) return false
  if (segment === '.' || segment === '..') return false
  if (segment.includes('/') || segment.includes('\\') || segment.includes('\0')) return false
  return true
}

function isBlockedArtifactName(fileName: string): boolean {
  if (!isSafeSingleSegment(fileName)) return true
  if (fileName.startsWith('.')) return true
  if (fileName.endsWith('.metadata.json')) return true
  if (SENSITIVE_ARTIFACT_NAME_RE.test(fileName)) return true
  return false
}

function resolveContainedFile(rootDir: string, fileName: string): string {
  if (isBlockedArtifactName(fileName)) {
    throw new HubRpcError(ErrorCode.PathNotAllowed, 'Artifact file name is not allowed.')
  }
  let realRoot: string
  try {
    realRoot = fs.realpathSync(rootDir)
  } catch {
    throw new HubRpcError(ErrorCode.NotFound, 'Artifact directory does not exist.')
  }
  const candidate = path.join(realRoot, fileName)
  let realCandidate: string
  try {
    const st = fs.statSync(candidate)
    if (!st.isFile()) {
      throw new HubRpcError(ErrorCode.PathNotAllowed, 'Artifact target is not a regular file.')
    }
    realCandidate = fs.realpathSync(candidate)
  } catch (err) {
    if (err instanceof HubRpcError) throw err
    throw new HubRpcError(ErrorCode.NotFound, `Artifact file not found: ${fileName}`)
  }
  if (!realCandidate.startsWith(realRoot + path.sep)) {
    throw new HubRpcError(ErrorCode.PathNotAllowed, 'Artifact symlink escapes allowed directory.')
  }
  if (SENSITIVE_ARTIFACT_NAME_RE.test(path.basename(realCandidate))) {
    throw new HubRpcError(ErrorCode.PathNotAllowed, 'Access to sensitive artifact file is blocked.')
  }
  return realCandidate
}

function inferAgyKind(fileName: string, ext: string, metaArtifactType?: string): ArtifactKind {
  const upperType = metaArtifactType?.toUpperCase() ?? ''
  if (upperType.includes('IMPLEMENTATION_PLAN') || upperType.includes('PLAN')) return 'plan'
  if (upperType.includes('TASK')) return 'task_list'
  if (upperType.includes('WALKTHROUGH')) return 'walkthrough'

  const lower = fileName.toLowerCase()
  if (IMAGE_EXTS.has(ext)) return 'image'
  if (ext === '.html' || ext === '.htm') return 'html'
  if (lower.includes('plan') && (ext === '.md' || ext === '.markdown')) return 'plan'
  if ((lower === 'task.md' || lower.includes('todo') || lower.includes('checklist')) && ext === '.md') {
    return 'task_list'
  }
  if (lower.includes('walkthrough') || lower.includes('summary') || lower.includes('report')) {
    return 'walkthrough'
  }
  if (CODE_EXTS.has(ext)) return 'code'
  return 'other'
}

function extractMarkdownHeadingAndSnippet(filePath: string): { heading?: string; snippet?: string } {
  let fd: number | null = null
  try {
    fd = fs.openSync(filePath, 'r')
    const buf = Buffer.alloc(4096)
    const n = fs.readSync(fd, buf, 0, buf.length, 0)
    const text = buf.subarray(0, n).toString('utf8')
    const lines = text.split('\n').map(l => l.trim())
    let heading: string | undefined
    let snippet: string | undefined
    for (const line of lines) {
      if (!line) continue
      if (!heading && /^#{1,3}\s+/.test(line)) {
        heading = line.replace(/^#{1,3}\s+/, '').replace(/[*_`]/g, '').trim().slice(0, 96)
        continue
      }
      if (!snippet && !line.startsWith('#') && !line.startsWith('---') && !line.startsWith('```')) {
        snippet = line.replace(/^[>\-*+]\s+/, '').replace(/[*_`]/g, '').trim().slice(0, 140)
      }
      if (heading && snippet) break
    }
    return { heading, snippet }
  } catch {
    return {}
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

function extractHeadingFromMarkdownText(text: string): { heading?: string; snippet?: string } {
  const lines = text.slice(0, 4096).split('\n').map(l => l.trim())
  let heading: string | undefined
  let snippet: string | undefined
  for (const line of lines) {
    if (!line) continue
    if (!heading && /^#{1,3}\s+/.test(line)) {
      heading = line.replace(/^#{1,3}\s+/, '').replace(/[*_`]/g, '').trim().slice(0, 96)
      continue
    }
    if (!snippet && !line.startsWith('#') && !line.startsWith('---') && !line.startsWith('```')) {
      snippet = line.replace(/^[>\-*+]\s+/, '').replace(/[*_`]/g, '').trim().slice(0, 140)
    }
    if (heading && snippet) break
  }
  return { heading, snippet }
}

function readAgyArtifactMetadata(brainConvDir: string, fileName: string): {
  artifactType?: string
  summary?: string
  updatedAt?: number
} {
  const metaPath = path.join(brainConvDir, `${fileName}.metadata.json`)
  if (!fs.existsSync(metaPath)) return {}
  try {
    const raw = fs.readFileSync(metaPath, 'utf8')
    const parsed = JSON.parse(raw) as {
      artifactType?: unknown
      summary?: unknown
      updatedAt?: unknown
    }
    const updatedAt =
      typeof parsed.updatedAt === 'string'
        ? Date.parse(parsed.updatedAt) || undefined
        : typeof parsed.updatedAt === 'number'
          ? parsed.updatedAt
          : undefined
    return {
      artifactType: typeof parsed.artifactType === 'string' ? parsed.artifactType : undefined,
      summary: typeof parsed.summary === 'string' ? parsed.summary.trim().slice(0, 220) : undefined,
      updatedAt,
    }
  } catch {
    return {}
  }
}

function formatFallbackTitle(fileName: string, kind: ArtifactKind): string {
  const stem = path.basename(fileName, path.extname(fileName))
  const lower = stem.toLowerCase()
  if (lower === 'implementation_plan') return '实施方案'
  if (lower === 'task') return '任务清单'
  if (lower === 'walkthrough') return '验收总结'
  const cleaned = stem.replace(/[_-]+/g, ' ').trim()
  if (cleaned) return cleaned.slice(0, 96)
  if (kind === 'plan') return '执行方案'
  return fileName
}

export function countQuickAgyArtifacts(homeDir: string, nativeId: string): number {
  if (!isSafeSingleSegment(nativeId)) return 0
  const convDir = path.join(homeDir, '.gemini', 'antigravity-cli', 'brain', nativeId)
  let entries: fs.Dirent[]
  try {
    entries = fs.readdirSync(convDir, { withFileTypes: true })
  } catch {
    return 0
  }
  let count = 0
  for (const e of entries) {
    if (!e.isFile()) continue
    if (isBlockedArtifactName(e.name)) continue
    const ext = path.extname(e.name).toLowerCase()
    if (Object.hasOwn(ALLOWED_AGY_EXT_MIME, ext)) {
      count++
    }
  }
  return count
}

function collectAgyBrainArtifacts(ctx: ArtifactSessionContext): ArtifactInfo[] {
  if (ctx.engineId !== 'agy' || !ctx.nativeId || !isSafeSingleSegment(ctx.nativeId)) return []
  const convDir = path.join(ctx.homeDir, '.gemini', 'antigravity-cli', 'brain', ctx.nativeId)
  let entries: fs.Dirent[]
  try {
    entries = fs.readdirSync(convDir, { withFileTypes: true })
  } catch {
    return []
  }

  const out: ArtifactInfo[] = []
  for (const e of entries) {
    if (!e.isFile()) continue
    if (isBlockedArtifactName(e.name)) continue
    const ext = path.extname(e.name).toLowerCase()
    const mimeType = ALLOWED_AGY_EXT_MIME[ext]
    if (!mimeType) continue

    let realFile: string
    let st: fs.Stats
    try {
      realFile = resolveContainedFile(convDir, e.name)
      st = fs.statSync(realFile)
    } catch {
      continue
    }

    const meta = readAgyArtifactMetadata(convDir, e.name)
    const kind = inferAgyKind(e.name, ext, meta.artifactType)
    const mdInfo = ext === '.md' || ext === '.markdown' ? extractMarkdownHeadingAndSnippet(realFile) : {}
    const title = mdInfo.heading || formatFallbackTitle(e.name, kind)
    const summary = meta.summary || mdInfo.snippet

    out.push({
      id: `agy_file:${e.name}`,
      sessionId: ctx.sessionId,
      kind,
      title,
      ...(summary ? { summary } : {}),
      fileName: e.name,
      mimeType,
      sizeBytes: st.size,
      updatedAt: Math.floor(meta.updatedAt ?? st.mtimeMs),
      source: 'agy_brain',
      ...(kind === 'plan' ? { actionable: true } : {}),
    })
  }
  return out
}

function extractReferencedClaudePlanFiles(events: AgentEvent[]): string[] {
  const names = new Set<string>()
  const planPathRe = /\.claude[\\/]plans[\\/]([A-Za-z0-9._-]+\.md)/gi
  for (const ev of events) {
    if (ev.type === 'tool.call') {
      const haystacks = [ev.input ?? '', ev.output ?? '', ...(ev.locations ?? [])]
      for (const h of haystacks) {
        if (!h || !h.includes('.claude')) continue
        let m: RegExpExecArray | null
        while ((m = planPathRe.exec(h)) !== null) {
          if (m[1] && isSafeSingleSegment(m[1])) names.add(m[1])
        }
      }
    } else if (ev.type === 'tool.update' && ev.output && ev.output.includes('.claude')) {
      let m: RegExpExecArray | null
      while ((m = planPathRe.exec(ev.output)) !== null) {
        if (m[1] && isSafeSingleSegment(m[1])) names.add(m[1])
      }
    }
  }
  return [...names]
}

function collectClaudePlanFiles(ctx: ArtifactSessionContext): ArtifactInfo[] {
  if (ctx.engineId !== 'claude') return []
  const plansDir = path.join(ctx.homeDir, '.claude', 'plans')
  if (!fs.existsSync(plansDir)) return []

  const referenced = extractReferencedClaudePlanFiles(ctx.events)
  if (referenced.length === 0) return []

  const out: ArtifactInfo[] = []
  for (const fileName of referenced) {
    try {
      const realFile = resolveContainedFile(plansDir, fileName)
      const st = fs.statSync(realFile)
      const mdInfo = extractMarkdownHeadingAndSnippet(realFile)
      out.push({
        id: `claude_plan:${fileName}`,
        sessionId: ctx.sessionId,
        kind: 'plan',
        title: mdInfo.heading || formatFallbackTitle(fileName, 'plan'),
        ...(mdInfo.snippet ? { summary: mdInfo.snippet } : {}),
        fileName,
        mimeType: 'text/markdown',
        sizeBytes: st.size,
        updatedAt: Math.floor(st.mtimeMs),
        source: 'claude_plan',
        actionable: true,
      })
    } catch {
      // ignore missing/invalid plan file
    }
  }
  return out
}

function collectVirtualArtifacts(ctx: ArtifactSessionContext): VirtualArtifactRecord[] {
  const out: VirtualArtifactRecord[] = []
  let latestPlanEntries: Array<{ content: string; status: string }> = []
  let latestPlanTs = 0

  for (const ev of ctx.events) {
    if (ev.type === 'plan.updated') {
      if (ev.entries.length > 0) {
        latestPlanEntries = ev.entries
        latestPlanTs = ev.ts
      }
      continue
    }
    if (ev.type !== 'tool.call' || !ev.input) continue
    const lowerTitle = ev.title.toLowerCase()
    const isExitPlan = lowerTitle.includes('exitplanmode') || lowerTitle === 'exit_plan_mode'
    const isClaudeArtifactTool =
      lowerTitle === 'create_artifact' ||
      lowerTitle === 'update_artifact' ||
      lowerTitle === 'write_artifact' ||
      lowerTitle === 'artifact'

    if (!isExitPlan && !isClaudeArtifactTool) continue

    let parsed: Record<string, unknown> | null = null
    try {
      const obj = JSON.parse(ev.input) as unknown
      if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
        parsed = obj as Record<string, unknown>
      }
    } catch {
      parsed = null
    }

    if (isExitPlan) {
      const planMarkdown =
        (typeof parsed?.plan === 'string' && parsed.plan.trim()) ||
        (typeof parsed?.content === 'string' && parsed.content.trim()) ||
        ''
      if (planMarkdown.length >= 16) {
        const mdInfo = extractHeadingFromMarkdownText(planMarkdown)
        const id = `virtual:claude_exit_plan:${ev.toolCallId}`
        out.push({
          info: {
            id,
            sessionId: ctx.sessionId,
            kind: 'plan',
            title: mdInfo.heading || 'Proposed Plan',
            ...(mdInfo.snippet ? { summary: mdInfo.snippet } : {}),
            mimeType: 'text/markdown',
            sizeBytes: Buffer.byteLength(planMarkdown, 'utf8'),
            updatedAt: ev.ts,
            source: 'claude_plan',
            actionable: true,
          },
          content: planMarkdown,
        })
      }
      continue
    }

    if (isClaudeArtifactTool && parsed) {
      const rawContent =
        (typeof parsed.content === 'string' && parsed.content) ||
        (typeof parsed.code === 'string' && parsed.code) ||
        (typeof parsed.html === 'string' && parsed.html) ||
        (typeof parsed.markdown === 'string' && parsed.markdown) ||
        ''
      if (!rawContent.trim()) continue
      const rawType = String(parsed.type ?? parsed.mimeType ?? parsed.language ?? '').toLowerCase()
      const kind: ArtifactKind =
        rawType.includes('html')
          ? 'html'
          : rawType.includes('markdown') || rawType === 'md'
            ? 'other'
            : rawType.includes('plan')
              ? 'plan'
              : 'code'
      const mimeType =
        kind === 'html'
          ? 'text/html'
          : rawType.includes('markdown') || rawType === 'md'
            ? 'text/markdown'
            : 'text/plain'
      const title =
        (typeof parsed.title === 'string' && parsed.title.trim()) ||
        (typeof parsed.name === 'string' && parsed.name.trim()) ||
        'Claude Artifact'
      const summary =
        (typeof parsed.summary === 'string' && parsed.summary.trim().slice(0, 160)) ||
        extractHeadingFromMarkdownText(rawContent).snippet
      out.push({
        info: {
          id: `virtual:claude_artifact:${ev.toolCallId}`,
          sessionId: ctx.sessionId,
          kind,
          title: title.slice(0, 96),
          ...(summary ? { summary } : {}),
          ...(typeof parsed.fileName === 'string' ? { fileName: parsed.fileName } : {}),
          mimeType,
          sizeBytes: Buffer.byteLength(rawContent, 'utf8'),
          updatedAt: ev.ts,
          source: 'claude_artifact',
          ...(kind === 'plan' ? { actionable: true } : {}),
        },
        content: rawContent,
      })
    }
  }

  if (latestPlanEntries.length > 0) {
    const done = latestPlanEntries.filter(e => e.status === 'completed').length
    const total = latestPlanEntries.length
    const lines = [
      '# 执行计划清单',
      '',
      ...latestPlanEntries.map(e => {
        const mark = e.status === 'completed' ? 'x' : ' '
        const suffix = e.status === 'in_progress' ? ' *(进行中)*' : ''
        return `- [${mark}] ${e.content}${suffix}`
      }),
    ]
    const md = lines.join('\n')
    const source: ArtifactSource = ctx.engineId === 'codex' ? 'codex_plan' : 'session_plan'
    out.push({
      info: {
        id: 'virtual:session_plan',
        sessionId: ctx.sessionId,
        kind: 'task_list',
        title: '执行计划清单',
        summary: `已完成 ${done}/${total} 项`,
        mimeType: 'text/markdown',
        sizeBytes: Buffer.byteLength(md, 'utf8'),
        updatedAt: latestPlanTs || Date.now(),
        source,
      },
      content: md,
    })
  }

  return out
}

const KIND_ORDER: Record<ArtifactKind, number> = {
  plan: 0,
  task_list: 1,
  walkthrough: 2,
  html: 3,
  image: 4,
  code: 5,
  other: 6,
}

export function listSessionArtifacts(ctx: ArtifactSessionContext): ArtifactInfo[] {
  const byId = new Map<string, ArtifactInfo>()
  for (const a of collectAgyBrainArtifacts(ctx)) byId.set(a.id, a)
  for (const a of collectClaudePlanFiles(ctx)) byId.set(a.id, a)
  for (const v of collectVirtualArtifacts(ctx)) {
    // Avoid duplicating a virtual `session_plan` if AGY already has a native `task_list` artifact
    if (
      v.info.id === 'virtual:session_plan' &&
      [...byId.values()].some(existing => existing.kind === 'task_list')
    ) {
      continue
    }
    byId.set(v.info.id, v.info)
  }

  return [...byId.values()].sort((a, b) => {
    const ka = KIND_ORDER[a.kind] ?? 9
    const kb = KIND_ORDER[b.kind] ?? 9
    if (ka !== kb) return ka - kb
    return b.updatedAt - a.updatedAt
  })
}

export function readSessionArtifact(
  ctx: ArtifactSessionContext,
  artifactId: string,
): ArtifactContentResult {
  if (!artifactId || artifactId.length > 512) {
    throw new HubRpcError(ErrorCode.InvalidParams, 'Invalid artifactId.')
  }

  if (artifactId.startsWith('agy_file:')) {
    if (ctx.engineId !== 'agy' || !ctx.nativeId || !isSafeSingleSegment(ctx.nativeId)) {
      throw new HubRpcError(ErrorCode.NotFound, 'AGY artifact directory is unavailable for this session.')
    }
    const fileName = artifactId.slice('agy_file:'.length)
    const ext = path.extname(fileName).toLowerCase()
    const mimeType = ALLOWED_AGY_EXT_MIME[ext]
    if (!mimeType) {
      throw new HubRpcError(ErrorCode.PathNotAllowed, 'Artifact file extension is not permitted.')
    }
    const convDir = path.join(ctx.homeDir, '.gemini', 'antigravity-cli', 'brain', ctx.nativeId)
    const realFile = resolveContainedFile(convDir, fileName)
    const st = fs.statSync(realFile)
    const meta = readAgyArtifactMetadata(convDir, fileName)
    const kind = inferAgyKind(fileName, ext, meta.artifactType)

    if (IMAGE_EXTS.has(ext)) {
      if (st.size > MAX_IMAGE_ARTIFACT_BYTES) {
        throw new HubRpcError(ErrorCode.InvalidParams, 'Image artifact exceeds 1.5 MB preview limit.')
      }
      const data = fs.readFileSync(realFile)
      const artifact: ArtifactInfo = {
        id: artifactId,
        sessionId: ctx.sessionId,
        kind,
        title: formatFallbackTitle(fileName, kind),
        ...(meta.summary ? { summary: meta.summary } : {}),
        fileName,
        mimeType,
        sizeBytes: st.size,
        updatedAt: Math.floor(meta.updatedAt ?? st.mtimeMs),
        source: 'agy_brain',
      }
      return {
        artifact,
        content: data.toString('base64'),
        isBase64: true,
        truncated: false,
      }
    }

    const { text, truncated } = readBoundedUtf8(realFile, MAX_TEXT_ARTIFACT_BYTES)
    const mdInfo = ext === '.md' || ext === '.markdown' ? extractHeadingFromMarkdownText(text) : {}
    const artifact: ArtifactInfo = {
      id: artifactId,
      sessionId: ctx.sessionId,
      kind,
      title: mdInfo.heading || formatFallbackTitle(fileName, kind),
      ...(meta.summary || mdInfo.snippet ? { summary: meta.summary || mdInfo.snippet } : {}),
      fileName,
      mimeType,
      sizeBytes: st.size,
      updatedAt: Math.floor(meta.updatedAt ?? st.mtimeMs),
      source: 'agy_brain',
      ...(kind === 'plan' ? { actionable: true } : {}),
    }
    return {
      artifact,
      content: text,
      isBase64: false,
      truncated,
    }
  }

  if (artifactId.startsWith('claude_plan:')) {
    if (ctx.engineId !== 'claude') {
      throw new HubRpcError(ErrorCode.NotFound, 'Claude plan artifacts are unavailable for this session.')
    }
    const fileName = artifactId.slice('claude_plan:'.length)
    if (!fileName.toLowerCase().endsWith('.md')) {
      throw new HubRpcError(ErrorCode.PathNotAllowed, 'Only Markdown Claude plans may be read.')
    }
    const referenced = extractReferencedClaudePlanFiles(ctx.events)
    if (!referenced.includes(fileName)) {
      throw new HubRpcError(ErrorCode.PathNotAllowed, 'Plan file is not associated with this session.')
    }
    const plansDir = path.join(ctx.homeDir, '.claude', 'plans')
    const realFile = resolveContainedFile(plansDir, fileName)
    const st = fs.statSync(realFile)
    const { text, truncated } = readBoundedUtf8(realFile, MAX_TEXT_ARTIFACT_BYTES)
    const mdInfo = extractHeadingFromMarkdownText(text)
    return {
      artifact: {
        id: artifactId,
        sessionId: ctx.sessionId,
        kind: 'plan',
        title: mdInfo.heading || formatFallbackTitle(fileName, 'plan'),
        ...(mdInfo.snippet ? { summary: mdInfo.snippet } : {}),
        fileName,
        mimeType: 'text/markdown',
        sizeBytes: st.size,
        updatedAt: Math.floor(st.mtimeMs),
        source: 'claude_plan',
        actionable: true,
      },
      content: text,
      isBase64: false,
      truncated,
    }
  }

  if (artifactId.startsWith('virtual:')) {
    const virtuals = collectVirtualArtifacts(ctx)
    const found = virtuals.find(v => v.info.id === artifactId)
    if (!found) {
      throw new HubRpcError(ErrorCode.NotFound, `Artifact "${artifactId}" not found.`)
    }
    const truncated = Buffer.byteLength(found.content, 'utf8') > MAX_TEXT_ARTIFACT_BYTES
    const content = truncated ? found.content.slice(0, MAX_TEXT_ARTIFACT_BYTES) : found.content
    return {
      artifact: found.info,
      content,
      isBase64: false,
      truncated,
    }
  }

  throw new HubRpcError(ErrorCode.NotFound, `Artifact "${artifactId}" not found.`)
}

function readBoundedUtf8(filePath: string, maxBytes: number): { text: string; truncated: boolean } {
  const st = fs.statSync(filePath)
  const truncated = st.size > maxBytes
  const readLen = Math.min(st.size, maxBytes)
  const fd = fs.openSync(filePath, 'r')
  try {
    const buf = Buffer.alloc(readLen)
    fs.readSync(fd, buf, 0, readLen, 0)
    return { text: buf.toString('utf8'), truncated }
  } finally {
    fs.closeSync(fd)
  }
}
