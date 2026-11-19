import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { defaultDataDir } from '../platform/index.ts'

interface FeishuSecretConfig {
  feishuAppId: string
  feishuAppSecret: string
  allowedOpenIds?: string[]
  allowedEmail?: string
  hmacSecret: string
}

export interface FeishuUserIdentity {
  openId: string
  name: string
  email: string
}

export interface FeishuDeviceChallenge {
  sid: string
  userCode: string
  verifyUrl: string
  applinkUrl: string
  expiresIn: number
  interval: number
}

export type FeishuPollResult =
  | { status: 'pending' }
  | { status: 'expired'; detail?: string }
  | { status: 'forbidden'; name: string; openId: string }
  | { status: 'ok'; user: FeishuUserIdentity; accessToken: string }

const MAX_AGE_MS = 30 * 24 * 3600 * 1000

export class FeishuGateAuth {
  private readonly secret: FeishuSecretConfig | null
  private readonly allowedOpenIds: Set<string>
  private readonly allowedEmail: string
  private readonly hmacKey: Buffer
  private readonly challenges = new Map<string, { deviceCode: string; expiresAt: number }>()

  constructor() {
    this.secret = this.loadSecret()
    this.allowedOpenIds = new Set(this.secret?.allowedOpenIds ?? [])
    this.allowedEmail = (this.secret?.allowedEmail ?? '').toLowerCase()
    this.hmacKey = this.secret?.hmacSecret
      ? Buffer.from(this.secret.hmacSecret, 'base64url')
      : crypto.randomBytes(32)
  }

  isConfigured(): boolean {
    return Boolean(this.secret?.feishuAppId && this.secret?.feishuAppSecret)
  }

  signSessionToken(user: FeishuUserIdentity): string {
    const exp = Date.now() + MAX_AGE_MS
    const body = Buffer.from(
      JSON.stringify({
        v: 2,
        sub: user.openId,
        name: user.name,
        email: user.email,
        exp,
      }),
      'utf8',
    ).toString('base64url')
    const sig = crypto.createHmac('sha256', this.hmacKey).update(body).digest('base64url')
    return `qprc_fs.${body}.${sig}`
  }

