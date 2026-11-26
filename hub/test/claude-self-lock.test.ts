import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import type { EngineAdapter, EngineSession } from '../src/engines/types.ts'
import { SessionManager } from '../src/session-manager.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'
import { TerminalBridge } from '../src/storage/terminal-bridge.ts'

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

describe('Claude self-lock false positive prevention (Hub ACP/SDK subprocess)', () => {
  it('[CSL1] ignores Hub own claude-agent-acp -> claude (sdk-ts) grandchild and headless non-TTY processes', async () => {
    const hubPid = 3377105
    const acpPid = 3380171
    const sdkPid = 3380199
    const sessId = 'b2fe3574-e1f9-4bb2-bee5-981582610518'

    const bridge = new TerminalBridge({
      tmuxBin: null,
      it2Bin: null,
      selfPid: hubPid,
      isPidAlive: pid => pid === sdkPid || pid === acpPid || pid === hubPid,
      runCommand: async (bin, args) => {
        if (bin === 'ps' && args[0] === '-o' && args[1] === 'tty=') {
          return '?\n'
        }
        if (bin === 'ps' && args[0] === '-o' && args[1] === 'ppid=,command=') {
          if (args[3] === String(sdkPid)) return `${acpPid} /opt/claude-agent-sdk/claude --input-format stream-json\n`
          if (args[3] === String(acpPid)) return `${hubPid} node claude-agent-acp/dist/index.js\n`
          if (args[3] === String(hubPid)) return `1 /Applications/Cursor.app/Contents/MacOS/Cursor\n`
        }
        return ''
      },
    })

    // 1) Explicit sdk-ts entrypoint
    const resSdk = await bridge.sendPromptToSession(
      {
        nativeId: sessId,
        engineId: 'claude',
        cwd: '/data00/home/alice',
        claudeRuntime: {
          pid: sdkPid,
          sessionId: sessId,
          cwd: '/data00/home/alice',
          entrypoint: 'sdk-ts',
          status: 'idle',
        },
      },
      '继续分析',
    )
    expect(resSdk).toEqual({ delivered: false, reason: 'not_running' })

    // 2) Even without entrypoint field, Hub grandchild (ownedBySelf) MUST return not_running
    const resGrandchild = await bridge.sendPromptToSession(
      {
        nativeId: sessId,
        engineId: 'claude',
        cwd: '/data00/home/alice',
        claudeRuntime: {
          pid: sdkPid,
          sessionId: sessId,
          cwd: '/data00/home/alice',
          status: 'idle',
        },
      },
      '继续分析',
    )
    expect(resGrandchild).toEqual({ delivered: false, reason: 'not_running' })
  })

  it('[CSL2] still blocks genuine external interactive Claude CLI on an unreachable IDE PTY', async () => {
    const hubPid = 3377105
    const externalClaudePid = 48120
    const sessId = 'b2fe3574-e1f9-4bb2-bee5-981582610518'

    const bridge = new TerminalBridge({
      tmuxBin: null,
      it2Bin: null,
      selfPid: hubPid,
      isPidAlive: pid => pid === externalClaudePid,
      runCommand: async (bin, args) => {
        if (bin === 'ps' && args[0] === '-o' && args[1] === 'tty=' && args[3] === String(externalClaudePid)) {
          return 'ttys004\n'
        }
        if (bin === 'ps' && args[0] === '-o' && args[1] === 'ppid=,command=') {
          if (args[3] === String(externalClaudePid)) return `48100 /bin/zsh -l\n`
          if (args[3] === '48100') return `1 /Applications/GoLand.app/Contents/MacOS/goland\n`
        }
        return ''
      },
    })

    const res = await bridge.sendPromptToSession(
      {
        nativeId: sessId,
        engineId: 'claude',
        cwd: '/tmp/repo',
        claudeRuntime: {
          pid: externalClaudePid,
          sessionId: sessId,
          cwd: '/tmp/repo',
          entrypoint: 'cli',
          status: 'idle',
        },
      },
      'hello',
    )
    expect(res).toEqual({ delivered: false, reason: 'unreachable_terminal', hostAppHint: 'GoLand' })
  })

  it('[CSL3] readClaudeRuntimeMap prefers interactive CLI session over sdk-ts subprocess for the same sessionId', () => {
    const tmpHome = makeTempDir('lework-claude-rt-map-')
    const sessionsDir = path.join(tmpHome, '.claude', 'sessions')
    fs.mkdirSync(sessionsDir, { recursive: true })
    const sessId = 'b2fe3574-e1f9-4bb2-bee5-981582610518'

    // Write CLI file first (1000.json) and SDK file second (9999.json) to test overwrite protection
    fs.writeFileSync(
      path.join(sessionsDir, '1000.json'),
      JSON.stringify({ pid: 1000, sessionId: sessId, cwd: '/tmp', entrypoint: 'cli', status: 'idle' }),
      'utf8',
    )
    fs.writeFileSync(
      path.join(sessionsDir, '9999.json'),
      JSON.stringify({ pid: 9999, sessionId: sessId, cwd: '/tmp', entrypoint: 'sdk-ts', status: 'idle' }),
      'utf8',
    )

    const bridge = new TerminalBridge({
      homeDir: tmpHome,
      isPidAlive: pid => pid === 1000 || pid === 9999,
    })

    const map = bridge.readClaudeRuntimeMap()
    expect(map.get(sessId)?.pid).toBe(1000)
    expect(map.get(sessId)?.entrypoint).toBe('cli')
  })

  it('[CSL4] SessionManager.prompt succeeds on Turn 2 for host:claude:* when Hub ACP spawns an sdk-ts session file', async () => {
    const root = fs.realpathSync(makeTempDir('lework-claude-turn2-'))
    const fakeHome = path.join(root, 'home')
    const metaDir = path.join(root, 'lework-meta')
    const nativeId = 'b2fe3574-e1f9-4bb2-bee5-981582610518'
    const sessionId = `host:claude:${nativeId}`
    const projectDir = path.join(fakeHome, '.claude', 'projects', '-tmp-demo')
    const sessionsDir = path.join(fakeHome, '.claude', 'sessions')
    fs.mkdirSync(projectDir, { recursive: true })
    fs.mkdirSync(sessionsDir, { recursive: true })
    fs.mkdirSync(metaDir, { recursive: true })

    fs.writeFileSync(
      path.join(projectDir, `${nativeId}.jsonl`),
      `${JSON.stringify({
        type: 'user',
        cwd: fakeHome,
        timestamp: new Date().toISOString(),
        message: { role: 'user', content: '初始问题' },
      })}\n`,
      'utf8',
    )

    const prevLeworkHome = process.env.LEWORK_HOME
    process.env.LEWORK_HOME = metaDir
    try {
      const sdkPid = process.pid // Alive PID, owned by self
      let promptCalls = 0
      const adapter: EngineAdapter = {
        id: 'claude',
        displayName: 'Claude Code',
        kind: 'cli',
        capabilities: () => ({
          permissions: true,
          images: true,
          modelSwitch: true,
          modes: true,
          history: true,
          cancel: true,
        }),
        probe: async () => ({ installed: true, ready: true, authStatus: 'authenticated' }),
        openSession: async init => {
          // Simulate @anthropic-ai/claude-agent-sdk creating ~/.claude/sessions/<pid>.json on Turn 1!
          fs.writeFileSync(
            path.join(sessionsDir, `${sdkPid}.json`),
            JSON.stringify({
              pid: sdkPid,
              sessionId: nativeId,
              cwd: init.cwd,
              entrypoint: 'sdk-ts',
              kind: 'interactive',
              status: 'idle',
            }),
            'utf8',
          )
          const handle: EngineSession = {
            sessionId: init.sessionId,
            cwd: init.cwd,
            isAlive: () => true,
            prompt: async turnId => {
              promptCalls++
              init.emit({ type: 'session.state', state: 'running' })
              init.emit({
                type: 'message.assistant.delta',
                messageId: `m_${turnId}`,
                text: `reply #${promptCalls}`,
              })
              init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
              init.emit({ type: 'session.state', state: 'idle' })
            },
            cancel: async () => {},
            respondPermission: async () => {},
            close: async () => {},
          }
          return handle
        },
      }

      const bridge = new TerminalBridge({
        homeDir: fakeHome,
        tmuxBin: null,
        it2Bin: null,
        selfPid: process.pid,
        isPidAlive: pid => pid === sdkPid,
        runCommand: async (bin, args) => {
          if (bin === 'ps' && args[0] === '-o' && args[1] === 'tty=') return '?\n'
          return ''
        },
      })
      const inspector = new HostTaskInspector({ homeDir: fakeHome, terminalBridge: bridge })
      const sm = new SessionManager(
        [fakeHome],
        [adapter],
        { onEvent: () => {}, onSessionUpdated: () => {} },
        undefined,
        inspector,
      )

      expect(sm.listSessions().some(s => s.id === sessionId)).toBe(true)

      // Turn 1: materializes host:claude:* session and writes sdk-ts runtime file
      await sm.prompt(sessionId, '第一条消息')
      expect(promptCalls).toBe(1)

      // Turn 2: MUST reuse Hub's own live session without throwing -32003 self-lock error!
      await sm.prompt(sessionId, '第二条消息')
      expect(promptCalls).toBe(2)

      await sm.closeAll()
    } finally {
      if (prevLeworkHome === undefined) delete process.env.LEWORK_HOME
      else process.env.LEWORK_HOME = prevLeworkHome
    }
  })
})
