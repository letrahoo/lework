import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { Buffer } from 'node:buffer'
import crypto from 'node:crypto'
import type {
  EngineCapabilities,
  EngineStatus,
  ModelOption,
  PermissionOption,
  ReasoningEffort,
  SessionMode,
  SlashCommandInfo,
} from '@lework/protocol'
import { augmentedPathEnv, findExecutable, isExecutableFile, probeCommandVersion } from '../platform/index.ts'
import { isExternalProviderId } from '../registry/provider-adapters.ts'
import { classifyRawToolCall } from '../storage/host-history.ts'
import {
  normalizeHostEffort,
  parseAgyModelString,
  readAgyLocalSettings,
  resolveAgyWireModel,
} from '../storage/host-history/engine-settings.ts'
import { isPlanStepToolName, parsePlanEntriesFromRaw } from '../storage/host-history/plan-steps.ts'
import { buildPromptTextWithAttachments, buildTerminalImagePrompt } from '../storage/attachment-store.ts'
import { HUB_VERSION } from '../version.ts'
import { StdioJsonRpcProcess, type JsonRpcInbound } from './acp-jsonrpc.ts'
import { formatEngineTurnError } from './network-error.ts'
import { discoverEngineSlashCommands, mergeSlashCommands, parseAcpAvailableCommands } from './slash-commands.ts'
import type {
  EngineAdapter,
  EngineSession,
  EngineSessionInit,
  StagedPromptFile,
  StagedPromptImage,
} from './types.ts'

export interface AgyAdapterOptions {
  binary?: string
  args?: string[]
  /** Explicitly choose `'acp'` or `'stream-json'` (defaults to `'acp'` with automatic fallback). */
  transport?: 'acp' | 'stream-json'
}

