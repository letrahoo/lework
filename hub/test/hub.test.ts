import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it } from 'vitest'
import { WebSocket } from 'ws'
import { ErrorCode, PROTOCOL_VERSION, type AgentEvent } from '@lework/protocol'
import { AgyEngineAdapter } from '../src/engines/agy-adapter.ts'
import { ClaudeCodeAdapter } from '../src/engines/claude-adapter.ts'
import { DshEngineAdapter } from '../src/engines/dsh-adapter.ts'
import { DiagnosticEchoAdapter } from '../src/engines/echo-adapter.ts'
import { PiEngineAdapter } from '../src/engines/pi-adapter.ts'
import { generateServiceDescriptor } from '../src/platform/index.ts'
import { HubServer } from '../src/server.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '../..')

// Isolated Hub data dir (TLS identity, issued tokens) for the whole suite
process.env.LEWORK_HOME = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-hub-test-'))

class TestRpcClient {
  private ws!: WebSocket
  private nextId = 1
  private readonly pending = new Map<number, { resolve: (v: any) => void; reject: (e: any) => void }>()
  readonly events: AgentEvent[] = []
  private readonly eventWaiters: Array<(ev: AgentEvent) => boolean> = []

  async connect(url: string): Promise<void> {
    // Self-signed Hub certificate: tests verify the pinned fingerprint separately
    this.ws = new WebSocket(url, { rejectUnauthorized: false })
    await new Promise<void>((resolve, reject) => {
      this.ws.once('open', () => resolve())
      this.ws.once('error', reject)
    })
    this.ws.on('message', raw => {
      const msg = JSON.parse(String(raw))
      if (typeof msg.id === 'number') {
        const p = this.pending.get(msg.id)
        if (!p) return
        this.pending.delete(msg.id)
        if (msg.error) p.reject(msg.error)
        else p.resolve(msg.result)
        return
      }
      if (msg.method === 'stream.event' && msg.params?.event) {
        const ev = msg.params.event as AgentEvent
        this.events.push(ev)
        for (let i = this.eventWaiters.length - 1; i >= 0; i--) {
          if (this.eventWaiters[i]!(ev)) {
            this.eventWaiters.splice(i, 1)
          }
        }
      }
    })
  }

  call<T = any>(method: string, params?: unknown): Promise<T> {
    const id = this.nextId++
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }))
    })
  }

  waitForEvent<T extends AgentEvent['type']>(
    type: T,
    predicate?: (ev: Extract<AgentEvent, { type: T }>) => boolean,
  ): Promise<Extract<AgentEvent, { type: T }>> {
    const existing = this.events.find(
      (e): e is Extract<AgentEvent, { type: T }> =>
        e.type === type && (!predicate || predicate(e as Extract<AgentEvent, { type: T }>)),
    )
    if (existing) return Promise.resolve(existing)

    return new Promise(resolve => {
      this.eventWaiters.push(ev => {
        if (ev.type === type && (!predicate || predicate(ev as Extract<AgentEvent, { type: T }>))) {
          resolve(ev as Extract<AgentEvent, { type: T }>)
          return true
        }
        return false
      })
    })
  }

  close(): void {
    this.ws?.close()
  }
}

