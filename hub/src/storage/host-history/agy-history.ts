import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { AgentEvent, EngineModelSpec, ReasoningEffort, SessionMeta, SubagentSummary } from '@lework/protocol'
import {
  classifyRawToolCall,
  describeToolActivity,
  extractProjectRootFromToolInput,
  mergeUniqueWorkspaces,
  readFirstAndLastLines,
  readFullSessionLines,
  sortAndCapSubagents,
  type CachedTranscript,
  type HostTranscriptEntry,
  type ParsedHostTranscript,
} from './common.ts'
import { parseStagedImagePromptHeader } from '../attachment-store.ts'
import { parseAgyModelString, readAgyLocalSettings } from './engine-settings.ts'

export function extractLatestAgyModelSelection(rawContent: string): string | undefined {
  if (!rawContent || !rawContent.includes('Model Selection')) return undefined
  const re = /`Model Selection`\s+from\s+[^\n]+?\s+to\s+([^\n.]+)\./g
  let last: string | undefined
  let m: RegExpExecArray | null
  while ((m = re.exec(rawContent)) !== null) {
    const val = m[1]?.trim()
    if (val && val.toLowerCase() !== 'none') last = val
  }
  return last
}

export function scanAgySessions(
  homeDir: string,
  transcriptCache: Map<string, CachedTranscript>,
  out: Map<string, HostTranscriptEntry>,
  agyCatalog?: EngineModelSpec[],
  subagentOut?: Map<string, HostTranscriptEntry>,
): void {
  const agyRoot = path.join(homeDir, '.gemini', 'antigravity-cli')
  const brainDir = path.join(agyRoot, 'brain')
  if (!fs.existsSync(brainDir)) return
  const agyHistoryMap = readAgyHistoryIndex(path.join(agyRoot, 'history.jsonl'))
  const agySummaryMap = readAgyConversationSummaries(path.join(agyRoot, 'conversation_summaries.db'))
  const agyDefaults = readAgyLocalSettings(homeDir, agyCatalog)

  let entries: fs.Dirent[] = []
  try {
    entries = fs.readdirSync(brainDir, { withFileTypes: true })
  } catch {
    return
  }
  const candidates: Array<{ convId: string; path: string; mtimeMs: number; birthtimeMs: number }> = []
  const subagentCandidatesByParent = new Map<
    string,
    Array<{
      convId: string
      path: string
      mtimeMs: number
      birthtimeMs: number
      summary: AgySummaryInfo
    }>
  >()

  const resolveRootParentId = (startId: string): string | undefined => {
    const visited = new Set<string>([startId])
    let cur = agySummaryMap.get(startId)?.parentConversationId
    while (cur && !visited.has(cur)) {
      visited.add(cur)
      const parentInfo = agySummaryMap.get(cur)
      if (!parentInfo?.isSubagent || !parentInfo.parentConversationId) {
        return cur
      }
      cur = parentInfo.parentConversationId
    }
    return cur
  }

  for (const d of entries) {
    if (!d.isDirectory()) continue
    const summaryInfo = agySummaryMap.get(d.name)
    const tp = path.join(brainDir, d.name, '.system_generated', 'logs', 'transcript.jsonl')
    if (summaryInfo?.isSubagent) {
      try {
        const st = fs.statSync(tp)
        const rootParentId = resolveRootParentId(d.name)
        if (rootParentId) {
          const list = subagentCandidatesByParent.get(rootParentId) ?? []
          list.push({
            convId: d.name,
            path: tp,
            mtimeMs: st.mtimeMs,
            birthtimeMs: st.birthtimeMs,
            summary: summaryInfo,
          })
          subagentCandidatesByParent.set(rootParentId, list)
        }
      } catch {
        // ignore missing subagent transcript
      }
      continue
    }
    try {
      const st = fs.statSync(tp)
      candidates.push({ convId: d.name, path: tp, mtimeMs: st.mtimeMs, birthtimeMs: st.birthtimeMs })
    } catch {
      continue
    }
  }
  candidates.sort((a, b) => b.mtimeMs - a.mtimeMs)
  for (const c of candidates.slice(0, 20)) {
    try {
      const { first, last } = readFirstAndLastLines(c.path, 25, 12)
      let firstPromptTitle = ''
      let rawModelSelection: string | undefined
      const inferredPaths: string[] = []
      for (const l of [...first, ...last]) {
        try {
          const o = JSON.parse(l) as {
            type?: string
            created_at?: string
            content?: string
            tool_calls?: Array<{ args?: Record<string, unknown>; arguments?: Record<string, unknown> }>
          }
          if (o.type === 'USER_INPUT' && typeof o.content === 'string') {
            const sel = extractLatestAgyModelSelection(o.content)
            if (sel) rawModelSelection = sel
            if (!firstPromptTitle) {
              const reqs = extractAgyUserRequests(o.content)
              if (reqs.length > 0) {
                const cleaned = parseStagedImagePromptHeader(reqs[0]!, false).cleanText
                firstPromptTitle = cleaned.replace(/\s+/g, ' ').trim().slice(0, 48)
              }
            }
          }
          if (Array.isArray(o.tool_calls)) {
            for (const tc of o.tool_calls) {
              const argsObj = normalizeAgyArgs(tc.args ?? tc.arguments)
              extractProjectRootFromToolInput(argsObj, inferredPaths, homeDir)
            }
          }
        } catch {
          // ignore
        }
      }

      const hist = agyHistoryMap.get(c.convId)
      const summary = agySummaryMap.get(c.convId)
      const title =
        hist?.customTitle ||
        firstPromptTitle ||
        summary?.title ||
        `AGY Session ${c.convId.slice(0, 8)}`
      const workspaces = mergeUniqueWorkspaces(
        [
          ...(summary?.workspaces ?? []),
          ...(hist?.workspaces ?? []),
          ...inferredPaths,
        ],
        homeDir,
      )
      const primaryCwd = workspaces[0] ?? homeDir

      const isRunning = Date.now() - c.mtimeMs < 75_000 && isAgyTailActivelyRunning(last)
      const sessionId = `host:agy:${c.convId}`
      let queued: string[] = []
      if (isRunning && hist && hist.prompts.length > 0) {
        const cached = transcriptCache.get(sessionId)
        if (cached && cached.mtimeMs === c.mtimeMs && cached.lastUserInputTs !== undefined) {
          queued = filterAgyQueuedFromHistory(
            hist.prompts,
            cached.lastUserInputTs,
            cached.recentUserTexts ?? [],
          )
        } else {
          const latestInput = findLatestAgyUserInputFromTail(c.path)
          queued = filterAgyQueuedFromHistory(hist.prompts, latestInput.ts, latestInput.texts)
        }
      }

      const parsedModel = parseAgyModelString(
        hist?.lastModel ?? rawModelSelection,
        agyCatalog,
      )
      const cached = transcriptCache.get(sessionId)

      const rawChildren = subagentCandidatesByParent.get(c.convId) ?? []
      rawChildren.sort((a, b) => b.mtimeMs - a.mtimeMs)
      const subagentSummaries: SubagentSummary[] = []
      for (const child of rawChildren.slice(0, 24)) {
        try {
          const built = buildAgySubagentSummary(
            child.convId,
            child.path,
            child.mtimeMs,
            child.birthtimeMs,
            child.summary,
            homeDir,
            primaryCwd,
          )
          subagentSummaries.push(built.summary)
          subagentOut?.set(built.summary.id, built.entry)
        } catch {
          // ignore unreadable subagent transcript
        }
      }
      const subagents = sortAndCapSubagents(subagentSummaries, 12)

      out.set(sessionId, {
        nativeId: c.convId,
        filePath: c.path,
        meta: {
          id: sessionId,
          engineId: 'agy',
          cwd: primaryCwd,
          workspaces: workspaces.length > 0 ? workspaces : [primaryCwd],
          title,
          state: isRunning ? 'running' : 'idle',
          queuedPrompts: queued.length > 0 ? queued : undefined,
          createdAt: Math.floor(c.birthtimeMs || c.mtimeMs),
          updatedAt: Math.floor(c.mtimeMs),
          lastSeq: 0,
          model: cached?.model ?? parsedModel.modelId ?? agyDefaults.model,
          reasoningEffort: cached?.reasoningEffort ?? parsedModel.effort ?? agyDefaults.effort,
          mode: agyDefaults.defaultMode,
          source: 'host',
          ...(subagents.length > 0 ? { subagents } : {}),
        },
      })
    } catch {
      // ignore
    }
  }
}

