import { spawnSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { ErrorCode, type CheckpointInfo, type FsEntry, type SessionWorktreeInfo } from '@lework/protocol'
import { defaultDataDir } from '../platform/index.ts'
import { HubRpcError, assertNotSensitive, isSensitivePath, resolveAllowedWorkspacePath } from '../security/path-guard.ts'

const MAX_READ_BYTES = 256 * 1024
const MAX_DIFF_BYTES = 96 * 1024
const MAX_DIR_ENTRIES = 120
const MAX_CHANGED_FILES = 150
const MAX_INDEXED_FILES = 4_000
const SKIP_WALK_DIRS = new Set([
  '.git',
  'node_modules',
  '.gradle',
  '.kotlin',
  'build',
  'dist',
  'DerivedData',
  '.next',
  'target',
  '__pycache__',
  '.venv',
])

export function searchWorkspaceFiles(
  dirPath: string,
  workspaceRoots: string[],
  opts: { query?: string; limit?: number } = {},
): { path: string; files: string[] } {
  const realDir = resolveAllowedWorkspacePath(dirPath, workspaceRoots)
  const maxOut = Math.min(Math.max(opts.limit ?? 50, 1), 200)
  const rawQuery = (opts.query ?? '').trim().replace(/^@+/, '').toLowerCase()

  let candidates: string[] = []
  const gitRes = spawnSync(
    'git',
    ['ls-files', '--cached', '--others', '--exclude-standard'],
    {
      cwd: realDir,
      encoding: 'utf8',
      timeout: 3_000,
      maxBuffer: 4 * 1024 * 1024,
    },
  )

  if (gitRes.status === 0 && gitRes.stdout) {
    for (const line of gitRes.stdout.split('\n')) {
      const rel = line.trim()
      if (!rel) continue
      const full = path.join(realDir, rel)
      if (isSensitivePath(full)) continue
      try {
        const realTarget = fs.realpathSync(full)
        if (!realTarget.startsWith(realDir + path.sep) || isSensitivePath(realTarget)) continue
      } catch {
        continue
      }
      candidates.push(rel)
      if (candidates.length >= MAX_INDEXED_FILES) break
    }
  } else {
    // Bounded BFS walk fallback for non-git workspaces
    const queue: string[] = ['']
    while (queue.length > 0 && candidates.length < MAX_INDEXED_FILES) {
      const relDir = queue.shift()!
      const absDir = relDir ? path.join(realDir, relDir) : realDir
      let entries: fs.Dirent[]
      try {
        entries = fs.readdirSync(absDir, { withFileTypes: true })
      } catch {
        continue
      }
      for (const d of entries) {
        if (d.name === '.DS_Store' || SKIP_WALK_DIRS.has(d.name) || d.isSymbolicLink()) continue
        const relPath = relDir ? `${relDir}/${d.name}` : d.name
        const absPath = path.join(realDir, relPath)
        if (isSensitivePath(absPath)) continue
        if (d.isDirectory()) {
          if (relPath.split('/').length <= 6) queue.push(relPath)
        } else if (d.isFile()) {
          candidates.push(relPath)
          if (candidates.length >= MAX_INDEXED_FILES) break
        }
      }
    }
  }

  if (!rawQuery) {
    candidates.sort((a, b) => a.length - b.length || a.localeCompare(b))
    return { path: realDir, files: candidates.slice(0, maxOut) }
  }

  const scored: { rel: string; score: number }[] = []
  for (const rel of candidates) {
    const lower = rel.toLowerCase()
    const base = path.posix.basename(lower)
    let score = -1
    if (base === rawQuery) score = 0
    else if (base.startsWith(rawQuery)) score = 1
    else if (lower.startsWith(rawQuery)) score = 2
    else if (base.includes(rawQuery)) score = 3
    else if (lower.includes(rawQuery)) score = 4
    if (score >= 0) {
      scored.push({ rel, score })
    }
  }

  scored.sort((a, b) => a.score - b.score || a.rel.length - b.rel.length || a.rel.localeCompare(b.rel))
  return { path: realDir, files: scored.slice(0, maxOut).map(s => s.rel) }
}

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
    if (isSensitivePath(fullPath)) continue
    try {
      const realEntry = fs.realpathSync(fullPath)
      if (isSensitivePath(realEntry)) continue
      const st = fs.statSync(realEntry)
      const parentCheck = st.isDirectory() ? realEntry : path.dirname(realEntry)
      resolveAllowedWorkspacePath(parentCheck, workspaceRoots)
      entries.push({
        name: d.name,
        path: fullPath,
        isDirectory: st.isDirectory(),
        size: st.isDirectory() ? 0 : st.size,
      })
    } catch {
      // skip broken or out-of-workspace symlinks
    }
    if (entries.length >= MAX_DIR_ENTRIES) break
  }

  entries.sort((a, b) => {
    if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1
    return a.name.localeCompare(b.name)
  })

  return { path: realDir, entries }
}

