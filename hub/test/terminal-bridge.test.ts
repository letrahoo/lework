import { describe, expect, it } from 'vitest'
import {
  TerminalBridge,
  parseLiveSpinnerLine,
  parseTerminalPermissionPrompt,
  parseTerminalSessionMode,
} from '../src/storage/terminal-bridge.ts'

describe('TerminalBridge TUI parsers & strict TTY isolation (M1 & M6)', () => {
  it('[T1.1 / T4.3] extracts Claude Code and AGY live spinner status lines accurately', () => {
    const claudeScreen = [
      '⏺ Let me analyze the architecture first.',
      '',
      '· Sketching… (2m 1s · ↓ 1.0k tokens)',
      '────────────────────────────────────────────────────────────────────────────────',
      '❯ ',
    ]
    expect(parseLiveSpinnerLine(claudeScreen)).toBe('· Sketching… (2m 1s · ↓ 1.0k tokens)')

    const agyScreen = [
      '● Running (pnpm check) (ctrl+o to expand)',
      '⣷  Running command...',
      '────────────────────────────────────────────────────────────────────────────────',
      '> ',
    ]
    expect(parseLiveSpinnerLine(agyScreen)).toBe('⣷ Running command...')
  })

  it('[T1.1] parses diverse Agent-internal approval prompts (Bash, Overwrite/Edit, Plan confirmation, AGY sandbox) while ignoring Markdown lists', () => {
    // 1. Standard Bash command approval
    const bashScreen = [
      '────────────────────────────────────────────────────────────────────────────────',
      ' Bash command',
      '   核实没有镜像或工作流变更并更新未推送的提交',
      '   │ git status --short && git log -1 --oneline',
      ' Do you want to proceed?',
      ' ❯ 1. Yes',
      "   2. Yes, and don't ask again for git commands in this project",
      '   3. No',
      '',
      ' Esc to cancel · Tab to amend',
    ]
    const p1 = parseTerminalPermissionPrompt('sess-1', bashScreen)
    expect(p1?.options).toHaveLength(3)
    expect(p1?.title).toContain('git status --short && git log -1 --oneline')

    // 2. Overwrite / Edit / Fetch tool approvals (previously missed by narrow verb regex!)
    const overwriteScreen = [
      '───',
      ' Write file',
      '   │ hub/src/server.ts',
      ' Do you want to overwrite hub/src/server.ts?',
      ' ❯ 1. Yes',
      '   2. Yes, allow all edits during this session',
      '   3. No',
    ]
    const p2 = parseTerminalPermissionPrompt('sess-2', overwriteScreen)
    expect(p2).toBeDefined()
    expect(p2?.options[1]?.kind).toBe('allow_always')
    expect(p2?.options[2]?.kind).toBe('reject_once')

    // 3. Claude Plan exit confirmation (`Would you like to proceed?`)
    const planScreen = [
      '───',
      ' Ready to code?',
      ' Would you like to proceed?',
      ' ❯ 1. Yes, and auto-accept edits',
      '   2. Yes, and manually approve edits',
      '   3. No, keep planning',
    ]
    const p3 = parseTerminalPermissionPrompt('sess-3', planScreen)
    expect(p3).toBeDefined()
    expect(p3?.options).toHaveLength(3)
    expect(p3?.options[2]?.kind).toBe('reject_once')

    // 4. AGY sandbox elevation / command confirmation menu
    const agySandboxScreen = [
      '╭──────────────────────────────────────────────────────────╮',
      '│ Command requires elevated user permission outside sandbox│',
      '│ pnpm check                                               │',
      '╰──────────────────────────────────────────────────────────╯',
      ' ❯ 1. Allow once',
      '   2. Always allow `pnpm check`',
      '   3. Deny',
    ]
    const p4 = parseTerminalPermissionPrompt('sess-4', agySandboxScreen)
    expect(p4).toBeDefined()
    expect(p4?.options).toEqual([
      { id: 'tty_key:1', name: '1. Allow once', kind: 'allow_once' },
      { id: 'tty_key:2', name: '2. Always allow `pnpm check`', kind: 'allow_always' },
      { id: 'tty_key:3', name: '3. Deny', kind: 'reject_once' },
    ])

    // 5. Normal Markdown numbered list in assistant answer MUST NOT false-trigger!
    const normalProseScreen = [
      '接下来可以直接这样试：',
      '  1. 验证跨窗口监控：在其中一个 Claude 里发个小任务',
      '  2. 以后开新任务：按 Ctrl+b 再按 c 新建窗口',
      '  3. 旧会话按需处理：保持原样即可',
      '❯ ',
    ]
    expect(parseTerminalPermissionPrompt('sess-5', normalProseScreen)).toBeUndefined()
  })

  it('[T1.2 / T1.3 / T1.5] enforces strict TTY isolation across Claude & AGY, detects unreachable DataGrip panels, and sends conditional Enter', async () => {
    const sentCommands: Array<{ bin: string; args: string[] }> = []
    let promptDismissedOnFirstKey = false

    const bridge = new TerminalBridge({
      tmuxBin: '/usr/local/bin/tmux',
      it2Bin: '/usr/local/bin/it2',
      tmuxSocketPaths: [],
      isPidAlive: pid => pid === 1001 || pid === 1002 || pid === 2001,
      runCommand: async (bin, args) => {
        sentCommands.push({ bin, args })
        if (bin === '/usr/local/bin/tmux' && args.includes('list-panes')) {
          return '%0|/dev/ttys010\n%2|/dev/ttys001\n'
        }
        if (bin === '/usr/local/bin/it2' && args[0] === 'session' && args[1] === 'list') {
          return JSON.stringify([{ id: 'it2-agy-1', tty: '/dev/ttys008' }])
        }
        if (bin === 'ps' && args.includes('1001')) {
          return 'ttys010\n' // In tmux %0
        }
        if (bin === 'ps' && args.includes('1002')) {
          return 'ttys003\n' // In DataGrip built-in terminal (NOT in tmux or iTerm2!)
        }
        if (bin === 'ps' && args[0] === '-axo') {
          return ' 2001 ttys008 agy -c\n 9999 ?? /Applications/Gemini.app/Contents/Helpers/agy --hub\n'
        }
        if (bin === 'lsof') {
          return 'p2001\nfcwd\nn/tmp/demo-agy-repo\n'
        }
        if (bin === '/usr/local/bin/tmux' && args.includes('capture-pane')) {
          if (promptDismissedOnFirstKey) return '✻ Running… (2s)\n'
          return [
            '───',
            ' Bash command',
            ' │ rm -rf dist',
            ' Do you want to proceed?',
            ' ❯ 1. Yes',
            '   2. No',
          ].join('\n')
        }
        if (bin === '/usr/local/bin/it2' && args[0] === 'session' && args[1] === 'read') {
          return [
            '───',
            ' Command requires user approval',
            ' │ launchctl kickstart -k gui/501/svc',
            ' ❯ 1. Allow once',
            '   2. Deny',
          ].join('\n')
        }
        if (bin === '/usr/local/bin/tmux' && args.includes('send-keys') && args.at(-1) === '1') {
          // Keep prompt visible on first test to verify trailing Enter, or dismiss when flag set
        }
        return ''
      },
    })

    // A) Reachable Claude session in tmux `%0`
    const okClaude = await bridge.inspectSessionTerminal({
      nativeId: 'claude-sess-1',
      engineId: 'claude',
      claudeRuntime: { pid: 1001, sessionId: 'claude-sess-1', cwd: '/tmp' },
    })
    expect(okClaude?.targetReachable).toBe(true)
    expect(okClaude?.pendingPermission?.options).toHaveLength(2)

    // B) Unreachable Claude session started inside DataGrip built-in panel (`ttys003`)
    const unreachableClaude = await bridge.inspectSessionTerminal({
      nativeId: 'claude-sess-dg',
      engineId: 'claude',
      claudeRuntime: { pid: 1002, sessionId: 'claude-sess-dg', cwd: '/tmp' },
    })
    expect(unreachableClaude?.targetReachable).toBe(false)
    expect(unreachableClaude?.pendingPermission).toBeUndefined()

    // C) Mismatched sessionId MUST NOT bind even if PID is alive
    const mismatched = await bridge.inspectSessionTerminal({
      nativeId: 'wrong-session-id',
      engineId: 'claude',
      claudeRuntime: { pid: 1001, sessionId: 'claude-sess-1', cwd: '/tmp' },
    })
    expect(mismatched?.targetReachable).toBe(false)

    // D) AGY session running in iTerm2 (`ttys008`) matched by unambiguous cwd
    const okAgy = await bridge.inspectSessionTerminal({
      nativeId: 'agy-conv-1',
      engineId: 'agy',
      cwd: '/tmp/demo-agy-repo',
    })
    expect(okAgy?.targetReachable).toBe(true)
    expect(okAgy?.pendingPermission?.options[0]?.name).toBe('1. Allow once')

    // E) Keystroke delivery: sends '1' AND trailing 'Enter' when prompt didn't dismiss immediately
    sentCommands.length = 0
    const sent = await bridge.sendPermissionKeystroke(
      {
        nativeId: 'claude-sess-1',
        engineId: 'claude',
        claudeRuntime: { pid: 1001, sessionId: 'claude-sess-1', cwd: '/tmp' },
      },
      'tty_key:1',
    )
    expect(sent).toBe(true)
    const tmuxSends = sentCommands.filter(c => c.bin.endsWith('tmux') && c.args.includes('send-keys'))
    expect(tmuxSends.map(c => c.args.at(-1))).toEqual(['1', 'Enter'])

    // When prompt dismisses immediately on digit keystroke, NO extra Enter is sent!
    sentCommands.length = 0
    promptDismissedOnFirstKey = true
    await bridge.sendPermissionKeystroke(
      {
        nativeId: 'claude-sess-1',
        engineId: 'claude',
        claudeRuntime: { pid: 1001, sessionId: 'claude-sess-1', cwd: '/tmp' },
      },
      'tty_key:1',
    )
    const tmuxSendsAfter = sentCommands.filter(c => c.bin.endsWith('tmux') && c.args.includes('send-keys'))
    expect(tmuxSendsAfter.map(c => c.args.at(-1))).toEqual(['1'])
  })

  it('[T1.6] bridges launchd TMPDIR + C-locale mismatch via tmux -u -S socket paths and identifies IDE hostAppHint', async () => {
    const sentCommands: Array<{ bin: string; args: string[]; env?: NodeJS.ProcessEnv }> = []
    const privateSock = '/private/tmp/tmux-501/default'

    const bridge = new TerminalBridge({
      tmuxBin: '/opt/homebrew/bin/tmux',
      it2Bin: null,
      tmuxSocketPaths: ['/var/folders/xx/T/tmux-501/dead', privateSock],
      isPidAlive: pid => pid === 21674 || pid === 28376,
      runCommand: async (bin, args, _timeout, env) => {
        sentCommands.push({ bin, args, env })
        // Simulate launchd environment where bare tmux (no -S) or /var/folders socket fails,
        // AND verify `-u` + UTF-8 locale are always supplied so `\t` / `❯` / Chinese never turn into `_`!
        if (bin.endsWith('tmux')) {
          expect(args[0]).toBe('-u')
          expect(env?.LANG).toBeTruthy()
          const sIdx = args.indexOf('-S')
          if (sIdx === -1) {
            throw new Error('no server running on /var/folders/xx/T/tmux-501/default')
          }
          if (args[sIdx + 1] !== privateSock) {
            throw new Error('Connection refused')
          }
          if (args.includes('list-panes')) {
            return '%0|/dev/ttys010\n'
          }
          if (args.includes('capture-pane')) {
            return [
              '───',
              ' Bash command',
              ' │ xcodebuild -scheme DemoApp build',
              ' Do you want to proceed?',
              ' ❯ 1. Yes',
              '   2. Yes, allow all commands during this session',
              '   3. No',
            ].join('\n')
          }
        }
        if (bin === 'ps' && args[0] === '-o' && args[1] === 'tty=' && args[3] === '21674') {
          return 'ttys010\n'
        }
        if (bin === 'ps' && args[0] === '-o' && args[1] === 'tty=' && args[3] === '28376') {
          return 'ttys004\n'
        }
        if (bin === 'ps' && args[0] === '-o' && args[1] === 'ppid=,command=') {
          if (args[3] === '28376') return '28300 /bin/zsh -l\n'
          if (args[3] === '28300') return '1 /Applications/GoLand.app/Contents/MacOS/goland\n'
        }
        return ''
      },
    })

    // 1. Session on /dev/ttys010 inside `/private/tmp/tmux-501/default` is reachable from launchd!
    const okTmux = await bridge.inspectSessionTerminal({
      nativeId: 'becc7366-c7d2-4eb0-8f2e-2034d876007a',
      engineId: 'claude',
      claudeRuntime: { pid: 21674, sessionId: 'becc7366-c7d2-4eb0-8f2e-2034d876007a', cwd: '/tmp' },
    })
    expect(okTmux?.targetReachable).toBe(true)
    expect(okTmux?.pendingPermission?.options).toHaveLength(3)

    // Keystrokes also pass `-u -S /private/tmp/tmux-501/default`!
    sentCommands.length = 0
    const delivered = await bridge.sendPermissionKeystroke(
      {
        nativeId: 'becc7366-c7d2-4eb0-8f2e-2034d876007a',
        engineId: 'claude',
        claudeRuntime: { pid: 21674, sessionId: 'becc7366-c7d2-4eb0-8f2e-2034d876007a', cwd: '/tmp' },
      },
      'tty_key:1',
    )
    expect(delivered).toBe(true)
    const sendCalls = sentCommands.filter(c => c.bin.endsWith('tmux') && c.args.includes('send-keys'))
    expect(sendCalls[0]?.args.slice(0, 4)).toEqual(['-u', '-S', privateSock, 'send-keys'])

    // 2. Session genuinely started inside GoLand built-in terminal reports `hostAppHint: 'GoLand'`
    const golandInspect = await bridge.inspectSessionTerminal({
      nativeId: 'goland-sess-1',
      engineId: 'claude',
      claudeRuntime: { pid: 28376, sessionId: 'goland-sess-1', cwd: '/tmp' },
    })
    expect(golandInspect?.targetReachable).toBe(false)
    expect(golandInspect?.hostAppHint).toBe('GoLand')
  })

  it('[T6.2] extracts live permission mode from terminal status line', () => {
    expect(parseTerminalSessionMode(['❯ ', '  ⏸ plan mode on (shift+tab to cycle)'])).toBe('plan')
    expect(parseTerminalSessionMode(['❯ ', '  ⏵⏵ accept edits on (shift+tab to cycle)'])).toBe('acceptEdits')
    expect(parseTerminalSessionMode(['❯ ', '  ⏵⏵ auto mode on (shift+tab to cycle)'])).toBe('auto')
    expect(parseTerminalSessionMode(['> ', 'esc to cancel   auto · Gemini 4 Carbon · high'])).toBe('auto')
    expect(parseTerminalSessionMode(['❯ ', '  ⏵⏵ bypass permissions on'])).toBe('bypassPermissions')
    expect(parseTerminalSessionMode(['❯ '])).toBeUndefined()
  })
})
