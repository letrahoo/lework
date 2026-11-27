import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PiEngineAdapter } from '../src/engines/pi-adapter.ts'
import { PiSkillCatalog } from '../src/engines/pi-skills.ts'
import type { EmitAgentEvent } from '../src/engines/types.ts'

let home: string
let root: string

beforeEach(() => {
  home = fs.mkdtempSync(path.join(os.tmpdir(), 'pi-skill-test-'))
  vi.stubEnv('HOME', home)
  vi.stubEnv('LEWORK_HOME', path.join(home, 'hub-data'))
  vi.stubEnv('LEWORK_SKILL_STORE_DIRS', '')
  root = path.join(home, '.agents', 'skills')
  fs.mkdirSync(root, { recursive: true })
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  fs.rmSync(home, { recursive: true, force: true })
})

function install(dir: string, content: string) {
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(path.join(dir, 'SKILL.md'), content)
}

it('reads shared installs and hidden skill-store symlinks, including skills added to an existing session', async () => {
  const request = vi
    .fn()
    .mockResolvedValue(Response.json({ choices: [{ message: { content: 'ordinary reply' } }] }))
  vi.stubGlobal('fetch', request)
  const emit = vi.fn<EmitAgentEvent>()
  const cwd = path.join(home, 'workspace')
  fs.mkdirSync(cwd)

  const session = await new PiEngineAdapter().openSession({
    sessionId: 'p',
    cwd,
    modelId: 'fake',
    emit,
  })
  await session.prompt('0', '/skills')
  expect(emit).toHaveBeenCalledWith(expect.objectContaining({ text: expect.stringContaining('尚未安装') }))

  const pkg = path.join(home, '.skill-store', 'skills', 'example--id')
  install(
    pkg,
    '---\nname: private-example\ndescription: >\n  private description\n---\nprivate body\n```shell\nexit 99\n```',
  )
  fs.symlinkSync(pkg, path.join(root, 'private-example'))

  const nestedPkg = path.join(home, '.pi', 'agent', 'skills', 'nested-example')
  install(nestedPkg, '---\ndescription: nested description\n---\nnested body')
  fs.symlinkSync(nestedPkg, path.join(root, 'nested-example'))

  await session.prompt('1', '/skills')
  await session.prompt('2', '/skill private-example')
  await session.prompt('3', '/skill unknown-task extra')
  await session.prompt('4', '/skill missing')

  expect(emit).toHaveBeenCalledWith(
    expect.objectContaining({ text: expect.stringContaining('private-example — private description') }),
  )
  expect(emit).toHaveBeenCalledWith(
    expect.objectContaining({ text: expect.stringContaining('nested-example — nested description') }),
  )
  expect(emit).toHaveBeenCalledWith(expect.objectContaining({ text: expect.stringContaining('private body') }))
  expect(emit).toHaveBeenCalledWith(expect.objectContaining({ text: expect.stringContaining('未找到可读取') }))
  expect(request).not.toHaveBeenCalled()
  expect(emit.mock.calls.some(([e]) => e.type === 'tool.call' || e.type === 'permission.requested')).toBe(false)

  await session.prompt('5', 'Hello')
  expect(request).toHaveBeenCalledTimes(1)
  const outgoing = JSON.parse(request.mock.calls[0]![1].body)
  expect(JSON.stringify(outgoing.messages)).not.toMatch(/private-example|private description|private body|\/skills?/)
  await session.close()
})

it('rejects traversal, outside-directory links, non-skill hidden dirs, and linked SKILL.md files', () => {
  const catalog = new PiSkillCatalog(home)
  install(path.join(home, 'outside'), 'do not disclose')
  install(path.join(home, '.ssh', 'fake-skill'), 'secret key')
  fs.symlinkSync(path.join(home, 'outside'), path.join(root, 'outside-link'))
  fs.symlinkSync(path.join(home, '.ssh', 'fake-skill'), path.join(root, 'ssh-link'))
  fs.mkdirSync(path.join(root, 'file-link'))
  fs.symlinkSync(path.join(home, 'outside', 'SKILL.md'), path.join(root, 'file-link', 'SKILL.md'))
  fs.symlinkSync(path.join(home, 'nonexistent'), path.join(root, 'broken'))

  expect(catalog.list()).toEqual([])
  for (const name of ['outside-link', 'ssh-link', 'file-link', '../outside', 'broken', '/etc/passwd']) {
    expect(catalog.handleCommand(`/skill ${name}`)).toContain('未找到可读取')
  }
})

it('allows custom store roots configured via LEWORK_SKILL_STORE_DIRS', () => {
  const customStore = path.join(home, 'custom-store')
  const pkg = path.join(customStore, 'custom-pkg')
  install(pkg, '---\ndescription: custom store skill\n---\ncustom guide')
  fs.symlinkSync(pkg, path.join(root, 'custom-skill'))

  const catalog = new PiSkillCatalog(home)
  expect(catalog.list()).toEqual([])

  vi.stubEnv('LEWORK_SKILL_STORE_DIRS', customStore)
  expect(catalog.list()).toEqual([{ name: 'custom-skill', description: 'custom store skill' }])
  expect(catalog.handleCommand('/skill custom-skill')).toContain('custom guide')
})

it('bounds large guides, lists descriptions instead of bodies and renders Markdown literally', () => {
  install(
    path.join(root, 'sample'),
    `---\ndescription: display name\n---\n\`\`\`\nbody-only\n\`\`\`\n${'x'.repeat(60_000)}`,
  )
  const catalog = new PiSkillCatalog(home)
  expect(catalog.handleCommand('/skills')).not.toContain('body-only')
  const body = catalog.handleCommand('/skill sample')!
  expect(body).toContain('\n````\n')
  expect(body).toContain('truncated')
  expect(body.length).toBeLessThan(51_000)
  expect(catalog.handleCommand('normal chat')).toBeUndefined()
})
