import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import { PROTOCOL_VERSION, type AgentEvent, type SessionMeta } from '@lework/protocol'
import { DiagnosticEchoAdapter } from '../src/engines/echo-adapter.ts'
import { HubServer } from '../src/server.ts'
import { SessionMetadataStore } from '../src/storage/session-metadata.ts'
import {
  extractSessionTitleSignals,
  generateHeuristicSessionTitle,
  generateLlmSessionTitle,
  inferProjectScope,
  sanitizeStructuredTitle,
} from '../src/storage/session-title.ts'

function mkSessionMeta(overrides: Partial<SessionMeta> = {}): SessionMeta {
  return {
    id: 'sess_1',
    engineId: 'claude',
    cwd: '/Users/dev/personal/lework',
    workspaces: ['/Users/dev/personal/lework'],
    title: 'Claude Code · lework',
    state: 'idle',
    createdAt: 1_000,
    updatedAt: 2_000,
    lastSeq: 1,
    ...overrides,
  }
}

describe('structured session auto-title engine', () => {
  it('sanitizes multi-level titles, preserves hyphenated scopes, and drops filler modules', () => {
    expect(
      sanitizeStructuredTitle('标题：「lework-sidebar-项目分组与折叠。」', { scope: 'lework' }),
    ).toBe('lework-sidebar-项目分组与折叠')

    expect(
      sanitizeStructuredTitle('gecko-sdk-downloader-离线包校验修复', { scope: 'gecko-sdk' }),
    ).toBe('gecko-sdk-downloader-离线包校验修复')

    expect(
      sanitizeStructuredTitle('lework-通用-发版流程优化', { scope: 'lework' }),
    ).toBe('lework-发版流程优化')

    expect(
      sanitizeStructuredTitle('lework-lework-hub-自动标题生成与上下文同步优化方案超长标题测试', {
        scope: 'lework',
        maxLength: 26,
      }),
    ).toSatisfy((v: string) => v.startsWith('lework-hub-') && v.length <= 26)
  })

  it('infers clean project scope from worktree baseRepoPath and ignores generic home dirs', () => {
    const wtMeta = mkSessionMeta({
      cwd: '/Users/dev/.lework-worktrees/lework/feat-grouping',
      worktree: {
        baseRepoPath: '/Users/dev/personal/lework',
        worktreePath: '/Users/dev/.lework-worktrees/lework/feat-grouping',
        branch: 'lework/feat-grouping',
      },
    })
    expect(inferProjectScope(wtMeta, '/Users/dev').scope).toBe('lework')

    const homeMeta = mkSessionMeta({
      cwd: '/Users/dev',
      workspaces: ['/Users/dev'],
    })
    expect(inferProjectScope(homeMeta, '/Users/dev').scope).toBeUndefined()
  })

  it('derives <Scope>-<Module>-<Feature> heuristically from user prompts and touched files', () => {
    const meta = mkSessionMeta()
    const events: AgentEvent[] = [
      {
        seq: 0,
        sessionId: meta.id,
        ts: 1_000,
        type: 'message.user',
        messageId: 'u1',
        text: '请帮我优化左侧边栏会话列表，按项目目录聚合折叠，方便找任务。',
      },
      {
        seq: 1,
        sessionId: meta.id,
        ts: 1_100,
        type: 'tool.call',
        toolCallId: 't1',
        title: 'Edit DesktopSidebar.kt',
        kind: 'edit',
        status: 'completed',
        locations: [
          '/Users/dev/personal/lework/app/shared/src/commonMain/kotlin/dev/lework/ui/desktop/DesktopSidebar.kt',
        ],
      },
      {
        seq: 2,
        sessionId: meta.id,
        ts: 1_200,
        type: 'message.user',
        messageId: 'u2',
        text: '好的，继续推进',
      },
    ]

    const res = generateHeuristicSessionTitle(meta, events, '/Users/dev')
    expect(res).not.toBeNull()
    expect(res!.title).toMatch(/^lework-sidebar-/)
    expect(res!.title).not.toContain('好的')
    expect(res!.title.length).toBeLessThanOrEqual(34)
  })

  it('handles numbered multi-feature requests concisely', () => {
    const meta = mkSessionMeta({
      cwd: '/Users/dev/work/gecko-sdk',
      workspaces: ['/Users/dev/work/gecko-sdk'],
    })
    const events: AgentEvent[] = [
      {
        seq: 0,
        sessionId: meta.id,
        ts: 1_000,
        type: 'message.user',
        messageId: 'u1',
        text: '支持两个功能：\n1. 根据会话上下文自动更新会话标题，精简明了\n2. 按目录粒度聚合左侧会话信息',
      },
      {
        seq: 1,
        sessionId: meta.id,
        ts: 1_100,
        type: 'tool.call',
        toolCallId: 't1',
        title: 'Read downloader/verify.go',
        kind: 'read',
        status: 'completed',
        locations: ['/Users/dev/work/gecko-sdk/downloader/verify.go'],
      },
    ]

    const res = generateHeuristicSessionTitle(meta, events, '/Users/dev')
    expect(res).not.toBeNull()
    expect(res!.title.startsWith('gecko-sdk-')).toBe(true)
    expect(res!.title).toContain('与')
  })

  it('calls OpenAI-chat and Anthropic-messages LLM providers and sanitizes model output', async () => {
    const meta = mkSessionMeta()
    const signals = extractSessionTitleSignals(
      meta,
      [
        {
          seq: 0,
          sessionId: meta.id,
          ts: 1_000,
          type: 'message.user',
          messageId: 'u1',
          text: '修复 AGY 会话隔离和流式事件串台问题',
        },
      ],
      '/Users/dev',
    )

    const openaiTitle = await generateLlmSessionTitle(
      {
        providerId: 'cpa_local',
        providerName: 'CPA',
        baseUrl: 'http://127.0.0.1:8317',
        apiKey: 'test-key',
        wireApis: ['openai-chat'],
        modelId: 'gpt-5-mini',
      },
      signals,
      {
        fetchImpl: (async () =>
          new Response(
            JSON.stringify({
              choices: [{ message: { content: '标题: "lework-agy-会话隔离修复。"\n额外说明' } }],
            }),
            { status: 200, headers: { 'content-type': 'application/json' } },
          )) as typeof fetch,
      },
    )
    expect(openaiTitle).toBe('lework-agy-会话隔离修复')

    const anthropicTitle = await generateLlmSessionTitle(
      {
        providerId: 'prov_claude',
        providerName: 'Anthropic',
        baseUrl: 'https://example.invalid',
        apiKey: 'sk-ant-test',
        wireApis: ['anthropic-messages'],
        modelId: 'claude-haiku-4-5',
      },
      signals,
      {
        fetchImpl: (async () =>
          new Response(
            JSON.stringify({
              content: [{ type: 'text', text: '`lework-agy-多会话流式隔离`' }],
            }),
            { status: 200, headers: { 'content-type': 'application/json' } },
          )) as typeof fetch,
      },
    )
    expect(anthropicTitle).toBe('lework-agy-多会话流式隔离')
  })
})

