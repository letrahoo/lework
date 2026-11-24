import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EngineCapabilities, EngineStatus } from '@lework/protocol'
import type { EngineAdapter, EngineSession, EngineSessionInit } from '../src/engines/types.ts'
import { ModelRegistry } from '../src/registry/model-registry.ts'
import { isChatModelId, mergeSessionEnv } from '../src/registry/provider-adapters.ts'
import { SessionManager } from '../src/session-manager.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'
import { HubServer } from '../src/server.ts'
import { WebSocket } from 'ws'

let tmpDir: string

beforeEach(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-providers-test-'))
  vi.stubEnv('HOME', tmpDir)
  vi.stubEnv('LEWORK_HOME', path.join(tmpDir, 'host-a'))
  vi.stubEnv('LEWORK_CPA_CONFIG', path.join(tmpDir, 'host-a', 'cpa-config.yaml'))
  vi.stubEnv('LEWORK_CPA_KEY', '')
  vi.stubEnv('ANTHROPIC_BASE_URL', '')
  vi.stubEnv('ANTHROPIC_API_KEY', '')
  vi.stubEnv('OPENAI_BASE_URL', '')
  vi.stubEnv('OPENAI_API_KEY', '')
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  fs.rmSync(tmpDir, { recursive: true, force: true })
})

class RecordingEngineAdapter implements EngineAdapter {
  readonly kind = 'cli' as const
  readonly openCalls: EngineSessionInit[] = []
  readonly hotModelSwitches: string[] = []

  constructor(
    readonly id: string,
    readonly displayName: string,
    private readonly status: EngineStatus,
  ) {}

  async probe(): Promise<EngineStatus> {
    return this.status
  }

  capabilities(): EngineCapabilities {
    return {
      permissions: true,
      images: true,
      modelSwitch: true,
      modes: true,
      history: true,
      cancel: true,
    }
  }

  async openSession(init: EngineSessionInit): Promise<EngineSession> {
    this.openCalls.push(init)
    const nativeId = init.resumeNativeId ?? `native_${this.id}_${this.openCalls.length}`
    init.onNativeSessionLinked?.(nativeId)
    return {
      sessionId: init.sessionId,
      cwd: init.cwd,
      isAlive: () => true,
      prompt: async turnId => {
        init.emit({ type: 'session.state', state: 'running' })
        init.emit({ type: 'message.assistant.delta', messageId: `m_${turnId}`, text: 'ok' })
        init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
        init.emit({ type: 'session.state', state: 'idle' })
      },
      cancel: async () => {},
      respondPermission: async () => {},
      setModel: async modelId => {
        this.hotModelSwitches.push(modelId)
      },
      close: async () => {},
    }
  }
}