function extractAgySubagentBrief(rawContent: string): string | undefined {
  if (!rawContent || !rawContent.includes('[Message]') || !rawContent.includes('content=')) {
    return undefined
  }
  const idx = rawContent.indexOf('content=')
  if (idx < 0) return undefined
  const body = rawContent
    .slice(idx + 'content='.length)
    .replace(/<\/SYSTEM_MESSAGE>[\s\S]*$/g, '')
    .trim()
  return body || undefined
}

function buildAgySubagentSummary(
  convId: string,
  filePath: string,
  mtimeMs: number,
  birthtimeMs: number,
  summaryInfo: AgySummaryInfo | undefined,
  homeDir: string,
  fallbackCwd: string,
): { summary: SubagentSummary; entry: HostTranscriptEntry } {
  const { first, last } = readFirstAndLastLines(filePath, 15, 12)
  let firstPrompt = ''
  const inferredPaths: string[] = []
  for (const l of [...first, ...last]) {
    try {
      const o = JSON.parse(l) as {
        type?: string
        content?: string
        tool_calls?: Array<{ args?: Record<string, unknown>; arguments?: Record<string, unknown> }>
      }
      if (!firstPrompt && o.type === 'USER_INPUT' && typeof o.content === 'string') {
        const reqs = extractAgyUserRequests(o.content)
        const candidate = reqs[0] ?? extractAgySubagentBrief(o.content)
        if (candidate) {
          const cleaned = parseStagedImagePromptHeader(candidate, false).cleanText
          firstPrompt = cleaned.replace(/\s+/g, ' ').trim().slice(0, 48)
        }
      }
      if (Array.isArray(o.tool_calls)) {
        for (const tc of o.tool_calls) {
          const argsObj = normalizeAgyArgs(tc.args ?? tc.arguments)
          extractProjectRootFromToolInput(argsObj, inferredPaths, homeDir)
        }
      }
    } catch {
      // ignore
    }
  }

  const title = (summaryInfo?.title || firstPrompt || `SubAgent ${convId.slice(0, 8)}`).slice(0, 64)
  const workspaces = mergeUniqueWorkspaces(
    [...(summaryInfo?.workspaces ?? []), ...inferredPaths],
    homeDir,
  )
  const workspace = workspaces[0]
  const activity = inferAgySubagentActivity(last, mtimeMs)
  const subId = `host:agy:${convId}`
  const createdAt = Math.floor(birthtimeMs || mtimeMs)
  const updatedAt = Math.floor(mtimeMs)

  const summary: SubagentSummary = {
    id: subId,
    title,
    state: activity.state,
    statusText: activity.statusText,
    createdAt,
    updatedAt,
    ...(workspace ? { workspace } : {}),
  }

  const entry: HostTranscriptEntry = {
    nativeId: convId,
    filePath,
    meta: {
      id: subId,
      engineId: 'agy',
      cwd: workspace ?? fallbackCwd,
      workspaces: workspace ? [workspace] : [fallbackCwd],
      title,
      state: activity.state,
      liveStatusText: activity.state === 'running' ? activity.statusText : undefined,
      createdAt,
      updatedAt,
      lastSeq: 0,
      source: 'host',
    },
  }
  return { summary, entry }
}

