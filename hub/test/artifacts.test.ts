import fs from 'node:fs'
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { ErrorCode } from '@lework/protocol'
import { HubRpcError } from '../src/security/path-guard.ts'
import { SessionManager } from '../src/session-manager.ts'
import { listSessionArtifacts, readSessionArtifact } from '../src/storage/artifacts.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'

const nodeRequire = createRequire(import.meta.url)

// 1x1 transparent PNG
const TINY_PNG_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='

describe('Unified per-engine Artifacts & Proposed Plan closure', () => {
  const tempDirs: string[] = []
  const origLeworkHome = process.env.LEWORK_HOME

  function makeFakeHome(): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-artifacts-test-'))
    tempDirs.push(dir)
    process.env.LEWORK_HOME = path.join(dir, '.lework')
    return dir
  }

  afterEach(() => {
    if (origLeworkHome === undefined) {
      delete process.env.LEWORK_HOME
    } else {
      process.env.LEWORK_HOME = origLeworkHome
    }
    for (const d of tempDirs.splice(0)) {
      fs.rmSync(d, { recursive: true, force: true })
    }
  })

  it('discovers AGY brain artifacts (plan/task/image), blocks traversal & sensitive files, and coexists with SessionMeta.subagents', () => {
    const home = makeFakeHome()
    const wsDir = path.join(home, 'work', 'demo-repo')
    fs.mkdirSync(wsDir, { recursive: true })

    const agyRoot = path.join(home, '.gemini', 'antigravity-cli')
    const parentId = 'parent-agy-1111'
    const childId = 'child-agy-2222'

    const parentBrain = path.join(agyRoot, 'brain', parentId)
    const parentLogs = path.join(parentBrain, '.system_generated', 'logs')
    const childLogs = path.join(agyRoot, 'brain', childId, '.system_generated', 'logs')
    fs.mkdirSync(parentLogs, { recursive: true })
    fs.mkdirSync(childLogs, { recursive: true })

    const nowIso = new Date().toISOString()
    fs.writeFileSync(
      path.join(parentLogs, 'transcript.jsonl'),
      [
        JSON.stringify({
          step_index: 0,
          type: 'USER_INPUT',
          created_at: nowIso,
          content: '<USER_REQUEST>\n设计并实现统一产物面板\n</USER_REQUEST>',
        }),
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          created_at: nowIso,
          content: '已产出方案。',
        }),
      ].join('\n') + '\n',
    )
    fs.writeFileSync(
      path.join(childLogs, 'transcript.jsonl'),
      [
        JSON.stringify({
          step_index: 0,
          type: 'USER_INPUT',
          created_at: nowIso,
          content: '<USER_REQUEST>\n调研代码仓库协议定义\n</USER_REQUEST>',
        }),
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          created_at: nowIso,
          content: '协议定义位于 protocol-schema/src/index.ts。',
        }),
      ].join('\n') + '\n',
    )

    fs.writeFileSync(
      path.join(parentBrain, 'implementation_plan.md'),
      '# 统一产物架构方案\n\n采用协议层统一抽象。\n',
    )
    fs.writeFileSync(
      path.join(parentBrain, 'implementation_plan.md.metadata.json'),
      JSON.stringify({
        artifactType: 'ARTIFACT_TYPE_IMPLEMENTATION_PLAN',
        summary: '为多引擎提供统一产物与方案审批闭环',
        updatedAt: '2026-11-23T12:00:00Z',
      }),
    )
    fs.writeFileSync(path.join(parentBrain, 'task.md'), '# 任务清单\n\n- [x] 定义协议\n- [ ] 实现 UI\n')
    fs.writeFileSync(
      path.join(parentBrain, 'task.md.metadata.json'),
      JSON.stringify({ artifactType: 'ARTIFACT_TYPE_TASK', summary: '开发进度追踪' }),
    )
    fs.writeFileSync(path.join(parentBrain, 'preview.png'), Buffer.from(TINY_PNG_B64, 'base64'))
    // Sensitive & escaping files MUST be ignored and blocked
    fs.writeFileSync(path.join(parentBrain, '.env'), 'SECRET=1')
    fs.writeFileSync(path.join(parentBrain, 'credentials.json'), '{}')
    const outsideSecret = path.join(home, 'outside.md')
    fs.writeFileSync(outsideSecret, '# Outside Secret')
    fs.symlinkSync(outsideSecret, path.join(parentBrain, 'escape.md'))

    const sqliteMod = nodeRequire('node:sqlite') as {
      DatabaseSync: new (location: string) => {
        exec: (sql: string) => void
        prepare: (sql: string) => { run: (...args: unknown[]) => void }
        close: () => void
      }
    }
    const db = new sqliteMod.DatabaseSync(path.join(agyRoot, 'conversation_summaries.db'))
    db.exec(`
      CREATE TABLE conversation_summaries (
        conversation_id TEXT PRIMARY KEY,
        title TEXT,
        workspace_uris TEXT,
        parent_conversation_id TEXT,
        nesting_depth INTEGER
      );
    `)
    const ins = db.prepare(
      'INSERT INTO conversation_summaries (conversation_id, title, workspace_uris, parent_conversation_id, nesting_depth) VALUES (?, ?, ?, ?, ?)',
    )
    const wsJson = JSON.stringify([wsDir])
    ins.run(parentId, '主任务：统一产物面板', wsJson, '', 0)
    ins.run(childId, '协议结构调研', wsJson, parentId, 1)
    db.close()

    const inspector = new HostTaskInspector({ homeDir: home })
    const sm = new SessionManager(
      [wsDir],
      [],
      { onEvent: () => {}, onSessionUpdated: () => {} },
      undefined,
      inspector,
    )

    // Top-level session list keeps sidebar clean and attaches both `subagents` and `artifactCount`
    const topSessions = sm.listSessions('agy')
    expect(topSessions).toHaveLength(1)
    expect(topSessions[0]?.id).toBe(`host:agy:${parentId}`)
    expect(topSessions[0]?.subagents?.length).toBe(1)
    expect(topSessions[0]?.artifactCount).toBe(3)

    // Artifacts sorted: plan -> task_list -> image, excluding blocked files
    const artifacts = sm.listArtifacts(`host:agy:${parentId}`)
    expect(artifacts.map(a => a.id)).toEqual([
      'agy_file:implementation_plan.md',
      'agy_file:task.md',
      'agy_file:preview.png',
    ])
    expect(artifacts[0]?.kind).toBe('plan')
    expect(artifacts[0]?.actionable).toBe(true)
    expect(artifacts[0]?.title).toBe('统一产物架构方案')

    const planContent = sm.readArtifact(`host:agy:${parentId}`, 'agy_file:implementation_plan.md')
    expect(planContent.isBase64).toBe(false)
    expect(planContent.content).toContain('采用协议层统一抽象')

    const pngContent = sm.readArtifact(`host:agy:${parentId}`, 'agy_file:preview.png')
    expect(pngContent.isBase64).toBe(true)
    expect(pngContent.content).toBe(TINY_PNG_B64)

    for (const badId of ['agy_file:../outside.md', 'agy_file:escape.md', 'agy_file:.env', 'agy_file:credentials.json']) {
      let caught: unknown
      try {
        sm.readArtifact(`host:agy:${parentId}`, badId)
      } catch (err) {
        caught = err
      }
      expect(caught).toBeInstanceOf(HubRpcError)
      expect((caught as HubRpcError).code).toBe(ErrorCode.PathNotAllowed)
    }
  })

  it('discovers referenced `~/.claude/plans/*.md`, ExitPlanMode & create_artifact virtual artifacts and blocks unreferenced plans', () => {
    const home = makeFakeHome()
    const wsDir = path.join(home, 'work', 'lework')
    const projDir = path.join(home, '.claude', 'projects', '-work-lework')
    const plansDir = path.join(home, '.claude', 'plans')
    const parentId = 'aaaa1111-2222-3333-4444-555566667777'
    fs.mkdirSync(wsDir, { recursive: true })
    fs.mkdirSync(projDir, { recursive: true })
    fs.mkdirSync(plansDir, { recursive: true })

    const planFile = path.join(plansDir, 'refactor-router.md')
    const unreferencedPlan = path.join(plansDir, 'unrelated-secret.md')
    fs.writeFileSync(planFile, '# 路由模块重构方案\n\n拆分状态机与网络层。\n')
    fs.writeFileSync(unreferencedPlan, '# Unrelated\n')

    fs.writeFileSync(
      path.join(projDir, `${parentId}.jsonl`),
      [
        JSON.stringify({
          type: 'user',
          cwd: wsDir,
          message: { role: 'user', content: '规划路由模块重构并产出预览页面' },
        }),
        JSON.stringify({
          type: 'assistant',
          cwd: wsDir,
          message: {
            id: 'msg_1',
            role: 'assistant',
            stop_reason: 'tool_use',
            content: [
              {
                type: 'tool_use',
                id: 'tc_write_plan',
                name: 'Write',
                input: { file_path: planFile, content: '# 路由模块重构方案' },
              },
              {
                type: 'tool_use',
                id: 'tc_exit_plan',
                name: 'ExitPlanMode',
                input: { plan: '# 最终执行步骤\n\n1. 更新协议\n2. 运行测试' },
              },
              {
                type: 'tool_use',
                id: 'tc_artifact',
                name: 'create_artifact',
                input: {
                  title: '状态机示意图',
                  type: 'text/html',
                  content: '<html><body><h1>Diagram</h1></body></html>',
                },
              },
            ],
          },
        }),
      ].join('\n') + '\n',
    )

    const inspector = new HostTaskInspector({ homeDir: home })
    const sm = new SessionManager(
      [wsDir],
      [],
      { onEvent: () => {}, onSessionUpdated: () => {} },
      undefined,
      inspector,
    )

    const artifacts = sm.listArtifacts(`host:claude:${parentId}`)
    expect(artifacts.some(a => a.id === 'claude_plan:refactor-router.md' && a.actionable === true)).toBe(true)
    expect(artifacts.some(a => a.id === 'virtual:claude_exit_plan:tc_exit_plan' && a.kind === 'plan')).toBe(true)
    expect(artifacts.some(a => a.id === 'virtual:claude_artifact:tc_artifact' && a.kind === 'html')).toBe(true)

    const refPlan = sm.readArtifact(`host:claude:${parentId}`, 'claude_plan:refactor-router.md')
    expect(refPlan.content).toContain('拆分状态机与网络层')

    const htmlArt = sm.readArtifact(`host:claude:${parentId}`, 'virtual:claude_artifact:tc_artifact')
    expect(htmlArt.content).toContain('<h1>Diagram</h1>')

    expect(() => sm.readArtifact(`host:claude:${parentId}`, 'claude_plan:unrelated-secret.md')).toThrow(
      HubRpcError,
    )
  })

  it('synthesizes a unified task_list artifact from Codex/session `plan.updated` events', () => {
    const home = makeFakeHome()
    const ctx = {
      sessionId: 'sess_codex_1',
      engineId: 'codex',
      homeDir: home,
      events: [
        {
          seq: 1,
          sessionId: 'sess_codex_1',
          ts: 1700000001000,
          type: 'plan.updated' as const,
          entries: [
            { content: '分析当前错误日志', status: 'completed' as const },
            { content: '修复空指针异常', status: 'in_progress' as const },
            { content: '补充单元测试', status: 'pending' as const },
          ],
        },
      ],
    }

    const list = listSessionArtifacts(ctx)
    expect(list).toHaveLength(1)
    expect(list[0]?.id).toBe('virtual:session_plan')
    expect(list[0]?.source).toBe('codex_plan')
    expect(list[0]?.summary).toBe('已完成 1/3 项')

    const res = readSessionArtifact(ctx, 'virtual:session_plan')
    expect(res.content).toContain('- [x] 分析当前错误日志')
    expect(res.content).toContain('- [ ] 修复空指针异常 *(进行中)*')
  })
})
