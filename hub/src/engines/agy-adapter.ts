import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { Buffer } from 'node:buffer'
import crypto from 'node:crypto'
import type { EngineCapabilities, EngineStatus, ReasoningEffort, SessionMode } from '@lework/protocol'
import { augmentedPathEnv, findExecutable, isExecutableFile, probeCommandVersion } from '../platform/index.ts'
import { isExternalProviderId } from '../registry/provider-adapters.ts'
import { classifyRawToolCall } from '../storage/host-history.ts'
import {
  parseAgyModelString,
  readAgyLocalSettings,
  resolveAgyWireModel,
} from '../storage/host-history/engine-settings.ts'
import { isPlanStepToolName, parsePlanEntriesFromRaw } from '../storage/host-history/plan-steps.ts'
import { formatEngineTurnError } from './network-error.ts'
import type { EngineAdapter, EngineSession, EngineSessionInit, StagedPromptImage } from './types.ts'

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
    if (this.opts.binary) return isExecutableFile(this.opts.binary) ? this.opts.binary : null
    return findExecutable('agy')
  }

  async probe(): Promise<EngineStatus> {
    const bin = this.resolveBinary()
    const version = bin ? probeCommandVersion(bin, ['--version']) : undefined
    const installed = Boolean(bin)
    return {
      installed,
      ready: false,
      authStatus: installed ? 'unknown' : undefined,
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
    if (isExternalProviderId(init.providerId)) {
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
  private stderrTail = ''
  private conversationId: string | undefined
  private modelFlag: string | undefined
  private effortFlag: ReasoningEffort | undefined
  private activeMode: SessionMode | undefined
  private spawnedSpecKey: string | null = null
  private currentMsgId = `msg_${crypto.randomUUID()}`
  private emittedDeltaInTurn = false
  private emittedPlanStepKeys = new Set<string>()
  private pendingTurn: {
    turnId: string
    sawStreamActivity: boolean
    onActivity: () => void
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
    this.activeMode = init.mode
    this.conversationId = init.resumeNativeId
    if (this.conversationId) {
      this.init.onNativeSessionLinked?.(this.conversationId)
    }
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

  private resolveCurrentSpec(): {
    wireModel?: string
    wireEffort?: ReasoningEffort
    mode?: SessionMode
    key: string
  } {
    const { wireModel, wireEffort } = resolveAgyWireModel(this.modelFlag, this.effortFlag)
    const mode = this.activeMode
    return {
      wireModel,
      wireEffort,
      mode,
      key: `${wireModel ?? ''}|${wireEffort ?? ''}|${mode ?? 'default'}`,
    }
  }

  private async stopWorkerAndWait(): Promise<void> {
    const child = this.proc
    this.proc = null
    this.spawnedSpecKey = null
    if (!child || child.exitCode !== null) return

    await new Promise<void>(resolve => {
      let done = false
      const finish = () => {
        if (done) return
        done = true
        clearTimeout(killTimer)
        clearTimeout(maxTimer)
        resolve()
      }
      child.once('exit', finish)
      const killTimer = setTimeout(() => {
        try {
          child.kill('SIGKILL')
        } catch {
          // ignore
        }
      }, 1_000)
      killTimer.unref?.()
      const maxTimer = setTimeout(finish, 1_800)
      maxTimer.unref?.()

      try {
        child.stdin.end()
      } catch {
        // ignore
      }
      try {
        child.kill('SIGINT')
      } catch {
        finish()
      }
    })
  }

  private async spawnWorker(): Promise<void> {
    const spec = this.resolveCurrentSpec()
    const args = [...this.baseArgs]
    if (this.conversationId) {
      args.push('--conversation', this.conversationId)
    }
    if (spec.wireModel) {
      args.push('--model', spec.wireModel)
    }
    if (spec.wireEffort) {
      args.push('--effort', spec.wireEffort)
    }
    if (spec.mode === 'bypassPermissions') {
      args.push('--dangerously-skip-permissions')
    } else if (spec.mode === 'plan') {
      args.push('--mode', 'plan')
    } else if (spec.mode === 'acceptEdits') {
      args.push('--mode', 'accept-edits')
    } else if (spec.mode === 'auto') {
      args.push('--mode', 'auto')
    }
    const child = spawn(this.bin, args, {
      cwd: this.cwd,
      env: {
        ...augmentedPathEnv(process.env),
        ...this.init.env,
        TERM: 'dumb',
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    this.proc = child
    this.spawnedSpecKey = spec.key
    this.buf = Buffer.alloc(0)
    this.stderrTail = ''

    await new Promise<void>((resolve, reject) => {
      let settled = false
      // `agy --input-format stream-json` emits `init` only after reading the first NDJSON turn
      // on stdin; wait briefly solely to catch immediate binary/flag startup errors.
      const timer = setTimeout(() => {
        if (!settled) {
          settled = true
          resolve()
        }
      }, 150)
      timer.unref?.()

      child.stdout.on('data', (chunk: Buffer) => {
        this.consumeStdout(Buffer.from(chunk), ev => {
          if (ev.event === 'init' && !settled) {
            settled = true
            clearTimeout(timer)
            resolve()
          }
        })
      })

      child.stderr.on('data', (chunk: Buffer) => {
        const str = chunk.toString('utf8')
        this.stderrTail = (this.stderrTail + str).slice(-4_096)
        if (
          this.pendingTurn &&
          /(already open|already in use|failed to.*lock|conversation.*locked)/i.test(str)
        ) {
          const pending = this.pendingTurn
          this.pendingTurn = null
          pending.reject(
            new Error('该 AGY 会话正在电脑终端窗口中打开并占用锁，请在 tmux 或 iTerm2 中运行以双向同步'),
          )
        }
      })

      child.on('error', err => {
        if (!settled) {
          settled = true
          clearTimeout(timer)
          reject(err)
        } else if (this.pendingTurn) {
          const pending = this.pendingTurn
          this.pendingTurn = null
          pending.reject(err)
        }
      })

      child.on('exit', (code, signal) => {
        const fatalDetail = summarizeAgyStderr(this.stderrTail)
        if (!settled) {
          settled = true
          clearTimeout(timer)
          reject(
            new Error(
              fatalDetail || `agy exited before init (code=${code ?? 'null'}, signal=${signal ?? 'null'})`,
            ),
          )
          return
        }
        if (this.pendingTurn) {
          const pending = this.pendingTurn
          this.pendingTurn = null
          if (!this.closed && code !== 0) {
            pending.reject(
              new Error(
                fatalDetail || `AGY 进程异常结束 (code=${code ?? 'null'}, signal=${signal ?? 'null'})`,
              ),
            )
          } else {
            pending.resolve()
          }
        }
      })
    })
  }

  isAlive(): boolean {
    return Boolean(
      !this.closed &&
        this.proc &&
        this.proc.exitCode === null &&
        !this.proc.killed &&
        !this.proc.stdin.destroyed &&
        this.proc.stdin.writable,
    )
  }

  async prompt(turnId: string, text: string, images?: StagedPromptImage[]): Promise<void> {
    this.currentMsgId = `msg_${turnId}`
    this.emittedDeltaInTurn = false
    this.emittedPlanStepKeys.clear()
    this.init.emit({ type: 'session.state', state: 'running' })

    const imgHeader =
      images && images.length > 0
        ? `[用户附加了本地图片文件，请使用 read_file 查看：${images.map(i => i.stagedPath).join(', ')}]\n\n`
        : ''
    const effectiveContent = `${imgHeader}${text.trim() || '请结合所附图片进行分析。'}`

    try {
      const desiredKey = this.resolveCurrentSpec().key
      if (!this.isAlive() || this.spawnedSpecKey !== desiredKey) {
        await this.stopWorkerAndWait()
        await this.spawnWorker()
      }
      if (!this.proc) throw new Error('AGY stream session is not running')
      this.stderrTail = ''

      await new Promise<void>((resolve, reject) => {
        // Guard against presence-lock deadlocks where `agy --conversation <id>` blocks before emitting `init`.
        // Cleared immediately upon the first `init` / `step_update` / `result` event so long coding turns never time out!
        const initGuardTimer = setTimeout(() => {
          if (this.pendingTurn && this.pendingTurn.turnId === turnId && !this.pendingTurn.sawStreamActivity) {
            const pending = this.pendingTurn
            this.pendingTurn = null
            if (this.proc && !this.proc.killed) {
              try {
                this.proc.kill('SIGINT')
              } catch {
                // ignore
              }
              this.proc = null
            }
            const stderrHint = summarizeAgyStderr(this.stderrTail)
            pending.reject(
              new Error(
                stderrHint ||
                  'AGY 会话可能已在电脑其他终端窗口打开并持有锁，请在对应终端继续或新建会话。',
              ),
            )
          }
        }, 20_000)
        initGuardTimer.unref?.()

        this.pendingTurn = {
          turnId,
          sawStreamActivity: false,
          onActivity: () => {
            if (this.pendingTurn && !this.pendingTurn.sawStreamActivity) {
              this.pendingTurn.sawStreamActivity = true
              clearTimeout(initGuardTimer)
            }
          },
          resolve: () => {
            clearTimeout(initGuardTimer)
            resolve()
          },
          reject: err => {
            clearTimeout(initGuardTimer)
            reject(err)
          },
        }
        const payload = JSON.stringify({
          event: 'user',
          message: { content: effectiveContent },
        })
        this.proc!.stdin.write(payload + '\n')
      })
      this.init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
      this.init.emit({ type: 'session.state', state: 'idle' })
    } catch (err) {
      this.pendingTurn = null
      const msg = formatEngineTurnError(err, this.stderrTail)
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
    const parsed = parseAgyModelString(modelId)
    this.modelFlag = parsed.modelId ?? modelId
    if (parsed.effort) {
      this.effortFlag = parsed.effort
    }
    if (!this.pendingTurn && this.proc && this.spawnedSpecKey !== this.resolveCurrentSpec().key) {
      await this.stopWorkerAndWait()
    }
  }

  async setEffort(effort: ReasoningEffort): Promise<void> {
    this.effortFlag = effort
    if (!this.pendingTurn && this.proc && this.spawnedSpecKey !== this.resolveCurrentSpec().key) {
      await this.stopWorkerAndWait()
    }
  }

  async setMode(mode: SessionMode): Promise<void> {
    this.activeMode = mode
    if (!this.pendingTurn && this.proc && this.spawnedSpecKey !== this.resolveCurrentSpec().key) {
      await this.stopWorkerAndWait()
    }
  }

  async close(): Promise<void> {
    this.closed = true
    if (this.pendingTurn) {
      const pending = this.pendingTurn
      this.pendingTurn = null
      pending.resolve()
    }
    await this.stopWorkerAndWait()
    this.init.emit({ type: 'session.state', state: 'idle' })
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
    this.pendingTurn?.onActivity()
    const eventName = event.event
    if (eventName === 'init') {
      if (typeof event.conversation_id === 'string' && event.conversation_id) {
        this.conversationId = event.conversation_id
        this.init.onNativeSessionLinked?.(event.conversation_id)
      }
      return
    }
    if (eventName === 'step_update' && event.step_update && typeof event.step_update === 'object') {
      const step = event.step_update as Record<string, unknown>
      if (typeof step.conversation_id === 'string' && step.conversation_id && !this.conversationId) {
        this.conversationId = step.conversation_id
        this.init.onNativeSessionLinked?.(step.conversation_id)
      }
      const stepType = step.step_type
      if (stepType === 'agent_response' && typeof step.text_delta === 'string') {
        if (step.text_delta.length > 0) this.emittedDeltaInTurn = true
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
      } else if (stepType === 'plan' || stepType === 'steps') {
        const entries = parsePlanEntriesFromRaw(step.steps ?? step.entries ?? step.plan)
        if (entries.length > 0) {
          this.init.emit({ type: 'plan.updated', entries })
        }
      } else if (stepType === 'tool') {
        const toolInfo = (step.tool_info as Record<string, unknown>) ?? {}
        const toolName =
          typeof step.tool_name === 'string' ? step.tool_name : String(toolInfo.name ?? 'tool')
        const stepKey = String(step.step_index ?? toolName)
        if (isPlanStepToolName(toolName) === 'update_steps') {
          const rawSteps =
            toolInfo.parameters ?? step.tool_input ?? step.args ?? toolInfo.args ?? toolInfo.input
          const entries = parsePlanEntriesFromRaw(rawSteps)
          if (entries.length > 0 && !this.emittedPlanStepKeys.has(stepKey)) {
            this.emittedPlanStepKeys.add(stepKey)
            this.init.emit({ type: 'plan.updated', entries })
          }
          return
        }
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
      if (typeof res.conversation_id === 'string' && res.conversation_id) {
        this.conversationId = res.conversation_id
        this.init.onNativeSessionLinked?.(res.conversation_id)
      }
      if (!this.emittedDeltaInTurn && typeof res.response === 'string' && res.response.length > 0) {
        this.init.emit({
          type: 'message.assistant.delta',
          messageId: this.currentMsgId,
          text: res.response,
        })
      }
      if (res.usage && typeof res.usage === 'object') {
        const u = res.usage as Record<string, unknown>
        const inputTokens = typeof u.input_tokens === 'number' ? u.input_tokens : undefined
        const outputTokens = typeof u.output_tokens === 'number' ? u.output_tokens : undefined
        if (inputTokens !== undefined || outputTokens !== undefined) {
          this.init.emit({ type: 'usage', inputTokens, outputTokens })
        }
      }
      if (this.pendingTurn) {
        const pending = this.pendingTurn
        this.pendingTurn = null
        if (res.status === 'ERROR' || res.stop_reason === 'error') {
          const detail =
            typeof res.error === 'string' && res.error.trim()
              ? res.error.trim()
              : summarizeAgyStderr(this.stderrTail) || 'AGY execution failed'
          pending.reject(new Error(detail))
        } else {
          pending.resolve()
        }
      }
    }
  }
}

function summarizeAgyStderr(raw: string): string {
  const lines = raw
    .split(/\r?\n/)
    .map(l => l.trim())
    .filter(l => l.length > 0 && !/^warning:/i.test(l))
  return lines.slice(-4).join('\n').slice(0, 500)
}