export function resolveAgySubagentEntry(
  homeDir: string,
  sessionId: string,
): HostTranscriptEntry | undefined {
  if (!sessionId.startsWith('host:agy:')) return undefined
  const convId = sessionId.slice('host:agy:'.length)
  if (!/^[A-Za-z0-9_-]{4,128}$/.test(convId)) return undefined
  const agyRoot = path.join(homeDir, '.gemini', 'antigravity-cli')
  const tp = path.join(agyRoot, 'brain', convId, '.system_generated', 'logs', 'transcript.jsonl')
  let st: fs.Stats
  try {
    st = fs.statSync(tp)
  } catch {
    return undefined
  }
  const summaryMap = readAgyConversationSummaries(path.join(agyRoot, 'conversation_summaries.db'))
  const built = buildAgySubagentSummary(
    convId,
    tp,
    st.mtimeMs,
    st.birthtimeMs,
    summaryMap.get(convId),
    homeDir,
    homeDir,
  )
  return built.entry
}

export function inferAgySubagentActivity(
  tailLines: string[],
  mtimeMs: number,
): { state: SessionMeta['state']; statusText: string } {
  let lastStatus: string | undefined
  let lastType: string | undefined
  let lastToolActivity: string | undefined

  for (let i = tailLines.length - 1; i >= 0; i--) {
    try {
      const o = JSON.parse(tailLines[i]!) as {
        type?: string
        status?: string
        tool_calls?: Array<{ name?: string; args?: unknown; arguments?: unknown }>
      }
      if (lastType === undefined && o.type) {
        lastType = o.type
        lastStatus = o.status
      }
      if (!lastToolActivity && Array.isArray(o.tool_calls) && o.tool_calls.length > 0) {
        const lastTc = o.tool_calls[o.tool_calls.length - 1]!
        if (lastTc.name) {
          const argsObj = normalizeAgyArgs(lastTc.args ?? lastTc.arguments)
          lastToolActivity = describeToolActivity(
            lastTc.name,
            argsObj ? JSON.stringify(argsObj) : undefined,
          )
        }
      }
      if (lastType && lastToolActivity) break
    } catch {
      // ignore
    }
  }

  if (lastStatus === 'ERROR') {
    return { state: 'error', statusText: '执行出错' }
  }
  if (lastStatus === 'CANCELLED') {
    return { state: 'idle', statusText: '已取消' }
  }
  const activelyRunning = Date.now() - mtimeMs < 90_000 && isAgyTailActivelyRunning(tailLines)
  if (activelyRunning) {
    if (lastType === 'USER_INPUT') {
      return { state: 'running', statusText: '思考中…' }
    }
    return { state: 'running', statusText: lastToolActivity ?? '思考中…' }
  }
  return { state: 'idle', statusText: '已完成' }
}

