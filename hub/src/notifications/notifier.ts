import crypto from 'node:crypto'
import type { AgentEvent, HubInfo, SessionMeta } from '@lework/protocol'

export type NotificationProviderType = 'bark' | 'feishu' | 'telegram' | 'webhook'

export interface NotificationConfig {
  enabled: boolean
  provider: NotificationProviderType
  /** Target endpoint URL (Bark server/key URL, Feishu bot webhook URL, or custom HTTP POST URL). */
  url?: string
  /** Optional signing secret (Feishu bot HMAC secret or generic webhook X-Lework-Signature secret). */
  secret?: string
  /** Telegram Bot API token & target chatId when provider === 'telegram'. */
  telegramBotToken?: string
  telegramChatId?: string
  /**
   * Grace window (ms) before pushing an unanswered permission request when at least one client is
   * subscribed on WebSocket. When zero clients are subscribed, notifications fire after 1.5s.
   */
  approvalDelayMs?: number
  /**
   * Minimum turn duration (ms) required to push `turn.done` when a client is actively subscribed.
   * When zero clients are subscribed (phone locked in pocket) or on error, notifications always fire.
   */
  minTurnDurationMs?: number
}

export interface NotificationPayload {
  kind: 'approval_needed' | 'turn_completed' | 'turn_failed' | 'test'
  title: string
  body: string
  deepLink: string
  hub: Pick<HubInfo, 'id' | 'name'>
  sessionId?: string
  sessionTitle?: string
  timestamp: number
}

export interface NotificationDispatcherOptions {
  getConfig: () => NotificationConfig | undefined
  getHubInfo: () => HubInfo
  getSessionMeta: (sessionId: string) => SessionMeta | undefined
  getActiveSubscriberCount: (sessionId: string) => number
  fetchImpl?: typeof fetch
}

export function buildSessionDeepLink(sessionId: string, hubId: string): string {
  return `lework://session?id=${encodeURIComponent(sessionId)}&hubId=${encodeURIComponent(hubId)}`
}

export class NotificationDispatcher {
  private readonly pendingApprovalTimers = new Map<string, NodeJS.Timeout>()
  private readonly turnStartedAtBySession = new Map<string, number>()
  private readonly notifiedRequestIds = new Set<string>()
  private readonly fetchFn: typeof fetch

  constructor(private readonly opts: NotificationDispatcherOptions) {
    this.fetchFn = opts.fetchImpl ?? fetch
  }

  dispose(): void {
    for (const timer of this.pendingApprovalTimers.values()) {
      clearTimeout(timer)
    }
    this.pendingApprovalTimers.clear()
    this.turnStartedAtBySession.clear()
    this.notifiedRequestIds.clear()
  }

