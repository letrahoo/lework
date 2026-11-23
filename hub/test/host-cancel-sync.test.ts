import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import type { AgentEvent, SessionMeta } from '@lework/protocol'
import { SessionManager } from '../src/session-manager.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'
import { isAgyTailActivelyRunning, parseAgyTranscript } from '../src/storage/host-history/agy-history.ts'
import {
  isClaudeTailActivelyRunning,
  parseClaudeTranscript,
} from '../src/storage/host-history/claude-history.ts'
import { TerminalBridge } from '../src/storage/terminal-bridge.ts'

describe('Host cancel & UI state sync regression suite', () => {
  const tempDirs: string[] = []

  function makeFakeHome(): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-test-home-'))
    tempDirs.push(dir)
    return dir
  }

  afterEach(() => {
    for (const d of tempDirs.splice(0)) {
      fs.rmSync(d, { recursive: true, force: true })
    }
  })

  it('[T8.1] cancelling live host:agy session immediately transitions to idle, resists 75s mtime bounce across poll/discovery, and resumes cleanly on follow-up prompt', async () => {
    const home = makeFakeHome()
    const wsDir = path.join(home, 'work', 'demo-app')
    fs.mkdirSync(wsDir, { recursive: true })
    const convId = 'aa11bb22-3344-4555-8666-77889900aabb'
    const logsDir = path.join(home, '.gemini', 'antigravity-cli', 'brain', convId, '.system_generated', 'logs')
    fs.mkdirSync(logsDir, { recursive: true })
    const tpFile = path.join(logsDir, 'transcript.jsonl')

    const nowIso = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
    // Transcript ends mid-turn on an in-progress PLANNER_RESPONSE with tool_calls (so parser sees `isRunning: true`!)
    fs.writeFileSync(
      tpFile,
      [
        JSON.stringify({
          step_index: 0,
          type: 'USER_INPUT',
          created_at: nowIso,
          content: '<USER_REQUEST>\n运行完整测试并分析结果\n</USER_REQUEST>',
        }),
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          status: 'RUNNING',
          created_at: nowIso,
          tool_calls: [{ id: 'tc_long', name: 'run_command', args: { CommandLine: 'pnpm test' } }],
        }),
      ].join('\n') + '\n',
    )

    let screenLines = '⣷ Running command...\n> \nesc to cancel   auto · Gemini 4 Carbon · high\n'
    const sentKeys: string[][] = []
    const mockBridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/opt/homebrew/bin/tmux',
      it2Bin: null,
      tmuxSocketPaths: ['/private/tmp/tmux-501/default'],
      isPidAlive: pid => pid === 31001,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux') && args.includes('list-panes')) return '%4|/dev/ttys012|0\n'
        if (bin === 'ps' && args[0] === '-axo') return '31001 ttys012 agy -c\n'
        if (bin === 'lsof' || bin.endsWith('/lsof')) {
          return `p31001\nfcwd\nn${wsDir}\nf9\nn${home}/.gemini/antigravity-cli/presence/${convId}.lock\n`
        }
        if (bin.endsWith('tmux') && args.includes('capture-pane')) return screenLines
        if (bin.endsWith('tmux') && args.includes('send-keys')) {
          sentKeys.push(args)
        }
        return ''
      },
    })

    const emittedEvents: AgentEvent[] = []
    const updates: SessionMeta[] = []
    const inspector = new HostTaskInspector({
      homeDir: home,
      terminalBridge: mockBridge,
      callbacks: {
        onEvents: evs => emittedEvents.push(...evs),
        onSessionUpdated: m => updates.push({ ...m }),
      },
    })

    const sid = `host:agy:${convId}`
    inspector.retainWatch(sid)
    await inspector.syncTerminalNow(sid)
    expect(inspector.readHostSessionEvents(sid)?.session.state).toBe('running')

    // Click Stop: sends Escape, immediately broadcasts `idle`, and records cancel watermark
    const cancelled = await inspector.cancelLiveHostSession(sid)
    expect(cancelled).toBe(true)
    expect(sentKeys.some(args => args.at(-1) === 'Escape')).toBe(true)
    expect(updates.at(-1)?.state).toBe('idle')
    expect(updates.at(-1)?.liveStatusText).toBeUndefined()
    expect(updates.at(-1)?.turnStartedAt).toBeUndefined()
    expect(updates.at(-1)?.queuedPrompts).toBeUndefined()

    // Even while terminal briefly still renders a residual spinner during the 1.8s grace window,
    // or after terminal stops while transcript.jsonl still ends mid-turn (< 75s mtime),
    // EVERY read/poll/discovery path MUST stay `idle`!
    await inspector.syncTerminalNow(sid)
    expect(inspector.readHostSessionEvents(sid)?.session.state).toBe('idle')
    expect(inspector.discoverHostSessions(true).find(s => s.id === sid)?.state).toBe('idle')

    screenLines = '> \n? for shortcuts   auto · Gemini 4 Carbon · high\n'
    // Simulate terminal flushing an abort TOOL_RESPONSE right after Escape
    fs.appendFileSync(
      tpFile,
      JSON.stringify({
        step_index: 2,
        type: 'TOOL_RESPONSE',
        status: 'DONE',
        created_at: nowIso,
        content: 'Command terminated by user',
      }) + '\n',
    )
    await inspector.syncTerminalNow(sid)
    expect(inspector.readHostSessionEvents(sid)?.session.state).toBe('idle')
    expect(inspector.discoverHostSessions(true).find(s => s.id === sid)?.state).toBe('idle')
    expect(updates.at(-1)?.state).toBe('idle')

    // Sending a follow-up prompt clears the watermark and transitions cleanly to `running` without bogus queuedPrompts!
    const delivered = await inspector.tryPromptLiveHostSession(sid, '重新只跑单测', '重新只跑单测')
    expect(delivered).toBe(true)
    expect(updates.at(-1)?.state).toBe('running')
    expect(updates.at(-1)?.queuedPrompts).toBeUndefined()

    inspector.releaseWatch(sid)
    inspector.stopAllWatches()

    // Also verify AGY transcript parser handles explicit CANCELLED/INTERRUPTED step statuses
    const cancelledTp = path.join(logsDir, 'cancelled-sample.jsonl')
    fs.writeFileSync(
      cancelledTp,
      [
        JSON.stringify({
          step_index: 0,
          type: 'USER_INPUT',
          created_at: nowIso,
          content: '<USER_REQUEST>\n执行任务\n</USER_REQUEST>',
        }),
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          status: 'DONE',
          created_at: nowIso,
          tool_calls: [{ id: 'tc_abort_1', name: 'run_command', args: { CommandLine: 'sleep 99' } }],
        }),
        JSON.stringify({
          step_index: 2,
          type: 'TOOL_RESPONSE',
          status: 'CANCELLED',
          created_at: nowIso,
          content: '',
        }),
      ].join('\n') + '\n',
    )
    const parsedCancelled = parseAgyTranscript('host:agy:cancelled', cancelledTp)
    expect(parsedCancelled.isRunning).toBe(false)
    expect(parsedCancelled.turnStartedAt).toBeUndefined()
    const failedUpdate = parsedCancelled.events.find(
      (e): e is Extract<AgentEvent, { type: 'tool.update' }> =>
        e.type === 'tool.update' && e.toolCallId === 'tc_abort_1',
    )
    expect(failedUpdate?.status).toBe('failed')
    expect(failedUpdate?.output).toBe('已停止')
    expect(
      isAgyTailActivelyRunning([
        JSON.stringify({ step_index: 2, type: 'TOOL_RESPONSE', status: 'INTERRUPTED', content: '' }),
      ]),
    ).toBe(false)
  })

  it('[T8.2] cancelling live host:claude session ignores stale busy metadata and marks [Request interrupted by user] idle with closed tools', async () => {
    const home = makeFakeHome()
    const claudeSessDir = path.join(home, '.claude', 'sessions')
    const claudeProjDir = path.join(home, '.claude', 'projects', '-tmp-cancel-demo')
    fs.mkdirSync(claudeSessDir, { recursive: true })
    fs.mkdirSync(claudeProjDir, { recursive: true })

    const nativeId = '22222222-3333-4444-5555-666666666666'
    const jsonlPath = path.join(claudeProjDir, `${nativeId}.jsonl`)
    const rtPath = path.join(claudeSessDir, '6001.json')
    const preCancelTs = Date.now() - 500

    fs.writeFileSync(
      rtPath,
      JSON.stringify({
        pid: 6001,
        sessionId: nativeId,
        cwd: '/tmp/cancel-demo',
        name: 'cancel-claude-test',
        status: 'busy',
        updatedAt: preCancelTs,
      }),
    )
    fs.writeFileSync(
      jsonlPath,
      [
        JSON.stringify({
          type: 'user',
          cwd: '/tmp/cancel-demo',
          message: { role: 'user', content: '分析整个仓库的构建脚本' },
        }),
        JSON.stringify({
          type: 'assistant',
          cwd: '/tmp/cancel-demo',
          message: {
            id: 'a_1',
            role: 'assistant',
            stop_reason: 'tool_use',
            content: [{ type: 'tool_use', id: 'tool_u1', name: 'Bash', input: { command: 'npm run build' } }],
          },
        }),
      ].join('\n') + '\n',
    )

    const mockBridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/opt/homebrew/bin/tmux',
      tmuxSocketPaths: [],
      it2Bin: null,
      isPidAlive: pid => pid === 6001,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux') && args.includes('list-panes')) return '%5|/dev/ttys020|0\n'
        if (bin === 'ps' && args[1] === 'tty=' && args.includes('6001')) return 'ttys020\n'
        if (bin.endsWith('tmux') && args.includes('capture-pane')) return '❯ \n'
        return ''
      },
    })

    const updates: SessionMeta[] = []
    const inspector = new HostTaskInspector({
      homeDir: home,
      terminalBridge: mockBridge,
      callbacks: {
        onEvents: () => {},
        onSessionUpdated: m => updates.push({ ...m }),
      },
    })

    const sid = `host:claude:${nativeId}`
    inspector.retainWatch(sid)
    expect(inspector.readHostSessionEvents(sid)?.session.state).toBe('running')

    const ok = await inspector.cancelLiveHostSession(sid)
    expect(ok).toBe(true)
    expect(updates.at(-1)?.state).toBe('idle')

    // Stale pre-cancel `status: "busy"` in ~/.claude/sessions/6001.json MUST NOT bounce state back to running!
    await inspector.syncTerminalNow(sid)
    expect(inspector.readHostSessionEvents(sid)?.session.state).toBe('idle')
    expect(inspector.discoverHostSessions(true).find(s => s.id === sid)?.state).toBe('idle')

    // When Claude appends `[Request interrupted by user]`, parser marks `isRunning: false`,
    // closes still-open `tool_u1` as `failed` (`已停止`), and does NOT emit a synthetic user bubble!
    fs.appendFileSync(
      jsonlPath,
      JSON.stringify({
        type: 'user',
        cwd: '/tmp/cancel-demo',
        message: { role: 'user', content: [{ type: 'text', text: '[Request interrupted by user]' }] },
      }) + '\n',
    )
    const parsed = parseClaudeTranscript(sid, jsonlPath)
    expect(parsed.isRunning).toBe(false)
    expect(parsed.turnStartedAt).toBeUndefined()
    expect(parsed.events.filter(e => e.type === 'message.user')).toHaveLength(1)
    const closedTool = parsed.events.find(
      (e): e is Extract<AgentEvent, { type: 'tool.update' }> =>
        e.type === 'tool.update' && e.toolCallId === 'tool_u1',
    )
    expect(closedTool?.status).toBe('failed')
    expect(closedTool?.output).toBe('已停止')
    expect(
      isClaudeTailActivelyRunning([
        JSON.stringify({
          type: 'user',
          message: { role: 'user', content: '[Request interrupted by user for tool use]' },
        }),
      ]),
    ).toBe(false)

    inspector.releaseWatch(sid)
    inspector.stopAllWatches()
  })

  it('[T8.3] cancelling while waiting_permission resolves pending permission as cancelled and returns to idle', async () => {
    const home = makeFakeHome()
    const wsDir = path.join(home, 'work', 'perm-cancel')
    fs.mkdirSync(wsDir, { recursive: true })
    const convId = 'cc33dd44-5566-4777-8888-990011223344'
    const logsDir = path.join(home, '.gemini', 'antigravity-cli', 'brain', convId, '.system_generated', 'logs')
    fs.mkdirSync(logsDir, { recursive: true })
    const tpFile = path.join(logsDir, 'transcript.jsonl')
    fs.writeFileSync(
      tpFile,
      JSON.stringify({
        step_index: 0,
        type: 'USER_INPUT',
        created_at: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
        content: '<USER_REQUEST>\n执行危险命令\n</USER_REQUEST>',
      }) + '\n',
    )

    let showingPerm = true
    const mockBridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/opt/homebrew/bin/tmux',
      it2Bin: null,
      tmuxSocketPaths: ['/private/tmp/tmux-501/default'],
      isPidAlive: pid => pid === 41009,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux') && args.includes('list-panes')) return '%6|/dev/ttys021|0\n'
        if (bin === 'ps' && args[0] === '-axo') return '41009 ttys021 agy -c\n'
        if (bin === 'lsof' || bin.endsWith('/lsof')) {
          return `p41009\nfcwd\nn${wsDir}\nf9\nn${home}/.gemini/antigravity-cli/presence/${convId}.lock\n`
        }
        if (bin.endsWith('tmux') && args.includes('capture-pane')) {
          if (showingPerm) {
            return [
              '───',
              ' Bash command',
              ' │ rm -rf build',
              ' Do you want to proceed?',
              ' ❯ 1. Yes',
              '   2. No',
            ].join('\n')
          }
          return '> \n? for shortcuts   auto · Gemini 4 Carbon · high\n'
        }
        if (bin.endsWith('tmux') && args.includes('send-keys') && args.at(-1) === 'Escape') {
          showingPerm = false
        }
        return ''
      },
    })

    const emittedEvents: AgentEvent[] = []
    const updates: SessionMeta[] = []
    const inspector = new HostTaskInspector({
      homeDir: home,
      terminalBridge: mockBridge,
      callbacks: {
        onEvents: evs => emittedEvents.push(...evs),
        onSessionUpdated: m => updates.push({ ...m }),
      },
    })

    const sid = `host:agy:${convId}`
    inspector.retainWatch(sid)
    await inspector.syncTerminalNow(sid)

    const requested = emittedEvents.find(
      (e): e is Extract<AgentEvent, { type: 'permission.requested' }> => e.type === 'permission.requested',
    )
    expect(requested).toBeDefined()
    expect(updates.at(-1)?.state).toBe('waiting_permission')
    expect(inspector.getActiveHostPermission(sid)).not.toBeNull()

    const ok = await inspector.cancelLiveHostSession(sid)
    expect(ok).toBe(true)
    expect(inspector.getActiveHostPermission(sid)).toBeNull()
    expect(updates.at(-1)?.state).toBe('idle')

    const resolved = emittedEvents.find(
      (e): e is Extract<AgentEvent, { type: 'permission.resolved' }> =>
        e.type === 'permission.resolved' && e.requestId === requested!.requestId,
    )
    expect(resolved?.outcome).toBe('cancelled')

    await inspector.syncTerminalNow(sid)
    expect(updates.at(-1)?.state).toBe('idle')

    inspector.releaseWatch(sid)
    inspector.stopAllWatches()
  })

  it('[T8.4] direct terminal interrupt on live AGY session transitions to idle after consecutive idle polls without waiting 75s', async () => {
    const home = makeFakeHome()
    const wsDir = path.join(home, 'work', 'direct-esc')
    fs.mkdirSync(wsDir, { recursive: true })
    const convId = 'dd44ee55-6677-4888-9999-aabbccddeeff'
    const logsDir = path.join(home, '.gemini', 'antigravity-cli', 'brain', convId, '.system_generated', 'logs')
    fs.mkdirSync(logsDir, { recursive: true })
    const tpFile = path.join(logsDir, 'transcript.jsonl')
    fs.writeFileSync(
      tpFile,
      JSON.stringify({
        step_index: 0,
        type: 'USER_INPUT',
        created_at: new Date(Date.now() - 6_000).toISOString().replace(/\.\d{3}Z$/, 'Z'),
        content: '<USER_REQUEST>\n直接在终端按 Esc 中断的场景\n</USER_REQUEST>',
      }) + '\n',
    )
    // File was modified 6 seconds ago (< 75s window, >= 4s terminal-idle threshold)
    const sixSecsAgo = new Date(Date.now() - 6_000)
    fs.utimesSync(tpFile, sixSecsAgo, sixSecsAgo)

    const mockBridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/opt/homebrew/bin/tmux',
      it2Bin: null,
      tmuxSocketPaths: ['/private/tmp/tmux-501/default'],
      isPidAlive: pid => pid === 42010,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux') && args.includes('list-panes')) return '%8|/dev/ttys022|0\n'
        if (bin === 'ps' && args[0] === '-axo') return '42010 ttys022 agy -c\n'
        if (bin === 'lsof' || bin.endsWith('/lsof')) {
          return `p42010\nfcwd\nn${wsDir}\nf9\nn${home}/.gemini/antigravity-cli/presence/${convId}.lock\n`
        }
        if (bin.endsWith('tmux') && args.includes('capture-pane')) {
          return '> \n? for shortcuts   auto · Gemini 4 Carbon · high\n'
        }
        return ''
      },
    })

    const updates: SessionMeta[] = []
    const inspector = new HostTaskInspector({
      homeDir: home,
      terminalBridge: mockBridge,
      callbacks: {
        onEvents: () => {},
        onSessionUpdated: m => updates.push({ ...m }),
      },
    })

    const sid = `host:agy:${convId}`
    inspector.retainWatch(sid)
    expect(inspector.readHostSessionEvents(sid)?.session.state).toBe('running')

    // Poll #1: single no-spinner check does NOT prematurely flicker mid-turn
    await inspector.syncTerminalNow(sid)
    expect(inspector.readHostSessionEvents(sid)?.session.state).toBe('running')

    // Poll #2: 2 consecutive idle terminal polls + file idle >= 4s -> transitions cleanly to idle!
    await inspector.syncTerminalNow(sid)
    expect(updates.at(-1)?.state).toBe('idle')
    expect(inspector.readHostSessionEvents(sid)?.session.state).toBe('idle')
    expect(inspector.discoverHostSessions(true).find(s => s.id === sid)?.state).toBe('idle')

    inspector.releaseWatch(sid)
    inspector.stopAllWatches()
  })

  it('[T8.5] Hub-managed SessionManager.cancel() clears live status and suppresses stale late running events', async () => {
    const wsDir = makeFakeHome()
    let emitFn: ((ev: any) => void) | null = null
    let resolveTurn: (() => void) | null = null

    const updates: SessionMeta[] = []
    const sm = new SessionManager(
      [wsDir],
      [
        {
          id: 'claude',
          displayName: 'Claude Code',
          kind: 'cli',
          probe: async () => ({ installed: true, ready: true }),
          capabilities: () => ({
            permissions: true,
            images: true,
            modelSwitch: true,
            modes: true,
            history: true,
            cancel: true,
          }),
          openSession: async init => {
            emitFn = init.emit
            return {
              sessionId: init.sessionId,
              cwd: init.cwd,
              prompt: async () => {
                await new Promise<void>(r => {
                  resolveTurn = r
                })
              },
              cancel: async () => {
                // Simulate a late stale event emitted by adapter right after cancel
                setTimeout(() => {
                  emitFn?.({ type: 'session.state', state: 'running' })
                  resolveTurn?.()
                }, 20)
              },
              respondPermission: async () => {},
              close: async () => {
                resolveTurn?.()
              },
            }
          },
        },
      ],
      {
        onEvent: () => {},
        onSessionUpdated: m => updates.push({ ...m }),
      },
    )

    const sess = await sm.createSession({ engineId: 'claude', cwd: wsDir })
    await sm.prompt(sess.id, '长时间任务')
    expect(sm.getSessionMeta(sess.id)?.state).toBe('running')

    await sm.cancel(sess.id)
    expect(sm.getSessionMeta(sess.id)?.state).toBe('idle')
    expect(sm.getSessionMeta(sess.id)?.liveStatusText).toBeUndefined()
    expect(sm.getSessionMeta(sess.id)?.turnStartedAt).toBeUndefined()

    // Wait past the delayed stale `running` emit from cancelled turn
    await new Promise(r => setTimeout(r, 60))
    expect(sm.getSessionMeta(sess.id)?.state).toBe('idle')
    expect(updates.at(-1)?.state).toBe('idle')

    await sm.closeAll()
  })
})
