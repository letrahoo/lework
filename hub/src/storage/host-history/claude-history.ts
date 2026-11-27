import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { AgentEvent, SessionMeta, SubagentSummary } from '@lework/protocol'
import type { ClaudeLiveSessionMeta } from '../terminal-bridge.ts'
import { downscaleInlineImageBase64, parseStagedImagePromptHeader } from '../attachment-store.ts'
import {
  classifyRawToolCall,
  collectRecentFiles,
  describeToolActivity,
  extractProjectRootFromToolInput,
  mergeUniqueWorkspaces,
  readFirstAndLastLines,
  readFullSessionLines,
  sortAndCapSubagents,
  subagentActivity,
  type TailSignal,
  type HostTranscriptEntry,
  type ParsedHostTranscript,
} from './common.ts'
import { readClaudeLocalSettings, resolveClaudeEffectiveModel } from './engine-settings.ts'
import {
  ClaudeTaskStepTracker,
  PlanTurnHygieneTracker,
  extractLatestClaudeStepProgress,
  isPlanStepToolName,
} from './plan-steps.ts'

export function scanClaudeSessions(
  homeDir: string,
  claudeRuntimeMap: Map<string, ClaudeLiveSessionMeta>,
  out: Map<string, HostTranscriptEntry>,
  subagentOut?: Map<string, HostTranscriptEntry>,
): void {
  const projectsDir = path.join(homeDir, '.claude', 'projects')
  if (!fs.existsSync(projectsDir)) return
  const claudeDefaults = readClaudeLocalSettings(homeDir)

  const files = collectRecentFiles(
    projectsDir,
    f => f.endsWith('.jsonl') && path.basename(path.dirname(f)) !== 'subagents',
    24,
  )
  for (const f of files) {
    try {
      const nativeId = path.basename(f.path, '.jsonl')
      const sessionId = `host:claude:${nativeId}`
      const slug = path.basename(path.dirname(f.path))
      const { first, last } = readFirstAndLastLines(f.path, 25, 35)
      let promptTitle = ''
      let customTitle = ''
      let model: string | undefined
      const cwds: string[] = []
      for (const l of [...first, ...last]) {
        try {
          const o = JSON.parse(l) as {
            type?: string
            customTitle?: string
            cwd?: string
            message?: { role?: string; model?: string; content?: unknown }
          }
          if (typeof o.cwd === 'string' && o.cwd.trim()) {
            cwds.push(o.cwd.trim())
          }
          if (o.type === 'custom-title' && typeof o.customTitle === 'string' && o.customTitle.trim()) {
            customTitle = o.customTitle.trim()
          }
          if (!promptTitle && o.type === 'user' && o.message?.content) {
            const txt = extractClaudeText(o.message.content)
            if (
              txt &&
              !txt.startsWith('<') &&
              !txt.startsWith('Base directory for this skill:') &&
              !isClaudeInterruptText(txt)
            ) {
              const cleaned = parseStagedImagePromptHeader(txt, false).cleanText
              promptTitle = cleaned.replace(/\s+/g, ' ').trim().slice(0, 48)
            } else if (!txt && Array.isArray(o.message.content)) {
              const imgCount = (o.message.content as Array<{ type?: unknown }>).filter(
                item => item?.type === 'image',
              ).length
              if (imgCount > 0) {
                promptTitle = `（已发送 ${Math.min(imgCount, 4)} 张图片）`
              }
            }
          }
          if (o.message?.model) model = o.message.model
        } catch {
          // ignore
        }
      }
      const rt = claudeRuntimeMap.get(nativeId)
      const title = rt?.name || customTitle || promptTitle
      if (!title) continue
      if (rt?.cwd) cwds.unshift(rt.cwd)
      const workspaces = mergeUniqueWorkspaces(
        cwds.length > 0 ? cwds : [decodeClaudeProjectSlug(slug)],
      )
      const primaryCwd = workspaces[0] ?? decodeClaudeProjectSlug(slug)

      let state: SessionMeta['state'] = 'idle'
      if (rt) {
        // Trust `~/.claude/sessions/*.json` explicitly when interactive CLI is alive
        if (rt.waitingFor === 'permission prompt') {
          state = 'waiting_permission'
        } else if (rt.status === 'running' || rt.status === 'busy') {
          state = 'running'
        } else {
          state = 'idle'
        }
      } else if (Date.now() - f.mtimeMs < 45_000 && isClaudeTailActivelyRunning(last)) {
        state = 'running'
      }

      const subagents = scanClaudeSubagentsForParent(
        f.path,
        nativeId,
        primaryCwd,
        subagentOut,
      )

      out.set(sessionId, {
        nativeId,
        filePath: f.path,
        meta: {
          id: sessionId,
          engineId: 'claude',
          cwd: primaryCwd,
          workspaces,
          title,
          state,
          liveStatusText: state === 'waiting_permission' ? '等待终端执行审批…' : undefined,
          createdAt: Math.floor(f.birthtimeMs || f.mtimeMs),
          updatedAt: Math.floor(Math.max(f.mtimeMs, rt?.updatedAt ?? 0)),
          lastSeq: 0,
          model: resolveClaudeEffectiveModel(model, claudeDefaults.env) ?? claudeDefaults.model,
          reasoningEffort: claudeDefaults.effort,
          mode: claudeDefaults.defaultMode,
          source: 'host',
          ...(subagents.length > 0 ? { subagents } : {}),
        },
      })
    } catch {
      // ignore
    }
  }
}

