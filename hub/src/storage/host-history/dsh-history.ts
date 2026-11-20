import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import type { AgentEvent } from '@lework/protocol'
import {
  classifyRawToolCall,
  collectRecentFiles,
  extractProjectRootFromToolInput,
  mergeUniqueWorkspaces,
  type HostTranscriptEntry,
  type ParsedHostTranscript,
} from './common.ts'

function readDshZstdLines(filePath: string): string[] {
  const compressed = fs.readFileSync(filePath)
  const raw = zlib.zstdDecompressSync(compressed).toString('utf8')
  return raw.split('\n').filter(Boolean)
}

export function scanDshSessions(
  homeDir: string,
  out: Map<string, HostTranscriptEntry>,
): void {
  const sessionsDir = path.join(homeDir, '.dsh', 'sessions')
  if (!fs.existsSync(sessionsDir)) return

  const files = collectRecentFiles(
    sessionsDir,
    f => path.basename(f) === 'session.v4.jsonl.zstd',
    24,
  )
  for (const f of files) {
    try {
      const lines = readDshZstdLines(f.path)
      if (lines.length === 0) continue

      let nativeId = path.basename(path.dirname(f.path))
      let cwd = path.join(homeDir, 'work')
      let createdAt = Math.floor(f.birthtimeMs || f.mtimeMs)
      let delegationDepth = 0
      let isSeeded = false
      let title = ''
      let firstUserPrompt = ''
      let model: string | undefined
      let turnOpen = false

      for (const l of lines) {
        try {
          const o = JSON.parse(l) as {
            type?: string
            id?: string
            cwd?: string
            createdAt?: number
            delegationDepth?: number
            isSeeded?: boolean
            data?: {
              title?: string
              route?: { model?: string }
              source?: { kind?: string }
              content?: Array<{ type?: string; text?: string }>
            }
          }
          if (o.type === 'session') {
            if (typeof o.id === 'string') nativeId = o.id
            if (typeof o.cwd === 'string' && o.cwd.trim()) cwd = o.cwd.trim()
            if (typeof o.createdAt === 'number') createdAt = Math.floor(o.createdAt)
            if (typeof o.delegationDepth === 'number') delegationDepth = o.delegationDepth
            if (o.isSeeded === true) isSeeded = true
          } else if (o.type === 'session/title' && typeof o.data?.title === 'string' && o.data.title.trim()) {
            title = o.data.title.trim()
          } else if (!firstUserPrompt && o.type === 'user/message' && o.data?.source?.kind === 'user') {
            const txt = (o.data.content ?? [])
              .map(c => c.text ?? '')
              .join('\n')
              .replace(/\s+/g, ' ')
              .trim()
            if (txt && !txt.startsWith('<')) firstUserPrompt = txt.slice(0, 48)
          } else if (o.type === 'request/header' && typeof o.data?.route?.model === 'string') {
            model = o.data.route.model
          } else if (o.type === 'turn/start') {
            turnOpen = true
          } else if (o.type === 'turn/end') {
            turnOpen = false
          }
        } catch {
          // ignore line
        }
      }

      if (delegationDepth > 0 || isSeeded) continue
      const resolvedTitle = title || firstUserPrompt
      if (!resolvedTitle) continue

      const workspaces = mergeUniqueWorkspaces([cwd])
      const primaryCwd = workspaces[0] ?? cwd
      const isRunning = Date.now() - f.mtimeMs < 90_000 && turnOpen
      const sessionId = `host:dsh:${nativeId}`

      out.set(sessionId, {
        nativeId,
        filePath: f.path,
        meta: {
          id: sessionId,
          engineId: 'dsh',
          cwd: primaryCwd,
          workspaces: workspaces.length > 0 ? workspaces : [primaryCwd],
          title: resolvedTitle,
          state: isRunning ? 'running' : 'idle',
          createdAt,
          updatedAt: Math.floor(f.mtimeMs),
          lastSeq: 0,
          model,
          reasoningEffort: 'medium',
          mode: 'default',
          source: 'host',
        },
      })
    } catch {
      // ignore
    }
  }
}