describe('Host-scoped Model Providers & Multi-Engine Merging', () => {
  it('filters out non-chat models while keeping cross-vendor chat models', () => {
    expect(isChatModelId('claude-opus-4-6')).toBe(true)
    expect(isChatModelId('gpt-5.4')).toBe(true)
    expect(isChatModelId('gemini-2.5-pro')).toBe(true)
    expect(isChatModelId('deepseek-reasoner')).toBe(true)
    expect(isChatModelId('glm-4.7')).toBe(true)
    expect(isChatModelId('imagen-3.0-generate')).toBe(false)
    expect(isChatModelId('dall-e-3')).toBe(false)
    expect(isChatModelId('text-embedding-3-large')).toBe(false)
    expect(isChatModelId('whisper-1')).toBe(false)
    expect(isChatModelId('gpt-image-1')).toBe(false)
    expect(isChatModelId('tts-1-hd')).toBe(false)
    expect(isChatModelId('bge-reranker-v2')).toBe(false)
  })

  it('isolates provider configs per host LEWORK_HOME and respects deleting default CPA', async () => {
    const fetchStub = vi.fn().mockResolvedValue(
      Response.json({ data: [{ id: 'claude-opus-4-6' }, { id: 'gpt-5.4' }] }),
    )
    vi.stubGlobal('fetch', fetchStub)

    // Host A: keeps default CPA + adds a custom OpenRouter gateway
    const regA = new ModelRegistry('hub_a', 'http://127.0.0.1:8317')
    await regA.upsertProvider({
      name: 'OpenRouter',
      adapter: 'generic',
      baseUrl: 'https://openrouter.ai/api/v1',
      apiKey: 'or-secret-123',
      wireApis: ['openai-chat', 'anthropic-messages'],
      enabledEngines: ['claude', 'codex', 'pi'],
    })
    const providersA = regA.listProviders([])
    expect(providersA.map(p => p.id)).toEqual(['cpa_local', 'prov_openrouter'])
    expect(providersA.find(p => p.id === 'prov_openrouter')?.hasApiKey).toBe(true)
    expect(JSON.stringify(providersA)).not.toContain('or-secret-123')

    // Host B: separate LEWORK_HOME starts cleanly with its own default CPA and deletes it
    vi.stubEnv('LEWORK_HOME', path.join(tmpDir, 'host-b'))
    const regB = new ModelRegistry('hub_b', 'http://127.0.0.1:8317')
    expect(regB.listProviders([]).map(p => p.id)).toEqual(['cpa_local'])
    regB.removeProvider('cpa_local')
    expect(regB.listProviders([])).toEqual([])

    // Reloading Host B does NOT resurrect deleted CPA
    const regBReloaded = new ModelRegistry('hub_b', 'http://127.0.0.1:8317')
    expect(regBReloaded.listProviders([])).toEqual([])

    // Host A still has both providers intact
    vi.stubEnv('LEWORK_HOME', path.join(tmpDir, 'host-a'))
    const regAReloaded = new ModelRegistry('hub_a', 'http://127.0.0.1:8317')
    expect(regAReloaded.listProviders([]).map(p => p.id)).toEqual(['cpa_local', 'prov_openrouter'])
  })

  it('merges native + compatible external provider models across Claude, Codex, Pi while blocking AGY', async () => {
    const fetchStub = vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('http://127.0.0.1:8317/')) {
        return Response.json({
          data: [
            { id: 'claude-opus-4-6' },
            { id: 'gpt-5.4' },
            { id: 'gemini-2.5-pro' },
            { id: 'gpt-image-1' },
          ],
        })
      }
      if (url.startsWith('https://gw.example.com/')) {
        return Response.json({
          data: [{ id: 'gpt-5.4' }, { id: 'deepseek-r1' }],
        })
      }
      return new Response('', { status: 404 })
    })
    vi.stubGlobal('fetch', fetchStub)

    // Seed native Codex cache so Codex has native + proxy same-name collision (`gpt-5.4`)
    fs.mkdirSync(path.join(tmpDir, '.codex'), { recursive: true })
    fs.writeFileSync(
      path.join(tmpDir, '.codex', 'models_cache.json'),
      JSON.stringify({ models: [{ slug: 'gpt-5.4', display_name: 'GPT-5.4' }] }),
    )

    const registry = new ModelRegistry('hub_a', 'http://127.0.0.1:8317')
    await registry.upsertProvider({
      name: 'Gateway',
      adapter: 'generic',
      baseUrl: 'https://gw.example.com',
      apiKey: 'gw-token',
      wireApis: ['openai-responses'],
      enabledEngines: ['codex', 'agy'], // `agy` must be stripped!
    })
    await registry.refreshProviders()

    const claudeAdapter = new RecordingEngineAdapter('claude', 'Claude Code', {
      installed: true,
      ready: true,
      authStatus: 'authenticated',
    })
    const codexAdapter = new RecordingEngineAdapter('codex', 'Codex', {
      installed: true,
      ready: true,
      authStatus: 'authenticated',
    })
    const agyAdapter = new RecordingEngineAdapter('agy', 'Antigravity', {
      installed: true,
      ready: true,
      authStatus: 'authenticated',
    })

    const sm = new SessionManager(
      [tmpDir],
      [claudeAdapter, codexAdapter, agyAdapter],
      { onEvent: () => {}, onSessionUpdated: () => {} },
      registry,
      new HostTaskInspector({ homeDir: tmpDir }),
    )

    const engines = await sm.listEngines()
    const claude = engines.find(e => e.id === 'claude')!
    const codex = engines.find(e => e.id === 'codex')!
    const agy = engines.find(e => e.id === 'agy')!

    // Claude gets native official models + CPA cross-vendor chat models (no gpt-image-1, no openai-only Gateway)
    expect(claude.models?.some(m => m.id === 'claude-opus-4-6' && m.providerId === 'sub_claude')).toBe(true)
    expect(claude.models?.some(m => m.id === 'claude-opus-4-6' && m.providerId === 'cpa_local' && m.providerName === 'CPA')).toBe(true)
    expect(claude.models?.some(m => m.id === 'gemini-2.5-pro' && m.providerId === 'cpa_local')).toBe(true)
    expect(claude.models?.some(m => m.id === 'gpt-image-1')).toBe(false)
    expect(claude.models?.some(m => m.providerId === 'prov_gateway')).toBe(false)

    // Codex merges native `sub_codex` + CPA + Gateway, preserving same-name disambiguation on `gpt-5.4`
    const codexGpt54Providers = codex.models?.filter(m => m.id === 'gpt-5.4').map(m => m.providerId)
    expect(codexGpt54Providers).toEqual(['sub_codex', 'cpa_local', 'prov_gateway'])
    expect(codex.models?.some(m => m.id === 'deepseek-r1' && m.providerId === 'prov_gateway')).toBe(true)

    // AGY never receives any external provider models and rejects external provider routing
    expect(agy.models?.every(m => !m.providerId || m.providerId.startsWith('sub_'))).toBe(true)
    expect(() => registry.resolveSessionEnv('agy', 'cpa_local', 'gemini-2.5-pro')).toThrow(/Compliance guardrail/)
    expect(() => registry.resolveSessionEnv('agy', 'prov_gateway', 'gpt-5.4')).toThrow(/Compliance guardrail/)
  })

  it('isolates official subscription env from inherited proxy vars and reopens session on cross-provider switch', async () => {
    vi.stubEnv('ANTHROPIC_BASE_URL', 'http://127.0.0.1:8317')
    vi.stubEnv('ANTHROPIC_API_KEY', 'leaked-proxy-key')
    vi.stubEnv('OPENAI_BASE_URL', 'http://127.0.0.1:8317/v1')
    vi.stubEnv('OPENAI_API_KEY', 'leaked-openai-proxy-key')

    fs.mkdirSync(path.join(tmpDir, 'host-a'), { recursive: true })
    fs.writeFileSync(
      path.join(tmpDir, 'host-a', 'cpa-config.yaml'),
      'api-keys:\n  - "cpa-yaml-key"\n',
    )

    const fetchStub = vi.fn().mockResolvedValue(
      Response.json({ data: [{ id: 'claude-opus-4-6' }, { id: 'claude-sonnet-4-6' }] }),
    )
    vi.stubGlobal('fetch', fetchStub)

    const registry = new ModelRegistry('hub_a', 'http://127.0.0.1:8317')
    await registry.refreshProviders()

    // Official route strips inherited proxy env via mergeSessionEnv
    const officialClaudeEnv = mergeSessionEnv(
      process.env,
      registry.resolveSessionEnv('claude', 'sub_claude', 'claude-opus-4-6'),
    )
    expect(officialClaudeEnv.ANTHROPIC_BASE_URL).toBeUndefined()
    expect(officialClaudeEnv.ANTHROPIC_API_KEY).toBeUndefined()
    expect(officialClaudeEnv.ANTHROPIC_MODEL).toBeUndefined()

    const officialCodexEnv = mergeSessionEnv(
      process.env,
      registry.resolveSessionEnv('codex', 'sub_codex', 'gpt-5.4'),
    )
    expect(officialCodexEnv.OPENAI_BASE_URL).toBeUndefined()
    expect(officialCodexEnv.OPENAI_API_KEY).toBeUndefined()

    // External CPA route injects normalized URL + YAML key
    const cpaClaudeEnv = mergeSessionEnv(
      process.env,
      registry.resolveSessionEnv('claude', 'cpa_local', 'claude-opus-4-6'),
    )
    expect(cpaClaudeEnv.ANTHROPIC_BASE_URL).toBe('http://127.0.0.1:8317')
    expect(cpaClaudeEnv.ANTHROPIC_API_KEY).toBe('cpa-yaml-key')
    expect(cpaClaudeEnv.ANTHROPIC_MODEL).toBe('claude-opus-4-6')

    // SessionManager: hot-switch within same provider, graceful reopen with resumeNativeId on cross-provider switch
    const claudeAdapter = new RecordingEngineAdapter('claude', 'Claude Code', {
      installed: true,
      ready: true,
      authStatus: 'authenticated',
    })
    const sm = new SessionManager(
      [tmpDir],
      [claudeAdapter],
      { onEvent: () => {}, onSessionUpdated: () => {} },
      registry,
      new HostTaskInspector({ homeDir: tmpDir }),
    )

    const created = await sm.createSession({
      engineId: 'claude',
      cwd: tmpDir,
      providerId: 'sub_claude',
      modelId: 'claude-opus-4-6',
    })
    expect(created.providerId).toBe('sub_claude')
    expect(claudeAdapter.openCalls).toHaveLength(1)

    // Same provider, different model -> hot switch (no reopen)
    await sm.setModel(created.id, 'claude-sonnet-4-6', { providerId: 'sub_claude' })
    expect(claudeAdapter.openCalls).toHaveLength(1)
    expect(claudeAdapter.hotModelSwitches).toEqual(['claude-sonnet-4-6'])

    // Cross provider (`sub_claude` -> `cpa_local`) with same model name -> reopens handle with resumeNativeId & CPA env
    const switched = await sm.setModel(created.id, 'claude-opus-4-6', { providerId: 'cpa_local' })
    expect(switched.providerId).toBe('cpa_local')
    expect(switched.model).toBe('claude-opus-4-6')
    expect(claudeAdapter.openCalls).toHaveLength(2)
    expect(claudeAdapter.openCalls[1]?.resumeNativeId).toBe('native_claude_1')
    expect(claudeAdapter.openCalls[1]?.env?.ANTHROPIC_BASE_URL).toBe('http://127.0.0.1:8317')
    expect(claudeAdapter.openCalls[1]?.env?.ANTHROPIC_API_KEY).toBe('cpa-yaml-key')

    // Switch back (`cpa_local` -> `sub_claude`) -> reopens with proxy env cleared
    const switchedBack = await sm.setModel(created.id, 'claude-opus-4-6', { providerId: 'sub_claude' })
    expect(switchedBack.providerId).toBe('sub_claude')
    expect(claudeAdapter.openCalls).toHaveLength(3)
    expect(claudeAdapter.openCalls[2]?.resumeNativeId).toBe('native_claude_1')
    expect(claudeAdapter.openCalls[2]?.env?.ANTHROPIC_BASE_URL).toBeUndefined()
  })

  it('supports provider.list / provider.probe / provider.upsert / provider.remove over Hub JSON-RPC', async () => {
    const mockProxy = http.createServer((req, res) => {
      if (req.url === '/v1/models') {
        if (req.headers.authorization !== 'Bearer demo-key') {
          res.writeHead(401)
          res.end()
          return
        }
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ data: [{ id: 'gpt-5.4' }, { id: 'claude-opus-4-6' }] }))
        return
      }
      res.writeHead(404)
      res.end()
    })
    await new Promise<void>(resolve => mockProxy.listen(0, '127.0.0.1', resolve))
    const proxyPort = (mockProxy.address() as { port: number }).port
    const proxyUrl = `http://127.0.0.1:${proxyPort}`

    const hub = new HubServer({
      config: {
        hubId: 'hub_rpc_test',
        name: 'Test Host',
        role: 'standalone',
        host: '127.0.0.1',
        port: 0,
        token: 'rpc-secret',
        workspaceRoots: [tmpDir],
        cpaEndpoint: 'http://127.0.0.1:1',
      },
      adapters: [
        new RecordingEngineAdapter('claude', 'Claude Code', {
          installed: true,
          ready: true,
          authStatus: 'authenticated',
        }),
      ],
      hostInspector: new HostTaskInspector({ homeDir: tmpDir }),
    })

    const { port } = await hub.start()
    const ws = new WebSocket(`wss://127.0.0.1:${port}/v1/ws`, { rejectUnauthorized: false })
    await new Promise<void>((resolve, reject) => {
      ws.once('open', () => resolve())
      ws.once('error', reject)
    })

    let reqId = 1
    const rpc = (method: string, params?: unknown) =>
      new Promise<any>((resolve, reject) => {
        const id = reqId++
        const onMsg = (raw: Buffer) => {
          const msg = JSON.parse(raw.toString('utf8'))
          if (msg.id !== id) return
          ws.off('message', onMsg)
          if (msg.error) reject(new Error(msg.error.message))
          else resolve(msg.result)
        }
        ws.on('message', onMsg)
        ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }))
      })

    try {
      await rpc('hub.hello', {
        protocolVersion: 1,
        token: 'rpc-secret',
        client: { name: 'test', version: '1.0.0', platform: 'macos' },
      })

      // Draft probe with wrong key -> authStatus required
      const badProbe = await rpc('provider.probe', {
        provider: {
          name: 'MyGateway',
          adapter: 'generic',
          baseUrl: proxyUrl,
          apiKey: 'wrong-key',
          wireApis: ['anthropic-messages', 'openai-responses'],
        },
      })
      expect(badProbe.provider.healthy).toBe(false)
      expect(badProbe.provider.authStatus).toBe('required')

      // Upsert with valid key -> immediately healthy & merged into Claude engine catalog
      const upserted = await rpc('provider.upsert', {
        provider: {
          name: 'MyGateway',
          adapter: 'generic',
          baseUrl: proxyUrl,
          apiKey: 'demo-key',
          wireApis: ['anthropic-messages', 'openai-responses'],
          enabledEngines: ['claude', 'codex'],
        },
      })
      expect(upserted.provider.healthy).toBe(true)
      expect(upserted.provider.models).toEqual(['claude-opus-4-6', 'gpt-5.4'])
      expect(upserted.provider.hasApiKey).toBe(true)
      expect(JSON.stringify(upserted)).not.toContain('demo-key')

      const claudeEngine = upserted.engines.find((e: any) => e.id === 'claude')
      expect(
        claudeEngine.models.some(
          (m: any) => m.id === 'gpt-5.4' && m.providerId === upserted.provider.id && m.providerName === 'MyGateway',
        ),
      ).toBe(true)

      // Curate selectedModels to only expose `gpt-5.4` (keeps full discovered list in `provider.models`)
      const filtered = await rpc('provider.upsert', {
        provider: {
          id: upserted.provider.id,
          name: 'MyGateway',
          adapter: 'generic',
          baseUrl: proxyUrl,
          wireApis: ['anthropic-messages', 'openai-responses'],
          enabledEngines: ['claude', 'codex'],
          selectedModels: ['gpt-5.4'],
        },
      })
      expect(filtered.provider.models).toEqual(['claude-opus-4-6', 'gpt-5.4'])
      expect(filtered.provider.selectedModels).toEqual(['gpt-5.4'])
      const claudeFiltered = filtered.engines.find((e: any) => e.id === 'claude')
      expect(
        claudeFiltered.models.filter((m: any) => m.providerId === upserted.provider.id).map((m: any) => m.id),
      ).toEqual(['gpt-5.4'])

      // Passing empty selectedModels clears the filter and restores all discovered models
      const restored = await rpc('provider.upsert', {
        provider: {
          id: upserted.provider.id,
          name: 'MyGateway',
          adapter: 'generic',
          baseUrl: proxyUrl,
          wireApis: ['anthropic-messages', 'openai-responses'],
          enabledEngines: ['claude', 'codex'],
          selectedModels: [],
        },
      })
      expect(restored.provider.selectedModels).toBeUndefined()
      const claudeRestored = restored.engines.find((e: any) => e.id === 'claude')
      expect(
        claudeRestored.models.filter((m: any) => m.providerId === upserted.provider.id).map((m: any) => m.id),
      ).toEqual(['claude-opus-4-6', 'gpt-5.4'])

      // Remove provider -> models disappear cleanly
      const removed = await rpc('provider.remove', { providerId: upserted.provider.id })
      expect(removed.providers.some((p: any) => p.id === upserted.provider.id)).toBe(false)
      const claudeAfter = removed.engines.find((e: any) => e.id === 'claude')
      expect(claudeAfter.models.some((m: any) => m.providerId === upserted.provider.id)).toBe(false)
    } finally {
      ws.close()
      await hub.stop()
      await new Promise<void>(resolve => mockProxy.close(() => resolve()))
    }
  })
})
