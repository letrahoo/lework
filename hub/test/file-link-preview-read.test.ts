import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ErrorCode } from '@lework/protocol'
import { getReadableFileRoots, readWorkspaceFile } from '../src/storage/workspace-fs.ts'

describe('Hub local file preview read roots & home expansion', () => {
  const originalHome = process.env.HOME
  let tempRoot = ''
  let fakeHome = ''
  let workspaceDir = ''

  beforeEach(() => {
    tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-file-preview-'))
    fakeHome = path.join(tempRoot, 'home')
    workspaceDir = path.join(tempRoot, 'workspace')
    fs.mkdirSync(path.join(fakeHome, '.agent-memory', 'concepts', 'entries', 'internal-infra'), { recursive: true })
    fs.mkdirSync(path.join(fakeHome, '.ssh'), { recursive: true })
    fs.mkdirSync(workspaceDir, { recursive: true })
    process.env.HOME = fakeHome
  })

  afterEach(() => {
    if (originalHome === undefined) {
      delete process.env.HOME
    } else {
      process.env.HOME = originalHome
    }
    fs.rmSync(tempRoot, { recursive: true, force: true })
  })

  it('includes ~/.agent-memory in readable file roots and supports ~/ expansion for Concept Memory docs', () => {
    const rgoPath = path.join(fakeHome, '.agent-memory', 'concepts', 'entries', 'internal-infra', 'rgo.md')
    const overviewHtmlPath = path.join(fakeHome, '.agent-memory', 'concepts', 'overview.html')
    const overviewMdPath = path.join(fakeHome, '.agent-memory', 'concepts', 'OVERVIEW.md')
    fs.writeFileSync(rgoPath, '# RGO\n\nResource Governance Orchestrator.')
    fs.writeFileSync(overviewHtmlPath, '<!doctype html><html><title>Concept Memory</title><body><h1>总览看板</h1></body></html>')
    fs.writeFileSync(overviewMdPath, '# Concept Memory 总览\n\n- [rgo.md](entries/internal-infra/rgo.md)')

    const readableRoots = getReadableFileRoots([workspaceDir])
    expect(readableRoots).toContain(workspaceDir)
    expect(readableRoots).toContain(path.join(fakeHome, '.agent-memory'))

    const rgoAbs = readWorkspaceFile(rgoPath, readableRoots)
    expect(rgoAbs.content).toContain('Resource Governance Orchestrator')
    expect(rgoAbs.truncated).toBe(false)

    const rgoHomeShortcut = readWorkspaceFile('~/.agent-memory/concepts/entries/internal-infra/rgo.md', readableRoots)
    expect(rgoHomeShortcut.path).toBe(fs.realpathSync(rgoPath))
    expect(rgoHomeShortcut.content).toContain('# RGO')

    const overviewHtml = readWorkspaceFile(overviewHtmlPath, readableRoots)
    expect(overviewHtml.content).toContain('总览看板')

    const overviewMd = readWorkspaceFile(overviewMdPath, readableRoots)
    expect(overviewMd.content).toContain('[rgo.md](entries/internal-infra/rgo.md)')
  })

  it('still blocks sensitive files, path traversal, and symlinks escaping ~/.agent-memory', () => {
    const sshKey = path.join(fakeHome, '.ssh', 'id_ed25519')
    fs.writeFileSync(sshKey, 'PRIVATE-SSH-KEY')

    const dotEnvInMemory = path.join(fakeHome, '.agent-memory', '.env')
    fs.writeFileSync(dotEnvInMemory, 'API_SECRET=topsecret')

    const symlinkEscape = path.join(fakeHome, '.agent-memory', 'concepts', 'leak.md')
    fs.symlinkSync(sshKey, symlinkEscape)

    const readableRoots = getReadableFileRoots([workspaceDir])

    expect(() => readWorkspaceFile('~/.ssh/id_ed25519', readableRoots)).toThrowError()
    expect(() => readWorkspaceFile('~/.agent-memory/../.ssh/id_ed25519', readableRoots)).toThrowError()
    expect(() => readWorkspaceFile(dotEnvInMemory, readableRoots)).toThrowError()
    expect(() => readWorkspaceFile(symlinkEscape, readableRoots)).toThrowError()

    try {
      readWorkspaceFile(symlinkEscape, readableRoots)
      expect.unreachable('symlink escape should throw PathNotAllowed')
    } catch (err: any) {
      expect(err.code).toBe(ErrorCode.PathNotAllowed)
    }
  })

  it('truncates files larger than 256 KB cleanly instead of truncating medium docs at 64 KB', () => {
    const mediumDoc = path.join(workspaceDir, 'medium.md')
    const mediumContent = 'A'.repeat(100 * 1024)
    fs.writeFileSync(mediumDoc, mediumContent)

    const mediumRes = readWorkspaceFile(mediumDoc, getReadableFileRoots([workspaceDir]))
    expect(mediumRes.truncated).toBe(false)
    expect(mediumRes.content.length).toBe(100 * 1024)

    const hugeDoc = path.join(workspaceDir, 'huge.md')
    fs.writeFileSync(hugeDoc, 'B'.repeat(300 * 1024))
    const hugeRes = readWorkspaceFile(hugeDoc, getReadableFileRoots([workspaceDir]))
    expect(hugeRes.truncated).toBe(true)
    expect(hugeRes.content.length).toBe(256 * 1024)
  })
})