/**
 * Returns the read-only root allowlist for `fs.read`.
 * Includes all configured workspace roots plus safe user knowledge directories
 * such as `~/.agent-memory` (Concept Memory cards & dashboards) when present,
 * without adding them to workspace pickers or directory browsing.
 * `assertNotSensitive` still applies to every symlink-resolved target.
 */
export function getReadableFileRoots(workspaceRoots: string[]): string[] {
  const roots = [...workspaceRoots]
  const agentMemoryDir = path.join(os.homedir(), '.agent-memory')
  try {
    if (fs.statSync(agentMemoryDir).isDirectory()) {
      roots.push(agentMemoryDir)
    }
  } catch {
    // ~/.agent-memory does not exist on this host
  }
  return [...new Set(roots)]
}

function expandHomeShortcut(filePath: string): string {
  if (filePath === '~') return os.homedir()
  if (filePath.startsWith('~/')) {
    return path.join(os.homedir(), filePath.slice(2))
  }
  return filePath
}

export function readWorkspaceFile(filePath: string, workspaceRoots: string[]): {
  path: string
  content: string
  truncated: boolean
} {
  const expandedPath = typeof filePath === 'string' ? expandHomeShortcut(filePath.trim()) : ''
  if (!expandedPath || !path.isAbsolute(expandedPath) || expandedPath.split(/[/\\]+/).includes('..')) {
    throw new HubRpcError(ErrorCode.PathNotAllowed, 'Invalid file path.')
  }
  const parentDir = path.dirname(expandedPath)
  resolveAllowedWorkspacePath(parentDir, workspaceRoots)

  let realFile: string
  try {
    realFile = fs.realpathSync(expandedPath)
    // Also verify real parent is inside workspaceRoots
    resolveAllowedWorkspacePath(path.dirname(realFile), workspaceRoots)
    assertNotSensitive(realFile)
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

export type GitChangeKind = 'modified' | 'added' | 'deleted' | 'renamed' | 'untracked'

export interface GitChangedFile {
  path: string
  status: GitChangeKind
  additions: number
  deletions: number
}

export interface WorkspaceGitStatus {
  path: string
  isGitRepo: boolean
  branch?: string
  headSha?: string
  totalAdditions: number
  totalDeletions: number
  files: GitChangedFile[]
}

export function inspectWorkspaceGitStatus(
  dirPath: string,
  workspaceRoots: string[],
): WorkspaceGitStatus {
  const realDir = resolveAllowedWorkspacePath(dirPath, workspaceRoots)
  const inside = spawnSync('git', ['rev-parse', '--is-inside-work-tree'], {
    cwd: realDir,
    encoding: 'utf8',
    timeout: 3_000,
  })
  if (inside.status !== 0 || inside.stdout.trim() !== 'true') {
    return { path: realDir, isGitRepo: false, totalAdditions: 0, totalDeletions: 0, files: [] }
  }

  const branchRes = spawnSync('git', ['branch', '--show-current'], {
    cwd: realDir,
    encoding: 'utf8',
    timeout: 3_000,
  })
  const headRes = spawnSync('git', ['rev-parse', '--short', 'HEAD'], {
    cwd: realDir,
    encoding: 'utf8',
    timeout: 3_000,
  })
  const hasHead = headRes.status === 0 && Boolean(headRes.stdout.trim())
  const headSha = hasHead ? headRes.stdout.trim() : undefined
  const branch = branchRes.status === 0 && branchRes.stdout.trim() ? branchRes.stdout.trim() : headSha

  // Parse numstat against HEAD (or index on unborn branch)
  const numstatArgs = hasHead ? ['diff', 'HEAD', '--numstat'] : ['diff', '--numstat']
  const numstatRes = spawnSync('git', numstatArgs, {
    cwd: realDir,
    encoding: 'utf8',
    timeout: 5_000,
    maxBuffer: 2 * 1024 * 1024,
  })
  const statsByPath = new Map<string, { add: number; del: number }>()
  if (numstatRes.status === 0 && numstatRes.stdout) {
    for (const line of numstatRes.stdout.split('\n')) {
      if (!line.trim()) continue
      const parts = line.split('\t')
      if (parts.length < 3) continue
      const add = Number.parseInt(parts[0] ?? '0', 10)
      const del = Number.parseInt(parts[1] ?? '0', 10)
      const relPath = parts.slice(2).join('\t').trim()
      if (relPath) {
        statsByPath.set(relPath, {
          add: Number.isFinite(add) ? add : 0,
          del: Number.isFinite(del) ? del : 0,
        })
      }
    }
  }

  const statusRes = spawnSync('git', ['status', '--porcelain=v1', '-uall'], {
    cwd: realDir,
    encoding: 'utf8',
    timeout: 5_000,
    maxBuffer: 2 * 1024 * 1024,
  })
  const files: GitChangedFile[] = []
  let totalAdditions = 0
  let totalDeletions = 0

  if (statusRes.status === 0 && statusRes.stdout) {
    for (const rawLine of statusRes.stdout.split('\n')) {
      if (rawLine.length < 4) continue
      const xy = rawLine.slice(0, 2)
      let rel = rawLine.slice(3).trim()
      if (rel.includes(' -> ')) {
        rel = rel.split(' -> ').pop()!.trim()
      }
      if (rel.startsWith('"') && rel.endsWith('"')) {
        try {
          rel = JSON.parse(rel) as string
        } catch {
          rel = rel.slice(1, -1)
        }
      }
      if (!rel || rel.split(/[/\\]+/).includes('..')) continue
      const absCandidate = path.resolve(realDir, rel)
      if (isSensitivePath(absCandidate)) continue

      const kind: GitChangeKind =
        xy === '??'
          ? 'untracked'
          : xy.includes('R')
            ? 'renamed'
            : xy.includes('D')
              ? 'deleted'
              : xy.includes('A')
                ? 'added'
                : 'modified'

      let st = statsByPath.get(rel)
      if (!st && kind === 'untracked') {
        try {
          const fstat = fs.statSync(absCandidate)
          if (fstat.isFile() && fstat.size <= MAX_READ_BYTES) {
            const lineCount = fs.readFileSync(absCandidate, 'utf8').split('\n').length
            st = { add: lineCount, del: 0 }
          }
        } catch {
          // ignore
        }
      }
      const additions = st?.add ?? 0
      const deletions = st?.del ?? 0
      totalAdditions += additions
      totalDeletions += deletions
      files.push({ path: rel, status: kind, additions, deletions })
      if (files.length >= MAX_CHANGED_FILES) break
    }
  }

  return {
    path: realDir,
    isGitRepo: true,
    branch: branch || undefined,
    headSha,
    totalAdditions,
    totalDeletions,
    files,
  }
}

export function readWorkspaceGitDiff(
  dirPath: string,
  workspaceRoots: string[],
  opts: { filePath?: string; baseCommitSha?: string } = {},
): {
  path: string
  filePath?: string
  diff: string
  truncated: boolean
} {
  const realDir = resolveAllowedWorkspacePath(dirPath, workspaceRoots)
  const relFile = opts.filePath?.trim()
  if (relFile) {
    if (path.isAbsolute(relFile) || relFile.split(/[/\\]+/).includes('..')) {
      throw new HubRpcError(ErrorCode.PathNotAllowed, 'Invalid relative file path.')
    }
    const resolvedTarget = path.resolve(realDir, relFile)
    if (resolvedTarget !== realDir && !resolvedTarget.startsWith(realDir + path.sep)) {
      throw new HubRpcError(ErrorCode.PathNotAllowed, 'File path escapes workspace.')
    }
    assertNotSensitive(resolvedTarget)
    try {
      const lst = fs.lstatSync(resolvedTarget)
      if (lst.isSymbolicLink()) {
        const realTarget = fs.realpathSync(resolvedTarget)
        resolveAllowedWorkspacePath(path.dirname(realTarget), workspaceRoots)
        assertNotSensitive(realTarget)
      }
    } catch (err) {
      if (err instanceof HubRpcError) throw err
    }
  }

  const baseRef =
    opts.baseCommitSha && /^[0-9a-f]{7,40}$/i.test(opts.baseCommitSha)
      ? opts.baseCommitSha
      : undefined
  const headCheck = spawnSync('git', ['rev-parse', '--verify', 'HEAD'], {
    cwd: realDir,
    encoding: 'utf8',
    timeout: 3_000,
  })
  const targetRef = baseRef ?? (headCheck.status === 0 ? 'HEAD' : undefined)

  const diffArgs = ['diff', '--no-color', '--unified=3']
  if (targetRef) diffArgs.push(targetRef)
  if (relFile) diffArgs.push('--', relFile)

  const diffRes = spawnSync('git', diffArgs, {
    cwd: realDir,
    encoding: 'utf8',
    timeout: 6_000,
    maxBuffer: 4 * 1024 * 1024,
  })
  let rawDiff = diffRes.status === 0 ? diffRes.stdout : ''

  // If a specific untracked file was requested, `git diff HEAD -- <file>` is empty; synthesize its `+` preview.
  if (!rawDiff.trim() && relFile) {
    const absFile = path.resolve(realDir, relFile)
    try {
      const lst = fs.lstatSync(absFile)
      if (lst.isSymbolicLink()) {
        throw new HubRpcError(ErrorCode.PathNotAllowed, 'Symlink diff preview outside git index is blocked.')
      }
      if (lst.isFile()) {
        assertNotSensitive(absFile)
        const realFile = fs.realpathSync(absFile)
        resolveAllowedWorkspacePath(path.dirname(realFile), workspaceRoots)
        assertNotSensitive(realFile)
        const readLen = Math.min(lst.size, MAX_READ_BYTES)
        const fd = fs.openSync(realFile, 'r')
        try {
          const buf = Buffer.alloc(readLen)
          fs.readSync(fd, buf, 0, readLen, 0)
          const lines = buf.toString('utf8').split('\n')
          rawDiff = [
            `--- /dev/null`,
            `+++ b/${relFile}`,
            `@@ -0,0 +1,${lines.length} @@`,
            ...lines.map(l => `+${l}`),
          ].join('\n')
        } finally {
          fs.closeSync(fd)
        }
      }
    } catch (err) {
      if (err instanceof HubRpcError) throw err
    }
  }

  // Filter out any sensitive hunks when viewing whole-workspace diff (`relFile` omitted)
  if (!relFile && rawDiff) {
    const filteredChunks: string[] = []
    for (const chunk of rawDiff.split(/^(?=diff --git )/m)) {
      if (!chunk.trim()) continue
      const m = chunk.match(/^diff --git a\/(.+?) b\/(.+)$/m)
      const bPath = m?.[2]?.trim()
      if (bPath && isSensitivePath(path.resolve(realDir, bPath))) {
        continue
      }
      filteredChunks.push(chunk)
    }
    rawDiff = filteredChunks.join('')
  }

  const truncated = Buffer.byteLength(rawDiff, 'utf8') > MAX_DIFF_BYTES
  const sliced = truncated ? Buffer.from(rawDiff, 'utf8').subarray(0, MAX_DIFF_BYTES).toString('utf8') : rawDiff
  return {
    path: realDir,
    filePath: relFile,
    diff: sliced,
    truncated,
  }
}

interface StoredCheckpointEntry extends CheckpointInfo {
  cwd: string
}

export class CheckpointManager {
  private readonly storePath: string
  private entries: StoredCheckpointEntry[] = []

  constructor(storePath = path.join(defaultDataDir(), 'checkpoints.json')) {
    this.storePath = storePath
    this.load()
  }

  list(sessionId: string, cwd?: string): CheckpointInfo[] {
    const normCwd = cwd ? safeRealpath(cwd) : undefined
    return this.entries
      .filter(e => e.sessionId === sessionId || (normCwd !== undefined && e.cwd === normCwd))
      .slice(0, 30)
      .map(({ cwd: _cwd, ...rest }) => rest)
  }

  create(
    sessionId: string,
    cwd: string,
    label?: string,
    opts: { deduplicateUnchanged?: boolean } = {},
  ): CheckpointInfo {
    const realCwd = safeRealpath(cwd)
    const sha = captureWorkingTreeCommit(realCwd, label ?? `lework-${sessionId}`)
    if (!sha) {
      throw new HubRpcError(ErrorCode.InvalidParams, '当前目录不是 Git 仓库，无法创建快照检查点')
    }

    if (opts.deduplicateUnchanged) {
      const latest = this.entries.find(e => e.cwd === realCwd)
      if (latest && isSameGitTree(realCwd, latest.commitSha, sha)) {
        const { cwd: _c, ...info } = latest
        return info
      }
    }

    const info: CheckpointInfo = {
      id: `ckpt_${crypto.randomBytes(5).toString('hex')}`,
      sessionId,
      label: label?.trim() || `手动快照 (${sha.slice(0, 8)})`,
      commitSha: sha,
      createdAt: Date.now(),
    }
    this.entries.unshift({ ...info, cwd: realCwd })
    if (this.entries.length > 200) {
      this.entries = this.entries.slice(0, 200)
    }
    this.save()
    return info
  }

  restore(sessionId: string, cwd: string, checkpointId: string): void {
    const realCwd = safeRealpath(cwd)
    const found = this.entries.find(
      c => c.id === checkpointId && (c.sessionId === sessionId || c.cwd === realCwd),
    )
    if (!found) {
      throw new HubRpcError(ErrorCode.NotFound, `Checkpoint "${checkpointId}" not found`)
    }
    if (!/^[0-9a-f]{7,40}$/i.test(found.commitSha)) {
      throw new HubRpcError(ErrorCode.InvalidParams, '检查点缺少有效的 Git 快照哈希，无法回退')
    }

    // Safety net: automatically snapshot current working tree before overwriting tracked files,
    // so an accidental rollback can itself be undone in one tap.
    const currentSha = captureWorkingTreeCommit(realCwd, `lework-pre-restore-${sessionId}`)
    if (currentSha && !isSameGitTree(realCwd, currentSha, found.commitSha)) {
      this.create(sessionId, realCwd, `回退前自动备份 (${currentSha.slice(0, 8)})`, {
        deduplicateUnchanged: true,
      })
    }

    const res = spawnSync('git', ['checkout', found.commitSha, '--', '.'], {
      cwd: realCwd,
      encoding: 'utf8',
      timeout: 6_000,
    })
    if (res.status !== 0) {
      throw new HubRpcError(
        ErrorCode.Internal,
        `回退检查点失败: ${(res.stderr || res.stdout || '').trim().slice(0, 180)}`,
      )
    }
  }

  private load(): void {
    try {
      if (!fs.existsSync(this.storePath)) return
      const parsed = JSON.parse(fs.readFileSync(this.storePath, 'utf8')) as {
        checkpoints?: StoredCheckpointEntry[]
      }
      if (Array.isArray(parsed.checkpoints)) {
        this.entries = parsed.checkpoints
      }
    } catch {
      this.entries = []
    }
  }

  private save(): void {
    try {
      fs.mkdirSync(path.dirname(this.storePath), { recursive: true, mode: 0o700 })
      fs.writeFileSync(
        this.storePath,
        JSON.stringify({ checkpoints: this.entries }, null, 2) + '\n',
        { encoding: 'utf8', mode: 0o600 },
      )
      fs.chmodSync(this.storePath, 0o600)
    } catch {
      // ignore in read-only test env
    }
  }
}

function safeRealpath(p: string): string {
  try {
    return fs.realpathSync(p)
  } catch {
    return path.resolve(p)
  }
}

/**
 * Creates a commit object capturing current tracked working-tree + index state without mutating
 * the working tree (`git stash create`), falling back to `HEAD` when the working tree is clean.
 */
function captureWorkingTreeCommit(cwd: string, message: string): string | null {
  const stashRes = spawnSync('git', ['stash', 'create', message], {
    cwd,
    encoding: 'utf8',
    timeout: 5_000,
  })
  const stashSha = stashRes.status === 0 ? stashRes.stdout.trim() : ''
  if (/^[0-9a-f]{7,40}$/i.test(stashSha)) {
    return stashSha.slice(0, 12)
  }
  const headRes = spawnSync('git', ['rev-parse', '--short=12', 'HEAD'], {
    cwd,
    encoding: 'utf8',
    timeout: 3_000,
  })
  const headSha = headRes.status === 0 ? headRes.stdout.trim() : ''
  return /^[0-9a-f]{7,40}$/i.test(headSha) ? headSha : null
}

function isSameGitTree(cwd: string, shaA: string, shaB: string): boolean {
  if (shaA === shaB) return true
  const treeA = spawnSync('git', ['rev-parse', `${shaA}^{tree}`], { cwd, encoding: 'utf8', timeout: 2_500 })
  const treeB = spawnSync('git', ['rev-parse', `${shaB}^{tree}`], { cwd, encoding: 'utf8', timeout: 2_500 })
  return (
    treeA.status === 0 &&
    treeB.status === 0 &&
    Boolean(treeA.stdout.trim()) &&
    treeA.stdout.trim() === treeB.stdout.trim()
  )
}

/**
 * Returns the managed worktree root directory (`$LEWORK_WORKTREES_DIR`, default `~/.lework-worktrees`),
 * physically separate from `~/.lework` credentials so `path-guard` keeps secrets isolated.
 */
export function getManagedWorktreesRoot(): string {
  const custom = process.env.LEWORK_WORKTREES_DIR?.trim()
  const target = custom ? path.resolve(custom) : path.join(os.homedir(), '.lework-worktrees')
  try {
    fs.mkdirSync(target, { recursive: true, mode: 0o755 })
    return fs.realpathSync(target)
  } catch {
    return target
  }
}

export function normalizeWorktreeBranch(rawBranch: string | undefined, shortId: string): string {
  const trimmed = (rawBranch ?? '').trim()
  let candidate: string
  if (!trimmed) {
    const now = new Date()
    const mm = String(now.getMonth() + 1).padStart(2, '0')
    const dd = String(now.getDate()).padStart(2, '0')
    const cleanShort = shortId.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 6) || 'task'
    candidate = `lework/${mm}${dd}-${cleanShort}`
  } else {
    const cleaned = trimmed
      .replace(/\s+/g, '-')
      .replace(/[^A-Za-z0-9._/-]/g, '-')
      .replace(/\/+/g, '/')
      .replace(/^[-./]+|[-./]+$/g, '')
      .slice(0, 72)
    if (!cleaned) {
      throw new HubRpcError(ErrorCode.InvalidParams, '分支名称格式无效')
    }
    candidate = cleaned.includes('/') ? cleaned : `lework/${cleaned}`
  }

  const check = spawnSync('git', ['check-ref-format', '--branch', candidate], {
    encoding: 'utf8',
    timeout: 2_500,
  })
  if (check.status !== 0) {
    throw new HubRpcError(ErrorCode.InvalidParams, `分支名称不合法: ${candidate}`)
  }
  return check.stdout.trim() || candidate
}

function runGitWithLockRetry(cwd: string, args: string[], timeoutMs = 15_000) {
  let res = spawnSync('git', args, { cwd, encoding: 'utf8', timeout: timeoutMs })
  for (let attempt = 0; attempt < 3 && res.status !== 0; attempt++) {
    const errText = `${res.stderr ?? ''}\n${res.stdout ?? ''}`
    if (!/Unable to create .*\.lock|Another git process seems to be running/i.test(errText)) {
      break
    }
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 120 * (attempt + 1))
    res = spawnSync('git', args, { cwd, encoding: 'utf8', timeout: timeoutMs })
  }
  return res
}

