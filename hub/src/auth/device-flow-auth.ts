import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { defaultDataDir } from '../platform/index.ts'
import { FeishuProvider } from './oauth/feishu-provider.ts'
import {
  createOAuthProvider,
  registerOAuthProvider,
  type OAuthDeviceProvider,
  type OAuthProviderConfig,
} from './oauth/provider.ts'
import { Rfc8628Provider } from './oauth/rfc8628-provider.ts'
import { IssuedTokenStore } from './token-store.ts'

registerOAuthProvider('feishu', cfg => new FeishuProvider(cfg))
registerOAuthProvider('rfc8628', cfg => new Rfc8628Provider(cfg))

export interface AuthUserIdentity {
  subject: string
  name: string
  email: string
}

export interface DeviceLoginChallenge {
  sid: string
  userCode: string
  verifyUrl: string
  /** Deep link into the IdP's native app when available, else same as verifyUrl. */
  appLaunchUrl: string
  expiresIn: number
  interval: number
}

export type DeviceLoginPollResult =
  | { status: 'pending' }
  | { status: 'expired'; detail?: string }
  | { status: 'forbidden'; name: string }
  | { status: 'ok'; user: AuthUserIdentity; accessToken: string }

const TOKEN_PREFIX = 'lework_oa.'
const MAX_AGE_MS = 14 * 24 * 3600 * 1000
const MAX_PENDING_CHALLENGES = 16
const START_RATE_WINDOW_MS = 60_000
const START_RATE_MAX = 6

/**
 * Optional "sign in with your IdP" for the phone, on top of the static access token.
 *
 * Config: `<dataDir>/oauth.json` (or `$LEWORK_OAUTH_SECRET_FILE`), see `OAuthProviderConfig`.
 * The legacy `feishu-secret.json` layout (`feishuAppId`, `feishuAppSecret`, `allowedOpenIds`,
 * `allowedEmail`) is still accepted.
 */
export class DeviceFlowAuth {
  private readonly cfg: OAuthProviderConfig | null
  private readonly provider: OAuthDeviceProvider | null
  private readonly allowedSubjects: Set<string>
  private readonly allowedEmails: Set<string>
  private readonly hmacKey: Buffer
  private readonly challenges = new Map<string, { deviceCode: string; expiresAt: number; codeChallenge: string }>()
  private readonly startTimestamps: number[] = []
  private readonly hubId: string
  readonly issued: IssuedTokenStore

  constructor(opts: { hubId?: string; issued?: IssuedTokenStore; config?: OAuthProviderConfig | null } = {}) {
    this.hubId = opts.hubId ?? 'hub'
    this.issued = opts.issued ?? new IssuedTokenStore()
    this.cfg = opts.config !== undefined ? opts.config : loadOAuthConfig()
    let provider: OAuthDeviceProvider | null = null
    if (this.cfg) {
      try {
        provider = createOAuthProvider(this.cfg)
      } catch (err) {
        console.warn(`[lework-auth] OAuth disabled: ${err instanceof Error ? err.message : err}`)
      }
    }
    this.provider = provider
    this.allowedSubjects = new Set(this.cfg?.allowedSubjects ?? [])
    this.allowedEmails = new Set((this.cfg?.allowedEmails ?? []).map(e => e.toLowerCase()))
    this.hmacKey = this.cfg?.hmacSecret ? Buffer.from(this.cfg.hmacSecret, 'base64url') : crypto.randomBytes(32)
  }

  isConfigured(): boolean {
    return this.provider !== null
  }

  /** Public description for the login screen. */
  describe(): { enabled: boolean; provider?: string; displayName?: string } {
    return this.provider
      ? { enabled: true, provider: this.provider.id, displayName: this.provider.displayName }
      : { enabled: false }
  }

  private isAllowed(subject: string, email: string | undefined): boolean {
    return this.allowedSubjects.has(subject) || (!!email && this.allowedEmails.has(email.toLowerCase()))
  }

  signSessionToken(user: AuthUserIdentity): string {
    const exp = Date.now() + MAX_AGE_MS
    const body = Buffer.from(
      JSON.stringify({
        v: 4,
        aud: this.hubId,
        jti: crypto.randomBytes(9).toString('base64url'),
        sub: user.subject,
        name: user.name,
        email: user.email,
        exp,
      }),
      'utf8',
    ).toString('base64url')
    const sig = crypto.createHmac('sha256', this.hmacKey).update(body).digest('base64url')
    const token = `${TOKEN_PREFIX}${body}.${sig}`
    this.issued.record(token, user.subject, user.name, exp)
    return token
  }

  verifySessionToken(token: string): AuthUserIdentity | null {
    if (!token.startsWith(TOKEN_PREFIX)) return null
    const [body, sig, ...rest] = token.slice(TOKEN_PREFIX.length).split('.')
    if (!body || !sig || rest.length > 0) return null
    const expected = crypto.createHmac('sha256', this.hmacKey).update(body).digest('base64url')
    const a = Buffer.from(sig, 'utf8')
    const b = Buffer.from(expected, 'utf8')
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null
    try {
      const p = JSON.parse(Buffer.from(body, 'base64url').toString('utf8')) as {
        v?: number
        aud?: string
        sub?: string
        name?: string
        email?: string
        exp?: number
      }
      if (p.v !== 4 || typeof p.exp !== 'number' || p.exp <= Date.now()) return null
      if (p.aud !== this.hubId) return null
      // Revocation: only tokens still present in the issued-token registry are accepted
      if (!this.issued.isActive(token)) return null
      const subject = p.sub ?? ''
      const email = (p.email ?? '').toLowerCase()
      if (!this.isAllowed(subject, email)) return null
      return { subject, name: p.name ?? 'Owner', email }
    } catch {
      return null
    }
  }

