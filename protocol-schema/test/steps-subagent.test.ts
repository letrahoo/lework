import { describe, expect, it } from 'vitest'
import { AgentEvent, Methods, PlanEntry, SubagentSummary } from '../src/index.ts'

describe('protocol-schema live steps & subagent extensions', () => {
  it('validates all PlanEntry statuses including skipped and failed', () => {
    const entries = [
      PlanEntry.parse({ content: 'Sync main', status: 'completed' }),
      PlanEntry.parse({ content: 'Build Hub', status: 'in_progress' }),
      PlanEntry.parse({ content: 'Deploy Devbox', status: 'pending' }),
      PlanEntry.parse({ content: 'Optional cleanup', status: 'skipped' }),
      PlanEntry.parse({ content: 'Legacy check', status: 'failed' }),
    ]
    const ev = AgentEvent.parse({
      seq: 12,
      sessionId: 'host:agy:conv-1',
      ts: 1700000001200,
      type: 'plan.updated',
      entries,
    })
    expect(ev.type).toBe('plan.updated')
    if (ev.type === 'plan.updated') {
      expect(ev.entries.map(e => e.status)).toEqual([
        'completed',
        'in_progress',
        'pending',
        'skipped',
        'failed',
      ])
    }
  })

  it('validates SubagentSummary hierarchy & step progress and stream.subscribe latestPlan', () => {
    const sub = SubagentSummary.parse({
      id: 'host:agy:sub-2',
      title: 'Codebase Researcher',
      role: 'research',
      state: 'running',
      statusText: '查阅 ConversationTimeline.kt',
      createdAt: 1700000000000,
      updatedAt: 1700000015000,
      workspace: '/tmp/repo',
      parentId: 'host:agy:sub-1',
      depth: 2,
      stepsDone: 2,
      stepsTotal: 4,
    })
    expect(sub.depth).toBe(2)
    expect(sub.stepsDone).toBe(2)
    expect(sub.stepsTotal).toBe(4)

    const subscribed = Methods['stream.subscribe'].result.parse({
      session: {
        id: 'host:agy:conv-1',
        engineId: 'agy',
        cwd: '/tmp/repo',
        title: 'Main Session',
        state: 'running',
        createdAt: 1700000000000,
        updatedAt: 1700000020000,
        lastSeq: 50,
        subagents: [sub],
      },
      events: [],
      truncated: true,
      earliestSeq: 30,
      hasMoreBefore: true,
      hasMoreAfter: false,
      latestPlan: [{ content: 'Verify build', status: 'in_progress' }],
    })
    expect(subscribed.latestPlan).toEqual([{ content: 'Verify build', status: 'in_progress' }])
  })
})
