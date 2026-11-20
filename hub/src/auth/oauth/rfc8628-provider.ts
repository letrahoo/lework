import type {
  DeviceAuthorization,
  OAuthDeviceProvider,
  OAuthProviderConfig,
  OAuthUser,
  TokenPollResult,
} from './provider.ts'

/** Generic RFC 8628 device flow against any IdP (e.g. company SSO / Keycloak / Okta / Entra). */
export class Rfc8628Provider implements OAuthDeviceProvider {
  readonly id: string = 'rfc8628'
  readonly displayName: string

  constructor(protected readonly cfg: OAuthProviderConfig) {
    this.displayName = cfg.displayName ?? 'SSO'
    if (cfg.provider === 'rfc8628') {
      for (const k of ['deviceAuthorizationEndpoint', 'tokenEndpoint', 'userinfoEndpoint'] as const) {
        if (!cfg[k]) throw new Error(`OAuth config missing ${k}`)
      }
    }
  }

  protected clientAuthHeaders(): Record<string, string> {
    if (!this.cfg.clientSecret) return {}
    const basic = Buffer.from(`${this.cfg.clientId}:${this.cfg.clientSecret}`).toString('base64')
    return { Authorization: `Basic ${basic}` }
  }

  async startDeviceAuthorization(): Promise<DeviceAuthorization> {
    const resp = await fetch(this.cfg.deviceAuthorizationEndpoint!, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...this.clientAuthHeaders() },
      body: new URLSearchParams({ client_id: this.cfg.clientId, scope: this.cfg.scope ?? 'openid profile email' }),
    })
    if (!resp.ok) throw new Error(`device_authorization failed (${resp.status})`)
    const d = (await resp.json()) as {
      device_code: string
      user_code: string
      verification_uri: string
      verification_uri_complete?: string
      expires_in?: number
      interval?: number
    }
    return {
      deviceCode: d.device_code,
      userCode: d.user_code,
      verificationUri: d.verification_uri,
      verificationUriComplete: d.verification_uri_complete,
      expiresIn: Number(d.expires_in ?? 300),
      interval: Number(d.interval ?? 5),
    }
  }

  async pollToken(deviceCode: string): Promise<TokenPollResult> {
    const body = new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
      device_code: deviceCode,
      client_id: this.cfg.clientId,
      ...(this.cfg.clientSecret ? { client_secret: this.cfg.clientSecret } : {}),
    })
    const resp = await fetch(this.cfg.tokenEndpoint!, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    })
    const t = (await resp.json()) as { error?: string; error_description?: string; access_token?: string }
    if (t.error === 'authorization_pending' || t.error === 'slow_down') return { status: 'pending' }
    if (!t.access_token) return { status: 'error', detail: t.error_description || t.error || 'token_error' }
    return { status: 'ok', accessToken: t.access_token }
  }

  async fetchUser(accessToken: string): Promise<OAuthUser> {
    const resp = await fetch(this.cfg.userinfoEndpoint!, { headers: { Authorization: `Bearer ${accessToken}` } })
    const u = (await resp.json()) as { sub?: string; name?: string; email?: string; email_verified?: boolean }
    return {
      subject: u.sub ?? '',
      name: u.name ?? 'Owner',
      verifiedEmail: u.email_verified ? u.email?.toLowerCase() : undefined,
    }
  }
}
