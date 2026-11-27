import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ErrorCode, type AgentEvent, type SessionMeta } from '@lework/protocol'
import { TerminalBridge } from '../src/storage/terminal-bridge.js'
import { SessionManager } from '../src/session-manager.js'
import { HubRpcError } from '../src/security/path-guard.js'
import type { HostTaskInspector } from '../src/storage/host-history.js'
import type { EngineAdapter, EngineSession, EngineSessionInit } from '../src/engines/types.js'

function createTempPresenceLock(convId: string): { tmpHome: string; lockFile: string } {
  const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-agy-self-lock-'))
  const presenceDir = path.join(tmpHome, '.gemini', 'antigravity-cli', 'presence')
  fs.mkdirSync(presenceDir, { recursive: true })
  const lockFile = path.join(presenceDir, `${convId}.lock`)
  fs.writeFileSync(lockFile, '')
  return { tmpHome, lockFile }
}

describe('AGY self-lock false positive prevention', () => {
  let prevLeworkHome: string | undefined
  let tmpLeworkHome: string

  beforeEach(() => {
    prevLeworkHome = process.env.LEWORK_HOME
    tmpLeworkHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-home-agy-self-lock-'))
    process.env.LEWORK_HOME = tmpLeworkHome
  })

  afterEach(() => {
    if (prevLeworkHome === undefined) {
      delete process.env.LEWORK_HOME
    } else {
      process.env.LEWORK_HOME = prevLeworkHome
    }
    fs.rmSync(tmpLeworkHome, { recursive: true, force: true })
  })

  it('[SL1] TerminalBridge.checkAgyPresenceLock ignores Hub own stream-json child even when Hub runs inside an IDE terminal', async () => {
    const convId = '6494dc54-c85c-49b1-b103-bdc5cb50fe5d'
    const hubPid = 23747
    const workerPid = 76566
    const { tmpHome, lockFile } = createTempPresenceLock(convId)

    try {
      const bridge = new TerminalBridge({
        homeDir: tmpHome,
        lsofBin: '/usr/sbin/lsof',
        selfPid: hubPid,
        isPidAlive: pid => pid === workerPid || pid === hubPid,
        runCommand: async (bin, args) => {
          if (bin === 'ps' && args[0] === '-axo') {
            return `${workerPid} ?? /usr/local/bin/agy --input-format stream-json --output-format stream-json --conversation ${convId}\n`
          }
          if (bin === '/usr/sbin/lsof' && args.includes(lockFile)) {
            return `p${workerPid}\nf14\n`
          }
          if (bin === 'ps' && args[0] === '-o' && args[1] === 'ppid=,command=') {
            if (args[3] === String(workerPid)) {
              return `${hubPid} /usr/local/bin/agy --input-format stream-json --conversation ${convId}\n`
            }
            // Even if Hub itself was started from a Cursor terminal in dev mode,
            // its direct child must be recognized as Hub-owned before walking up to Cursor.
            if (args[3] === String(hubPid)) {
              return `20000 /Applications/Cursor.app/Contents/MacOS/Cursor\n`
            }
          }
          return ''
        },
      })

      const res = await bridge.checkAgyPresenceLock(convId)
      expect(res).toEqual({ locked: false })

      // Also verify `agy acp` Hub child processes are ignored (not treated as external TUI windows)
      const acpBridge = new TerminalBridge({
        homeDir: tmpHome,
        lsofBin: '/usr/sbin/lsof',
        selfPid: hubPid,
        isPidAlive: pid => pid === workerPid || pid === hubPid,
        runCommand: async (bin, args) => {
          if (bin === 'ps' && args[0] === '-axo') {
            return `${workerPid} ?? /usr/local/bin/agy acp\n`
          }
          if (bin === '/usr/sbin/lsof' && args.includes(lockFile)) {
            return `p${workerPid}\nf14\n`
          }
          if (bin === 'ps' && args[0] === '-o' && args[1] === 'ppid=,command=') {
            if (args[3] === String(workerPid)) {
              return `${hubPid} /usr/local/bin/agy acp\n`
            }
          }
          return ''
        },
      })
      expect(await acpBridge.checkAgyPresenceLock(convId)).toEqual({ locked: false })
    } finally {
      fs.rmSync(tmpHome, { recursive: true, force: true })
    }
  })

  it('[SL2] TerminalBridge.checkAgyPresenceLock ignores Hub grandchild while still blocking external terminals and orphans from dead Hubs', async () => {
    const convId = '7a8b9c0d-1234-4567-89ab-cdef01234567'
    const hubPid = 30000
    const wrapperPid = 30001
    const grandchildPid = 30002
    const externalPid = 41002
    const orphanPid = 55001
    const { tmpHome, lockFile } = createTempPresenceLock(convId)

    try {
      // 1) Grandchild of current Hub -> ignored
      const grandchildBridge = new TerminalBridge({
        homeDir: tmpHome,
        lsofBin: '/usr/sbin/lsof',
        selfPid: hubPid,
        isPidAlive: pid => pid === grandchildPid || pid === wrapperPid || pid === hubPid,
        runCommand: async (bin, args) => {
          if (bin === 'ps' && args[0] === '-axo') return ''
          if (bin === '/usr/sbin/lsof' && args.includes(lockFile)) return `p${grandchildPid}\n`
          if (bin === 'ps' && args[0] === '-o' && args[1] === 'ppid=,command=') {
            if (args[3] === String(grandchildPid)) return `${wrapperPid} /usr/local/bin/agy --input-format stream-json\n`
            if (args[3] === String(wrapperPid)) return `${hubPid} /bin/sh -c agy\n`
          }
          return ''
        },
      })
      expect(await grandchildBridge.checkAgyPresenceLock(convId)).toEqual({ locked: false })

      // 2) Hub child + external Cursor terminal -> still reports locked by Cursor
      const mixedBridge = new TerminalBridge({
        homeDir: tmpHome,
        lsofBin: '/usr/sbin/lsof',
        selfPid: hubPid,
        isPidAlive: pid => pid === grandchildPid || pid === externalPid,
        runCommand: async (bin, args) => {
          if (bin === 'ps' && args[0] === '-axo') return ''
          if (bin === '/usr/sbin/lsof' && args.includes(lockFile)) {
            return `p${grandchildPid}\np${externalPid}\n`
          }
          if (bin === 'ps' && args[0] === '-o' && args[1] === 'ppid=,command=') {
            if (args[3] === String(grandchildPid)) return `${hubPid} /usr/local/bin/agy --input-format stream-json\n`
            if (args[3] === String(externalPid)) return `41000 /bin/zsh -l\n`
            if (args[3] === '41000') return `1 /Applications/Cursor.app/Contents/MacOS/Cursor\n`
          }
          return ''
        },
      })
      expect(await mixedBridge.checkAgyPresenceLock(convId)).toEqual({
        locked: true,
        hostAppHint: 'Cursor',
      })

      // 3) Orphaned stream-json process from a dead previous Hub (ppid=1) -> still reports locked
      const orphanBridge = new TerminalBridge({
        homeDir: tmpHome,
        lsofBin: '/usr/sbin/lsof',
        selfPid: hubPid,
        isPidAlive: pid => pid === orphanPid,
        runCommand: async (bin, args) => {
          if (bin === 'ps' && args[0] === '-axo') return ''
          if (bin === '/usr/sbin/lsof' && args.includes(lockFile)) return `p${orphanPid}\n`
          if (bin === 'ps' && args[0] === '-o' && args[1] === 'ppid=,command=') {
            if (args[3] === String(orphanPid)) {
              return `1 /usr/local/bin/agy --input-format stream-json --conversation ${convId}\n`
            }
          }
          return ''
        },
      })
      expect(await orphanBridge.checkAgyPresenceLock(convId)).toEqual({
        locked: true,
        hostAppHint: undefined,
      })
    } finally {
      fs.rmSync(tmpHome, { recursive: true, force: true })
    }
  })

  it('[SL3] SessionManager.prompt reuses live Hub AGY handle on Turn 2 without triggering self-lock conflict', async () => {
    const convId = '11112222-3333-4444-5555-666677778888'
    const sessionId = `host:agy:${convId}`
    const cwd = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-agy-cwd-'))

    try {
      let openCount = 0
      let promptCalls = 0
      let lockChecks = 0
      let alive = false

      const fakeAgyAdapter: EngineAdapter = {
        id: 'agy',
        displayName: 'Antigravity',
        kind: 'cli',
        probe: async () => ({
          installed: true,
          ready: true,
          authStatus: 'authenticated',
        }),
        capabilities: () => ({
          permissions: false,
          images: true,
          modelSwitch: true,
          modes: true,
          history: true,
          cancel: true,
        }),
        openSession: async (init: EngineSessionInit): Promise<EngineSession> => {
          openCount++
          alive = true
          return {
            sessionId: init.sessionId,
            cwd: init.cwd,
            isAlive: () => alive,
            prompt: async (turnId: string) => {
              promptCalls++
              init.emit({ type: 'session.state', state: 'running' })
              init.emit({ type: 'message.assistant.delta', messageId: `m_${turnId}`, text: `reply ${promptCalls}` })
              init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
              init.emit({ type: 'session.state', state: 'idle' })
            },
            cancel: async () => undefined,
            respondPermission: async () => undefined,
            close: async () => {
              alive = false
            },
          }
        },
      }

      const baseMeta: SessionMeta = {
        id: sessionId,
        engineId: 'agy',
        cwd,
        title: 'what-is-demo',
        state: 'idle',
        createdAt: 1000,
        updatedAt: 1000,
        lastSeq: 1,
        source: 'host',
      }
      const seedEvents: AgentEvent[] = [
        {
          seq: 1,
          ts: 1000,
          sessionId,
          type: 'message.user',
          messageId: 'u_seed',
          text: 'initial prompt',
        },
      ]

      const fakeInspector = {
        setCallbacks: () => undefined,
        setMetaStore: () => undefined,
        getHomeDir: () => tmpLeworkHome,
        getDiscoveredSubagents: () => [],
        retainWatch: () => undefined,
        releaseWatch: () => undefined,
        readHostSessionEvents: (id: string) =>
          id === sessionId ? { session: { ...baseMeta }, events: [...seedEvents] } : null,
        getHostSession: (id: string) =>
          id === sessionId
            ? { nativeId: convId, filePath: path.join(cwd, 'transcript.jsonl'), meta: { ...baseMeta } }
            : undefined,
        tryPromptLiveHostSession: async () => false,
        // Even if the presence lock file is held by our own worker, SessionManager must not reject Turn 2!
        isAgyLockedInInteractiveTerminal: async () => {
          lockChecks++
          return alive ? { locked: true } : { locked: false }
        },
      } as unknown as HostTaskInspector

      const manager = new SessionManager(
        [cwd],
        [fakeAgyAdapter],
        { onEvent: () => undefined, onSessionUpdated: () => undefined },
        undefined,
        fakeInspector,
      )

      // Turn 1: no Hub handle yet -> checks lock (unlocked) -> opens persistent worker
      await manager.prompt(sessionId, 'turn 1')
      expect(openCount).toBe(1)
      expect(promptCalls).toBe(1)
      expect(lockChecks).toBe(1)

      // Turn 2: Hub already owns the live AGY worker -> reuses handle with 0 false self-lock conflict!
      await manager.prompt(sessionId, 'turn 2')
      expect(openCount).toBe(1)
      expect(promptCalls).toBe(2)
      expect(lockChecks).toBe(1)

      // If Hub worker later dies (isAlive() === false) AND an external terminal acquires the lock,
      // the guardrail MUST still trigger Conflict!
      alive = false
      const externalInspector = fakeInspector as unknown as {
        isAgyLockedInInteractiveTerminal: () => Promise<{ locked: boolean; hostAppHint?: string }>
      }
      externalInspector.isAgyLockedInInteractiveTerminal = async () => {
        lockChecks++
        return { locked: true, hostAppHint: 'Cursor' }
      }
      let caught: unknown
      try {
        await manager.prompt(sessionId, 'turn 3 while external terminal locked')
      } catch (err) {
        caught = err
      }
      expect(caught).toBeInstanceOf(HubRpcError)
      const rpcErr = caught as HubRpcError
      expect(rpcErr.code).toBe(ErrorCode.Conflict)
      expect(rpcErr.message).toContain('Cursor')
      expect(lockChecks).toBe(2)
    } finally {
      fs.rmSync(cwd, { recursive: true, force: true })
    }
  })

  it('[SL4] SessionManager.prompt awaits in-flight close() from releaseIdleHostWrapperIfDiskAdvanced before checking lock and respawning', async () => {
    const convId = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
    const sessionId = `host:agy:${convId}`
    const cwd = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-agy-close-race-'))

    try {
      const timeline: string[] = []
      let diskLastSeq = 1
      let lockHeldByClosingWorker = false
      let openCount = 0

      const fakeAgyAdapter: EngineAdapter = {
        id: 'agy',
        displayName: 'Antigravity',
        kind: 'cli',
        probe: async () => ({
          installed: true,
          ready: true,
          authStatus: 'authenticated',
        }),
        capabilities: () => ({
          permissions: false,
          images: true,
          modelSwitch: true,
          modes: true,
          history: true,
          cancel: true,
        }),
        openSession: async (init: EngineSessionInit): Promise<EngineSession> => {
          openCount++
          const instanceNum = openCount
          let alive = true
          lockHeldByClosingWorker = true
          timeline.push(`open#${instanceNum}`)
          return {
            sessionId: init.sessionId,
            cwd: init.cwd,
            isAlive: () => alive,
            prompt: async (turnId: string) => {
              timeline.push(`prompt#${instanceNum}`)
              init.emit({ type: 'session.state', state: 'running' })
              init.emit({ type: 'message.assistant.delta', messageId: `m_${turnId}`, text: 'ok' })
              init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
              init.emit({ type: 'session.state', state: 'idle' })
            },
            cancel: async () => undefined,
            respondPermission: async () => undefined,
            close: async () => {
              timeline.push(`close-start#${instanceNum}`)
              await new Promise(resolve => setTimeout(resolve, 40))
              alive = false
              lockHeldByClosingWorker = false
              timeline.push(`close-end#${instanceNum}`)
            },
          }
        },
      }

      const makeMeta = (): SessionMeta => ({
        id: sessionId,
        engineId: 'agy',
        cwd,
        title: 'close-race-demo',
        state: 'idle',
        createdAt: 1000,
        updatedAt: 1000,
        lastSeq: diskLastSeq,
        source: 'host',
      })

      const fakeInspector = {
        setCallbacks: () => undefined,
        setMetaStore: () => undefined,
        getHomeDir: () => tmpLeworkHome,
        getDiscoveredSubagents: () => [],
        retainWatch: () => undefined,
        releaseWatch: () => undefined,
        getActiveHostPermission: () => null,
        readHostSessionEvents: (id: string) =>
          id === sessionId
            ? {
                session: makeMeta(),
                events: [
                  {
                    seq: diskLastSeq,
                    ts: 1000,
                    sessionId,
                    type: 'message.user' as const,
                    messageId: `u_${diskLastSeq}`,
                    text: 'seed',
                  },
                ],
              }
            : null,
        getHostSession: (id: string) =>
          id === sessionId
            ? { nativeId: convId, filePath: path.join(cwd, 'transcript.jsonl'), meta: makeMeta() }
            : undefined,
        tryPromptLiveHostSession: async () => false,
        isAgyLockedInInteractiveTerminal: async () => {
          timeline.push(`lock-check(held=${lockHeldByClosingWorker})`)
          return { locked: lockHeldByClosingWorker }
        },
      } as unknown as HostTaskInspector

      const manager = new SessionManager(
        [cwd],
        [fakeAgyAdapter],
        { onEvent: () => undefined, onSessionUpdated: () => undefined },
        undefined,
        fakeInspector,
      )

      // Turn 1 spawns worker #1
      await manager.prompt(sessionId, 'turn 1')

      // Simulate disk advancing (e.g. post-turn transcript flush) triggering async release of idle wrapper #1
      diskLastSeq = 999
      manager.subscribeSnapshot(sessionId, 0)

      // Immediately fire Turn 2 BEFORE worker #1's 40ms close() finishes!
      await manager.prompt(sessionId, 'turn 2 after disk advance')

      expect(openCount).toBe(2)
      const closeEndIdx = timeline.indexOf('close-end#1')
      const secondLockCheckIdx = timeline.lastIndexOf('lock-check(held=false)')
      const secondOpenIdx = timeline.indexOf('open#2')
      expect(closeEndIdx).toBeGreaterThan(-1)
      expect(secondLockCheckIdx).toBeGreaterThan(closeEndIdx)
      expect(secondOpenIdx).toBeGreaterThan(secondLockCheckIdx)
    } finally {
      fs.rmSync(cwd, { recursive: true, force: true })
    }
  })
})
