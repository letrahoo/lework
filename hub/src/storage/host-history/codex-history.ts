import fs from 'node:fs'
import path from 'node:path'
import type { AgentEvent } from '@lework/protocol'
import {
  downscaleInlineImageBase64,
  parseStagedImagePromptHeader,
} from '../attachment-store.ts'
import {
  classifyRawToolCall,
  collectRecentFiles,
  mergeUniqueWorkspaces,
  readFirstAndLastLines,
  readFullSessionLines,
  type HostTranscriptEntry,
  type ParsedHostTranscript,
} from './common.ts'
import { readCodexLocalSettings } from './engine-settings.ts'
import {
  PlanTurnHygieneTracker,
  isPlanStepToolName,
  parsePlanEntriesFromRaw,
} from './plan-steps.ts'

export function scanCodexSessions(
  homeDir: string,
  out: Map<string, HostTranscriptEntry>,
): void {
  const codexDir = path.join(homeDir, '.codex')
  const indexFile = path.join(codexDir, 'session_index.jsonl')
  const sessionsDir = path.join(codexDir, 'sessions')
  if (!fs.existsSync(sessionsDir)) return

  const titlesById = new Map<string, string>()
  if (fs.existsSync(indexFile)) {
    try {
      const lines = fs.readFileSync(indexFile, 'utf8').trim().split('\n').slice(-80)
      for (const line of lines) {
        try {
          const obj = JSON.parse(line) as { id?: string; thread_name?: string }
          if (obj.id && obj.thread_name) titlesById.set(obj.id, obj.thread_name)
        } catch {
          // ignore
        }
      }
    } catch {
      // ignore
    }
  }

  const rolloutFiles = collectRecentFiles(sessionsDir, f => f.endsWith('.jsonl'), 20)
  for (const f of rolloutFiles) {
    try {
      const idMatch = path.basename(f.path).match(/([0-9a-f]{8}-[0-9a-f-]{27})\.jsonl$/i)
      const nativeId = idMatch?.[1] ?? path.basename(f.path, '.jsonl')
      const sessionId = `host:codex:${nativeId}`
      const headAndTail = readFirstAndLastLines(f.path, 10, 10)
      const wsCandidates: string[] = []
      for (const l of [...headAndTail.first, ...headAndTail.last]) {
        try {
          const o = JSON.parse(l) as {
            type?: string
            payload?: { cwd?: string; writable_roots?: string[] }
          }
          if (typeof o.payload?.cwd === 'string') {
            wsCandidates.push(o.payload.cwd)
          }
          if (Array.isArray(o.payload?.writable_roots)) {
            wsCandidates.push(...o.payload.writable_roots.filter((r): r is string => typeof r === 'string'))
          }
        } catch {
          // ignore
        }
      }
      const workspaces = mergeUniqueWorkspaces(wsCandidates)
      const cwd = workspaces[0] ?? homeDir
      let startedCount = 0
      let completedCount = 0
      for (const l of headAndTail.last) {
        if (l.includes('"task_started"')) startedCount++
        if (l.includes('"task_complete"') || l.includes('"turn_aborted"')) completedCount++
      }
      const recentlyModified = Date.now() - f.mtimeMs < 90_000
      const isRunning = recentlyModified && startedCount > completedCount

      const codexDefaults = readCodexLocalSettings(homeDir)
      const title = titlesById.get(nativeId) ?? `Codex · ${path.basename(cwd)}`
      out.set(sessionId, {
        nativeId,
        filePath: f.path,
        meta: {
          id: sessionId,
          engineId: 'codex',
          cwd,
          workspaces: workspaces.length > 0 ? workspaces : [cwd],
          title,
          state: isRunning ? 'running' : 'idle',
          createdAt: Math.floor(f.birthtimeMs || f.mtimeMs),
          updatedAt: Math.floor(f.mtimeMs),
          lastSeq: 0,
          model: codexDefaults.model,
          reasoningEffort: codexDefaults.effort,
          mode: codexDefaults.defaultMode,
          source: 'host',
        },
      })
    } catch {
      // ignore
    }
  }
}

