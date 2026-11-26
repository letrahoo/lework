import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import type { AgentEvent, SessionMeta } from '@lework/protocol'
import { sliceSessionWindow } from '../src/session-manager.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'
import { parseAgyTranscript } from '../src/storage/host-history/agy-history.ts'

function makeSessionMeta(id: string, lastSeq: number): SessionMeta {
  return {
    id,
    engineId: 'agy',
    cwd: '/tmp/demo',
    title: 'Streaming Pagination Test',
    state: 'idle',
    createdAt: 1_000,
    updatedAt: 5_000,
    lastSeq,
    source: 'hub',
  }
}

describe('sliceSessionWindow streaming message integrity', () => {
  it('never splits a long streaming assistant reply (>220 deltas) on initial paged subscribe', () => {
    const sid = 'sess_stream_long'
    const events: AgentEvent[] = []
    let seq = 0

    events.push({
      seq: seq++,
      sessionId: sid,
      ts: 1_000,
      type: 'message.user',
      messageId: 'u_turn_1',
      text: 'https://www.anthropic.com/research/migrating-api-to-rust 帮我介绍下这篇文章',
    })

    for (let i = 0; i < 11; i++) {
      const callId = `agy_tc_${i}`
      events.push({
        seq: seq++,
        sessionId: sid,
        ts: 1_100 + i * 10,
        type: 'tool.call',
        toolCallId: callId,
        title: i === 2 ? 'Skill' : 'search_web',
        kind: i === 2 ? 'other' : 'fetch',
        status: 'in_progress',
      })
      events.push({
        seq: seq++,
        sessionId: sid,
        ts: 1_105 + i * 10,
        type: 'tool.update',
        toolCallId: callId,
        status: 'completed',
        output: 'ok',
      })
    }

    // Emit 360 streaming assistant delta chunks sharing one messageId (exceeding limit=220 and 160-step backtrack)
    const chunks: string[] = []
    for (let i = 0; i < 360; i++) {
      const piece = i === 0 ? '### 核心结论\n前半段完整正文-' : i === 359 ? '尾段结束。' : `段${i}_`
      chunks.push(piece)
      events.push({
        seq: seq++,
        sessionId: sid,
        ts: 2_000 + i,
        type: 'message.assistant.delta',
        messageId: 'msg_turn_1',
        text: piece,
      })
    }

    const meta = makeSessionMeta(sid, seq - 1)
    const snap = sliceSessionWindow(meta, events, -1, 0, { limit: 220 })

    // Must snap all the way back past all 360 deltas to the start of the turn (`message.user`)
    expect(snap.events[0]?.type).toBe('message.user')
    expect(snap.hasMoreBefore).toBe(false)
    const assembled = snap.events
      .filter((e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
      .map(e => e.text)
      .join('')
    expect(assembled).toBe(chunks.join(''))
  })

  it('keeps multi-turn reverse pagination intact without splitting messages or orphaning tool.update', () => {
    const sid = 'sess_multi_turn_stream'
    const events: AgentEvent[] = []
    let seq = 0

    for (let turn = 1; turn <= 3; turn++) {
      events.push({
        seq: seq++,
        sessionId: sid,
        ts: turn * 10_000,
        type: 'message.user',
        messageId: `u_${turn}`,
        text: `第 ${turn} 轮问题`,
      })
      events.push({
        seq: seq++,
        sessionId: sid,
        ts: turn * 10_000 + 1,
        type: 'tool.call',
        toolCallId: `tc_${turn}`,
        title: 'search_web',
        kind: 'fetch',
        status: 'in_progress',
      })
      events.push({
        seq: seq++,
        sessionId: sid,
        ts: turn * 10_000 + 2,
        type: 'tool.update',
        toolCallId: `tc_${turn}`,
        status: 'completed',
        output: `res_${turn}`,
      })
      for (let d = 0; d < 260; d++) {
        events.push({
          seq: seq++,
          sessionId: sid,
          ts: turn * 10_000 + 10 + d,
          type: 'message.assistant.delta',
          messageId: `msg_${turn}`,
          text: d === 0 ? `[T${turn}:HEAD]` : d === 259 ? `[T${turn}:TAIL]` : 'x',
        })
      }
    }

    const meta = makeSessionMeta(sid, seq - 1)
    const page1 = sliceSessionWindow(meta, events, -1, 0, { limit: 220 })
    expect(page1.events[0]?.type).toBe('message.user')
    expect((page1.events[0] as Extract<AgentEvent, { type: 'message.user' }>).messageId).toBe('u_3')
    expect(page1.hasMoreBefore).toBe(true)

    const p1Text = page1.events
      .filter((e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
      .map(e => e.text)
      .join('')
    expect(p1Text.startsWith('[T3:HEAD]')).toBe(true)
    expect(p1Text.endsWith('[T3:TAIL]')).toBe(true)

    const page2 = sliceSessionWindow(meta, events, -1, 0, { beforeSeq: page1.earliestSeq, limit: 220 })
    expect(page2.events[0]?.type).toBe('message.user')
    expect((page2.events[0] as Extract<AgentEvent, { type: 'message.user' }>).messageId).toBe('u_2')
    const p2Text = page2.events
      .filter((e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
      .map(e => e.text)
      .join('')
    expect(p2Text.startsWith('[T2:HEAD]')).toBe(true)
    expect(p2Text.endsWith('[T2:TAIL]')).toBe(true)

    const page3 = sliceSessionWindow(meta, events, -1, 0, { beforeSeq: page2.earliestSeq, limit: 220 })
    expect(page3.events[0]?.type).toBe('message.user')
    expect((page3.events[0] as Extract<AgentEvent, { type: 'message.user' }>).messageId).toBe('u_1')
    expect(page3.hasMoreBefore).toBe(false)
    const p3Text = page3.events
      .filter((e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
      .map(e => e.text)
      .join('')
    expect(p3Text.startsWith('[T1:HEAD]')).toBe(true)
    expect(p3Text.endsWith('[T1:TAIL]')).toBe(true)
  })

  it('keeps aroundSeq windows intact on both leading and trailing streaming message boundaries', () => {
    const sid = 'sess_around_stream'
    const events: AgentEvent[] = []
    let seq = 0

    for (let turn = 1; turn <= 4; turn++) {
      events.push({
        seq: seq++,
        sessionId: sid,
        ts: turn * 10_000,
        type: 'message.user',
        messageId: `u_${turn}`,
        text: `Q${turn}`,
      })
      for (let d = 0; d < 250; d++) {
        events.push({
          seq: seq++,
          sessionId: sid,
          ts: turn * 10_000 + d,
          type: 'message.assistant.delta',
          messageId: `msg_${turn}`,
          text: d === 0 ? `<START_${turn}>` : d === 249 ? `<END_${turn}>` : '.',
        })
      }
    }

    const meta = makeSessionMeta(sid, seq - 1)
    // Jump right into the middle of Turn 2's streaming reply
    const midTurn2Seq = 1 + 250 + 1 + 125
    const snap = sliceSessionWindow(meta, events, -1, 0, { aroundSeq: midTurn2Seq, limit: 120 })
    const turn2Text = snap.events
      .filter(
        (e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> =>
          e.type === 'message.assistant.delta' && e.messageId === 'msg_2',
      )
      .map(e => e.text)
      .join('')
    expect(turn2Text.startsWith('<START_2>')).toBe(true)
    expect(turn2Text.endsWith('<END_2>')).toBe(true)

    // Any streaming message that started in the window must also finish cleanly in the window
    const lastDelta = [...snap.events]
      .reverse()
      .find((e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
    expect(lastDelta).toBeDefined()
    const tailMsgText = snap.events
      .filter(
        (e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> =>
          e.type === 'message.assistant.delta' && e.messageId === lastDelta!.messageId,
      )
      .map(e => e.text)
      .join('')
    const tailTurnNum = lastDelta!.messageId.replace('msg_', '')
    expect(tailMsgText.endsWith(`<END_${tailTurnNum}>`)).toBe(true)
  })
})

describe('parseAgyTranscript full transcript hydration', () => {
  it('hydrates truncated PLANNER_RESPONSE and USER_INPUT fields from sibling transcript_full.jsonl', () => {
    const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-agy-full-'))
    try {
      const convId = 'conv-truncated-hydrate'
      const logsDir = path.join(tmpHome, '.gemini', 'antigravity-cli', 'brain', convId, '.system_generated', 'logs')
      fs.mkdirSync(logsDir, { recursive: true })
      const compactPath = path.join(logsDir, 'transcript.jsonl')
      const fullPath = path.join(logsDir, 'transcript_full.jsonl')

      const fullReply = '### 第一部分：核心结论\n\n' + '完整正文内容。'.repeat(200) + '\n\n### 第三部分：收尾总结'
      const compactReply = '### 第一部分：核心结论\n\n完整正文\n<truncated 1788 bytes>\n### 第三部分：收尾总结'

      const compactLines = [
        JSON.stringify({
          step_index: 0,
          type: 'USER_INPUT',
          status: 'DONE',
          created_at: '2026-11-26T12:00:43Z',
          content: '<USER_REQUEST>\n帮我介绍下这篇文章\n</USER_REQUEST>',
        }),
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          status: 'DONE',
          created_at: '2026-11-26T12:03:04Z',
          content: compactReply,
          thinking: 'short\n<truncated 900 bytes>\nend',
          truncated_fields: ['content', 'thinking'],
        }),
      ]
      const fullLines = [
        compactLines[0]!,
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          status: 'DONE',
          created_at: '2026-11-26T12:03:04Z',
          content: fullReply,
          thinking: 'full reasoning body without truncation',
        }),
      ]

      fs.writeFileSync(compactPath, compactLines.join('\n') + '\n', 'utf8')
      fs.writeFileSync(fullPath, fullLines.join('\n') + '\n', 'utf8')

      const parsed = parseAgyTranscript(`host:agy:${convId}`, compactPath)
      const assistantEv = parsed.events.find(e => e.type === 'message.assistant.delta')
      expect(assistantEv?.type).toBe('message.assistant.delta')
      if (assistantEv?.type === 'message.assistant.delta') {
        expect(assistantEv.text).toBe(fullReply)
        expect(assistantEv.text).not.toContain('<truncated')
      }
      const reasoningEv = parsed.events.find(e => e.type === 'reasoning.delta')
      expect(reasoningEv?.type).toBe('reasoning.delta')
      if (reasoningEv?.type === 'reasoning.delta') {
        expect(reasoningEv.text).toBe('full reasoning body without truncation')
      }

      // Also verify HostTaskInspector invalidates cache when sibling transcript_full.jsonl updates later
      const inspector = new HostTaskInspector({ homeDir: tmpHome })
      inspector.discoverHostSessions(true)
      const firstRead = inspector.readHostSessionEvents(`host:agy:${convId}`)
      const firstAssistant = firstRead?.events.find(e => e.type === 'message.assistant.delta')
      expect(firstAssistant?.type === 'message.assistant.delta' ? firstAssistant.text : '').toBe(fullReply)

      const updatedFullReply = fullReply + '\n\n补充段落'
      fs.writeFileSync(
        fullPath,
        [
          compactLines[0]!,
          JSON.stringify({
            step_index: 1,
            type: 'PLANNER_RESPONSE',
            status: 'DONE',
            created_at: '2026-11-26T12:03:04Z',
            content: updatedFullReply,
            thinking: 'full reasoning body without truncation',
          }),
        ].join('\n') + '\n',
        'utf8',
      )
      const secondRead = inspector.readHostSessionEvents(`host:agy:${convId}`)
      const secondAssistant = secondRead?.events.find(e => e.type === 'message.assistant.delta')
      expect(secondAssistant?.type === 'message.assistant.delta' ? secondAssistant.text : '').toBe(updatedFullReply)
    } finally {
      fs.rmSync(tmpHome, { recursive: true, force: true })
    }
  })

  it('gracefully parses transcript.jsonl when sibling transcript_full.jsonl does not exist', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-agy-compact-only-'))
    try {
      const compactPath = path.join(tmpDir, 'transcript.jsonl')
      fs.writeFileSync(
        compactPath,
        [
          JSON.stringify({
            step_index: 0,
            type: 'USER_INPUT',
            status: 'DONE',
            created_at: '2026-11-26T12:00:43Z',
            content: '<USER_REQUEST>\nhello\n</USER_REQUEST>',
          }),
          JSON.stringify({
            step_index: 1,
            type: 'PLANNER_RESPONSE',
            status: 'DONE',
            created_at: '2026-11-26T12:00:50Z',
            content: 'compact fallback content',
            truncated_fields: ['content'],
          }),
        ].join('\n') + '\n',
        'utf8',
      )
      const parsed = parseAgyTranscript('host:agy:compact-only', compactPath)
      const assistantEv = parsed.events.find(e => e.type === 'message.assistant.delta')
      expect(assistantEv?.type === 'message.assistant.delta' ? assistantEv.text : '').toBe('compact fallback content')
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true })
    }
  })
})
