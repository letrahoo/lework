import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { DiagnosticEchoAdapter } from '../src/engines/echo-adapter.ts'
import { SessionManager } from '../src/session-manager.ts'
import { cleanupSessionWorktree, createSessionWorktree } from '../src/storage/workspace-fs.ts'

function git(cwd: string, args: string[]): string {
  const res = spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    env: {
      ...process.env,
      GIT_AUTHOR_NAME: 'Lework Test',
      GIT_AUTHOR_EMAIL: 'test@lework.dev',
      GIT_COMMITTER_NAME: 'Lework Test',
      GIT_COMMITTER_EMAIL: 'test@lework.dev',
    },
  })
  if (res.status !== 0) {
    throw new Error(`git ${args.join(' ')} failed: ${res.stderr || res.stdout}`)
  }
  return res.stdout.trim()
}

describe('Git Worktree session isolation & safe archive cleanup', () => {
  let tmpRoot: string
  let repoDir: string
  let worktreesDir: string
  let dataDir: string
  const prevWorktreesEnv = process.env.LEWORK_WORKTREES_DIR
  const prevHomeEnv = process.env.LEWORK_HOME

  beforeEach(() => {
    tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-wt-test-'))
    repoDir = path.join(tmpRoot, 'sample-repo')
    worktreesDir = path.join(tmpRoot, 'managed-worktrees')
    dataDir = path.join(tmpRoot, 'lework-data')
    fs.mkdirSync(path.join(repoDir, 'packages', 'subpkg'), { recursive: true })
    fs.writeFileSync(path.join(repoDir, 'README.md'), '# Sample Repo\n', 'utf8')
    fs.writeFileSync(path.join(repoDir, 'packages', 'subpkg', 'index.ts'), 'export const x = 1\n', 'utf8')

    git(repoDir, ['init', '-b', 'main'])
    git(repoDir, ['add', '.'])
    git(repoDir, ['commit', '-m', 'chore: initial commit'])

    process.env.LEWORK_WORKTREES_DIR = worktreesDir
    process.env.LEWORK_HOME = dataDir
  })

  afterEach(() => {
    if (prevWorktreesEnv === undefined) delete process.env.LEWORK_WORKTREES_DIR
    else process.env.LEWORK_WORKTREES_DIR = prevWorktreesEnv

    if (prevHomeEnv === undefined) delete process.env.LEWORK_HOME
    else process.env.LEWORK_HOME = prevHomeEnv

    fs.rmSync(tmpRoot, { recursive: true, force: true })
  })

  it('creates an isolated worktree branch and maps monorepo subdirectories accurately', () => {
    const subDir = path.join(repoDir, 'packages', 'subpkg')
    const res = createSessionWorktree({
      cwd: subDir,
      workspaceRoots: [repoDir],
      requestedBranch: 'feat-desktop',
      shortId: 'ab12cd',
    })

    expect(res.worktree.branch).toBe('lework/feat-desktop')
    expect(res.worktree.baseBranch).toBe('main')
    expect(res.worktree.cleanedUp).toBe(false)
    expect(fs.existsSync(res.worktree.worktreePath)).toBe(true)
    expect(res.sessionCwd.endsWith(path.join('packages', 'subpkg'))).toBe(true)
    expect(fs.existsSync(path.join(res.sessionCwd, 'index.ts'))).toBe(true)

    const curBranch = git(res.worktree.worktreePath, ['branch', '--show-current'])
    expect(curBranch).toBe('lework/feat-desktop')
  })

  it('rejects non-git directories with a clear InvalidParams error', () => {
    const plainDir = path.join(tmpRoot, 'plain-folder')
    fs.mkdirSync(plainDir, { recursive: true })

    expect(() =>
      createSessionWorktree({
        cwd: plainDir,
        workspaceRoots: [plainDir],
        shortId: '112233',
      }),
    ).toThrow(/不是 Git 仓库/)
  })

  it('auto-removes clean worktree & unused branch on session archive, while protecting dirty worktrees', async () => {
    const mgr = new SessionManager(
      [repoDir],
      [new DiagnosticEchoAdapter()],
      { onEvent: () => {}, onSessionUpdated: () => {} },
    )

    // 1. Clean worktree -> archiving removes directory AND deletes zero-commit branch
    const cleanSess = await mgr.createSession({
      engineId: 'mock',
      cwd: repoDir,
      useWorktree: true,
      worktreeBranch: 'clean-task',
    })
    expect(cleanSess.worktree?.branch).toBe('lework/clean-task')
    const cleanPath = cleanSess.worktree!.worktreePath
    expect(fs.existsSync(cleanPath)).toBe(true)

    const archivedClean = mgr.archiveSession(cleanSess.id, true)
    expect(archivedClean.archived).toBe(true)
    expect(archivedClean.worktree?.cleanedUp).toBe(true)
    expect(fs.existsSync(cleanPath)).toBe(false)

    // 1b. Simulate leftover build cache (`node_modules`) & send follow-up prompt -> resurrects worktree seamlessly!
    fs.mkdirSync(path.join(cleanPath, 'node_modules', 'dummy-pkg'), { recursive: true })
    await mgr.prompt(cleanSess.id, '归档清理后再次追问，自动复活工作树')
    expect(fs.existsSync(path.join(cleanPath, '.git'))).toBe(true)
    expect(git(cleanPath, ['branch', '--show-current'])).toBe('lework/clean-task')
    const revivedMeta = mgr.listSessions().find(s => s.id === cleanSess.id)
    expect(revivedMeta?.worktree?.cleanedUp).toBe(false)

    // 2. Dirty worktree -> archiving MUST preserve uncommitted files on disk
    const dirtySess = await mgr.createSession({
      engineId: 'mock',
      cwd: repoDir,
      useWorktree: true,
      worktreeBranch: 'dirty-task',
    })
    const dirtyPath = dirtySess.worktree!.worktreePath
    fs.writeFileSync(path.join(dirtyPath, 'WIP.txt'), 'uncommitted user work\n', 'utf8')

    const archivedDirty = mgr.archiveSession(dirtySess.id, true)
    expect(archivedDirty.archived).toBe(true)
    expect(archivedDirty.worktree?.cleanedUp).toBe(false)
    expect(fs.existsSync(path.join(dirtyPath, 'WIP.txt'))).toBe(true)

    // 3. Committed worktree -> archiving removes checkout directory but KEEPS branch ref in main repo
    const committedSess = await mgr.createSession({
      engineId: 'mock',
      cwd: repoDir,
      useWorktree: true,
      worktreeBranch: 'committed-task',
    })
    const committedPath = committedSess.worktree!.worktreePath
    fs.writeFileSync(path.join(committedPath, 'feature.ts'), 'export const ready = true\n', 'utf8')
    git(committedPath, ['add', 'feature.ts'])
    git(committedPath, ['commit', '-m', 'feat: add feature'])

    const cleanupRes = cleanupSessionWorktree(committedSess.worktree!)
    expect(cleanupRes.cleanedUp).toBe(true)
    expect(cleanupRes.branchRemoved).toBe(false)
    expect(fs.existsSync(committedPath)).toBe(false)
    const branches = git(repoDir, ['branch', '--list', 'lework/committed-task'])
    expect(branches).toContain('lework/committed-task')

    await mgr.closeAll()
  })
})