/**
 * Google Antigravity (`agy`) CLI adapter.
 * Prefers AGY's native ACP JSON-RPC server (`agy acp`) for zero-restart model/effort/mode switching,
 * dynamic slash-command discovery, interactive tool permissions, structured `ask_question` elicitation,
 * native base64 image blocks, and live subagent step streaming.
 * Automatically falls back to `--input-format stream-json` for older binaries or explicit test overrides.
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

  private shouldUseAcp(): boolean {
    if (this.opts.transport === 'stream-json') return false
    if (this.opts.transport === 'acp') return true
    if (this.opts.args !== undefined) {
      return this.opts.args[0] === 'acp'
    }
    return true
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
        ? `${bin} (${this.shouldUseAcp() ? 'ACP' : 'stream-json'} · 仅官方订阅登录)`
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

    if (this.shouldUseAcp()) {
      const acpArgs = this.opts.args ?? ['acp']
      const acpSession = new AgyAcpSession(init, bin, acpArgs)
      try {
        await acpSession.start()
        return acpSession
      } catch (err) {
        await acpSession.close().catch(() => undefined)
        const errMsg = err instanceof Error ? err.message : String(err)
        // Only fall back to legacy stream-json if the CLI binary does not understand `acp`
        if (
          this.opts.transport !== 'acp' &&
          /(unknown command|unknown subcommand|unknown flag|flag provided but not defined|unrecognized|not a valid|invalid argument|exited before|ACP process exited)/i.test(
            errMsg,
          )
        ) {
          const fallback = new AgyStreamSession(
            init,
            bin,
            ['--input-format', 'stream-json', '--output-format', 'stream-json'],
          )
          await fallback.start()
          return fallback
        }
        throw err
      }
    }

    const session = new AgyStreamSession(
      init,
      bin,
      this.opts.args ?? ['--input-format', 'stream-json', '--output-format', 'stream-json'],
    )
    await session.start()
    return session
  }
}

// ---------------------------------------------------------------------------
// Native AGY ACP Session (`agy acp`)
// ---------------------------------------------------------------------------

interface PendingAcpPermission {
  kind: 'permission'
  upstreamId: string | number
  options: PermissionOption[]
}

interface ParsedElicitationQuestion {
  fieldKey: string
  otherFieldKey?: string
  title: string
  description?: string
  isArray: boolean
  minItems?: number
  fieldType: 'string' | 'number' | 'integer' | 'boolean'
  options: Array<{ id: string; label: string; wireValue: string }>
}

interface PendingElicitationState {
  kind: 'elicitation'
  upstreamId: string | number
  questions: ParsedElicitationQuestion[]
  currentIndex: number
  answers: Record<string, unknown>
}

type PendingAcpInteraction = PendingAcpPermission | PendingElicitationState

function toAgyAcpModeId(mode: SessionMode): string {
  switch (mode) {
    case 'acceptEdits':
      return 'accept-edits'
    case 'plan':
      return 'plan'
    case 'auto':
    case 'bypassPermissions':
      return 'auto'
    case 'default':
    default:
      return 'default'
  }
}

function fromAgyAcpModeId(raw: string): SessionMode | undefined {
  switch (raw.trim().toLowerCase()) {
    case 'default':
      return 'default'
    case 'accept-edits':
    case 'acceptedits':
      return 'acceptEdits'
    case 'auto':
      return 'auto'
    case 'plan':
      return 'plan'
    default:
      return undefined
  }
}

function mapEffortForAgyAcp(
  effort: ReasoningEffort,
  availableEfforts: string[],
): string {
  if (availableEfforts.includes(effort)) return effort
  if (effort === 'max' && availableEfforts.includes('xhigh')) return 'xhigh'
  if (effort === 'max' && availableEfforts.includes('high')) return 'high'
  if (effort === 'xhigh' && availableEfforts.includes('high')) return 'high'
  return availableEfforts[0] ?? effort
}

class AgyAcpSession implements EngineSession {
  readonly sessionId: string
  readonly cwd: string
  private rpc: StdioJsonRpcProcess | null = null
  private acpSessionId: string | null = null
  private lastNativeSessionId: string | undefined
  private modelFlag: string | undefined
  private effortFlag: ReasoningEffort | undefined
  private activeMode: SessionMode | undefined
  private availableThoughtLevels: string[] = ['low', 'medium', 'high', 'xhigh']
  private currentMsgId = `msg_${crypto.randomUUID()}`
  private turnSeq = 0
  private activeTurnToken = 0
  private starting = false
  private syncingInitialConfig = false
  private closed = false
  private readonly pendingInteractions = new Map<string, PendingAcpInteraction>()
  private readonly suppressedPlanToolIds = new Set<string>()
  private readonly canonicalToolTitles = new Map<string, string>()
  private readonly activeSubagentToolIds = new Set<string>()
  private latestTurnUsage: { inputTokens?: number; outputTokens?: number } | null = null
  private lastEmittedContextTokens: number | undefined
  private lastSubagentsHintAt = 0

  constructor(
    private readonly init: EngineSessionInit,
    private readonly bin: string,
    private readonly acpArgs: string[],
  ) {
    this.sessionId = init.sessionId
    this.cwd = init.cwd
    this.lastNativeSessionId = init.resumeNativeId
    this.modelFlag = init.modelId
    this.effortFlag = init.reasoningEffort
    this.activeMode = init.mode
  }

  isAlive(): boolean {
    return Boolean(!this.closed && this.rpc?.isAlive() && this.acpSessionId)
  }

  async start(): Promise<void> {
    this.starting = true
    try {
      const hostAgy = readAgyLocalSettings()
      if (!this.modelFlag && hostAgy.model) this.modelFlag = hostAgy.model
      if (!this.effortFlag && hostAgy.effort) this.effortFlag = hostAgy.effort

      const rpc = new StdioJsonRpcProcess({
        command: this.bin,
        args: this.acpArgs,
        cwd: this.cwd,
        env: {
          ...augmentedPathEnv(process.env),
          ...this.init.env,
          TERM: 'dumb',
        },
        onInbound: msg => this.handleInbound(msg),
        onExit: (code, signal) => {
          this.clearPendingInteractions(!this.closed, false)
          if (this.rpc === rpc) {
            this.acpSessionId = null
          }
          if (!this.closed && this.activeTurnToken === 0 && !this.starting) {
            const detail = formatEngineTurnError(
              new Error(`AGY ACP process exited (code=${code ?? 'null'}, signal=${signal ?? 'null'})`),
              rpc.getStderrTail(),
            )
            this.init.emit({ type: 'session.state', state: 'error', detail })
          }
        },
      })
      this.rpc = rpc
      rpc.start()

      const initRes = (await rpc.request(
        'initialize',
        {
          protocolVersion: 1,
          clientCapabilities: {
            fs: { readTextFile: false, writeTextFile: false },
            terminal: false,
            elicitation: { form: {} },
          },
          clientInfo: {
            name: 'lework',
            version: HUB_VERSION,
          },
        },
        25_000,
      )) as Record<string, unknown> | null

      const agentCaps =
        initRes?.agentCapabilities && typeof initRes.agentCapabilities === 'object'
          ? (initRes.agentCapabilities as Record<string, unknown>)
          : null
      const supportsLoadSession = agentCaps?.loadSession === true

      let created: Record<string, unknown> | null = null
      let upstreamId: string | null = null
      const resumeTarget = this.lastNativeSessionId ?? this.init.resumeNativeId

      if (resumeTarget) {
        let resumeErr: unknown = null
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
          upstreamId = typeof created?.sessionId === 'string' ? created.sessionId : resumeTarget
        } catch (err) {
          resumeErr = err
          created = null
          upstreamId = null
        }

        if (!upstreamId && supportsLoadSession) {
          try {
            created = (await rpc.request(
              'session/load',
              {
                sessionId: resumeTarget,
                cwd: this.cwd,
                mcpServers: [],
              },
              30_000,
            )) as Record<string, unknown> | null
            upstreamId = typeof created?.sessionId === 'string' ? created.sessionId : resumeTarget
          } catch (loadErr) {
            resumeErr = loadErr
            created = null
            upstreamId = null
          }
        }

        if (!upstreamId) {
          await rpc.close().catch(() => undefined)
          if (this.rpc === rpc) this.rpc = null
          const reason = resumeErr instanceof Error ? resumeErr.message : String(resumeErr ?? 'unknown error')
          throw new Error(`恢复 AGY 历史会话失败（${reason}），请确认该会话未被其他终端占用后重试。`)
        }
      } else {
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
        throw new Error('AGY ACP session/new did not return a valid sessionId')
      }

      this.acpSessionId = upstreamId
      this.lastNativeSessionId = upstreamId
      this.init.onNativeSessionLinked?.(upstreamId)

      const desiredModel = this.modelFlag
      const desiredEffort = this.effortFlag
      const desiredMode = this.activeMode
      this.syncingInitialConfig = true
      try {
        const reportedModel = this.extractSessionMetadata(created ?? {})
        if (desiredModel) this.modelFlag = desiredModel
        if (desiredEffort) this.effortFlag = desiredEffort
        if (desiredMode) this.activeMode = desiredMode
        await this.syncInitialConfig(rpc, upstreamId, reportedModel)
      } finally {
        this.syncingInitialConfig = false
      }
    } finally {
      this.starting = false
    }
  }

  private async syncInitialConfig(
    rpc: StdioJsonRpcProcess,
    sessionId: string,
    reportedCurrentModel?: string,
  ): Promise<void> {
    const { wireModel, wireEffort } = resolveAgyWireModel(this.modelFlag, this.effortFlag)
    if (wireModel && wireModel !== reportedCurrentModel) {
      await rpc
        .request('session/set_model', { sessionId, modelId: wireModel }, 5_000)
        .catch(async () => {
          if (this.modelFlag && this.modelFlag !== wireModel) {
            try {
              await rpc.request('session/set_model', { sessionId, modelId: this.modelFlag }, 5_000)
              return
            } catch {
              // fall through to set_config_option
            }
          }
          if (this.modelFlag) {
            await rpc
              .request(
                'session/set_config_option',
                { sessionId, configId: 'model', value: this.modelFlag },
                5_000,
              )
              .catch(() => undefined)
          }
        })
    }
    const targetEffort = wireEffort ?? this.effortFlag
    if (targetEffort) {
      const mapped = mapEffortForAgyAcp(targetEffort, this.availableThoughtLevels)
      await rpc
        .request(
          'session/set_config_option',
          { sessionId, configId: 'thought_level', value: mapped },
          5_000,
        )
        .catch(() => undefined)
    }
    if (this.activeMode) {
      try {
        await rpc.request(
          'session/set_mode',
          { sessionId, modeId: toAgyAcpModeId(this.activeMode) },
          5_000,
        )
      } catch (err) {
        if (this.activeMode === 'plan' || this.activeMode === 'default') {
          throw new Error(
            `无法将 AGY 会话切换到安全模式 (${this.activeMode}): ${err instanceof Error ? err.message : String(err)}`,
          )
        }
      }
    }
  }

  private async ensureRunning(): Promise<void> {
    if (this.isAlive()) return
    const stale = this.rpc
    this.rpc = null
    this.acpSessionId = null
    this.clearPendingInteractions(!this.closed, false)
    await stale?.close().catch(() => undefined)
    await this.start()
  }

  async prompt(
    turnId: string,
    text: string,
    images?: StagedPromptImage[],
    files?: StagedPromptFile[],
  ): Promise<void> {
    const myToken = ++this.turnSeq
    this.currentMsgId = `msg_${turnId}`
    this.suppressedPlanToolIds.clear()
    this.canonicalToolTitles.clear()
    this.activeSubagentToolIds.clear()
    this.latestTurnUsage = null
    this.init.emit({ type: 'session.state', state: 'running' })

    const promptBlocks: Array<Record<string, unknown>> = []
    for (const img of images ?? []) {
      promptBlocks.push({
        type: 'image',
        data: img.dataBase64,
        mimeType: img.mimeType,
      })
    }
    const effectiveText = buildPromptTextWithAttachments(text, files, Boolean(images?.length))
    if (effectiveText) {
      promptBlocks.push({ type: 'text', text: effectiveText })
    }

    try {
      await this.ensureRunning()
      const rpc = this.rpc
      const acpSessionId = this.acpSessionId
      if (!rpc || !acpSessionId) {
        throw new Error('AGY ACP session is not initialized')
      }
      this.activeTurnToken = myToken
      rpc.clearStderrTail()

      // Pass timeoutMs=0 so active long-running tasks and interactive approval waits never hit
      // a bogus fixed wall-clock cutoff while the child process is still alive and responsive.
      const res = (await rpc.request(
        'session/prompt',
        {
          sessionId: acpSessionId,
          prompt: promptBlocks,
        },
        0,
      )) as Record<string, unknown> | null

      if (this.activeTurnToken !== myToken) return

      this.flushLatestTurnUsage()
      if (this.pendingInteractions.size > 0) {
        this.clearPendingInteractions(true, false)
      }

      const rawStop = typeof res?.stopReason === 'string' ? res.stopReason : 'end_turn'
      const stopReason =
        rawStop === 'cancelled' || rawStop === 'max_tokens' || rawStop === 'refusal'
          ? rawStop
          : 'end_turn'

      this.init.emit({ type: 'turn.done', turnId, stopReason })
      this.init.emit({ type: 'session.state', state: 'idle' })
    } catch (err) {
      if (this.activeTurnToken !== myToken && this.activeTurnToken !== 0) return
      const stderr = this.rpc?.getStderrTail()
      this.clearPendingInteractions(true, true)
      if (this.rpc?.isAlive() && this.acpSessionId) {
        try {
          this.rpc.notify('session/cancel', { sessionId: this.acpSessionId })
        } catch {
          // ignore
        }
      }
      const msg = formatEngineTurnError(err, stderr)
      this.init.emit({ type: 'error', message: msg })
      this.init.emit({ type: 'turn.done', turnId, stopReason: 'error', error: msg })
      this.init.emit({ type: 'session.state', state: 'idle' })
    } finally {
      if (this.activeTurnToken === myToken) {
        this.activeTurnToken = 0
      }
    }
  }

  async cancel(): Promise<void> {
    if (!this.rpc || !this.acpSessionId) return
    this.clearPendingInteractions(true, true)
    try {
      this.rpc.notify('session/cancel', { sessionId: this.acpSessionId })
    } catch {
      // ignore if pipe already closed
    }
  }

  async respondPermission(
    requestId: string,
    optionId: string | null,
    customInput?: string,
  ): Promise<void> {
    const pending = this.pendingInteractions.get(requestId)
    if (!pending || !this.rpc) {
      throw new Error(`No pending confirmation request with id "${requestId}"`)
    }

    if (pending.kind === 'permission') {
      if (optionId === null) {
        this.pendingInteractions.delete(requestId)
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
        this.pendingInteractions.delete(requestId)
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
      if (this.pendingInteractions.size === 0) {
        this.init.emit({ type: 'session.state', state: 'running' })
      }
      return
    }

    // Elicitation (`ask_question`) step response — validate BEFORE mutating/deleting pending map!
    const question = pending.questions[pending.currentIndex]
    if (!question || optionId === null) {
      this.pendingInteractions.delete(requestId)
      this.rpc.respond(pending.upstreamId, { action: 'decline' })
      this.init.emit({
        type: 'permission.resolved',
        requestId,
        optionId: null,
        outcome: 'cancelled',
      })
      if (this.pendingInteractions.size === 0) {
        this.init.emit({ type: 'session.state', state: 'running' })
      }
      return
    }

    const trimmedCustom = customInput?.trim()
    let answerKey = question.fieldKey
    let answerValue: unknown
    if (trimmedCustom && (optionId === '__custom__' || !question.options.some(o => o.id === optionId))) {
      if (question.otherFieldKey) {
        answerKey = question.otherFieldKey
        answerValue = trimmedCustom
      } else if (question.isArray) {
        const parts = trimmedCustom
          .split(/[,，\n]+/)
          .map(s => s.trim())
          .filter(Boolean)
        if (parts.length === 0) {
          throw new Error('请输入有效回答')
        }
        if (question.minItems && parts.length < question.minItems) {
          throw new Error(`至少需要选择或输入 ${question.minItems} 项（用逗号分隔）`)
        }
        answerValue = parts.map(part => {
          const byLabel = question.options.find(
            o => o.wireValue === part || o.label.toLowerCase() === part.toLowerCase(),
          )
          return coerceElicitationValue(byLabel ? byLabel.wireValue : part, question.fieldType)
        })
      } else {
        answerValue = coerceElicitationValue(trimmedCustom, question.fieldType)
      }
    } else {
      const matched = question.options.find(o => o.id === optionId)
      if (!matched) {
        throw new Error(`Invalid question option "${optionId}"`)
      }
      const coerced = coerceElicitationValue(matched.wireValue, question.fieldType)
      answerValue = question.isArray ? [coerced] : coerced
    }

    this.pendingInteractions.delete(requestId)
    pending.answers[answerKey] = answerValue

    this.init.emit({
      type: 'permission.resolved',
      requestId,
      optionId,
      outcome: 'selected',
    })

    // Multi-question ask_question: advance to next question before resolving the upstream RPC!
    if (pending.currentIndex + 1 < pending.questions.length) {
      pending.currentIndex += 1
      this.presentElicitationStep(pending)
      return
    }

    this.rpc.respond(pending.upstreamId, {
      action: 'accept',
      content: pending.answers,
    })
    if (this.pendingInteractions.size === 0) {
      this.init.emit({ type: 'session.state', state: 'running' })
    }
  }

  async setModel(modelId: string): Promise<void> {
    const parsed = parseAgyModelString(modelId)
    const nextModel = parsed.modelId ?? modelId
    const nextEffort = parsed.effort ?? this.effortFlag
    if (!this.isAlive() || !this.rpc || !this.acpSessionId) {
      this.modelFlag = nextModel
      if (parsed.effort) this.effortFlag = parsed.effort
      return
    }
    const { wireModel } = resolveAgyWireModel(nextModel, nextEffort)
    const target = wireModel ?? nextModel
    let lastErr: unknown = null
    try {
      await this.rpc.request(
        'session/set_model',
        { sessionId: this.acpSessionId, modelId: target },
        10_000,
      )
      this.modelFlag = nextModel
      if (parsed.effort) this.effortFlag = parsed.effort
      return
    } catch (err) {
      lastErr = err
    }
    if (nextModel && nextModel !== target) {
      try {
        await this.rpc.request(
          'session/set_model',
          { sessionId: this.acpSessionId, modelId: nextModel },
          10_000,
        )
        this.modelFlag = nextModel
        if (parsed.effort) this.effortFlag = parsed.effort
        return
      } catch (err) {
        lastErr = err
      }
    }
    try {
      await this.rpc.request(
        'session/set_config_option',
        { sessionId: this.acpSessionId, configId: 'model', value: nextModel },
        10_000,
      )
      this.modelFlag = nextModel
      if (parsed.effort) this.effortFlag = parsed.effort
    } catch (err) {
      throw err instanceof Error ? err : lastErr instanceof Error ? lastErr : new Error(String(err))
    }
  }

  async setEffort(effort: ReasoningEffort): Promise<void> {
    if (!this.isAlive() || !this.rpc || !this.acpSessionId) {
      this.effortFlag = effort
      return
    }
    const mapped = mapEffortForAgyAcp(effort, this.availableThoughtLevels)
    try {
      await this.rpc.request(
        'session/set_config_option',
        { sessionId: this.acpSessionId, configId: 'thought_level', value: mapped },
        10_000,
      )
      this.effortFlag = effort
      return
    } catch (firstErr) {
      const { wireModel } = resolveAgyWireModel(this.modelFlag, effort)
      if (wireModel) {
        await this.rpc.request(
          'session/set_model',
          { sessionId: this.acpSessionId, modelId: wireModel },
          10_000,
        )
        this.effortFlag = effort
        return
      }
      throw firstErr
    }
  }

  async setMode(mode: SessionMode): Promise<void> {
    if (!this.isAlive() || !this.rpc || !this.acpSessionId) {
      this.activeMode = mode
      return
    }
    await this.rpc.request(
      'session/set_mode',
      { sessionId: this.acpSessionId, modeId: toAgyAcpModeId(mode) },
      10_000,
    )
    this.activeMode = mode
  }

  async close(): Promise<void> {
    this.closed = true
    this.clearPendingInteractions(false, true)
    if (this.rpc?.isAlive() && this.acpSessionId) {
      await this.rpc
        .request('session/close', { sessionId: this.acpSessionId }, 2_000)
        .catch(() => undefined)
    }
    await this.rpc?.close()
    this.rpc = null
    this.acpSessionId = null
  }

  private clearPendingInteractions(emitEvents: boolean, respondUpstream: boolean): void {
    for (const [reqId, pending] of this.pendingInteractions.entries()) {
      this.pendingInteractions.delete(reqId)
      if (respondUpstream) {
        try {
          if (pending.kind === 'permission') {
            this.rpc?.respond(pending.upstreamId, { outcome: { outcome: 'cancelled' } })
          } else {
            this.rpc?.respond(pending.upstreamId, { action: 'cancel' })
          }
        } catch {
          // ignore
        }
      }
      if (emitEvents) {
        this.init.emit({
          type: 'permission.resolved',
          requestId: reqId,
          optionId: null,
          outcome: 'cancelled',
        })
      }
    }
  }

  private flushLatestTurnUsage(): void {
    const u = this.latestTurnUsage
    this.latestTurnUsage = null
    if (!u) return
    const hasInput = typeof u.inputTokens === 'number' && u.inputTokens > 0
    const hasOutput = typeof u.outputTokens === 'number' && u.outputTokens > 0
    if (!hasInput && !hasOutput) return
    if (hasInput && !hasOutput && u.inputTokens === this.lastEmittedContextTokens) return
    if (hasInput) this.lastEmittedContextTokens = u.inputTokens
    this.init.emit({
      type: 'usage',
      ...(hasInput ? { inputTokens: u.inputTokens } : {}),
      ...(hasOutput ? { outputTokens: u.outputTokens } : {}),
    })
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

    if (msg.kind === 'request' && msg.method === 'elicitation/create') {
      this.handleElicitationRequest(msg.id, msg.params)
      return
    }

    if (msg.kind === 'request') {
      this.rpc?.respondError(msg.id, -32601, `Unsupported client callback: ${msg.method}`)
    }
  }

  private handleSessionUpdate(update: Record<string, unknown>): void {
    const kind = String(update.sessionUpdate ?? update.type ?? '')

    if (kind === 'available_commands_update') {
      const live = parseAcpAvailableCommands(update.availableCommands, 'agy')
      const merged: SlashCommandInfo[] = mergeSlashCommands(
        live,
        discoverEngineSlashCommands('agy', { cwd: this.cwd }),
        { localizeBuiltins: true },
      )
      this.init.onSlashCommandsChanged?.(merged)
      return
    }

    if (kind === 'current_mode_update') {
      const modeId = typeof update.currentModeId === 'string' ? update.currentModeId : ''
      const mapped = fromAgyAcpModeId(modeId)
      if (mapped && !(this.activeMode === 'bypassPermissions' && mapped === 'auto')) {
        this.activeMode = mapped
        if (!this.syncingInitialConfig) {
          this.init.onModeChanged?.(mapped)
        }
      }
      return
    }

    if (kind === 'config_option_update') {
      this.parseConfigOptions(update.configOptions, !this.syncingInitialConfig)
      return
    }

    // Only emit conversational stream deltas/tools/usage while a live turn is active so any residual
    // replay frames during session/resume or idle config updates never pollute transcript/usage history.
    if (this.activeTurnToken === 0) return

    if (kind === 'usage_update') {
      // Only record explicit per-turn token counters (`inputTokens` / `outputTokens`).
      // ACP `usage_update.used` is cumulative context-window occupancy, NOT incremental per-turn input tokens;
      // feeding `used` into additive `usage` events would overcount multi-turn token consumption.
      const rawIn =
        typeof update.inputTokens === 'number'
          ? update.inputTokens
          : typeof update.input_tokens === 'number'
            ? update.input_tokens
            : undefined
      const rawOut =
        typeof update.outputTokens === 'number'
          ? update.outputTokens
          : typeof update.output_tokens === 'number'
            ? update.output_tokens
            : undefined
      const inputTokens =
        rawIn !== undefined && Number.isFinite(rawIn) ? Math.max(0, Math.floor(rawIn)) : undefined
      const outputTokens =
        rawOut !== undefined && Number.isFinite(rawOut) ? Math.max(0, Math.floor(rawOut)) : undefined
      if ((inputTokens !== undefined && inputTokens > 0) || (outputTokens !== undefined && outputTokens > 0)) {
        this.latestTurnUsage = {
          ...(inputTokens !== undefined && inputTokens > 0
            ? { inputTokens }
            : this.latestTurnUsage?.inputTokens !== undefined
              ? { inputTokens: this.latestTurnUsage.inputTokens }
              : {}),
          ...(outputTokens !== undefined && outputTokens > 0
            ? { outputTokens }
            : this.latestTurnUsage?.outputTokens !== undefined
              ? { outputTokens: this.latestTurnUsage.outputTokens }
              : {}),
        }
      }
      return
    }

    if (kind === 'agent_message_chunk' || kind === 'agent_message') {
      const text = extractAcpTextContent(update)
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
      const text = extractAcpTextContent(update)
      if (text) {
        this.init.emit({
          type: 'reasoning.delta',
          messageId: `${this.currentMsgId}_think`,
          text,
        })
      }
      return
    }

    if (kind === 'plan' && Array.isArray(update.entries)) {
      const entries = parsePlanEntriesFromRaw(update.entries)
      this.init.emit({ type: 'plan.updated', entries })
      return
    }

    if (kind === 'tool_call') {
      const toolCallId = String(update.toolCallId ?? update.callId ?? crypto.randomUUID())
      const rawTitle = String(update.title ?? update.name ?? 'Tool')
      const rawName = typeof update.name === 'string' ? update.name : rawTitle
      const rawInputStr =
        update.rawInput !== undefined
          ? typeof update.rawInput === 'string'
            ? update.rawInput
            : JSON.stringify(update.rawInput)
          : undefined

      if (isAgyPlanUpdateCall(rawName, rawTitle, update.rawInput)) {
        this.suppressedPlanToolIds.add(toolCallId)
        if (update.rawInput !== undefined) {
          const entries = parsePlanEntriesFromRaw(update.rawInput)
          this.init.emit({ type: 'plan.updated', entries })
        }
        return
      }

      const rawKind = String(update.kind ?? 'other')
      const normalizedTitle = normalizeAgyAcpToolTitle(rawName, rawTitle, update.rawInput, rawKind)
      const classified = classifyRawToolCall(normalizedTitle, rawInputStr)
      const toolKind = rawKind !== 'other' ? normalizeAcpToolKind(rawKind) : classified.kind
      const status = normalizeAcpToolStatus(String(update.status ?? 'in_progress'))
      const locations = extractAcpLocations(update.locations)
      const finalTitle =
        classified.title === 'Skill' || normalizedTitle !== rawTitle ? classified.title : rawTitle
      const finalInput = classified.title === 'Skill' ? (classified.input ?? rawInputStr) : rawInputStr

      if (normalizedTitle !== rawTitle || classified.title === 'Skill') {
        this.canonicalToolTitles.set(toolCallId, finalTitle)
      }

      this.init.emit({
        type: 'tool.call',
        toolCallId,
        title: finalTitle,
        kind: toolKind,
        status,
        input: finalInput,
        locations: locations.length > 0 ? locations : undefined,
      })

      if (isSubagentActivityTool(rawName, normalizedTitle, rawInputStr, rawKind)) {
        this.activeSubagentToolIds.add(toolCallId)
        this.triggerSubagentsHint()
      }
      return
    }

    if (kind === 'tool_call_update') {
      const toolCallId = String(update.toolCallId ?? update.callId ?? '')
      if (!toolCallId) return
      if (this.suppressedPlanToolIds.has(toolCallId)) {
        if (update.rawInput !== undefined) {
          const entries = parsePlanEntriesFromRaw(update.rawInput)
          this.init.emit({ type: 'plan.updated', entries })
        }
        return
      }
      const status = update.status ? normalizeAcpToolStatus(String(update.status)) : undefined
      const canonical = this.canonicalToolTitles.get(toolCallId)
      const title = canonical ?? (typeof update.title === 'string' ? update.title : undefined)
      const output = extractAcpToolOutput(update)
      this.init.emit({
        type: 'tool.update',
        toolCallId,
        status,
        title,
        output,
      })
      if ((status === 'completed' || status === 'failed') && this.activeSubagentToolIds.has(toolCallId)) {
        this.activeSubagentToolIds.delete(toolCallId)
        const now = Date.now()
        if (now - this.lastSubagentsHintAt > 1_500) {
          this.triggerSubagentsHint()
        }
      }
    }
  }

  private triggerSubagentsHint(): void {
    this.lastSubagentsHintAt = Date.now()
    this.init.onSubagentsHint?.()
  }

  private handlePermissionRequest(upstreamId: string | number, params: Record<string, unknown>): void {
    const toolCall =
      params.toolCall && typeof params.toolCall === 'object'
        ? (params.toolCall as Record<string, unknown>)
        : {}
    const title = buildAgyPermissionTitle(toolCall, params)
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
        { id: 'allow', name: '仅本次允许', kind: 'allow_once' },
        { id: 'deny', name: '拒绝', kind: 'reject_once' },
      )
    }

    // In `bypassPermissions` mode, auto-approve without interrupting the user.
    if (this.activeMode === 'bypassPermissions') {
      const allowOpt =
        options.find(o => o.kind === 'allow_once') ?? options.find(o => o.kind === 'allow_always')
      if (allowOpt) {
        try {
          this.rpc?.respond(upstreamId, {
            outcome: { outcome: 'selected', optionId: allowOpt.id },
          })
          return
        } catch {
          // fall through to manual prompt
        }
      }
    }

    const requestId = `perm_${crypto.randomUUID()}`
    this.pendingInteractions.set(requestId, { kind: 'permission', upstreamId, options })
    this.init.emit({ type: 'session.state', state: 'waiting_permission', detail: title.split('\n')[0] })
    this.init.emit({
      type: 'permission.requested',
      requestId,
      toolCallId,
      title,
      options,
      interactionKind: 'permission',
      allowCustomInput: false,
    })
  }

  private handleElicitationRequest(upstreamId: string | number, params: Record<string, unknown>): void {
    const questions = parseElicitationQuestions(params)
    if (questions.length === 0 || questions.some(q => q.isArray && (q.minItems ?? 0) > 1)) {
      // Gracefully reject unparseable or mandatory multi-select forms so AGY falls back to chat mode
      this.rpc?.respondError(upstreamId, -32602, 'Unsupported elicitation schema')
      return
    }
    const state: PendingElicitationState = {
      kind: 'elicitation',
      upstreamId,
      questions,
      currentIndex: 0,
      answers: {},
    }
    this.presentElicitationStep(state)
  }

  private presentElicitationStep(state: PendingElicitationState): void {
    const q = state.questions[state.currentIndex]
    if (!q) return
    const requestId = `elicit_${crypto.randomUUID()}`
    this.pendingInteractions.set(requestId, state)

    const prefix =
      state.questions.length > 1
        ? `请确认问题 (${state.currentIndex + 1}/${state.questions.length})：${q.title}`
        : q.title
    const fullTitle = q.description && q.description !== q.title ? `${prefix}\n${q.description}` : prefix
    const options: PermissionOption[] = q.options.map((opt, idx) => ({
      id: opt.id,
      name: `${idx + 1}. ${opt.label}`,
      kind: 'allow_once',
    }))

    this.init.emit({ type: 'session.state', state: 'waiting_permission', detail: prefix })
    this.init.emit({
      type: 'permission.requested',
      requestId,
      title: fullTitle,
      options,
      interactionKind: 'question',
      allowCustomInput: Boolean(q.otherFieldKey || options.length === 0),
    })
  }

  private extractSessionMetadata(payload: Record<string, unknown>): string | undefined {
    this.parseConfigOptions(payload.configOptions)
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

  private parseConfigOptions(raw: unknown, applyCurrentValues = false): void {
    if (!Array.isArray(raw)) return
    for (const item of raw) {
      if (!item || typeof item !== 'object') continue
      const opt = item as Record<string, unknown>
      const id = typeof opt.id === 'string' ? opt.id : ''
      if (id === 'thought_level') {
        if (Array.isArray(opt.options)) {
          const levels = opt.options
            .map(o => (o && typeof o === 'object' && typeof (o as Record<string, unknown>).value === 'string'
              ? String((o as Record<string, unknown>).value)
              : null))
            .filter((v): v is string => Boolean(v))
          if (levels.length > 0) this.availableThoughtLevels = levels
        }
        if (applyCurrentValues && typeof opt.currentValue === 'string') {
          this.effortFlag = normalizeHostEffort(opt.currentValue, this.effortFlag ?? 'high')
        }
      } else if (id === 'mode' && applyCurrentValues && typeof opt.currentValue === 'string') {
        const mapped = fromAgyAcpModeId(opt.currentValue)
        if (mapped && !(this.activeMode === 'bypassPermissions' && mapped === 'auto')) {
          this.activeMode = mapped
          this.init.onModeChanged?.(mapped)
        }
      }
    }
  }
}

function isAgyPlanUpdateCall(rawName: string, rawTitle: string, rawInput: unknown): boolean {
  if (isPlanStepToolName(rawName) === 'update_steps' || isPlanStepToolName(rawTitle) === 'update_steps') {
    return true
  }
  const lower = `${rawName} ${rawTitle}`.toLowerCase()
  if (lower.includes('update_steps') || lower.includes('update steps') || lower.includes('checklist')) {
    return rawInput === undefined || parsePlanEntriesFromRaw(rawInput).length > 0
  }
  return false
}

const NON_SUBAGENT_ACP_KINDS = new Set(['read', 'edit', 'delete', 'move', 'search', 'execute', 'fetch'])

function normalizeAgyAcpToolTitle(
  rawName: string,
  rawTitle: string,
  rawInput: unknown,
  rawKind = 'other',
): string {
  const kindLower = rawKind.toLowerCase()
  if (!NON_SUBAGENT_ACP_KINDS.has(kindLower)) {
    const nameLower = rawName.trim().toLowerCase()
    const titleLower = rawTitle.trim().toLowerCase()
    if (
      nameLower === 'invoke_subagent' ||
      nameLower === 'define_subagent' ||
      nameLower === 'manage_subagents' ||
      /\b(invoke_subagent|subagent)\b/.test(nameLower) ||
      /^subagent\b/.test(titleLower)
    ) {
      return 'invoke_subagent'
    }
    if (nameLower === 'run_workflow' || /\brun_workflow\b/.test(nameLower)) return 'run_workflow'
    if (nameLower === 'swarm' || /^swarm\b/.test(titleLower)) return 'swarm'
  }
  if (rawInput && typeof rawInput === 'object' && !Array.isArray(rawInput)) {
    const obj = rawInput as Record<string, unknown>
    if (typeof obj.TypeName === 'string' && typeof obj.Role === 'string' && typeof obj.Prompt === 'string') {
      return 'invoke_subagent'
    }
    if (typeof obj.action === 'string' && ('brief' in obj || 'tasks' in obj)) {
      return 'swarm'
    }
  }
  return rawTitle
}

function isSubagentActivityTool(
  rawName: string,
  normalizedTitle: string,
  rawInput?: string,
  rawKind = 'other',
): boolean {
  if (NON_SUBAGENT_ACP_KINDS.has(rawKind.toLowerCase())) return false
  const lowerName = rawName.trim().toLowerCase()
  const lowerNorm = normalizedTitle.trim().toLowerCase()
  return (
    lowerNorm === 'invoke_subagent' ||
    lowerNorm === 'swarm' ||
    lowerNorm === 'run_workflow' ||
    lowerName === 'invoke_subagent' ||
    lowerName === 'swarm' ||
    lowerName === 'run_workflow' ||
    lowerName === 'task' ||
    lowerName === 'agent' ||
    Boolean(rawInput && /"(?:TypeName|conversationId)"/.test(rawInput))
  )
}

function coerceElicitationValue(raw: string, fieldType: ParsedElicitationQuestion['fieldType']): unknown {
  const trimmed = raw.trim()
  if (fieldType === 'boolean') {
    const lower = trimmed.toLowerCase()
    if (lower === 'true' || lower === 'yes' || lower === '1' || lower === '是') return true
    if (lower === 'false' || lower === 'no' || lower === '0' || lower === '否') return false
    throw new Error('请输入有效的是/否（true 或 false）')
  }
  if (fieldType === 'integer') {
    if (!/^[+-]?\d+$/.test(trimmed)) {
      throw new Error('请输入有效的整数')
    }
    const n = Number(trimmed)
    if (!Number.isSafeInteger(n)) {
      throw new Error('请输入有效范围内的整数')
    }
    return n
  }
  if (fieldType === 'number') {
    if (!/^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(trimmed)) {
      throw new Error('请输入有效的数字')
    }
    const n = Number(trimmed)
    if (!Number.isFinite(n)) {
      throw new Error('请输入有效的有限数字')
    }
    return n
  }
  return raw
}

function buildAgyPermissionTitle(
  toolCall: Record<string, unknown>,
  params: Record<string, unknown>,
): string {
  const baseTitle = String(toolCall.title ?? params.title ?? '需要确认操作').trim()
  const rawInput = toolCall.rawInput
  let detail = ''
  if (rawInput && typeof rawInput === 'object' && !Array.isArray(rawInput)) {
    const obj = rawInput as Record<string, unknown>
    const cmd = obj.CommandLine ?? obj.commandLine ?? obj.command ?? obj.cmd
    const filePath = obj.Path ?? obj.path ?? obj.targetFile ?? obj.TargetFile ?? obj.AbsolutePath
    if (typeof cmd === 'string' && cmd.trim()) {
      detail = cmd.trim()
    } else if (typeof filePath === 'string' && filePath.trim()) {
      detail = filePath.trim()
    }
  } else if (typeof rawInput === 'string' && rawInput.trim()) {
    detail = rawInput.trim()
  }
  if (!detail) {
    const locs = extractAcpLocations(toolCall.locations)
    if (locs.length > 0) detail = locs[0]!
  }
  return detail && !baseTitle.includes(detail) ? `${baseTitle}\n${detail}` : baseTitle
}

export function parseElicitationQuestions(params: Record<string, unknown>): ParsedElicitationQuestion[] {
  const schema =
    params.requestedSchema && typeof params.requestedSchema === 'object'
      ? (params.requestedSchema as Record<string, unknown>)
      : null
  const props =
    schema?.properties && typeof schema.properties === 'object'
      ? (schema.properties as Record<string, unknown>)
      : null
  if (!props) return []

  const propKeys = Object.keys(props)
  const propKeySet = new Set(propKeys)
  const otherKeys = new Set(propKeys.filter(k => k.endsWith('_other') && propKeySet.has(k.slice(0, -6))))
  const questionKeys = propKeys.filter(k => !otherKeys.has(k))
  const out: ParsedElicitationQuestion[] = []

  for (const key of questionKeys) {
    const rawProp = props[key]
    if (!rawProp || typeof rawProp !== 'object') continue
    const prop = rawProp as Record<string, unknown>
    const isArray = prop.type === 'array'
    const itemsObj =
      isArray && prop.items && typeof prop.items === 'object'
        ? (prop.items as Record<string, unknown>)
        : prop
    const rawType = typeof itemsObj.type === 'string' ? itemsObj.type : 'string'
    const fieldType: ParsedElicitationQuestion['fieldType'] =
      rawType === 'boolean' || rawType === 'integer' || rawType === 'number' ? rawType : 'string'

    const options: Array<{ id: string; label: string; wireValue: string }> = []
    const oneOf = Array.isArray(itemsObj.oneOf)
      ? itemsObj.oneOf
      : Array.isArray(itemsObj.anyOf)
        ? itemsObj.anyOf
        : null
    if (oneOf) {
      oneOf.forEach((choice: unknown, idx: number) => {
        if (!choice || typeof choice !== 'object') return
        const c = choice as Record<string, unknown>
        const wireValue =
          typeof c.const === 'string'
            ? c.const
            : Array.isArray(c.enum) && typeof c.enum[0] === 'string'
              ? c.enum[0]
              : ''
        if (!wireValue) return
        const label =
          typeof c.title === 'string' && c.title.trim() ? c.title.trim() : wireValue
        options.push({ id: `opt_${idx}_${wireValue.slice(0, 32)}`, label, wireValue })
      })
    } else if (Array.isArray(itemsObj.enum)) {
      const enumNames = Array.isArray(itemsObj.enumNames) ? itemsObj.enumNames : []
      itemsObj.enum.forEach((val: unknown, idx: number) => {
        if (typeof val !== 'string' || !val) return
        const named = typeof enumNames[idx] === 'string' ? String(enumNames[idx]).trim() : ''
        options.push({ id: `opt_${idx}_${val.slice(0, 32)}`, label: named || val, wireValue: val })
      })
    }

    const otherKey = otherKeys.has(`${key}_other`) ? `${key}_other` : undefined
    const title =
      (typeof prop.title === 'string' && prop.title.trim()) ||
      (typeof prop.description === 'string' && prop.description.trim()) ||
      (typeof params.message === 'string' && params.message.trim()) ||
      '请回答以下问题'
    const description =
      typeof prop.description === 'string' && prop.description.trim() && prop.description.trim() !== title
        ? prop.description.trim()
        : undefined

    const rawMinItems = typeof prop.minItems === 'number' && Number.isFinite(prop.minItems)
      ? Math.max(0, Math.floor(prop.minItems))
      : undefined
    out.push({
      fieldKey: key,
      otherFieldKey: otherKey,
      title,
      description,
      isArray,
      ...(rawMinItems !== undefined ? { minItems: rawMinItems } : {}),
      fieldType,
      options,
    })
  }
  return out
}

function extractAcpTextContent(update: Record<string, unknown>): string | undefined {
  if (typeof update.text === 'string') return update.text
  const content = update.content
  if (content && typeof content === 'object' && !Array.isArray(content)) {
    const c = content as Record<string, unknown>
    if (typeof c.text === 'string') return c.text
  }
  return undefined
}

function extractAcpLocations(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  const out: string[] = []
  for (const loc of raw) {
    if (loc && typeof loc === 'object' && typeof (loc as { path?: unknown }).path === 'string') {
      out.push((loc as { path: string }).path)
    }
  }
  return out
}

function extractAcpToolOutput(update: Record<string, unknown>): string | undefined {
  if (Array.isArray(update.content)) {
    const parts: string[] = []
    for (const item of update.content) {
      if (!item || typeof item !== 'object') continue
      const obj = item as Record<string, unknown>
      if (obj.type === 'diff') {
        const diffPath = typeof obj.path === 'string' ? obj.path : 'file'
        const newText = typeof obj.newText === 'string' ? obj.newText : ''
        const oldText = typeof obj.oldText === 'string' ? obj.oldText : ''
        const summaryLines = newText ? newText.split('\n').length : 0
        const removedLines = oldText ? oldText.split('\n').length : 0
        parts.push(`已更新 ${diffPath} (+${summaryLines} / -${removedLines} 行)`)
      } else if (typeof obj.text === 'string') {
        parts.push(obj.text)
      } else if (obj.content && typeof obj.content === 'object') {
        const inner = obj.content as Record<string, unknown>
        if (typeof inner.text === 'string') parts.push(inner.text)
      }
    }
    if (parts.length > 0) return parts.join('\n')
  }
  if (update.rawOutput !== undefined) {
    return typeof update.rawOutput === 'string'
      ? update.rawOutput
      : JSON.stringify(update.rawOutput)
  }
  return undefined
}

function normalizeAcpToolKind(
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

function normalizeAcpToolStatus(raw: string): 'pending' | 'in_progress' | 'completed' | 'failed' {
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

// ---------------------------------------------------------------------------
// Legacy / Fallback AGY `stream-json` Session
// ---------------------------------------------------------------------------

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

  async prompt(
    turnId: string,
    text: string,
    images?: StagedPromptImage[],
    files?: StagedPromptFile[],
  ): Promise<void> {
    this.currentMsgId = `msg_${turnId}`
    this.emittedDeltaInTurn = false
    this.emittedPlanStepKeys.clear()
    this.init.emit({ type: 'session.state', state: 'running' })

    const effectiveContent = buildTerminalImagePrompt(images ?? [], text, files ?? [])

    try {
      const desiredKey = this.resolveCurrentSpec().key
      if (!this.isAlive() || this.spawnedSpecKey !== desiredKey) {
        await this.stopWorkerAndWait()
        await this.spawnWorker()
      }
      if (!this.proc) throw new Error('AGY stream session is not running')
      this.stderrTail = ''

      await new Promise<void>((resolve, reject) => {
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
    // Legacy stream-json permissions are managed via AGY session flags
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
    const eventName = event.event
    if (eventName === 'init') {
      const initConvId = extractAgyEventConversationId(event)
      if (initConvId) {
        if (!this.conversationId) {
          this.conversationId = initConvId
          this.init.onNativeSessionLinked?.(initConvId)
        } else if (initConvId !== this.conversationId) {
          return
        }
      }
      this.pendingTurn?.onActivity()
      return
    }
    if (eventName === 'command_result' && event.command && typeof event.command === 'object') {
      const cmd = event.command as Record<string, unknown>
      const text =
        typeof cmd.output === 'string'
          ? cmd.output
          : cmd.data !== undefined
            ? JSON.stringify(cmd.data, null, 2)
            : ''
      if (text.trim()) {
        this.emittedDeltaInTurn = true
        this.init.emit({
          type: 'message.assistant.delta',
          messageId: this.currentMsgId,
          text,
        })
      }
      return
    }
    if (eventName === 'step_update' && event.step_update && typeof event.step_update === 'object') {
      const step = event.step_update as Record<string, unknown>
      const stepConvId = extractAgyEventConversationId(step, event)
      if (stepConvId) {
        if (!this.conversationId) {
          this.conversationId = stepConvId
          this.init.onNativeSessionLinked?.(stepConvId)
        } else if (stepConvId !== this.conversationId) {
          // Strict conversation isolation: never mix another conversation's or subagent's
          // `update_steps` / `plan` / deltas / tools into this session's timeline.
          return
        }
      }
      if (
        step.is_subagent === true ||
        step.isSubagent === true ||
        (typeof step.parent_conversation_id === 'string' && step.parent_conversation_id.trim().length > 0) ||
        (typeof step.parentConversationId === 'string' && step.parentConversationId.trim().length > 0)
      ) {
        return
      }
      this.pendingTurn?.onActivity()
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
      } else if (stepType === 'error_message') {
        const msg = typeof step.error === 'string' ? step.error : typeof step.text_delta === 'string' ? step.text_delta : ''
        if (msg.trim()) {
          this.init.emit({ type: 'error', message: msg.trim() })
        }
      } else if (stepType === 'plan' || stepType === 'steps') {
        const entries = parsePlanEntriesFromRaw(step.steps ?? step.entries ?? step.plan)
        if (entries.length > 0) {
          this.init.emit({ type: 'plan.updated', entries })
        }
      } else if (stepType === 'subagent') {
        const subInfo = (step.subagent_info as Record<string, unknown>) ?? {}
        const callId = `agy_sub_${step.step_index ?? Date.now()}`
        const rawInput = Object.keys(subInfo).length > 0 ? JSON.stringify(subInfo) : undefined
        if (step.state === 'ACTIVE') {
          this.init.emit({
            type: 'tool.call',
            toolCallId: callId,
            title: 'Task',
            kind: 'other',
            status: 'in_progress',
            input: rawInput,
          })
          this.init.onSubagentsHint?.()
        } else if (step.state === 'DONE') {
          this.init.emit({
            type: 'tool.update',
            toolCallId: callId,
            status: step.status === 'ERROR' ? 'failed' : 'completed',
            output:
              typeof subInfo.output === 'string'
                ? subInfo.output
                : typeof subInfo.conversation_id === 'string'
                  ? JSON.stringify({ conversationId: subInfo.conversation_id })
                  : undefined,
          })
          this.init.onSubagentsHint?.()
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
      const resConvId = extractAgyEventConversationId(res, event)
      if (resConvId) {
        if (!this.conversationId) {
          this.conversationId = resConvId
          this.init.onNativeSessionLinked?.(resConvId)
        } else if (resConvId !== this.conversationId) {
          return
        }
      }
      this.pendingTurn?.onActivity()
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
        const outputTokens =
          typeof u.output_tokens === 'number'
            ? u.output_tokens + (typeof u.thinking_tokens === 'number' ? u.thinking_tokens : 0)
            : undefined
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

function extractAgyEventConversationId(
  primary: Record<string, unknown>,
  secondary?: Record<string, unknown>,
): string | undefined {
  const direct = primary.conversation_id ?? primary.conversationId
  if (typeof direct === 'string' && direct.trim()) return direct.trim()
  const alt = secondary?.conversation_id ?? secondary?.conversationId
  if (typeof alt === 'string' && alt.trim()) return alt.trim()
  return undefined
}

function summarizeAgyStderr(raw: string): string {
  const lines = raw
    .split(/\r?\n/)
    .map(l => l.trim())
    .filter(l => l.length > 0 && !/^warning:/i.test(l))
  return lines.slice(-4).join('\n').slice(0, 500)
}
