import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { defaultDataDir } from '../platform/index.ts'
import { tokenAttestKey, tokenKid } from '../security/tls.ts'

/**
 * Registry of bearer tokens issued by the Hub via OAuth device flow.
 *
 * Only `SHA-256(token)` is persisted (never the token itself), which is enough to
 *   1. revoke individual / all issued tokens (`hub tokens revoke ...`), and
 *   2. answer token-bound TLS attestation (`attestProof` is keyed by SHA-256(token)).
 */
export interface IssuedTokenRecord {
  kid: string
  keyHash: string
  sub: string
  name: string
  issuedAt: number
  expiresAt: number
}

export class IssuedTokenStore {
  private readonly file: string
  private records: IssuedTokenRecord[] = []

  constructor(file = path.join(defaultDataDir(), 'issued-tokens.json')) {
    this.file = file
    this.load()
  }

  record(token: string, sub: string, name: string, expiresAt: number): IssuedTokenRecord {
    this.prune()
    const rec: IssuedTokenRecord = {
      kid: tokenKid(token),
      keyHash: tokenAttestKey(token).toString('hex'),
      sub,
      name,
      issuedAt: Date.now(),
      expiresAt,
    }
    this.records.push(rec)
    this.save()
    return rec
  }

  isActive(token: string): boolean {
    const hash = tokenAttestKey(token)
    const now = Date.now()
    return this.records.some(r => {
      if (r.expiresAt <= now) return false
      const stored = Buffer.from(r.keyHash, 'hex')
      return stored.length === hash.length && crypto.timingSafeEqual(stored, hash)
    })
  }

  attestKeyForKid(kid: string): Buffer | null {
    const now = Date.now()
    const rec = this.records.find(r => r.kid === kid && r.expiresAt > now)
    return rec ? Buffer.from(rec.keyHash, 'hex') : null
  }

  list(): IssuedTokenRecord[] {
    this.prune()
    return [...this.records]
  }

  revoke(kid: string): boolean {
    const before = this.records.length
    this.records = this.records.filter(r => r.kid !== kid)
    this.save()
    return this.records.length !== before
  }

  revokeAll(): number {
    const n = this.records.length
    this.records = []
    this.save()
    return n
  }

  private prune(): void {
    const now = Date.now()
    this.records = this.records.filter(r => r.expiresAt > now)
  }

  private load(): void {
    try {
      const raw = JSON.parse(fs.readFileSync(this.file, 'utf8')) as { tokens?: IssuedTokenRecord[] }
      this.records = Array.isArray(raw.tokens) ? raw.tokens : []
    } catch {
      this.records = []
    }
  }

  private save(): void {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true, mode: 0o700 })
      fs.writeFileSync(this.file, JSON.stringify({ tokens: this.records }, null, 2) + '\n', { mode: 0o600 })
      fs.chmodSync(this.file, 0o600)
    } catch {
      // ignore (read-only test env)
    }
  }
}