export function parseAgyTranscript(
  sessionId: string,
  filePath: string,
  agyCatalog?: EngineModelSpec[],
): ParsedHostTranscript {
  const events: AgentEvent[] = []
  const workspaces: string[] = []
  let pendingToolIds: string[] = []
  let isRunning = false
  let turnStartedAt: number | undefined
  let lastUserInputTs = 0
  let latestModelRaw: string | undefined
  const recentUserTexts: string[] = []
  const lines = readFullSessionLines(filePath)
  let seq = 0
  const failOpenTools = (ts: number, output = '已停止') => {
    for (const callId of pendingToolIds) {
      events.push({
        seq: seq++,
        sessionId,
        ts,
        type: 'tool.update',
        toolCallId: callId,
        status: 'failed',
        output,
      })
    }
    pendingToolIds = []
  }
  for (const line of lines) {
    try {
      const o = JSON.parse(line) as {
        step_index?: number
        type?: string
        status?: string
        created_at?: string
        content?: string
        thinking?: string
        input_tokens?: number
        output_tokens?: number
        tool_calls?: Array<{ id?: string; name?: string; arguments?: unknown; args?: unknown }>
      }
      const ts = o.created_at ? Date.parse(o.created_at) || Date.now() : Date.now()
      const upperStatus = typeof o.status === 'string' ? o.status.toUpperCase() : ''
      const upperType = typeof o.type === 'string' ? o.type.toUpperCase() : ''
      const stepCancelled = isAgyCancelledStatus(upperStatus) || AGY_CANCELLED_STATUSES.has(upperType)
      if (o.type === 'USER_INPUT' && typeof o.content === 'string') {
        const sel = extractLatestAgyModelSelection(o.content)
        if (sel) latestModelRaw = sel
        const reqs = extractAgyUserRequests(o.content)
        if (reqs.length > 0) {
          isRunning = true
          turnStartedAt = ts
          lastUserInputTs = ts
          reqs.forEach((txt, idx) => {
            recentUserTexts.push(txt)
            if (recentUserTexts.length > 16) recentUserTexts.shift()
            const imgInfo = parseStagedImagePromptHeader(txt, true)
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.user',
              messageId: idx === 0 ? `u_${o.step_index ?? seq}` : `u_${o.step_index ?? seq}_${idx}`,
              text: imgInfo.cleanText,
              ...(imgInfo.imageCount > 0 ? { imageCount: imgInfo.imageCount } : {}),
              ...(imgInfo.imagePreviews ? { imagePreviews: imgInfo.imagePreviews } : {}),
            })
          })
        } else if (events.length === 0) {
          const brief = extractAgySubagentBrief(o.content)
          if (brief) {
            isRunning = true
            turnStartedAt = ts
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.user',
              messageId: `u_${o.step_index ?? seq}`,
              text: brief.slice(0, 4000),
            })
          } else if (!o.content.trim().startsWith('<SYSTEM_MESSAGE>') && !o.content.trim().startsWith('<CONTEXT_SUMMARY>')) {
            isRunning = true
            turnStartedAt = ts
          }
        } else if (!o.content.trim().startsWith('<SYSTEM_MESSAGE>') && !o.content.trim().startsWith('<CONTEXT_SUMMARY>')) {
          isRunning = true
          turnStartedAt = ts
        }
        if (stepCancelled) {
          failOpenTools(ts)
          isRunning = false
          turnStartedAt = undefined
        }
      } else if (o.type === 'PLANNER_RESPONSE') {
        const plannerAborted = stepCancelled || upperStatus === 'ERROR' || upperStatus === 'FAILED'
        if (plannerAborted) {
          failOpenTools(ts)
        } else {
          pendingToolIds = []
        }
        if (typeof o.thinking === 'string' && o.thinking.trim()) {
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'reasoning.delta',
            messageId: `th_${o.step_index ?? seq}`,
            text: o.thinking.trim().slice(0, 1800),
          })
        }
        const hasTools = Array.isArray(o.tool_calls) && o.tool_calls.length > 0
        if (Array.isArray(o.tool_calls)) {
          for (const tc of o.tool_calls) {
            if (tc.name) {
              const cleanArgsObj = normalizeAgyArgs(tc.args ?? tc.arguments)
              extractProjectRootFromToolInput(cleanArgsObj, workspaces)
              const rawInput = cleanArgsObj ? JSON.stringify(cleanArgsObj) : undefined
              const normalized = classifyRawToolCall(tc.name, rawInput)
              const callId = tc.id ?? `tc_${o.step_index ?? seq}_${tc.name}`
              if (!plannerAborted) {
                pendingToolIds.push(callId)
              }
              events.push({
                seq: seq++,
                sessionId,
                ts,
                type: 'tool.call',
                toolCallId: callId,
                title: normalized.title,
                kind: normalized.kind,
                status: plannerAborted ? 'failed' : 'in_progress',
                input: normalized.input?.slice(0, 1600),
                ...(plannerAborted ? { output: '已停止' } : {}),
              })
            }
          }
        }
        if (typeof o.content === 'string' && o.content.trim()) {
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'message.assistant.delta',
            messageId: `a_${o.step_index ?? seq}`,
            text: o.content.trim(),
          })
        }
        if (o.input_tokens || o.output_tokens) {
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'usage',
            inputTokens: o.input_tokens,
            outputTokens: o.output_tokens,
          })
        }
        isRunning = plannerAborted ? false : hasTools
        if (!isRunning) turnStartedAt = undefined
      } else if (
        (o.type === 'GENERIC' || o.type === 'TOOL_RESPONSE') &&
        typeof o.content === 'string'
      ) {
        const callId = pendingToolIds.shift()
        if (callId) {
          const failed = stepCancelled || upperStatus === 'ERROR' || upperStatus === 'FAILED'
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'tool.update',
            toolCallId: callId,
            status: failed ? 'failed' : 'completed',
            output: o.content.trim() ? o.content.slice(0, 1600) : stepCancelled ? '已停止' : '',
          })
        }
        if (stepCancelled) {
          failOpenTools(ts)
          isRunning = false
          turnStartedAt = undefined
        } else {
          isRunning = true
        }
      } else if (stepCancelled) {
        failOpenTools(ts)
        isRunning = false
        turnStartedAt = undefined
      }
    } catch {
      // ignore
    }
  }
  const resolvedModel: { modelId?: string; effort?: ReasoningEffort } = parseAgyModelString(
    latestModelRaw,
    agyCatalog,
  )
  return {
    events,
    workspaces,
    isRunning: isRunning || pendingToolIds.length > 0,
    turnStartedAt,
    lastUserInputTs,
    recentUserTexts,
    model: resolvedModel.modelId,
    reasoningEffort: resolvedModel.effort,
  }
}

