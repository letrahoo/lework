import { describe, expect, it } from 'vitest'
import {
  parseAgyModelsCliOutput,
  sortReasoningEfforts,
} from '../src/storage/host-history/engine-settings.ts'

describe('Reasoning effort canonical ordering', () => {
  it('sorts and deduplicates efforts strictly from low to max', () => {
    expect(sortReasoningEfforts(['high', 'xhigh', 'medium', 'low'])).toEqual([
      'low',
      'medium',
      'high',
      'xhigh',
    ])
    expect(sortReasoningEfforts(['max', 'low', 'high', 'medium', 'xhigh', 'high'])).toEqual([
      'low',
      'medium',
      'high',
      'xhigh',
      'max',
    ])
    expect(sortReasoningEfforts(['medium', 'high', 'low'])).toEqual(['low', 'medium', 'high'])
    expect(sortReasoningEfforts([])).toEqual([])
  })

  it('normalizes unsorted AGY CLI model efforts while preserving default effort semantics', () => {
    const cliStdout = [
      'gemini-4-neon-high\tGemini 4 Neon (High)',
      'gemini-4-neon-xhigh\tGemini 4 Neon (XHigh)',
      'gemini-4-neon-medium\tGemini 4 Neon (Medium)',
      'gemini-4-neon-low\tGemini 4 Neon (Low)',
      'gemini-4-carbon-medium\tGemini 4 Carbon (Medium)',
      'gemini-4-carbon-high\tGemini 4 Carbon (High)',
      'gemini-4-carbon-low\tGemini 4 Carbon (Low)',
      'gemini-pro-orchestrator-neon\tOrchestrator (Neon)',
      'gemini-3.8-flash-high\tGemini 3.8 Flash (High)',
      'gemini-3.8-flash-medium\tGemini 3.8 Flash (Medium)',
      'gemini-3.8-flash-low\tGemini 3.8 Flash (Low)',
      'claude-opus-5-5-low\tClaude Opus 5.5 (Low)',
      'claude-opus-5-5-medium\tClaude Opus 5.5 (Medium)',
      'claude-opus-5-5-high\tClaude Opus 5.5 (High)',
      'gpt-oss-120b-medium\tGPT-OSS 120B (Medium)',
      'gemini-lite-medium\tGemini Lite (Medium)',
      'gemini-lite-low\tGemini Lite (Low)',
    ].join('\n')

    const specs = parseAgyModelsCliOutput(cliStdout)
    const byId = new Map(specs.map(s => [s.id, s]))

    expect(byId.get('gemini-4-neon')).toMatchObject({
      name: 'Gemini 4 Neon',
      efforts: ['low', 'medium', 'high', 'xhigh'],
      defaultEffort: 'high',
    })
    expect(byId.get('gemini-4-carbon')).toMatchObject({
      name: 'Gemini 4 Carbon',
      efforts: ['low', 'medium', 'high'],
      defaultEffort: 'high',
    })
    expect(byId.get('gemini-pro-orchestrator-neon')).toMatchObject({
      name: 'Orchestrator (Neon)',
      efforts: undefined,
      defaultEffort: undefined,
    })
    expect(byId.get('gemini-3.8-flash')).toMatchObject({
      name: 'Gemini 3.8 Flash',
      efforts: ['low', 'medium', 'high'],
      defaultEffort: 'high',
    })
    expect(byId.get('claude-opus-5-5')).toMatchObject({
      name: 'Claude Opus 5.5',
      efforts: ['low', 'medium', 'high'],
      defaultEffort: 'high',
    })
    expect(byId.get('gpt-oss-120b')).toMatchObject({
      name: 'GPT-OSS 120B',
      efforts: ['medium'],
      defaultEffort: 'medium',
    })
    // When `high` is absent, retain CLI's first listed default (`medium`) while sorting options ascending.
    expect(byId.get('gemini-lite')).toMatchObject({
      name: 'Gemini Lite',
      efforts: ['low', 'medium'],
      defaultEffort: 'medium',
    })
  })
})