  onEvent(event: AgentEvent): void {
    const cfg = this.opts.getConfig()
    if (!cfg?.enabled) return

    switch (event.type) {
      case 'message.user': {
        this.turnStartedAtBySession.set(event.sessionId, event.ts || Date.now())
        break
      }
      case 'session.state': {
        if (event.state === 'running' && !this.turnStartedAtBySession.has(event.sessionId)) {
          this.turnStartedAtBySession.set(event.sessionId, event.ts || Date.now())
        }
        if (event.state === 'idle' || event.state === 'closed') {
          this.clearApprovalTimer(event.sessionId)
        }
        break
      }
      case 'permission.requested': {
        if (this.notifiedRequestIds.has(event.requestId)) return
        this.clearApprovalTimer(event.sessionId)
        const subscribers = this.opts.getActiveSubscriberCount(event.sessionId)
        const delayMs =
          subscribers > 0 ? Math.max(0, cfg.approvalDelayMs ?? 12_000) : Math.min(cfg.approvalDelayMs ?? 1_500, 1_500)
        const timer = setTimeout(() => {
          this.pendingApprovalTimers.delete(event.sessionId)
          if (this.notifiedRequestIds.has(event.requestId)) return
          this.notifiedRequestIds.add(event.requestId)
          if (this.notifiedRequestIds.size > 512) {
            const oldest = this.notifiedRequestIds.values().next().value
            if (oldest) this.notifiedRequestIds.delete(oldest)
          }
          const meta = this.opts.getSessionMeta(event.sessionId)
          const hub = this.opts.getHubInfo()
          const sessionTitle = meta?.title || event.sessionId
          const cleanPermTitle = event.title.replace(/\s+/g, ' ').trim().slice(0, 140)
          void this.dispatch(cfg, {
            kind: 'approval_needed',
            title: `等待确认 · ${sessionTitle}`,
            body: `${cleanPermTitle}\n主机：${hub.name}`,
            deepLink: buildSessionDeepLink(event.sessionId, hub.id),
            hub: { id: hub.id, name: hub.name },
            sessionId: event.sessionId,
            sessionTitle,
            timestamp: Date.now(),
          })
        }, delayMs)
        timer.unref?.()
        this.pendingApprovalTimers.set(event.sessionId, timer)
        break
      }
      case 'permission.resolved': {
        this.clearApprovalTimer(event.sessionId)
        break
      }
      case 'turn.done': {
        this.clearApprovalTimer(event.sessionId)
        const startedAt = this.turnStartedAtBySession.get(event.sessionId)
        this.turnStartedAtBySession.delete(event.sessionId)
        if (event.stopReason === 'cancelled') return

        const hub = this.opts.getHubInfo()
        const meta = this.opts.getSessionMeta(event.sessionId)
        const sessionTitle = meta?.title || event.sessionId
        const subscribers = this.opts.getActiveSubscriberCount(event.sessionId)
        const durationMs = startedAt ? Math.max(0, (event.ts || Date.now()) - startedAt) : 0
        const minDurationMs = cfg.minTurnDurationMs ?? 15_000

        if (event.stopReason === 'error') {
          void this.dispatch(cfg, {
            kind: 'turn_failed',
            title: `执行异常 · ${sessionTitle}`,
            body: `${(event.error || '任务执行中断').slice(0, 140)}\n主机：${hub.name}`,
            deepLink: buildSessionDeepLink(event.sessionId, hub.id),
            hub: { id: hub.id, name: hub.name },
            sessionId: event.sessionId,
            sessionTitle,
            timestamp: Date.now(),
          })
          return
        }

        if (subscribers === 0 || durationMs >= minDurationMs) {
          const secLabel = durationMs >= 1000 ? `（耗时 ${Math.round(durationMs / 1000)}s）` : ''
          void this.dispatch(cfg, {
            kind: 'turn_completed',
            title: `任务已完成 · ${sessionTitle}`,
            body: `当前轮次已执行完毕${secLabel}，点击直接查看结果。\n主机：${hub.name}`,
            deepLink: buildSessionDeepLink(event.sessionId, hub.id),
            hub: { id: hub.id, name: hub.name },
            sessionId: event.sessionId,
            sessionTitle,
            timestamp: Date.now(),
          })
        }
        break
      }
    }
  }

  async sendTestNotification(overrideCfg?: NotificationConfig): Promise<{ ok: boolean; error?: string }> {
    const cfg = overrideCfg ?? this.opts.getConfig()
    if (!cfg || !cfg.enabled) {
      return { ok: false, error: 'Notifications are not enabled in ~/.lework/config.json' }
    }
    const hub = this.opts.getHubInfo()
    return await this.dispatch(cfg, {
      kind: 'test',
      title: `Lework 通知测试 · ${hub.name}`,
      body: '离线审批与长任务完成推送已连通，点击可唤起 Lework 客户端。',
      deepLink: `lework://session?hubId=${encodeURIComponent(hub.id)}`,
      hub: { id: hub.id, name: hub.name },
      timestamp: Date.now(),
    })
  }

  private clearApprovalTimer(sessionId: string): void {
    const prev = this.pendingApprovalTimers.get(sessionId)
    if (prev) {
      clearTimeout(prev)
      this.pendingApprovalTimers.delete(sessionId)
    }
  }

  async dispatch(cfg: NotificationConfig, payload: NotificationPayload): Promise<{ ok: boolean; error?: string }> {
    try {
      switch (cfg.provider) {
        case 'bark':
          return await this.sendBark(cfg, payload)
        case 'feishu':
          return await this.sendFeishu(cfg, payload)
        case 'telegram':
          return await this.sendTelegram(cfg, payload)
        case 'webhook':
          return await this.sendGenericWebhook(cfg, payload)
        default:
          return { ok: false, error: `Unsupported provider: ${String(cfg.provider)}` }
      }
    } catch (err) {
      return { ok: false, error: err instanceof Error ? err.message : String(err) }
    }
  }

