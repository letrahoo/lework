import crypto from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type {
  EngineCapabilities,
  EngineStatus,
  ModelOption,
  PermissionOption,
} from '@lework/protocol'
import { findExecutable, probeCommandVersion } from '../platform/index.ts'
import { StdioJsonRpcProcess, type JsonRpcInbound } from './acp-jsonrpc.ts'
import { formatEngineTurnError } from './network-error.ts'
import type { EngineAdapter, EngineSession, EngineSessionInit, StagedPromptImage } from './types.ts'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const hubRoot = path.resolve(__dirname, '../..')
const repoRoot = path.resolve(hubRoot, '..')

export interface ClaudeAdapterOptions {
  /** Override ACP binary path (e.g. in tests). */
  acpBinary?: string
  /** Override args passed to `acpBinary`. */
  acpArgs?: string[]
  /** Extra environment variables for the child process (e.g. CPA endpoint injection). */
  env?: NodeJS.ProcessEnv
}

interface PendingPermission {
  upstreamId: string | number
  options: PermissionOption[]
}

export class ClaudeCodeAdapter implements EngineAdapter {
  readonly id = 'claude'
  readonly displayName = 'Claude Code'
  readonly kind = 'cli' as const

  constructor(private readonly opts: ClaudeAdapterOptions = {}) {}

  private resolveAcpBinary(): string | null {
    if (this.opts.acpBinary) return this.opts.acpBinary
    return findExecutable('claude-agent-acp', [
      path.join(hubRoot, 'node_modules', '.bin', 'claude-agent-acp'),
      path.join(repoRoot, 'node_modules', '.bin', 'claude-agent-acp'),
    ])
  }

  async probe(): Promise<EngineStatus> {
    const acpBin = this.resolveAcpBinary()
    const claudeBin = findExecutable('claude')
    const version = claudeBin ? probeCommandVersion(claudeBin, ['--version']) : undefined
    const installed = Boolean(acpBin && (claudeBin || this.opts.acpBinary))
    return {
      installed,
      ready: installed,
      version: version ?? (acpBin ? 'acp-ready' : undefined),
      detail: installed
        ? `ACP adapter: ${acpBin}`
        : 'Missing claude CLI or @agentclientprotocol/claude-agent-acp',
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
    const acpBin = this.resolveAcpBinary()
    if (!acpBin) {
      throw new Error('claude-agent-acp binary is not available')
    }
    const mergedEnv = { ...this.opts.env, ...init.env }
    const session = new ClaudeAcpSession(init, acpBin, this.opts.acpArgs ?? [], mergedEnv)
    await session.start()
    return session
  }
}

class ClaudeAcpSession implements EngineSession {
  readonly sessionId: string
  readonly cwd: string
  private rpc: StdioJsonRpcProcess | null = null
  private acpSessionId: string | null = null
  private lastNativeSessionId: string | undefined
  private selectedModel: string | undefined
  private activeMode: import('@lework/protocol').SessionMode | undefined
  private currentMsgId = `msg_${crypto.randomUUID()}`
  private readonly pendingPermissions = new Map<string, PendingPermission>()
  private activeTurn = false
  private closed = false

  constructor(
    private readonly init: EngineSessionInit,
    private readonly acpBin: string,
    private readonly acpArgs: string[],
    private readonly extraEnv?: NodeJS.ProcessEnv,
  ) {
    this.sessionId = init.sessionId
    this.cwd = init.cwd
    this.lastNativeSessionId = init.resumeNativeId
    this.selectedModel = init.modelId
    this.activeMode = init.mode
  }

  isAlive(): boolean {
    return Boolean(!this.closed && this.rpc?.isAlive() && this.acpSessionId)
  }

