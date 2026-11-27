import { describe, expect, it } from 'vitest'
import { Methods, SessionMeta } from '../src/index.ts'

describe('session autoTitle & regenerateTitle protocol contract', () => {
  it('accepts optional autoTitle alongside title and customTitle on SessionMeta', () => {
    const parsed = SessionMeta.parse({
      id: 'sess_auto_1',
      engineId: 'claude',
      cwd: '/workspace/lework',
      title: 'lework-sidebar-项目分组',
      autoTitle: 'lework-sidebar-项目分组',
      state: 'idle',
      createdAt: 1_700_000_000_000,
      updatedAt: 1_700_000_001_000,
      lastSeq: 4,
    })
    expect(parsed.autoTitle).toBe('lework-sidebar-项目分组')
    expect(parsed.customTitle).toBeUndefined()
  })

  it('validates session.regenerateTitle request params and result shape', () => {
    const req = Methods['session.regenerateTitle'].params.parse({ sessionId: 'sess_auto_1' })
    expect(req.sessionId).toBe('sess_auto_1')

    expect(() => Methods['session.regenerateTitle'].params.parse({ sessionId: '' })).toThrow()

    const res = Methods['session.regenerateTitle'].result.parse({
      session: {
        id: 'sess_auto_1',
        engineId: 'agy',
        cwd: '/workspace/lework',
        title: 'lework-hub-自动标题生成',
        autoTitle: 'lework-hub-自动标题生成',
        state: 'idle',
        createdAt: 1_700_000_000_000,
        updatedAt: 1_700_000_002_000,
        lastSeq: 8,
      },
    })
    expect(res.session.title).toBe('lework-hub-自动标题生成')
    expect(res.session.autoTitle).toBe('lework-hub-自动标题生成')
  })
})
