import { spawnSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { ErrorCode, type CheckpointInfo, type FsEntry } from '@qprc/protocol'
import { HubRpcError, resolveAllowedWorkspacePath } from '../security/path-guard.ts'

const MAX_READ_BYTES = 64 * 1024
const MAX_DIR_ENTRIES = 120

export function listWorkspaceDirectory(dirPath: string, workspaceRoots: string[]): {
  path: string
  entries: FsEntry[]
} {
  const realDir = resolveAllowedWorkspacePath(dirPath, workspaceRoots)
  const rawEntries = fs.readdirSync(realDir, { withFileTypes: true })
  const entries: FsEntry[] = []

  for (const d of rawEntries) {
    if (d.name === '.git' || d.name === 'node_modules' || d.name === '.DS_Store') continue
    const fullPath = path.join(realDir, d.name)
    try {
      const st = fs.statSync(fullPath)
      entries.push({
        name: d.name,
        path: fullPath,
        isDirectory: st.isDirectory(),
        size: st.isDirectory() ? 0 : st.size,
      })
    } catch {
      // skip broken symlinks
    }
    if (entries.length >= MAX_DIR_ENTRIES) break
  }

  entries.sort((a, b) => {
    if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1
    return a.name.localeCompare(b.name)
  })

  return { path: realDir, entries }
}

export function readWorkspaceFile(filePath: string, workspaceRoots: string[]): {
  path: string
  content: string
  truncated: boolean
} {
  if (!filePath || !path.isAbsolute(filePath) || filePath.split(/[/\\]+/).includes('..')) {
    throw new HubRpcError(ErrorCode.PathNotAllowed, 'Invalid file path.')
  }
  const parentDir = path.dirname(filePath)
  resolveAllowedWorkspacePath(parentDir, workspaceRoots)

  let realFile: string
  try {
    realFile = fs.realpathSync(filePath)
    // Also verify real parent is inside workspaceRoots
    resolveAllowedWorkspacePath(path.dirname(realFile), workspaceRoots)
    const st = fs.statSync(realFile)
    if (!st.isFile()) {
      throw new HubRpcError(ErrorCode.InvalidParams, 'Target path is not a regular file.')
    }
    const fd = fs.openSync(realFile, 'r')
    try {
      const len = Math.min(st.size, MAX_READ_BYTES)
      const buf = Buffer.alloc(len)
      fs.readSync(fd, buf, 0, len, 0)
      return {
        path: realFile,
        content: buf.toString('utf8'),
        truncated: st.size > MAX_READ_BYTES,
      }
    } finally {
      fs.closeSync(fd)
    }
  } catch (err) {
    if (err instanceof HubRpcError) throw err
    throw new HubRpcError(ErrorCode.NotFound, `Unable to read file: ${filePath}`)
  }
}

export class CheckpointManager {
  private readonly checkpointsBySession = new Map<string, CheckpointInfo[]>()

  list(sessionId: string): CheckpointInfo[] {
    return [...(this.checkpointsBySession.get(sessionId) ?? [])]
  }

  create(sessionId: string, cwd: string, label?: string): CheckpointInfo {
    const headRes = spawnSync('git', ['rev-parse', '--short', 'HEAD'], {
      cwd,
      encoding: 'utf8',
      timeout: 3_000,
    })
    const headSha = headRes.status === 0 ? headRes.stdout.trim() : 'working-tree'

    // Create a git stash commit object without modifying the working tree (`git stash create`)
    const stashRes = spawnSync('git', ['stash', 'create', label ?? `qprc-${sessionId}`], {
      cwd,
      encoding: 'utf8',
      timeout: 5_000,
    })
    const stashSha = stashRes.status === 0 && stashRes.stdout.trim() ? stashRes.stdout.trim().slice(0, 12) : headSha

    const info: CheckpointInfo = {
      id: `ckpt_${crypto.randomBytes(5).toString('hex')}`,
      sessionId,
      label: label?.trim() || `Checkpoint (${stashSha || 'clean'})`,
      commitSha: stashSha || 'HEAD',
      createdAt: Date.now(),
    }
    const list = this.checkpointsBySession.get(sessionId) ?? []
    list.unshift(info)
    this.checkpointsBySession.set(sessionId, list.slice(0, 30))
    return info
  }

  restore(sessionId: string, cwd: string, checkpointId: string): void {
    const list = this.checkpointsBySession.get(sessionId) ?? []
    const found = list.find(c => c.id === checkpointId)
    if (!found) {
      throw new HubRpcError(ErrorCode.NotFound, `Checkpoint "${checkpointId}" not found`)
    }
    if (found.commitSha && found.commitSha !== 'HEAD' && found.commitSha !== 'working-tree') {
      spawnSync('git', ['checkout', found.commitSha, '--', '.'], {
        cwd,
        encoding: 'utf8',
        timeout: 5_000,
      })
    }
  }
}