describe('SessionMetadataStore autoTitle precedence and persistence', () => {
  const dirs: string[] = []

  afterEach(() => {
    for (const d of dirs.splice(0)) {
      fs.rmSync(d, { recursive: true, force: true })
    }
  })

  it('enforces customTitle > autoTitle > raw title and links across hub/host aliases', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-title-meta-'))
    dirs.push(dir)
    const metaPath = path.join(dir, 'session-meta.json')
    const store = new SessionMetadataStore(metaPath)
    const base = mkSessionMeta({ id: 'sess_hub_1', title: 'Claude Code · lework' })

    store.setAutoTitle('sess_hub_1', 'lework-hub-自动标题生成', {
      source: 'heuristic',
      signalCount: 2,
    })
    const withAuto = store.applyToMeta(base)
    expect(withAuto.title).toBe('lework-hub-自动标题生成')
    expect(withAuto.autoTitle).toBe('lework-hub-自动标题生成')
    expect(withAuto.customTitle).toBeUndefined()

    // Manual rename overrides autoTitle while preserving autoTitle underneath
    store.rename('sess_hub_1', '我的手动命名')
    const withCustom = store.applyToMeta(base)
    expect(withCustom.title).toBe('我的手动命名')
    expect(withCustom.customTitle).toBe('我的手动命名')
    expect(withCustom.autoTitle).toBe('lework-hub-自动标题生成')

    // Linking hub <-> host propagates both customTitle and autoTitle
    store.linkAlias('sess_hub_1', 'host:claude:native-uuid-1')
    const reloaded = new SessionMetadataStore(metaPath)
    const hostApplied = reloaded.applyToMeta({
      ...base,
      id: 'host:claude:native-uuid-1',
    })
    expect(hostApplied.title).toBe('我的手动命名')
    expect(hostApplied.autoTitle).toBe('lework-hub-自动标题生成')

    // Clearing customTitle falls back cleanly to autoTitle
    reloaded.rename('sess_hub_1', '')
    const restored = reloaded.applyToMeta(base)
    expect(restored.title).toBe('lework-hub-自动标题生成')
    expect(restored.customTitle).toBeUndefined()
  })
})

