import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { ErrorCode } from '@lework/protocol'
import {
  CheckpointManager,
  inspectWorkspaceGitStatus,
  readWorkspaceGitDiff,
} from '../src/storage/workspace-fs.ts'

function git(cwd: string, args: string[]) {
  execFileSync('git', args, {
    cwd,
    stdio: 'ignore',
    env: {
      ...process.env,
      GIT_AUTHOR_NAME: 'Test',
      GIT_AUTHOR_EMAIL: 'test@example.com',
      GIT_COMMITTER_NAME: 'Test',
      GIT_COMMITTER_EMAIL: 'test@example.com',
    },
  })
}

describe('Workspace Git Diff & Checkpoint Restore', () => {
  it('inspects changed & untracked files, blocks sensitive files, and restores checkpoints with auto-backup', () => {
    const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-git-ckpt-'))
    const repo = path.join(tmpRoot, 'demo')
    fs.mkdirSync(repo, { recursive: true })
    git(repo, ['init', '-b', 'main'])
    fs.writeFileSync(path.join(repo, 'app.ts'), 'export const v = 1\nexport const name = "lework"\n')
    git(repo, ['add', 'app.ts'])
    git(repo, ['commit', '-m', 'init'])

    // Modify tracked file, add untracked file, and plant a sensitive `.env` file
    fs.writeFileSync(path.join(repo, 'app.ts'), 'export const v = 2\nexport const name = "lework"\nexport const ok = true\n')
    fs.writeFileSync(path.join(repo, 'notes.md'), '# Release Notes\n- Added diff viewer\n')
    fs.writeFileSync(path.join(repo, '.env'), 'API_SECRET=top-secret\n')

    const status = inspectWorkspaceGitStatus(repo, [tmpRoot])
    expect(status.isGitRepo).toBe(true)
    expect(status.branch).toBe('main')
    expect(status.files.map(f => f.path).sort()).toEqual(['app.ts', 'notes.md'])
    const appStat = status.files.find(f => f.path === 'app.ts')!
    expect(appStat.status).toBe('modified')
    expect(appStat.additions).toBe(2)
    expect(appStat.deletions).toBe(1)

    // Tracked file diff
    const appDiff = readWorkspaceGitDiff(repo, [tmpRoot], { filePath: 'app.ts' })
    expect(appDiff.diff).toContain('-export const v = 1')
    expect(appDiff.diff).toContain('+export const v = 2')

    // Untracked file synthesized diff
    const notesDiff = readWorkspaceGitDiff(repo, [tmpRoot], { filePath: 'notes.md' })
    expect(notesDiff.diff).toContain('+++ b/notes.md')
    expect(notesDiff.diff).toContain('+# Release Notes')

    // Sensitive `.env` must be blocked on direct diff AND stripped from full workspace diff
    expect(() => readWorkspaceGitDiff(repo, [tmpRoot], { filePath: '.env' })).toThrowError()
    const fullDiff = readWorkspaceGitDiff(repo, [tmpRoot])
    expect(fullDiff.diff).not.toContain('API_SECRET')

    // Checkpoint creation, persistence across reload, deduplication, and restore with auto-backup
    const storeFile = path.join(tmpRoot, 'checkpoints.json')
    const mgr = new CheckpointManager(storeFile)
    const c1 = mgr.create('host:claude:sess1', repo, '修改 v=2 快照')
    const dup = mgr.create('host:claude:sess1', repo, '重复自动快照', { deduplicateUnchanged: true })
    expect(dup.id).toBe(c1.id)

    // Make a destructive edit (`v = 999`)
    fs.writeFileSync(path.join(repo, 'app.ts'), 'export const v = 999 // broken\n')

    // Reload manager from disk to verify persistence across Hub restarts
    const reloaded = new CheckpointManager(storeFile)
    expect(reloaded.list('other-session-same-cwd', repo)).toHaveLength(1)

    // Restore back to c1 (`v = 2`)
    reloaded.restore('host:claude:sess1', repo, c1.id)
    expect(fs.readFileSync(path.join(repo, 'app.ts'), 'utf8')).toContain('export const v = 2')

    // Auto-backup of `v = 999` was recorded so even the rollback can be undone!
    const allAfterRestore = reloaded.list('host:claude:sess1', repo)
    expect(allAfterRestore.length).toBeGreaterThanOrEqual(2)
    const backup = allAfterRestore.find(c => c.label.startsWith('回退前自动备份'))
    expect(backup).toBeDefined()
    reloaded.restore('host:claude:sess1', repo, backup!.id)
    expect(fs.readFileSync(path.join(repo, 'app.ts'), 'utf8')).toContain('v = 999')
  })
})