export function extractAgyUserRequests(rawContent: string): string[] {
  if (!rawContent) return []
  if (rawContent.includes('<USER_REQUEST>')) {
    const out: string[] = []
    const re = /<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/g
    let m: RegExpExecArray | null
    while ((m = re.exec(rawContent)) !== null) {
      const inner = (m[1] ?? '').trim()
      if (inner) out.push(inner)
    }
    if (out.length > 0) return out
    // Fallback when `transcript.jsonl` truncated a very long prompt before `</USER_REQUEST>`
    const idx = rawContent.indexOf('<USER_REQUEST>')
    const fallback = rawContent
      .slice(idx + '<USER_REQUEST>'.length)
      .replace(/<ADDITIONAL_METADATA>[\s\S]*$|<USER_SETTINGS_CHANGE>[\s\S]*$|<SYSTEM_MESSAGE>[\s\S]*$/g, '')
      .trim()
    return fallback ? [fallback] : []
  }

  const trimmed = rawContent
    .replace(/<ADDITIONAL_METADATA>[\s\S]*$|<USER_SETTINGS_CHANGE>[\s\S]*$/g, '')
    .trim()
  if (
    !trimmed ||
    trimmed.startsWith('<SYSTEM_MESSAGE>') ||
    trimmed.startsWith('<CONTEXT_SUMMARY>')
  ) {
    return []
  }
  return [trimmed]
}

