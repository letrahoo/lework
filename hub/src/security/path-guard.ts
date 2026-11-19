import fs from 'node:fs'
import path from 'node:path'
import { ErrorCode } from '@qprc/protocol'

export class HubRpcError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(message)
    this.name = 'HubRpcError'
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
