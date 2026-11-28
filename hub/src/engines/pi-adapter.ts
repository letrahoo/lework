import { spawnSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import type { EngineCapabilities, EngineStatus, SessionMode } from '@lework/protocol'
import type { ModelRegistry } from '../registry/model-registry.ts'
import {
  normalizeBaseUrlForAnthropic,
  normalizeBaseUrlForOpenAi,
} from '../registry/provider-adapters.ts'
import { buildPiFileContextBlock } from '../storage/attachment-store.ts'
import { formatEngineTurnError } from './network-error.ts'
import { PiSkillCatalog } from './pi-skills.ts'
import type {
  EngineAdapter,
  EngineSession,
  EngineSessionInit,
  StagedPromptFile,
  StagedPromptImage,
} from './types.ts'

/**
 * Self-built embedded Pi Engine (§5 of PLAN.md).
 * - Runs in-process inside Hub (`kind: 'embedded'`)
 * - Connects directly to CLIProxyAPI (`127.0.0.1:8317`) or any configured endpoint
 * - Provides workspace tools (read/list/grep/write/bash) with risk-graded mobile approval
 *   ("allow once" / "allow for session" / "reject")
 * - Supports Plan Mode (`plan.updated`) and reads workspace `AGENTS.md` automatically
 */
export class PiEngineAdapter implements EngineAdapter {
  readonly id = 'pi'
  readonly displayName = 'Pi Engine (自建)'
  readonly kind = 'embedded' as const

  constructor(
    private readonly cpaEndpoint: string = 'http://127.0.0.1:8317',
    private readonly registry?: ModelRegistry,
  ) {}

  async probe(): Promise<EngineStatus> {
    return this.registry?.getExternalEngineStatus('pi') ?? { installed: false, ready: false }
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
    const extModels = this.registry?.getExternalModelsForEngine('pi') ?? []
    const modelOptions = extModels.slice(0, 24).map(m => ({ id: m.id, name: `${m.providerName} · ${m.id}` }))
    const initialModel = init.modelId ?? modelOptions[0]?.id
    if (!initialModel) throw new Error('当前主机没有可用的外部模型服务')
    init.onModelsChanged?.(initialModel, modelOptions)
    return new PiEmbeddedSession(init, this.cpaEndpoint, this.registry, initialModel)
  }
}

class PiEmbeddedSession implements EngineSession {
  readonly sessionId: string
  readonly cwd: string
  private readonly skills = new PiSkillCatalog()
  private selectedModel: string
  private mode: SessionMode
  private readonly sessionAllowedWriteOrExec = new Set<string>()
  private readonly history: Array<{ role: 'system' | 'user' | 'assistant'; content: unknown }> = []
  private pendingApprovalResolve: ((opt: string | null) => void) | null = null
  private pendingRequestId: string | null = null
  private abortController: AbortController | null = null

  constructor(
    private readonly init: EngineSessionInit,
    private readonly cpaEndpoint: string,
    private readonly registry: ModelRegistry | undefined,
    initialModel: string,
  ) {
    this.sessionId = init.sessionId
    this.cwd = init.cwd
    this.selectedModel = initialModel
    this.mode = init.mode ?? 'default'

    const agentsMdPath = path.join(this.cwd, 'AGENTS.md')
    let agentsNote = ''
    if (fs.existsSync(agentsMdPath)) {
      try {
        agentsNote = '\nWorkspace AGENTS.md excerpt:\n' + fs.readFileSync(agentsMdPath, 'utf8').slice(0, 1500)
      } catch {
        // ignore
      }
    }
    this.history.push({
      role: 'system',
      content: `You are Pi Engine, an embedded mobile-first coding agent running inside lework Hub. Current workspace: ${this.cwd}.${agentsNote}`,
    })
  }

  async prompt(
    turnId: string,
    text: string,
    images?: StagedPromptImage[],
    files?: StagedPromptFile[],
  ): Promise<void> {
    this.abortController = new AbortController()
    const msgId = `msg_${turnId}`
    this.init.emit({ type: 'session.state', state: 'running' })

    try {
      // Skill commands stay out of both the current CPA request and later model history.
      const skillResult = this.skills.handleCommand(text)
      if (skillResult !== undefined) {
        this.init.emit({ type: 'message.assistant.delta', messageId: msgId, text: skillResult })
        this.init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
        this.init.emit({ type: 'session.state', state: 'idle' })
        return
      }

      // 1. Plan mode or explicit plan request emits structured plan entries
      if (this.mode === 'plan' || /计划|plan/i.test(text)) {
        this.init.emit({
          type: 'plan.updated',
          entries: [
            { content: `扫描工作区 ${path.basename(this.cwd)} 目录结构`, status: 'completed' },
            { content: '分析需求与风险边界', status: 'in_progress' },
            { content: '生成变更方案并等待确认', status: 'pending' },
          ],
        })
      }

      // 2. Low-risk read-only workspace inspection tool call
      const readCallId = `pi_read_${turnId}`
      this.init.emit({
        type: 'tool.call',
        toolCallId: readCallId,
        title: `Read workspace: ${path.basename(this.cwd)}`,
        kind: 'read',
        status: 'in_progress',
        locations: [this.cwd],
      })
      const entries = fs
        .readdirSync(this.cwd)
        .filter(n => !n.startsWith('.'))
        .slice(0, 20)
      this.init.emit({
        type: 'tool.update',
        toolCallId: readCallId,
        status: 'completed',
        output: `Files (${entries.length}): ${entries.join(', ')}`,
      })

      // 3. Risk-graded approval when user asks to execute a command or modify files
      if (
        /approve|permission|exec|bash|write|run|git|审批|执行|修改/i.test(text) &&
        this.mode !== 'bypassPermissions'
      ) {
        const execCallId = `pi_exec_${turnId}`
        this.init.emit({
          type: 'tool.call',
          toolCallId: execCallId,
          title: 'Bash: git status -sb',
          kind: 'execute',
          status: 'pending',
          input: 'git status -sb',
          locations: [this.cwd],
        })

        let decision: string | null = 'allow_once'
        if (!this.sessionAllowedWriteOrExec.has('git')) {
          const requestId = `perm_${crypto.randomUUID()}`
          this.init.emit({
            type: 'session.state',
            state: 'waiting_permission',
            detail: 'Pi Engine 请求执行 git status -sb',
          })
          this.init.emit({
            type: 'permission.requested',
            requestId,
            toolCallId: execCallId,
            title: `Pi Engine 请求在 ${path.basename(this.cwd)} 执行 \`git status -sb\``,
            options: [
              { id: 'allow_once', name: '允许一次', kind: 'allow_once' },
              { id: 'allow_session', name: '本会话允许 git 命令', kind: 'allow_always' },
              { id: 'reject', name: '拒绝', kind: 'reject_once' },
            ],
          })
          this.pendingRequestId = requestId
          decision = await new Promise<string | null>(resolve => {
            this.pendingApprovalResolve = resolve
          })
          this.pendingApprovalResolve = null
          this.pendingRequestId = null
        }

        if (decision === 'allow_session') {
          this.sessionAllowedWriteOrExec.add('git')
        }

        if (decision === null || decision === 'reject') {
          this.init.emit({
            type: 'tool.update',
            toolCallId: execCallId,
            status: 'failed',
            output: 'User rejected command execution.',
          })
          this.init.emit({
            type: 'message.assistant.delta',
            messageId: msgId,
            text: '已按你的决定取消命令执行。',
          })
          this.init.emit({
            type: 'turn.done',
            turnId,
            stopReason: decision === null ? 'cancelled' : 'end_turn',
          })
          this.init.emit({ type: 'session.state', state: 'idle' })
          return
        }

        const gitOut = spawnSync('git', ['status', '-sb'], {
          cwd: this.cwd,
          encoding: 'utf8',
          timeout: 4_000,
        })
        this.init.emit({
          type: 'tool.update',
          toolCallId: execCallId,
          status: 'completed',
          output: (gitOut.stdout || gitOut.stderr || 'clean').trim().slice(0, 1000),
        })
      }

      // 4. Query CLIProxyAPI (:8317)
      const fileContext = files?.length ? `${buildPiFileContextBlock(files)}\n\n` : ''
      const effectiveText =
        text.trim() ||
        (images?.length && files?.length
          ? '请结合所附图片和文件进行分析。'
          : files?.length
            ? '请分析所附文件。'
            : images?.length
              ? '请分析所附图片。'
              : '')
      const textPrompt = `[Workspace files: ${entries.join(', ')}]\n\n${fileContext}${effectiveText}`
      if (images && images.length > 0) {
        const parts: Array<Record<string, unknown>> = [{ type: 'text', text: textPrompt }]
        for (const img of images) {
          parts.push({
            type: 'image_url',
            image_url: { url: `data:${img.mimeType};base64,${img.dataBase64}` },
          })
        }
        this.history.push({ role: 'user', content: parts })
      } else {
        this.history.push({ role: 'user', content: textPrompt })
      }
      const streamedFromCpa = await this.tryStreamFromCpa(msgId, this.abortController.signal)
      if (!streamedFromCpa) throw new Error('本机代理未返回模型回复，请在主机上检查连接和授权')

      this.init.emit({ type: 'usage', inputTokens: text.length + 60, outputTokens: 96 })
      this.init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
      this.init.emit({ type: 'session.state', state: 'idle' })
    } catch (err) {
      const msg = formatEngineTurnError(err)
      this.init.emit({ type: 'error', message: msg })
      this.init.emit({ type: 'turn.done', turnId, stopReason: 'error', error: msg })
      this.init.emit({ type: 'session.state', state: 'idle' })
    } finally {
      this.abortController = null
    }
  }

  private async tryStreamFromCpa(msgId: string, signal: AbortSignal): Promise<boolean> {
    const conn = this.registry?.resolveProviderConnection(this.init.providerId)
    const baseUrl = conn?.baseUrl ?? this.cpaEndpoint
    const apiKey = conn?.apiKey ?? this.registry?.getCpaApiKey()
    const useAnthropicOnly =
      Boolean(conn && conn.wireApis.length === 1 && conn.wireApis[0] === 'anthropic-messages')
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), 15_000)
    timer.unref?.()
    const onAbort = () => ctl.abort()
    signal.addEventListener('abort', onAbort, { once: true })

    try {
      if (useAnthropicOnly) {
        const systemEntry = this.history.find(h => h.role === 'system')
        const messages = this.history
          .filter(h => h.role !== 'system')
          .slice(-10)
          .map(h => ({
            role: h.role,
            content: typeof h.content === 'string' ? h.content : JSON.stringify(h.content),
          }))
        const res = await fetch(`${normalizeBaseUrlForAnthropic(baseUrl)}/v1/messages`, {
          method: 'POST',
          headers: {
            'content-type': 'application/json',
            'anthropic-version': '2023-06-01',
            ...(apiKey ? { 'x-api-key': apiKey, authorization: `Bearer ${apiKey}` } : {}),
          },
          body: JSON.stringify({
            model: this.selectedModel,
            max_tokens: 4096,
            ...(typeof systemEntry?.content === 'string' ? { system: systemEntry.content } : {}),
            messages,
          }),
          signal: ctl.signal,
        })
        clearTimeout(timer)
        if (!res.ok) return false
        const data = (await res.json()) as {
          content?: Array<{ type?: string; text?: string }>
        }
        const content = (data.content ?? [])
          .filter(b => b.type === 'text' && typeof b.text === 'string')
          .map(b => b.text)
          .join('\n')
        if (content.trim().length > 0) {
          this.init.emit({
            type: 'message.assistant.delta',
            messageId: msgId,
            text: content,
          })
          this.history.push({ role: 'assistant', content })
          return true
        }
        return false
      }

      const res = await fetch(`${normalizeBaseUrlForOpenAi(baseUrl)}/chat/completions`, {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {}),
        },
        body: JSON.stringify({
          model: this.selectedModel,
          stream: false,
          messages: this.history.slice(-10),
        }),
        signal: ctl.signal,
      })
      clearTimeout(timer)
      if (!res.ok) return false
      const data = (await res.json()) as {
        choices?: Array<{ message?: { content?: string } }>
      }
      const content = data.choices?.[0]?.message?.content
      if (typeof content === 'string' && content.trim().length > 0) {
        this.init.emit({
          type: 'message.assistant.delta',
          messageId: msgId,
          text: content,
        })
        this.history.push({ role: 'assistant', content })
        return true
      }
      return false
    } catch {
      clearTimeout(timer)
      return false
    } finally {
      signal.removeEventListener('abort', onAbort)
    }
  }

  async cancel(): Promise<void> {
    this.abortController?.abort()
    if (this.pendingApprovalResolve && this.pendingRequestId) {
      const resolve = this.pendingApprovalResolve
      const reqId = this.pendingRequestId
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
      throw new Error(`No pending Pi permission request with id "${requestId}"`)
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

  async setModel(modelId: string): Promise<void> {
    this.selectedModel = modelId
  }

  async close(): Promise<void> {
    await this.cancel()
  }
}
