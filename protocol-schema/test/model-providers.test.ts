import { describe, expect, it } from 'vitest'
import { Methods, ProviderConfigInput, ProviderInfo, SessionMeta } from '../src/index.ts'

describe('host-scoped provider RPC & model disambiguation schemas', () => {
  it('validates provider.list / upsert / remove / probe RPC contracts', () => {
    const input = ProviderConfigInput.parse({
      name: 'OpenRouter',
      adapter: 'generic',
      baseUrl: 'https://openrouter.ai/api/v1',
      apiKey: 'or-secret',
      wireApis: ['openai-chat', 'anthropic-messages'],
      enabledEngines: ['claude', 'codex', 'pi'],
      enabled: true,
      manualModels: ['openai/gpt-5.4'],
    })
    expect(input.adapter).toBe('generic')

    const info = ProviderInfo.parse({
      id: 'prov_openrouter',
      name: 'OpenRouter',
      type: 'endpoint',
      adapter: 'generic',
      baseUrl: 'https://openrouter.ai/api/v1',
      wireApi: 'openai-chat',
      wireApis: ['openai-chat', 'anthropic-messages'],
      enabledEngines: ['claude', 'codex', 'pi'],
      enabled: true,
      hasApiKey: true,
      healthy: true,
      authStatus: 'authenticated',
      latencyMs: 42,
      models: ['openai/gpt-5.4'],
    })
    expect(info.hasApiKey).toBe(true)

    const upsertRes = Methods['provider.upsert'].result.parse({
      provider: info,
      providers: [info],
      engines: [],
    })
    expect(upsertRes.provider.id).toBe('prov_openrouter')

    const probeParams = Methods['provider.probe'].params.parse({ provider: input })
    expect(probeParams?.provider?.name).toBe('OpenRouter')

    const removeParams = Methods['provider.remove'].params.parse({ providerId: 'prov_openrouter' })
    expect(removeParams.providerId).toBe('prov_openrouter')
  })

  it('supports providerId on SessionMeta, session.setModel, and session.prompt', () => {
    const meta = SessionMeta.parse({
      id: 'sess_1',
      engineId: 'claude',
      cwd: '/tmp/repo',
      title: 'Test',
      state: 'idle',
      createdAt: 1,
      updatedAt: 2,
      lastSeq: 0,
      providerId: 'cpa_local',
      model: 'claude-opus-4-6',
    })
    expect(meta.providerId).toBe('cpa_local')

    const setModelParams = Methods['session.setModel'].params.parse({
      sessionId: 'sess_1',
      providerId: 'sub_claude',
      modelId: 'claude-opus-4-6',
    })
    expect(setModelParams.providerId).toBe('sub_claude')

    const promptParams = Methods['session.prompt'].params.parse({
      sessionId: 'sess_1',
      text: 'hello',
      providerId: 'cpa_local',
      modelId: 'claude-opus-4-6',
    })
    expect(promptParams.providerId).toBe('cpa_local')
  })
})
