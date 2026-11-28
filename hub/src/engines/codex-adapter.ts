import { readCodexLocalSettings } from '../storage/host-history/engine-settings.ts'
import { probeCliAuth } from './cli-auth-status.ts'
import crypto from 'node:crypto'
import type { EngineCapabilities, EngineStatus, PermissionOption, SessionMode } from '@lework/protocol'
import { findExecutable, isExecutableFile, probeCommandVersion } from '../platform/index.ts'
import { isExternalProviderId, mergeSessionEnv } from '../registry/provider-adapters.ts'
import { buildPromptTextWithAttachments } from '../storage/attachment-store.ts'
import { StdioJsonRpcProcess, type JsonRpcInbound } from './acp-jsonrpc.ts'
import { formatEngineTurnError } from './network-error.ts'
import type {
  EngineAdapter,
  EngineSession,
  EngineSessionInit,
  StagedPromptFile,
  StagedPromptImage,
} from './types.ts'

export function buildCodexLaunchArgs(
  baseArgs: string[],
  init: Pick<EngineSessionInit, 'providerId' | 'env'>,
): string[] {
  const baseUrl = init.env?.OPENAI_BASE_URL?.trim()
  if (!isExternalProviderId(init.providerId) || !baseUrl) {
    return [...baseArgs]
  }
  const providerName = init.env?.LEWORK_PROVIDER_NAME?.trim() || 'External Proxy'
  const wireApi = init.env?.LEWORK_CODEX_WIRE_API === 'chat' ? 'chat' : 'responses'
  return [
    ...baseArgs,
    '-c',
    'model_provider=lework_proxy',
    '-c',
    `model_providers.lework_proxy.name=${JSON.stringify(providerName)}`,
    '-c',
    `model_providers.lework_proxy.base_url=${JSON.stringify(baseUrl)}`,
    '-c',
    'model_providers.lework_proxy.env_key="OPENAI_API_KEY"',
    '-c',
    `model_providers.lework_proxy.wire_api=${JSON.stringify(wireApi)}`,
  ]
}

export interface CodexAdapterOptions {
  binary?: string
  args?: string[]
}

interface PendingCodexApproval {
  upstreamId: string | number
  options: PermissionOption[]
}

// Codex accepts different sandbox representations on thread and turn requests.
const modePolicies = {
  default: { sandbox: 'read-only', type: 'readOnly', approvalPolicy: 'on-request' },
  plan: { sandbox: 'read-only', type: 'readOnly', approvalPolicy: 'never' },
  acceptEdits: { sandbox: 'workspace-write', type: 'workspaceWrite', approvalPolicy: 'untrusted' },
  auto: { sandbox: 'workspace-write', type: 'workspaceWrite', approvalPolicy: 'on-request' },
  bypassPermissions: { sandbox: 'workspace-write', type: 'workspaceWrite', approvalPolicy: 'never' },
} as const satisfies Record<SessionMode, object>

export class CodexEngineAdapter implements EngineAdapter {
  readonly id = 'codex'
  readonly displayName = 'OpenAI Codex'
  readonly kind = 'server' as const

  constructor(private readonly opts: CodexAdapterOptions = {}) {}

  private resolveBinary(): string | null {
    if (this.opts.binary) return isExecutableFile(this.opts.binary) ? this.opts.binary : null
    return findExecutable('codex')
  }