function scanClaudeSubagentsForParent(
  parentFilePath: string,
  parentUuid: string,
  fallbackCwd: string,
  subagentOut?: Map<string, HostTranscriptEntry>,
): SubagentSummary[] {
  const subagentsDir = path.join(path.dirname(parentFilePath), parentUuid, 'subagents')
  if (!fs.existsSync(subagentsDir)) return []
  let dirEntries: fs.Dirent[]
  try {
    dirEntries = fs.readdirSync(subagentsDir, { withFileTypes: true })
  } catch {
    return []
  }

  const files: Array<{ stem: string; filePath: string; mtimeMs: number; birthtimeMs: number }> = []
  for (const e of dirEntries) {
    if (!e.isFile() || !e.name.startsWith('agent-') || !e.name.endsWith('.jsonl')) continue
    const full = path.join(subagentsDir, e.name)
    try {
      const st = fs.statSync(full)
      files.push({
        stem: path.basename(e.name, '.jsonl'),
        filePath: full,
        mtimeMs: st.mtimeMs,
        birthtimeMs: st.birthtimeMs,
      })
    } catch {
      // ignore unreadable file
    }
  }
  if (files.length === 0) return []
  files.sort((a, b) => b.mtimeMs - a.mtimeMs)

  const summaries: SubagentSummary[] = []
  for (const f of files.slice(0, 24)) {
    try {
      const built = buildClaudeSubagentSummary(
        parentUuid,
        f.stem,
        f.filePath,
        f.mtimeMs,
        f.birthtimeMs,
        fallbackCwd,
      )
      summaries.push(built.summary)
      subagentOut?.set(built.summary.id, built.entry)
    } catch {
      // ignore unreadable subagent file
    }
  }
  return sortAndCapSubagents(summaries, 12)
}

function readClaudeSubagentSidecarMeta(
  subFilePath: string,
): { description?: string; agentType?: string } | undefined {
  const metaPath = subFilePath.replace(/\.jsonl$/, '.meta.json')
  if (!fs.existsSync(metaPath)) return undefined
  try {
    const raw = JSON.parse(fs.readFileSync(metaPath, 'utf8')) as {
      description?: unknown
      agentType?: unknown
      role?: unknown
      name?: unknown
    }
    const description =
      typeof raw.description === 'string' && raw.description.trim()
        ? raw.description.trim()
        : typeof raw.name === 'string' && raw.name.trim()
          ? raw.name.trim()
          : undefined
    const agentType =
      typeof raw.agentType === 'string' && raw.agentType.trim()
        ? raw.agentType.trim()
        : typeof raw.role === 'string' && raw.role.trim()
          ? raw.role.trim()
          : undefined
    return { description, agentType }
  } catch {
    return undefined
  }
}

