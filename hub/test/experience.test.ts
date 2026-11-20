import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { WebSocket } from 'ws'
import { PROTOCOL_VERSION, type AgentEvent } from '@lework/protocol'
import {
  NotificationDispatcher,
  buildSessionDeepLink,
  type NotificationConfig,
} from '../src/notifications/notifier.ts'
import { loadOrCreateHubTls } from '../src/security/tls.ts'
import { HubServer } from '../src/server.ts'

class MiniWsClient {
  private ws!: WebSocket
  private nextId = 1
  private pending = new Map<number, { resolve: (v: any) => void; reject: (e: any) => void }>()
  readonly events: AgentEvent[] = []

  async connect(url: string): Promise<void> {
    this.ws = new WebSocket(url, { rejectUnauthorized: false })
    this.ws.on('message', raw => {
      const msg = JSON.parse(String(raw))
      if (typeof msg.id === 'number') {
        const waiter = this.pending.get(msg.id)
        if (waiter) {
          this.pending.delete(msg.id)
          if (msg.error) waiter.reject(msg.error)
          else waiter.resolve(msg.result)
        }
      } else if (msg.method === 'stream.event' && msg.params?.event) {
        this.events.push(msg.params.event as AgentEvent)
      }
    })
    await new Promise<void>((resolve, reject) => {
      this.ws.once('open', () => resolve())
      this.ws.once('error', reject)
    })
  }

  call<T = any>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    const id = this.nextId++
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }))
    })
  }

  close(): void {
    this.ws?.close()
  }
}

