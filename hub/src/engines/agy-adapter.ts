import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { Buffer } from 'node:buffer'
import crypto from 'node:crypto'
import type { EngineCapabilities, EngineStatus } from '@lework/protocol'
import { augmentedPathEnv, findExecutable, probeCommandVersion } from '../platform/index.ts'
import { classifyRawToolCall } from '../storage/host-history.ts'
import { readAgyLocalSettings } from '../storage/host-history/engine-settings.ts'
import type { EngineAdapter, EngineSession, EngineSessionInit } from './types.ts'

export interface AgyAdapterOptions {
  binary?: string
  args?: string[]
}

/**
 * Google Antigravity (`agy`) CLI adapter using `--input-format stream-json --output-format stream-json`.
 * Compliance guardrail (§4.3): Only uses the host's official `agy` login; never injects API proxy endpoints.
 */
export class AgyEngineAdapter implements EngineAdapter {
  readonly id = 'agy'
  readonly displayName = 'Google Antigravity (AGY)'
  readonly kind = 'cli' as const

  constructor(private readonly opts: AgyAdapterOptions = {}) {}

  private resolveBinary(): string | null {
    if (this.opts.binary) return this.opts.binary
    return findExecutable('agy')
  }

  async probe(): Promise<EngineStatus> {
    const bin = this.resolveBinary()
    const version = bin ? probeCommandVersion(bin, ['--version']) : undefined
    const installed = Boolean(bin)
    return {
      installed,
      ready: installed,
      version,
      detail: installed
        ? `${bin} (stream-json · 仅官方订阅登录)`
        : 'Missing agy CLI in PATH',
    }
  }

  capabilities(): EngineCapabilities {
    return {
      permissions: true,
      images: true,
      modelSwitch: true,
      modes: true,
      history: true,
      cancel: true,
    }
  }

  async openSession(init: EngineSessionInit): Promise<EngineSession> {
    if (init.providerId === 'cpa_local') {
      throw new Error('AGY 禁止使用 API 反代，请使用 AGY 官方订阅登录模式。')
    }
    const bin = this.resolveBinary()
    if (!bin) throw new Error('agy binary is not available on this host')
    const session = new AgyStreamSession(
      init,
      bin,
      this.opts.args ?? ['--input-format', 'stream-json', '--output-format', 'stream-json'],
    )
    await session.start()
    return session
  }
}

class AgyStreamSession implements EngineSession {
  readonly sessionId: string
  readonly cwd: string
  private proc: ChildProcessWithoutNullStreams | null = null
  private buf: Buffer = Buffer.alloc(0)
  private conversationId: string | undefined
  private modelFlag: string | undefined
  private effortFlag: string | undefined
  private currentMsgId = `msg_${crypto.randomUUID()}`
  private pendingTurn: {
    turnId: string
    resolve: () => void
    reject: (err: Error) => void
  } | null = null
  private closed = false

  constructor(
    private readonly init: EngineSessionInit,
    private readonly bin: string,
    private readonly baseArgs: string[],
  ) {
    this.sessionId = init.sessionId
    this.cwd = init.cwd
    this.modelFlag = init.modelId
    this.effortFlag = init.reasoningEffort
    this.conversationId = init.resumeNativeId
  }

  async start(): Promise<void> {
    const hostAgy = readAgyLocalSettings()
    if (!this.modelFlag && hostAgy.model) {
      this.modelFlag = hostAgy.model
    }
    if (!this.effortFlag && hostAgy.effort) {
      this.effortFlag = hostAgy.effort
    }
    if (this.modelFlag) {
      this.init.onModelsChanged?.(this.modelFlag, [])
    }
    await this.spawnWorker()
  }

