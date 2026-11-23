import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import type { AgentEvent, EngineCapabilities, EngineStatus } from '@lework/protocol'
import { AgyEngineAdapter } from '../src/engines/agy-adapter.ts'
import { ClaudeCodeAdapter } from '../src/engines/claude-adapter.ts'
import { CodexEngineAdapter } from '../src/engines/codex-adapter.ts'
import { formatEngineTurnError, isLikelyNetworkError } from '../src/engines/network-error.ts'
import type { EngineAdapter, EngineSession, EngineSessionInit } from '../src/engines/types.ts'
import { SessionManager } from '../src/session-manager.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'

describe('Network Error Normalization & Seamless Turn Recovery', () => {
  const tmpDirs: string[] = []

  function makeTmpDir(prefix: string): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
    tmpDirs.push(dir)
    return dir
  }

  afterEach(() => {
    while (tmpDirs.length > 0) {
      const dir = tmpDirs.pop()!
      fs.rmSync(dir, { recursive: true, force: true })
    }
  })

  it('classifies English & Chinese network errors accurately without false positives', () => {
    const networkSamples = [
      'read ECONNRESET',
      'getaddrinfo ENOTFOUND api.anthropic.com',
      'connect ETIMEDOUT 104.18.0.1:443',
      'getaddrinfo EAI_AGAIN api.openai.com',
      'TypeError: fetch failed',
      'socket hang up',
      'UND_ERR_CONNECT_TIMEOUT',
      'connect EHOSTUNREACH 192.168.1.1',
      'connect ENETUNREACH',
      'HTTP 502 Bad Gateway',
      '503 Service Unavailable',
      'Upstream returned 504 Gateway Timeout',
      'TLS handshake timeout',
      'broken pipe',
      'Unexpected EOF while reading stream',
      '电脑断网，连接断开',
      '请求超时，目标服务不可达',
    ]
    for (const sample of networkSamples) {
      expect(isLikelyNetworkError(sample), `Expected network error for: ${sample}`).toBe(true)
    }

    const nonNetworkSamples = [
      'FAILED_PRECONDITION (code 400): User location is not supported for the API use.',
      'Invalid parameter: modelId is required',
      'SyntaxError: Unexpected token } in JSON at position 12',
      'Permission denied: /etc/shadow',
      'typeof value === "string"',
    ]
    for (const sample of nonNetworkSamples) {
      expect(isLikelyNetworkError(sample), `Expected non-network error for: ${sample}`).toBe(false)
    }

    const fetchWithCause = new TypeError('fetch failed', {
      cause: { code: 'ENOTFOUND', message: 'getaddrinfo ENOTFOUND api.anthropic.com' },
    })
    const formatted = formatEngineTurnError(fetchWithCause)
    expect(formatted).toContain('网络连接异常:')
    expect(formatted).toContain('ENOTFOUND')
    // Formatting is idempotent
    expect(formatEngineTurnError(formatted)).toBe(formatted)
  })

  it('releases turn lock on network failure and reopens dead session handle with resumeNativeId on immediate retry', async () => {
    const wsDir = makeTmpDir('lework-net-ws-')
    const homeDir = makeTmpDir('lework-net-home-')
    const events: AgentEvent[] = []
    const openCalls: Array<{ resumeNativeId?: string }> = []
    let turnAttempt = 0

    const flakyAdapter: EngineAdapter = {
      id: 'flaky',
      displayName: 'Flaky Test Engine',
      kind: 'cli',
      async probe(): Promise<EngineStatus> {
        return { installed: true, ready: true, version: '1.0.0' }
      },
      capabilities(): EngineCapabilities {
        return {
          permissions: true,
          images: false,
          modelSwitch: false,
          modes: false,
          history: true,
          cancel: true,
        }
      },
      async openSession(init: EngineSessionInit): Promise<EngineSession> {
        openCalls.push({ resumeNativeId: init.resumeNativeId })
        let alive = true
        const nativeId = init.resumeNativeId ?? 'native_conv_42'
        init.onNativeSessionLinked?.(nativeId)
        return {
          sessionId: init.sessionId,
          cwd: init.cwd,
          isAlive: () => alive,
          async prompt(turnId: string, text: string): Promise<void> {
            turnAttempt++
            if (turnAttempt === 1) {
              alive = false
              throw new TypeError('fetch failed', {
                cause: { code: 'ECONNRESET', message: 'socket hang up' },
              })
            }
            init.emit({
              type: 'message.assistant.delta',
              messageId: `msg_${turnId}`,
              text: `Resumed (${nativeId}) for: ${text}`,
            })
            init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
            init.emit({ type: 'session.state', state: 'idle' })
          },
          async cancel() {},
          async respondPermission() {},
          async close() {
            alive = false
          },
        }
      },
    }

    const manager = new SessionManager(
      [wsDir],
      [flakyAdapter],
      {
        onEvent: ev => events.push(ev),
        onSessionUpdated: () => {},
      },
      undefined,
      new HostTaskInspector({ homeDir }),
    )

    try {
      const meta = await manager.createSession({ engineId: 'flaky', cwd: wsDir })
      await manager.prompt(meta.id, '帮我实现断网续跑')

      await waitUntil(() => events.some(e => e.type === 'turn.done' && e.stopReason === 'error'))
      const errorEv = events.find(
        (e): e is Extract<AgentEvent, { type: 'error' }> => e.type === 'error',
      )
      expect(errorEv?.message).toContain('网络连接异常:')
      expect(errorEv?.message).toContain('ECONNRESET')
      // Session cleanly exited `running` so retry is NEVER blocked
      expect(manager.getSessionMeta(meta.id)?.state).toBe('idle')

      // Immediately retry on the exact same session -> reopens dead handle with resumeNativeId!
      await manager.prompt(meta.id, '网络已恢复，请继续完成上一轮任务')
      await waitUntil(() =>
        events.some(e => e.type === 'turn.done' && e.stopReason === 'end_turn'),
      )

      expect(openCalls).toHaveLength(2)
      expect(openCalls[1]?.resumeNativeId).toBe('native_conv_42')
      const reply = events
        .filter((e): e is Extract<AgentEvent, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
        .map(e => e.text)
        .join('')
      expect(reply).toContain('Resumed (native_conv_42)')
      expect(manager.getSessionMeta(meta.id)?.state).toBe('idle')
    } finally {
      await manager.closeAll()
    }
  })

  it('ClaudeCodeAdapter respawns and calls session/resume seamlessly after mid-turn network crash', async () => {
    const tmpDir = makeTmpDir('lework-claude-crash-')
    const markerFile = path.join(tmpDir, 'attempt.txt')
    const resumeLogFile = path.join(tmpDir, 'resumed.txt')
    const mockScript = path.join(tmpDir, 'crashing-claude-acp.mjs')
    fs.writeFileSync(
      mockScript,
      `
import fs from 'node:fs'
import readline from 'node:readline'

const markerFile = ${JSON.stringify(markerFile)}
const resumeLogFile = ${JSON.stringify(resumeLogFile)}
const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n') }

rl.on('line', line => {
  if (!line.trim()) return
  const msg = JSON.parse(line)
  if (msg.method === 'initialize') {
    send({ jsonrpc: '2.0', id: msg.id, result: { protocolVersion: 1 } })
  } else if (msg.method === 'session/new') {
    send({ jsonrpc: '2.0', id: msg.id, result: { sessionId: 'claude_native_99' } })
  } else if (msg.method === 'session/resume') {
    fs.writeFileSync(resumeLogFile, String(msg.params.sessionId || ''))
    send({ jsonrpc: '2.0', id: msg.id, result: { sessionId: msg.params.sessionId } })
  } else if (msg.method === 'session/prompt') {
    const attempt = fs.existsSync(markerFile) ? Number(fs.readFileSync(markerFile, 'utf8')) + 1 : 1
    fs.writeFileSync(markerFile, String(attempt))
    if (attempt === 1) {
      process.stderr.write('Error: read ECONNRESET while streaming response\\n')
      process.exit(1)
    }
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: msg.params.sessionId,
        update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'Claude resumed cleanly' } },
      },
    })
    send({ jsonrpc: '2.0', id: msg.id, result: { stopReason: 'end_turn' } })
  }
})
`,
      { mode: 0o755 },
    )

    const adapter = new ClaudeCodeAdapter({
      acpBinary: process.execPath,
      acpArgs: [mockScript],
    })
    const emitted: Array<Record<string, unknown>> = []
    const session = await adapter.openSession({
      sessionId: 'sess_claude_net',
      cwd: tmpDir,
      emit: ev => emitted.push(ev as Record<string, unknown>),
    })

    try {
      await session.prompt('t1', '第一轮任务')
      expect(
        emitted.some(
          e => e.type === 'error' && String(e.message).includes('网络连接异常') && String(e.message).includes('ECONNRESET'),
        ),
      ).toBe(true)
      expect(emitted.filter(e => e.type === 'session.state').at(-1)?.state).toBe('idle')
      expect(session.isAlive?.()).toBe(false)

      // Immediate retry respawns child & invokes session/resume with claude_native_99
      await session.prompt('t2', '网络已恢复，请继续')
      expect(fs.readFileSync(resumeLogFile, 'utf8')).toBe('claude_native_99')
      expect(
        emitted.some(
          e => e.type === 'message.assistant.delta' && String(e.text).includes('Claude resumed cleanly'),
        ),
      ).toBe(true)
      expect(emitted.filter(e => e.type === 'turn.done').at(-1)?.stopReason).toBe('end_turn')
    } finally {
      await session.close()
    }
  })

  it('CodexEngineAdapter rejects in-flight turn on child network crash instead of hanging and resumes thread on retry', async () => {
    const tmpDir = makeTmpDir('lework-codex-crash-')
    const markerFile = path.join(tmpDir, 'attempt.txt')
    const resumeLogFile = path.join(tmpDir, 'resumed.txt')
    const mockScript = path.join(tmpDir, 'crashing-codex.mjs')
    fs.writeFileSync(
      mockScript,
      `
import fs from 'node:fs'
import readline from 'node:readline'

const markerFile = ${JSON.stringify(markerFile)}
const resumeLogFile = ${JSON.stringify(resumeLogFile)}
const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
function send(obj) { process.stdout.write(JSON.stringify(obj) + '\\n') }

rl.on('line', line => {
  if (!line.trim()) return
  const msg = JSON.parse(line)
  if (msg.method === 'initialize') {
    send({ jsonrpc: '2.0', id: msg.id, result: {} })
  } else if (msg.method === 'thread/start') {
    send({ jsonrpc: '2.0', id: msg.id, result: { thread: { id: 'codex_thread_77' } } })
  } else if (msg.method === 'thread/resume') {
    fs.writeFileSync(resumeLogFile, String(msg.params.threadId || ''))
    send({ jsonrpc: '2.0', id: msg.id, result: { thread: { id: msg.params.threadId } } })
  } else if (msg.method === 'turn/start') {
    const attempt = fs.existsSync(markerFile) ? Number(fs.readFileSync(markerFile, 'utf8')) + 1 : 1
    fs.writeFileSync(markerFile, String(attempt))
    send({ jsonrpc: '2.0', id: msg.id, result: { turn: { id: 'turn_' + attempt } } })
    if (attempt === 1) {
      setTimeout(() => {
        process.stderr.write('connect ETIMEDOUT api.openai.com:443\\n')
        process.exit(1)
      }, 20)
    } else {
      send({ jsonrpc: '2.0', method: 'item/agentMessage/delta', params: { delta: 'Codex resumed cleanly' } })
      send({ jsonrpc: '2.0', method: 'turn/completed', params: { turn: { id: 'turn_' + attempt, status: 'completed' } } })
    }
  }
})
`,
      { mode: 0o755 },
    )

    const adapter = new CodexEngineAdapter({
      binary: process.execPath,
      args: [mockScript],
    })
    const emitted: Array<Record<string, unknown>> = []
    const session = await adapter.openSession({
      sessionId: 'sess_codex_net',
      cwd: tmpDir,
      emit: ev => emitted.push(ev as Record<string, unknown>),
    })

    try {
      await session.prompt('t1', '执行单测')
      expect(
        emitted.some(
          e => e.type === 'error' && String(e.message).includes('网络连接异常') && String(e.message).includes('ETIMEDOUT'),
        ),
      ).toBe(true)
      expect(emitted.filter(e => e.type === 'session.state').at(-1)?.state).toBe('idle')

      await session.prompt('t2', '网络恢复，继续执行')
      expect(fs.readFileSync(resumeLogFile, 'utf8')).toBe('codex_thread_77')
      expect(
        emitted.some(
          e => e.type === 'message.assistant.delta' && String(e.text).includes('Codex resumed cleanly'),
        ),
      ).toBe(true)
    } finally {
      await session.close()
    }
  })

  it('AgyEngineAdapter normalizes network crash and respawns with --conversation on retry', async () => {
    const tmpDir = makeTmpDir('lework-agy-crash-')
    const markerFile = path.join(tmpDir, 'attempt.txt')
    const argsLogFile = path.join(tmpDir, 'args.txt')
    const mockScript = path.join(tmpDir, 'crashing-agy.mjs')
    fs.writeFileSync(
      mockScript,
      `
import fs from 'node:fs'
import readline from 'node:readline'

const markerFile = ${JSON.stringify(markerFile)}
const argsLogFile = ${JSON.stringify(argsLogFile)}
const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })

rl.on('line', line => {
  if (!line.trim()) return
  const attempt = fs.existsSync(markerFile) ? Number(fs.readFileSync(markerFile, 'utf8')) + 1 : 1
  fs.writeFileSync(markerFile, String(attempt))
  fs.writeFileSync(argsLogFile, process.argv.slice(2).join(' '))
  process.stdout.write(JSON.stringify({ event: 'init', conversation_id: 'agy_conv_55' }) + '\\n')
  if (attempt === 1) {
    process.stderr.write('TypeError: fetch failed (getaddrinfo EAI_AGAIN)\\n')
    process.exit(1)
  }
  process.stdout.write(JSON.stringify({
    event: 'result',
    result: { conversation_id: 'agy_conv_55', response: 'AGY resumed cleanly', status: 'OK' },
  }) + '\\n')
})
`,
      { mode: 0o755 },
    )

    const adapter = new AgyEngineAdapter({
      binary: process.execPath,
      args: [mockScript],
    })
    const emitted: Array<Record<string, unknown>> = []
    const session = await adapter.openSession({
      sessionId: 'sess_agy_net',
      cwd: tmpDir,
      emit: ev => emitted.push(ev as Record<string, unknown>),
    })

    try {
      await session.prompt('t1', '分析日志')
      expect(
        emitted.some(
          e => e.type === 'error' && String(e.message).includes('网络连接异常') && String(e.message).includes('EAI_AGAIN'),
        ),
      ).toBe(true)
      expect(emitted.filter(e => e.type === 'session.state').at(-1)?.state).toBe('idle')

      await session.prompt('t2', '网络恢复，继续分析')
      expect(fs.readFileSync(argsLogFile, 'utf8')).toContain('--conversation agy_conv_55')
      expect(
        emitted.some(
          e => e.type === 'message.assistant.delta' && String(e.text).includes('AGY resumed cleanly'),
        ),
      ).toBe(true)
    } finally {
      await session.close()
    }
  })
})

async function waitUntil(predicate: () => boolean, timeoutMs = 4_000): Promise<void> {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    if (predicate()) return
    await new Promise(resolve => setTimeout(resolve, 15))
  }
  throw new Error('waitUntil timed out')
}
