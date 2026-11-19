import type { EngineCapabilities, EngineStatus } from '@qprc/protocol'
import type { EngineAdapter, EngineSession, EngineSessionInit } from './types.ts'

/**
 * Deterministic diagnostic engine (`mock`) that exercises the full AgentEvent
 * pipeline (reasoning -> tool call -> permission request -> tool output ->
 * streaming assistant text -> turn.done) without requiring external network access.
 *
 * If the prompt contains `[approve]` or `exec` or `写` or `run`, it pauses for
 * interactive user approval before completing the tool call.
 */
export class DiagnosticEchoAdapter implements EngineAdapter {
  readonly id = 'mock'
  readonly displayName = 'Diagnostic Simulator'
  readonly kind = 'embedded' as const

  async probe(): Promise<EngineStatus> {
    return {
      installed: true,
      ready: true,
      version: '0.1.0-m0',
      detail: 'Built-in deterministic engine for E2E & approval flow verification',
    }
  }

  capabilities(): EngineCapabilities {
    return {
      permissions: true,
      images: false,
      modelSwitch: true,
      modes: true,
      history: true,
      cancel: true,
    }
  }

  async openSession(init: EngineSessionInit): Promise<EngineSession> {
    init.onModelsChanged?.('mock-fast', [
      { id: 'mock-fast', name: 'Simulator Fast' },
      { id: 'mock-ApprovalTest', name: 'Simulator Approval-Strict' },
    ])
    return new DiagnosticSession(init)
  }
}

class DiagnosticSession implements EngineSession {
  readonly sessionId: string
  readonly cwd: string
  private pendingApprovalResolve: ((optionId: string | null) => void) | null = null
  private pendingRequestId: string | null = null
  private cancelled = false

  constructor(private readonly init: EngineSessionInit) {
    this.sessionId = init.sessionId
    this.cwd = init.cwd
  }

  async prompt(turnId: string, text: string): Promise<void> {
    this.cancelled = false
    const msgId = `msg_${turnId}`
    this.init.emit({ type: 'session.state', state: 'running' })
    this.init.emit({
      type: 'reasoning.delta',
      messageId: `${msgId}_think`,
      text: `Analyzing request in ${this.cwd}...`,
    })

    const needsApproval = /approve|permission|exec|bash|write|审批|执行/i.test(text)
    if (needsApproval) {
      const toolCallId = `tc_${turnId}`
      const requestId = `perm_${turnId}`
      this.init.emit({
        type: 'tool.call',
        toolCallId,
        title: `Bash: echo "${text.slice(0, 40)}"`,
        kind: 'execute',
        status: 'pending',
        input: text,
      })
      this.init.emit({
        type: 'session.state',
        state: 'waiting_permission',
        detail: `Approve command in ${this.cwd}?`,
      })
      this.init.emit({
        type: 'permission.requested',
        requestId,
        toolCallId,
        title: `Allow executing command in ${this.cwd}?`,
        options: [
          { id: 'allow', name: '允许一次 (Allow once)', kind: 'allow_once' },
          { id: 'reject', name: '拒绝 (Reject)', kind: 'reject_once' },
        ],
      })

      this.pendingRequestId = requestId
      const chosen = await new Promise<string | null>(resolve => {
        this.pendingApprovalResolve = resolve
      })
      this.pendingApprovalResolve = null
      this.pendingRequestId = null

      if (this.cancelled || chosen === null) {
        this.init.emit({ type: 'turn.done', turnId, stopReason: 'cancelled' })
        this.init.emit({ type: 'session.state', state: 'idle' })
        return
      }

      if (chosen === 'reject') {
        this.init.emit({
          type: 'tool.update',
          toolCallId,
          status: 'failed',
          output: 'Rejected by user on mobile client.',
        })
        this.init.emit({
          type: 'message.assistant.delta',
          messageId: msgId,
          text: '命令执行已被拒绝，未对工作区做任何修改。',
        })
        this.init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
        this.init.emit({ type: 'session.state', state: 'idle' })
        return
      }

      this.init.emit({
        type: 'tool.update',
        toolCallId,
        status: 'completed',
        output: `Executed in ${this.cwd}: OK`,
      })
    }

    const chunks = [`收到你的指令：「${text}」。\n`, `当前工作区：\`${this.cwd}\`。\n`, '流式响应与事件序号续传正常。']
    for (const chunk of chunks) {
      if (this.cancelled) break
      this.init.emit({
        type: 'message.assistant.delta',
        messageId: msgId,
        text: chunk,
      })
      await new Promise(r => setTimeout(r, 15))
    }

    this.init.emit({ type: 'usage', inputTokens: text.length, outputTokens: 32 })
    this.init.emit({
      type: 'turn.done',
      turnId,
      stopReason: this.cancelled ? 'cancelled' : 'end_turn',
    })
    this.init.emit({ type: 'session.state', state: 'idle' })
  }

  async cancel(): Promise<void> {
    this.cancelled = true
    if (this.pendingApprovalResolve && this.pendingRequestId) {
      const reqId = this.pendingRequestId
      const resolve = this.pendingApprovalResolve
      this.pendingApprovalResolve = null
      this.pendingRequestId = null
      this.init.emit({
        type: 'permission.resolved',
        requestId: reqId,
        optionId: null,
        outcome: 'cancelled',
      })
      resolve(null)
    }
  }

  async respondPermission(requestId: string, optionId: string | null): Promise<void> {
    if (this.pendingRequestId !== requestId || !this.pendingApprovalResolve) {
      throw new Error(`No pending permission request with id "${requestId}"`)
    }
    const resolve = this.pendingApprovalResolve
    this.pendingApprovalResolve = null
    this.pendingRequestId = null
    this.init.emit({
      type: 'permission.resolved',
      requestId,
      optionId,
      outcome: optionId === null ? 'cancelled' : 'selected',
    })
    this.init.emit({ type: 'session.state', state: 'running' })
    resolve(optionId)
  }

  async close(): Promise<void> {
    await this.cancel()
  }
}