export function findLatestAgyUserInputFromTail(filePath: string): { ts: number; texts: string[] } {
  const st = fs.statSync(filePath)
  if (st.size === 0) return { ts: 0, texts: [] }
  const chunkSize = 128 * 1024
  const fd = fs.openSync(filePath, 'r')
  try {
    let pos = st.size
    let carry = ''
    let latestTs = 0
    const collectedTexts: string[] = []

    while (pos > 0) {
      const readLen = Math.min(chunkSize, pos)
      pos -= readLen
      const buf = Buffer.alloc(readLen)
      fs.readSync(fd, buf, 0, readLen, pos)
      const combined = buf.toString('utf8') + carry
      const lines = combined.split('\n')
      carry = pos > 0 ? (lines.shift() ?? '') : ''

      for (let i = lines.length - 1; i >= 0; i--) {
        const line = lines[i]?.trim()
        if (!line || !line.includes('"USER_INPUT"')) continue
        try {
          const o = JSON.parse(line) as { type?: string; created_at?: string; content?: string }
          if (o.type === 'USER_INPUT' && typeof o.content === 'string') {
            const reqs = extractAgyUserRequests(o.content)
            if (reqs.length > 0) {
              const ts = o.created_at ? Date.parse(o.created_at) || 0 : 0
              if (latestTs === 0) latestTs = ts
              collectedTexts.push(...reqs)
              if (collectedTexts.length >= 6 || (latestTs > 0 && latestTs - ts > 5_000)) {
                return { ts: latestTs, texts: collectedTexts }
              }
            }
          }
        } catch {
          // ignore malformed line
        }
      }

      if (latestTs > 0) {
        return { ts: latestTs, texts: collectedTexts }
      }
    }
    return { ts: latestTs, texts: collectedTexts }
  } finally {
    fs.closeSync(fd)
  }
}

interface AgySummaryInfo {
  title?: string
  workspaces: string[]
  isSubagent: boolean
  parentConversationId?: string
}

let cachedSummaryDbPath = ''
let cachedSummaryStamp = ''
let cachedSummaryMap = new Map<string, AgySummaryInfo>()
const nodeRequire = createRequire(import.meta.url)