  private async spawnWorker(): Promise<void> {
    const args = [...this.baseArgs]
    if (this.conversationId) {
      args.push('--conversation', this.conversationId)
    }
    if (this.modelFlag) {
      const wireModel =
        this.effortFlag && !this.modelFlag.endsWith(`-${this.effortFlag}`) && !this.modelFlag.includes('orchestrator')
          ? `${this.modelFlag}-${this.effortFlag}`
          : this.modelFlag
      args.push('--model', wireModel)
    }
    if (this.effortFlag) {
      args.push('--effort', this.effortFlag)
    }
    const child = spawn(this.bin, args, {
      cwd: this.cwd,
      env: augmentedPathEnv(process.env),
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    this.proc = child
    this.buf = Buffer.alloc(0)

    await new Promise<void>((resolve, reject) => {
      let initialized = false
      const timer = setTimeout(() => {
        if (!initialized) {
          initialized = true
          // Some agy builds wait for first user input before emitting init; allow proceeding
          resolve()
        }
      }, 4_000)
      timer.unref?.()

      child.stdout.on('data', (chunk: Buffer) => {
        this.consumeStdout(Buffer.from(chunk), ev => {
          if (ev.event === 'init' && !initialized) {
            initialized = true
            clearTimeout(timer)
            if (typeof ev.conversation_id === 'string') {
              this.conversationId = ev.conversation_id
            }
            resolve()
          }
        })
      })

      child.stderr.on('data', () => {
        // drain stderr
      })

      child.on('error', err => {
        if (!initialized) {
          initialized = true
          clearTimeout(timer)
          reject(err)
        }
      })

      child.on('exit', code => {
        if (!initialized) {
          initialized = true
          clearTimeout(timer)
          reject(new Error(`agy exited before init (code=${code ?? 'null'})`))
        }
        if (this.pendingTurn) {
          const pending = this.pendingTurn
          this.pendingTurn = null
          pending.resolve()
        }
      })
    })
  }

  async prompt(turnId: string, text: string): Promise<void> {
    if (!this.proc || this.proc.exitCode !== null || this.proc.killed) {
      await this.spawnWorker()
    }
    if (!this.proc) throw new Error('AGY stream session is not running')

    this.currentMsgId = `msg_${turnId}`
    this.init.emit({ type: 'session.state', state: 'running' })

    try {
      await new Promise<void>((resolve, reject) => {
        this.pendingTurn = { turnId, resolve, reject }
        const payload = JSON.stringify({
          event: 'user',
          message: { content: text },
        })
        this.proc!.stdin.write(payload + '\n')
      })
      this.init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
      this.init.emit({ type: 'session.state', state: 'idle' })
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      this.init.emit({ type: 'error', message: msg })
      this.init.emit({ type: 'turn.done', turnId, stopReason: 'error', error: msg })
      this.init.emit({ type: 'session.state', state: 'idle' })
    }
  }

  async cancel(): Promise<void> {
    if (this.pendingTurn) {
      const pending = this.pendingTurn
      this.pendingTurn = null
      pending.resolve()
    }
    if (this.proc && !this.proc.killed) {
      try {
        this.proc.kill('SIGINT')
      } catch {
        // ignore
      }
      this.proc = null
    }
    this.init.emit({ type: 'session.state', state: 'idle' })
  }

  async respondPermission(_requestId: string, _optionId: string | null): Promise<void> {
    // AGY stream-json permissions are managed via AGY session flags
  }

  async setModel(modelId: string): Promise<void> {
    this.modelFlag = modelId
  }

  async close(): Promise<void> {
    this.closed = true
    await this.cancel()
  }

  private consumeStdout(chunk: Buffer, onEvent?: (ev: Record<string, unknown>) => void): void {
    this.buf = this.buf.length === 0 ? chunk : Buffer.concat([this.buf, chunk])
    let nl = this.buf.indexOf(0x0a)
    while (nl >= 0) {
      const line = this.buf.subarray(0, nl).toString('utf8').trim()
      this.buf = this.buf.subarray(nl + 1)
      if (line) {
        try {
          const parsed = JSON.parse(line) as Record<string, unknown>
          onEvent?.(parsed)
          this.handleAgyEvent(parsed)
        } catch {
          // ignore non-json lines
        }
      }
      nl = this.buf.indexOf(0x0a)
    }
  }

  private handleAgyEvent(event: Record<string, unknown>): void {
    const eventName = event.event
    if (eventName === 'step_update' && event.step_update && typeof event.step_update === 'object') {
      const step = event.step_update as Record<string, unknown>
      const stepType = step.step_type
      if (stepType === 'agent_response' && typeof step.text_delta === 'string') {
        this.init.emit({
          type: 'message.assistant.delta',
          messageId: this.currentMsgId,
          text: step.text_delta,
        })
      } else if (stepType === 'thought' || stepType === 'reasoning') {
        const text =
          typeof step.text_delta === 'string'
            ? step.text_delta
            : typeof step.thought === 'string'
              ? step.thought
              : typeof step.reasoning === 'string'
                ? step.reasoning
                : undefined
        if (text) {
          this.init.emit({
            type: 'reasoning.delta',
            messageId: `${this.currentMsgId}_think`,
            text,
          })
        }
      } else if (stepType === 'tool') {
        const toolInfo = (step.tool_info as Record<string, unknown>) ?? {}
        const toolName =
          typeof step.tool_name === 'string' ? step.tool_name : String(toolInfo.name ?? 'tool')
        const callId = `agy_tc_${step.step_index ?? Date.now()}`
        if (step.state === 'ACTIVE') {
          const rawInput = toolInfo.parameters ? JSON.stringify(toolInfo.parameters) : undefined
          const classified = classifyRawToolCall(toolName, rawInput)
          this.init.emit({
            type: 'tool.call',
            toolCallId: callId,
            title: classified.title,
            kind: classified.kind,
            status: 'in_progress',
            input: classified.input,
          })
        } else if (step.state === 'DONE') {
          this.init.emit({
            type: 'tool.update',
            toolCallId: callId,
            status: step.status === 'ERROR' ? 'failed' : 'completed',
            output:
              typeof toolInfo.output === 'string'
                ? toolInfo.output
                : JSON.stringify(toolInfo.output ?? ''),
          })
        }
      }
      return
    }

    if (eventName === 'result' && event.result && typeof event.result === 'object') {
      const res = event.result as Record<string, unknown>
      if (typeof res.response === 'string' && res.response.length > 0) {
        this.init.emit({
          type: 'message.assistant.delta',
          messageId: this.currentMsgId,
          text: res.response,
        })
      }
      if (this.pendingTurn) {
        const pending = this.pendingTurn
        this.pendingTurn = null
        if (res.status === 'ERROR') {
          pending.reject(new Error(String(res.error ?? 'AGY execution failed')))
        } else {
          pending.resolve()
        }
      }
    }
  }
}
