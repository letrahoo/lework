import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import zlib from 'node:zlib'
import { afterEach, describe, expect, it } from 'vitest'
import type { AgentEvent, SessionMeta } from '@qprc/protocol'
import { collectLanIpv4Addresses } from '../src/bonjour.ts'
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
        JSON.stringify({ type: 'user', cwd: '/tmp/demo', message: { role: 'user', content: '1. 增加一个仅限 BOE 安全域名的开关吧' } }),
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
      '1. 增加一个仅限 BOE 安全域名的开关吧',
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
        { address: '192.168.31.88', netmask: '255.255.255.0', family: 'IPv4', mac: '', internal: false, cidr: '192.168.31.88/24' },
      ],
    })
    expect(ips).toEqual(['192.168.31.88'])
  })
})
