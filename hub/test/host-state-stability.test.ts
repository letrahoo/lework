import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import type { AgentEvent, SessionMeta } from '@lework/protocol'
import { HostTaskInspector } from '../src/storage/host-history.ts'
import { TerminalBridge } from '../src/storage/terminal-bridge.ts'

describe('Host watched session state & liveStatus stability', () => {
  const tempDirs: string[] = []

  function makeFakeHome(): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-stability-home-'))
    tempDirs.push(dir)
    return dir
  }

  afterEach(() => {
    for (const d of tempDirs.splice(0)) {
      fs.rmSync(d, { recursive: true, force: true })
    }
  })

  it('prevents running <-> idle oscillation between terminal poll, 350ms file poll, and 2.5s global discovery', async () => {
    const home = makeFakeHome()
    const wsDir = path.join(home, 'work', 'demo-app')
    fs.mkdirSync(wsDir, { recursive: true })
    const convId = 'cc33dd44-5566-4777-8888-9900aabbccdd'
    const logsDir = path.join(home, '.gemini', 'antigravity-cli', 'brain', convId, '.system_generated', 'logs')
    fs.mkdirSync(logsDir, { recursive: true })
    const tpFile = path.join(logsDir, 'transcript.jsonl')

    // Transcript file is >75s old (e.g. long-running shell command with no new transcript write yet),
    // so disk-only inspection says `idle`, while the live terminal pane still shows an active spinner.
    const oldMs = Date.now() - 120_000
    const oldIso = new Date(oldMs).toISOString().replace(/\.\d{3}Z$/, 'Z')
    fs.writeFileSync(
      tpFile,
      [
        JSON.stringify({
          step_index: 0,
          type: 'USER_INPUT',
          created_at: oldIso,
          content: '<USER_REQUEST>\n运行长耗时构建\n</USER_REQUEST>',
        }),
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          status: 'RUNNING',
          created_at: oldIso,
          tool_calls: [{ id: 'tc_build', name: 'run_command', args: { CommandLine: 'pnpm build' } }],
        }),
      ].join('\n') + '\n',
    )
    fs.utimesSync(tpFile, new Date(oldMs), new Date(oldMs))

    let screenLines =
      '⡿ Building workspace modules and verifying cross-platform targets...\n> \nesc to cancel   auto · Gemini 4\n'

    const mockBridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/opt/homebrew/bin/tmux',
      it2Bin: null,
      tmuxSocketPaths: ['/private/tmp/tmux-501/default'],
      isPidAlive: pid => pid === 41001,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux') && args.includes('list-panes')) return '%8|/dev/ttys021|0\n'
        if (bin === 'ps' && args[0] === '-axo') return '41001 ttys021 agy -c\n'
        if (bin === 'lsof' || bin.endsWith('/lsof')) {
          return `p41001\nfcwd\nn${wsDir}\nf9\nn${home}/.gemini/antigravity-cli/presence/${convId}.lock\n`
        }
        if (bin.endsWith('tmux') && args.includes('capture-pane')) return screenLines
        return ''
      },
    })

    const updates: SessionMeta[] = []
    const events: AgentEvent[] = []
    const inspector = new HostTaskInspector({
      homeDir: home,
      terminalBridge: mockBridge,
      callbacks: {
        onEvents: evs => events.push(...evs),
        onSessionUpdated: m => updates.push({ ...m }),
      },
    })

    const sid = `host:agy:${convId}`
    inspector.discoverHostSessions(true)
    inspector.retainWatch(sid)

    try {
      // 1. Live terminal sync sees active spinner -> transitions session cleanly to `running`
      await inspector.syncTerminalNow(sid)
      expect(updates.at(-1)?.state).toBe('running')
      expect(updates.at(-1)?.liveStatusText).toContain('Building workspace modules')
      const updatesAfterTerminalRunning = updates.length

      // 2. Subsequent file poll (`readHostSessionEvents`) and global scan (`discoverHostSessions(true)`)
      //    must NOT revert `running` back to `idle` while the terminal active lease is valid!
      const fileSnap = inspector.readHostSessionEvents(sid)
      expect(fileSnap?.session.state).toBe('running')

      const discovered = inspector.discoverHostSessions(true)
      const discMeta = discovered.find(s => s.id === sid)
      expect(discMeta?.state).toBe('running')
      expect(discMeta?.liveStatusText).toContain('Building workspace modules')

      // Trigger internal 350ms file poll path directly to assert zero idle bounce broadcasts
      ;(inspector as unknown as { pollWatchedSession: (id: string) => void }).pollWatchedSession(sid)
      expect(updates.length).toBe(updatesAfterTerminalRunning)
      expect(updates.every(u => u.state === 'running')).toBe(true)

      // 3. Transient single missed spinner capture while still running (`esc to cancel` visible)
      //    holds the previous liveStatusText briefly instead of flickering to undefined.
      screenLines = 'compiling step 4/12...\n> \nesc to cancel   auto · Gemini 4\n'
      await inspector.syncTerminalNow(sid)
      expect(updates.at(-1)?.state).toBe('running')
      expect(updates.at(-1)?.liveStatusText).toContain('Building workspace modules')

      // 4. Once terminal stops showing spinner & cancel hint, state cleanly returns to idle and clears liveStatusText.
      screenLines = 'Build completed successfully.\n> \n? for shortcuts\n'
      await inspector.syncTerminalNow(sid)
      expect(updates.at(-1)?.state).toBe('idle')
      expect(updates.at(-1)?.liveStatusText).toBeUndefined()
    } finally {
      inspector.releaseWatch(sid)
      inspector.stopAllWatches()
    }
  })

  it('preserves waiting_permission and liveStatusText across discoverHostSessions(true) for watched sessions', async () => {
    const home = makeFakeHome()
    const wsDir = path.join(home, 'work', 'perm-app')
    fs.mkdirSync(wsDir, { recursive: true })
    const convId = 'ee55ff66-7788-4999-8aaa-bbccddeeff00'
    const logsDir = path.join(home, '.gemini', 'antigravity-cli', 'brain', convId, '.system_generated', 'logs')
    fs.mkdirSync(logsDir, { recursive: true })
    const tpFile = path.join(logsDir, 'transcript.jsonl')

    const nowIso = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
    fs.writeFileSync(
      tpFile,
      [
        JSON.stringify({
          step_index: 0,
          type: 'USER_INPUT',
          created_at: nowIso,
          content: '<USER_REQUEST>\n执行部署脚本\n</USER_REQUEST>',
        }),
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          status: 'RUNNING',
          created_at: nowIso,
          tool_calls: [{ id: 'tc_deploy', name: 'run_command', args: { CommandLine: './deploy.sh' } }],
        }),
      ].join('\n') + '\n',
    )

    const permScreen = [
      'Bash command',
      '│ ./deploy.sh',
      'Do you want to proceed?',
      '❯ 1. Yes',
      '  2. No',
    ].join('\n')

    const mockBridge = new TerminalBridge({
      homeDir: home,
      tmuxBin: '/opt/homebrew/bin/tmux',
      it2Bin: null,
      tmuxSocketPaths: ['/private/tmp/tmux-501/default'],
      isPidAlive: pid => pid === 41002,
      runCommand: async (bin, args) => {
        if (bin.endsWith('tmux') && args.includes('list-panes')) return '%9|/dev/ttys022|0\n'
        if (bin === 'ps' && args[0] === '-axo') return '41002 ttys022 agy -c\n'
        if (bin === 'lsof' || bin.endsWith('/lsof')) {
          return `p41002\nfcwd\nn${wsDir}\nf9\nn${home}/.gemini/antigravity-cli/presence/${convId}.lock\n`
        }
        if (bin.endsWith('tmux') && args.includes('capture-pane')) return permScreen
        return ''
      },
    })

    const updates: SessionMeta[] = []
    const inspector = new HostTaskInspector({
      homeDir: home,
      terminalBridge: mockBridge,
      callbacks: {
        onEvents: () => undefined,
        onSessionUpdated: m => updates.push({ ...m }),
      },
    })

    const sid = `host:agy:${convId}`
    inspector.discoverHostSessions(true)
    inspector.retainWatch(sid)

    try {
      await inspector.syncTerminalNow(sid)
      expect(updates.at(-1)?.state).toBe('waiting_permission')

      const discovered = inspector.discoverHostSessions(true)
      const discMeta = discovered.find(s => s.id === sid)
      expect(discMeta?.state).toBe('waiting_permission')
      expect(discMeta?.liveStatusText).toBe('等待执行审批…')
    } finally {
      inspector.releaseWatch(sid)
      inspector.stopAllWatches()
    }
  })
})
