import { describe, expect, it } from 'vitest'
import { inferAgySubagentActivity } from '../src/storage/host-history/agy-history.ts'
import { inferClaudeSubagentActivity } from '../src/storage/host-history/claude-history.ts'
import { sortAndCapSubagents } from '../src/storage/host-history/common.ts'

const lines = (...events: unknown[]) => events.map(event => JSON.stringify(event))

describe('subagent completion evidence', () => {
  it('does not archive a quiet tool call or partially written assistant reply', () => {
    const old = Date.now() - 120_000
    const tool = { type: 'assistant', message: { stop_reason: 'tool_use' } }
    expect(inferClaudeSubagentActivity(lines({ type: 'error' }, tool), old).state).toBe('idle')
    expect(inferClaudeSubagentActivity(lines({ type: 'assistant', message: { stop_reason: null } }), old).state).toBe('idle')
    expect(inferAgySubagentActivity(lines({ type: 'PLANNER_RESPONSE', status: 'DONE', tool_calls: [{ name: 'read' }] }), old).state).toBe('idle')
    expect(inferAgySubagentActivity([], old).state).toBe('idle')
  })

  it('uses the latest turn signal when a child resumes after completion', () => {
    const end = { type: 'assistant', message: { stop_reason: 'end_turn' } }
    expect(inferClaudeSubagentActivity(lines(end), 0).state).toBe('closed')
    expect(inferClaudeSubagentActivity(lines(end, { type: 'api-request' }), Date.now()).state).toBe('running')
    expect(inferAgySubagentActivity(lines({ type: 'PLANNER_RESPONSE', status: 'CANCELED' }), 0)).toEqual({ state: 'closed', statusText: '已取消' })
    expect(inferAgySubagentActivity(lines({ type: 'PLANNER_RESPONSE', status: 'FAILED' }), 0).state).toBe('error')
  })

  it('does not let recent history displace an unresolved child from the summary', () => {
    const base = { title: 'child', updatedAt: 1 }
    const selected = sortAndCapSubagents([
      { ...base, id: 'quiet', state: 'idle' },
      { ...base, id: 'done', state: 'closed', updatedAt: 100 },
      { ...base, id: 'approval', state: 'waiting_permission' },
    ], 2)
    expect(selected.map(s => s.id)).toEqual(['approval', 'quiet'])
  })
})