interface CodexContentPart {
  type?: string
  text?: string
  image_url?: string | { url?: string }
  url?: string
  data?: string
  mime_type?: string
  mimeType?: string
  source?: { type?: string; data?: string; media_type?: string }
}

function extractCodexInlineImages(content: CodexContentPart[] | undefined): {
  count: number
  previews: string[]
} {
  if (!Array.isArray(content)) return { count: 0, previews: [] }
  let count = 0
  const previews: string[] = []
  const seen = new Set<string>()
  const pushPreview = (rawB64: string, mimeType?: string): void => {
    if (previews.length >= 4) return
    const trimmed = rawB64.trim()
    if (!trimmed) return
    const resolved =
      trimmed.length <= 260_000
        ? trimmed
        : downscaleInlineImageBase64(trimmed, mimeType)
    if (resolved && !seen.has(resolved)) {
      seen.add(resolved)
      previews.push(resolved)
    }
  }

  for (const item of content) {
    if (!item || typeof item !== 'object') continue
    if (item.type !== 'input_image' && item.type !== 'image') continue
    count++
    if (previews.length >= 4) continue

    const rawUrl =
      typeof item.image_url === 'string'
        ? item.image_url
        : typeof item.image_url?.url === 'string'
          ? item.image_url.url
          : typeof item.url === 'string'
            ? item.url
            : undefined
    if (rawUrl) {
      const dataMatch = rawUrl.trim().match(/^data:(image\/[^;]+);base64,([\s\S]+)$/i)
      if (dataMatch) {
        pushPreview(dataMatch[2]!, dataMatch[1])
        continue
      }
    }

    const rawData =
      typeof item.data === 'string'
        ? item.data
        : typeof item.source?.data === 'string'
          ? item.source.data
          : undefined
    if (rawData) {
      const dataMatch = rawData.trim().match(/^data:(image\/[^;]+);base64,([\s\S]+)$/i)
      const mime = item.source?.media_type ?? item.mime_type ?? item.mimeType ?? dataMatch?.[1]
      pushPreview(dataMatch ? dataMatch[2]! : rawData, mime)
    }
  }
  return { count, previews }
}