  /**
   * @param codeChallenge hex SHA-256 of a client-held random verifier (PKCE-style). The issued
   *   token is only released to a poller that presents the matching verifier.
   */
  async startDeviceFlow(codeChallenge: string): Promise<DeviceLoginChallenge> {
    if (!this.provider) throw new Error('OAuth device flow is not configured on this Hub')
    if (!/^[0-9a-f]{64}$/.test(codeChallenge)) throw new Error('Invalid code challenge')
    this.cleanChallenges()
    const now = Date.now()
    while (this.startTimestamps.length > 0 && this.startTimestamps[0]! < now - START_RATE_WINDOW_MS) {
      this.startTimestamps.shift()
    }
    if (this.startTimestamps.length >= START_RATE_MAX || this.challenges.size >= MAX_PENDING_CHALLENGES) {
      throw new Error('Too many login attempts, please retry in a minute')
    }
    this.startTimestamps.push(now)

    const d = await this.provider.startDeviceAuthorization()
    const sid = crypto.randomBytes(18).toString('base64url')
    this.challenges.set(sid, { deviceCode: d.deviceCode, expiresAt: now + d.expiresIn * 1000, codeChallenge })
    const verifyUrl = d.verificationUriComplete || d.verificationUri
    return {
      sid,
      userCode: d.userCode,
      verifyUrl,
      appLaunchUrl: d.appLaunchUri ?? verifyUrl,
      expiresIn: d.expiresIn,
      interval: d.interval,
    }
  }

  async pollDeviceFlow(sid: string, codeVerifier: string): Promise<DeviceLoginPollResult> {
    if (!this.provider) return { status: 'expired', detail: 'not configured' }
    this.cleanChallenges()
    const record = this.challenges.get(sid)
    if (!record) return { status: 'expired' }
    const presented = crypto.createHash('sha256').update(codeVerifier ?? '', 'utf8').digest()
    const expected = Buffer.from(record.codeChallenge, 'hex')
    if (presented.length !== expected.length || !crypto.timingSafeEqual(presented, expected)) {
      return { status: 'expired', detail: 'verifier mismatch' }
    }

    const tok = await this.provider.pollToken(record.deviceCode)
    if (tok.status === 'pending') return { status: 'pending' }
    if (tok.status === 'error') {
      this.challenges.delete(sid)
      return { status: 'expired', detail: tok.detail }
    }
    const u = await this.provider.fetchUser(tok.accessToken)
    this.challenges.delete(sid)

    if (!this.isAllowed(u.subject, u.verifiedEmail)) {
      console.warn(`[lework-auth] REJECTED unauthorized ${this.provider.id} user: name=${u.name} sub=${u.subject}`)
      return { status: 'forbidden', name: u.name || 'unknown' }
    }
    const user: AuthUserIdentity = { subject: u.subject, name: u.name || 'Owner', email: u.verifiedEmail ?? '' }
    const accessToken = this.signSessionToken(user)
    console.log(`[lework-auth] ACCEPTED ${this.provider.id} login: name=${user.name} sub=${user.subject}`)
    return { status: 'ok', user, accessToken }
  }

  private cleanChallenges(): void {
    const now = Date.now()
    for (const [k, v] of this.challenges) {
      if (v.expiresAt <= now) this.challenges.delete(k)
    }
  }
}

/** Loads `oauth.json` (or the legacy Feishu secret file) and normalises it. */
export function loadOAuthConfig(): OAuthProviderConfig | null {
  const dir = defaultDataDir()
  const candidates = [
    process.env.LEWORK_OAUTH_SECRET_FILE,
    path.join(dir, 'oauth.json'),
    path.join(dir, 'feishu-secret.json'),
  ].filter((v): v is string => Boolean(v))
  for (const p of candidates) {
    if (!fs.existsSync(p)) continue
    try {
      if ((fs.statSync(p).mode & 0o077) !== 0) {
        try {
          fs.chmodSync(p, 0o600)
          console.warn(`[lework-auth] Tightened permissions of ${p} to 0600`)
        } catch {
          console.warn(`[lework-auth] WARNING: ${p} is readable by other users; chmod 600 it`)
        }
      }
      const raw = JSON.parse(fs.readFileSync(p, 'utf8')) as Record<string, unknown>
      return normalizeOAuthConfig(raw)
    } catch {
      continue
    }
  }
  return null
}

export function normalizeOAuthConfig(raw: Record<string, unknown>): OAuthProviderConfig | null {
  const str = (k: string) => (typeof raw[k] === 'string' ? (raw[k] as string) : undefined)
  const arr = (k: string) => (Array.isArray(raw[k]) ? (raw[k] as unknown[]).filter((x): x is string => typeof x === 'string') : [])
  // Legacy Feishu layout
  if (str('feishuAppId')) {
    return {
      provider: 'feishu',
      clientId: str('feishuAppId')!,
      clientSecret: str('feishuAppSecret'),
      allowedSubjects: arr('allowedOpenIds'),
      allowedEmails: str('allowedEmail') ? [str('allowedEmail')!] : [],
      hmacSecret: str('hmacSecret'),
    }
  }
  if (!str('provider') || !str('clientId')) return null
  return {
    provider: str('provider')!,
    clientId: str('clientId')!,
    clientSecret: str('clientSecret'),
    scope: str('scope'),
    displayName: str('displayName'),
    deviceAuthorizationEndpoint: str('deviceAuthorizationEndpoint'),
    tokenEndpoint: str('tokenEndpoint'),
    userinfoEndpoint: str('userinfoEndpoint'),
    allowedSubjects: arr('allowedSubjects'),
    allowedEmails: arr('allowedEmails'),
    hmacSecret: str('hmacSecret'),
  }
}
