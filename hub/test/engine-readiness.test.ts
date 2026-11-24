import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ClaudeCodeAdapter } from '../src/engines/claude-adapter.ts'
import { CodexEngineAdapter } from '../src/engines/codex-adapter.ts'
import { DshEngineAdapter } from '../src/engines/dsh-adapter.ts'
import { PiEngineAdapter } from '../src/engines/pi-adapter.ts'
import { probeCliAuth } from '../src/engines/cli-auth-status.ts'
import { ModelRegistry } from '../src/registry/model-registry.ts'

let home: string
beforeEach(() => {
  home = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-readiness-'))
  for (const [key, value] of Object.entries({ HOME: home, LEWORK_HOME: path.join(home, 'hub'),
    CODEX_HOME: path.join(home, '.codex'), CLAUDE_CONFIG_DIR: path.join(home, '.claude'), LEWORK_CPA_CONFIG: path.join(home, 'proxy.yaml'),
    OPENAI_BASE_URL: '', LEWORK_CPA_KEY: '' })) vi.stubEnv(key, value)
  fs.mkdirSync(path.join(home, '.local/bin'), { recursive: true })
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  fs.rmSync(home, { recursive: true, force: true })
})

function executable(name: string, body: string) {
  const bin = path.join(home, '.local/bin', name)
  fs.writeFileSync(bin, '#!/bin/sh\n' + body, { mode: 0o700 })
  return bin
}

it('classifies only recognized noninteractive login results and never relays raw output', async () => {
  for (const [cli, args, body, expected] of [
    ['claude', 'auth status --json', `printf '%s' '{"loggedIn":true,"account":"private-fixture"}'`, 'authenticated'],
    ['claude', 'auth status --json', `printf '%s' '{"loggedIn":false}'; exit 1`, 'required'],
    ['claude', 'auth status --json', `printf '%s' 'unsupported'; exit 1`, 'unknown'],
    ['codex', 'login status', `printf '%s' 'Logged in using an API key - private-fixture' >&2`, 'authenticated'],
    ['codex', 'login status', `printf '%s' 'Not logged in' >&2; exit 1`, 'required'],
    ['codex', 'login status', `printf '%s' 'unrelated diagnostic'; exit 1`, 'unknown'],
  ] as const) {
    const bin = executable(cli, `[ "$*" = "${args}" ] || exit 2\n${body}`)
    expect(await probeCliAuth(bin, cli)).toBe(expected)
  }
})

it('checks the actual entrypoint; a custom provider config is evidence, not proof of authorization', async () => {
  const bin = executable('codex', `if [ "$*" = '--version' ]; then printf '%s' 'codex 1.2.3 private-fixture'; exit 0; fi\nprintf '%s' 'Not logged in' >&2; exit 1`)
  const adapter = new CodexEngineAdapter({ binary: bin })
  expect(await adapter.probe()).toMatchObject({ installed: true, ready: false, authStatus: 'required', version: '1.2.3' })
  fs.mkdirSync(path.join(home, '.codex'))
  fs.writeFileSync(path.join(home, '.codex/config.toml'), 'model_provider = "internal"')
  expect(await adapter.probe()).toMatchObject({ installed: true, ready: false, authStatus: 'unknown' })
  fs.chmodSync(bin, 0o600)
  expect((await adapter.probe()).installed).toBe(false)
  expect((await new ClaudeCodeAdapter({ acpBinary: bin }).probe()).installed).toBe(false)
  expect((await new DshEngineAdapter().probe()).installed).toBe(false)
})

it('requires a current usable proxy catalog and clears old models on 401, 403 or failure', async () => {
  const registry = new ModelRegistry('test', 'http://proxy.invalid')
  const pi = new PiEngineAdapter('http://proxy.invalid', registry)
  const fetchStub = vi.fn().mockResolvedValueOnce(Response.json({ data: [{ id: 'sample' }] }))
    .mockResolvedValueOnce(new Response('', { status: 401 })).mockResolvedValueOnce(new Response('', { status: 403 }))
    .mockRejectedValueOnce(new Error('offline'))
  vi.stubGlobal('fetch', fetchStub)
  await registry.refreshCpaModels()
  expect(await pi.probe()).toMatchObject({ installed: true, ready: true })
  for (const _status of [401, 403]) {
    await registry.refreshCpaModels()
    expect(await pi.probe()).toMatchObject({ installed: true, ready: false, authStatus: 'required' })
    expect(registry.getCachedCpaModels()).toEqual([])
  }
  await registry.refreshCpaModels()
  expect((await pi.probe()).installed).toBe(false)
  expect(fetchStub.mock.calls.every(([url, options]) => url.endsWith('/v1/models') && !options.body && !options.method)).toBe(true)
})