export function parseCodexTranscript(
  sessionId: string,
  filePath: string,
): ParsedHostTranscript {
  const events: AgentEvent[] = []
  const workspaces: string[] = []
  const openTools = new Set<string>()
  const ignoredStepTools = new Set<string>()
  const planHygiene = new PlanTurnHygieneTracker()
  let activeTurn = false
  let turnStartedAt: number | undefined
  const lines = readFullSessionLines(filePath)
  let seq = 0
  const emitPlanCleared = (ts: number) => {
    events.push({
      seq: seq++,
      sessionId,
      ts,
      type: 'plan.updated',
      entries: [],
    })
  }
  for (const line of lines) {
    try {
      const o = JSON.parse(line) as {
        timestamp?: string
        type?: string
        payload?: {
          type?: string
          id?: string
          call_id?: string
          cwd?: string
          writable_roots?: string[]
          role?: string
          name?: string
          arguments?: string
          input?: string
          output?: string
          content?: CodexContentPart[]
        }
      }
      const ts = o.timestamp ? Date.parse(o.timestamp) || Date.now() : Date.now()
      if (typeof o.payload?.cwd === 'string') {
        workspaces.push(o.payload.cwd)
      }
      if (Array.isArray(o.payload?.writable_roots)) {
        workspaces.push(...o.payload.writable_roots.filter((r): r is string => typeof r === 'string'))
      }
      if (o.type === 'event_msg' && o.payload?.type === 'task_started') {
        activeTurn = true
        turnStartedAt = ts
      } else if (
        o.type === 'event_msg' &&
        (o.payload?.type === 'task_complete' || o.payload?.type === 'turn_aborted')
      ) {
        if (o.payload.type === 'turn_aborted') {
          for (const callId of openTools) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'tool.update',
              toolCallId: callId,
              status: 'failed',
              output: '已停止',
            })
          }
        }
        openTools.clear()
        activeTurn = false
        turnStartedAt = undefined
        if (planHygiene.onTurnEnded()) emitPlanCleared(ts)
      }

      if (o.type === 'response_item' && o.payload?.type === 'message') {
        const role = o.payload.role
        const text = (o.payload.content ?? [])
          .map(c => (typeof c.text === 'string' ? c.text : ''))
          .join('\n')
          .trim()
        if (role === 'user') {
          const inlineImages = extractCodexInlineImages(o.payload.content)
          if (!text && inlineImages.count === 0) continue
          if (
            text.startsWith('# AGENTS.md') ||
            text.startsWith('<environment_context>') ||
            text.startsWith('<INSTRUCTIONS>') ||
            text.startsWith('<skill>')
          ) {
            continue
          }
          const stagedParsed = parseStagedImagePromptHeader(text, true)
          const mergedPreviews: string[] = []
          const seenPreviews = new Set<string>()
          for (const p of [...inlineImages.previews, ...(stagedParsed.imagePreviews ?? [])]) {
            if (!p || seenPreviews.has(p)) continue
            seenPreviews.add(p)
            mergedPreviews.push(p)
            if (mergedPreviews.length >= 4) break
          }
          const totalImgs = Math.min(
            4,
            inlineImages.count > 0 && stagedParsed.imageCount > 0
              ? Math.max(inlineImages.count, stagedParsed.imageCount, mergedPreviews.length)
              : Math.max(inlineImages.count, stagedParsed.imageCount),
          )
          const finalText =
            stagedParsed.cleanText || (totalImgs > 0 ? `（已发送 ${totalImgs} 张图片）` : '')
          if (!finalText && totalImgs === 0 && !stagedParsed.files?.length) continue
          if (planHygiene.onUserMessage()) emitPlanCleared(ts)
          activeTurn = true
          turnStartedAt = ts
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'message.user',
            messageId: o.payload.id ?? `u_${seq}`,
            text: finalText,
            ...(totalImgs > 0 ? { imageCount: totalImgs } : {}),
            ...(mergedPreviews.length > 0 ? { imagePreviews: mergedPreviews } : {}),
            ...(stagedParsed.files?.length ? { files: stagedParsed.files } : {}),
          })
        } else if (role === 'assistant') {
          if (!text) continue
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'message.assistant.delta',
            messageId: o.payload.id ?? `a_${seq}`,
            text,
          })
        }
      } else if (
        o.type === 'response_item' &&
        (o.payload?.type === 'function_call' || o.payload?.type === 'custom_tool_call')
      ) {
        const callId = o.payload.call_id ?? `cx_tc_${seq}`
        const rawName = o.payload.name ?? 'tool'
        const rawArgs = o.payload.arguments ?? o.payload.input ?? ''
        if (isPlanStepToolName(rawName) === 'update_plan') {
          const entries = parsePlanEntriesFromRaw(rawArgs)
          ignoredStepTools.add(callId)
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'plan.updated',
            entries,
          })
          planHygiene.onPlanUpdated(entries)
          continue
        }
        const normalized = classifyRawToolCall(rawName, rawArgs)
        openTools.add(callId)
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
      } else if (
        o.type === 'response_item' &&
        (o.payload?.type === 'function_call_output' || o.payload?.type === 'custom_tool_call_output')
      ) {
        const callId = o.payload.call_id
        if (callId && ignoredStepTools.has(callId)) {
          ignoredStepTools.delete(callId)
          continue
        }
        if (callId && openTools.has(callId)) {
          openTools.delete(callId)
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'tool.update',
            toolCallId: callId,
            status: 'completed',
            output: typeof o.payload.output === 'string' ? o.payload.output.slice(0, 1600) : undefined,
          })
        }
      }
    } catch {
      // ignore
    }
  }
  return { events, workspaces, isRunning: activeTurn || openTools.size > 0, turnStartedAt }
}
