import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { AgentEventBody, SlashCommandInfo } from '@lework/protocol'
import { AgyEngineAdapter, parseElicitationQuestions } from '../src/engines/agy-adapter.ts'
import { discoverEngineSlashCommands, parseAcpAvailableCommands } from '../src/engines/slash-commands.ts'

const tmpDirs: string[] = []
function mkTmpDir(prefix = 'lework-agy-acp-'): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
  tmpDirs.push(dir)
  return dir
}

let prevHome: string | undefined
let prevLeworkHome: string | undefined
let prevClaudeConfigDir: string | undefined

beforeEach(() => {
  prevHome = process.env.HOME
  prevLeworkHome = process.env.LEWORK_HOME
  prevClaudeConfigDir = process.env.CLAUDE_CONFIG_DIR
  const isolatedHome = mkTmpDir('lework-isolated-home-')
  process.env.HOME = isolatedHome
  process.env.LEWORK_HOME = path.join(isolatedHome, '.lework')
  process.env.CLAUDE_CONFIG_DIR = path.join(isolatedHome, '.claude')
})

afterEach(() => {
  if (prevHome === undefined) delete process.env.HOME
  else process.env.HOME = prevHome
  if (prevLeworkHome === undefined) delete process.env.LEWORK_HOME
  else process.env.LEWORK_HOME = prevLeworkHome
  if (prevClaudeConfigDir === undefined) delete process.env.CLAUDE_CONFIG_DIR
  else process.env.CLAUDE_CONFIG_DIR = prevClaudeConfigDir
  while (tmpDirs.length > 0) {
    const dir = tmpDirs.pop()!
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

function createMockAgyAcpBinary(dir: string): string {
  const binPath = path.join(dir, 'mock-agy-acp.mjs')
  fs.writeFileSync(
    binPath,
    `#!/usr/bin/env node
import readline from 'node:readline'

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
const log = []
let activeSessionId = 'agy-acp-conv-1'
let pendingOutboundId = 100
const outboundWaiters = new Map()
let currentMode = 'default'
let currentModel = 'gemini-4-neon-high'
let currentEffort = 'high'

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\\n')
}

function callClient(method, params) {
  const id = pendingOutboundId++
  return new Promise((resolve, reject) => {
    outboundWaiters.set(id, { resolve, reject })
    send({ jsonrpc: '2.0', id, method, params })
  })
}

rl.on('line', async line => {
  const trimmed = line.trim()
  if (!trimmed) return
  let msg
  try { msg = JSON.parse(trimmed) } catch { return }

  if ((msg.id !== undefined) && ('result' in msg || 'error' in msg)) {
    const w = outboundWaiters.get(msg.id)
    if (w) {
      outboundWaiters.delete(msg.id)
      if (msg.error) w.reject(new Error(msg.error.message || 'client error'))
      else w.resolve(msg.result)
    }
    return
  }

  const { id, method, params = {} } = msg
  log.push({ method, params })

  if (method === 'initialize') {
    send({
      jsonrpc: '2.0',
      id,
      result: {
        protocolVersion: 1,
        agentCapabilities: { loadSession: true, promptCapabilities: { image: true } },
        agentInfo: { name: 'Mock Antigravity ACP Server', version: '1.5.18' },
      },
    })
    return
  }

  if (method === 'session/new' || method === 'session/resume') {
    activeSessionId = method === 'session/resume' && params.sessionId ? params.sessionId : 'agy-acp-conv-1'
    send({
      jsonrpc: '2.0',
      id,
      result: {
        sessionId: activeSessionId,
        models: {
          currentModelId: currentModel,
          availableModels: [
            { modelId: 'gemini-4-neon-high', name: 'Gemini 4 Neon (High)' },
            { modelId: 'claude-opus-5-5-medium', name: 'Claude Opus 5.5 (Medium)' },
          ],
        },
        modes: {
          currentModeId: currentMode,
          availableModes: [
            { id: 'default', name: 'Default' },
            { id: 'accept-edits', name: 'Accept edits' },
            { id: 'auto', name: 'Auto' },
            { id: 'plan', name: 'Plan' },
          ],
        },
        configOptions: [
          {
            id: 'thought_level',
            currentValue: currentEffort,
            options: [
              { value: 'low', name: 'Low' },
              { value: 'medium', name: 'Medium' },
              { value: 'high', name: 'High' },
              { value: 'xhigh', name: 'Extra' },
            ],
          },
        ],
      },
    })
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: activeSessionId,
        update: {
          sessionUpdate: 'available_commands_update',
          availableCommands: [
            { name: 'plan', description: 'Plan carefully before executing', input: { hint: 'task' } },
            { name: 'compact', description: 'Compact conversation context' },
            { name: 'my-custom-skill', description: 'Run custom skill', input: { hint: 'args' } },
          ],
        },
      },
    })
    return
  }

  if (method === 'session/set_model') {
    currentModel = params.modelId
    send({ jsonrpc: '2.0', id, result: {} })
    return
  }

  if (method === 'session/set_mode') {
    currentMode = params.modeId
    send({ jsonrpc: '2.0', id, result: {} })
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: { sessionId: activeSessionId, update: { sessionUpdate: 'current_mode_update', currentModeId: currentMode } },
    })
    return
  }

  if (method === 'session/set_config_option') {
    if (params.configId === 'thought_level') currentEffort = params.value
    send({ jsonrpc: '2.0', id, result: { configOptions: [] } })
    return
  }

  if (method === 'session/prompt') {
    const blocks = Array.isArray(params.prompt) ? params.prompt : []
    const textBlock = blocks.find(b => b.type === 'text')
    const imgBlock = blocks.find(b => b.type === 'image')
    const promptText = textBlock?.text || ''

    if (promptText.includes('ask-multi')) {
      const elicitRes = await callClient('elicitation/create', {
        mode: 'form',
        message: 'The agent has 2 questions.',
        requestedSchema: {
          type: 'object',
          properties: {
            q0: {
              type: 'string',
              title: '选择发布方式',
              oneOf: [
                { const: 'canary', title: '灰度发布' },
                { const: 'full', title: '全量发布' },
              ],
            },
            q0_other: { type: 'string', title: 'Other' },
            q1: {
              type: 'string',
              title: '补充说明',
              enum: ['无需说明', '附带变更日志'],
            },
            q1_other: { type: 'string', title: 'Other' },
          },
        },
      })
      send({
        jsonrpc: '2.0',
        method: 'session/update',
        params: {
          sessionId: activeSessionId,
          update: {
            sessionUpdate: 'agent_message_chunk',
            content: { type: 'text', text: 'ELICIT=' + JSON.stringify(elicitRes) },
          },
        },
      })
      send({ jsonrpc: '2.0', id, result: { stopReason: 'end_turn' } })
      return
    }

    if (promptText.includes('need-perm')) {
      const permRes = await callClient('session/request_permission', {
        sessionId: activeSessionId,
        toolCall: {
          toolCallId: 'tc_perm_1',
          title: 'Run shell command',
          kind: 'execute',
          rawInput: { CommandLine: 'rm -rf build/' },
        },
        options: [
          { optionId: 'allow', name: 'Allow once', kind: 'allow_once' },
          { optionId: 'deny', name: 'Deny', kind: 'reject_once' },
        ],
      })
      send({
        jsonrpc: '2.0',
        method: 'session/update',
        params: {
          sessionId: activeSessionId,
          update: {
            sessionUpdate: 'agent_message_chunk',
            content: { type: 'text', text: 'PERM=' + JSON.stringify(permRes) },
          },
        },
      })
      send({ jsonrpc: '2.0', id, result: { stopReason: 'end_turn' } })
      return
    }

    // Standard rich turn: thought + update_steps + edit diff + usage + reply
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: activeSessionId,
        update: { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: '分析需求中' } },
      },
    })
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: activeSessionId,
        update: {
          sessionUpdate: 'tool_call',
          toolCallId: 'tc_steps',
          title: 'update_steps',
          kind: 'other',
          status: 'in_progress',
          rawInput: {
            steps: [
              { title: '反编译协议', status: 'done' },
              { title: '接入 ACP', status: 'in_progress' },
            ],
          },
        },
      },
    })
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: activeSessionId,
        update: { sessionUpdate: 'tool_call_update', toolCallId: 'tc_steps', status: 'completed', rawOutput: 'OK' },
      },
    })
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: activeSessionId,
        update: {
          sessionUpdate: 'tool_call',
          toolCallId: 'tc_edit',
          title: 'Edit src/app.ts',
          kind: 'edit',
          status: 'in_progress',
          locations: [{ path: 'src/app.ts' }],
        },
      },
    })
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: activeSessionId,
        update: {
          sessionUpdate: 'tool_call_update',
          toolCallId: 'tc_edit',
          status: 'completed',
          content: [{ type: 'diff', path: 'src/app.ts', oldText: 'a', newText: 'a\\nb' }],
        },
      },
    })
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: activeSessionId,
        update: { sessionUpdate: 'usage_update', used: 99999, size: 1000000 },
      },
    })
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: activeSessionId,
        update: { sessionUpdate: 'usage_update', inputTokens: 320, outputTokens: 88, used: 320, size: 1000000 },
      },
    })
    send({
      jsonrpc: '2.0',
      method: 'session/update',
      params: {
        sessionId: activeSessionId,
        update: {
          sessionUpdate: 'agent_message_chunk',
          content: {
            type: 'text',
            text: \`OK model=\${currentModel} effort=\${currentEffort} mode=\${currentMode} img=\${Boolean(imgBlock)}\`,
          },
        },
      },
    })
    send({ jsonrpc: '2.0', id, result: { stopReason: 'end_turn' } })
    return
  }

  if (method === 'session/close') {
    send({ jsonrpc: '2.0', id, result: {} })
    return
  }

  if (id !== undefined) {
    send({ jsonrpc: '2.0', id, result: {} })
  }
})
`,
    { mode: 0o755 },
  )
  return binPath
}

describe('AGY native ACP adapter, elicitation ask_question & slash commands', () => {
  it('streams commands, thoughts, update_steps -> plan.updated, diffs, native images, and zero-restart config switches over ACP', async () => {
    const dir = mkTmpDir()
    const bin = createMockAgyAcpBinary(dir)
    const emitted: AgentEventBody[] = []
    let latestCommands: SlashCommandInfo[] = []
    let linkedNativeId = ''

    const adapter = new AgyEngineAdapter({ binary: bin, transport: 'acp' })
    const sess = await adapter.openSession({
      sessionId: 'sess_agy_acp_1',
      cwd: dir,
      modelId: 'gemini-4-neon',
      reasoningEffort: 'high',
      mode: 'acceptEdits',
      emit: ev => emitted.push(ev),
      onNativeSessionLinked: id => {
        linkedNativeId = id
      },
      onSlashCommandsChanged: cmds => {
        latestCommands = cmds
      },
    })

    expect(linkedNativeId).toBe('agy-acp-conv-1')
    // Live ACP commands merged with localized Chinese AGY descriptions
    expect(latestCommands.some(c => c.name === 'plan' && c.description.includes('执行方案'))).toBe(true)
    expect(latestCommands.some(c => c.name === 'my-custom-skill')).toBe(true)

    // Live zero-restart switching
    await sess.setModel!('claude-opus-5-5')
    await sess.setEffort!('xhigh')
    await sess.setMode!('plan')

    await sess.prompt('turn_1', 'hello with image', [
      {
        mimeType: 'image/png',
        dataBase64: 'iVBORw0KGgo=',
        stagedPath: path.join(dir, 'img.png'),
      },
    ])

    const plans = emitted.filter(e => e.type === 'plan.updated')
    expect(plans).toHaveLength(1)
    expect(plans[0]).toMatchObject({
      type: 'plan.updated',
      entries: [
        { content: '反编译协议', status: 'completed' },
        { content: '接入 ACP', status: 'in_progress' },
      ],
    })
    // update_steps must NOT emit noisy tool.call/tool.update
    expect(emitted.some(e => e.type === 'tool.call' && e.toolCallId === 'tc_steps')).toBe(false)
    expect(emitted.some(e => e.type === 'tool.update' && e.toolCallId === 'tc_steps')).toBe(false)

    // Edit diff content formatted cleanly
    const diffUpdate = emitted.find(e => e.type === 'tool.update' && e.toolCallId === 'tc_edit')
    expect(diffUpdate).toMatchObject({
      status: 'completed',
      output: '已更新 src/app.ts (+2 / -1 行)',
    })

    // Assistant reply verifies live switches + native image block
    const reply = emitted
      .filter((e): e is Extract<AgentEventBody, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
      .map(e => e.text)
      .join('')
    expect(reply).toContain('model=claude-opus-5-5')
    expect(reply).toContain('effort=xhigh')
    expect(reply).toContain('mode=plan')
    expect(reply).toContain('img=true')

    const usage = emitted.find(e => e.type === 'usage')
    expect(usage).toMatchObject({ inputTokens: 320, outputTokens: 88 })

    await sess.close()
  })

  it('handles interactive tool permission approval and bypassPermissions auto-approval over ACP', async () => {
    const dir = mkTmpDir()
    const bin = createMockAgyAcpBinary(dir)
    const emitted: AgentEventBody[] = []

    const adapter = new AgyEngineAdapter({ binary: bin, transport: 'acp' })
    const sess = await adapter.openSession({
      sessionId: 'sess_agy_perm',
      cwd: dir,
      mode: 'default',
      emit: ev => {
        emitted.push(ev)
        if (ev.type === 'permission.requested') {
          void sess.respondPermission(ev.requestId, 'allow')
        }
      },
    })

    await sess.prompt('turn_perm', 'need-perm')
    const permEv = emitted.find(e => e.type === 'permission.requested')
    expect(permEv).toMatchObject({
      interactionKind: 'permission',
      allowCustomInput: false,
    })
    expect(permEv?.type === 'permission.requested' && permEv.title).toContain('rm -rf build/')

    const reply1 = emitted
      .filter((e): e is Extract<AgentEventBody, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
      .map(e => e.text)
      .join('')
    expect(reply1).toContain('"optionId":"allow"')

    // Switching to bypassPermissions auto-approves without emitting permission.requested
    emitted.length = 0
    await sess.setMode!('bypassPermissions')
    await sess.prompt('turn_bypass', 'need-perm')
    expect(emitted.some(e => e.type === 'permission.requested')).toBe(false)
    const reply2 = emitted
      .filter((e): e is Extract<AgentEventBody, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
      .map(e => e.text)
      .join('')
    expect(reply2).toContain('"optionId":"allow"')

    await sess.close()
  })

  it('walks multi-question ask_question elicitation/create with option select + custom free-text input', async () => {
    const dir = mkTmpDir()
    const bin = createMockAgyAcpBinary(dir)
    const emitted: AgentEventBody[] = []
    let questionStep = 0

    const adapter = new AgyEngineAdapter({ binary: bin, transport: 'acp' })
    const sess = await adapter.openSession({
      sessionId: 'sess_agy_elicit',
      cwd: dir,
      emit: ev => {
        emitted.push(ev)
        if (ev.type === 'permission.requested' && ev.interactionKind === 'question') {
          questionStep++
          if (questionStep === 1) {
            // Pick first option ("灰度发布" -> wireValue "canary")
            void sess.respondPermission(ev.requestId, ev.options[0]!.id)
          } else {
            // Provide custom free-text answer on second question
            void sess.respondPermission(ev.requestId, '__custom__', '附带详细发布说明')
          }
        }
      },
    })

    await sess.prompt('turn_elicit', 'ask-multi')
    const qEvents = emitted.filter(
      (e): e is Extract<AgentEventBody, { type: 'permission.requested' }> => e.type === 'permission.requested',
    )
    expect(qEvents).toHaveLength(2)
    expect(qEvents[0]!.title).toContain('(1/2)')
    expect(qEvents[0]!.allowCustomInput).toBe(true)
    expect(qEvents[1]!.title).toContain('(2/2)')

    const reply = emitted
      .filter((e): e is Extract<AgentEventBody, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
      .map(e => e.text)
      .join('')
    expect(reply).toContain('"action":"accept"')
    expect(reply).toContain('"q0":"canary"')
    expect(reply).toContain('"q1_other":"附带详细发布说明"')

    await sess.close()
  })

  it('discovers installed AGY skills from isolated HOME and parses ACP command updates', () => {
    const fakeHome = mkTmpDir('lework-skills-home-')
    const skillDir = path.join(fakeHome, '.gemini', 'skills', 'demo-audit')
    fs.mkdirSync(skillDir, { recursive: true })
    fs.writeFileSync(
      path.join(skillDir, 'SKILL.md'),
      `---\nname: demo-audit\ndescription: "自动审计代码规范与性能热点"\n---\n# Demo\n`,
    )

    const discovered = discoverEngineSlashCommands('agy', { homeDir: fakeHome })
    expect(discovered.some(c => c.name === 'plan' && c.source === 'builtin')).toBe(true)
    expect(discovered.some(c => c.name === 'swarm' && c.source === 'workflow')).toBe(true)
    const demoSkill = discovered.find(c => c.name === 'demo-audit')
    expect(demoSkill).toMatchObject({
      name: 'demo-audit',
      description: '自动审计代码规范与性能热点',
      source: 'skill',
    })

    const acpParsed = parseAcpAvailableCommands(
      [{ name: 'compact', description: 'Compact context', input: { hint: 'focus' } }],
      'agy',
    )
    expect(acpParsed[0]?.description).toContain('压缩当前对话上下文')

    const parsedQuestions = parseElicitationQuestions({
      requestedSchema: {
        properties: {
          q0: { type: 'array', title: '选择目标平台', items: { enum: ['macOS', 'iOS'] } },
        },
      },
    })
    expect(parsedQuestions).toHaveLength(1)
    expect(parsedQuestions[0]!.isArray).toBe(true)
    expect(parsedQuestions[0]!.options.map(o => o.wireValue)).toEqual(['macOS', 'iOS'])
  })

  it('rejects invalid permission option without deleting pending interaction and blocks symlink escape in command discovery', async () => {
    const dir = mkTmpDir('lework-agy-hardening-')
    const bin = createMockAgyAcpBinary(dir)
    const emitted: AgentEventBody[] = []
    let retryAttempted = false

    const adapter = new AgyEngineAdapter({ binary: bin, transport: 'acp' })
    const sess = await adapter.openSession({
      sessionId: 'sess_agy_retry_perm',
      cwd: dir,
      emit: ev => {
        emitted.push(ev)
        if (ev.type === 'permission.requested' && !retryAttempted) {
          retryAttempted = true
          // First submit an invalid optionId -> must throw WITHOUT deleting pending interaction, then valid retry succeeds
          void sess
            .respondPermission(ev.requestId, 'nonexistent_option')
            .catch(() => sess.respondPermission(ev.requestId, 'allow'))
        }
      },
    })

    await sess.prompt('turn_retry_perm', 'need-perm')
    const reply = emitted
      .filter((e): e is Extract<AgentEventBody, { type: 'message.assistant.delta' }> => e.type === 'message.assistant.delta')
      .map(e => e.text)
      .join('')
    expect(reply).toContain('"outcome":"selected"')
    expect(reply).toContain('"optionId":"allow"')
    await sess.close()

    // Security: project-local symlink pointing outside cwd MUST NOT be read into slash command descriptions
    const fakeHome = mkTmpDir('lework-sec-home-')
    const outsideSecret = path.join(fakeHome, 'secret-outside.txt')
    fs.writeFileSync(outsideSecret, 'TOP_SECRET_TOKEN_XYZ', 'utf8')
    const repoDir = mkTmpDir('lework-sec-repo-')
    const cmdDir = path.join(repoDir, '.claude', 'commands')
    fs.mkdirSync(cmdDir, { recursive: true })
    fs.symlinkSync(outsideSecret, path.join(cmdDir, 'leak.md'))

    const claudeCmds = discoverEngineSlashCommands('claude', { homeDir: fakeHome, cwd: repoDir })
    expect(claudeCmds.some(c => c.name === 'leak' || c.description.includes('TOP_SECRET_TOKEN_XYZ'))).toBe(false)

    // Per-engine classification: Claude `/review` is builtin, whereas custom `/goal` on Claude stays `skill`
    const parsedClaude = parseAcpAvailableCommands(
      [
        { name: 'review', description: 'Review diff' },
        { name: 'goal', description: 'Custom user skill' },
      ],
      'claude',
    )
    expect(parsedClaude.find(c => c.name === 'review')?.source).toBe('builtin')
    expect(parsedClaude.find(c => c.name === 'goal')?.source).toBe('skill')

    // Resume failure MUST throw instead of silently creating a brand-new conversation
    const failResumeBin = path.join(dir, 'mock-agy-resume-fail.mjs')
    fs.writeFileSync(
      failResumeBin,
      `#!/usr/bin/env node
import readline from 'node:readline'
const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
const send = obj => process.stdout.write(JSON.stringify(obj) + '\\n')
rl.on('line', line => {
  const msg = JSON.parse(line)
  if (msg.method === 'initialize') {
    send({ jsonrpc: '2.0', id: msg.id, result: { protocolVersion: 1 } })
  } else if (msg.method === 'session/resume') {
    send({ jsonrpc: '2.0', id: msg.id, error: { code: -32000, message: 'conversation locked' } })
  } else if (msg.method === 'session/new') {
    send({ jsonrpc: '2.0', id: msg.id, result: { sessionId: 'should-never-be-created' } })
  }
})
`,
      { mode: 0o755 },
    )
    const resumeAdapter = new AgyEngineAdapter({ binary: failResumeBin, transport: 'acp' })
    await expect(
      resumeAdapter.openSession({
        sessionId: 'host:agy:locked-conv',
        cwd: dir,
        resumeNativeId: 'locked-conv',
        emit: () => {},
      }),
    ).rejects.toThrow(/恢复 AGY 历史会话失败/)
  })
})
