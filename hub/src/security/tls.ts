import { execFileSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { defaultDataDir } from '../platform/index.ts'

/**
 * Hub TLS identity.
 *
 * Every Hub serves HTTPS/WSS with a long-lived self-signed certificate. Clients do NOT
 * rely on any CA: they pin the SHA-256 fingerprint of the leaf certificate (DER), which they
 * learn either via token-bound attestation (`POST /v1/auth/attest`, see `attestProof`) or from
 * the pairing QR code. Because the relay / SSH tunnel only forwards raw TCP, TLS terminates at
 * the Hub itself, so relays and overlay networks only ever see ciphertext (end-to-end).
 */
export interface HubTlsMaterial {
  key: string
  cert: string
  /** Lowercase hex SHA-256 of the DER-encoded leaf certificate. */
  fingerprint: string
}

const OPENSSL_CANDIDATES = ['/opt/homebrew/bin/openssl', '/usr/local/bin/openssl', '/usr/bin/openssl', 'openssl']

export function certFingerprint(certPem: string): string {
  const x509 = new crypto.X509Certificate(certPem)
  return crypto.createHash('sha256').update(x509.raw).digest('hex')
}

/** Human-readable short form shown in CLI / App for out-of-band comparison. */
export function shortFingerprint(fp: string): string {
  return (fp.slice(0, 16).match(/.{4}/g) ?? []).join('-').toUpperCase()
}

export function loadOrCreateHubTls(opts: { dir?: string; commonName?: string } = {}): HubTlsMaterial {
  const dir = opts.dir ?? path.join(defaultDataDir(), 'tls')
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
  const keyPath = path.join(dir, 'hub-key.pem')
  const certPath = path.join(dir, 'hub-cert.pem')

  if (fs.existsSync(keyPath) && fs.existsSync(certPath)) {
    const key = fs.readFileSync(keyPath, 'utf8')
    const cert = fs.readFileSync(certPath, 'utf8')
    try {
      const x509 = new crypto.X509Certificate(cert)
      const notExpired = new Date(x509.validTo).getTime() > Date.now() + 7 * 24 * 3600 * 1000
      const matches = x509.checkPrivateKey(crypto.createPrivateKey(key))
      if (notExpired && matches) {
        tightenPerms(keyPath)
        return { key, cert, fingerprint: certFingerprint(cert) }
      }
    } catch {
      // fall through and regenerate
    }
  }

  const { privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  const keyPem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString()
  const tmpKey = path.join(dir, `.hub-key.${process.pid}.pem`)
  fs.writeFileSync(tmpKey, keyPem, { mode: 0o600 })
  const cn = (opts.commonName ?? 'lework-hub').replace(/[^A-Za-z0-9._-]/g, '-').slice(0, 60)
  try {
    const opensslBin = findOpenssl()
    const certPem = execFileSync(
      opensslBin,
      ['req', '-new', '-x509', '-key', tmpKey, '-sha256', '-days', '3650', '-subj', `/CN=${cn}/O=Lework`],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
    if (!certPem.includes('BEGIN CERTIFICATE')) {
      throw new Error('openssl did not output a certificate')
    }
    fs.renameSync(tmpKey, keyPath)
    fs.writeFileSync(certPath, certPem, { mode: 0o644 })
    return { key: keyPem, cert: certPem, fingerprint: certFingerprint(certPem) }
  } finally {
    fs.rmSync(tmpKey, { force: true })
  }
}

function findOpenssl(): string {
  for (const c of OPENSSL_CANDIDATES) {
    if (c.includes('/') ? fs.existsSync(c) : true) {
      try {
        execFileSync(c, ['version'], { stdio: 'ignore' })
        return c
      } catch {
        continue
      }
    }
  }
  throw new Error('openssl not found: required once to generate the Hub TLS certificate')
}

function tightenPerms(p: string): void {
  try {
    fs.chmodSync(p, 0o600)
  } catch {
    // ignore
  }
}

// ---------------------------------------------------------------------------
// Token-bound attestation
// ---------------------------------------------------------------------------

/** Public key-id for a bearer token: lets the Hub find which secret to use without the token. */
export function tokenKid(token: string): string {
  return crypto.createHash('sha256').update(`lework-kid\0${token}`).digest('hex').slice(0, 16)
}

/** Attestation key = SHA-256(token). Hub may store only this hash for issued tokens. */
export function tokenAttestKey(token: string): Buffer {
  return crypto.createHash('sha256').update(token, 'utf8').digest()
}

/**
 * proof = HMAC-SHA256(SHA-256(token), "lework-attest-v1\n" + fingerprint + "\n" + nonce)
 *
 * Proves to a client that holds `token` that the TLS endpoint it is talking to (whose leaf
 * certificate hashes to `fingerprint`) is operated by someone who also knows the token — a
 * man-in-the-middle presenting its own certificate cannot produce a valid proof.
 */
export function attestProof(attestKey: Buffer, fingerprint: string, nonce: string): string {
  return crypto
    .createHmac('sha256', attestKey)
    .update(`lework-attest-v1\n${fingerprint.toLowerCase()}\n${nonce}`)
    .digest('hex')
}