function parseAgyWorkspaceUris(raw: unknown): string[] {
  if (typeof raw !== 'string' || !raw.trim()) return []
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    const out: string[] = []
    for (const item of parsed) {
      if (typeof item !== 'string' || !item.trim()) continue
      if (item.startsWith('file://')) {
        try {
          out.push(fileURLToPath(item))
        } catch {
          // ignore invalid file URI
        }
      } else if (item.startsWith('/')) {
        out.push(item)
      }
    }
    return out
  } catch {
    return []
  }
}

export function readAgyConversationSummaries(summaryDbPath: string): Map<string, AgySummaryInfo> {
  if (!fs.existsSync(summaryDbPath)) return new Map()
  try {
    const dbStat = fs.statSync(summaryDbPath)
    let walStamp = '0:0'
    try {
      const walStat = fs.statSync(`${summaryDbPath}-wal`)
      walStamp = `${walStat.mtimeMs}:${walStat.size}`
    } catch {
      // no wal file
    }
    const stamp = `${dbStat.mtimeMs}:${dbStat.size}:${walStamp}`
    if (summaryDbPath === cachedSummaryDbPath && stamp === cachedSummaryStamp) {
      return cachedSummaryMap
    }

    const sqliteMod = nodeRequire('node:sqlite') as {
      DatabaseSync: new (
        location: string,
        options?: { open?: boolean; readOnly?: boolean },
      ) => {
        prepare: (sql: string) => { all: () => Array<Record<string, unknown>> }
        close: () => void
      }
    }
    const db = new sqliteMod.DatabaseSync(summaryDbPath, { open: true, readOnly: true })
    const out = new Map<string, AgySummaryInfo>()
    try {
      const rows = db
        .prepare(
          'SELECT conversation_id, title, workspace_uris, parent_conversation_id, nesting_depth FROM conversation_summaries',
        )
        .all()
      for (const row of rows) {
        const convId = typeof row.conversation_id === 'string' ? row.conversation_id.trim() : ''
        if (!convId) continue
        const parentId =
          typeof row.parent_conversation_id === 'string' ? row.parent_conversation_id.trim() : ''
        const depth = typeof row.nesting_depth === 'number' ? row.nesting_depth : 0
        const title =
          typeof row.title === 'string' && row.title.trim() ? row.title.trim() : undefined
        out.set(convId, {
          title,
          workspaces: parseAgyWorkspaceUris(row.workspace_uris),
          isSubagent: Boolean(parentId) || depth > 0,
          parentConversationId: parentId || undefined,
        })
      }
    } finally {
      db.close()
    }

    cachedSummaryDbPath = summaryDbPath
    cachedSummaryStamp = stamp
    cachedSummaryMap = out
    return out
  } catch {
    return cachedSummaryMap
  }
}

interface AgyHistoryInfo {
  customTitle?: string
  lastModel?: string
  workspaces: string[]
  prompts: Array<{ text: string; ts: number }>
}

let cachedAgyHistoryPath = ''
let cachedAgyHistoryMtime = 0
let cachedAgyHistorySize = 0
let cachedAgyHistoryMap = new Map<string, AgyHistoryInfo>()

export function readAgyHistoryIndex(historyFile: string): Map<string, AgyHistoryInfo> {
  if (!fs.existsSync(historyFile)) return new Map()
  try {
    const st = fs.statSync(historyFile)
    if (
      historyFile === cachedAgyHistoryPath &&
      st.mtimeMs === cachedAgyHistoryMtime &&
      st.size === cachedAgyHistorySize
    ) {
      return cachedAgyHistoryMap
    }
    const out = new Map<string, AgyHistoryInfo>()
    const raw = fs.readFileSync(historyFile, 'utf8')
    for (const line of raw.split('\n')) {
      if (!line.trim()) continue
      try {
        const o = JSON.parse(line) as {
          conversationId?: string
          workspace?: string
          display?: string
          timestamp?: number
          type?: string
        }
        if (!o.conversationId) continue
        const rec = out.get(o.conversationId) ?? { workspaces: [], prompts: [] }
        if (typeof o.workspace === 'string' && o.workspace.trim()) {
          rec.workspaces.push(o.workspace.trim())
        }
        if (o.type === 'slash_command' && typeof o.display === 'string') {
          const cmd = o.display.trim()
          if (cmd.startsWith('/rename ')) {
            const renamed = cmd.slice('/rename '.length).trim()
            if (renamed) rec.customTitle = renamed
          } else if (cmd.startsWith('/model ')) {
            const mArg = cmd.slice('/model '.length).trim()
            if (mArg) rec.lastModel = mArg
          } else if (cmd === '/clear') {
            rec.prompts.length = 0
          }
        } else if (
          o.type !== 'slash_command' &&
          typeof o.display === 'string' &&
          typeof o.timestamp === 'number'
        ) {
          const trimmed = o.display.trim()
          if (trimmed && !trimmed.startsWith('/')) {
            rec.prompts.push({ text: trimmed, ts: o.timestamp })
          }
        }
        out.set(o.conversationId, rec)
      } catch {
        // ignore line
      }
    }
    cachedAgyHistoryPath = historyFile
    cachedAgyHistoryMtime = st.mtimeMs
    cachedAgyHistorySize = st.size
    cachedAgyHistoryMap = out
    return out
  } catch {
    return cachedAgyHistoryMap
  }
}

