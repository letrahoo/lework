import fs from 'node:fs'
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import zlib from 'node:zlib'
import { afterEach, describe, expect, it } from 'vitest'
import type { AgentEvent, SessionMeta } from '@lework/protocol'
import { collectLanIpv4Addresses } from '../src/bonjour.ts'
import { AgyEngineAdapter } from '../src/engines/agy-adapter.ts'
import { SessionManager } from '../src/session-manager.ts'
import {
  HostTaskInspector,
  extractAgyUserRequests,
  filterAgyQueuedFromHistory,
} from '../src/storage/host-history.ts'
import { TerminalBridge } from '../src/storage/terminal-bridge.ts'

describe('HostTaskInspector sandboxed replay & regression suite (M1, M2, M5, M6)', () => {
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

  it('[T2.2 / T2.3] extracts multi-USER_REQUEST blocks & handles sub-second .000Z rounding', () => {
    const raw = [
      '<CONTEXT_SUMMARY>\nOld summary...\n</CONTEXT_SUMMARY>',
      '<USER_REQUEST>\n第一条问题\n</USER_REQUEST>',
      '<ADDITIONAL_METADATA>\nThe current local time is: 2026-11-19T11:14:45+08:00.\n</ADDITIONAL_METADATA>',
      '<USER_REQUEST>\n第二条追加问题\n</USER_REQUEST>',
      '<ADDITIONAL_METADATA>\nThe current local time is: 2026-11-19T11:25:09+08:00.\n</ADDITIONAL_METADATA>',
    ].join('\n')

    expect(extractAgyUserRequests(raw)).toEqual(['第一条问题', '第二条追加问题'])
    expect(extractAgyUserRequests('<SYSTEM_MESSAGE>\n[Notice] Restarted\n</SYSTEM_MESSAGE>')).toEqual([])
    expect(extractAgyUserRequests('<CONTEXT_SUMMARY>\nCompacted\n</CONTEXT_SUMMARY>')).toEqual([])

    const prompts = [
      { text: '旧问题1', ts: 1795023000123 },
      { text: '旧问题2', ts: 1795059533650 },
      { text: '真正排队的下一条问题', ts: 1795059671154 },
    ]
    expect(filterAgyQueuedFromHistory(prompts, 1795059533000, ['旧问题2'])).toEqual([
      '真正排队的下一条问题',
    ])
  })

  it('[T2.1 / T2.4 / T2.5] never leaks 20 historical prompts into queuedPrompts on >200-line running AGY transcripts, clears on idle, and preserves watched metadata', () => {
    const home = makeFakeHome()
    const convId = 'cd3513de-0000-4000-8000-000000000001'
    const logsDir = path.join(home, '.gemini', 'antigravity-cli', 'brain', convId, '.system_generated', 'logs')
    fs.mkdirSync(logsDir, { recursive: true })
    const wsPath = path.join(home, 'work', 'lework-repo')
    fs.mkdirSync(wsPath, { recursive: true })

    const baseSec = Math.floor(Date.now() / 1000) - 600
    const transcriptLines: string[] = []
    const historyLines: string[] = []

    // 20 historical turns (>200 lines total)
    for (let turn = 0; turn < 20; turn++) {
      const turnSec = baseSec + turn * 20
      const iso = new Date(turnSec * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z')
      const q = `历史第 ${turn + 1} 个提问：检查模块状态`
      historyLines.push(
        JSON.stringify({
          conversationId: convId,
          workspace: wsPath,
          display: q,
          timestamp: turnSec * 1000 + 680, // +680ms sub-second offset!
        }),
      )
      transcriptLines.push(
        JSON.stringify({
          step_index: turn * 10,
          type: 'USER_INPUT',
          created_at: iso,
          content: `<USER_REQUEST>\n${q}\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\ntime\n</ADDITIONAL_METADATA>`,
        }),
      )
      for (let s = 1; s <= 9; s++) {
        transcriptLines.push(
          JSON.stringify({
            step_index: turn * 10 + s,
            type: s % 2 === 1 ? 'PLANNER_RESPONSE' : 'GENERIC',
            status: 'DONE',
            created_at: iso,
            tool_calls: s % 2 === 1 ? [{ name: 'read_file', args: { Path: path.join(wsPath, 'README.md') } }] : undefined,
            content: s % 2 === 0 ? 'file content line' : '',
          }),
        )
      }
    }

    // Add 30 trailing tool-call steps in the 20th turn so the last 6 lines contain ZERO USER_INPUTs!
    const activeIso = new Date((baseSec + 390) * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z')
    for (let extra = 0; extra < 30; extra++) {
      transcriptLines.push(
        JSON.stringify({
          step_index: 500 + extra,
          type: extra % 2 === 0 ? 'PLANNER_RESPONSE' : 'GENERIC',
          status: 'DONE',
          created_at: activeIso,
          tool_calls: extra % 2 === 0 ? [{ name: 'run_command', args: { CommandLine: 'ls', toolSummary: 'List dir' } }] : undefined,
          content: extra % 2 === 1 ? 'ok' : '',
        }),
      )
    }

    // Add ONE genuinely queued 21st prompt in history.jsonl
    historyLines.push(
      JSON.stringify({
        conversationId: convId,
        workspace: wsPath,
        display: '唯一真正排队的第 21 条任务',
        timestamp: (baseSec + 410) * 1000 + 125,
      }),
    )

    const tpFile = path.join(logsDir, 'transcript.jsonl')
    fs.writeFileSync(tpFile, transcriptLines.join('\n') + '\n')
    fs.writeFileSync(
      path.join(home, '.gemini', 'antigravity-cli', 'history.jsonl'),
      historyLines.join('\n') + '\n',
    )

    const inspector = new HostTaskInspector({ homeDir: home })
    const discovered = inspector.discoverHostSessions(true)
    const sess = discovered.find(s => s.id === `host:agy:${convId}`)
    expect(sess).toBeDefined()
    expect(sess?.state).toBe('running')
    expect(sess?.queuedPrompts).toEqual(['唯一真正排队的第 21 条任务'])

    const full = inspector.readHostSessionEvents(`host:agy:${convId}`)
    expect(full?.session.queuedPrompts).toEqual(['唯一真正排队的第 21 条任务'])
    expect(full?.session.lastSeq).toBeGreaterThan(100)

    // [T2.5] Re-running discoverHostSessions(true) preserves rich lastSeq on cached/watched session
    const reDiscovered = inspector.discoverHostSessions(true).find(s => s.id === `host:agy:${convId}`)
    expect(reDiscovered?.lastSeq).toBe(full?.session.lastSeq)

    // [T2.4] Once final PLANNER_RESPONSE finishes the turn -> state becomes `idle` -> queuedPrompts MUST clear!
    fs.appendFileSync(
      tpFile,
      JSON.stringify({
        step_index: 999,
        type: 'PLANNER_RESPONSE',
        status: 'DONE',
        created_at: activeIso,
        content: '全部执行完毕。',
      }) + '\n',
    )
    const afterIdle = inspector.readHostSessionEvents(`host:agy:${convId}`)
    expect(afterIdle?.session.state).toBe('idle')
    expect(afterIdle?.session.queuedPrompts).toBeUndefined()
  })

  it('[T1.2 / T1.4 / T2.6] handles Claude status="busy", queue-operation symmetry, and guaranteed fallback banner when waiting_permission in non-tmux IDE panel', async () => {
    const home = makeFakeHome()
    const claudeSessDir = path.join(home, '.claude', 'sessions')
    const claudeProjDir = path.join(home, '.claude', 'projects', '-tmp-demo')
    fs.mkdirSync(claudeSessDir, { recursive: true })
    fs.mkdirSync(claudeProjDir, { recursive: true })

    // Local Claude settings alignment ([T6.2])
    fs.writeFileSync(
      path.join(home, '.claude', 'settings.json'),
      JSON.stringify({
        model: 'gemini-4-carbon-high',
        effortLevel: 'high',
        permissions: { defaultMode: 'auto' },
      }),
    )

    const busyId = '11111111-2222-3333-4444-555555555555'
    const permId = '66666666-7777-8888-9999-000000000000'

    fs.writeFileSync(
      path.join(claudeSessDir, '5001.json'),
      JSON.stringify({
        pid: 5001,
        sessionId: busyId,
        cwd: '/tmp/demo',
        name: 'busy-claude-task',
        status: 'busy',
        updatedAt: Date.now(),
      }),
    )
    fs.writeFileSync(
      path.join(claudeProjDir, `${busyId}.jsonl`),
      [
        JSON.stringify({ type: 'user', cwd: '/tmp/demo', message: { role: 'user', content: '1. 增加一个仅限测试环境安全域名的开关' } }),
        JSON.stringify({ type: 'queue-operation', operation: 'enqueue', content: '2. 冷启还是自动拉包，但不用触发自动渲染' }),
        JSON.stringify({
          type: 'queue-operation',
          operation: 'remove',
          content: '2. 冷启还是自动拉包，但不用触发自动渲染',
          reason: 'absorbed_mid_turn',
        }),
        JSON.stringify({
          type: 'attachment',
          uuid: 'qc_uuid_2',
          attachment: {
            type: 'queued_command',
            prompt: '2. 冷启还是自动拉包，但不用触发自动渲染',
            origin: { kind: 'human' },
            humanTurn: true,
          },
        }),
        JSON.stringify({ type: 'queue-operation', operation: 'enqueue', content: '跟进排队问题 A' }),
        JSON.stringify({ type: 'queue-operation', operation: 'enqueue', content: '跟进排队问题 B' }),
        JSON.stringify({ type: 'queue-operation', operation: 'dequeue' }),
      ].join('\n') + '\n',
    )

    fs.writeFileSync(
      path.join(claudeSessDir, '5002.json'),
      JSON.stringify({
        pid: 5002,
        sessionId: permId,
        cwd: '/tmp/demo',
        name: 'datagrip-claude-perm',
        status: 'idle',
        waitingFor: 'permission prompt',
        updatedAt: Date.now(),
      }),
    )
    fs.writeFileSync(
      path.join(claudeProjDir, `${permId}.jsonl`),
      JSON.stringify({ type: 'user', cwd: '/tmp/demo', message: { role: 'user', content: 'deploy db' } }) + '\n',
    )

    const emittedEvents: AgentEvent[] = []
    const emittedUpdates: SessionMeta[] = []
    const mockBridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/usr/local/bin/tmux',
      tmuxSocketPaths: [],
      it2Bin: null,
      isPidAlive: pid => pid === 5001 || pid === 5002,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux') && args.includes('list-panes')) return '%0|/dev/ttys010\n'
        // PID 5002 is on ttys003 (DataGrip panel -> NOT in tmux!)
        if (bin === 'ps' && args[1] === 'tty=' && args.includes('5002')) return 'ttys003\n'
        if (bin === 'ps' && args[1] === 'ppid=,command=' && args.includes('5002')) {
          return '1 /Applications/DataGrip.app/Contents/MacOS/datagrip\n'
        }
        if (bin === 'ps' && args.includes('5001')) return 'ttys010\n'
        return ''
      },
    })

    const inspector = new HostTaskInspector({
      homeDir: home,
      terminalBridge: mockBridge,
      callbacks: {
        onEvents: evs => emittedEvents.push(...evs),
        onSessionUpdated: m => emittedUpdates.push(m),
      },
    })

    const defaults = inspector.getEngineHostDefaults('claude')
    expect(defaults.defaultModel).toBe('gemini-4-carbon-high')
    expect(defaults.defaultMode).toBe('auto')
    expect(defaults.defaultEffort).toBe('high')

    // [T1.4 & T2.6] `status: "busy"` is recognized as `running`, mid-turn `queued_command` becomes `message.user`, and `queuedPrompts` keeps only B!
    const busyLoaded = inspector.readHostSessionEvents(`host:claude:${busyId}`)
    expect(busyLoaded?.session.state).toBe('running')
    expect(busyLoaded?.session.queuedPrompts).toEqual(['跟进排队问题 B'])
    const userMsgs = busyLoaded?.events.filter(e => e.type === 'message.user').map(e => (e as any).text)
    expect(userMsgs).toEqual([
      '1. 增加一个仅限测试环境安全域名的开关',
      '2. 冷启还是自动拉包，但不用触发自动渲染',
    ])

    // [T1.2] Watching `permId` (in DataGrip panel) MUST emit a fallback `permission.requested` explanation card!
    inspector.retainWatch(`host:claude:${permId}`)
    await new Promise(r => setTimeout(r, 80))
    inspector.releaseWatch(`host:claude:${permId}`)
    inspector.stopAllWatches()

    const fallbackPerm = emittedEvents.find(
      (e): e is Extract<AgentEvent, { type: 'permission.requested' }> =>
        e.sessionId === `host:claude:${permId}` && e.type === 'permission.requested',
    )
    expect(fallbackPerm).toBeDefined()
    expect(fallbackPerm?.options).toEqual([])
    expect(fallbackPerm?.title).toContain('DataGrip 内置终端')
  })

  it('[T2.7] decompresses DSH v4 zstd sessions, skips delegated sub-sessions, and maps bash/skill tools', () => {
    const home = makeFakeHome()
    const topDir = path.join(home, '.dsh', 'sessions', '--work--', 'session-top')
    const subDir = path.join(home, '.dsh', 'sessions', '--work--', 'session-sub')
    fs.mkdirSync(topDir, { recursive: true })
    fs.mkdirSync(subDir, { recursive: true })

    const topLines = [
      JSON.stringify({
        type: 'session',
        id: 'dsh-top-1',
        createdAt: Date.now() - 5000,
        cwd: '/workspace/demo-app',
        delegationDepth: 0,
        isSeeded: false,
      }),
      JSON.stringify({
        type: 'session/title',
        time: Date.now() - 4900,
        data: { title: '静态资源包链路排查' },
      }),
      JSON.stringify({
        type: 'user/message',
        time: Date.now() - 4800,
        data: { source: { kind: 'system' }, content: [{ type: 'text', text: '<system-reminder>ignore</system-reminder>' }] },
      }),
      JSON.stringify({
        type: 'user/message',
        time: Date.now() - 4700,
        data: { source: { kind: 'user' }, content: [{ type: 'text', text: '检查渠道包版本' }] },
      }),
      JSON.stringify({
        type: 'tool/call',
        time: Date.now() - 4600,
        data: { callId: 'call_1', name: 'bash', arguments: JSON.stringify({ command: 'ls -la', description: '列出目录' }) },
      }),
      JSON.stringify({
        type: 'tool/result',
        time: Date.now() - 4500,
        data: { message: { toolCallId: 'call_1', content: [{ type: 'text', text: 'total 8' }] } },
      }),
      JSON.stringify({
        type: 'turn/end',
        time: Date.now() - 4400,
        data: {},
      }),
    ]
    fs.writeFileSync(path.join(topDir, 'session.v4.jsonl.zstd'), zlib.zstdCompressSync(Buffer.from(topLines.join('\n'))))

    const subLines = [
      JSON.stringify({
        type: 'session',
        id: 'dsh-sub-1',
        createdAt: Date.now(),
        cwd: '/workspace/demo-app',
        delegationDepth: 1,
        isSeeded: false,
      }),
    ]
    fs.writeFileSync(path.join(subDir, 'session.v4.jsonl.zstd'), zlib.zstdCompressSync(Buffer.from(subLines.join('\n'))))

    const inspector = new HostTaskInspector({ homeDir: home })
    const dshSessions = inspector.discoverHostSessions(true).filter(s => s.engineId === 'dsh')
    expect(dshSessions).toHaveLength(1)
    expect(dshSessions[0]?.title).toBe('静态资源包链路排查')

    const parsed = inspector.readHostSessionEvents('host:dsh:dsh-top-1')
    expect(parsed?.events.filter(e => e.type === 'message.user')).toHaveLength(1)
    const bashCall = parsed?.events.find(e => e.type === 'tool.call')
    expect(bashCall?.type === 'tool.call' && bashCall.kind).toBe('execute')
  })

  it('[T5.3] filters out loopback, VPN utun/tun/ppp, Docker bridges, and link-local 169.254.* in Bonjour LAN discovery', () => {
    const ips = collectLanIpv4Addresses({
      lo0: [{ address: '127.0.0.1', netmask: '255.0.0.0', family: 'IPv4', mac: '', internal: true, cidr: '127.0.0.1/8' }],
      utun4: [{ address: '100.64.12.8', netmask: '255.255.0.0', family: 'IPv4', mac: '', internal: false, cidr: '100.64.12.8/16' }],
      docker0: [{ address: '172.17.0.1', netmask: '255.255.0.0', family: 'IPv4', mac: '', internal: false, cidr: '172.17.0.1/16' }],
      en0: [
        { address: '169.254.10.20', netmask: '255.255.0.0', family: 'IPv4', mac: '', internal: false, cidr: '169.254.10.20/16' },
        { address: '192.168.1.88', netmask: '255.255.255.0', family: 'IPv4', mac: '', internal: false, cidr: '192.168.1.88/24' },
      ],
    })
    expect(ips).toEqual(['192.168.1.88'])
  })

  it('[T6.3] resolves Claude ANTHROPIC_DEFAULT_*_MODEL alias (e.g. opus -> gpt-6.2-astra) and AGY Gemini 4 Carbon model echo', () => {
    const home = makeFakeHome()
    fs.mkdirSync(path.join(home, '.claude'), { recursive: true })
    fs.writeFileSync(
      path.join(home, '.claude', 'settings.json'),
      JSON.stringify({
        model: 'opus',
        env: {
          ANTHROPIC_BASE_URL: 'http://127.0.0.1:8317',
          ANTHROPIC_DEFAULT_OPUS_MODEL: 'gpt-6.2-astra',
          ANTHROPIC_DEFAULT_SONNET_MODEL: 'gpt-6.2-astra',
        },
      }),
    )

    const agyDir = path.join(home, '.gemini', 'antigravity-cli')
    const convDir = path.join(agyDir, 'brain', 'conv-carbon-1', '.system_generated', 'logs')
    fs.mkdirSync(convDir, { recursive: true })
    fs.writeFileSync(
      path.join(agyDir, 'settings.json'),
      JSON.stringify({
        model: 'Gemini 4 Carbon (High)',
      }),
    )
    fs.writeFileSync(
      path.join(convDir, 'transcript.jsonl'),
      JSON.stringify({
        step_index: 0,
        type: 'USER_INPUT',
        created_at: '2026-11-20T00:00:00Z',
        content:
          '<USER_REQUEST>\n检查模型回显\n</USER_REQUEST>\n<USER_SETTINGS_CHANGE>\nThe user changed setting `Model Selection` from None to Gemini 4 Carbon (High). No need to comment.\n</USER_SETTINGS_CHANGE>',
      }) + '\n',
    )

    const inspector = new HostTaskInspector({ homeDir: home })
    expect(inspector.getEngineHostDefaults('claude').defaultModel).toBe('gpt-6.2-astra')
    expect(inspector.getEngineHostDefaults('agy').defaultModel).toBe('gemini-4-carbon')
    expect(inspector.getEngineHostDefaults('agy').defaultEffort).toBe('high')

    const agySessions = inspector.discoverHostSessions(true).filter(s => s.engineId === 'agy')
    expect(agySessions).toHaveLength(1)
    expect(agySessions[0]?.model).toBe('gemini-4-carbon')
    expect(agySessions[0]?.reasoningEffort).toBe('high')
  })

  it('[T7.2] syncs mobile prompt into live tmux AGY session without spawning a conflicting process, streams reply, and clears thinking state', async () => {
    const home = makeFakeHome()
    const wsDir = path.join(home, 'work', 'lework')
    fs.mkdirSync(wsDir, { recursive: true })
    const convId = 'c7757162-047a-4b72-adcd-8d76a7c73f99'
    const logsDir = path.join(home, '.gemini', 'antigravity-cli', 'brain', convId, '.system_generated', 'logs')
    fs.mkdirSync(logsDir, { recursive: true })
    const tpFile = path.join(logsDir, 'transcript.jsonl')
    fs.writeFileSync(
      tpFile,
      [
        JSON.stringify({
          step_index: 0,
          type: 'USER_INPUT',
          created_at: '2026-11-22T08:00:00Z',
          content: '<USER_REQUEST>\n初始终端对话\n</USER_REQUEST>',
        }),
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          status: 'DONE',
          created_at: '2026-11-22T08:00:05Z',
          content: '初始回复完成。',
        }),
      ].join('\n') + '\n',
    )

    const injectedTexts: string[] = []
    let spawnedAdapterSessions = 0
    const mockBridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/opt/homebrew/bin/tmux',
      it2Bin: null,
      tmuxSocketPaths: ['/private/tmp/tmux-501/default'],
      isPidAlive: pid => pid === 10253,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux') && args.includes('list-panes')) {
          return '%0|/dev/ttys001|0\n'
        }
        if (bin === 'ps' && args[0] === '-axo') {
          return '10253 ttys001 agy -c\n'
        }
        if (bin === 'lsof' || bin.endsWith('/lsof')) {
          return [
            'p10253',
            'fcwd',
            `n${wsDir}`,
            'f10',
            `n${home}/.gemini/antigravity-cli/presence/${convId}.lock`,
          ].join('\n')
        }
        if (bin.endsWith('tmux') && args.includes('capture-pane')) {
          return '> \n  auto · Gemini 4 Carbon · high\n'
        }
        if (bin.endsWith('tmux') && args.includes('send-keys') && args.includes('-l')) {
          injectedTexts.push(args.at(-1) ?? '')
        }
        return ''
      },
    })

    const inspector = new HostTaskInspector({ homeDir: home, terminalBridge: mockBridge })
    const events: AgentEvent[] = []
    const updates: SessionMeta[] = []
    const sm = new SessionManager(
      [home],
      [
        {
          id: 'agy',
          displayName: 'AGY',
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
          openSession: async () => {
            spawnedAdapterSessions++
            throw new Error('Should not spawn background AGY process when live tmux terminal is active!')
          },
        },
      ],
      {
        onEvent: ev => events.push(ev),
        onSessionUpdated: m => updates.push({ ...m }),
      },
      undefined,
      inspector,
    )

    const sid = `host:agy:${convId}`
    sm.retainHostWatch(sid)
    const snap0 = sm.subscribeSnapshot(sid, -1)
    expect(snap0.session.state).toBe('idle')

    await sm.prompt(sid, '从手机发往当前终端的消息')
    expect(spawnedAdapterSessions).toBe(0)
    expect(injectedTexts).toEqual(['从手机发往当前终端的消息'])
    expect(updates.at(-1)?.state).toBe('running')

    // Simulate live `agy` TUI on Mac writing the injected USER_INPUT + PLANNER_RESPONSE to transcript.jsonl
    const nowIso = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
    fs.appendFileSync(
      tpFile,
      [
        JSON.stringify({
          step_index: 2,
          type: 'USER_INPUT',
          created_at: nowIso,
          content: '<USER_REQUEST>\n从手机发往当前终端的消息\n</USER_REQUEST>',
        }),
        JSON.stringify({
          step_index: 3,
          type: 'PLANNER_RESPONSE',
          status: 'DONE',
          created_at: nowIso,
          content: '电脑终端已收到并同步回复手机！',
        }),
      ].join('\n') + '\n',
    )

    await new Promise(r => setTimeout(r, 650))
    sm.releaseHostWatch(sid)
    await sm.closeAll()

    expect(events.some(e => e.type === 'message.user' && e.text === '从手机发往当前终端的消息')).toBe(true)
    expect(
      events.some(e => e.type === 'message.assistant.delta' && e.text === '电脑终端已收到并同步回复手机！'),
    ).toBe(true)
    expect(updates.at(-1)?.state).toBe('idle')
  })

  it('[T7.3] surfaces AGY stream-json stderr failures and returns session state to idle instead of hanging on thinking', async () => {
    const fakeBinDir = makeFakeHome()
    const fakeAgy = path.join(fakeBinDir, 'fake-agy.mjs')
    fs.writeFileSync(
      fakeAgy,
      [
        '#!/usr/bin/env node',
        'process.stdin.once("data", () => {',
        '  process.stderr.write("warning: --mode auto is not available\\n");',
        '  process.stderr.write("FAILED_PRECONDITION (code 400): User location is not supported for the API use.\\n");',
        '  process.exit(1);',
        '});',
      ].join('\n'),
      { mode: 0o755 },
    )

    const adapter = new AgyEngineAdapter({
      binary: process.execPath,
      args: [fakeAgy],
    })
    const emitted: Array<{ type: string; state?: string; message?: string; stopReason?: string }> = []
    const session = await adapter.openSession({
      sessionId: 'sess_test_agy_err',
      cwd: fakeBinDir,
      emit: ev => emitted.push(ev as any),
    })

    await session.prompt('t1', 'hello')
    await session.close()

    expect(emitted.some(e => e.type === 'error' && e.message?.includes('FAILED_PRECONDITION'))).toBe(true)
    expect(emitted.some(e => e.type === 'turn.done' && e.stopReason === 'error')).toBe(true)
    expect(emitted.filter(e => e.type === 'session.state').at(-1)?.state).toBe('idle')
  })

  it('[T7.4] deduplicates Hub-created AGY sessions against native host:agy:* brains, resolves workspace_uris from conversation_summaries.db, and hides subagents', async () => {
    const home = makeFakeHome()
    const agyRoot = path.join(home, '.gemini', 'antigravity-cli')
    const leviWs = path.join(home, 'my-projects', 'levi')
    fs.mkdirSync(leviWs, { recursive: true })
    fs.mkdirSync(agyRoot, { recursive: true })

    // Create conversation_summaries.db with:
    // 1) `conv-hub-1` (headless session created via Hub in `home`)
    // 2) `conv-levi-1` (headless session in `~/my-projects/levi` with no history.jsonl entry)
    // 3) `conv-sub-1` (delegated subagent with `parent_conversation_id` & `nesting_depth = 1`)
    const req = createRequire(import.meta.url)
    const { DatabaseSync } = req('node:sqlite') as any
    const db = new DatabaseSync(path.join(agyRoot, 'conversation_summaries.db'))
    db.exec(`
      CREATE TABLE conversation_summaries (
        conversation_id TEXT PRIMARY KEY,
        title TEXT NOT NULL DEFAULT '',
        workspace_uris TEXT NOT NULL DEFAULT '',
        parent_conversation_id TEXT NOT NULL DEFAULT '',
        nesting_depth INTEGER NOT NULL DEFAULT 0
      );
    `)
    const ins = db.prepare(
      'INSERT INTO conversation_summaries (conversation_id, title, workspace_uris, parent_conversation_id, nesting_depth) VALUES (?, ?, ?, ?, ?)',
    )
    ins.run('conv-hub-1', '关于我了解哪些', JSON.stringify([pathToFileURL(home).href]), '', 0)
    ins.run('conv-levi-1', '仓库进度检查', JSON.stringify([pathToFileURL(leviWs).href]), '', 0)
    ins.run('conv-sub-1', 'Subagent Auditor', JSON.stringify([pathToFileURL(leviWs).href]), 'conv-levi-1', 1)
    db.close()

    for (const [cid, prompt] of [
      ['conv-hub-1', '关于我，你了解哪些'],
      ['conv-levi-1', '当前仓库是什么进度'],
      ['conv-sub-1', 'Audit security'],
    ] as const) {
      const dir = path.join(agyRoot, 'brain', cid, '.system_generated', 'logs')
      fs.mkdirSync(dir, { recursive: true })
      fs.writeFileSync(
        path.join(dir, 'transcript.jsonl'),
        [
          JSON.stringify({
            step_index: 0,
            type: 'USER_INPUT',
            created_at: '2026-11-22T13:11:03Z',
            content: `<USER_REQUEST>\n${prompt}\n</USER_REQUEST>`,
          }),
          JSON.stringify({
            step_index: 1,
            type: 'PLANNER_RESPONSE',
            status: 'DONE',
            created_at: '2026-11-22T13:11:08Z',
            content: '已完成。',
          }),
        ].join('\n') + '\n',
      )
    }

    const fakeAgy = path.join(home, 'fake-agy-ok.mjs')
    fs.writeFileSync(
      fakeAgy,
      [
        '#!/usr/bin/env node',
        'process.stdin.on("data", () => {',
        '  process.stdout.write(JSON.stringify({ event: "init", conversation_id: "conv-hub-1" }) + "\\n");',
        '  process.stdout.write(JSON.stringify({ event: "result", result: { conversation_id: "conv-hub-1", status: "SUCCESS", response: "OK" } }) + "\\n");',
        '});',
      ].join('\n'),
      { mode: 0o755 },
    )

    const inspector = new HostTaskInspector({ homeDir: home })
    const sm = new SessionManager(
      [home],
      [new AgyEngineAdapter({ binary: process.execPath, args: [fakeAgy] })],
      { onEvent: () => {}, onSessionUpdated: () => {} },
      undefined,
      inspector,
    )

    const created = await sm.createSession({ engineId: 'agy', cwd: home })
    await sm.prompt(created.id, '关于我，你了解哪些')
    await new Promise(r => setTimeout(r, 120))

    const listed = sm.listSessions('agy')
    await sm.closeAll()

    // 1. `conv-hub-1` ONLY appears once (as `created.id`), NEVER duplicated as `host:agy:conv-hub-1` under `/work`!
    expect(listed.some(s => s.id === created.id && s.cwd === fs.realpathSync(home))).toBe(true)
    expect(listed.some(s => s.id === 'host:agy:conv-hub-1')).toBe(false)

    // 2. `conv-levi-1` resolves its exact `~/my-projects/levi` workspace from `conversation_summaries.db` (never `/work`)!
    const leviSess = listed.find(s => s.id === 'host:agy:conv-levi-1')
    expect(leviSess?.cwd).toBe(fs.realpathSync(leviWs))

    // 3. Subagent `conv-sub-1` is excluded from session list!
    expect(listed.some(s => s.id === 'host:agy:conv-sub-1')).toBe(false)
  })
})