function buildClaudeSubagentSummary(
  parentUuid: string,
  agentStem: string,
  filePath: string,
  mtimeMs: number,
  birthtimeMs: number,
  fallbackCwd: string,
): { summary: SubagentSummary; entry: HostTranscriptEntry } {
  const sidecar = readClaudeSubagentSidecarMeta(filePath)
  const { first, last } = readFirstAndLastLines(filePath, 15, 20)
  let promptTitle = ''
  let slugTitle = ''
  let cwd: string | undefined
  for (const l of [...first, ...last]) {
    try {
      const o = JSON.parse(l) as {
        type?: string
        slug?: string
        cwd?: string
        message?: { role?: string; content?: unknown }
      }
      if (!cwd && typeof o.cwd === 'string' && o.cwd.trim()) {
        cwd = o.cwd.trim()
      }
      if (!slugTitle && typeof o.slug === 'string' && o.slug.trim()) {
        slugTitle = o.slug.trim()
      }
      if (!promptTitle && o.type === 'user' && o.message?.content) {
        const txt = extractClaudeText(o.message.content)
        if (txt && !txt.startsWith('<') && !txt.startsWith('Base directory for this skill:')) {
          promptTitle = txt.replace(/\s+/g, ' ').trim().slice(0, 48)
        }
      }
    } catch {
      // ignore
    }
  }

  const title = (sidecar?.description || promptTitle || slugTitle || agentStem).slice(0, 64)
  const role = sidecar?.agentType && sidecar.agentType !== title ? sidecar.agentType : undefined
  const workspace = cwd ?? fallbackCwd
  const activity = inferClaudeSubagentActivity(last, mtimeMs)
  const stepProgress = extractLatestClaudeStepProgress([...first, ...last])
  const subId = `host:claude-subagent:${parentUuid}:${agentStem}`
  const createdAt = Math.floor(birthtimeMs || mtimeMs)
  const updatedAt = Math.floor(mtimeMs)

  const summary: SubagentSummary = {
    id: subId,
    title,
    ...(role ? { role } : {}),
    state: activity.state,
    statusText: activity.statusText,
    createdAt,
    updatedAt,
    ...(workspace ? { workspace } : {}),
    parentId: `host:claude:${parentUuid}`,
    depth: 1,
    ...(stepProgress ? { stepsDone: stepProgress.stepsDone, stepsTotal: stepProgress.stepsTotal } : {}),
  }

  const entry: HostTranscriptEntry = {
    nativeId: agentStem,
    filePath,
    meta: {
      id: subId,
      engineId: 'claude',
      cwd: workspace,
      workspaces: [workspace],
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

export function resolveClaudeSubagentEntry(
  homeDir: string,
  sessionId: string,
): HostTranscriptEntry | undefined {
  const prefix = 'host:claude-subagent:'
  if (!sessionId.startsWith(prefix)) return undefined
  const rest = sessionId.slice(prefix.length)
  const sepIdx = rest.lastIndexOf(':')
  if (sepIdx <= 0 || sepIdx >= rest.length - 1) return undefined
  const parentUuid = rest.slice(0, sepIdx)
  const agentStem = rest.slice(sepIdx + 1)
  if (!/^[A-Za-z0-9._-]{1,128}$/.test(parentUuid) || !/^[A-Za-z0-9._-]{1,128}$/.test(agentStem)) {
    return undefined
  }
  const projectsDir = path.join(homeDir, '.claude', 'projects')
  if (!fs.existsSync(projectsDir)) return undefined
  let slugs: fs.Dirent[]
  try {
    slugs = fs.readdirSync(projectsDir, { withFileTypes: true })
  } catch {
    return undefined
  }
  for (const s of slugs) {
    if (!s.isDirectory()) continue
    const candidate = path.join(projectsDir, s.name, parentUuid, 'subagents', `${agentStem}.jsonl`)
    try {
      const st = fs.statSync(candidate)
      if (!st.isFile()) continue
      const fallbackCwd = decodeClaudeProjectSlug(s.name)
      return buildClaudeSubagentSummary(
        parentUuid,
        agentStem,
        candidate,
        st.mtimeMs,
        st.birthtimeMs,
        fallbackCwd,
      ).entry
    } catch {
      // keep checking next project slug
    }
  }
  return undefined
}

export function inferClaudeSubagentActivity(
  tailLines: string[],
  mtimeMs: number,
): { state: SessionMeta['state']; statusText: string } {
  let lastToolActivity: string | undefined

  for (let i = tailLines.length - 1; i >= 0; i--) {
    try {
      const o = JSON.parse(tailLines[i]!) as {
        type?: string
        message?: { role?: string; content?: unknown }
      }
      if (!lastToolActivity && o.message?.role === 'assistant' && Array.isArray(o.message.content)) {
        const blocks = o.message.content as Array<Record<string, unknown>>
        for (let b = blocks.length - 1; b >= 0; b--) {
          const part = blocks[b]
          if (part?.type === 'tool_use' && typeof part.name === 'string') {
            const rawInput = part.input ? JSON.stringify(part.input) : undefined
            lastToolActivity = describeToolActivity(part.name, rawInput)
            break
          }
        }
      }
      if (lastToolActivity) break
    } catch {
      // ignore
    }
  }

  return subagentActivity(claudeTailSignal(tailLines), mtimeMs, lastToolActivity)
}

export function parseClaudeTranscript(
  sessionId: string,
  filePath: string,
  claudeEnv?: Record<string, string>,
): ParsedHostTranscript {
  const events: AgentEvent[] = []
  const workspaces: string[] = []
  const openTools = new Set<string>()
  const ignoredStepTools = new Set<string>()
  const stepTracker = new ClaudeTaskStepTracker()
  const planHygiene = new PlanTurnHygieneTracker()
  const queued: string[] = []
  let isRunning = false
  let turnStartedAt: number | undefined
  let latestModel: string | undefined
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
        type?: string
        subtype?: string
        operation?: string
        isMeta?: boolean
        content?: unknown
        cwd?: string
        uuid?: string
        timestamp?: string
        attachment?: {
          type?: string
          prompt?: string
          source_uuid?: string
          origin?: { kind?: string }
        }
        message?: {
          id?: string
          role?: string
          model?: string
          stop_reason?: string | null
          usage?: { input_tokens?: number; output_tokens?: number }
          content?: unknown
        }
      }
      if (typeof o.cwd === 'string' && o.cwd.trim()) {
        workspaces.push(o.cwd.trim())
      }
      if (typeof o.message?.model === 'string' && o.message.model.trim()) {
        latestModel = o.message.model.trim()
      }
      const ts = o.timestamp ? Date.parse(o.timestamp) || Date.now() : Date.now()
      if (o.type === 'queue-operation') {
        if (o.operation === 'enqueue' && typeof o.content === 'string') {
          const cleanQ = o.content.trim()
          if (cleanQ && !cleanQ.startsWith('<')) {
            queued.push(cleanQ)
          }
        } else if (o.operation === 'dequeue' || o.operation === 'remove' || o.operation === 'clear') {
          if (o.operation === 'clear') {
            queued.length = 0
          } else if (typeof o.content === 'string' && o.content.trim()) {
            const targetIdx = queued.indexOf(o.content.trim())
            if (targetIdx >= 0) queued.splice(targetIdx, 1)
            else if (queued.length > 0) queued.shift()
          } else if (queued.length > 0) {
            queued.shift()
          }
        }
        continue
      }
      // Claude Code 2.1.x mid-turn steering: queued follow-ups absorbed during an active turn are
      // removed from `queue-operation` (`reason: "absorbed_mid_turn"`) and recorded as `attachment.queued_command`
      // rather than `type: "user"`. Surface them right in place as `message.user` just like Claude's Shell UI!
      if (o.type === 'attachment' && o.attachment?.type === 'queued_command') {
        const qcPrompt = typeof o.attachment.prompt === 'string' ? o.attachment.prompt.trim() : ''
        if (qcPrompt && !qcPrompt.startsWith('<') && o.attachment.origin?.kind !== 'task-notification') {
          const qIdx = queued.indexOf(qcPrompt)
          if (qIdx >= 0) queued.splice(qIdx, 1)
          if (planHygiene.onUserMessage()) emitPlanCleared(ts)
          isRunning = true
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'message.user',
            messageId: o.uuid ?? o.attachment.source_uuid ?? `u_qc_${seq}`,
            text: qcPrompt,
          })
        }
        continue
      }
      if (o.type === 'api-request') {
        isRunning = true
        if (!turnStartedAt) turnStartedAt = ts
        continue
      }
      if (o.type === 'system' && o.subtype === 'turn_duration') {
        isRunning = false
        turnStartedAt = undefined
        if (planHygiene.onTurnEnded()) emitPlanCleared(ts)
        continue
      }
      if (o.type === 'user' && o.message?.role === 'user') {
        if (Array.isArray(o.message.content)) {
          for (const item of o.message.content as Array<Record<string, unknown>>) {
            if (item.type === 'tool_result' && typeof item.tool_use_id === 'string') {
              const callId = item.tool_use_id
              if (ignoredStepTools.has(callId)) {
                ignoredStepTools.delete(callId)
                const outText = extractClaudeToolResultText(item.content)
                stepTracker.applyToolResult(callId, outText)
                continue
              }
              if (openTools.has(callId)) {
                openTools.delete(callId)
                const outText = extractClaudeToolResultText(item.content)
                events.push({
                  seq: seq++,
                  sessionId,
                  ts,
                  type: 'tool.update',
                  toolCallId: callId,
                  status: item.is_error === true ? 'failed' : 'completed',
                  output: outText ? outText.slice(0, 1600) : undefined,
                })
              }
            }
          }
        }
        if (isClaudeUserMessageInterrupted(o.message.content)) {
          isRunning = false
          turnStartedAt = undefined
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
          openTools.clear()
          if (planHygiene.onTurnEnded()) emitPlanCleared(ts)
          continue
        }
        isRunning = true
        if (!turnStartedAt) turnStartedAt = ts
        const txt = extractClaudeText(o.message.content)
        const inlineImages = extractClaudeInlineImagePreviews(o.message.content)
        if (
          (txt || inlineImages.count > 0) &&
          !o.isMeta &&
          !txt.startsWith('<') &&
          !txt.startsWith('Base directory for this skill:') &&
          !txt.startsWith('Output token limit hit. Resume directly')
        ) {
          const qIdx = queued.indexOf(txt.trim())
          if (qIdx >= 0) queued.splice(qIdx, 1)
          if (planHygiene.onUserMessage()) emitPlanCleared(ts)
          turnStartedAt = ts
          const stagedParsed = parseStagedImagePromptHeader(txt, true)
          const totalImgs = Math.max(inlineImages.count, stagedParsed.imageCount)
          const mergedPreviews = (inlineImages.previews.length > 0
            ? inlineImages.previews
            : stagedParsed.imagePreviews ?? []
          ).slice(0, 4)
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'message.user',
            messageId: o.uuid ?? `u_${seq}`,
            text: stagedParsed.cleanText || `（已发送 ${totalImgs} 张图片）`,
            ...(totalImgs > 0 ? { imageCount: totalImgs } : {}),
            ...(mergedPreviews.length > 0 ? { imagePreviews: mergedPreviews } : {}),
          })
        }
      } else if (
        o.type !== 'api-request-blob' &&
        o.message?.role === 'assistant' &&
        Array.isArray(o.message.content)
      ) {
        const msgId = o.message.id ?? o.uuid ?? `a_${seq}`
        let sawToolUse = false
        for (const part of o.message.content as Array<Record<string, unknown>>) {
          if (part.type === 'thinking' && typeof part.thinking === 'string' && part.thinking.trim()) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'reasoning.delta',
              messageId: `${msgId}_think`,
              text: part.thinking.trim().slice(0, 1800),
            })
          } else if (part.type === 'text' && typeof part.text === 'string' && part.text.trim()) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.assistant.delta',
              messageId: msgId,
              text: part.text,
            })
          } else if (part.type === 'tool_use' && typeof part.name === 'string') {
            sawToolUse = true
            const callId = String(part.id ?? `tc_${seq}`)
            const stepKind = isPlanStepToolName(part.name)
            if (stepKind) {
              ignoredStepTools.add(callId)
              const entries = stepTracker.applyToolUse(part.name, callId, part.input)
              if (entries !== null) {
                events.push({
                  seq: seq++,
                  sessionId,
                  ts,
                  type: 'plan.updated',
                  entries,
                })
                planHygiene.onPlanUpdated(entries)
              }
              continue
            }
            if (part.input && typeof part.input === 'object') {
              extractProjectRootFromToolInput(part.input as Record<string, unknown>, workspaces)
            }
            const rawInput = part.input ? JSON.stringify(part.input) : undefined
            const normalized = classifyRawToolCall(part.name, rawInput)
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
          }
        }
        if (o.message.usage && (o.message.usage.input_tokens || o.message.usage.output_tokens)) {
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'usage',
            inputTokens: o.message.usage.input_tokens,
            outputTokens: o.message.usage.output_tokens,
          })
        }
        isRunning = sawToolUse || o.message.stop_reason === 'tool_use'
        if (!isRunning) {
          turnStartedAt = undefined
          if (planHygiene.onTurnEnded()) emitPlanCleared(ts)
        }
      }
    } catch {
      // ignore
    }
  }
  return {
    events,
    workspaces,
    isRunning: isRunning || openTools.size > 0,
    turnStartedAt,
    queuedPrompts: queued.length > 0 ? queued : undefined,
    model: resolveClaudeEffectiveModel(latestModel, claudeEnv),
  }
}

