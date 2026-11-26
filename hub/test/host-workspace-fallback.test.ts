import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { ErrorCode } from '@lework/protocol'
import type { EngineAdapter, EngineSession } from '../src/engines/types.ts'
import { SessionManager } from '../src/session-manager.ts'
import { HubRpcError } from '../src/security/path-guard.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'
import {
  extractProjectRootFromToolInput,
  isFilesystemRoot,
  mergeUniqueWorkspaces,
} from '../src/storage/host-history/common.ts'

const tempDirs: string[] = []

function makeTempDir(prefix: string): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
  tempDirs.push(dir)
  return dir
}

afterEach(() => {
  while (tempDirs.length > 0) {
    const dir = tempDirs.pop()!
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

function makeStubAgyAdapter(startedCwds: string[]): EngineAdapter {
  return {
    id: 'agy',
    displayName: 'AGY',
    kind: 'cli',
    capabilities: () => ({
      permissions: false,
      images: false,
      modelSwitch: false,
      modes: false,
      history: true,
      cancel: true,
    }),
    probe: async () => ({
      installed: true,
      ready: true,
      authStatus: 'authenticated',
    }),
    openSession: async init => {
      startedCwds.push(init.cwd)
      const handle: EngineSession = {
        sessionId: init.sessionId,
        cwd: init.cwd,
        prompt: async turnId => {
          init.emit({ type: 'message.assistant.delta', messageId: `msg_${turnId}`, text: 'ok' })
          init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
        },
        respondPermission: async () => {},
        cancel: async () => {},
        close: async () => {},
      }
      return handle
    },
  }
}

function writeFakeAgyConversation(
  homeDir: string,
  convId: string,
  workspacePath: string,
  userPrompt = '分析仓库结构',
): void {
  const agyRoot = path.join(homeDir, '.gemini', 'antigravity-cli')
  const logsDir = path.join(agyRoot, 'brain', convId, '.system_generated', 'logs')
  fs.mkdirSync(logsDir, { recursive: true })
  fs.writeFileSync(
    path.join(logsDir, 'transcript.jsonl'),
    `${JSON.stringify({
      step_index: 0,
      source: 'USER_EXPLICIT',
      type: 'USER_INPUT',
      status: 'DONE',
      created_at: new Date().toISOString(),
      content: `<USER_REQUEST>\n${userPrompt}\n</USER_REQUEST>`,
    })}\n`,
    'utf8',
  )
  fs.appendFileSync(
    path.join(agyRoot, 'history.jsonl'),
    `${JSON.stringify({
      display: userPrompt,
      timestamp: Date.now(),
      workspace: workspacePath,
      conversationId: convId,
    })}\n`,
    'utf8',
  )
}

describe('Host session workspace fallback & symlinked $HOME handling', () => {
  it('filters filesystem root and recognizes symlinked home realpaths in workspace helpers', () => {
    const root = fs.realpathSync(makeTempDir('lework-ws-symlink-'))
    const realHome = path.join(root, 'data00', 'home', 'alice')
    const linkHome = path.join(root, 'home', 'alice')
    const geckoRepo = path.join(realHome, 'work', 'gecko')
    fs.mkdirSync(geckoRepo, { recursive: true })
    fs.mkdirSync(path.dirname(linkHome), { recursive: true })
    fs.symlinkSync(realHome, linkHome)

    expect(isFilesystemRoot('/')).toBe(true)
    expect(isFilesystemRoot(geckoRepo)).toBe(false)

    // Tool path uses realpath (/data00/home/alice/work/gecko/...) while homeDir is symlink (/home/alice)
    const inferred: string[] = []
    extractProjectRootFromToolInput(
      { Path: path.join(geckoRepo, 'mono', 'README.md') },
      inferred,
      linkHome,
    )
    expect(inferred).toEqual([geckoRepo])

    // Root "/" is dropped; symlinked realHome is treated as generic so geckoRepo wins
    const merged = mergeUniqueWorkspaces(['/', realHome, geckoRepo], linkHome)
    expect(merged).toEqual([geckoRepo])

    // Only "/" yields empty array so callers fall back cleanly instead of pinning cwd="/"
    expect(mergeUniqueWorkspaces(['/'], linkHome)).toEqual([])
  })

  it('safely falls back to configured workspaceRoot when materializing a host:* session recorded at "/"', async () => {
    const root = fs.realpathSync(makeTempDir('lework-host-root-fallback-'))
    const fakeHome = path.join(root, 'home')
    const allowedWorkspace = path.join(fakeHome, 'work', 'lework-workspace')
    const metaDir = path.join(root, 'lework-data')
    fs.mkdirSync(allowedWorkspace, { recursive: true })
    fs.mkdirSync(metaDir, { recursive: true })

    const convId = '46f6c835-e0f0-4238-be2b-af79c9523a41'
    writeFakeAgyConversation(fakeHome, convId, '/')

    const prevLeworkHome = process.env.LEWORK_HOME
    process.env.LEWORK_HOME = metaDir
    try {
      const startedCwds: string[] = []
      const inspector = new HostTaskInspector({ homeDir: fakeHome })
      const sm = new SessionManager(
        [allowedWorkspace],
        [makeStubAgyAdapter(startedCwds)],
        {
          onEvent: () => {},
          onSessionUpdated: () => {},
        },
        undefined,
        inspector,
      )

      const sessions = sm.listSessions()
      const hostSession = sessions.find(s => s.id === `host:agy:${convId}`)
      expect(hostSession).toBeDefined()
      expect(isFilesystemRoot(hostSession!.cwd)).toBe(false)

      // Browsing checkpoints on an unmaterialized host session outside workspaceRoots must not throw
      expect(sm.listCheckpoints(`host:agy:${convId}`)).toEqual([])

      // Sending a prompt materializes safely inside allowedWorkspace instead of failing on "/"
      const res = await sm.prompt(`host:agy:${convId}`, '继续分析')
      expect(res.turnId).toBeTruthy()
      expect(startedCwds).toEqual([allowedWorkspace])
      await sm.closeAll()
    } finally {
      if (prevLeworkHome === undefined) delete process.env.LEWORK_HOME
      else process.env.LEWORK_HOME = prevLeworkHome
    }
  })

  it('keeps sandbox enforcement for real out-of-root project directories and returns actionable CLI guidance', async () => {
    const root = fs.realpathSync(makeTempDir('lework-host-outside-project-'))
    const fakeHome = path.join(root, 'home')
    const allowedWorkspace = path.join(fakeHome, 'work', 'lework-workspace')
    const outsideRepo = path.join(fakeHome, 'work', 'gecko')
    const metaDir = path.join(root, 'lework-data')
    fs.mkdirSync(allowedWorkspace, { recursive: true })
    fs.mkdirSync(outsideRepo, { recursive: true })
    fs.mkdirSync(metaDir, { recursive: true })

    const convId = '73555e89-de4d-4e3e-b1a7-af0ffa2eb7f6'
    writeFakeAgyConversation(fakeHome, convId, outsideRepo)

    const prevLeworkHome = process.env.LEWORK_HOME
    process.env.LEWORK_HOME = metaDir
    try {
      const startedCwds: string[] = []
      const inspector = new HostTaskInspector({ homeDir: fakeHome })
      const sm = new SessionManager(
        [allowedWorkspace],
        [makeStubAgyAdapter(startedCwds)],
        {
          onEvent: () => {},
          onSessionUpdated: () => {},
        },
        undefined,
        inspector,
      )

      // Discover host sessions first, then verify checkpoint listing stays quiet for out-of-root host sessions
      expect(sm.listSessions().some(s => s.id === `host:agy:${convId}`)).toBe(true)
      expect(sm.listCheckpoints(`host:agy:${convId}`)).toEqual([])

      // Sending a prompt must NOT silently hijack a different repo; reject with actionable guidance
      let caught: unknown
      try {
        await sm.prompt(`host:agy:${convId}`, '查看状态')
      } catch (err) {
        caught = err
      }
      expect(caught).toBeInstanceOf(HubRpcError)
      expect((caught as HubRpcError).code).toBe(ErrorCode.PathNotAllowed)
      expect((caught as HubRpcError).message).toContain('lework config workspace add')
      expect((caught as HubRpcError).message).toContain(outsideRepo)
      expect(startedCwds).toEqual([])
      await sm.closeAll()
    } finally {
      if (prevLeworkHome === undefined) delete process.env.LEWORK_HOME
      else process.env.LEWORK_HOME = prevLeworkHome
    }
  })
})
