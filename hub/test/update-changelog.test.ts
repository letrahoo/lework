import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const SCRIPT_PATH = path.join(REPO_ROOT, 'scripts/update-changelog.mjs')

function runGit(args: string[], cwd: string): string {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    env: {
      ...process.env,
      GIT_AUTHOR_NAME: 'Test Bot',
      GIT_AUTHOR_EMAIL: 'test@example.com',
      GIT_COMMITTER_NAME: 'Test Bot',
      GIT_COMMITTER_EMAIL: 'test@example.com',
    },
  }).trim()
}

function runChangelogCli(args: string[], cwd = REPO_ROOT): { ok: boolean; stdout: string; stderr: string } {
  try {
    const stdout = execFileSync(process.execPath, [SCRIPT_PATH, ...args], {
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    return { ok: true, stdout, stderr: '' }
  } catch (err) {
    const e = err as { stdout?: Buffer | string; stderr?: Buffer | string }
    return {
      ok: false,
      stdout: String(e.stdout ?? ''),
      stderr: String(e.stderr ?? ''),
    }
  }
}

function seedBaseChangelogs(dir: string): void {
  fs.writeFileSync(
    path.join(dir, 'CHANGELOG.md'),
    `# Changelog

**English** | [简体中文](./CHANGELOG.zh-CN.md)

---

## [Unreleased]

---

## [v0.1.0] - 2026-11-20

### Added
- **Initial release**

---

[Unreleased]: https://github.com/example/demo/compare/v0.1.0...HEAD
[v0.1.0]: https://github.com/example/demo/releases/tag/v0.1.0
`,
    'utf8',
  )

  fs.writeFileSync(
    path.join(dir, 'CHANGELOG.zh-CN.md'),
    `# 更新日志（Changelog）

[English](./CHANGELOG.md) | **简体中文**

---

## [未发布 / Unreleased]

---

## [v0.1.0] - 2026-11-20

### 新增
- **首个版本**

---

[未发布 / Unreleased]: https://github.com/example/demo/compare/v0.1.0...HEAD
[v0.1.0]: https://github.com/example/demo/releases/tag/v0.1.0
`,
    'utf8',
  )
}

describe('scripts/update-changelog.mjs', () => {
  it('validates the repository bilingual CHANGELOG.md and CHANGELOG.zh-CN.md structure and parity', () => {
    const res = runChangelogCli(['--check', '--cwd', REPO_ROOT])
    expect(res.ok, res.stderr || res.stdout).toBe(true)
    expect(res.stdout).toContain('OK: bilingual CHANGELOG validated')
  })

  it('syncs multiple new release tags idempotently, classifies PRs, skips changelog-sync commits, and updates compare links', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-changelog-test-'))
    try {
      runGit(['init', '-b', 'main'], tmpDir)
      runGit(['remote', 'add', 'origin', 'https://github.com/example/demo.git'], tmpDir)
      seedBaseChangelogs(tmpDir)
      fs.writeFileSync(path.join(tmpDir, 'app.txt'), 'v0.1.0\n', 'utf8')
      runGit(['add', '.'], tmpDir)
      runGit(['commit', '-m', 'chore: initial release'], tmpDir)
      runGit(['tag', 'v0.1.0'], tmpDir)

      // Tag v0.1.1: one feat PR + one fix PR (simulated GitHub merge commits)
      fs.writeFileSync(path.join(tmpDir, 'app.txt'), 'v0.1.1a\n', 'utf8')
      runGit(['add', 'app.txt'], tmpDir)
      runGit(['commit', '-m', 'Merge pull request #12 from example/feat-search\n\nfeat(app): add instant global search'], tmpDir)

      fs.writeFileSync(path.join(tmpDir, 'app.txt'), 'v0.1.1b\n', 'utf8')
      runGit(['add', 'app.txt'], tmpDir)
      runGit(['commit', '-m', 'Merge pull request #13 from example/fix-newline\n\nfix(desktop): allow Shift+Enter newline'], tmpDir)
      runGit(['tag', 'v0.1.1'], tmpDir)

      // Tag v0.1.2: one squash PR + one changelog-sync commit that must be filtered out
      fs.writeFileSync(path.join(tmpDir, 'app.txt'), 'v0.1.2a\n', 'utf8')
      runGit(['add', 'app.txt'], tmpDir)
      runGit(['commit', '-m', 'docs(changelog): sync bilingual CHANGELOG through v0.1.1 (#14)'], tmpDir)

      fs.writeFileSync(path.join(tmpDir, 'app.txt'), 'v0.1.2b\n', 'utf8')
      runGit(['add', 'app.txt'], tmpDir)
      runGit(['commit', '-m', 'refactor(hub): streamline session window pagination (#15)'], tmpDir)
      runGit(['tag', 'v0.1.2'], tmpDir)

      // Strict check should fail before syncing
      const preCheck = runChangelogCli(['--check', '--strict-tags', '--cwd', tmpDir])
      expect(preCheck.ok).toBe(false)
      expect(preCheck.stderr).toContain('v0.1.1')
      expect(preCheck.stderr).toContain('v0.1.2')

      // Sync with --write --no-gh
      const ghOutputFile = path.join(tmpDir, 'gh-output.txt')
      const syncOut = execFileSync(
        process.execPath,
        [SCRIPT_PATH, '--write', '--no-gh', '--github-output', '--cwd', tmpDir],
        {
          encoding: 'utf8',
          env: { ...process.env, GITHUB_OUTPUT: ghOutputFile },
        },
      )
      expect(syncOut).toContain('v0.1.2, v0.1.1')

      const ghOutput = fs.readFileSync(ghOutputFile, 'utf8')
      expect(ghOutput).toContain('updated=true')
      expect(ghOutput).toContain('added_tags=v0.1.2,v0.1.1')
      expect(ghOutput).toContain('latest_tag=v0.1.2')

      const en = fs.readFileSync(path.join(tmpDir, 'CHANGELOG.md'), 'utf8')
      const zh = fs.readFileSync(path.join(tmpDir, 'CHANGELOG.zh-CN.md'), 'utf8')

      // Ordering: v0.1.2 before v0.1.1 before v0.1.0
      expect(en.indexOf('## [v0.1.2]')).toBeLessThan(en.indexOf('## [v0.1.1]'))
      expect(en.indexOf('## [v0.1.1]')).toBeLessThan(en.indexOf('## [v0.1.0]'))
      expect(zh.indexOf('## [v0.1.2]')).toBeLessThan(zh.indexOf('## [v0.1.1]'))

      // Classification & links
      expect(en).toContain('### Added\n- **add instant global search** ([#12](https://github.com/example/demo/pull/12))')
      expect(en).toContain('### Fixed\n- **allow Shift+Enter newline** ([#13](https://github.com/example/demo/pull/13))')
      expect(en).toContain('### Changed\n- **streamline session window pagination** ([#15](https://github.com/example/demo/pull/15))')
      expect(en).not.toContain('#14')

      expect(zh).toContain('### 新增\n- **add instant global search**（[#12](https://github.com/example/demo/pull/12)）')
      expect(zh).toContain('### 修复\n- **allow Shift+Enter newline**（[#13](https://github.com/example/demo/pull/13)）')
      expect(zh).toContain('### 变更\n- **streamline session window pagination**（[#15](https://github.com/example/demo/pull/15)）')
      expect(zh).not.toContain('#14')

      // Compare links updated cleanly
      expect(en).toContain('[Unreleased]: https://github.com/example/demo/compare/v0.1.2...HEAD')
      expect(en).toContain('[v0.1.2]: https://github.com/example/demo/compare/v0.1.1...v0.1.2')
      expect(en).toContain('[v0.1.1]: https://github.com/example/demo/compare/v0.1.0...v0.1.1')
      expect(zh).toContain('[未发布 / Unreleased]: https://github.com/example/demo/compare/v0.1.2...HEAD')

      // Strict check now passes
      const postCheck = runChangelogCli(['--check', '--strict-tags', '--cwd', tmpDir])
      expect(postCheck.ok, postCheck.stderr).toBe(true)

      // Second run is a clean idempotent no-op
      const secondSync = runChangelogCli(['--write', '--no-gh', '--cwd', tmpDir])
      expect(secondSync.ok).toBe(true)
      expect(secondSync.stdout).toContain('already up to date')
      expect(fs.readFileSync(path.join(tmpDir, 'CHANGELOG.md'), 'utf8')).toBe(en)
      expect(fs.readFileSync(path.join(tmpDir, 'CHANGELOG.zh-CN.md'), 'utf8')).toBe(zh)
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true })
    }
  })

  it('fails --check when EN and ZH changelogs diverge or have stale Unreleased link', () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-changelog-drift-'))
    try {
      seedBaseChangelogs(tmpDir)
      const brokenZh = fs
        .readFileSync(path.join(tmpDir, 'CHANGELOG.zh-CN.md'), 'utf8')
        .replace('compare/v0.1.0...HEAD', 'compare/v0.0.9...HEAD')
      fs.writeFileSync(path.join(tmpDir, 'CHANGELOG.zh-CN.md'), brokenZh, 'utf8')

      const res = runChangelogCli(['--check', '--cwd', tmpDir])
      expect(res.ok).toBe(false)
      expect(res.stderr).toContain('expected v0.1.0')
    } finally {
      fs.rmSync(tmpDir, { recursive: true, force: true })
    }
  })
})
