import fs from 'node:fs'
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { afterEach, describe, expect, it } from 'vitest'
import { AgyEngineAdapter } from '../src/engines/agy-adapter.ts'
import { parseAgyTranscript, scanAgySessions } from '../src/storage/host-history/agy-history.ts'
import { parseClaudeTranscript, scanClaudeSessions } from '../src/storage/host-history/claude-history.ts'
import { parseCodexTranscript } from '../src/storage/host-history/codex-history.ts'
import { classifyRawToolCall, describeToolActivity, type HostTranscriptEntry } from '../src/storage/host-history/common.ts'
import { extractLatestPlanFromEvents } from '../src/storage/host-history/plan-steps.ts'
import { parseLiveSpinnerLine } from '../src/storage/terminal-bridge.ts'
import type { AgentEvent, AgentEventBody } from '@lework/protocol'

const tmpDirs: string[] = []
const nodeRequire = createRequire(import.meta.url)

function mkTmpDir(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-steps-test-'))
  tmpDirs.push(dir)
  return dir
}

afterEach(() => {
  for (const d of tmpDirs.splice(0)) {
    fs.rmSync(d, { recursive: true, force: true })
  }
})

describe('Live Steps parsing & SubAgent enrichment across engines', () => {
  it('parses AGY update_steps into plan.updated without corrupting parallel tool responses', () => {
    const dir = mkTmpDir()
    const tp = path.join(dir, 'transcript.jsonl')
    const lines = [
      JSON.stringify({
        step_index: 1,
        type: 'USER_INPUT',
        created_at: '2026-11-26T08:39:00Z',
        content: '<USER_REQUEST>更新本机和 Devbox 服务</USER_REQUEST>',
      }),
      JSON.stringify({
        step_index: 2,
        type: 'PLANNER_RESPONSE',
        status: 'DONE',
        created_at: '2026-11-26T08:39:31Z',
        tool_calls: [
          {
            name: 'update_steps',
            args: {
              steps: JSON.stringify([
                { status: 'in_progress', title: 'Sync local main and clean merged branches' },
                { status: 'pending', title: 'Update and verify local Mac Hub service' },
                { status: 'skipped', title: 'Optional cache prune' },
              ]),
            },
          },
          {
            name: 'run_command',
            args: {
              CommandLine: '"git status -sb"',
              toolSummary: '"Check git status"',
            },
          },
        ],
      }),
      // First GENERIC is update_steps's `"OK"` response
      JSON.stringify({
        step_index: 3,
        type: 'GENERIC',
        status: 'DONE',
        created_at: '2026-11-26T08:39:32Z',
        content: 'OK',
      }),
      // Second GENERIC is run_command's real stdout
      JSON.stringify({
        step_index: 4,
        type: 'GENERIC',
        status: 'DONE',
        created_at: '2026-11-26T08:39:33Z',
        content: '## main...origin/main',
      }),
      JSON.stringify({
        step_index: 5,
        type: 'PLANNER_RESPONSE',
        status: 'DONE',
        created_at: '2026-11-26T08:39:40Z',
        tool_calls: [
          {
            name: 'update_steps',
            args: {
              steps: JSON.stringify([
                { status: 'done', title: 'Sync local main and clean merged branches' },
                { status: 'failed', title: 'Update and verify local Mac Hub service' },
                { status: 'skipped', title: 'Optional cache prune' },
              ]),
            },
          },
        ],
      }),
      JSON.stringify({
        step_index: 6,
        type: 'GENERIC',
        status: 'DONE',
        created_at: '2026-11-26T08:39:41Z',
        content: 'OK',
      }),
      JSON.stringify({
        step_index: 7,
        type: 'PLANNER_RESPONSE',
        status: 'DONE',
        created_at: '2026-11-26T08:39:45Z',
        content: '检查完毕。',
      }),
    ]
    fs.writeFileSync(tp, lines.join('\n') + '\n', 'utf8')

    const parsed = parseAgyTranscript('host:agy:conv-steps', tp)
    const planEvents = parsed.events.filter(
      (e): e is Extract<AgentEvent, { type: 'plan.updated' }> => e.type === 'plan.updated',
    )
    expect(planEvents).toHaveLength(2)
    expect(planEvents[0]!.entries).toEqual([
      { content: 'Sync local main and clean merged branches', status: 'in_progress' },
      { content: 'Update and verify local Mac Hub service', status: 'pending' },
      { content: 'Optional cache prune', status: 'skipped' },
    ])
    expect(planEvents[1]!.entries).toEqual([
      { content: 'Sync local main and clean merged branches', status: 'completed' },
      { content: 'Update and verify local Mac Hub service', status: 'failed' },
      { content: 'Optional cache prune', status: 'skipped' },
    ])

    // `update_steps` must NOT pollute tool.call; `run_command` MUST still receive its real stdout!
    const toolCalls = parsed.events.filter(
      (e): e is Extract<AgentEvent, { type: 'tool.call' }> => e.type === 'tool.call',
    )
    expect(toolCalls).toHaveLength(1)
    expect(toolCalls[0]!.title).toBe('Bash')

    const toolUpdates = parsed.events.filter(
      (e): e is Extract<AgentEvent, { type: 'tool.update' }> => e.type === 'tool.update',
    )
    expect(toolUpdates).toHaveLength(1)
    expect(toolUpdates[0]!.toolCallId).toBe(toolCalls[0]!.toolCallId)
    expect(toolUpdates[0]!.output).toContain('## main...origin/main')
    expect(parsed.isRunning).toBe(false)
  })

  it('emits plan.updated from live AGY stream-json update_steps tool events', async () => {
    const dir = mkTmpDir()
    const fakeBin = path.join(dir, 'fake-agy.mjs')
    fs.writeFileSync(
      fakeBin,
      `#!/usr/bin/env node
process.stdin.setEncoding('utf8')
process.stdin.on('data', () => {
  console.log(JSON.stringify({ event: 'init', conversation_id: 'live-conv-1' }))
  console.log(JSON.stringify({
    event: 'step_update',
    step_update: {
      step_index: 3,
      step_type: 'tool',
      tool_name: 'update_steps',
      state: 'ACTIVE',
      tool_info: {
        parameters: {
          steps: [
            { title: 'Inspect files', status: 'done' },
            { title: 'Run tests', status: 'in_progress' },
          ],
        },
      },
    },
  }))
  console.log(JSON.stringify({
    event: 'step_update',
    step_update: {
      step_index: 3,
      step_type: 'tool',
      tool_name: 'update_steps',
      state: 'DONE',
      tool_info: { output: 'OK' },
    },
  }))
  console.log(JSON.stringify({ event: 'result', result: { conversation_id: 'live-conv-1', response: 'Done.' } }))
})
`,
      { mode: 0o755 },
    )

    const emitted: AgentEventBody[] = []
    const adapter = new AgyEngineAdapter({ binary: fakeBin, args: [] })
    const sess = await adapter.openSession({
      sessionId: 'sess_agy_live',
      cwd: dir,
      emit: ev => emitted.push(ev),
    })
    await sess.prompt('turn_1', 'run check')
    await sess.close()

    const planEvents = emitted.filter(
      (e): e is Extract<AgentEventBody, { type: 'plan.updated' }> => e.type === 'plan.updated',
    )
    expect(planEvents).toHaveLength(1)
    expect(planEvents[0]!.entries).toEqual([
      { content: 'Inspect files', status: 'completed' },
      { content: 'Run tests', status: 'in_progress' },
    ])
    expect(emitted.some(e => e.type === 'tool.call')).toBe(false)
  })

  it('parses Claude TodoWrite and TaskCreate/TaskUpdate into plan.updated events', () => {
    const dir = mkTmpDir()
    const fp = path.join(dir, 'claude-sess.jsonl')
    const lines = [
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-26T09:00:00Z',
        message: { role: 'user', content: 'Implement step checklist' },
      }),
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-26T09:00:02Z',
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
                  { content: 'Inspect schema', status: 'completed' },
                  { content: 'Build UI card', status: 'in_progress' },
                ],
              },
            },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-26T09:00:03Z',
        message: {
          role: 'user',
          content: [{ type: 'tool_result', tool_use_id: 'tu_todo', content: 'Todos updated' }],
        },
      }),
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-26T09:00:05Z',
        message: {
          id: 'm2',
          role: 'assistant',
          stop_reason: 'tool_use',
          content: [
            {
              type: 'tool_use',
              id: 'tu_create',
              name: 'TaskCreate',
              input: { subject: 'Run full Gradle verification' },
            },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        timestamp: '2026-11-26T09:00:06Z',
        message: {
          role: 'user',
          content: [{ type: 'tool_result', tool_use_id: 'tu_create', content: 'Task #9 created successfully' }],
        },
      }),
      JSON.stringify({
        type: 'assistant',
        timestamp: '2026-11-26T09:00:08Z',
        message: {
          id: 'm3',
          role: 'assistant',
          stop_reason: 'tool_use',
          content: [
            {
              type: 'tool_use',
              id: 'tu_update',
              name: 'TaskUpdate',
              input: { taskId: '9', status: 'completed' },
            },
          ],
        },
      }),
    ]
    fs.writeFileSync(fp, lines.join('\n') + '\n', 'utf8')

    const parsed = parseClaudeTranscript('host:claude:sess-1', fp)
    const plans = parsed.events.filter(
      (e): e is Extract<AgentEvent, { type: 'plan.updated' }> => e.type === 'plan.updated',
    )
    expect(plans).toHaveLength(3)
    expect(plans[0]!.entries).toEqual([
      { content: 'Inspect schema', status: 'completed' },
      { content: 'Build UI card', status: 'in_progress' },
    ])
    expect(plans[2]!.entries).toEqual([
      { content: 'Inspect schema', status: 'completed' },
      { content: 'Build UI card', status: 'in_progress' },
      { content: 'Run full Gradle verification', status: 'completed' },
    ])
    expect(parsed.events.some(e => e.type === 'tool.call')).toBe(false)
  })

  it('parses Codex update_plan function_call into plan.updated', () => {
    const dir = mkTmpDir()
    const fp = path.join(dir, 'rollout-11111111-2222-3333-4444-555555555555.jsonl')
    const lines = [
      JSON.stringify({
        timestamp: '2026-11-26T10:00:00Z',
        type: 'response_item',
        payload: {
          type: 'function_call',
          call_id: 'call_plan_1',
          name: 'update_plan',
          arguments: JSON.stringify({
            plan: [
              { step: 'Read code', status: 'completed' },
              { step: 'Patch parser', status: 'in_progress' },
              { step: 'Verify tests', status: 'pending' },
            ],
          }),
        },
      }),
      JSON.stringify({
        timestamp: '2026-11-26T10:00:01Z',
        type: 'response_item',
        payload: {
          type: 'function_call_output',
          call_id: 'call_plan_1',
          output: 'Plan updated',
        },
      }),
    ]
    fs.writeFileSync(fp, lines.join('\n') + '\n', 'utf8')

    const parsed = parseCodexTranscript('host:codex:1', fp)
    const plans = parsed.events.filter(
      (e): e is Extract<AgentEvent, { type: 'plan.updated' }> => e.type === 'plan.updated',
    )
    expect(plans).toHaveLength(1)
    expect(plans[0]!.entries).toEqual([
      { content: 'Read code', status: 'completed' },
      { content: 'Patch parser', status: 'in_progress' },
      { content: 'Verify tests', status: 'pending' },
    ])
    expect(parsed.events.some(e => e.type === 'tool.call')).toBe(false)
  })

  it('captures AGY live spinner above an expanded multi-line step tree', () => {
    const screenLines = [
      '○ Thought for 15.3s (Initial focus on synchronizing local environments...)',
      '● Ran (git fetch origin --tags && git checkout main) (ctrl+o to expand)',
      '',
      '⠇ 1/7 steps · Running command... 17s',
      ' └ ✓ Sync local main and clean merged branches',
      '   ● Update and verify local Mac Hub service',
      '   ○ Deploy and verify DevboxCN Hub Master service',
      '   ○ Verify iPhone connection',
      '   ○ Run unit tests',
      '   ○ Run UI tests',
      '   ○ Push feature branch',
      '',
      '────────────────────────────────────────',
      '❯ Type a message...',
      '⏵⏵ auto · gemini-4-neon-xhigh  esc to cancel',
    ]
    expect(parseLiveSpinnerLine(screenLines)).toBe('⠇ 1/7 steps · Running command... 17s')
  })

  it('classifies swarm & run_workflow tools and enriches AGY/Claude subagent step progress + depth', () => {
    expect(classifyRawToolCall('swarm', '{"action":"add","name":"worker"}').title).toBe('Swarm')
    expect(classifyRawToolCall('run_workflow', '{"Description":"Batch audit"}').title).toBe('Workflow')
    expect(describeToolActivity('update_steps')).toBe('更新执行步骤')
    expect(describeToolActivity('swarm')).toBe('调度协作集群')
    expect(describeToolActivity('run_workflow')).toBe('执行工作流')

    // AGY nested subagent with stepsDone/stepsTotal + role + depth=2
    const fakeHome = mkTmpDir()
    const ws = path.join(fakeHome, 'personal', 'demo-repo')
    fs.mkdirSync(ws, { recursive: true })
    const agyRoot = path.join(fakeHome, '.gemini', 'antigravity-cli')
    const parentLog = path.join(agyRoot, 'brain', 'conv-root', '.system_generated', 'logs', 'transcript.jsonl')
    const subLog = path.join(agyRoot, 'brain', 'conv-nested', '.system_generated', 'logs', 'transcript.jsonl')
    fs.mkdirSync(path.dirname(parentLog), { recursive: true })
    fs.mkdirSync(path.dirname(subLog), { recursive: true })
    fs.writeFileSync(
      parentLog,
      JSON.stringify({
        type: 'USER_INPUT',
        created_at: new Date().toISOString(),
        content: '<USER_REQUEST>Root task</USER_REQUEST>',
      }) + '\n',
    )
    fs.writeFileSync(
      subLog,
      [
        JSON.stringify({
          type: 'USER_INPUT',
          created_at: new Date().toISOString(),
          content: '[Role: Database Debugger]\n[Message] content=Inspect slow queries',
        }),
        JSON.stringify({
          type: 'PLANNER_RESPONSE',
          status: 'DONE',
          created_at: new Date().toISOString(),
          tool_calls: [
            {
              name: 'update_steps',
              args: {
                steps: JSON.stringify([
                  { title: 'Collect explain plan', status: 'done' },
                  { title: 'Verify index usage', status: 'done' },
                  { title: 'Draft fix', status: 'in_progress' },
                ]),
              },
            },
          ],
        }),
      ].join('\n') + '\n',
    )

    const sqliteMod = nodeRequire('node:sqlite') as {
      DatabaseSync: new (loc: string) => {
        exec: (sql: string) => void
        prepare: (sql: string) => { run: (...args: unknown[]) => void }
        close: () => void
      }
    }
    const db = new sqliteMod.DatabaseSync(path.join(agyRoot, 'conversation_summaries.db'))
    db.exec(
      'CREATE TABLE conversation_summaries (conversation_id TEXT, title TEXT, workspace_uris TEXT, parent_conversation_id TEXT, nesting_depth INTEGER)',
    )
    const ins = db.prepare(
      'INSERT INTO conversation_summaries (conversation_id, title, workspace_uris, parent_conversation_id, nesting_depth) VALUES (?, ?, ?, ?, ?)',
    )
    ins.run('conv-root', 'Root Session', JSON.stringify([pathToFileURL(ws).href]), '', 0)
    ins.run('conv-mid', 'Mid Subagent', JSON.stringify([pathToFileURL(ws).href]), 'conv-root', 1)
    ins.run('conv-nested', 'Nested Inspector', JSON.stringify([pathToFileURL(ws).href]), 'conv-mid', 2)
    db.close()

    const out = new Map<string, HostTranscriptEntry>()
    scanAgySessions(fakeHome, new Map(), out)
    const rootEntry = out.get('host:agy:conv-root')
    expect(rootEntry?.meta.subagents).toHaveLength(1)
    const nested = rootEntry?.meta.subagents?.[0]
    expect(nested?.id).toBe('host:agy:conv-nested')
    expect(nested?.role).toBe('Database Debugger')
    expect(nested?.parentId).toBe('host:agy:conv-mid')
    expect(nested?.depth).toBe(2)
    expect(nested?.stepsDone).toBe(2)
    expect(nested?.stepsTotal).toBe(3)

    // Claude subagent step progress enrichment
    const claudeProj = path.join(fakeHome, '.claude', 'projects', '-tmp-demo')
    const parentUuid = '11111111-2222-3333-4444-555555555555'
    const claudeParent = path.join(claudeProj, `${parentUuid}.jsonl`)
    const claudeSub = path.join(claudeProj, parentUuid, 'subagents', 'agent-a1.jsonl')
    fs.mkdirSync(path.dirname(claudeSub), { recursive: true })
    fs.writeFileSync(
      claudeParent,
      JSON.stringify({
        type: 'user',
        cwd: ws,
        message: { role: 'user', content: 'Claude parent task' },
      }) + '\n',
    )
    fs.writeFileSync(
      claudeSub,
      [
        JSON.stringify({
          type: 'user',
          cwd: ws,
          message: { role: 'user', content: 'Audit hooks' },
        }),
        JSON.stringify({
          type: 'assistant',
          message: {
            role: 'assistant',
            stop_reason: 'tool_use',
            content: [
              {
                type: 'tool_use',
                id: 't1',
                name: 'TodoWrite',
                input: {
                  todos: [
                    { content: 'Read hooks', status: 'completed' },
                    { content: 'Check races', status: 'in_progress' },
                  ],
                },
              },
            ],
          },
        }),
      ].join('\n') + '\n',
    )
    const claudeOut = new Map<string, HostTranscriptEntry>()
    scanClaudeSessions(fakeHome, new Map(), claudeOut)
    const cSub = claudeOut.get(`host:claude:${parentUuid}`)?.meta.subagents?.[0]
    expect(cSub?.stepsDone).toBe(1)
    expect(cSub?.stepsTotal).toBe(2)
    expect(cSub?.depth).toBe(1)
    expect(cSub?.parentId).toBe(`host:claude:${parentUuid}`)
  })

  it('extractLatestPlanFromEvents preserves active plan and clears finished plan on new turn', () => {
    const activeEvents: AgentEvent[] = [
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
      { seq: 1, sessionId: 's1', ts: 2000, type: 'message.user', messageId: 'u2', text: 'Keep going' },
    ]
    expect(extractLatestPlanFromEvents(activeEvents)).toHaveLength(2)

    const completedThenNewTurn: AgentEvent[] = [
      {
        seq: 0,
        sessionId: 's1',
        ts: 1000,
        type: 'plan.updated',
        entries: [
          { content: 'Step 1', status: 'completed' },
          { content: 'Step 2', status: 'skipped' },
        ],
      },
      { seq: 1, sessionId: 's1', ts: 2000, type: 'message.user', messageId: 'u2', text: 'Next unrelated task' },
    ]
    expect(extractLatestPlanFromEvents(completedThenNewTurn)).toBeUndefined()
  })
})
