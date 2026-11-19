/**
 * Pluggable OAuth 2.0 Device Authorization Grant (RFC 8628) providers.
 *
 * The Hub only needs three things from an identity provider: start a device authorization,
 * poll for the token, and resolve the signed-in user. Built-in providers:
 *   - `feishu`  — Feishu / Lark (default implementation)
 *   - `rfc8628` — any standards-compliant IdP / company SSO configured purely by endpoints
 * Company-internal builds can add their own via `registerOAuthProvider()`.
 */

export interface OAuthProviderConfig {
  /** Provider id: `feishu` | `rfc8628` | custom registered id. */
  provider: string
  clientId: string
  clientSecret?: string
  scope?: string
  /** Shown on the phone login button, e.g. "飞书" / "Company SSO". */
  displayName?: string
  /** Generic RFC 8628 endpoints (required for `rfc8628`). */
  deviceAuthorizationEndpoint?: string
  tokenEndpoint?: string
  userinfoEndpoint?: string
  /** Allowlist of IdP subject ids (Feishu open_id / OIDC `sub`). */
  allowedSubjects?: string[]
  /** Allowlist of IdP-verified emails. */
  allowedEmails?: string[]
  /** HMAC key (base64url) for Hub-issued session tokens. */
  hmacSecret?: string
}

export interface DeviceAuthorization {
  deviceCode: string
  userCode: string
  verificationUri: string
  verificationUriComplete?: string
  /** Optional deep link that opens the IdP's native app (e.g. Feishu applink). */
  appLaunchUri?: string
  expiresIn: number
  interval: number
}

export type TokenPollResult =
  | { status: 'pending' }
  | { status: 'error'; detail: string }
  | { status: 'ok'; accessToken: string }

export interface OAuthUser {
  /** Stable IdP subject id. */
  subject: string
  name: string
  /** Only emails the IdP itself verified (never user-editable contact emails). */
  verifiedEmail?: string
}

export interface OAuthDeviceProvider {
  readonly id: string
  readonly displayName: string
  startDeviceAuthorization(): Promise<DeviceAuthorization>
  pollToken(deviceCode: string): Promise<TokenPollResult>
  fetchUser(accessToken: string): Promise<OAuthUser>
}

export type OAuthProviderFactory = (cfg: OAuthProviderConfig) => OAuthDeviceProvider

const registry = new Map<string, OAuthProviderFactory>()

export function registerOAuthProvider(id: string, factory: OAuthProviderFactory): void {
  registry.set(id, factory)
}

export function createOAuthProvider(cfg: OAuthProviderConfig): OAuthDeviceProvider {
  const factory = registry.get(cfg.provider)
  if (!factory) throw new Error(`Unknown OAuth provider "${cfg.provider}"`)
  return factory(cfg)
}