describe('v0.6.0 Experience Optimizations: Offline Notifications, @file Search & Image Prompts', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('dispatches unanswered approval & offline turn completion notifications without spamming resolved approvals', async () => {
    vi.useFakeTimers()
    const delivered: Array<{ url: string; headers: Record<string, string>; body: any }> = []
    const fakeFetch = vi.fn(async (input: unknown, init?: RequestInit) => {
      delivered.push({
        url: String(input),
        headers: (init?.headers as Record<string, string>) ?? {},
        body: init?.body ? JSON.parse(String(init.body)) : null,
      })
      return new Response('{"code":200}', { status: 200 })
    }) as unknown as typeof fetch

    let subscribers = 1
    let currentCfg: NotificationConfig = {
      enabled: true,
      provider: 'bark',
      url: 'https://api.day.app/test_device_key',
      approvalDelayMs: 10_000,
      minTurnDurationMs: 15_000,
    }

    const dispatcher = new NotificationDispatcher({
      getConfig: () => currentCfg,
      getHubInfo: () => ({
        id: 'hub_mac_01',
        name: 'MacBook-Pro',
        role: 'slave',
        platform: 'darwin',
        version: '0.6.0',
      }),
      getSessionMeta: sid => ({
        id: sid,
        engineId: 'claude',
        cwd: '/workspace/lework',
        title: '重构资源分发模块',
        state: 'running',
        createdAt: 1000,
        updatedAt: 2000,
        lastSeq: 5,
      }),
      getActiveSubscriberCount: () => subscribers,
      fetchImpl: fakeFetch,
    })

    // 1. Approval resolved quickly while user is watching on phone -> ZERO push noise
    dispatcher.onEvent({
      seq: 1,
      sessionId: 'host:claude:s1',
      ts: 10_000,
      type: 'permission.requested',
      requestId: 'perm_fast',
      title: 'Allow bash: git status',
      options: [{ id: '1', name: 'Allow', kind: 'allow_once' }],
    })
    vi.advanceTimersByTime(3_000)
    dispatcher.onEvent({
      seq: 2,
      sessionId: 'host:claude:s1',
      ts: 13_000,
      type: 'permission.resolved',
      requestId: 'perm_fast',
      optionId: '1',
      outcome: 'selected',
    })
    await vi.advanceTimersByTimeAsync(15_000)
    expect(delivered).toHaveLength(0)

    // 2. Approval unanswered while phone is locked (0 subscribers) -> Bark timeSensitive push with session deepLink
    subscribers = 0
    dispatcher.onEvent({
      seq: 3,
      sessionId: 'host:claude:s1',
      ts: 20_000,
      type: 'permission.requested',
      requestId: 'perm_offline',
      title: 'Allow bash: rm -rf dist && pnpm build',
      options: [{ id: '1', name: 'Allow', kind: 'allow_once' }],
    })
    await vi.advanceTimersByTimeAsync(2_000)
    expect(delivered).toHaveLength(1)
    expect(delivered[0]!.body).toMatchObject({
      group: 'Lework',
      level: 'timeSensitive',
      url: buildSessionDeepLink('host:claude:s1', 'hub_mac_01'),
    })
    expect(delivered[0]!.body.title).toContain('等待确认')

    // 3. Switch to Feishu signed interactive card & verify turn completion push when offline
    currentCfg = {
      enabled: true,
      provider: 'feishu',
      url: 'https://open.feishu.cn/open-apis/bot/v2/hook/mock_hook',
      secret: 'feishu_test_secret',
    }
    dispatcher.onEvent({
      seq: 4,
      sessionId: 'host:claude:s1',
      ts: 30_000,
      type: 'message.user',
      messageId: 'm_u1',
      text: 'Run full build',
    })
    dispatcher.onEvent({
      seq: 5,
      sessionId: 'host:claude:s1',
      ts: 52_000,
      type: 'turn.done',
      turnId: 't_1',
      stopReason: 'end_turn',
    })
    await vi.advanceTimersByTimeAsync(100)
    expect(delivered).toHaveLength(2)
    expect(delivered[1]!.body.msg_type).toBe('interactive')
    expect(delivered[1]!.body.sign).toBeTruthy()
    expect(JSON.stringify(delivered[1]!.body.card)).toContain('lework://session?id=host%3Aclaude%3As1')

    dispatcher.dispose()
  })

  it('serves workspace.files fuzzy search (filtering secrets) and stages multimodal images on session.prompt', async () => {
    const prevHome = process.env.LEWORK_HOME
    const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-exp-home-'))
    process.env.LEWORK_HOME = tmpHome
    const tmpWs = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-exp-ws-'))
    const tlsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-exp-tls-'))
    fs.mkdirSync(path.join(tmpWs, 'src', 'ui'), { recursive: true })
    fs.writeFileSync(path.join(tmpWs, 'README.md'), '# Demo\n')
    fs.writeFileSync(path.join(tmpWs, 'src', 'server.ts'), 'export const ok = true\n')
    fs.writeFileSync(path.join(tmpWs, 'src', 'ui', 'SessionComposer.kt'), 'class Composer\n')
    // Secret files that MUST never leak into @file autocomplete
    fs.writeFileSync(path.join(tmpWs, '.env'), 'SECRET_KEY=123\n')
    fs.writeFileSync(path.join(tmpWs, 'id_rsa'), 'PRIVATE KEY\n')

    const token = crypto.randomBytes(18).toString('base64url')
    const hub = new HubServer({
      config: {
        hubId: 'hub_exp_test',
        name: 'ExpTestHost',
        role: 'standalone',
        host: '127.0.0.1',
        port: 0,
        token,
        workspaceRoots: [tmpWs],
        cpaEndpoint: 'http://127.0.0.1:59999',
      },
      tls: loadOrCreateHubTls({ dir: tlsDir, commonName: 'lework-exp-test' }),
    })
    const { port } = await hub.start()
    const client = new MiniWsClient()

    try {
      await client.connect(`wss://127.0.0.1:${port}/v1/ws`)
      await client.call('hub.hello', {
        protocolVersion: PROTOCOL_VERSION,
        token,
        client: { name: 'test-client', version: '0.6.0', platform: 'test' },
      })

      const allFiles = await client.call<{ path: string; files: string[] }>('workspace.files', { path: tmpWs })
      expect(allFiles.files).toContain('README.md')
      expect(allFiles.files).toContain('src/server.ts')
      expect(allFiles.files).toContain('src/ui/SessionComposer.kt')
      expect(allFiles.files).not.toContain('.env')
      expect(allFiles.files).not.toContain('id_rsa')

      const filtered = await client.call<{ path: string; files: string[] }>('workspace.files', {
        path: tmpWs,
        query: 'composer',
      })
      expect(filtered.files).toEqual(['src/ui/SessionComposer.kt'])

      // Create embedded Pi session and send an image-only prompt
      const { session } = await client.call('session.create', {
        engineId: 'pi',
        cwd: tmpWs,
      })
      const onePxPngBase64 =
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='
      const { turnId } = await client.call('session.prompt', {
        sessionId: session.id,
        text: '',
        images: [
          {
            mimeType: 'image/png',
            dataBase64: onePxPngBase64,
            fileName: 'screenshot.png',
          },
        ],
      })
      expect(turnId).toMatch(/^turn_/)

      await vi.waitFor(() => {
        const done = client.events.find((e: AgentEvent) => e.sessionId === session.id && e.type === 'turn.done')
        expect(done).toBeTruthy()
      })
      const userEv = client.events.find(
        (e: AgentEvent): e is Extract<AgentEvent, { type: 'message.user' }> =>
          e.sessionId === session.id && e.type === 'message.user',
      )
      expect(userEv?.imageCount).toBe(1)
    } finally {
      client.close()
      await hub.stop()
      if (prevHome === undefined) delete process.env.LEWORK_HOME
      else process.env.LEWORK_HOME = prevHome
      fs.rmSync(tmpHome, { recursive: true, force: true })
      fs.rmSync(tmpWs, { recursive: true, force: true })
      fs.rmSync(tlsDir, { recursive: true, force: true })
    }
  })
})