export function parseDshTranscript(
  sessionId: string,
  filePath: string,
): ParsedHostTranscript {
  const events: AgentEvent[] = []
  const workspaces: string[] = []
  const openTools = new Set<string>()
  let turnOpen = false
  let turnStartedAt: number | undefined
  const lines = readDshZstdLines(filePath)
  let seq = 0

  for (const line of lines) {
    try {
      const o = JSON.parse(line) as {
        type?: string
        seq?: number
        time?: number
        cwd?: string
        data?: {
          turn?: number
          callId?: string
          name?: string
          arguments?: string
          id?: string
          source?: { kind?: string }
          content?: Array<{ type?: string; text?: string }>
          message?: {
            role?: string
            toolCallId?: string
            content?: Array<{ type?: string; text?: string }>
          }
          reason?: {
            kind?: string
            error?: { message?: string }
          }
        }
      }
      const ts = typeof o.time === 'number' ? o.time : Date.now()
      if (o.type === 'session' && typeof o.cwd === 'string' && o.cwd.trim()) {
        workspaces.push(o.cwd.trim())
      } else if (o.type === 'turn/start') {
        turnOpen = true
        turnStartedAt = ts
      } else if (o.type === 'turn/end') {
        turnOpen = false
        turnStartedAt = undefined
        if (o.data?.reason?.kind === 'error' && o.data.reason.error?.message) {
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'error',
            message: o.data.reason.error.message.slice(0, 500),
          })
        }
      } else if (o.type === 'user/message' && o.data?.source?.kind === 'user') {
        const txt = (o.data.content ?? [])
          .map(c => c.text ?? '')
          .join('\n')
          .trim()
        if (txt && !txt.startsWith('<')) {
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'message.user',
            messageId: o.data.id ?? `u_${o.seq ?? seq}`,
            text: txt,
          })
        }
      } else if (o.type === 'assistant/message' && Array.isArray(o.data?.message?.content)) {
        const msgId = `a_${o.seq ?? seq}`
        for (const part of o.data!.message!.content!) {
          if (part.type === 'reasoning' && typeof part.text === 'string' && part.text.trim()) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'reasoning.delta',
              messageId: `${msgId}_think`,
              text: part.text.trim().slice(0, 1800),
            })
          } else if (part.type === 'text' && typeof part.text === 'string' && part.text.trim()) {
            events.push({
              seq: seq++,
              sessionId,
              ts,
              type: 'message.assistant.delta',
              messageId: msgId,
              text: part.text.trim(),
            })
          }
        }
      } else if (o.type === 'tool/call' && typeof o.data?.name === 'string') {
        const callId = o.data.callId ?? `dsh_tc_${o.seq ?? seq}`
        const rawArgs = o.data.arguments
        if (rawArgs) {
          try {
            const parsedArgs = JSON.parse(rawArgs) as Record<string, unknown>
            extractProjectRootFromToolInput(parsedArgs, workspaces)
          } catch {
            // ignore
          }
        }
        const normalized = classifyRawToolCall(o.data.name, rawArgs)
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
      } else if (o.type === 'tool/result' && o.data?.message?.toolCallId) {
        const callId = o.data.message.toolCallId
        if (openTools.has(callId)) {
          openTools.delete(callId)
          const outText = (o.data.message.content ?? [])
            .map(c => c.text ?? '')
            .join('\n')
            .trim()
          events.push({
            seq: seq++,
            sessionId,
            ts,
            type: 'tool.update',
            toolCallId: callId,
            status: 'completed',
            output: outText ? outText.slice(0, 1600) : undefined,
          })
        }
      }
    } catch {
      // ignore
    }
  }
  return { events, workspaces, isRunning: turnOpen || openTools.size > 0, turnStartedAt }
}