const LEFTOVER_ONLY_DIRS = new Set(['node_modules', '.next', 'dist', 'build', '.gradle', '.kotlin'])

export function isManagedWorktreePath(candidatePath: string): boolean {
  if (!candidatePath || !path.isAbsolute(candidatePath) || path.resolve(candidatePath) !== candidatePath) {
    return false
  }
  const root = getManagedWorktreesRoot()
  const parentDir = path.dirname(candidatePath)
  let realParent: string
  try {
    realParent = fs.existsSync(parentDir) ? fs.realpathSync(parentDir) : path.resolve(parentDir)
  } catch {
    return false
  }
  const normalizedCandidate = path.join(realParent, path.basename(candidatePath))
  const rel = path.relative(root, normalizedCandidate)
  if (!rel || path.isAbsolute(rel)) return false
  const parts = rel.split(path.sep)
  return parts.length === 2 && parts.every(seg => seg.length > 0 && seg !== '.' && seg !== '..')
}

export function restoreSessionWorktreeIfNeeded(
  worktree: SessionWorktreeInfo,
  desiredCwd?: string,
): {
  sessionCwd: string
  worktree: SessionWorktreeInfo
  restored: boolean
} {
  const wtPath = path.resolve(worktree.worktreePath)
  const gitMarker = path.join(wtPath, '.git')
  if (fs.existsSync(gitMarker)) {
    const resolvedWt = safeRealpath(wtPath)
    const resolvedCwd =
      desiredCwd && fs.existsSync(desiredCwd) ? safeRealpath(desiredCwd) : resolvedWt
    return {
      sessionCwd: resolvedCwd,
      worktree: { ...worktree, worktreePath: resolvedWt, cleanedUp: false },
      restored: false,
    }
  }

  if (!isManagedWorktreePath(wtPath)) {
    throw new HubRpcError(ErrorCode.PathNotAllowed, '非受管分支工作区目录，已拒绝自动重建')
  }
  if (!worktree.baseRepoPath || !fs.existsSync(worktree.baseRepoPath)) {
    throw new HubRpcError(ErrorCode.NotFound, `主仓库目录不存在，无法恢复分支工作区: ${worktree.baseRepoPath}`)
  }
  const baseRepoPath = safeRealpath(worktree.baseRepoPath)
  assertNotSensitive(baseRepoPath)

  // Clear leftover build/cache-only directories (e.g. orphan node_modules) before re-attaching worktree
  if (fs.existsSync(wtPath)) {
    const entries = fs.readdirSync(wtPath)
    const onlyLeftovers = entries.every(name => LEFTOVER_ONLY_DIRS.has(name))
    if (!onlyLeftovers && entries.length > 0) {
      throw new HubRpcError(
        ErrorCode.Conflict,
        `目标分支工作区目录非空且无 Git 元数据，已阻止覆盖: ${wtPath}`,
      )
    }
    for (const entry of entries) {
      fs.rmSync(path.join(wtPath, entry), { recursive: true, force: true })
    }
  } else {
    fs.mkdirSync(path.dirname(wtPath), { recursive: true, mode: 0o755 })
  }

  runGitWithLockRetry(baseRepoPath, ['worktree', 'prune'], 3_000)

  const branch = worktree.branch
  const hasLocalBranch =
    spawnSync('git', ['show-ref', '--verify', '--quiet', `refs/heads/${branch}`], {
      cwd: baseRepoPath,
      timeout: 2_500,
    }).status === 0
  const hasRemoteBranch =
    !hasLocalBranch &&
    spawnSync('git', ['show-ref', '--verify', '--quiet', `refs/remotes/origin/${branch}`], {
      cwd: baseRepoPath,
      timeout: 2_500,
    }).status === 0

  const addArgs = hasLocalBranch
    ? ['worktree', 'add', wtPath, branch]
    : hasRemoteBranch
      ? ['worktree', 'add', '-b', branch, wtPath, `origin/${branch}`]
      : ['worktree', 'add', '-b', branch, wtPath, 'HEAD']

  const addRes = runGitWithLockRetry(baseRepoPath, addArgs, 15_000)
  if (addRes.status !== 0) {
    const detail = (addRes.stderr || addRes.stdout || '').trim().split('\n')[0] ?? 'git worktree add failed'
    throw new HubRpcError(ErrorCode.InvalidParams, `无法恢复独立分支工作区 (${branch}): ${detail}`)
  }

  const realWtPath = safeRealpath(wtPath)
  let sessionCwd = realWtPath
  if (desiredCwd) {
    const rel = path.relative(worktree.worktreePath, desiredCwd)
    if (rel && !rel.startsWith('..') && !path.isAbsolute(rel)) {
      const subCandidate = path.join(realWtPath, rel)
      if (fs.existsSync(subCandidate)) {
        sessionCwd = safeRealpath(subCandidate)
      }
    }
  }

  return {
    sessionCwd,
    worktree: {
      ...worktree,
      baseRepoPath,
      worktreePath: realWtPath,
      cleanedUp: false,
    },
    restored: true,
  }
}