  private async sendBark(
    cfg: NotificationConfig,
    payload: NotificationPayload,
  ): Promise<{ ok: boolean; error?: string }> {
    const base = (cfg.url ?? '').trim().replace(/\/+$/, '')
    if (!base) return { ok: false, error: 'Missing Bark URL' }
    const endpoint = base.endsWith('/push') ? base : `${base}`
    const body = {
      title: payload.title,
      body: payload.body,
      group: 'Lework',
      url: payload.deepLink,
      level: payload.kind === 'approval_needed' || payload.kind === 'turn_failed' ? 'timeSensitive' : 'active',
    }
    const res = await this.fetchFn(endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json; charset=utf-8' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(8_000),
    })
    return res.ok ? { ok: true } : { ok: false, error: `Bark HTTP ${res.status}` }
  }

  private async sendFeishu(
    cfg: NotificationConfig,
    payload: NotificationPayload,
  ): Promise<{ ok: boolean; error?: string }> {
    const url = (cfg.url ?? '').trim()
    if (!url) return { ok: false, error: 'Missing Feishu webhook URL' }
    const tsSec = String(Math.floor(payload.timestamp / 1000))
    let signFields: Record<string, string> = {}
    if (cfg.secret) {
      const stringToSign = `${tsSec}\n${cfg.secret}`
      const sign = crypto.createHmac('sha256', stringToSign).update('').digest('base64')
      signFields = { timestamp: tsSec, sign }
    }
    const headerTemplate =
      payload.kind === 'approval_needed'
        ? 'orange'
        : payload.kind === 'turn_failed'
          ? 'red'
          : 'green'
    const cardBody = {
      ...signFields,
      msg_type: 'interactive',
      card: {
        header: {
          template: headerTemplate,
          title: { tag: 'plain_text', content: payload.title },
        },
        elements: [
          {
            tag: 'div',
            text: {
              tag: 'lark_md',
              content: `${payload.body}\n\n[在手机端打开该会话](${payload.deepLink})`,
            },
          },
        ],
      },
    }
    const res = await this.fetchFn(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json; charset=utf-8' },
      body: JSON.stringify(cardBody),
      signal: AbortSignal.timeout(8_000),
    })
    return res.ok ? { ok: true } : { ok: false, error: `Feishu HTTP ${res.status}` }
  }

  private async sendTelegram(
    cfg: NotificationConfig,
    payload: NotificationPayload,
  ): Promise<{ ok: boolean; error?: string }> {
    const token = (cfg.telegramBotToken ?? '').trim()
    const chatId = (cfg.telegramChatId ?? '').trim()
    if (!token || !chatId) return { ok: false, error: 'Missing telegramBotToken or telegramChatId' }
    const endpoint = `https://api.telegram.org/bot${token}/sendMessage`
    const text = `${payload.title}\n\n${payload.body}\n\n${payload.deepLink}`
    const res = await this.fetchFn(endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ chat_id: chatId, text }),
      signal: AbortSignal.timeout(8_000),
    })
    return res.ok ? { ok: true } : { ok: false, error: `Telegram HTTP ${res.status}` }
  }

  private async sendGenericWebhook(
    cfg: NotificationConfig,
    payload: NotificationPayload,
  ): Promise<{ ok: boolean; error?: string }> {
    const url = (cfg.url ?? '').trim()
    if (!url) return { ok: false, error: 'Missing webhook URL' }
    const raw = JSON.stringify(payload)
    const headers: Record<string, string> = {
      'content-type': 'application/json; charset=utf-8',
    }
    if (cfg.secret) {
      headers['x-lework-signature'] = crypto.createHmac('sha256', cfg.secret).update(raw).digest('hex')
    }
    const res = await this.fetchFn(url, {
      method: 'POST',
      headers,
      body: raw,
      signal: AbortSignal.timeout(8_000),
    })
    return res.ok ? { ok: true } : { ok: false, error: `Webhook HTTP ${res.status}` }
  }
}
