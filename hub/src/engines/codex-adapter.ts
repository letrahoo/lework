import crypto from 'node:crypto'
import type { EngineCapabilities, EngineStatus, PermissionOption } from '@lework/protocol'
import { findExecutable, probeCommandVersion } from '../platform/index.ts'
import { StdioJsonRpcProcess, type JsonRpcInbound } from './acp-jsonrpc.ts'
import type { EngineAdapter, EngineSession, EngineSessionInit } from './types.ts'

export interface CodexAdapterOptions {
  binary?: string
  args?: string[]
}

interface PendingCodexApproval {
  upstreamId: string | number
  options: PermissionOption[]
}

export class CodexEngineAdapter implements EngineAdapter {
  readonly id = 'codex'
  readonly displayName = 'OpenAI Codex'
  readonly kind = 'server' as const

  constructor(private readonly opts: CodexAdapterOptions = {}) {}

  private resolveBinary(): string | null {
    if (this.opts.binary) return this.opts.binary
    return findExecutable('codex')
  }

  async probe(): Promise<EngineStatus> {
    const bin = this.resolveBinary()
    const version = bin ? probeCommandVersion(bin, ['--version']) : undefined
    const installed = Boolean(bin)
    return {
      installed,
      ready: installed,
      version,
      detail: installed ? `${bin} app-server (JSON-RPC stdio)` : 'Missing codex CLI in PATH',
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
    const bin = this.resolveBinary()
    if (!bin) throw new Error('codex binary is not available on this host')
    const session = new CodexAppServerSession(
      init,
      bin,
      this.opts.args ?? ['app-server'],
    )
    await session.start()
    return session
  }
}

class CodexAppServerSession implements EngineSession {
  readonly sessionId: string
  readonly cwd: string
  private rpc: StdioJsonRpcProcess | null = null
  private threadId: string | null = null
  private activeTurnId: string | null = null
  private selectedModel: string | undefined
  private selectedEffort: string | undefined
  private currentMsgId = `msg_${crypto.randomUUID()}`
  private readonly seenTools = new Set<string>()
  private readonly pendingApprovals = new Map<string, PendingCodexApproval>()
  private turnWaiter: {
    resolve: () => void
    reject: (err: Error) => void
  } | null = null
  private closed = false

  constructor(
    private readonly init: EngineSessionInit,
    private readonly bin: string,
    private readonly args: string[],
  ) {
    this.sessionId = init.sessionId
    this.cwd = init.cwd
    this.selectedModel = init.modelId
    this.selectedEffort = init.reasoningEffort
  }

  async start(): Promise<void> {
    this.rpc = new StdioJsonRpcProcess({
      command: this.bin,
      args: this.args,
      cwd: this.cwd,
      env: { ...process.env, ...this.init.env },
      onInbound: msg => this.handleInbound(msg),
      onExit: (code, signal) => {
        if (!this.closed) {
          this.init.emit({
            type: 'session.state',
            state: 'error',
            detail: `Codex app-server exited (code=${code ?? 'null'}, signal=${signal ?? 'null'})`,
          })
        }
      },
    })
    this.rpc.start()

    await this.rpc.request(
      'initialize',
      {
        clientInfo: {
          name: 'lework',
          title: 'Lework',
          version: '0.1.0',
        },
        capabilities: {
          experimentalApi: true,
          mcpServerOpenaiFormElicitation: false,
        },
      },
      15_000,
    )
    this.rpc.notify('initialized', {})

    let threadRes: Record<string, unknown> | null = null
    if (this.init.resumeNativeId) {
      try {
        threadRes = (await this.rpc.request(
          'thread/resume',
          {
            threadId: this.init.resumeNativeId,
            cwd: this.cwd,
            ...(this.selectedModel ? { model: this.selectedModel } : {}),
          },
          20_000,
        )) as Record<string, unknown> | null
      } catch {
        threadRes = null
      }
    }

    if (!threadRes) {
      threadRes = (await this.rpc.request(
        'thread/start',
        {
          cwd: this.cwd,
          ...(this.selectedModel ? { model: this.selectedModel } : {}),
        },
        20_000,
      )) as Record<string, unknown> | null
    }

    const threadObj =
      threadRes?.thread && typeof threadRes.thread === 'object'
        ? (threadRes.thread as Record<string, unknown>)
        : threadRes
    const tid =
      typeof threadObj?.id === 'string'
        ? threadObj.id
        : typeof threadRes?.threadId === 'string'
          ? threadRes.threadId
          : this.init.resumeNativeId ?? null
    if (!tid) {
      await this.close()
      throw new Error('Codex thread/start did not return a thread id')
    }
    this.threadId = tid

    this.init.onModelsChanged?.(this.selectedModel ?? 'gpt-5-codex', [
      { id: 'gpt-5-codex', name: 'GPT-5 Codex' },
      { id: 'o3', name: 'OpenAI o3' },
      { id: 'gpt-5', name: 'GPT-5' },
    ])
  }

