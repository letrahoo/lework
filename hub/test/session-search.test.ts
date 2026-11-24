import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { WebSocket } from 'ws'
import { PROTOCOL_VERSION, type AgentEvent, type SessionMeta } from '@lework/protocol'
import { DiagnosticEchoAdapter } from '../src/engines/echo-adapter.ts'
import { loadOrCreateHubTls } from '../src/security/tls.ts'
import { HubServer } from '../src/server.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'
import {
  ConversationSearchIndex,
  extractIndexableMessages,
  type SearchableSessionSource,
} from '../src/storage/search-index.ts'

describe('ConversationSearchIndex (SQLite FTS5 trigram)', () => {
  const indices: ConversationSearchIndex[] = []
  const tempDirs: string[] = []

  afterEach(() => {
    while (indices.length > 0) {
      indices.pop()?.close()
    }
    while (tempDirs.length > 0) {
      const dir = tempDirs.pop()
      if (dir) fs.rmSync(dir, { recursive: true, force: true })
    }
  })

  it('indexes user & assistant text, ignores tool noise, and handles Chinese/English multi-term & short queries', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-search-idx-'))
    tempDirs.push(tmpDir)
    const index = new ConversationSearchIndex(path.join(tmpDir, 'search-index.db'))
    indices.push(index)

    const sessA: SessionMeta = {
      id: 'host:agy:conv_harmony',
      engineId: 'agy',
      cwd: '/workspace/demo-app',
      title: 'App 迭代与规划',
      state: 'idle',
      createdAt: 1_000,
      updatedAt: 5_000,
      lastSeq: 4,
    }
    const eventsA: AgentEvent[] = [
      {
        seq: 0,
        sessionId: sessA.id,
        ts: 1_000,
        type: 'message.user',
        messageId: 'u_0',
        text: '复用企业证书的 Harmony 线上流程是什么预审批链路？',
      },
      {
        seq: 1,
        sessionId: sessA.id,
        ts: 2_000,
        type: 'tool.call',
        toolCallId: 't_1',
        title: 'noise tool',
        kind: 'search',
        status: 'completed',
        input: 'MCP_TOOL_SCHEMA_ONLY_KEYWORD_XYZ',
        output: 'MCP_TOOL_OUTPUT_NOISE_999',
      },
      {
        seq: 2,
        sessionId: sessA.id,
        ts: 3_000,
        type: 'message.assistant.delta',
        messageId: 'a_1',
        text: '鸿蒙企业签名走 AGC 建应用 + ',
      },
      {
        seq: 3,
        sessionId: sessA.id,
        ts: 3_100,
        type: 'message.assistant.delta',
        messageId: 'a_1',
        text: '云端签名平台复制密钥审批。',
      },
    ]

    const sessB: SessionMeta = {
      id: 'host:claude:conv_pr',
      engineId: 'claude',
      cwd: '/workspace/lework',
      title: 'Fix WebSocket reconnect',
      state: 'idle',
      createdAt: 2_000,
      updatedAt: 6_000,
      lastSeq: 1,
    }
    const eventsB: AgentEvent[] = [
      {
        seq: 0,
        sessionId: sessB.id,
        ts: 2_000,
        type: 'message.user',
        messageId: 'u_b0',
        text: '检查 PR 合并冲突与心跳超时',
      },
    ]

    const sources: SearchableSessionSource[] = [
      { meta: sessA, filePath: '/tmp/a.jsonl', fileMtimeMs: 100, fileSize: 500, loadEvents: () => eventsA },
      { meta: sessB, filePath: '/tmp/b.jsonl', fileMtimeMs: 200, fileSize: 300, loadEvents: () => eventsB },
    ]

    // 1. Multi-term mixed English + Chinese query matches hidden message even though title doesn't contain Harmony
    const r1 = index.search({ query: 'harmony 预审批' }, sources)
    expect(r1.hits).toHaveLength(1)
    expect(r1.hits[0]!.sessionId).toBe(sessA.id)
    expect(r1.hits[0]!.matches[0]!.seq).toBe(0)
    expect(r1.hits[0]!.matches[0]!.role).toBe('user')
    expect(r1.hits[0]!.matches[0]!.snippet).toContain('Harmony')

    // 2. Short 2-char Chinese query ("鸿蒙") and 2-char English query ("PR") both work cleanly
    const rShortZh = index.search({ query: '鸿蒙' }, sources)
    expect(rShortZh.hits).toHaveLength(1)
    expect(rShortZh.hits[0]!.matches[0]!.seq).toBe(2)
    expect(rShortZh.hits[0]!.matches[0]!.role).toBe('assistant')

    const rShortEn = index.search({ query: 'PR' }, sources)
    expect(rShortEn.hits).toHaveLength(1)
    expect(rShortEn.hits[0]!.sessionId).toBe(sessB.id)

    // 3. Tool noise is NOT indexed
    const rNoise = index.search({ query: 'MCP_TOOL_SCHEMA_ONLY_KEYWORD_XYZ' }, sources)
    expect(rNoise.hits).toHaveLength(0)

    // 4. Role filter strictly separates user prompts from assistant replies
    const rUserOnly = index.search({ query: '鸿蒙', role: 'user' }, sources)
    expect(rUserOnly.hits).toHaveLength(0)
    const rAssistantOnly = index.search({ query: '鸿蒙', role: 'assistant' }, sources)
    expect(rAssistantOnly.hits).toHaveLength(1)

    // 5. Incremental re-index detects changed file mtime/size
    eventsB.push({
      seq: 1,
      sessionId: sessB.id,
      ts: 7_000,
      type: 'message.assistant.delta',
      messageId: 'a_b1',
      text: '已补充鸿蒙端重连退避逻辑',
    })
    sources[1]!.fileMtimeMs = 250
    sources[1]!.fileSize = 420
    const rUpdated = index.search({ query: '鸿蒙' }, sources)
    expect(rUpdated.hits.map(h => h.sessionId)).toEqual([sessB.id, sessA.id])

    // 6. Helper cleanly merges streaming deltas under the first seq
    const extracted = extractIndexableMessages(eventsA)
    expect(extracted).toHaveLength(2)
    expect(extracted[1]!.seq).toBe(2)
    expect(extracted[1]!.text).toContain('云端签名平台复制密钥审批')
  })
})