export function createSessionWorktree(opts: {
  cwd: string
  workspaceRoots: string[]
  requestedBranch?: string
  shortId: string
}): {
  sessionCwd: string
  worktree: SessionWorktreeInfo
} {
  const realCwd = resolveAllowedWorkspacePath(opts.cwd, opts.workspaceRoots)
  const topRes = spawnSync('git', ['rev-parse', '--show-toplevel'], {
    cwd: realCwd,
    encoding: 'utf8',
    timeout: 3_000,
  })
  if (topRes.status !== 0 || !topRes.stdout.trim()) {
    throw new HubRpcError(
      ErrorCode.InvalidParams,
      '所选目录不是 Git 仓库，无法开启独立分支工作区',
    )
  }
  const baseRepoPath = safeRealpath(topRes.stdout.trim())
  assertNotSensitive(baseRepoPath)

  const headRes = spawnSync('git', ['rev-parse', '--verify', 'HEAD'], {
    cwd: baseRepoPath,
    encoding: 'utf8',
    timeout: 3_000,
  })
  if (headRes.status !== 0 || !headRes.stdout.trim()) {
    throw new HubRpcError(
      ErrorCode.InvalidParams,
      '该 Git 仓库尚无初始提交（HEAD），请先完成首次提交后再开启独立分支工作区',
    )
  }
  const baseCommitSha = headRes.stdout.trim().slice(0, 12)

  const baseBranchRes = spawnSync('git', ['branch', '--show-current'], {
    cwd: baseRepoPath,
    encoding: 'utf8',
    timeout: 3_000,
  })
  const baseBranch = baseBranchRes.status === 0 && baseBranchRes.stdout.trim()
    ? baseBranchRes.stdout.trim()
    : undefined

  const branch = normalizeWorktreeBranch(opts.requestedBranch, opts.shortId)
  const repoSlug =
    path.basename(baseRepoPath).replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-|-$/g, '') || 'repo'
  const repoHash = crypto.createHash('sha256').update(baseRepoPath).digest('hex').slice(0, 6)
  const branchDirSlug =
    branch.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '') || opts.shortId

  const worktreesRoot = getManagedWorktreesRoot()
  const repoContainer = path.join(worktreesRoot, `${repoSlug}-${repoHash}`)
  fs.mkdirSync(repoContainer, { recursive: true, mode: 0o755 })

  let worktreePath = path.join(repoContainer, branchDirSlug)
  if (fs.existsSync(worktreePath)) {
    worktreePath = path.join(repoContainer, `${branchDirSlug}-${opts.shortId.slice(0, 4)}`)
  }

  // Prune stale git worktree metadata before creating a new checkout
  runGitWithLockRetry(baseRepoPath, ['worktree', 'prune'], 3_000)

  const branchExists =
    spawnSync('git', ['show-ref', '--verify', '--quiet', `refs/heads/${branch}`], {
      cwd: baseRepoPath,
      timeout: 2_500,
    }).status === 0

  const addArgs = branchExists
    ? ['worktree', 'add', worktreePath, branch]
    : ['worktree', 'add', '-b', branch, worktreePath, 'HEAD']

  const addRes = runGitWithLockRetry(baseRepoPath, addArgs, 15_000)
  if (addRes.status !== 0) {
    const detail = (addRes.stderr || addRes.stdout || '').trim().split('\n')[0] ?? 'git worktree add failed'
    throw new HubRpcError(
      ErrorCode.InvalidParams,
      `无法创建独立分支工作区 (${branch}): ${detail}`,
    )
  }

  const realWorktreePath = safeRealpath(worktreePath)
  const relSubDir = path.relative(baseRepoPath, realCwd)
  const candidateSessionCwd =
    relSubDir && !relSubDir.startsWith('..') && !path.isAbsolute(relSubDir)
      ? path.join(realWorktreePath, relSubDir)
      : realWorktreePath
  const sessionCwd = fs.existsSync(candidateSessionCwd) ? safeRealpath(candidateSessionCwd) : realWorktreePath

  return {
    sessionCwd,
    worktree: {
      baseRepoPath,
      worktreePath: realWorktreePath,
      branch,
      ...(baseBranch ? { baseBranch } : {}),
      baseCommitSha,
      cleanedUp: false,
    },
  }
}