export function decodeClaudeProjectSlug(slug: string): string {
  if (!slug.startsWith('-')) return os.homedir()
  const tokens = slug.slice(1).split('-').filter(Boolean)
  let current = '/'
  let i = 0
  while (i < tokens.length) {
    // Greedily match longest hyphenated directory name on disk
    let matched: string | null = null
    let matchedEnd = i
    for (let j = tokens.length; j > i; j--) {
      const candidateName = tokens.slice(i, j).join('-')
      const candidatePath = path.join(current, candidateName)
      try {
        if (fs.statSync(candidatePath).isDirectory()) {
          matched = candidatePath
          matchedEnd = j
          break
        }
      } catch {
        // continue trying shorter hyphen join
      }
    }
    if (matched) {
      current = matched
      i = matchedEnd
    } else {
      break
    }
  }
  return current !== '/' ? current : os.homedir()
}

export function extractClaudeText(content: unknown): string {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .map((item: unknown) => {
        if (
          item &&
          typeof item === 'object' &&
          (item as { type?: unknown }).type === 'text' &&
          typeof (item as { text?: unknown }).text === 'string'
        ) {
          return (item as { text: string }).text
        }
        return ''
      })
      .filter(Boolean)
      .join('\n')
  }
  return ''
}

function extractClaudeInlineImagePreviews(content: unknown): { count: number; previews: string[] } {
  if (!Array.isArray(content)) return { count: 0, previews: [] }
  let count = 0
  const previews: string[] = []
  for (const item of content) {
    if (!item || typeof item !== 'object') continue
    const obj = item as {
      type?: unknown
      data?: unknown
      mimeType?: unknown
      media_type?: unknown
      source?: { type?: unknown; data?: unknown; media_type?: unknown; mediaType?: unknown }
    }
    if (obj.type === 'image') {
      count++
      if (previews.length >= 4) continue
      const rawData =
        typeof obj.data === 'string'
          ? obj.data.trim()
          : typeof obj.source?.data === 'string'
            ? obj.source.data.trim()
            : null
      if (!rawData) continue
      const mediaType =
        typeof obj.source?.media_type === 'string'
          ? obj.source.media_type
          : typeof obj.source?.mediaType === 'string'
            ? obj.source.mediaType
            : typeof obj.media_type === 'string'
              ? obj.media_type
              : typeof obj.mimeType === 'string'
                ? obj.mimeType
                : undefined
      if (rawData.length <= 260_000) {
        previews.push(rawData)
      } else {
        const downscaled = downscaleInlineImageBase64(rawData, mediaType)
        if (downscaled) {
          previews.push(downscaled)
        }
      }
    }
  }
  return { count, previews }
}

