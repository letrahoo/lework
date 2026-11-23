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
import { setCachedAgyCatalog } from '../src/storage/host-history/engine-settings.ts'
import { TerminalBridge } from '../src/storage/terminal-bridge.ts'

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

      // [T3.5] Non-destructive session rename & archive: custom title + archive state persist without deleting files
      const renamed = await client.call('session.rename', {
        sessionId,
        title: '核心支付重构任务',
      })
      expect(renamed.session.title).toBe('核心支付重构任务')
      expect(renamed.session.customTitle).toBe('核心支付重构任务')

      const archived = await client.call('session.archive', {
        sessionId,
        archived: true,
      })
      expect(archived.session.archived).toBe(true)
      expect(typeof archived.session.archivedAt).toBe('number')

      const listWhileArchived = await client.call('session.list', {})
      const foundArchived = listWhileArchived.sessions.find((s: any) => s.id === sessionId)
      expect(foundArchived?.title).toBe('核心支付重构任务')
      expect(foundArchived?.archived).toBe(true)

      // Archived session remains 100% readable via stream.subscribe
      const archivedSnap = await client.call('stream.subscribe', {
        sessionId,
        afterSeq: -1,
        limit: 10,
      })
      expect(archivedSnap.session.archived).toBe(true)
      expect(archivedSnap.events.length).toBeGreaterThan(0)

      // Unarchiving & resetting custom title restores original state cleanly
      const unarchived = await client.call('session.archive', {
        sessionId,
        archived: false,
      })
      expect(Boolean(unarchived.session.archived)).toBe(false)

      const resetTitle = await client.call('session.rename', {
        sessionId,
        title: '',
      })
      expect(resetTitle.session.customTitle).toBeUndefined()

      client.close()
    } finally {
      fs.rmSync(fakeHome, { recursive: true, force: true })
    }
  })

  it('queues follow-up prompts while a turn is running instead of throwing Session is already processing a turn', async () => {
    let releaseFirstTurn: (() => void) | null = null
    const receivedPrompts: string[] = []
    const slowAdapter = {
      id: 'mock',
      displayName: 'Slow Queue Test Engine',
      kind: 'cli' as const,
      async probe() {
        return { ready: true, detail: 'ok' }
      },
      capabilities() {
        return { streaming: true, approvals: false, setModel: false, images: false }
      },
      async openSession(opts: any) {
        return {
          async prompt(turnId: string, text: string) {
            receivedPrompts.push(text)
            if (receivedPrompts.length === 1) {
              await new Promise<void>(resolve => {
                releaseFirstTurn = resolve
              })
            }
            opts.emit({
              type: 'message.assistant.delta',
              messageId: `a_${turnId}`,
              delta: `完成:${text}`,
            })
            opts.emit({
              type: 'turn.done',
              turnId,
              stopReason: 'end_turn',
            })
          },
          async cancel() {
            releaseFirstTurn?.()
          },
          async respondPermission() {},
          async close() {
            releaseFirstTurn?.()
          },
        }
      },
    }

    server = new HubServer({
      config: {
        hubId: 'hub_queue_test',
        name: 'QueueMac',
        role: 'slave',
        host: '127.0.0.1',
        port: 0,
        token: 'secret-queue-token',
        workspaceRoots: [repoRoot],
        cpaEndpoint: 'http://127.0.0.1:8317',
      },
      adapters: [slowAdapter as any],
    })

    const { port } = await server.start()
    const client = new TestRpcClient()
    await client.connect(`wss://127.0.0.1:${port}/v1/ws`)
    await client.call('hub.hello', {
      protocolVersion: PROTOCOL_VERSION,
      token: 'secret-queue-token',
      client: { name: 'test-ios', version: '0.6.8', platform: 'ios' },
    })

    const created = await client.call('session.create', {
      engineId: 'mock',
      cwd: repoRoot,
    })
    const sid = created.session.id

    // First prompt starts running and blocks on `releaseFirstTurn`
    const firstTurn = await client.call('session.prompt', {
      sessionId: sid,
      text: '第一条任务',
    })
    expect(firstTurn.turnId).toMatch(/^turn_/)

    // Second prompt sent while state === 'running' MUST queue instead of throwing Conflict!
    const secondTurn = await client.call('session.prompt', {
      sessionId: sid,
      text: '第二条追加指令',
    })
    expect(secondTurn.turnId).toMatch(/^turn_/)

    const listedWhileRunning = await client.call('session.list', {})
    const runningMeta = listedWhileRunning.sessions.find((s: any) => s.id === sid)
    expect(runningMeta?.state).toBe('running')
    expect(runningMeta?.queuedPrompts).toEqual(['第二条追加指令'])

    // Release first turn -> queued second turn drains automatically and session returns to idle!
    releaseFirstTurn!()
    await client.waitForEvent(
      'turn.done',
      ev => ev.sessionId === sid && ev.turnId === secondTurn.turnId,
    )

    expect(receivedPrompts).toEqual(['第一条任务', '第二条追加指令'])
    const listedAfterDrain = await client.call('session.list', {})
    const idleMeta = listedAfterDrain.sessions.find((s: any) => s.id === sid)
    expect(idleMeta?.state).toBe('idle')
    expect(idleMeta?.queuedPrompts).toBeUndefined()

    client.close()
  })

  it('cancels individual queued prompts by index, clears all queued prompts, and handles empty/out-of-range idempotently', async () => {
    let releaseBlockingTurn: (() => void) | null = null
    let shouldBlockNextTurn = true
    const receivedPrompts: string[] = []
    const slowAdapter = {
      id: 'mock',
      displayName: 'Cancel Queue Test Engine',
      kind: 'cli' as const,
      async probe() {
        return { installed: true, ready: true, detail: 'ok' }
      },
      capabilities() {
        return {
          permissions: false,
          images: false,
          modelSwitch: false,
          modes: false,
          history: false,
          cancel: true,
        }
      },
      async openSession(opts: any) {
        return {
          async prompt(turnId: string, text: string) {
            receivedPrompts.push(text)
            if (shouldBlockNextTurn) {
              shouldBlockNextTurn = false
              await new Promise<void>(resolve => {
                releaseBlockingTurn = resolve
              })
            }
            opts.emit({
              type: 'message.assistant.delta',
              messageId: `a_${turnId}`,
              text: `完成:${text}`,
            })
            opts.emit({
              type: 'turn.done',
              turnId,
              stopReason: 'end_turn',
            })
          },
          async cancel() {
            releaseBlockingTurn?.()
          },
          async respondPermission() {},
          async close() {
            releaseBlockingTurn?.()
          },
        }
      },
    }

    server = new HubServer({
      config: {
        hubId: 'hub_cancel_queue_test',
        name: 'CancelQueueMac',
        role: 'slave',
        host: '127.0.0.1',
        port: 0,
        token: 'secret-cancel-queue-token',
        workspaceRoots: [repoRoot],
        cpaEndpoint: 'http://127.0.0.1:8317',
      },
      adapters: [slowAdapter as any],
    })

    const { port } = await server.start()
    const client = new TestRpcClient()
    await client.connect(`wss://127.0.0.1:${port}/v1/ws`)
    await client.call('hub.hello', {
      protocolVersion: PROTOCOL_VERSION,
      token: 'secret-cancel-queue-token',
      client: { name: 'test-ios', version: '0.7.5', platform: 'ios' },
    })

    const created = await client.call('session.create', {
      engineId: 'mock',
      cwd: repoRoot,
    })
    const sid = created.session.id

    // 1. Start blocking turn and queue 3 prompts
    await client.call('session.prompt', { sessionId: sid, text: '主任务 1' })
    await client.call('session.prompt', { sessionId: sid, text: '队列消息 A' })
    await client.call('session.prompt', { sessionId: sid, text: '队列消息 B（待取消）' })
    const thirdQueued = await client.call('session.prompt', { sessionId: sid, text: '队列消息 C' })

    // Cancel middle item (index = 1)
    const cancelMid = await client.call('session.cancelQueued', {
      sessionId: sid,
      index: 1,
    })
    expect(cancelMid).toEqual({
      ok: true,
      queuedPrompts: ['队列消息 A', '队列消息 C'],
      removedCount: 1,
    })

    // Out-of-range index is idempotent
    const outOfRange = await client.call('session.cancelQueued', {
      sessionId: sid,
      index: 99,
    })
    expect(outOfRange).toEqual({
      ok: true,
      queuedPrompts: ['队列消息 A', '队列消息 C'],
      removedCount: 0,
    })

    // Release blocking turn -> only A and C execute in FIFO order; B is NEVER executed
    releaseBlockingTurn!()
    await client.waitForEvent(
      'turn.done',
      ev => ev.sessionId === sid && ev.turnId === thirdQueued.turnId,
    )
    expect(receivedPrompts).toEqual(['主任务 1', '队列消息 A', '队列消息 C'])

    // Empty queue cancel is idempotent
    const onEmpty = await client.call('session.cancelQueued', {
      sessionId: sid,
      all: true,
    })
    expect(onEmpty).toEqual({
      ok: true,
      queuedPrompts: [],
      removedCount: 0,
    })

    // 2. Start a second blocking turn, queue 2 prompts, then clear all with `all: true`
    shouldBlockNextTurn = true
    const secondMain = await client.call('session.prompt', { sessionId: sid, text: '主任务 2' })
    await client.call('session.prompt', { sessionId: sid, text: '全部取消-1' })
    await client.call('session.prompt', { sessionId: sid, text: '全部取消-2' })

    const clearAll = await client.call('session.cancelQueued', {
      sessionId: sid,
      all: true,
    })
    expect(clearAll).toEqual({
      ok: true,
      queuedPrompts: [],
      removedCount: 2,
    })

    const listedAfterClear = await client.call('session.list', {})
    expect(listedAfterClear.sessions.find((s: any) => s.id === sid)?.queuedPrompts).toBeUndefined()

    releaseBlockingTurn!()
    await client.waitForEvent(
      'turn.done',
      ev => ev.sessionId === sid && ev.turnId === secondMain.turnId,
    )
    await new Promise(r => setTimeout(r, 60))

    expect(receivedPrompts).toEqual(['主任务 1', '队列消息 A', '队列消息 C', '主任务 2'])
    const finalList = await client.call('session.list', {})
    const finalMeta = finalList.sessions.find((s: any) => s.id === sid)
    expect(finalMeta?.state).toBe('idle')
    expect(finalMeta?.queuedPrompts).toBeUndefined()

    client.close()
  })

  it('restarts persistent AGY stream-json worker on model/effort/mode switch and avoids bogus effort suffixes', async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-agy-switch-'))
    const mockAgyScript = path.join(tmpDir, 'mock-agy-stream.mjs')
    fs.writeFileSync(
      mockAgyScript,
      `
import readline from 'node:readline'
const args = process.argv.slice(2)
const convIdx = args.indexOf('--conversation')
const convId = convIdx >= 0 ? args[convIdx + 1] : 'conv_agy_switch_1'
const rl = readline.createInterface({ input: process.stdin })
let sentInit = false
rl.on('line', line => {
  if (!line.trim()) return
  if (!sentInit) {
    sentInit = true
    process.stdout.write(JSON.stringify({ event: 'init', conversation_id: convId }) + '\\n')
  }
  const reply = 'ARGS:' + args.join(' ')
  process.stdout.write(JSON.stringify({ event: 'step_update', step_update: { step_type: 'agent_response', text_delta: reply } }) + '\\n')
  process.stdout.write(JSON.stringify({ event: 'result', result: { conversation_id: convId, status: 'OK', response: reply } }) + '\\n')
})
`,
      'utf8',
    )

    setCachedAgyCatalog([
      {
        id: 'gemini-4-carbon',
        name: 'Gemini 4 Carbon',
        providerId: 'sub_agy',
        efforts: ['low', 'medium', 'high'],
        defaultEffort: 'high',
      },
      {
        id: 'claude-sonnet-4-6',
        name: 'Claude Sonnet 4.6',
        providerId: 'sub_agy',
      },
    ])

    try {
      const agyAdapter = new AgyEngineAdapter({
        binary: process.execPath,
        args: [mockAgyScript, '--input-format', 'stream-json', '--output-format', 'stream-json'],
      })

      server = new HubServer({
        config: {
          hubId: 'hub_agy_switch',
          name: 'AgySwitchMac',
          role: 'slave',
          host: '127.0.0.1',
          port: 0,
          token: 'secret-agy-switch-token',
          workspaceRoots: [repoRoot],
          cpaEndpoint: 'http://127.0.0.1:8317',
        },
        adapters: [agyAdapter],
      })

      const { port } = await server.start()
      const client = new TestRpcClient()
      await client.connect(`wss://127.0.0.1:${port}/v1/ws`)
      await client.call('hub.hello', {
        protocolVersion: PROTOCOL_VERSION,
        token: 'secret-agy-switch-token',
        client: { name: 'test-ios', version: '0.6.9', platform: 'ios' },
      })

      const { session } = await client.call('session.create', {
        engineId: 'agy',
        cwd: repoRoot,
        modelId: 'gemini-4-carbon',
        reasoningEffort: 'high',
        mode: 'auto',
      })

      // Turn 1: initial worker runs gemini-4-carbon-high
      const t1 = await client.call('session.prompt', {
        sessionId: session.id,
        text: 'turn 1',
      })
      await client.waitForEvent('turn.done', ev => ev.turnId === t1.turnId)
      const turn1Delta = client.events.find(
        (e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> =>
          e.type === 'message.assistant.delta' && e.text.includes('--model gemini-4-carbon-high'),
      )
      expect(turn1Delta?.text).toContain('--effort high')
      expect(turn1Delta?.text).toContain('--mode auto')

      // Switch to non-effort model claude-sonnet-4-6 + plan mode via session.setModel
      const updatedMeta = await client.call('session.setModel', {
        sessionId: session.id,
        modelId: 'claude-sonnet-4-6',
        reasoningEffort: 'high',
        mode: 'plan',
      })
      expect(updatedMeta.session.model).toBe('claude-sonnet-4-6')
      expect(updatedMeta.session.mode).toBe('plan')

      // Turn 2: persistent worker MUST have restarted with --conversation conv_agy_switch_1 and NO -high suffix!
      const t2 = await client.call('session.prompt', {
        sessionId: session.id,
        text: 'turn 2',
      })
      await client.waitForEvent('turn.done', ev => ev.turnId === t2.turnId)
      const turn2Delta = client.events.find(
        (e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> =>
          e.type === 'message.assistant.delta' && e.text.includes('--model claude-sonnet-4-6'),
      )
      expect(turn2Delta).toBeDefined()
      expect(turn2Delta?.text).toContain('--conversation conv_agy_switch_1')
      expect(turn2Delta?.text).toContain('--mode plan')
      expect(turn2Delta?.text).not.toContain('claude-sonnet-4-6-high')
      expect(turn2Delta?.text).not.toContain('--effort')

      // Turn 3: switching effort via session.prompt overrides also restarts worker with new effort
      const t3 = await client.call('session.prompt', {
        sessionId: session.id,
        text: 'turn 3',
        modelId: 'gemini-4-carbon',
        reasoningEffort: 'low',
      })
      await client.waitForEvent('turn.done', ev => ev.turnId === t3.turnId)
      const turn3Delta = client.events.find(
        (e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> =>
          e.type === 'message.assistant.delta' && e.text.includes('--model gemini-4-carbon-low'),
      )
      expect(turn3Delta?.text).toContain('--effort low')

      client.close()
    } finally {
      setCachedAgyCatalog(null)
    }
  })

  it('persists session.setModel on unmaterialized host:agy:* sessions and syncs live tmux /model', async () => {
    const fakeHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-host-agy-model-'))
    const convId = 'ac96b78c-test-host-conv'
    const logDir = path.join(fakeHome, '.gemini', 'antigravity-cli', 'brain', convId, '.system_generated', 'logs')
    fs.mkdirSync(logDir, { recursive: true })
    fs.writeFileSync(
      path.join(logDir, 'transcript.jsonl'),
      [
        JSON.stringify({
          type: 'USER_INPUT',
          created_at: '2026-11-23T00:00:00Z',
          content:
            '<USER_REQUEST>你好</USER_REQUEST>\n<USER_SETTINGS_CHANGE>The user changed setting `Model Selection` from None to Gemini 4 Carbon (High).</USER_SETTINGS_CHANGE>',
        }),
        JSON.stringify({
          type: 'PLANNER_RESPONSE',
          created_at: '2026-11-23T00:00:02Z',
          response: '你好！',
        }),
      ].join('\n') + '\n',
      'utf8',
    )

    const injectedTmuxKeys: string[][] = []
    const bridge = new TerminalBridge({
      homeDir: fakeHome,
      tmuxBin: '/usr/local/bin/tmux',
      tmuxSocketPaths: [],
      isPidAlive: pid => pid === 4242,
      runCommand: async (bin, args) => {
        if (bin === '/usr/local/bin/tmux' && args.includes('list-panes')) {
          return '%1|/dev/ttys021|0\n'
        }
        if (bin === 'ps' && args[0] === '-axo') {
          return ` 4242 ttys021 agy --conversation ${convId}\n`
        }
        if (bin === 'lsof' || bin.endsWith('/lsof')) {
          return `p4242\nfcwd\nn${repoRoot}\n`
        }
        if (bin === '/usr/local/bin/tmux' && args.includes('capture-pane')) {
          return '> \n? for shortcuts   auto · Gemini 4 Carbon · high\n'
        }
        if (bin === '/usr/local/bin/tmux' && args.includes('send-keys')) {
          injectedTmuxKeys.push(args)
        }
        return ''
      },
    })

    // Unsafe characters in model arg must be rejected before touching terminal
    const rejectedBad = await bridge.sendModelSwitchToSession(
      { nativeId: convId, engineId: 'agy', cwd: repoRoot },
      'bad model; rm -rf /',
    )
    expect(rejectedBad).toBe(false)
    expect(injectedTmuxKeys).toHaveLength(0)

    const inspector = new HostTaskInspector({ homeDir: fakeHome, terminalBridge: bridge })
    setCachedAgyCatalog([
      {
        id: 'gemini-4-carbon',
        name: 'Gemini 4 Carbon',
        providerId: 'sub_agy',
        efforts: ['low', 'medium', 'high'],
        defaultEffort: 'high',
      },
      {
        id: 'gemini-4-argon',
        name: 'Gemini 4 Argon',
        providerId: 'sub_agy',
        efforts: ['low', 'medium', 'high'],
        defaultEffort: 'high',
      },
    ])

    try {
      server = new HubServer({
        config: {
          hubId: 'hub_host_model_test',
          name: 'HostModelMac',
          role: 'slave',
          host: '127.0.0.1',
          port: 0,
          token: 'secret-host-model-token',
          workspaceRoots: [repoRoot, fakeHome],
          cpaEndpoint: 'http://127.0.0.1:8317',
        },
        adapters: [new AgyEngineAdapter()],
        hostInspector: inspector,
      })

      const { port } = await server.start()
      const client = new TestRpcClient()
      await client.connect(`wss://127.0.0.1:${port}/v1/ws`)
      await client.call('hub.hello', {
        protocolVersion: PROTOCOL_VERSION,
        token: 'secret-host-model-token',
        client: { name: 'test-ios', version: '0.6.9', platform: 'ios' },
      })

      const hostSid = `host:agy:${convId}`
      const initialList = await client.call('session.list', {})
      const initialHost = initialList.sessions.find((s: any) => s.id === hostSid)
      expect(initialHost?.model).toBe('gemini-4-carbon')

      // Switch model on unmaterialized host session: must NOT throw NotFound, must persist & send /model to tmux!
      const switched = await client.call('session.setModel', {
        sessionId: hostSid,
        modelId: 'gemini-4-argon',
        reasoningEffort: 'medium',
      })
      expect(switched.session.model).toBe('gemini-4-argon')
      expect(switched.session.reasoningEffort).toBe('medium')

      if (process.platform === 'darwin' || process.platform === 'linux') {
        expect(injectedTmuxKeys.some(a => a.includes('/model gemini-4-argon-medium'))).toBe(true)
      }

      // Subsequent stream.subscribe MUST preserve gemini-4-argon instead of snapping back to transcript's gemini-4-carbon!
      const subSnap = await client.call('stream.subscribe', {
        sessionId: hostSid,
        afterSeq: -1,
      })
      expect(subSnap.session.model).toBe('gemini-4-argon')
      expect(subSnap.session.reasoningEffort).toBe('medium')

      client.close()
    } finally {
      setCachedAgyCatalog(null)
    }
  })
})
