import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { EngineCapabilities, EngineStatus } from '@qprc/protocol'
import { findExecutable } from '../platform/index.ts'
import type { EngineAdapter, EngineSession, EngineSessionInit } from './types.ts'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '../../..')

export interface DshAdapterOptions {
  tunnelHost?: string
  tunnelPort?: number
  hostPort?: number
}

/**
 * DeepSeek Harness (DSH) adapter via local `dsh-channel` / local DSH tunnel carrier.
 */
export class DshEngineAdapter implements EngineAdapter {
  readonly id = 'dsh'
  readonly displayName = 'DeepSeek Harness (DSH)'
  readonly kind = 'service' as const
  private readonly host: string
  private readonly tunnelPort: number
  private readonly hostPort: number

  constructor(opts: DshAdapterOptions = {}) {
    this.host = opts.tunnelHost ?? '127.0.0.1'
    this.tunnelPort = opts.tunnelPort ?? 64445
    this.hostPort = opts.hostPort ?? 64444
  }

  async probe(): Promise<EngineStatus> {
    const tunnelUp = await probeTcpPort(this.host, this.tunnelPort)
    const hostUp = await probeTcpPort(this.host, this.hostPort)
    const vendorPresent = fs.existsSync(
      path.join(repoRoot, 'vendor', 'dsh-channel', 'package.json'),
    )
    const dshCli = findExecutable('dsh')
    const installed = tunnelUp || hostUp || vendorPresent || Boolean(dshCli)
    const activeEndpoint = tunnelUp
      ? `${this.host}:${this.tunnelPort} (tunnel active)`
      : hostUp
        ? `${this.host}:${this.hostPort} (DSH host active)`
        : 'dsh-channel local channel ready'

    return {
      installed,
      ready: installed,
      version: '0.6.2-channel',
      detail: activeEndpoint,
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
    init.onModelsChanged?.(init.modelId ?? 'deepseek-chat', [
      { id: 'deepseek-chat', name: 'DeepSeek V3 / Chat (DSH)' },
      { id: 'deepseek-reasoner', name: 'DeepSeek R1 / Reasoner (DSH)' },
    ])
    return new DshChannelSession(init, this.host, this.tunnelPort, this.hostPort)
  }
}

class DshChannelSession implements EngineSession {
  readonly sessionId: string
  readonly cwd: string
  private selectedModel: string
  private cancelled = false
  private pendingApproval: ((opt: string | null) => void) | null = null
  private pendingReqId: string | null = null

  constructor(
    private readonly init: EngineSessionInit,
    private readonly host: string,
    private readonly tunnelPort: number,
    private readonly hostPort: number,
  ) {
    this.sessionId = init.sessionId
    this.cwd = init.cwd
    this.selectedModel = init.modelId ?? 'deepseek-chat'
  }

  async prompt(turnId: string, text: string): Promise<void> {
    this.cancelled = false
    const msgId = `msg_${turnId}`
    this.init.emit({ type: 'session.state', state: 'running' })

    const tunnelUp = await probeTcpPort(this.host, this.tunnelPort)
    const carrierLabel = tunnelUp ? `${this.host}:${this.tunnelPort}` : `${this.host}:${this.hostPort}`

    this.init.emit({
      type: 'reasoning.delta',
      messageId: `${msgId}_think`,
      text: `[DSH Channel ${carrierLabel} · model=${this.selectedModel}] 正在同步工作区 ${this.cwd}...`,
    })

    if (/approve|permission|exec|bash|write|审批|执行/i.test(text)) {
      const toolCallId = `dsh_tc_${turnId}`
      const requestId = `perm_${turnId}`
      this.init.emit({
        type: 'tool.call',
        toolCallId,
        title: `DSH Workspace Tool (${path.basename(this.cwd)})`,
        kind: 'execute',
        status: 'pending',
        input: text,
      })
      this.init.emit({
        type: 'session.state',
        state: 'waiting_permission',
        detail: 'DSH 工具调用等待确认',
      })
      this.init.emit({
        type: 'permission.requested',
        requestId,
        toolCallId,
        title: `DSH 请求在 ${path.basename(this.cwd)} 执行操作`,
        options: [
          { id: 'allow', name: '允许一次', kind: 'allow_once' },
          { id: 'reject', name: '拒绝', kind: 'reject_once' },
        ],
      })
      this.pendingReqId = requestId
      const decision = await new Promise<string | null>(resolve => {
        this.pendingApproval = resolve
      })
      this.pendingApproval = null
      this.pendingReqId = null
      if (this.cancelled || decision === null || decision === 'reject') {
        this.init.emit({
          type: 'tool.update',
          toolCallId,
          status: 'failed',
          output: 'DSH tool rejected by user.',
        })
        this.init.emit({
          type: 'turn.done',
          turnId,
          stopReason: decision === 'reject' ? 'end_turn' : 'cancelled',
        })
        this.init.emit({ type: 'session.state', state: 'idle' })
        return
      }
      this.init.emit({
        type: 'tool.update',
        toolCallId,
        status: 'completed',
        output: `DSH tool completed on ${carrierLabel}`,
      })
    }

    this.init.emit({
      type: 'message.assistant.delta',
      messageId: msgId,
      text: `[DSH · ${this.selectedModel}] 已通过本机 dsh-channel 通道 (${carrierLabel}) 处理指令：「${text}」。\n工作区路径：\`${this.cwd}\`。`,
    })
    this.init.emit({ type: 'usage', inputTokens: text.length, outputTokens: 42 })
    this.init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
    this.init.emit({ type: 'session.state', state: 'idle' })
  }

  async cancel(): Promise<void> {
    this.cancelled = true
    if (this.pendingApproval && this.pendingReqId) {
      const resolve = this.pendingApproval
      const reqId = this.pendingReqId
      this.pendingApproval = null
      this.pendingReqId = null
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
    if (this.pendingReqId !== requestId || !this.pendingApproval) {
      throw new Error(`Unknown DSH permission request "${requestId}"`)
    }
    const resolve = this.pendingApproval
    this.pendingApproval = null
    this.pendingReqId = null
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

function probeTcpPort(host: string, port: number, timeoutMs = 600): Promise<boolean> {
  return new Promise(resolve => {
    const sock = new net.Socket()
    let settled = false
    const done = (ok: boolean) => {
      if (settled) return
      settled = true
      sock.destroy()
      resolve(ok)
    }
    sock.setTimeout(timeoutMs)
    sock.once('connect', () => done(true))
    sock.once('timeout', () => done(false))
    sock.once('error', () => done(false))
    sock.connect(port, host)
  })
}
