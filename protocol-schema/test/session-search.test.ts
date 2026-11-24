import { describe, expect, it } from 'vitest'
import { Methods, SearchRoleFilter, SessionSearchResult } from '../src/index.ts'

describe('session.search and stream.subscribe aroundSeq schemas', () => {
  it('validates session.search params and result schemas', () => {
    const parsedParams = Methods['session.search'].params.parse({
      query: 'harmony 审批链路',
      engineId: 'agy',
      role: 'user',
      limit: 20,
      matchesPerSession: 3,
      includeArchived: false,
    })
    expect(parsedParams.role).toBe('user')
    expect(SearchRoleFilter.options).toEqual(['all', 'user', 'assistant'])

    const parsedResult = SessionSearchResult.parse({
      query: 'harmony 审批链路',
      hits: [
        {
          sessionId: 'host:agy:conv_1',
          engineId: 'agy',
          title: 'App 迭代规划',
          cwd: '/workspace/demo',
          updatedAt: 1700000000000,
          archived: false,
          matchCount: 2,
          matches: [
            {
              seq: 42,
              role: 'user',
              ts: 1700000000000,
              snippet: '复用企业证书的 Harmony 审批链路是什么',
              matchStart: 8,
              matchEnd: 15,
            },
          ],
        },
      ],
      indexedSessions: 12,
      totalSessions: 12,
      indexing: false,
    })
    expect(parsedResult.hits[0]?.matches[0]?.seq).toBe(42)
  })

  it('validates stream.subscribe aroundSeq param and hasMoreAfter result flag', () => {
    const subParams = Methods['stream.subscribe'].params.parse({
      sessionId: 'host:agy:conv_1',
      afterSeq: -1,
      aroundSeq: 420,
      limit: 260,
    })
    expect(subParams.aroundSeq).toBe(420)

    const subResult = Methods['stream.subscribe'].result.parse({
      session: {
        id: 'host:agy:conv_1',
        engineId: 'agy',
        cwd: '/workspace/demo',
        title: 'Long Session',
        state: 'idle',
        createdAt: 1700000000000,
        updatedAt: 1700000005000,
        lastSeq: 1200,
      },
      events: [],
      truncated: true,
      earliestSeq: 360,
      hasMoreBefore: true,
      hasMoreAfter: true,
    })
    expect(subResult.hasMoreAfter).toBe(true)
  })
})
