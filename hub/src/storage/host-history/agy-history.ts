import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { AgentEvent, EngineModelSpec, ReasoningEffort } from '@lework/protocol'
import {
  classifyRawToolCall,
  extractProjectRootFromToolInput,
  mergeUniqueWorkspaces,
  readFirstAndLastLines,
  readFullSessionLines,
  type CachedTranscript,
  type HostTranscriptEntry,
  type ParsedHostTranscript,
} from './common.ts'
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
  for (const d of entries) {
    if (!d.isDirectory()) continue
    if (agySummaryMap.get(d.name)?.isSubagent) continue
    const tp = path.join(brainDir, d.name, '.system_generated', 'logs', 'transcript.jsonl')
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
                firstPromptTitle = reqs[0]!.replace(/\s+/g, ' ').trim().slice(0, 48)
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
        },
      })
    } catch {
      // ignore
    }
  }
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
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.user',
              messageId: idx === 0 ? `u_${o.step_index ?? seq}` : `u_${o.step_index ?? seq}_${idx}`,
              text: txt,
            })
          })
        } else if (!o.content.trim().startsWith('<SYSTEM_MESSAGE>') && !o.content.trim().startsWith('<CONTEXT_SUMMARY>')) {
          isRunning = true
          turnStartedAt = ts
        }
      } else if (o.type === 'PLANNER_RESPONSE') {
        pendingToolIds = []
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
              pendingToolIds.push(callId)
              events.push({
                seq: seq++,
                sessionId,
                ts,
                type: 'tool.call',
                toolCallId: callId,
                title: normalized.title,
                kind: normalized.kind,
                status: 'in_progress',
                input: normalized.input?.slice(0, 1600),
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
        isRunning = hasTools
        if (!isRunning) turnStartedAt = undefined
      } else if (
        (o.type === 'GENERIC' || o.type === 'TOOL_RESPONSE') &&
        typeof o.content === 'string'
      ) {
        isRunning = true
        const callId = pendingToolIds.shift()
        if (callId) {
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'tool.update',
            toolCallId: callId,
            status: o.status === 'ERROR' ? 'failed' : 'completed',
            output: o.content.slice(0, 1600),
          })
        }
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

export function isAgyTailActivelyRunning(tailLines: string[]): boolean {
  for (let i = tailLines.length - 1; i >= 0; i--) {
    try {
      const o = JSON.parse(tailLines[i]!) as {
        type?: string
        status?: string
        tool_calls?: unknown[]
      }
      if (o.status === 'ERROR' || o.status === 'CANCELLED') return false
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