  async start(): Promise<void> {
    const rpc = new StdioJsonRpcProcess({
      command: this.acpBin,
      args: this.acpArgs,
      cwd: this.cwd,
      env: { ...process.env, ...this.extraEnv },
      onInbound: msg => this.handleInbound(msg),
      onExit: (code, signal) => {
        this.pendingPermissions.clear()
        if (this.rpc === rpc) {
          this.acpSessionId = null
        }
        if (!this.closed && !this.activeTurn) {
          const detail = formatEngineTurnError(
            new Error(`Claude ACP process exited (code=${code ?? 'null'}, signal=${signal ?? 'null'})`),
            rpc.getStderrTail(),
          )
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
        protocolVersion: 1,
        clientCapabilities: {
          fs: { readTextFile: false, writeTextFile: false },
          terminal: false,
        },
        clientInfo: {
          name: 'lework',
          version: '0.1.0',
        },
      },
      25_000,
    )

    let created: Record<string, unknown> | null = null
    let upstreamId: string | null = null
    const resumeTarget = this.lastNativeSessionId ?? this.init.resumeNativeId

    if (resumeTarget) {
      try {
        created = (await rpc.request(
          'session/resume',
          {
            sessionId: resumeTarget,
            cwd: this.cwd,
            mcpServers: [],
          },
          30_000,
        )) as Record<string, unknown> | null
        upstreamId =
          typeof created?.sessionId === 'string' ? created.sessionId : resumeTarget
      } catch {
        created = null
        upstreamId = null
      }
    }

    if (!upstreamId) {
      created = (await rpc.request(
        'session/new',
        {
          cwd: this.cwd,
          mcpServers: [],
        },
        30_000,
      )) as Record<string, unknown> | null
      upstreamId = typeof created?.sessionId === 'string' ? created.sessionId : null
    }

    if (!upstreamId) {
      await rpc.close()
      if (this.rpc === rpc) this.rpc = null
      throw new Error('Claude ACP session/new did not return a valid sessionId')
    }
    this.acpSessionId = upstreamId
    this.lastNativeSessionId = upstreamId
    this.init.onNativeSessionLinked?.(upstreamId)
    const reportedCurrentModel = this.extractModels(created ?? {})
    if (this.selectedModel && this.selectedModel !== reportedCurrentModel) {
      await rpc
        .request(
          'session/set_model',
          {
            sessionId: this.acpSessionId,
            modelId: this.selectedModel,
          },
          3_000,
        )
        .catch(() => undefined)
    }
    if (this.activeMode) {
      await rpc
        .request(
          'session/set_mode',
          {
            sessionId: this.acpSessionId,
            modeId: this.activeMode,
          },
          3_000,
        )
        .catch(() => undefined)
    }
  }

  private async ensureRunning(): Promise<void> {
    if (this.isAlive()) return
    const stale = this.rpc
    this.rpc = null
    this.acpSessionId = null
    this.pendingPermissions.clear()
    await stale?.close().catch(() => undefined)
    await this.start()
  }

  async prompt(turnId: string, text: string, images?: StagedPromptImage[]): Promise<void> {
    this.currentMsgId = `msg_${turnId}`
    this.activeTurn = true
    this.init.emit({ type: 'session.state', state: 'running' })

    const promptBlocks: Array<Record<string, unknown>> = []
    for (const img of images ?? []) {
      promptBlocks.push({
        type: 'image',
        data: img.dataBase64,
        mimeType: img.mimeType,
      })
    }
    const effectiveText = text.trim() || (images?.length ? '请结合所附图片进行分析。' : '')
    if (effectiveText) {
      promptBlocks.push({ type: 'text', text: effectiveText })
    }

    try {
      await this.ensureRunning()
      const rpc = this.rpc
      const acpSessionId = this.acpSessionId
      if (!rpc || !acpSessionId) {
        throw new Error('Claude ACP session is not initialized')
      }
      rpc.clearStderrTail()

      const res = (await rpc.request(
        'session/prompt',
        {
          sessionId: acpSessionId,
          prompt: promptBlocks,
        },
        10 * 60_000,
      )) as Record<string, unknown> | null

      const rawStop = typeof res?.stopReason === 'string' ? res.stopReason : 'end_turn'
      const stopReason =
        rawStop === 'cancelled' || rawStop === 'max_tokens' || rawStop === 'refusal'
          ? rawStop
          : 'end_turn'

      this.init.emit({ type: 'turn.done', turnId, stopReason })
      this.init.emit({ type: 'session.state', state: 'idle' })
    } catch (err) {
      const stderr = this.rpc?.getStderrTail()
      for (const [reqId] of this.pendingPermissions.entries()) {
        this.pendingPermissions.delete(reqId)
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
    if (!this.rpc || !this.acpSessionId) return
    // Fail-closed any pending permission requests on cancel
    for (const [reqId, pending] of this.pendingPermissions.entries()) {
      this.pendingPermissions.delete(reqId)
      try {
        this.rpc.respond(pending.upstreamId, { outcome: { outcome: 'cancelled' } })
      } catch {
        // ignore
      }
      this.init.emit({
        type: 'permission.resolved',
        requestId: reqId,
        optionId: null,
        outcome: 'cancelled',
      })
    }
    this.rpc.notify('session/cancel', { sessionId: this.acpSessionId })
  }

  async respondPermission(requestId: string, optionId: string | null): Promise<void> {
    const pending = this.pendingPermissions.get(requestId)
    if (!pending || !this.rpc) {
      throw new Error(`No pending permission request with id "${requestId}"`)
    }
    this.pendingPermissions.delete(requestId)

    if (optionId === null) {
      this.rpc.respond(pending.upstreamId, { outcome: { outcome: 'cancelled' } })
      this.init.emit({
        type: 'permission.resolved',
        requestId,
        optionId: null,
        outcome: 'cancelled',
      })
    } else {
      const matched = pending.options.find(o => o.id === optionId)
      if (!matched) {
        throw new Error(`Invalid permission option "${optionId}"`)
      }
      this.rpc.respond(pending.upstreamId, {
        outcome: { outcome: 'selected', optionId: matched.id },
      })
      this.init.emit({
        type: 'permission.resolved',
        requestId,
        optionId: matched.id,
        outcome: 'selected',
      })
    }

    if (this.pendingPermissions.size === 0) {
      this.init.emit({ type: 'session.state', state: 'running' })
    }
  }

  async setModel(modelId: string): Promise<void> {
    this.selectedModel = modelId
    if (!this.isAlive() || !this.rpc || !this.acpSessionId) return
    await this.rpc.request('session/set_model', {
      sessionId: this.acpSessionId,
      modelId,
    })
  }

  async setMode(mode: import('@lework/protocol').SessionMode): Promise<void> {
    this.activeMode = mode
    if (!this.isAlive() || !this.rpc || !this.acpSessionId) return
    await this.rpc.request('session/set_mode', {
      sessionId: this.acpSessionId,
      modeId: mode,
    })
  }

  async close(): Promise<void> {
    this.closed = true
    for (const [, pending] of this.pendingPermissions) {
      try {
        this.rpc?.respond(pending.upstreamId, { outcome: { outcome: 'cancelled' } })
      } catch {
        // ignore
      }
    }
    this.pendingPermissions.clear()
    await this.rpc?.close()
    this.rpc = null
  }

  private handleInbound(msg: JsonRpcInbound): void {
    if (msg.kind === 'notification' && msg.method === 'session/update') {
      const update =
        msg.params.update && typeof msg.params.update === 'object'
          ? (msg.params.update as Record<string, unknown>)
          : msg.params
      this.handleSessionUpdate(update)
      return
    }

    if (msg.kind === 'request' && msg.method === 'session/request_permission') {
      this.handlePermissionRequest(msg.id, msg.params)
      return
    }

    if (msg.kind === 'request') {
      // Fail-closed any unsupported callback from upstream ACP
      this.rpc?.respondError(msg.id, -32601, `Unsupported client callback: ${msg.method}`)
    }
  }

  private handleSessionUpdate(update: Record<string, unknown>): void {
    const kind = String(update.sessionUpdate ?? update.type ?? '')

    if (kind === 'agent_message_chunk' || kind === 'agent_message') {
      const text = extractTextContent(update)
      if (text) {
        this.init.emit({
          type: 'message.assistant.delta',
          messageId: this.currentMsgId,
          text,
        })
      }
      return
    }

    if (kind === 'agent_thought_chunk') {
      const text = extractTextContent(update)
      if (text) {
        this.init.emit({
          type: 'reasoning.delta',
          messageId: `${this.currentMsgId}_think`,
          text,
        })
      }
      return
    }

    if (kind === 'tool_call') {
      const toolCallId = String(update.toolCallId ?? update.callId ?? crypto.randomUUID())
      const title = String(update.title ?? update.name ?? 'Tool')
      const rawKind = String(update.kind ?? 'other')
      const toolKind = normalizeToolKind(rawKind)
      const status = normalizeToolStatus(String(update.status ?? 'in_progress'))
      const input =
        update.rawInput !== undefined
          ? typeof update.rawInput === 'string'
            ? update.rawInput
            : JSON.stringify(update.rawInput)
          : undefined
      const locations = Array.isArray(update.locations)
        ? update.locations
            .map((loc: unknown) =>
              loc && typeof loc === 'object' && typeof (loc as { path?: unknown }).path === 'string'
                ? (loc as { path: string }).path
                : null,
            )
            .filter((p): p is string => Boolean(p))
        : undefined

      this.init.emit({
        type: 'tool.call',
        toolCallId,
        title,
        kind: toolKind,
        status,
        input,
        locations: locations?.length ? locations : undefined,
      })
      return
    }

    if (kind === 'tool_call_update') {
      const toolCallId = String(update.toolCallId ?? update.callId ?? '')
      if (!toolCallId) return
      const status = update.status ? normalizeToolStatus(String(update.status)) : undefined
      const title = typeof update.title === 'string' ? update.title : undefined
      const output = extractToolOutput(update)
      this.init.emit({
        type: 'tool.update',
        toolCallId,
        status,
        title,
        output,
      })
      return
    }

    if (kind === 'plan' && Array.isArray(update.entries)) {
      const entries = update.entries
        .map((e: unknown) => {
          if (!e || typeof e !== 'object') return null
          const obj = e as Record<string, unknown>
          const content = typeof obj.content === 'string' ? obj.content : ''
          const st = obj.status === 'in_progress' || obj.status === 'completed' ? obj.status : 'pending'
          return content ? { content, status: st as 'pending' | 'in_progress' | 'completed' } : null
        })
        .filter((e): e is { content: string; status: 'pending' | 'in_progress' | 'completed' } => Boolean(e))
      this.init.emit({ type: 'plan.updated', entries })
    }
  }

  private handlePermissionRequest(upstreamId: string | number, params: Record<string, unknown>): void {
    const toolCall =
      params.toolCall && typeof params.toolCall === 'object'
        ? (params.toolCall as Record<string, unknown>)
        : {}
    const title = String(toolCall.title ?? params.title ?? 'Permission requested')
    const toolCallId = typeof toolCall.toolCallId === 'string' ? toolCall.toolCallId : undefined
    const rawOptions = Array.isArray(params.options) ? params.options : []

    const options: PermissionOption[] = rawOptions
      .map((opt: unknown) => {
        if (!opt || typeof opt !== 'object') return null
        const o = opt as Record<string, unknown>
        const id = String(o.optionId ?? o.id ?? '')
        const name = String(o.name ?? id)
        const rawKind = String(o.kind ?? 'allow_once')
        const kind: PermissionOption['kind'] =
          rawKind === 'allow_always' || rawKind === 'reject_once' || rawKind === 'reject_always'
            ? rawKind
            : 'allow_once'
        return id ? { id, name, kind } : null
      })
      .filter((o): o is PermissionOption => Boolean(o))

    if (options.length === 0) {
      options.push(
        { id: 'allow', name: 'Allow once', kind: 'allow_once' },
        { id: 'reject', name: 'Reject', kind: 'reject_once' },
      )
    }

    const requestId = `perm_${crypto.randomUUID()}`
    this.pendingPermissions.set(requestId, { upstreamId, options })
    this.init.emit({ type: 'session.state', state: 'waiting_permission', detail: title })
    this.init.emit({
      type: 'permission.requested',
      requestId,
      toolCallId,
      title,
      options,
    })
  }

  private extractModels(payload: Record<string, unknown>): string | undefined {
    const modelsObj =
      payload.models && typeof payload.models === 'object'
        ? (payload.models as Record<string, unknown>)
        : null
    if (!modelsObj) return undefined
    const current = typeof modelsObj.currentModelId === 'string' ? modelsObj.currentModelId : undefined
    const avail = Array.isArray(modelsObj.availableModels) ? modelsObj.availableModels : []
    const parsed: ModelOption[] = avail
      .map((m: unknown) => {
        if (!m || typeof m !== 'object') return null
        const o = m as Record<string, unknown>
        const id = String(o.modelId ?? o.id ?? '')
        const name = String(o.name ?? id)
        return id ? { id, name } : null
      })
      .filter((m): m is ModelOption => Boolean(m))
    if (parsed.length > 0) {
      this.init.onModelsChanged?.(current, parsed)
    }
    return current
  }
}

function extractTextContent(update: Record<string, unknown>): string | undefined {
  if (typeof update.text === 'string') return update.text
  const content = update.content
  if (content && typeof content === 'object' && !Array.isArray(content)) {
    const c = content as Record<string, unknown>
    if (typeof c.text === 'string') return c.text
  }
  return undefined
}

function extractToolOutput(update: Record<string, unknown>): string | undefined {
  if (Array.isArray(update.content)) {
    const texts: string[] = []
    for (const item of update.content) {
      if (item && typeof item === 'object') {
        const obj = item as Record<string, unknown>
        if (typeof obj.text === 'string') texts.push(obj.text)
        else if (obj.content && typeof obj.content === 'object') {
          const inner = obj.content as Record<string, unknown>
          if (typeof inner.text === 'string') texts.push(inner.text)
        }
      }
    }
    if (texts.length > 0) return texts.join('\n')
  }
  if (update.rawOutput !== undefined) {
    return typeof update.rawOutput === 'string'
      ? update.rawOutput
      : JSON.stringify(update.rawOutput)
  }
  return undefined
}

function normalizeToolKind(
  raw: string,
): 'read' | 'edit' | 'delete' | 'move' | 'search' | 'execute' | 'think' | 'fetch' | 'other' {
  switch (raw) {
    case 'read':
    case 'edit':
    case 'delete':
    case 'move':
    case 'search':
    case 'execute':
    case 'think':
    case 'fetch':
      return raw
    default:
      return 'other'
  }
}

function normalizeToolStatus(raw: string): 'pending' | 'in_progress' | 'completed' | 'failed' {
  switch (raw) {
    case 'pending':
    case 'in_progress':
    case 'completed':
    case 'failed':
      return raw
    default:
      return 'in_progress'
  }
}
