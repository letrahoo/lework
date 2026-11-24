import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { WebSocket } from 'ws'
import { PROTOCOL_VERSION } from '@lework/protocol'
import { DiagnosticEchoAdapter } from '../src/engines/echo-adapter.ts'
import { HubServer } from '../src/server.ts'
import { loadOrCreateHubTls } from '../src/security/tls.ts'

describe('HubServer WebSocket runtime diagnostic logs', () => {
  const servers: HubServer[] = []
  const tempDirs: string[] = []
  const prevLeworkHome = process.env.LEWORK_HOME

  afterEach(async () => {
    vi.restoreAllMocks()
    while (servers.length > 0) {
      const srv = servers.pop()
      if (srv) await srv.stop()
    }
    if (prevLeworkHome === undefined) {
      delete process.env.LEWORK_HOME
    } else {
      process.env.LEWORK_HOME = prevLeworkHome
    }
    while (tempDirs.length > 0) {
      const dir = tempDirs.pop()
      if (dir) fs.rmSync(dir, { recursive: true, force: true })
    }
  })

  it('logs ws open, hello ok, rpc error, and close without leaking tokens', async () => {
    const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-ws-log-'))
    tempDirs.push(tmpHome)
    process.env.LEWORK_HOME = tmpHome
    const token = 'lework_oa.secret_runtime_log_token_test_123456'

    const logLines: string[] = []
    const warnLines: string[] = []
    vi.spyOn(console, 'log').mockImplementation((...args: unknown[]) => {
      logLines.push(args.map(String).join(' '))
    })
    vi.spyOn(console, 'warn').mockImplementation((...args: unknown[]) => {
      warnLines.push(args.map(String).join(' '))
    })

    const server = new HubServer({
      config: {
        hubId: 'hub_ws_log_test',
        name: 'WsLogTestHub',
        role: 'slave',
        host: '127.0.0.1',
        port: 0,
        token,
        workspaceRoots: [tmpHome],
        cpaEndpoint: 'http://127.0.0.1:8317',
      },
      adapters: [new DiagnosticEchoAdapter()],
      tls: loadOrCreateHubTls({ dir: path.join(tmpHome, 'tls') }),
    })
    servers.push(server)
    const { port } = await server.start()

    // 1. Failed auth emits warning without leaking the attempted token
    const badToken = 'lework_oa.bad_secret_token_999999'
    const badWs = new WebSocket(`wss://127.0.0.1:${port}/v1/ws`, { rejectUnauthorized: false })
    await new Promise<void>((resolve, reject) => {
      badWs.once('open', () => resolve())
      badWs.once('error', reject)
    })
    const badReply = await new Promise<string>(resolve => {
      badWs.once('message', raw => resolve(String(raw)))
      badWs.send(
        JSON.stringify({
          jsonrpc: '2.0',
          id: 1,
          method: 'hub.hello',
          params: {
            protocolVersion: PROTOCOL_VERSION,
            token: badToken,
            client: { name: 'lework-desktop', version: '0.7.10', platform: 'macOS Desktop' },
          },
        }),
      )
    })
    expect(badReply).toContain('Invalid or expired access token')
    await new Promise<void>(resolve => {
      badWs.once('close', () => resolve())
    })

    // 2. Successful hello + clean close
    const goodWs = new WebSocket(`wss://127.0.0.1:${port}/v1/ws`, { rejectUnauthorized: false })
    await new Promise<void>((resolve, reject) => {
      goodWs.once('open', () => resolve())
      goodWs.once('error', reject)
    })
    const okReply = await new Promise<string>(resolve => {
      goodWs.once('message', raw => resolve(String(raw)))
      goodWs.send(
        JSON.stringify({
          jsonrpc: '2.0',
          id: 1,
          method: 'hub.hello',
          params: {
            protocolVersion: PROTOCOL_VERSION,
            token,
            client: { name: 'lework-desktop', version: '0.7.10', platform: 'macOS Desktop' },
          },
        }),
      )
    })
    expect(okReply).toContain('hub_ws_log_test')
    const closed = new Promise<void>(resolve => goodWs.once('close', () => resolve()))
    goodWs.close(1000, 'test done')
    await closed
    await new Promise(r => setTimeout(r, 30))

    const allLogs = [...logLines, ...warnLines].join('\n')
    expect(allLogs).toContain('[lework-ws]')
    expect(allLogs).toContain('open remote=')
    expect(allLogs).toContain('hello ok client=lework-desktop/macOS Desktop@0.7.10')
    expect(allLogs).toContain('rpc error method=hub.hello')
    expect(allLogs).toContain('close code=1000')
    expect(allLogs).not.toContain(token)
    expect(allLogs).not.toContain(badToken)
  })
})
