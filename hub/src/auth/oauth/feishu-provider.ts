import type { DeviceAuthorization, OAuthProviderConfig, OAuthUser } from './provider.ts'
import { Rfc8628Provider } from './rfc8628-provider.ts'

/** Feishu / Lark device flow (RFC 8628 with Feishu-specific endpoints and user info shape). */
export class FeishuProvider extends Rfc8628Provider {
  override readonly id = 'feishu'

  constructor(cfg: OAuthProviderConfig) {
    super({
      ...cfg,
      displayName: cfg.displayName ?? '飞书',
      scope: cfg.scope ?? 'offline_access',
      deviceAuthorizationEndpoint:
        cfg.deviceAuthorizationEndpoint ?? 'https://accounts.feishu.cn/oauth/v1/device_authorization',
      tokenEndpoint: cfg.tokenEndpoint ?? 'https://open.feishu.cn/open-apis/authen/v2/oauth/token',
      userinfoEndpoint: cfg.userinfoEndpoint ?? 'https://open.feishu.cn/open-apis/authen/v1/user_info',
    })
  }

  override async startDeviceAuthorization(): Promise<DeviceAuthorization> {
    const d = await super.startDeviceAuthorization()
    const target = d.verificationUriComplete || d.verificationUri
    return {
      ...d,
      interval: Math.min(d.interval, 2),
      appLaunchUri: `https://applink.feishu.cn/client/web_url/open?mode=window&url=${encodeURIComponent(target)}`,
    }
  }

  override async fetchUser(accessToken: string): Promise<OAuthUser> {
    const resp = await fetch(this.cfg.userinfoEndpoint!, { headers: { Authorization: `Bearer ${accessToken}` } })
    const j = (await resp.json()) as {
      data?: { open_id?: string; name?: string; enterprise_email?: string }
    }
    const u = j.data ?? {}
    return {
      subject: u.open_id ?? '',
      name: u.name || 'Owner',
      // Only the tenant-verified enterprise email counts; the contact `email` is user-controlled.
      verifiedEmail: u.enterprise_email?.toLowerCase() || undefined,
    }
  }
}
