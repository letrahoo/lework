import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import type { AgentEvent, AgentEventBody } from '@lework/protocol'
import { SessionManager } from '../src/session-manager.ts'
import { parseAgyTranscript } from '../src/storage/host-history/agy-history.ts'
import { parseClaudeTranscript } from '../src/storage/host-history/claude-history.ts'
import { parseCodexTranscript } from '../src/storage/host-history/codex-history.ts'
import {
  PlanTurnHygieneTracker,
  extractLatestPlanFromEvents,
} from '../src/storage/host-history/plan-steps.ts'

const tmpDirs: string[] = []
const originalLeworkHome = process.env.LEWORK_HOME

function mkTmpDir(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-steps-lifecycle-'))
  tmpDirs.push(dir)
  return dir
}

afterEach(() => {
  if (originalLeworkHome === undefined) {
    delete process.env.LEWORK_HOME
  } else {
    process.env.LEWORK_HOME = originalLeworkHome
  }
  for (const d of tmpDirs.splice(0)) {
    fs.rmSync(d, { recursive: true, force: true })
  }
})

describe('Execution steps lifecycle & turn hygiene across Hub and host history', () => {
  it('extractLatestPlanFromEvents keeps interrupted plan for ended turn and clears it once a new turn begins', () => {
    const interruptedTurn: AgentEvent[] = [
      { seq: 0, sessionId: 's1', ts: 1000, type: 'message.user', messageId: 'u1', text: '安装到 iPhone' },
      {
        seq: 1,
        sessionId: 's1',
        ts: 1100,
        type: 'plan.updated',
        entries: [
          { content: '检查已连接设备', status: 'completed' },
          { content: '同步最新代码', status: 'completed' },
          { content: '编译签名并安装到 iPhone', status: 'in_progress' },
        ],
      },
      { seq: 2, sessionId: 's1', ts: 1500, type: 'turn.done', turnId: 't1', stopReason: 'cancelled' },
      { seq: 3, sessionId: 's1', ts: 1501, type: 'session.state', state: 'idle' },
    ]
    // Still the latest turn: preserve entries so UI can show collapsed `2/3 步 · 已中断`
    expect(extractLatestPlanFromEvents(interruptedTurn)).toHaveLength(3)

    // Once user starts Turn 2, the dead Turn 1 plan must clear immediately
    const withTurn2: AgentEvent[] = [
      ...interruptedTurn,
      { seq: 4, sessionId: 's1', ts: 2000, type: 'message.user', messageId: 'u2', text: '排查登录页样式' },
    ]
    expect(extractLatestPlanFromEvents(withTurn2)).toBeUndefined()

    // Ending via session.state(error | closed) also marks turn ended and clears on next turn
    for (const state of ['error', 'closed'] as const) {
      const endedByState: AgentEvent[] = [
        interruptedTurn[1]!,
        { seq: 2, sessionId: 's1', ts: 1600, type: 'session.state', state },
      ]
      expect(extractLatestPlanFromEvents(endedByState)).toHaveLength(3)
      expect(
        extractLatestPlanFromEvents([
          ...endedByState,
          { seq: 3, sessionId: 's1', ts: 2000, type: 'message.user', messageId: 'u_next', text: '新任务' },
        ]),
      ).toBeUndefined()
    }
  })

  it('PlanTurnHygieneTracker preserves mid-turn follow-up and clears stale plan once follow-up completes or another turn starts', () => {
    const midTurnFollowUp: AgentEvent[] = [
      {
        seq: 0,
        sessionId: 's1',
        ts: 1000,
        type: 'plan.updated',
        entries: [
          { content: 'Step 1', status: 'completed' },
          { content: 'Step 2', status: 'in_progress' },
        ],
      },
      { seq: 1, sessionId: 's1', ts: 1200, type: 'message.user', messageId: 'u_mid', text: '顺便看下日志' },
    ]
    // Immediate mid-turn follow-up keeps active plan visible
    expect(extractLatestPlanFromEvents(midTurnFollowUp)).toHaveLength(2)

    // If that post-plan turn ends without any fresh `plan.updated`, clear stale plan
    expect(
      extractLatestPlanFromEvents([
        ...midTurnFollowUp,
        { seq: 2, sessionId: 's1', ts: 1800, type: 'turn.done', turnId: 't1', stopReason: 'end_turn' },
      ]),
    ).toBeUndefined()

    // A second consecutive user turn without any fresh `plan.updated` also clears stale plan
    expect(
      extractLatestPlanFromEvents([
        ...midTurnFollowUp,
        { seq: 2, sessionId: 's1', ts: 1900, type: 'message.user', messageId: 'u_next', text: '换一个任务' },
      ]),
    ).toBeUndefined()

    // Direct tracker sanity check: fresh plan update resets post-user-turn flag
    const tracker = new PlanTurnHygieneTracker()
    tracker.onPlanUpdated([{ content: 'A', status: 'in_progress' }])
    expect(tracker.onUserMessage()).toBe(false)
    tracker.onPlanUpdated([{ content: 'A', status: 'completed' }])
    expect(tracker.onTurnEnded()).toBe(false)
    expect(tracker.currentEntries).toHaveLength(1)
    expect(tracker.onUserMessage()).toBe(true)
    expect(tracker.currentEntries).toHaveLength(0)
  })

  it('AGY host history keeps interrupted steps for Turn 1 and emits monotonic plan.updated([]) when Turn 2 starts', () => {
    const dir = mkTmpDir()
    const tp = path.join(dir, 'transcript.jsonl')
    const turn1Lines = [
      JSON.stringify({
        step_index: 1,
        type: 'USER_INPUT',
        created_at: '2026-11-27T02:00:00Z',
        content: '<USER_REQUEST>把最新版 Lework 装到 iPhone</USER_REQUEST>',
      }),
      JSON.stringify({
        step_index: 2,
        type: 'PLANNER_RESPONSE',
        status: 'DONE',
        created_at: '2026-11-27T02:00:10Z',
        tool_calls: [
          {
            name: 'update_steps',
            args: {
              steps: JSON.stringify([
                { status: 'done', title: '检查最新版本与已连接的 iPhone 设备' },
                { status: 'done', title: '同步最新代码准备 iOS 构建环境' },
                { status: 'in_progress', title: '编译签名并安装 Lework 到 iPhone' },
              ]),
            },
          },
          {
            name: 'run_command',
            args: { CommandLine: '"xcodebuild ..."' },
          },
        ],
      }),
      JSON.stringify({
        step_index: 3,
        type: 'GENERIC',
        status: 'DONE',
        created_at: '2026-11-27T02:00:11Z',
        content: 'OK',
      }),
      // User denied permission -> tool rejected & cancelled
      JSON.stringify({
        step_index: 4,
        type: 'GENERIC',
        status: 'CANCELLED',
        created_at: '2026-11-27T02:00:20Z',
        content: 'User rejected tool call',
      }),
    ]
    fs.writeFileSync(tp, turn1Lines.join('\n') + '\n', 'utf8')

    const parsedTurn1 = parseAgyTranscript('host:agy:conv-ios', tp)
    expect(parsedTurn1.isRunning).toBe(false)
    expect(extractLatestPlanFromEvents(parsedTurn1.events)).toHaveLength(3)
    const seqsBeforeTurn2 = parsedTurn1.events.map(e => ({ seq: e.seq, type: e.type }))

    // Now user moves on to Turn 2 (unrelated task without update_steps)
    const turn2Lines = [
      ...turn1Lines,
      JSON.stringify({
        step_index: 5,
        type: 'USER_INPUT',
        created_at: '2026-11-27T02:05:00Z',
        content: '<USER_REQUEST>帮我排查一下登录页样式</USER_REQUEST>',
      }),
      JSON.stringify({
        step_index: 6,
        type: 'PLANNER_RESPONSE',
        status: 'DONE',
        created_at: '2026-11-27T02:05:10Z',
        content: '已查明登录页样式原因。',
      }),
    ]
    fs.writeFileSync(tp, turn2Lines.join('\n') + '\n', 'utf8')

    const parsedTurn2 = parseAgyTranscript('host:agy:conv-ios', tp)
    expect(parsedTurn2.isRunning).toBe(false)
    // Prefix events must keep identical seqs (append-only stability for live tail streaming)
    expect(
      parsedTurn2.events.slice(0, seqsBeforeTurn2.length).map(e => ({ seq: e.seq, type: e.type })),
    ).toEqual(seqsBeforeTurn2)

    const clearEvent = parsedTurn2.events.find(
      e => e.type === 'plan.updated' && e.entries.length === 0,
    )
    expect(clearEvent).toBeDefined()
    expect(extractLatestPlanFromEvents(parsedTurn2.events)).toBeUndefined()
  })

  it('Claude host history preserves plan on mid-turn queued_command and clears interrupted plan on Turn 2', () => {
    const dir = mkTmpDir()
    const fp = path.join(dir, 'claude-interrupted.jsonl')
    const lines = [
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-27T03:00:00Z',
        message: { role: 'user', content: '执行三步发布' },
      }),
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T03:00:02Z',
        message: {
          id: 'm1',
          role: 'assistant',
          stop_reason: 'tool_use',
          content: [
            {
              type: 'tool_use',
              id: 'tu_todo',
              name: 'TodoWrite',
              input: {
                todos: [
                  { content: '检查依赖', status: 'completed' },
                  { content: '构建产物', status: 'in_progress' },
                ],
              },
            },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-27T03:00:03Z',
        message: {
          role: 'user',
          content: [{ type: 'tool_result', tool_use_id: 'tu_todo', content: 'Todos updated' }],
        },
      }),
      // Mid-turn steering while still running -> must NOT clear plan!
      JSON.stringify({
        type: 'attachment',
        timestamp: '2026-11-27T03:00:04Z',
        attachment: { type: 'queued_command', prompt: '记得打开 verbose 日志' },
      }),
    ]
    fs.writeFileSync(fp, lines.join('\n') + '\n', 'utf8')
    const midTurn = parseClaudeTranscript('host:claude:c1', fp)
    expect(extractLatestPlanFromEvents(midTurn.events)).toHaveLength(2)

    // Fresh update + user interrupt on Turn 1, then a brand new Turn 2
    const withInterruptAndTurn2 = [
      ...lines,
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T03:00:06Z',
        message: {
          id: 'm2',
          role: 'assistant',
          stop_reason: 'tool_use',
          content: [
            {
              type: 'tool_use',
              id: 'tu_todo2',
              name: 'TodoWrite',
              input: {
                todos: [
                  { content: '检查依赖', status: 'completed' },
                  { content: '构建产物', status: 'in_progress' },
                ],
              },
            },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-27T03:00:10Z',
        message: { role: 'user', content: '[Request interrupted by user]' },
      }),
    ]
    fs.writeFileSync(fp, withInterruptAndTurn2.join('\n') + '\n', 'utf8')
    const afterInterrupt = parseClaudeTranscript('host:claude:c1', fp)
    expect(afterInterrupt.isRunning).toBe(false)
    expect(extractLatestPlanFromEvents(afterInterrupt.events)).toHaveLength(2)

    const withTurn2 = [
      ...withInterruptAndTurn2,
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-27T03:01:00Z',
        message: { role: 'user', content: '现在帮我看下 README' },
      }),
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T03:01:05Z',
        message: {
          id: 'm3',
          role: 'assistant',
          stop_reason: 'end_turn',
          content: [{ type: 'text', text: 'README 内容正常。' }],
        },
      }),
    ]
    fs.writeFileSync(fp, withTurn2.join('\n') + '\n', 'utf8')
    const afterTurn2 = parseClaudeTranscript('host:claude:c1', fp)
    expect(afterTurn2.isRunning).toBe(false)
    expect(extractLatestPlanFromEvents(afterTurn2.events)).toBeUndefined()
  })

  it('Codex host history keeps aborted Turn 1 plan and clears it once Turn 2 starts', () => {
    const dir = mkTmpDir()
    const fp = path.join(dir, 'rollout-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.jsonl')
    const turn1 = [
      JSON.stringify({
        timestamp: '2026-11-27T04:00:00Z',
        type: 'event_msg',
        payload: { type: 'task_started' },
      }),
      JSON.stringify({
        timestamp: '2026-11-27T04:00:00Z',
        type: 'response_item',
        payload: {
          type: 'message',
          role: 'user',
          content: [{ type: 'input_text', text: '升级依赖并跑测试' }],
        },
      }),
      JSON.stringify({
        timestamp: '2026-11-27T04:00:05Z',
        type: 'response_item',
        payload: {
          type: 'function_call',
          call_id: 'call_plan_1',
          name: 'update_plan',
          arguments: JSON.stringify({
            plan: [
              { step: '检查版本', status: 'completed' },
              { step: '运行测试', status: 'in_progress' },
            ],
          }),
        },
      }),
      JSON.stringify({
        timestamp: '2026-11-27T04:00:06Z',
        type: 'response_item',
        payload: { type: 'function_call_output', call_id: 'call_plan_1', output: 'Plan updated' },
      }),
      JSON.stringify({
        timestamp: '2026-11-27T04:00:15Z',
        type: 'event_msg',
        payload: { type: 'turn_aborted' },
      }),
    ]
    fs.writeFileSync(fp, turn1.join('\n') + '\n', 'utf8')

    const parsed1 = parseCodexTranscript('host:codex:aborted', fp)
    expect(parsed1.isRunning).toBe(false)
    expect(extractLatestPlanFromEvents(parsed1.events)).toHaveLength(2)

    const turn2 = [
      ...turn1,
      JSON.stringify({
        timestamp: '2026-11-27T04:02:00Z',
        type: 'event_msg',
        payload: { type: 'task_started' },
      }),
      JSON.stringify({
        timestamp: '2026-11-27T04:02:00Z',
        type: 'response_item',
        payload: {
          type: 'message',
          role: 'user',
          content: [{ type: 'input_text', text: '换个问题：查一下 git log' }],
        },
      }),
      JSON.stringify({
        timestamp: '2026-11-27T04:02:05Z',
        type: 'event_msg',
        payload: { type: 'task_complete' },
      }),
    ]
    fs.writeFileSync(fp, turn2.join('\n') + '\n', 'utf8')

    const parsed2 = parseCodexTranscript('host:codex:aborted', fp)
    expect(parsed2.isRunning).toBe(false)
    expect(extractLatestPlanFromEvents(parsed2.events)).toBeUndefined()
  })

  it('SessionManager.cancel() records idle session.state so cancelled turn steps clear on the next prompt', async () => {
    const isolatedHome = mkTmpDir()
    process.env.LEWORK_HOME = isolatedHome
    const wsDir = mkTmpDir()

    let emitFn: ((ev: AgentEventBody) => void) | null = null
    let resolveTurn1: (() => void) | null = null
    let turnNumber = 0

    const sm = new SessionManager(
      [wsDir],
      [
        {
          id: 'claude',
          displayName: 'Claude Code',
          kind: 'cli',
          probe: async () => ({ installed: true, ready: true }),
          capabilities: () => ({
            permissions: true,
            images: true,
            modelSwitch: true,
            modes: true,
            history: true,
            cancel: true,
          }),
          openSession: async init => {
            emitFn = init.emit
            return {
              sessionId: init.sessionId,
              cwd: init.cwd,
              prompt: async (turnId: string) => {
                turnNumber++
                if (turnNumber === 1) {
                  emitFn?.({ type: 'session.state', state: 'running' })
                  emitFn?.({
                    type: 'plan.updated',
                    entries: [
                      { content: 'Prepare build', status: 'completed' },
                      { content: 'Install to iPhone', status: 'in_progress' },
                    ],
                  })
                  await new Promise<void>(r => {
                    resolveTurn1 = r
                  })
                } else {
                  emitFn?.({ type: 'session.state', state: 'running' })
                  emitFn?.({
                    type: 'message.assistant.delta',
                    messageId: 'a_2',
                    text: '新任务已处理完成。',
                  })
                  emitFn?.({ type: 'turn.done', turnId, stopReason: 'end_turn' })
                  emitFn?.({ type: 'session.state', state: 'idle' })
                }
              },
              cancel: async () => {
                // Intentionally does NOT emit `session.state(idle)` (like Claude/Codex adapters)
                resolveTurn1?.()
              },
              respondPermission: async () => {},
              close: async () => {
                resolveTurn1?.()
              },
            }
          },
        },
      ],
      {
        onEvent: () => {},
        onSessionUpdated: () => {},
      },
    )

    try {
      const sess = await sm.createSession({ engineId: 'claude', cwd: wsDir })
      await sm.prompt(sess.id, '安装到 iPhone')
      await sm.cancel(sess.id)

      // Immediately after cancel: Turn 1's interrupted plan is preserved for collapsed summary,
      // and an idle `session.state` event was recorded in the stream.
      const snapAfterCancel = sm.subscribeSnapshot(sess.id, -1)
      expect(snapAfterCancel.session.state).toBe('idle')
      expect(snapAfterCancel.events.at(-1)).toMatchObject({ type: 'session.state', state: 'idle' })
      expect(snapAfterCancel.latestPlan).toHaveLength(2)

      // Next prompt supersedes Turn 1 -> stale interrupted plan clears cleanly!
      await sm.prompt(sess.id, '排查登录页问题')
      await new Promise(r => setTimeout(r, 30))
      const snapAfterTurn2 = sm.subscribeSnapshot(sess.id, -1)
      expect(snapAfterTurn2.latestPlan).toBeUndefined()
    } finally {
      await sm.closeAll()
    }
  })

  it('Claude host history ignores intermediate stop_reason:null streaming records before and after queued_command', () => {
    const dir = mkTmpDir()
    const fp = path.join(dir, 'claude-streaming-null-stop.jsonl')
    const baseLines = [
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-27T05:00:00Z',
        message: { role: 'user', content: '执行多步重构' },
      }),
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T05:00:01Z',
        message: {
          id: 'm1',
          role: 'assistant',
          stop_reason: 'tool_use',
          content: [
            {
              type: 'tool_use',
              id: 'tu_todo_1',
              name: 'TodoWrite',
              input: {
                todos: [
                  { content: '分析调用链', status: 'completed' },
                  { content: '修改核心状态机', status: 'in_progress' },
                ],
              },
            },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-27T05:00:02Z',
        message: {
          role: 'user',
          content: [{ type: 'tool_result', tool_use_id: 'tu_todo_1', content: 'Todos updated' }],
        },
      }),
      // Intermediate streaming thinking/text chunks with `stop_reason: null` BEFORE queued_command
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T05:00:03Z',
        message: {
          id: 'm2',
          role: 'assistant',
          stop_reason: null,
          content: [{ type: 'thinking', thinking: '先检查下游影响范围…' }],
        },
      }),
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T05:00:04Z',
        message: {
          id: 'm2',
          role: 'assistant',
          stop_reason: null,
          content: [{ type: 'text', text: '正在定位相关文件。' }],
        },
      }),
      // Mid-turn steering absorbed while still in the same turn -> must NOT clear plan!
      JSON.stringify({
        type: 'attachment',
        timestamp: '2026-11-27T05:00:05Z',
        attachment: { type: 'queued_command', prompt: '顺便补上单测' },
      }),
      // Intermediate streaming thinking/text chunks with `stop_reason: null` AFTER queued_command
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T05:00:06Z',
        message: {
          id: 'm3',
          role: 'assistant',
          stop_reason: null,
          content: [{ type: 'thinking', thinking: '收到补充要求，继续执行…' }],
        },
      }),
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T05:00:07Z',
        message: {
          id: 'm3',
          role: 'assistant',
          stop_reason: null,
          content: [{ type: 'text', text: '好的，会一并补齐单测。' }],
        },
      }),
    ]
    fs.writeFileSync(fp, baseLines.join('\n') + '\n', 'utf8')

    const midStream = parseClaudeTranscript('host:claude:stream-null', fp)
    expect(midStream.events.some(e => e.type === 'plan.updated' && e.entries.length === 0)).toBe(false)
    expect(extractLatestPlanFromEvents(midStream.events)).toEqual([
      { content: '分析调用链', status: 'completed' },
      { content: '修改核心状态机', status: 'in_progress' },
    ])

    // Once an explicit terminal `stop_reason: 'end_turn'` arrives without fresh plan update,
    // the post-queued_command turn ends and clears the stale plan cleanly.
    const withExplicitEnd = [
      ...baseLines,
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T05:00:15Z',
        message: {
          id: 'm4',
          role: 'assistant',
          stop_reason: 'end_turn',
          content: [{ type: 'text', text: '重构和单测均已完成。' }],
        },
      }),
    ]
    fs.writeFileSync(fp, withExplicitEnd.join('\n') + '\n', 'utf8')
    const afterEnd = parseClaudeTranscript('host:claude:stream-null', fp)
    expect(afterEnd.isRunning).toBe(false)
    expect(extractLatestPlanFromEvents(afterEnd.events)).toBeUndefined()
  })

  it('Claude TaskCreate in Turn 2 does not resurrect interrupted Turn 1 tasks after plan clear', () => {
    const dir = mkTmpDir()
    const fp = path.join(dir, 'claude-taskcreate-reset.jsonl')
    const lines = [
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-27T05:10:00Z',
        message: { role: 'user', content: 'Turn 1 任务' },
      }),
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T05:10:02Z',
        message: {
          id: 'm1',
          role: 'assistant',
          stop_reason: 'tool_use',
          content: [
            {
              type: 'tool_use',
              id: 'tu_c1',
              name: 'TaskCreate',
              input: { subject: '旧任务步骤一', status: 'in_progress' },
            },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-27T05:10:03Z',
        message: {
          role: 'user',
          content: [{ type: 'tool_result', tool_use_id: 'tu_c1', content: 'Task #1 created' }],
        },
      }),
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-27T05:10:08Z',
        message: { role: 'user', content: '[Request interrupted by user]' },
      }),
      // Turn 2 starts fresh and creates a brand new task
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-27T05:11:00Z',
        message: { role: 'user', content: 'Turn 2 新任务' },
      }),
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-27T05:11:02Z',
        message: {
          id: 'm2',
          role: 'assistant',
          stop_reason: 'tool_use',
          content: [
            {
              type: 'tool_use',
              id: 'tu_c2',
              name: 'TaskCreate',
              input: { subject: '新任务步骤一', status: 'in_progress' },
            },
          ],
        },
      }),
    ]
    fs.writeFileSync(fp, lines.join('\n') + '\n', 'utf8')

    const parsed = parseClaudeTranscript('host:claude:task-reset', fp)
    const latest = extractLatestPlanFromEvents(parsed.events)
    expect(latest).toEqual([{ content: '新任务步骤一', status: 'in_progress' }])
  })

  it('Codex turn_aborted fails open tool calls and clears isRunning cleanly', () => {
    const dir = mkTmpDir()
    const fp = path.join(dir, 'rollout-12345678-1234-1234-1234-1234567890ab.jsonl')
    const lines = [
      JSON.stringify({
        timestamp: '2026-11-27T05:20:00Z',
        type: 'event_msg',
        payload: { type: 'task_started' },
      }),
      JSON.stringify({
        timestamp: '2026-11-27T05:20:01Z',
        type: 'response_item',
        payload: {
          type: 'message',
          role: 'user',
          content: [{ type: 'input_text', text: '运行集成构建' }],
        },
      }),
      JSON.stringify({
        timestamp: '2026-11-27T05:20:02Z',
        type: 'response_item',
        payload: {
          type: 'function_call',
          call_id: 'call_shell_unclosed',
          name: 'shell',
          arguments: JSON.stringify({ command: 'xcodebuild archive' }),
        },
      }),
      JSON.stringify({
        timestamp: '2026-11-27T05:20:10Z',
        type: 'event_msg',
        payload: { type: 'turn_aborted' },
      }),
    ]
    fs.writeFileSync(fp, lines.join('\n') + '\n', 'utf8')

    const parsed = parseCodexTranscript('host:codex:abort-open-tool', fp)
    expect(parsed.isRunning).toBe(false)
    const failedUpdate = parsed.events.find(
      e => e.type === 'tool.update' && e.toolCallId === 'call_shell_unclosed',
    )
    expect(failedUpdate).toMatchObject({
      type: 'tool.update',
      toolCallId: 'call_shell_unclosed',
      status: 'failed',
      output: '已停止',
    })
  })

  it('SessionManager.prompt() drains in-flight cancel() before starting Turn 2 and clears Turn 1 cancelled plan', async () => {
    const isolatedHome = mkTmpDir()
    process.env.LEWORK_HOME = isolatedHome
    const wsDir = mkTmpDir()

    let emitFn: ((ev: AgentEventBody) => void) | null = null
    const timeline: string[] = []
    const deferred: {
      resolveCancel: (() => void) | null
      resolveTurn1: (() => void) | null
      resolveTurn2: (() => void) | null
    } = {
      resolveCancel: null,
      resolveTurn1: null,
      resolveTurn2: null,
    }
    let turnNumber = 0

    const sm = new SessionManager(
      [wsDir],
      [
        {
          id: 'claude',
          displayName: 'Claude Code',
          kind: 'cli',
          probe: async () => ({ installed: true, ready: true }),
          capabilities: () => ({
            permissions: true,
            images: true,
            modelSwitch: true,
            modes: true,
            history: true,
            cancel: true,
          }),
          openSession: async init => {
            emitFn = init.emit
            return {
              sessionId: init.sessionId,
              cwd: init.cwd,
              prompt: async (_turnId: string, text: string) => {
                turnNumber++
                timeline.push(`prompt:start:${turnNumber}:${text}`)
                emitFn?.({ type: 'session.state', state: 'running' })
                if (turnNumber === 1) {
                  emitFn?.({
                    type: 'plan.updated',
                    entries: [
                      { content: 'Turn 1 Step 1', status: 'completed' },
                      { content: 'Turn 1 Step 2', status: 'in_progress' },
                    ],
                  })
                  await new Promise<void>(r => {
                    deferred.resolveTurn1 = r
                  })
                } else {
                  await new Promise<void>(r => {
                    deferred.resolveTurn2 = r
                  })
                }
              },
              cancel: async () => {
                timeline.push('cancel:start')
                deferred.resolveTurn1?.()
                await new Promise<void>(r => {
                  deferred.resolveCancel = r
                })
                timeline.push('cancel:done')
              },
              respondPermission: async () => {},
              close: async () => {
                deferred.resolveTurn1?.()
                deferred.resolveTurn2?.()
                deferred.resolveCancel?.()
              },
            }
          },
        },
      ],
      {
        onEvent: () => {},
        onSessionUpdated: () => {},
      },
    )

    try {
      const sess = await sm.createSession({ engineId: 'claude', cwd: wsDir })
      await sm.prompt(sess.id, 'Turn 1')
      // Begin cancel(), suspending inside req.handle.cancel()
      const cancelPromise = sm.cancel(sess.id)
      // Immediately dispatch Turn 2 while cancel() is still suspended
      const turn2PromptPromise = sm.prompt(sess.id, 'Turn 2')
      await new Promise(r => setTimeout(r, 25))

      // Zero concurrent overlap: handle.prompt(Turn 2) MUST NOT have started yet!
      expect(timeline).toEqual(['prompt:start:1:Turn 1', 'cancel:start'])

      // Resolve cancel -> cancel records session.state('idle'), then Turn 2 begins cleanly
      deferred.resolveCancel?.()
      await cancelPromise
      await turn2PromptPromise

      expect(timeline).toEqual([
        'prompt:start:1:Turn 1',
        'cancel:start',
        'cancel:done',
        'prompt:start:2:Turn 2',
      ])
      expect(sm.getSessionMeta(sess.id)?.state).toBe('running')

      const snap = sm.subscribeSnapshot(sess.id, -1)
      // Turn 1's idle boundary was recorded BEFORE Turn 2's message.user, clearing Turn 1's stale plan!
      const idleIdx = snap.events.findIndex(e => e.type === 'session.state' && e.state === 'idle')
      const turn2UserIdx = snap.events.findIndex(e => e.type === 'message.user' && e.text === 'Turn 2')
      expect(idleIdx).toBeGreaterThanOrEqual(0)
      expect(turn2UserIdx).toBeGreaterThan(idleIdx)
      expect(snap.latestPlan).toBeUndefined()
    } finally {
      await sm.closeAll()
    }
  })

  it('SessionManager.cancel() skips late idle event when superseded by another cancel or session close', async () => {
    const isolatedHome = mkTmpDir()
    process.env.LEWORK_HOME = isolatedHome
    const wsDir = mkTmpDir()

    const emittedEvents: AgentEvent[] = []
    const cancelResolvers: Array<() => void> = []
    let resolveTurn1: (() => void) | null = null

    const sm = new SessionManager(
      [wsDir],
      [
        {
          id: 'claude',
          displayName: 'Claude Code',
          kind: 'cli',
          probe: async () => ({ installed: true, ready: true }),
          capabilities: () => ({
            permissions: true,
            images: true,
            modelSwitch: true,
            modes: true,
            history: true,
            cancel: true,
          }),
          openSession: async init => ({
            sessionId: init.sessionId,
            cwd: init.cwd,
            prompt: async () => {
              init.emit({ type: 'session.state', state: 'running' })
              await new Promise<void>(r => {
                resolveTurn1 = r
              })
            },
            cancel: async () => {
              resolveTurn1?.()
              await new Promise<void>(r => {
                cancelResolvers.push(r)
              })
            },
            respondPermission: async () => {},
            close: async () => {
              resolveTurn1?.()
            },
          }),
        },
      ],
      {
        onEvent: ev => emittedEvents.push(ev),
        onSessionUpdated: () => {},
      },
    )

    try {
      // Case 1: Superseding cancel() advances turnGeneration; only the latest cancel emits idle
      const s1 = await sm.createSession({ engineId: 'claude', cwd: wsDir })
      await sm.prompt(s1.id, 'Turn 1')
      const firstCancel = sm.cancel(s1.id)
      const secondCancel = sm.cancel(s1.id)
      expect(cancelResolvers).toHaveLength(2)

      // Resolving the stale first cancel must NOT emit idle
      cancelResolvers[0]!()
      await firstCancel
      expect(emittedEvents.filter(e => e.sessionId === s1.id && e.type === 'session.state' && e.state === 'idle')).toHaveLength(0)

      // Resolving the active second cancel emits exactly one idle event
      cancelResolvers[1]!()
      await secondCancel
      expect(emittedEvents.filter(e => e.sessionId === s1.id && e.type === 'session.state' && e.state === 'idle')).toHaveLength(1)

      // Case 2: Closing session while cancel() is suspended suppresses late idle emission
      const s2 = await sm.createSession({ engineId: 'claude', cwd: wsDir })
      await sm.prompt(s2.id, 'Turn 1')
      const hangingCancel = sm.cancel(s2.id)
      await sm.closeSession(s2.id)
      cancelResolvers[2]!()
      await hangingCancel
      expect(emittedEvents.filter(e => e.sessionId === s2.id && e.type === 'session.state' && e.state === 'idle')).toHaveLength(0)
    } finally {
      for (const r of cancelResolvers) r()
      await sm.closeAll()
    }
  })
})