describe('HubServer auto-title & session.regenerateTitle E2E', () => {
  const prevHome = process.env.LEWORK_HOME
  const tempDirs: string[] = []
  let hub: HubServer | null = null
  let mockLlmServer: http.Server | null = null

  afterEach(async () => {
    if (hub) {
      await hub.stop()
      hub = null
    }
    if (mockLlmServer) {
      await new Promise<void>(resolve => mockLlmServer!.close(() => resolve()))
      mockLlmServer = null
    }
    if (prevHome === undefined) delete process.env.LEWORK_HOME
    else process.env.LEWORK_HOME = prevHome
    for (const d of tempDirs.splice(0)) {
      fs.rmSync(d, { recursive: true, force: true })
    }
  })

  it('auto-titles new sessions, respects manual customTitle, and supports LLM-backed session.regenerateTitle', async () => {
    const leworkHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-autotitle-home-'))
    const repoRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-autotitle-root-'))
    const projectDir = path.join(repoRoot, 'lework')
    fs.mkdirSync(projectDir, { recursive: true })
    tempDirs.push(leworkHome, repoRoot)
    process.env.LEWORK_HOME = leworkHome

    // Spin up a local OpenAI-compatible mock LLM endpoint
    let llmCallCount = 0
    mockLlmServer = http.createServer((req, res) => {
      if (req.method === 'GET' && req.url === '/v1/models') {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ data: [{ id: 'gpt-5-mini' }] }))
        return
      }
      if (req.method === 'POST' && req.url === '/v1/chat/completions') {
        llmCallCount++
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(
          JSON.stringify({
            choices: [{ message: { content: 'lework-sidebar-项目聚合与自动标题' } }],
          }),
        )
        return
      }
      res.writeHead(404)
      res.end()
    })
    await new Promise<void>(resolve => mockLlmServer!.listen(0, '127.0.0.1', () => resolve()))
    const llmAddr = mockLlmServer.address()
    const llmPort = typeof llmAddr === 'object' && llmAddr ? llmAddr.port : 0

    hub = new HubServer({
      config: {
        hubId: 'hub_title_test',
        name: 'TitleTestHub',
        role: 'standalone',
        host: '127.0.0.1',
        port: 0,
        token: 'test-token-auto-title-1234',
        workspaceRoots: [repoRoot],
        cpaEndpoint: `http://127.0.0.1:${llmPort}`,
      },
      adapters: [new DiagnosticEchoAdapter()],
    })
    const { port } = await hub.start()

    const ws = new WebSocket(`wss://127.0.0.1:${port}/v1/ws`, { rejectUnauthorized: false })
    await new Promise<void>((resolve, reject) => {
      ws.once('open', () => resolve())
      ws.once('error', reject)
    })

    let nextId = 1
    const pending = new Map<number, { resolve: (v: unknown) => void; reject: (e: Error) => void }>()
    const updates: SessionMeta[] = []
    ws.on('message', raw => {
      const msg = JSON.parse(String(raw)) as {
        id?: number
        method?: string
        params?: { session?: SessionMeta }
        result?: unknown
        error?: { message: string }
      }
      if (msg.method === 'session.updated' && msg.params?.session) {
        updates.push(msg.params.session)
        return
      }
      if (typeof msg.id === 'number' && pending.has(msg.id)) {
        const p = pending.get(msg.id)!
        pending.delete(msg.id)
        if (msg.error) p.reject(new Error(msg.error.message))
        else p.resolve(msg.result)
      }
    })
    const rpc = <T>(method: string, params?: unknown): Promise<T> =>
      new Promise((resolve, reject) => {
        const id = nextId++
        pending.set(id, { resolve: resolve as (v: unknown) => void, reject })
        ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }))
      })

    try {
      await rpc('hub.hello', {
        protocolVersion: PROTOCOL_VERSION,
        token: 'test-token-auto-title-1234',
        client: { name: 'test-desktop', version: '0.8.22', platform: 'macos' },
      })

      // Probe providers so ModelRegistry marks our local mock OpenAI provider healthy
      await rpc('provider.probe', { providerId: 'cpa_local' })

      const created = await rpc<{ session: SessionMeta }>('session.create', {
        engineId: 'mock',
        cwd: projectDir,
      })
      const sid = created.session.id

      await rpc('session.prompt', {
        sessionId: sid,
        text: '请帮我优化左侧边栏，按项目目录聚合折叠并自动更新会话标题',
      })

      // Wait briefly for turn completion and async LLM title update
      const deadline = Date.now() + 4_000
      while (Date.now() < deadline) {
        if (updates.some(u => u.id === sid && u.title === 'lework-sidebar-项目聚合与自动标题')) {
          break
        }
        await new Promise(r => setTimeout(r, 40))
      }

      const latestAuto = [...updates].reverse().find(u => u.id === sid)
      expect(latestAuto?.title).toBe('lework-sidebar-项目聚合与自动标题')
      expect(latestAuto?.autoTitle).toBe('lework-sidebar-项目聚合与自动标题')
      expect(llmCallCount).toBeGreaterThanOrEqual(1)

      // Manual rename sets customTitle and locks against auto-override on subsequent turns
      const renamed = await rpc<{ session: SessionMeta }>('session.rename', {
        sessionId: sid,
        title: '人工锁定标题',
      })
      expect(renamed.session.title).toBe('人工锁定标题')
      expect(renamed.session.customTitle).toBe('人工锁定标题')

      await rpc('session.prompt', {
        sessionId: sid,
        text: '继续完善移动端抽屉交互与单元测试',
      })
      await new Promise(r => setTimeout(r, 200))

      const listed = await rpc<{ sessions: SessionMeta[] }>('session.list')
      const afterSecondTurn = listed.sessions.find(s => s.id === sid)
      expect(afterSecondTurn?.title).toBe('人工锁定标题')
      expect(afterSecondTurn?.customTitle).toBe('人工锁定标题')

      // Explicit session.regenerateTitle clears customTitle and refreshes structured autoTitle
      const regenerated = await rpc<{ session: SessionMeta }>('session.regenerateTitle', {
        sessionId: sid,
      })
      expect(regenerated.session.customTitle).toBeUndefined()
      expect(regenerated.session.autoTitle).toBe('lework-sidebar-项目聚合与自动标题')
      expect(regenerated.session.title).toBe('lework-sidebar-项目聚合与自动标题')
    } finally {
      ws.close()
    }
  })
})