  verifySessionToken(token: string): FeishuUserIdentity | null {
    if (!token.startsWith('qprc_fs.')) return null
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const [, body, sig] = parts
    if (!body || !sig) return null
    const expectedSig = crypto.createHmac('sha256', this.hmacKey).update(body).digest('base64url')
    const aBuf = Buffer.from(sig, 'utf8')
    const bBuf = Buffer.from(expectedSig, 'utf8')
    if (aBuf.length !== bBuf.length || !crypto.timingSafeEqual(aBuf, bBuf)) {
      return null
    }
    try {
      const payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8')) as {
        v?: number
        sub?: string
        name?: string
        email?: string
        exp?: number
      }
      if (payload.v !== 2 || typeof payload.exp !== 'number' || payload.exp <= Date.now()) {
        return null
      }
      const openId = payload.sub ?? ''
      const email = (payload.email ?? '').toLowerCase()
      const allowed = this.allowedOpenIds.has(openId) || (this.allowedEmail && email === this.allowedEmail)
      if (!allowed) return null
      return {
        openId,
        name: payload.name ?? 'Owner',
        email,
      }
    } catch {
      return null
    }
  }

  async startDeviceFlow(): Promise<FeishuDeviceChallenge> {
    if (!this.secret) {
      throw new Error('Feishu App credentials not found in ~/.qprc/feishu-secret.json')
    }
    this.cleanChallenges()
    const basic = Buffer.from(`${this.secret.feishuAppId}:${this.secret.feishuAppSecret}`).toString('base64')
    const body = new URLSearchParams({
      client_id: this.secret.feishuAppId,
      scope: 'offline_access',
    }).toString()

    const resp = await fetch('https://accounts.feishu.cn/oauth/v1/device_authorization', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        Authorization: `Basic ${basic}`,
      },
      body,
    })
    if (!resp.ok) {
      throw new Error(`Feishu device_authorization failed (${resp.status})`)
    }
    const data = (await resp.json()) as {
      device_code: string
      user_code: string
      verification_uri: string
      verification_uri_complete?: string
      expires_in?: number
      interval?: number
    }
    const sid = crypto.randomBytes(18).toString('base64url')
    const expiresIn = Number(data.expires_in ?? 300)
    this.challenges.set(sid, {
      deviceCode: data.device_code,
      expiresAt: Date.now() + expiresIn * 1000,
    })
    const verifyUrl = data.verification_uri_complete || data.verification_uri
    const applinkUrl = `https://applink.feishu.cn/client/web_url/open?mode=window&url=${encodeURIComponent(verifyUrl)}`
    return {
      sid,
      userCode: data.user_code,
      verifyUrl,
      applinkUrl,
      expiresIn,
      interval: Number(data.interval ?? 2),
    }
  }

  async pollDeviceFlow(sid: string): Promise<FeishuPollResult> {
    if (!this.secret) {
      return { status: 'expired', detail: 'Feishu secret not configured' }
    }
    this.cleanChallenges()
    const record = this.challenges.get(sid)
    if (!record) return { status: 'expired' }

    const body = new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
      device_code: record.deviceCode,
      client_id: this.secret.feishuAppId,
      client_secret: this.secret.feishuAppSecret,
    }).toString()

    const tokenResp = await fetch('https://open.feishu.cn/open-apis/authen/v2/oauth/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    })
    const tokenData = (await tokenResp.json()) as {
      error?: string
      error_description?: string
      access_token?: string
    }
    if (tokenData.error === 'authorization_pending' || tokenData.error === 'slow_down') {
      return { status: 'pending' }
    }
    if (!tokenData.access_token) {
      this.challenges.delete(sid)
      return { status: 'expired', detail: tokenData.error_description || tokenData.error || 'token_error' }
    }

    const userResp = await fetch('https://open.feishu.cn/open-apis/authen/v1/user_info', {
      headers: { Authorization: `Bearer ${tokenData.access_token}` },
    })
    const userJson = (await userResp.json()) as {
      data?: { open_id?: string; name?: string; email?: string; enterprise_email?: string }
    }
    this.challenges.delete(sid)

    const u = userJson.data ?? {}
    const openId = u.open_id ?? ''
    const email = (u.email || u.enterprise_email || '').toLowerCase()
    const allowed = this.allowedOpenIds.has(openId) || (this.allowedEmail && email === this.allowedEmail)

    if (!allowed) {
      console.warn(`[qprc-feishu-gate] REJECTED unauthorized Feishu user: name=${u.name} open_id=${openId} email=${email}`)
      return {
        status: 'forbidden',
        name: u.name || '未知用户',
        openId,
      }
    }

    const user: FeishuUserIdentity = {
      openId,
      name: u.name || 'Owner',
      email: email || this.allowedEmail,
    }
    const accessToken = this.signSessionToken(user)
    console.log(`[qprc-feishu-gate] ACCEPTED owner Feishu login: name=${user.name} open_id=${user.openId}`)
    return {
      status: 'ok',
      user,
      accessToken,
    }
  }

  private cleanChallenges(): void {
    const now = Date.now()
    for (const [k, v] of this.challenges) {
      if (v.expiresAt <= now) this.challenges.delete(k)
    }
  }

  private loadSecret(): FeishuSecretConfig | null {
    const candidates = [
      process.env.LEWORK_OAUTH_SECRET_FILE,
      path.join(defaultDataDir(), 'feishu-secret.json'),
      path.join(os.homedir(), '.lework', 'oauth-secret.json'),
    ].filter((v): v is string => Boolean(v))
    for (const p of candidates) {
      if (fs.existsSync(p)) {
        try {
          return JSON.parse(fs.readFileSync(p, 'utf8')) as FeishuSecretConfig
        } catch {
          continue
        }
      }
    }
    return null
  }
}
