import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import type { AgentEvent, AgentEventBody, SessionMeta } from '@lework/protocol'
import { AgyEngineAdapter } from '../src/engines/agy-adapter.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'
import { TerminalBridge } from '../src/storage/terminal-bridge.ts'

const tmpDirs: string[] = []
function mkTmpDir(prefix = 'lework-agy-isolation-'): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
  tmpDirs.push(dir)
  return dir
}

afterEach(() => {
  while (tmpDirs.length > 0) {
    const dir = tmpDirs.pop()!
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

describe('AGY strict cross-session isolation (shared workspace without WorkTree)', () => {
  it('never binds AGY session A to live terminal B by cwd fallback when lsof omits presence locks', async () => {
    const home = mkTmpDir('lework-agy-home-')
    const repo = path.join(home, 'shared-repo')
    fs.mkdirSync(repo, { recursive: true })

    const sentTmuxCommands: string[][] = []
    const bridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/opt/homebrew/bin/tmux',
      it2Bin: null,
      tmuxSocketPaths: ['/private/tmp/tmux-501/default'],
      isPidAlive: pid => pid === 71001,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux')) {
          sentTmuxCommands.push(args)
          if (args.includes('list-panes')) {
            return '%9|/dev/ttys020|0\n'
          }
          if (args.includes('capture-pane')) {
            return [
              '───',
              ' Bash command',
              ' │ pnpm -r test',
              ' Do you want to proceed?',
              ' ❯ 1. Yes',
              '   2. No',
              '⠇ 2/4 steps · Running session B tests... 14s',
            ].join('\n')
          }
        }
        if (bin === 'ps' && args[0] === '-axo') {
          // Live terminal B in the same repo without `--conversation` flag
          return '71001 ttys020 agy\n'
        }
        if (bin === 'lsof' || bin.endsWith('/lsof')) {
          // Simulate `lsof -b -p` returning ONLY cwd (missing presence/*.lock), or holding open DB for convB
          return [
            'p71001',
            'fcwd',
            `n${repo}`,
            'f19',
            `n${home}/.gemini/antigravity-cli/conversations/conv-B-live-1111.db`,
          ].join('\n')
        }
        return ''
      },
    })

    // Session A shares the EXACT same repo cwd (no WorkTree) — MUST NOT bind to B's terminal!
    const inspectA = await bridge.inspectSessionTerminal({
      nativeId: 'conv-A-idle-0000',
      engineId: 'agy',
      cwd: repo,
    })
    expect(inspectA?.targetReachable).toBe(false)
    expect(inspectA?.pendingPermission).toBeUndefined()
    expect(inspectA?.liveStatusText).toBeUndefined()

    // Prompt sent to A MUST NOT be injected into B's tmux pane!
    sentTmuxCommands.length = 0
    const deliveryA = await bridge.sendPromptToSession(
      {
        nativeId: 'conv-A-idle-0000',
        engineId: 'agy',
        cwd: repo,
      },
      '这是发给 A 的消息，绝不能注入到 B',
    )
    expect(deliveryA).toEqual({ delivered: false, reason: 'not_running' })
    expect(sentTmuxCommands.some(args => args.includes('send-keys') || args.includes('paste-buffer'))).toBe(false)

    // Session B itself still matches cleanly via open conversation DB (`conversations/conv-B-live-1111.db`)
    const inspectB = await bridge.inspectSessionTerminal({
      nativeId: 'conv-B-live-1111',
      engineId: 'agy',
      cwd: repo,
    })
    expect(inspectB?.targetReachable).toBe(true)
    expect(inspectB?.pendingPermission?.options).toHaveLength(2)
  })

  it('resolves exact AGY terminal via presence/<convId>.lock fallback even when PID-scoped lsof -b misses it, without leaking to session A', async () => {
    const home = mkTmpDir('lework-agy-lockfb-')
    const repo = path.join(home, 'shared-repo')
    const presenceDir = path.join(home, '.gemini', 'antigravity-cli', 'presence')
    fs.mkdirSync(repo, { recursive: true })
    fs.mkdirSync(presenceDir, { recursive: true })
    const convB = 'bbbb1111-2222-4333-8444-555566667777'
    const convA = 'aaaa1111-2222-4333-8444-555566667777'
    const lockB = path.join(presenceDir, `${convB}.lock`)
    fs.writeFileSync(lockB, 'lock', 'utf8')

    const bridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/opt/homebrew/bin/tmux',
      it2Bin: null,
      lsofBin: '/usr/sbin/lsof',
      tmuxSocketPaths: ['/private/tmp/tmux-501/default'],
      selfPid: 99999,
      isPidAlive: pid => pid === 72001 || pid === 72002,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux') && args.includes('list-panes')) {
          return '%11|/dev/ttys021|0\n'
        }
        if (bin.endsWith('tmux') && args.includes('capture-pane')) {
          return '⠇ 1/3 steps · Building B...\nesc to cancel   auto · Gemini 4 Carbon · high\n'
        }
        if (bin === 'ps' && args[0] === '-axo') {
          return '72001 ttys021 agy\n'
        }
        if (bin === 'ps' && args[0] === '-o' && args[1] === 'tty=' && args[3] === '72002') {
          return 'ttys021\n'
        }
        if (bin === 'ps' && args[0] === '-o' && args[1] === 'ppid=,command=') {
          return '72001 agy\n'
        }
        if (bin === '/usr/sbin/lsof') {
          if (args.includes('-p')) {
            // PID-scoped lsof -b only returned cwd (missed helper lock held by child PID 72002)
            return `p72001\nfcwd\nn${repo}\n`
          }
          if (args.includes(lockB)) {
            return 'p72002\nf15\n'
          }
        }
        return ''
      },
    })

    // Exact lockfile probe resolves B cleanly on %11
    const inspectB = await bridge.inspectSessionTerminal({ nativeId: convB, engineId: 'agy', cwd: repo })
    expect(inspectB?.targetReachable).toBe(true)
    expect(inspectB?.liveStatusText).toContain('Building B...')

    // Unrelated session A in the same repo STILL does NOT bind to %11
    const inspectA = await bridge.inspectSessionTerminal({ nativeId: convA, engineId: 'agy', cwd: repo })
    expect(inspectA?.targetReachable).toBe(false)
    expect(inspectA?.liveStatusText).toBeUndefined()
  })

  it('HostTaskInspector keeps idle AGY session A isolated when background AGY session B runs in the same cwd', async () => {
    const home = mkTmpDir('lework-agy-inspector-')
    const repo = path.join(home, 'lework')
    const convA = 'aaaa0000-1111-4222-8333-444455556666'
    const convB = 'bbbb0000-1111-4222-8333-444455556666'
    const logsA = path.join(home, '.gemini', 'antigravity-cli', 'brain', convA, '.system_generated', 'logs')
    const logsB = path.join(home, '.gemini', 'antigravity-cli', 'brain', convB, '.system_generated', 'logs')
    fs.mkdirSync(repo, { recursive: true })
    fs.mkdirSync(logsA, { recursive: true })
    fs.mkdirSync(logsB, { recursive: true })

    fs.writeFileSync(
      path.join(logsA, 'transcript.jsonl'),
      [
        JSON.stringify({
          step_index: 0,
          type: 'USER_INPUT',
          created_at: new Date(Date.now() - 120_000).toISOString(),
          content: '<USER_REQUEST>Session A task</USER_REQUEST>',
        }),
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          status: 'DONE',
          created_at: new Date(Date.now() - 110_000).toISOString(),
          content: 'Session A finished cleanly.',
        }),
      ].join('\n') + '\n',
      'utf8',
    )
    fs.writeFileSync(
      path.join(logsB, 'transcript.jsonl'),
      [
        JSON.stringify({
          step_index: 0,
          type: 'USER_INPUT',
          created_at: new Date().toISOString(),
          content: '<USER_REQUEST>Session B active task</USER_REQUEST>',
        }),
      ].join('\n') + '\n',
      'utf8',
    )
    fs.writeFileSync(
      path.join(home, '.gemini', 'antigravity-cli', 'history.jsonl'),
      [
        JSON.stringify({ conversationId: convA, workspace: repo, display: 'Session A task', timestamp: Date.now() - 120_000 }),
        JSON.stringify({ conversationId: convB, workspace: repo, display: 'Session B active task', timestamp: Date.now() }),
      ].join('\n') + '\n',
      'utf8',
    )

    const bridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/opt/homebrew/bin/tmux',
      it2Bin: null,
      tmuxSocketPaths: ['/private/tmp/tmux-501/default'],
      isPidAlive: pid => pid === 73001,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux') && args.includes('list-panes')) return '%5|/dev/ttys030|0\n'
        if (bin.endsWith('tmux') && args.includes('capture-pane')) {
          return [
            '───',
            ' Bash command',
            ' │ git push origin main',
            ' Do you want to proceed?',
            ' ❯ 1. Yes',
            '   2. No',
          ].join('\n')
        }
        if (bin === 'ps' && args[0] === '-axo') return '73001 ttys030 agy\n'
        if (bin === 'lsof' || bin.endsWith('/lsof')) {
          // Only cwd visible on PID 73001 — previously caused A to hijack B's terminal!
          return `p73001\nfcwd\nn${repo}\n`
        }
        return ''
      },
    })

    const pushedEvents: AgentEvent[] = []
    const pushedUpdates: SessionMeta[] = []
    const inspector = new HostTaskInspector({
      homeDir: home,
      terminalBridge: bridge,
      callbacks: {
        onEvents: evs => pushedEvents.push(...evs),
        onSessionUpdated: meta => pushedUpdates.push(meta),
      },
    })

    const sidA = `host:agy:${convA}`
    try {
      inspector.discoverHostSessions(true)
      inspector.retainWatch(sidA)
      await inspector.syncTerminalNow(sidA)

      const metaA = inspector.getHostSession(sidA)?.meta
      expect(metaA?.state).toBe('idle')
      expect(metaA?.liveStatusText).toBeUndefined()
      expect(pushedEvents.filter(e => e.sessionId === sidA && e.type === 'permission.requested')).toHaveLength(0)
      expect(pushedUpdates.some(u => u.id === sidA && (u.state === 'waiting_permission' || u.state === 'running'))).toBe(
        false,
      )
    } finally {
      inspector.stopAllWatches()
    }
  })

  it('AgyEngineAdapter drops foreign conversation_id and subagent step_update events so B steps never mix into A', async () => {
    const dir = mkTmpDir('lework-agy-stream-')
    const fakeBin = path.join(dir, 'fake-agy.mjs')
    fs.writeFileSync(
      fakeBin,
      `#!/usr/bin/env node
process.stdin.setEncoding('utf8')
process.stdin.on('data', () => {
  console.log(JSON.stringify({ event: 'init', conversation_id: 'conv-A-primary' }))
  // Foreign conversation B emits update_steps + text delta + result -> MUST BE DROPPED!
  console.log(JSON.stringify({
    event: 'step_update',
    step_update: {
      conversation_id: 'conv-B-foreign',
      step_index: 1,
      step_type: 'tool',
      tool_name: 'update_steps',
      state: 'ACTIVE',
      tool_info: { parameters: { steps: [{ title: 'Foreign B Step 1', status: 'in_progress' }] } },
    },
  }))
  console.log(JSON.stringify({
    event: 'step_update',
    step_update: {
      conversation_id: 'conv-B-foreign',
      step_index: 2,
      step_type: 'agent_response',
      text_delta: 'LEAKED FROM B',
    },
  }))
  console.log(JSON.stringify({
    event: 'step_update',
    step_update: {
      parent_conversation_id: 'conv-A-primary',
      step_index: 3,
      step_type: 'plan',
      steps: [{ title: 'Subagent Step Should Not Leak', status: 'in_progress' }],
    },
  }))
  console.log(JSON.stringify({
    event: 'result',
    result: { conversation_id: 'conv-B-foreign', response: 'FOREIGN RESULT' },
  }))
  // Legitimate conversation A events -> MUST BE KEPT!
  console.log(JSON.stringify({
    event: 'step_update',
    step_update: {
      conversation_id: 'conv-A-primary',
      step_index: 4,
      step_type: 'tool',
      tool_name: 'update_steps',
      state: 'ACTIVE',
      tool_info: { parameters: { steps: [{ title: 'Primary A Step 1', status: 'in_progress' }] } },
    },
  }))
  console.log(JSON.stringify({
    event: 'step_update',
    step_update: {
      conversation_id: 'conv-A-primary',
      step_index: 5,
      step_type: 'agent_response',
      text_delta: 'Clean A reply',
    },
  }))
  console.log(JSON.stringify({
    event: 'result',
    result: { conversation_id: 'conv-A-primary', response: 'Clean A reply' },
  }))
})
`,
      { mode: 0o755 },
    )

    const emitted: AgentEventBody[] = []
    const adapter = new AgyEngineAdapter({ binary: fakeBin, args: [] })
    const sess = await adapter.openSession({
      sessionId: 'sess_agy_a',
      cwd: dir,
      emit: ev => emitted.push(ev),
    })
    await sess.prompt('turn_a', 'run A')
    await sess.close()

    const plans = emitted.filter(
      (e): e is Extract<AgentEventBody, { type: 'plan.updated' }> => e.type === 'plan.updated',
    )
    expect(plans).toHaveLength(1)
    expect(plans[0]!.entries).toEqual([{ content: 'Primary A Step 1', status: 'in_progress' }])

    const texts = emitted
      .filter((e): e is Extract<AgentEventBody, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
      .map(e => e.text)
      .join('')
    expect(texts).toBe('Clean A reply')
  })
})