export function filterAgyQueuedFromHistory(
  prompts: Array<{ text: string; ts: number }>,
  lastUserInputTs: number,
  recentUserTexts: string[],
): string[] {
  if (prompts.length === 0 || lastUserInputTs <= 0) return []
  const normRecent = new Set(
    recentUserTexts.map(t => t.replace(/\s+/g, ' ').trim()).filter(Boolean),
  )
  const out: string[] = []
  for (const p of prompts) {
    // `transcript.jsonl` `created_at` is ISO-8601 rounded down to whole seconds (.000Z),
    // while `history.jsonl` uses millisecond epoch timestamps.
    if (p.ts < lastUserInputTs) continue
    const normP = p.text.replace(/\s+/g, ' ').trim()
    if (!normP) continue
    if (p.ts <= lastUserInputTs + 2_500 && normRecent.has(normP)) continue
    out.push(p.text)
  }
  return out
}

export function resolveAgyQueuedPrompts(
  homeDir: string,
  convId: string,
  lastUserInputTs: number,
  recentUserTexts: string[],
): string[] | undefined {
  const historyFile = path.join(homeDir, '.gemini', 'antigravity-cli', 'history.jsonl')
  const hist = readAgyHistoryIndex(historyFile).get(convId)
  if (!hist) return undefined
  const queued = filterAgyQueuedFromHistory(hist.prompts, lastUserInputTs, recentUserTexts)
  return queued.length > 0 ? queued : undefined
}

export function normalizeAgyArgs(raw: unknown): Record<string, unknown> | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof v === 'string' && v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
      try {
        out[k] = JSON.parse(v)
        continue
      } catch {
        // keep as-is
      }
    }
    out[k] = v
  }
  return out
}

const AGY_CANCELLED_STATUSES = new Set(['CANCELLED', 'CANCELED', 'INTERRUPTED', 'ABORTED'])

function isAgyCancelledStatus(status?: string): boolean {
  return typeof status === 'string' && AGY_CANCELLED_STATUSES.has(status.toUpperCase())
}

export function isAgyTailActivelyRunning(tailLines: string[]): boolean {
  for (let i = tailLines.length - 1; i >= 0; i--) {
    try {
      const o = JSON.parse(tailLines[i]!) as {
        type?: string
        status?: string
        tool_calls?: unknown[]
      }
      const upperStatus = typeof o.status === 'string' ? o.status.toUpperCase() : ''
      const upperType = typeof o.type === 'string' ? o.type.toUpperCase() : ''
      if (
        upperStatus === 'ERROR' ||
        upperStatus === 'FAILED' ||
        isAgyCancelledStatus(upperStatus) ||
        AGY_CANCELLED_STATUSES.has(upperType)
      ) {
        return false
      }
      if (o.type === 'PLANNER_RESPONSE') {
        return Array.isArray(o.tool_calls) && o.tool_calls.length > 0
      }
      if (o.type === 'USER_INPUT' || o.type === 'TOOL_RESPONSE' || o.type === 'GENERIC') {
        return true
      }
    } catch {
      // ignore
    }
  }
  return false
}
