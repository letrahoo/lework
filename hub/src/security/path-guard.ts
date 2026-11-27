import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { ErrorCode } from '@lework/protocol'

export class HubRpcError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(message)
    this.name = 'HubRpcError'
  }
}

function expandRealPaths(dirs: string[]): string[] {
  const out: string[] = []
  for (const d of dirs) {
    out.push(d)
    try {
      out.push(fs.realpathSync(d))
    } catch {
      // not present
    }
  }
  return [...new Set(out)]
}

/**
 * Hard credential / secret vault directories that must never be exposed even for direct
 * read-only file link previews.
 */
function credentialVaultDirs(): string[] {
  const home = os.homedir()
  return expandRealPaths([
    process.env.LEWORK_HOME ?? path.join(home, '.lework'),
    path.join(home, '.lework'),
    path.join(home, '.hermes', 'secrets'),
    path.join(home, '.ssh'),
    path.join(home, '.gnupg'),
    path.join(home, '.aws'),
    path.join(home, '.azure'),
    path.join(home, '.kube'),
    path.join(home, '.docker'),
    path.join(home, '.config', 'gcloud'),
    path.join(home, '.config', 'gh'),
    path.join(home, '.password-store'),
    path.join(home, 'Library', 'Keychains'),
    path.join(home, 'Library', 'Cookies'),
  ])
}

/**
 * Credential / secret locations that must never be listed, read, or used as a session cwd from
 * the phone, even if they live inside a configured workspace root (e.g. when `$HOME` is a root).
 * Checked against the *real* (symlink-resolved) path.
 */
function sensitiveDirs(): string[] {
  const home = os.homedir()
  return expandRealPaths([
    ...credentialVaultDirs(),
    path.join(home, '.claude'),
    path.join(home, '.codex'),
    path.join(home, '.gemini'),
    path.join(home, '.hermes'),
    path.join(home, '.cursor'),
    path.join(home, '.config', 'Cursor'),
    path.join(home, '.config', 'opencode'),
    path.join(home, '.config', 'claude'),
  ])
}

const SENSITIVE_FILE_RE =
  /^(id_(rsa|dsa|ecdsa|ed25519)(_sk)?|\.netrc|\.npmrc|\.pypirc|\.git-credentials|\.credentials\.json|credentials(\.(json|ya?ml|toml|ini|xml|txt))?|auth\.json|oauth_creds\.json|\.env(\.(?!example$|sample$|template$)[\w.-]+)?)$|\.(pem|key|p12|pfx|keystore|jks|kdbx)$/i

function matchesSensitiveSecretFile(realPath: string): boolean {
  const secretFile = process.env.LEWORK_OAUTH_SECRET_FILE
  if (secretFile && path.resolve(secretFile) === realPath) return true
  const cpaConfigFile = process.env.LEWORK_CPA_CONFIG
  if (cpaConfigFile && path.resolve(cpaConfigFile) === realPath) return true
  return SENSITIVE_FILE_RE.test(path.basename(realPath))
}

export function isSensitivePath(realPath: string): boolean {
  for (const dir of sensitiveDirs()) {
    if (realPath === dir || realPath.startsWith(dir + path.sep)) return true
  }
  return matchesSensitiveSecretFile(realPath)
}

export function assertNotSensitive(realPath: string): void {
  if (isSensitivePath(realPath)) {
    throw new HubRpcError(ErrorCode.PathNotAllowed, 'Access to credential / secret files is blocked.')
  }
}

/**
 * Lighter secret-only check for `fs.read` file preview: allows reading arbitrary non-secret
 * host files (including docs/skills/plans inside `~/.claude`, `~/.gemini`, `~/.codex`, etc.)
 * while still safeguarding hard credential vaults and secret files (`.env`, private keys,
 * `.credentials.json`, `auth.json`, `oauth_creds.json`, `~/.ssh`, `~/.lework`).
 */
export function isSensitiveReadTarget(realPath: string): boolean {
  for (const dir of credentialVaultDirs()) {
    if (realPath === dir || realPath.startsWith(dir + path.sep)) return true
  }
  return matchesSensitiveSecretFile(realPath)
}

export function assertNotSensitiveReadTarget(realPath: string): void {
  if (isSensitiveReadTarget(realPath)) {
    throw new HubRpcError(ErrorCode.PathNotAllowed, 'Access to credential / secret files is blocked.')
  }
}

/**
 * Verifies that `candidate` is an existing directory lexically and physically
 * (`realpath`) inside at least one of the configured `workspaceRoots`.
 * Rejects `..` segments and symlink escapes.
 */
export function resolveAllowedWorkspacePath(candidate: string, workspaceRoots: string[]): string {
  if (!candidate || typeof candidate !== 'string' || !path.isAbsolute(candidate)) {
    throw new HubRpcError(ErrorCode.PathNotAllowed, 'Workspace path must be an absolute path.')
  }
  const segments = candidate.split(/[/\\]+/)
  if (segments.includes('..')) {
    throw new HubRpcError(ErrorCode.PathNotAllowed, 'Workspace path must not contain ".." traversal.')
  }

  let realCandidate: string
  try {
    const st = fs.statSync(candidate)
    if (!st.isDirectory()) {
      throw new HubRpcError(ErrorCode.PathNotAllowed, 'Workspace path is not a directory.')
    }
    realCandidate = fs.realpathSync(candidate)
  } catch (err) {
    if (err instanceof HubRpcError) throw err
    throw new HubRpcError(ErrorCode.PathNotAllowed, `Workspace directory does not exist: ${candidate}`)
  }

  for (const root of workspaceRoots) {
    try {
      const realRoot = fs.realpathSync(root)
      if (realCandidate === realRoot || realCandidate.startsWith(realRoot + path.sep)) {
        assertNotSensitive(realCandidate)
        return realCandidate
      }
    } catch {
      continue
    }
  }

  throw new HubRpcError(
    ErrorCode.PathNotAllowed,
    `Path "${realCandidate}" is outside allowed workspace roots.`,
  )
}