  async prompt(turnId: string, text: string): Promise<void> {
    if (!this.rpc || !this.threadId) {
      throw new Error('Codex session is not initialized')
    }
    this.currentMsgId = `msg_${turnId}`
    this.init.emit({ type: 'session.state', state: 'running' })

    const completionPromise = new Promise<void>((resolve, reject) => {
      this.turnWaiter = { resolve, reject }
    })

    try {
      const startRes = (await this.rpc.request(
        'turn/start',
        {
          threadId: this.threadId,
          input: [{ type: 'text', text }],
          ...(this.selectedModel ? { model: this.selectedModel } : {}),
          ...(this.selectedEffort ? { effort: this.selectedEffort } : {}),
        },
        60_000,
      )) as Record<string, unknown> | null

      const turnObj =
        startRes?.turn && typeof startRes.turn === 'object'
          ? (startRes.turn as Record<string, unknown>)
          : startRes
      if (typeof turnObj?.id === 'string') {
        this.activeTurnId = turnObj.id
      }

      await completionPromise
      this.init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
      this.init.emit({ type: 'session.state', state: 'idle' })
    } catch (err) {
      this.turnWaiter = null
      const msg = err instanceof Error ? err.message : String(err)
      this.init.emit({ type: 'error', message: msg })
      this.init.emit({ type: 'turn.done', turnId, stopReason: 'error', error: msg })
      this.init.emit({ type: 'session.state', state: 'idle' })
    }
  }

  async cancel(): Promise<void> {
    if (this.rpc && this.threadId && this.activeTurnId) {
      try {
        await this.rpc.request(
          'turn/interrupt',
          { threadId: this.threadId, turnId: this.activeTurnId },
          5_000,
        )
      } catch {
        // ignore
      }
    }
    if (this.turnWaiter) {
      const w = this.turnWaiter
      this.turnWaiter = null
      w.resolve()
    }
  }

  async respondPermission(requestId: string, optionId: string | null): Promise<void> {
    const pending = this.pendingApprovals.get(requestId)
    if (!pending || !this.rpc) {
      throw new Error(`No pending Codex approval with id "${requestId}"`)
    }
    this.pendingApprovals.delete(requestId)
    const approved = optionId === 'allow'
    this.rpc.respond(pending.upstreamId, {
      decision: approved ? 'approved' : 'denied',
    })
    this.init.emit({
      type: 'permission.resolved',
      requestId,
      optionId,
      outcome: optionId === null ? 'cancelled' : 'selected',
    })
    if (this.pendingApprovals.size === 0) {
      this.init.emit({ type: 'session.state', state: 'running' })
    }
  }

  async setModel(modelId: string): Promise<void> {
    this.selectedModel = modelId
  }

  async close(): Promise<void> {
    this.closed = true
    this.turnWaiter?.resolve()
    this.turnWaiter = null
    await this.rpc?.close()
    this.rpc = null
  }

  private handleInbound(msg: JsonRpcInbound): void {
    const { method, params } = msg
    if (method === 'item/agentMessage/delta' && typeof params.delta === 'string') {
      this.init.emit({
        type: 'message.assistant.delta',
        messageId: this.currentMsgId,
        text: params.delta,
      })
      return
    }

    if (
      (method === 'item/reasoning/summaryTextDelta' || method === 'item/reasoning/textDelta') &&
      typeof params.delta === 'string'
    ) {
      this.init.emit({
        type: 'reasoning.delta',
        messageId: `${this.currentMsgId}_think`,
        text: params.delta,
      })
      return
    }

    if (
      method === 'item/commandExecution/outputDelta' ||
      method === 'item/fileChange/outputDelta'
    ) {
      const itemId = String(params.itemId ?? 'codex_tool')
      const delta = typeof params.delta === 'string' ? params.delta : ''
      if (!this.seenTools.has(itemId)) {
        this.seenTools.add(itemId)
        this.init.emit({
          type: 'tool.call',
          toolCallId: itemId,
          title: method.includes('fileChange') ? 'Codex File Patch' : 'Codex Command',
          kind: method.includes('fileChange') ? 'edit' : 'execute',
          status: 'in_progress',
        })
      }
      if (delta) {
        this.init.emit({
          type: 'tool.update',
          toolCallId: itemId,
          status: 'in_progress',
          output: delta,
        })
      }
      return
    }

    if (msg.kind === 'request' && (method === 'item/commandExecution/requestApproval' || method === 'item/fileChange/requestApproval')) {
      const requestId = `perm_${crypto.randomUUID()}`
      const cmd = Array.isArray(params.command)
        ? params.command.join(' ')
        : typeof params.command === 'string'
          ? params.command
          : method
      const options: PermissionOption[] = [
        { id: 'allow', name: '允许一次 (Approve)', kind: 'allow_once' },
        { id: 'reject', name: '拒绝 (Deny)', kind: 'reject_once' },
      ]
      this.pendingApprovals.set(requestId, { upstreamId: msg.id, options })
      this.init.emit({ type: 'session.state', state: 'waiting_permission', detail: cmd })
      this.init.emit({
        type: 'permission.requested',
        requestId,
        title: `Codex 请求执行: ${cmd}`,
        options,
      })
      return
    }

    if (method === 'turn/completed') {
      const w = this.turnWaiter
      this.turnWaiter = null
      w?.resolve()
    }
  }
}
