#!/usr/bin/env node
/**
 * Minimal ACP-compliant stdio agent used by ClaudeCodeAdapter integration tests.
 * Speaks the exact JSON-RPC 2.0 wire format of `@agentclientprotocol/claude-agent-acp`.
 */
import readline from 'node:readline'

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
let nextReqId = 100
const pendingCallbacks = new Map()

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n')
}

rl.on('line', async line => {
  if (!line.trim()) return
  const msg = JSON.parse(line)

  // Response to our inbound request (e.g. session/request_permission)
  if ((typeof msg.id === 'number' || typeof msg.id === 'string') && ('result' in msg || 'error' in msg)) {
    const resolve = pendingCallbacks.get(msg.id)
    if (resolve) {
      pendingCallbacks.delete(msg.id)
      resolve(msg.result)
    }
    return
  }

  if (msg.method === 'initialize') {
    send({
      jsonrpc: '2.0',
      id: msg.id,
      result: {
        protocolVersion: 1,
        agentInfo: { name: 'claude-agent-acp-mock', version: '0.108.0' },
      },
    })
    return
  }

  if (msg.method === 'session/resume') {
    send({
      jsonrpc: '2.0',
      id: msg.id,
      result: {
        models: {
          currentModelId: 'claude-sonnet-4-5',
          availableModels: [
            { modelId: 'claude-sonnet-4-5', name: 'Claude Sonnet 4.5' },
            { modelId: 'claude-opus-4-1', name: 'Claude Opus 4.1' },
          ],
        },
      },
    })
    return
  }

  if (msg.method === 'session/new') {
    send({
      jsonrpc: '2.0',
      id: msg.id,
      result: {
        sessionId: 'acp_sess_test_1',
        models: {
          currentModelId: 'claude-sonnet-4-5',
          availableModels: [
            { modelId: 'claude-sonnet-4-5', name: 'Claude Sonnet 4.5' },
            { modelId: 'claude-opus-4-1', name: 'Claude Opus 4.1' },
          ],
        },
      },
    })
    return
  }

  if (msg.method === 'session/prompt') {
    const sessionId = msg.params.sessionId
    const userText = msg.params.prompt?.[0]?.text ?? ''

    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId,
        update: {
          sessionUpdate: 'agent_thought_chunk',
          content: { type: 'text', text: 'Planning write operation...' },
        },
      },
    })

    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId,
        update: {
          sessionUpdate: 'tool_call',
          toolCallId: 'tc_write_1',
          title: 'Write notes.txt',
          kind: 'edit',
          status: 'pending',
          rawInput: { path: 'notes.txt' },
        },
      },
    })

    const permReqId = nextReqId++
    const decisionPromise = new Promise(resolve => {
      pendingCallbacks.set(permReqId, resolve)
    })

    send({
      jsonrpc: '2.0',
      id: permReqId,
      method: 'session/request_permission',
      params: {
        sessionId,
        toolCall: {
          toolCallId: 'tc_write_1',
          title: 'Write notes.txt',
        },
        options: [
          { optionId: 'allow_once_id', name: 'Allow once', kind: 'allow_once' },
          { optionId: 'reject_once_id', name: 'Reject', kind: 'reject_once' },
        ],
      },
    })

    const decision = await decisionPromise
    const selected = decision?.outcome?.optionId

    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId,
        update: {
          sessionUpdate: 'tool_call_update',
          toolCallId: 'tc_write_1',
          status: selected === 'allow_once_id' ? 'completed' : 'failed',
          content: [
            {
              type: 'content',
              content: {
                type: 'text',
                text: selected === 'allow_once_id' ? 'Wrote 12 bytes' : 'Permission denied',
              },
            },
          ],
        },
      },
    })

    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId,
        update: {
          sessionUpdate: 'agent_message_chunk',
          content: {
            type: 'text',
            text: `Claude ACP finished (${selected}) for: ${userText}`,
          },
        },
      },
    })

    send({
      jsonrpc: '2.0',
      id: msg.id,
      result: { stopReason: 'end_turn' },
    })
  }
})