export function extractClaudeToolResultText(content: unknown): string {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .map((item: unknown) => {
        if (item && typeof item === 'object' && typeof (item as { text?: unknown }).text === 'string') {
          return (item as { text: string }).text
        }
        return ''
      })
      .filter(Boolean)
      .join('\n')
  }
  return ''
}

function isClaudeInterruptText(text: string): boolean {
  return /^\[Request interrupted by user(?:\b|\s|\])/i.test(text.trim())
}

export function isClaudeUserMessageInterrupted(content: unknown): boolean {
  const txt = extractClaudeText(content)
  if (txt && isClaudeInterruptText(txt)) return true
  if (Array.isArray(content)) {
    for (const item of content) {
      if (!item || typeof item !== 'object') continue
      const obj = item as { type?: unknown; is_error?: unknown; content?: unknown; text?: unknown }
      if (obj.type === 'text' && typeof obj.text === 'string' && isClaudeInterruptText(obj.text)) {
        return true
      }
      if (obj.type === 'tool_result' && obj.is_error === true) {
        const resTxt = extractClaudeToolResultText(obj.content)
        if (resTxt && isClaudeInterruptText(resTxt)) {
          return true
        }
      }
    }
  }
  return false
}

export function isClaudeTailActivelyRunning(tailLines: string[]): boolean {
  return claudeTailSignal(tailLines) === 'active'
}

function claudeTailSignal(tailLines: string[]): TailSignal {
  for (let i = tailLines.length - 1; i >= 0; i--) {
    try {
      const o = JSON.parse(tailLines[i]!) as {
        type?: string
        isApiErrorMessage?: boolean
        subtype?: string
        message?: { role?: string; stop_reason?: string | null; content?: unknown }
      }
      if (o.isApiErrorMessage || o.type === 'error') return 'error'
      if (o.type === 'system' && o.subtype === 'turn_duration') return 'done'
      if (o.type === 'api-request') return 'active'
      if (o.type === 'assistant' || o.message?.role === 'assistant') {
        if (o.message?.stop_reason === 'tool_use') return 'active'
        if (o.message?.stop_reason === 'end_turn' || o.message?.stop_reason === 'stop_sequence') return 'done'
        return 'unknown'
      }
      if (o.type === 'user') {
        return isClaudeUserMessageInterrupted(o.message?.content) ? 'cancelled' : 'active'
      }
    } catch {
      // ignore non-json
    }
  }
  return 'unknown'
}
