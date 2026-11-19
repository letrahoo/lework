import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  PROTOCOL_VERSION,
  AgentEvent,
  Methods,
  Notifications,
  RpcRequest,
  RpcResponse,
} from '../src/index.ts'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

describe('protocol-schema v1', () => {
  it('validates all conformance event fixtures', () => {
    const raw = fs.readFileSync(path.join(__dirname, '../fixtures/events.json'), 'utf8')
    const events = JSON.parse(raw) as unknown[]
    expect(events.length).toBeGreaterThanOrEqual(10)
    for (const ev of events) {
      const parsed = AgentEvent.parse(ev)
      expect(parsed.sessionId).toBe('s_1')
    }
  })

  it('validates JSON-RPC request/response envelopes and method schemas', () => {
    const req = RpcRequest.parse({
      jsonrpc: '2.0',
      id: 1,
      method: 'hub.hello',
      params: {
        protocolVersion: PROTOCOL_VERSION,
        token: 'dev-secret-token',
        client: { name: 'lework-ios', version: '0.1.0', platform: 'ios' },
      },
    })
    const helloParams = Methods['hub.hello'].params.parse(req.params)
    expect(helloParams.protocolVersion).toBe(PROTOCOL_VERSION)

    const resp = RpcResponse.parse({
      jsonrpc: '2.0',
      id: 1,
      result: {
        protocolVersion: PROTOCOL_VERSION,
        hub: {
          id: 'hub_local',
          name: 'MacBook-Pro',
          role: 'slave',
          platform: 'darwin',
          version: '0.1.0',
        },
        capabilities: ['engine.claude.v1'],
      },
    })
    const helloResult = Methods['hub.hello'].result.parse(resp.result)
    expect(helloResult.hub.role).toBe('slave')
  })

  it('validates stream.event notification', () => {
    const n = Notifications['stream.event'].parse({
      event: {
        seq: 1,
        sessionId: 's_1',
        ts: 1700000000100,
        type: 'message.assistant.delta',
        messageId: 'm_1',
        text: 'Hello',
      },
    })
    expect(n.event.type).toBe('message.assistant.delta')
  })
})
