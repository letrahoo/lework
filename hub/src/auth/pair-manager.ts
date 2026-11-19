import crypto from 'node:crypto'

const PAIR_CODE_TTL_MS = 5 * 60 * 1000
const MAX_ACTIVE_PAIR_CODES = 16

interface PendingPairTicket {
  codeHash: Buffer
  expiresAt: number
}

export interface QrPairingPayload {
  v: 2
  hubId: string
  name: string
  role: string
  /** Ordered candidate HTTPS endpoints (LAN, mDNS `.local`, Tailscale IP, Relays). */
  urls: string[]
  /** Back-compat primary URL for v2 parsers. */
  url: string
  /** Full 64-hex SHA-256 fingerprint of the Hub's self-signed TLS leaf certificate. */
  fp: string
  /** Short-lived one-time pairing code (redeemed via `POST /v1/auth/pair`). */
  pairCode?: string
  /** Optional fallback static token when `hub pair` is invoked while `hubd` is offline. */
  token?: string
}

/**
 * Manages short-lived, single-use QR pairing codes (`POST /v1/auth/pair`).
 *
 * Scanning a QR code redeems the one-time `pairCode` for a per-device revocable token in
 * `IssuedTokenStore`, so screenshots of old QR codes expire within 5 minutes and never expose
 * the root Hub configuration token.
 */
export class PairingTicketManager {
  private readonly tickets = new Map<string, PendingPairTicket>()

  issueTicket(): { pairCode: string; expiresInSec: number } {
    this.prune()
    if (this.tickets.size >= MAX_ACTIVE_PAIR_CODES) {
      const oldestKey = this.tickets.keys().next().value
      if (oldestKey) this.tickets.delete(oldestKey)
    }
    const id = crypto.randomBytes(6).toString('hex')
    const secret = crypto.randomBytes(18).toString('base64url')
    const pairCode = `lwp_${id}_${secret}`
    const codeHash = crypto.createHash('sha256').update(pairCode, 'utf8').digest()
    this.tickets.set(id, {
      codeHash,
      expiresAt: Date.now() + PAIR_CODE_TTL_MS,
    })
    return { pairCode, expiresInSec: Math.floor(PAIR_CODE_TTL_MS / 1000) }
  }

  consumeTicket(pairCode: string): boolean {
    this.prune()
    const m = /^lwp_([0-9a-f]{12})_[A-Za-z0-9_-]{16,40}$/.exec(pairCode.trim())
    if (!m) return false
    const id = m[1]!
    const entry = this.tickets.get(id)
    if (!entry) return false
    // Single-use: delete immediately on first attempt to prevent brute-force / replay
    this.tickets.delete(id)
    if (entry.expiresAt <= Date.now()) return false
    const candidateHash = crypto.createHash('sha256').update(pairCode.trim(), 'utf8').digest()
    return (
      candidateHash.length === entry.codeHash.length &&
      crypto.timingSafeEqual(candidateHash, entry.codeHash)
    )
  }

  private prune(): void {
    const now = Date.now()
    for (const [k, v] of this.tickets) {
      if (v.expiresAt <= now) this.tickets.delete(k)
    }
  }
}

export function encodePairingDeepLink(payload: QrPairingPayload): string {
  const b64 = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url')
  return `lework://pair?d=${b64}`
}