  async probe(): Promise<EngineStatus> {
    const bin = this.resolveBinary()
    const version = bin ? probeCommandVersion(bin, ['--version']) : undefined
    const installed = Boolean(bin)
    // login status covers Codex's own login, not a user-configured provider.
    const nativeProvider = installed && !process.env.OPENAI_BASE_URL && !readCodexLocalSettings().usesExternalProvider
    const authStatus = nativeProvider && bin ? await probeCliAuth(bin, 'codex') : 'unknown'
    return {
      installed,
      ready: installed && authStatus === 'authenticated',
      authStatus: installed ? authStatus : undefined,
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
    const baseArgs = this.opts.args ?? ['app-server']
    const session = new CodexAppServerSession(
      init,
      bin,
      buildCodexLaunchArgs(baseArgs, init),
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
  private lastThreadId: string | undefined
  private activeTurnId: string | null = null
  private selectedModel: string | undefined
  private selectedMode: SessionMode | undefined
  private selectedEffort: string | undefined
  private currentMsgId = `msg_${crypto.randomUUID()}`
  private readonly seenTools = new Set<string>()
  private readonly pendingApprovals = new Map<string, PendingCodexApproval>()
  private turnWaiter: {
    resolve: () => void
    reject: (err: Error) => void
  } | null = null
  private activeTurn = false
  private closed = false

  constructor(
    private readonly init: EngineSessionInit,
    private readonly bin: string,
    private readonly args: string[],
  ) {
    this.sessionId = init.sessionId
    this.cwd = init.cwd
    this.lastThreadId = init.resumeNativeId
    this.selectedModel = init.modelId
    this.selectedMode = init.mode
    this.selectedEffort = init.reasoningEffort
  }

  isAlive(): boolean {
    return Boolean(!this.closed && this.rpc?.isAlive() && this.threadId)
  }

  async start(): Promise<void> {
    const rpc = new StdioJsonRpcProcess({
      command: this.bin,
      args: this.args,
      cwd: this.cwd,
      env: mergeSessionEnv(process.env, this.init.env),
      onInbound: msg => this.handleInbound(msg),
      onExit: (code, signal) => {
        this.pendingApprovals.clear()
        this.activeTurnId = null
        if (this.rpc === rpc) {
          this.threadId = null
        }
        const detail = formatEngineTurnError(
          new Error(`Codex app-server exited (code=${code ?? 'null'}, signal=${signal ?? 'null'})`),
          rpc.getStderrTail(),
        )
        if (this.turnWaiter) {
          const w = this.turnWaiter
          this.turnWaiter = null
          w.reject(new Error(detail))
        } else if (!this.closed && !this.activeTurn) {
          this.init.emit({
            type: 'session.state',
            state: 'error',
            detail,
          })
        }
      },
    })
    this.rpc = rpc
    rpc.start()

    await rpc.request(
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
    rpc.notify('initialized', {})

    const policy = this.selectedMode && modePolicies[this.selectedMode]
    const threadPolicy = policy ? { sandbox: policy.sandbox, approvalPolicy: policy.approvalPolicy } : {}
    let threadRes: Record<string, unknown> | null = null
    const resumeTarget = this.lastThreadId ?? this.init.resumeNativeId
    if (resumeTarget) {
      try {
        threadRes = (await rpc.request(
          'thread/resume',
          {
            ...threadPolicy,
            threadId: resumeTarget,
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
      threadRes = (await rpc.request(
        'thread/start',
        {
          ...threadPolicy,
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
          : resumeTarget ?? null
    if (!tid) {
      await rpc.close()
      if (this.rpc === rpc) this.rpc = null
      throw new Error('Codex thread/start did not return a thread id')
    }
    this.threadId = tid
    this.lastThreadId = tid
    this.init.onNativeSessionLinked?.(tid)

    this.init.onModelsChanged?.(this.selectedModel ?? 'gpt-5-codex', [
      { id: 'gpt-5-codex', name: 'GPT-5 Codex' },
      { id: 'o3', name: 'OpenAI o3' },
      { id: 'gpt-5', name: 'GPT-5' },
    ])
  }

  private async ensureRunning(): Promise<void> {
    if (this.isAlive()) return
    const stale = this.rpc
    this.rpc = null
    this.threadId = null
    this.activeTurnId = null
    this.pendingApprovals.clear()
    await stale?.close().catch(() => undefined)
    await this.start()
  }

  async prompt(
    turnId: string,
    text: string,
    images?: StagedPromptImage[],
    files?: StagedPromptFile[],
  ): Promise<void> {
    this.currentMsgId = `msg_${turnId}`
    this.activeTurn = true
    this.init.emit({ type: 'session.state', state: 'running' })

    const inputItems: Array<Record<string, unknown>> = []
    for (const img of images ?? []) {
      inputItems.push({ type: 'localImage', path: img.stagedPath })
    }
    const effectiveText = buildPromptTextWithAttachments(text, files, Boolean(images?.length))
    if (effectiveText) {
      inputItems.push({ type: 'text', text: effectiveText })
    }

    try {
      await this.ensureRunning()
      const rpc = this.rpc
      const threadId = this.threadId
      if (!rpc || !threadId) {
        throw new Error('Codex session is not initialized')
      }
      rpc.clearStderrTail()

      const completionPromise = new Promise<void>((resolve, reject) => {
        this.turnWaiter = { resolve, reject }
      })

      const policy = this.selectedMode && modePolicies[this.selectedMode]
      const startRes = (await rpc.request(
        'turn/start',
        {
          threadId,
          input: inputItems,
          ...(policy && { sandboxPolicy: { type: policy.type }, approvalPolicy: policy.approvalPolicy }),
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
      this.activeTurnId = null
      this.init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
      this.init.emit({ type: 'session.state', state: 'idle' })
    } catch (err) {
      const stderr = this.rpc?.getStderrTail()
      this.turnWaiter = null
      this.activeTurnId = null
      for (const [reqId] of this.pendingApprovals.entries()) {
        this.pendingApprovals.delete(reqId)
        this.init.emit({
          type: 'permission.resolved',
          requestId: reqId,
          optionId: null,
          outcome: 'cancelled',
        })
      }
      const msg = formatEngineTurnError(err, stderr)
      this.init.emit({ type: 'error', message: msg })
      this.init.emit({ type: 'turn.done', turnId, stopReason: 'error', error: msg })
      this.init.emit({ type: 'session.state', state: 'idle' })
    } finally {
      this.activeTurn = false
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
    if (optionId !== null && !pending.options.some(option => option.id === optionId)) {
      throw new Error(`Unknown Codex approval option: ${optionId}`)
    }
    this.rpc.respond(pending.upstreamId, {
      decision: optionId === null ? 'cancel' : optionId === 'allow' ? 'accept' : 'decline',
    })
    this.pendingApprovals.delete(requestId)
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

  async setMode(mode: SessionMode): Promise<void> {
    this.selectedMode = mode
  }

  async setModel(modelId: string): Promise<void> {
    this.selectedModel = modelId
  }

  async setEffort(effort: import('@lework/protocol').ReasoningEffort): Promise<void> {
    this.selectedEffort = effort
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

    if (method === 'turn/plan/updated' && Array.isArray(params.plan)) {
      const entries = (params.plan as Array<Record<string, unknown>>)
        .map(item => {
          const content =
            typeof item.step === 'string'
              ? item.step.trim()
              : typeof item.content === 'string'
                ? item.content.trim()
                : ''
          const rawStatus = typeof item.status === 'string' ? item.status.toLowerCase() : 'pending'
          const status =
            rawStatus === 'completed' || rawStatus === 'done'
              ? ('completed' as const)
              : rawStatus === 'inprogress' || rawStatus === 'in_progress'
                ? ('in_progress' as const)
                : ('pending' as const)
          return content ? { content, status } : null
        })
        .filter((e): e is { content: string; status: 'pending' | 'in_progress' | 'completed' } => e !== null)
      if (entries.length > 0) {
        this.init.emit({ type: 'plan.updated', entries })
      }
      return
    }

    if (method === 'item/plan/delta' && typeof params.delta === 'string') {
      this.init.emit({
        type: 'message.assistant.delta',
        messageId: `${this.currentMsgId}_plan`,
        text: params.delta,
      })
      return
    }

    if (method === 'turn/completed') {
      const w = this.turnWaiter
      this.turnWaiter = null
      const turnObj =
        params.turn && typeof params.turn === 'object'
          ? (params.turn as Record<string, unknown>)
          : undefined
      const errRaw = extractCodexErrorText(params.error ?? turnObj?.error)
      if (turnObj?.status === 'failed' || errRaw) {
        w?.reject(new Error(errRaw || 'Codex turn failed'))
      } else {
        w?.resolve()
      }
      return
    }

    if (method === 'error' || method === 'turn/failed') {
      const errMsg = extractCodexErrorText(params.error ?? params.message ?? params) || 'Codex error'
      if (this.turnWaiter) {
        const w = this.turnWaiter
        this.turnWaiter = null
        w.reject(new Error(errMsg))
      } else {
        this.init.emit({ type: 'error', message: formatEngineTurnError(errMsg) })
      }
    }
  }
}

function extractCodexErrorText(raw: unknown): string | null {
  if (!raw) return null
  if (typeof raw === 'string') return raw.trim() || null
  if (typeof raw === 'object') {
    const obj = raw as Record<string, unknown>
    if (typeof obj.message === 'string' && obj.message.trim()) return obj.message.trim()
    if (typeof obj.error === 'string' && obj.error.trim()) return obj.error.trim()
    if (obj.error && typeof obj.error === 'object') {
      const nested = (obj.error as Record<string, unknown>).message
      if (typeof nested === 'string' && nested.trim()) return nested.trim()
    }
  }
  return null
}