describe('HubServer + ClaudeCodeAdapter M0 E2E', () => {
  let server: HubServer | null = null

  afterEach(async () => {
    if (server) {
      await server.stop()
      server = null
    }
  })

  it('enforces auth, method allowlist, workspace root sandbox, Claude ACP prompt, approval & seq catch-up', async () => {
    const mockAcpScript = path.join(__dirname, 'mock-acp-agent.mjs')
    const claudeAdapter = new ClaudeCodeAdapter({
      acpBinary: process.execPath,
      acpArgs: [mockAcpScript],
    })

    server = new HubServer({
      config: {
        hubId: 'hub_test_1',
        name: 'TestMac',
        role: 'slave',
        host: '127.0.0.1',
        port: 0,
        token: 'secret-test-token-12345',
        workspaceRoots: [repoRoot],
        cpaEndpoint: 'http://127.0.0.1:8317',
      },
      adapters: [
        claudeAdapter,
        new PiEngineAdapter(),
        new AgyEngineAdapter(),
        new DshEngineAdapter(),
        new DiagnosticEchoAdapter(),
      ],
    })

    const { port } = await server.start()
    const wsUrl = `wss://127.0.0.1:${port}/v1/ws`
    const client = new TestRpcClient()
    await client.connect(wsUrl)

    // 1. Calls before hub.hello are rejected with Unauthorized
    await expect(client.call('engine.list')).rejects.toMatchObject({
      code: ErrorCode.Unauthorized,
    })

    // 2. Authenticate via hub.hello
    const hello = await client.call('hub.hello', {
      protocolVersion: PROTOCOL_VERSION,
      token: 'secret-test-token-12345',
      client: { name: 'test-ios', version: '0.1.0', platform: 'ios' },
    })
    expect(hello.hub.id).toBe('hub_test_1')

    // 3. Unknown method is rejected by compile-time allowlist
    await expect(client.call('system.execArbitrary')).rejects.toMatchObject({
      code: ErrorCode.MethodNotFound,
    })

    // 4. Out-of-bounds workspace path is rejected
    await expect(
      client.call('session.create', {
        engineId: 'claude',
        cwd: '/etc',
      }),
    ).rejects.toMatchObject({
      code: ErrorCode.PathNotAllowed,
    })

    // 5. Create a real Claude ACP session in repoRoot
    const { session } = await client.call('session.create', {
      engineId: 'claude',
      cwd: repoRoot,
    })
    expect(session.engineId).toBe('claude')
    expect(session.models?.length).toBe(2)

    // 6. Send prompt -> receive permission.requested -> approve -> receive streaming reply & turn.done
    await client.call('session.prompt', {
      sessionId: session.id,
      text: 'Please write notes.txt',
    })

    const permEvent = await client.waitForEvent('permission.requested')
    expect(permEvent.title).toContain('Write notes.txt')
    expect(permEvent.options.some(o => o.id === 'allow_once_id')).toBe(true)

    await client.call('approval.respond', {
      sessionId: session.id,
      requestId: permEvent.requestId,
      optionId: 'allow_once_id',
    })

    const doneEvent = await client.waitForEvent('turn.done')
    expect(doneEvent.stopReason).toBe('end_turn')

    const assistantDeltas = client.events.filter(e => e.type === 'message.assistant.delta')
    expect(assistantDeltas.map(d => (d as any).text).join('')).toContain('Claude ACP finished (allow_once_id)')

    // 7. Reconnect with a second client and verify afterSeq catch-up
    const client2 = new TestRpcClient()
    await client2.connect(wsUrl)
    await client2.call('hub.hello', {
      protocolVersion: PROTOCOL_VERSION,
      token: 'secret-test-token-12345',
      client: { name: 'test-ios-reconnect', version: '0.1.0', platform: 'ios' },
    })

    const catchUp = await client2.call('stream.subscribe', {
      sessionId: session.id,
      afterSeq: 2,
    })
    expect(catchUp.truncated).toBe(false)
    expect(catchUp.events.every((e: AgentEvent) => e.seq > 2)).toBe(true)
    expect(catchUp.events.some((e: AgentEvent) => e.type === 'turn.done')).toBe(true)

    // 8. Compliance guardrail: AGY rejects API proxy endpoint provider
    await expect(
      client.call('session.create', {
        engineId: 'agy',
        cwd: repoRoot,
        providerId: 'cpa_local',
      }),
    ).rejects.toMatchObject({
      code: ErrorCode.InvalidParams,
    })

    // 9. Workspace read-only file browser & git checkpointing
    const dirList = await client.call('fs.list', { path: repoRoot })
    expect(dirList.entries.some((e: any) => e.name === 'package.json')).toBe(true)

    const fileRead = await client.call('fs.read', {
      path: path.join(repoRoot, 'package.json'),
    })
    expect(fileRead.content).toContain('"name": "lework"')

    const { checkpoint } = await client.call('checkpoint.create', {
      sessionId: session.id,
      label: 'Before refactor',
    })
    expect(checkpoint.label).toBe('Before refactor')
    const { checkpoints } = await client.call('checkpoint.list', {
      sessionId: session.id,
    })
    expect(checkpoints.length).toBe(2)
    expect(checkpoints[0].label).toBe('Before refactor')
    expect(checkpoints[1].label).toContain('对话前自动快照')

    const gitStatus = await client.call('git.status', { path: repoRoot })
    expect(gitStatus.isGitRepo).toBe(true)

    // 10. Pi Engine embedded turn + approval flow
    const { session: piSession } = await client.call('session.create', {
      engineId: 'pi',
      cwd: repoRoot,
      mode: 'plan',
    })
    await client.call('session.prompt', {
      sessionId: piSession.id,
      text: '生成计划并扫描工作区',
    })
    const piDone = await client.waitForEvent(
      'turn.done',
      ev => ev.sessionId === piSession.id,
    )
    expect(piDone.stopReason).toBe('end_turn')
    expect(
      client.events.some(e => e.sessionId === piSession.id && e.type === 'plan.updated'),
    ).toBe(true)

    client.close()
    client2.close()
  })

  it('generates service descriptor for daemon installation', () => {
    const desc = generateServiceDescriptor({ role: 'slave', repoRoot })
    expect(desc.content.length).toBeGreaterThan(50)
    expect(desc.pathHint).toContain('hub-slave')
  })

  it('[T3.1 - T3.4] replays historical host:* sessions with zero process spawn, aligns turn-boundary pagination, and lazily promotes via session/resume on prompt', async () => {
    const fakeHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-e2e-home-'))
    try {
      const nativeId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
      const projDir = path.join(fakeHome, '.claude', 'projects', '-repo')
      fs.mkdirSync(projDir, { recursive: true })

      const historyRows: string[] = []
      for (let t = 1; t <= 6; t++) {
        historyRows.push(
          JSON.stringify({
            type: 'user',
            cwd: repoRoot,
            timestamp: new Date(Date.now() - (20 - t) * 60_000).toISOString(),
            message: { role: 'user', content: `历史问题 #${t}` },
          }),
        )
        historyRows.push(
          JSON.stringify({
            type: 'assistant',
            cwd: repoRoot,
            timestamp: new Date(Date.now() - (20 - t) * 60_000 + 1_000).toISOString(),
            message: {
              id: `hist_a_${t}`,
              role: 'assistant',
              stop_reason: 'end_turn',
              content: [{ type: 'text', text: `历史回答 #${t}` }],
            },
          }),
        )
      }
      historyRows.push(JSON.stringify({ type: 'system', subtype: 'turn_duration' }))
      fs.writeFileSync(path.join(projDir, `${nativeId}.jsonl`), historyRows.join('\n') + '\n')

      let acpSpawnCount = 0
      const mockAcpScript = path.join(__dirname, 'mock-acp-agent.mjs')
      const baseClaude = new ClaudeCodeAdapter({
        acpBinary: process.execPath,
        acpArgs: [mockAcpScript],
      })
      const countingClaude: typeof baseClaude = Object.assign(
        Object.create(Object.getPrototypeOf(baseClaude)),
        baseClaude,
        {
          openSession: async (init: any) => {
            acpSpawnCount++
            expect(init.resumeNativeId).toBe(nativeId)
            return baseClaude.openSession(init)
          },
        },
      )

      server = new HubServer({
        config: {
          hubId: 'hub_test_lazy',
          name: 'TestLazyMac',
          role: 'slave',
          host: '127.0.0.1',
          port: 0,
          token: 'secret-lazy-token',
          workspaceRoots: [repoRoot],
          cpaEndpoint: 'http://127.0.0.1:8317',
        },
        adapters: [countingClaude],
        hostInspector: new HostTaskInspector({ homeDir: fakeHome }),
      })

      const { port } = await server.start()
      const client = new TestRpcClient()
      await client.connect(`wss://127.0.0.1:${port}/v1/ws`)
      await client.call('hub.hello', {
        protocolVersion: PROTOCOL_VERSION,
        token: 'secret-lazy-token',
        client: { name: 'lework-e2e', platform: 'ios', version: '0.3.8' },
      })

      const sessionId = `host:claude:${nativeId}`

      // [T3.1 & T3.2] Subscribe with small limit=4 -> 0 child processes spawned, snapped cleanly to message.user!
      const snap = await client.call('stream.subscribe', {
        sessionId,
        afterSeq: -1,
        limit: 4,
      })
      expect(acpSpawnCount).toBe(0)
      expect(snap.session.state).toBe('idle')
      expect(snap.events[0]?.type).toBe('message.user')
      expect(snap.hasMoreBefore).toBe(true)

      // Reverse pagination (`beforeSeq`) loads earlier turns cleanly aligned to message.user
      const older = await client.call('stream.subscribe', {
        sessionId,
        afterSeq: -1,
        beforeSeq: snap.earliestSeq,
        limit: 4,
      })
      expect(acpSpawnCount).toBe(0)
      expect(older.events[0]?.type).toBe('message.user')
      expect(older.events.every((e: AgentEvent) => e.seq < snap.earliestSeq)).toBe(true)

      const lastHistoricalSeq = snap.session.lastSeq

      // [T3.3 & T3.4] Prompting the idle historical session lazily spawns headless ACP via `session/resume`
      await client.call('session.prompt', {
        sessionId,
        text: '基于历史上下文继续执行',
      })
      expect(acpSpawnCount).toBe(1)

      const permEv = await client.waitForEvent(
        'permission.requested',
        ev => ev.sessionId === sessionId,
      )
      await client.call('approval.respond', {
        sessionId,
        requestId: permEv.requestId,
        optionId: 'allow_once_id',
      })
      const doneEv = await client.waitForEvent('turn.done', ev => ev.sessionId === sessionId)
      expect(doneEv.seq).toBeGreaterThan(lastHistoricalSeq)

      client.close()
    } finally {
      fs.rmSync(fakeHome, { recursive: true, force: true })
    }
  })
})