export function cleanupSessionWorktree(worktree: SessionWorktreeInfo): {
  cleanedUp: boolean
  dirty: boolean
  branchRemoved: boolean
} {
  if (!worktree.worktreePath || !fs.existsSync(worktree.worktreePath)) {
    if (worktree.baseRepoPath && fs.existsSync(worktree.baseRepoPath)) {
      spawnSync('git', ['worktree', 'prune'], {
        cwd: worktree.baseRepoPath,
        encoding: 'utf8',
        timeout: 3_000,
      })
    }
    return { cleanedUp: true, dirty: false, branchRemoved: false }
  }

  const statusRes = spawnSync('git', ['status', '--porcelain=v1', '-uall'], {
    cwd: worktree.worktreePath,
    encoding: 'utf8',
    timeout: 5_000,
  })
  if (statusRes.status !== 0 || statusRes.stdout.trim().length > 0) {
    // Protect uncommitted / untracked work unconditionally
    return { cleanedUp: false, dirty: true, branchRemoved: false }
  }

  const curHeadRes = spawnSync('git', ['rev-parse', '--short=12', 'HEAD'], {
    cwd: worktree.worktreePath,
    encoding: 'utf8',
    timeout: 3_000,
  })
  const curHeadSha = curHeadRes.status === 0 ? curHeadRes.stdout.trim() : ''

  const removeRes = runGitWithLockRetry(worktree.baseRepoPath, ['worktree', 'remove', worktree.worktreePath], 10_000)
  if (removeRes.status !== 0 && fs.existsSync(worktree.worktreePath)) {
    return { cleanedUp: false, dirty: false, branchRemoved: false }
  }

  let branchRemoved = false
  // Only delete the branch ref if zero new commits were made since the worktree was branched
  if (
    worktree.branch &&
    worktree.baseCommitSha &&
    curHeadSha &&
    curHeadSha.startsWith(worktree.baseCommitSha.slice(0, 7))
  ) {
    const delBranch = spawnSync('git', ['branch', '-d', worktree.branch], {
      cwd: worktree.baseRepoPath,
      encoding: 'utf8',
      timeout: 3_000,
    })
    branchRemoved = delBranch.status === 0
  }

  return { cleanedUp: true, dirty: false, branchRemoved }
}