describe('HubServer session.search and stream.subscribe aroundSeq E2E', () => {
  const servers: HubServer[] = []
  const tempDirs: string[] = []
  const prevLeworkHome = process.env.LEWORK_HOME

  afterEach(async () => {
    while (servers.length > 0) {
      const srv = servers.pop()
      if (srv) await srv.stop()
    }
    if (prevLeworkHome === undefined) delete process.env.LEWORK_HOME
    else process.env.LEWORK_HOME = prevLeworkHome
    while (tempDirs.length > 0) {
      const dir = tempDirs.pop()
      if (dir) fs.rmSync(dir, { recursive: true, force: true })
    }
  })

  it('searches across sessions and slices aroundSeq window with hasMoreAfter flag over WSS', async () => {
    const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-search-e2e-'))
    tempDirs.push(tmpHome)
    process.env.LEWORK_HOME = tmpHome
    const fakeUserHome = path.join(tmpHome, 'fake-home')
    const claudeProj = path.join(fakeUserHome, '.claude', 'projects', '-workspace-demo')
    fs.mkdirSync(claudeProj, { recursive: true })

    // Create a large Claude host transcript with 450 user/assistant pairs so target turn sits deep in history
    const lines: string[] = []
    for (let i = 0; i < 450; i++) {
      const ts = new Date(1_700_000_000_000 + i * 1_000).toISOString()
      const uText = i === 12 ? '请问 Harmony 线上预审批链路需要经过哪些节点？' : `常规测试消息编号 ${i}`
      const aText = i === 12 ? '需要经过应用创建与密钥复制两个审批节点。' : `常规回复编号 ${i}`
      lines.push(
        JSON.stringify({
          type: 'user',
          uuid: `u_${i}`,
          cwd: '/workspace/demo',
          timestamp: ts,
          message: { role: 'user', content: uText },
        }),
      )
      lines.push(
        JSON.stringify({
          type: 'assistant',
          uuid: `a_${i}`,
          cwd: '/workspace/demo',
          timestamp: ts,
          message: { role: 'assistant', content: [{ type: 'text', text: aText }] },
        }),
      )
    }
    fs.writeFileSync(path.join(claudeProj, 'sess-long-history.jsonl'), lines.join('\n') + '\n', 'utf8')

    const token = 'lework_oa.search_e2e_token_123456'
    const server = new HubServer({
      config: {
        hubId: 'hub_search_e2e',
        name: 'SearchTestHub',
        role: 'slave',
        host: '127.0.0.1',
        port: 0,
        token,
        workspaceRoots: [tmpHome],
        cpaEndpoint: 'http://127.0.0.1:8317',
      },
      adapters: [new DiagnosticEchoAdapter()],
      hostInspector: new HostTaskInspector({ homeDir: fakeUserHome }),
      tls: loadOrCreateHubTls({ dir: path.join(tmpHome, 'tls') }),
    })
    servers.push(server)
    const { port } = await server.start()

    const ws = new WebSocket(`wss://127.0.0.1:${port}/v1/ws`, { rejectUnauthorized: false })
    await new Promise<void>((resolve, reject) => {
      ws.once('open', () => resolve())
      ws.once('error', reject)
    })

    let rpcId = 1
    const rpcCall = <T = any>(method: string, params?: unknown): Promise<T> =>
      new Promise<T>((resolve, reject) => {
        const id = rpcId++
        const onMsg = (raw: WebSocket.RawData) => {
          const msg = JSON.parse(String(raw))
          if (msg.id !== id) return
          ws.off('message', onMsg)
          if (msg.error) reject(msg.error)
          else resolve(msg.result as T)
        }
        ws.on('message', onMsg)
        ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }))
      })

    const hello = await rpcCall('hub.hello', {
      protocolVersion: PROTOCOL_VERSION,
      token,
      client: { name: 'lework-desktop', version: '0.7.12', platform: 'macOS Desktop' },
    })
    expect(hello.capabilities).toContain('feature.search.v1')

    // 1. Global search finds deep turn #12
    const res = await rpcCall('session.search', {
      query: 'Harmony 预审批链路',
      role: 'user',
    })
    expect(res.hits).toHaveLength(1)
    const hit = res.hits[0]
    expect(hit.sessionId).toBe('host:claude:sess-long-history')
    const targetSeq = hit.matches[0].seq
    expect(targetSeq).toBeGreaterThan(0)
    expect(targetSeq).toBeLessThan(100)

    // 2. stream.subscribe with aroundSeq loads a window containing targetSeq and marks hasMoreAfter=true
    const snap = await rpcCall('stream.subscribe', {
      sessionId: hit.sessionId,
      afterSeq: -1,
      aroundSeq: targetSeq,
      limit: 160,
    })
    expect(snap.events.some((e: AgentEvent) => e.seq === targetSeq)).toBe(true)
    expect(snap.hasMoreAfter).toBe(true)

    // 3. In-session search (`sessionId` scoped) returns chronological matches across unloaded history
    const inSession = await rpcCall('session.search', {
      query: '审批',
      sessionId: hit.sessionId,
      matchesPerSession: 20,
    })
    expect(inSession.hits).toHaveLength(1)
    expect(inSession.hits[0].matches.length).toBe(2)
    expect(inSession.hits[0].matches[0].seq).toBeLessThan(inSession.hits[0].matches[1].seq)

    ws.close()
  })
})
