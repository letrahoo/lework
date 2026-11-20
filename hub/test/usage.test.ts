import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it } from 'vitest'
import { WebSocket } from 'ws'
import {
  ErrorCode,
  Methods,
  PROTOCOL_VERSION,
  type AgentEvent,
  type ProxyUsageResult,
  type SessionUsageResult,
  type UsageQuotaResult,
  type UsageSummaryResult,
} from '@lework/protocol'
import { ClaudeCodeAdapter } from '../src/engines/claude-adapter.ts'
import { DiagnosticEchoAdapter } from '../src/engines/echo-adapter.ts'
import { HubServer } from '../src/server.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'
import {
  UsageScanner,
  UsageService,
  clearPricingOverrideCache,
  resolveContextWindow,
  resolveModelPricing,
} from '../src/usage/index.ts'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '../..')

process.env.LEWORK_HOME = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-usage-test-'))

class TestWsClient {
  private ws!: WebSocket
  private nextId = 1
  private readonly pending = new Map<
    number,
    { resolve: (v: any) => void; reject: (e: any) => void }
  >()
  readonly events: AgentEvent[] = []
  private readonly waiters: Array<(ev: AgentEvent) => boolean> = []

  async connect(url: string): Promise<void> {
    this.ws = new WebSocket(url, { rejectUnauthorized: false })
    await new Promise<void>((resolve, reject) => {
      this.ws.once('open', () => resolve())
      this.ws.once('error', reject)
    })
    this.ws.on('message', raw => {
      const msg = JSON.parse(String(raw))
      if (typeof msg.id === 'number') {
        const waiter = this.pending.get(msg.id)
        if (!waiter) return
        this.pending.delete(msg.id)
        if (msg.error) waiter.reject(msg.error)
        else waiter.resolve(msg.result)
        return
      }
      if (msg.method === 'stream.event' && msg.params?.event) {
        const ev = msg.params.event as AgentEvent
        this.events.push(ev)
        for (let i = this.waiters.length - 1; i >= 0; i--) {
          if (this.waiters[i]!(ev)) this.waiters.splice(i, 1)
        }
      }
    })
  }

  call<T = any>(method: string, params?: unknown): Promise<T> {
    const id = this.nextId++
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }))
    })
  }

  waitForEvent<T extends AgentEvent['type']>(
    type: T,
    predicate?: (ev: Extract<AgentEvent, { type: T }>) => boolean,
  ): Promise<Extract<AgentEvent, { type: T }>> {
    const existing = this.events.find(
      (e): e is Extract<AgentEvent, { type: T }> =>
        e.type === type && (!predicate || predicate(e as Extract<AgentEvent, { type: T }>)),
    )
    if (existing) return Promise.resolve(existing)
    return new Promise(resolve => {
      this.waiters.push(ev => {
        if (ev.type === type && (!predicate || predicate(ev as Extract<AgentEvent, { type: T }>))) {
          resolve(ev as Extract<AgentEvent, { type: T }>)
          return true
        }
        return false
      })
    })
  }

  close(): void {
    this.ws?.close()
  }
}

function makeTempDir(prefix: string): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix))
}

describe('Usage Statistics, Context Window & Subscription Quota Backend', () => {
  const cleanupDirs: string[] = []
  let activeServer: HubServer | null = null
  let mockCpaServer: http.Server | null = null

  afterEach(async () => {
    clearPricingOverrideCache()
    if (activeServer) {
      await activeServer.stop()
      activeServer = null
    }
    if (mockCpaServer) {
      await new Promise<void>(resolve => mockCpaServer!.close(() => resolve()))
      mockCpaServer = null
    }
    while (cleanupDirs.length > 0) {
      const dir = cleanupDirs.pop()!
      fs.rmSync(dir, { recursive: true, force: true })
    }
  })

  it('1. parses Claude JSONL, deduplicates streaming chunks (message.id + requestId / requestRef), computes cost & cache hit %', () => {
    const homeDir = makeTempDir('lework-claude-usage-')
    const extraClaudeCfg = makeTempDir('lework-claude-cfg-')
    cleanupDirs.push(homeDir, extraClaudeCfg)

    const nowMs = Date.parse('2026-11-19T12:00:00.000Z')
    const projDir = path.join(homeDir, '.claude', 'projects', '-Users-dev-proj')
    fs.mkdirSync(projDir, { recursive: true })

    const sessionUuid = '11111111-2222-3333-4444-555555555555'
    const sessionFile = path.join(projDir, `${sessionUuid}.jsonl`)

    // Streaming chunks of turn 1 (`msg_a1:req_1`): should keep highest `output_tokens` (200)
    // Turn 2 (`msg_a2` + `requestRef` from Claude 2.x): multi-block duplicate, should count once (output 100)
    // Turn 3 (missing requestId): falls back to line offset key so two distinct lines are both preserved
    // Synthetic 0-token API error row: ignored
    const lines = [
      JSON.stringify({
        type: 'assistant',
        sessionId: sessionUuid,
        cwd: '/Users/dev/proj',
        timestamp: '2026-11-19T10:05:00.000Z',
        message: {
          id: 'msg_synth',
          role: 'assistant',
          model: '<synthetic>',
          usage: { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
        },
      }),
      JSON.stringify({
        type: 'assistant',
        sessionId: sessionUuid,
        requestId: 'req_1',
        cwd: '/Users/dev/proj',
        timestamp: '2026-11-19T10:10:00.000Z',
        message: {
          id: 'msg_a1',
          role: 'assistant',
          model: 'claude-sonnet-4-5-20250929',
          usage: {
            input_tokens: 2_000,
            output_tokens: 45,
            cache_creation_input_tokens: 8_000,
            cache_read_input_tokens: 0,
          },
        },
      }),
      JSON.stringify({
        type: 'assistant',
        sessionId: sessionUuid,
        requestId: 'req_1',
        cwd: '/Users/dev/proj',
        timestamp: '2026-11-19T10:10:04.000Z',
        message: {
          id: 'msg_a1',
          role: 'assistant',
          model: 'claude-sonnet-4-5-20250929',
          usage: {
            input_tokens: 2_000,
            output_tokens: 200,
            cache_creation_input_tokens: 8_000,
            cache_read_input_tokens: 0,
          },
        },
      }),
      // Out-of-order lower output_tokens chunk for same msg_a1:req_1 -> ignored
      JSON.stringify({
        type: 'assistant',
        sessionId: sessionUuid,
        requestId: 'req_1',
        cwd: '/Users/dev/proj',
        timestamp: '2026-11-19T10:10:05.000Z',
        message: {
          id: 'msg_a1',
          role: 'assistant',
          model: 'claude-sonnet-4-5-20250929',
          usage: {
            input_tokens: 2_000,
            output_tokens: 120,
            cache_creation_input_tokens: 8_000,
            cache_read_input_tokens: 0,
          },
        },
      }),
      // Turn 2 with Claude 2.x `requestRef`
      JSON.stringify({
        type: 'assistant',
        sessionId: sessionUuid,
        requestRef: 'ref_2',
        cwd: '/Users/dev/proj',
        timestamp: '2026-11-19T10:15:00.000Z',
        message: {
          id: 'msg_a2',
          role: 'assistant',
          model: 'claude-sonnet-4-5-20250929',
          usage: {
            input_tokens: 1_000,
            output_tokens: 100,
            cache_creation_input_tokens: 0,
            cache_read_input_tokens: 9_000,
          },
        },
      }),
      JSON.stringify({
        type: 'assistant',
        sessionId: sessionUuid,
        requestRef: 'ref_2',
        cwd: '/Users/dev/proj',
        timestamp: '2026-11-19T10:15:01.000Z',
        message: {
          id: 'msg_a2',
          role: 'assistant',
          model: 'claude-sonnet-4-5-20250929',
          usage: {
            input_tokens: 1_000,
            output_tokens: 100,
            cache_creation_input_tokens: 0,
            cache_read_input_tokens: 9_000,
          },
        },
      }),
    ]
    fs.writeFileSync(sessionFile, lines.join('\n') + '\n', 'utf8')

    // Also verify `$CLAUDE_CONFIG_DIR/projects/**/*.jsonl` scanning + missing requestId fallback
    const altProjDir = path.join(extraClaudeCfg, 'projects', '-alt')
    fs.mkdirSync(altProjDir, { recursive: true })
    fs.writeFileSync(
      path.join(altProjDir, 'alt-sess.jsonl'),
      [
        JSON.stringify({
          type: 'assistant',
          timestamp: '2026-11-19T11:00:00.000Z',
          message: {
            id: 'msg_no_req',
            role: 'assistant',
            model: 'claude-opus-4-6',
            usage: { input_tokens: 1_000, output_tokens: 100, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
          },
        }),
        JSON.stringify({
          type: 'assistant',
          timestamp: '2026-11-19T11:01:00.000Z',
          message: {
            id: 'msg_no_req',
            role: 'assistant',
            model: 'claude-opus-4-6',
            usage: { input_tokens: 1_000, output_tokens: 100, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
          },
        }),
      ].join('\n') + '\n',
      'utf8',
    )

    const service = new UsageService({
      cpaEndpoint: 'http://127.0.0.1:8317',
      homeDir,
      claudeConfigDir: extraClaudeCfg,
      memoTtlMs: 0,
      now: () => nowMs,
    })

    const summary = service.getSummary({ days: 7 })
    expect(Methods['usage.summary'].result.parse(summary)).toBeTruthy()

    // Main session:
    // Turn 1: in=2000, out=200, cw=8000, cr=0 -> total=10200
    // Turn 2: in=1000, out=100, cw=0, cr=9000 -> total=10100
    // Alt session (2 lines without requestId -> both counted):
    // 2x (in=1000, out=100) -> in=2000, out=200, total=2200
    expect(summary.totals.requests).toBe(4)
    expect(summary.totals.inputTokens).toBe(5_000)
    expect(summary.totals.outputTokens).toBe(500)
    expect(summary.totals.cacheWriteTokens).toBe(8_000)
    expect(summary.totals.cacheReadTokens).toBe(9_000)
    expect(summary.totals.totalTokens).toBe(22_500)

    // Sonnet 4.5 cost ($3/M in, $15/M out, $3.75/M cw, $0.30/M cr):
    // (3000*3 + 300*15 + 8000*3.75 + 9000*0.30) / 1e6 = (9000 + 4500 + 30000 + 2700) / 1e6 = 0.0462
    // Opus 4.6 cost ($5/M in, $25/M out):
    // (2000*5 + 200*25) / 1e6 = (10000 + 5000) / 1e6 = 0.015
    // Total = 0.0612 USD
    expect(summary.totals.estimatedCostUsd).toBeCloseTo(0.0612, 6)

    // Session cache hit % on main session:
    // Prompt sum = 3000 (in) + 8000 (cw) + 9000 (cr) = 20_000; cacheRead = 9000 -> 45.0%
    const sessUsage = service.getSessionUsage(`host:claude:${sessionUuid}`)
    expect(sessUsage.totals.requests).toBe(2)
    expect(sessUsage.cacheHitPercent).toBe(45)
    expect(sessUsage.totals.estimatedCostUsd).toBeCloseTo(0.0462, 6)

    // Verify optional user pricing.json override
    const customDataDir = makeTempDir('lework-pricing-override-')
    cleanupDirs.push(customDataDir)
    fs.writeFileSync(
      path.join(customDataDir, 'pricing.json'),
      JSON.stringify({
        models: {
          'claude-sonnet-4-5': { input: 10, output: 50, cacheWrite: 10, cacheRead: 1 },
        },
      }),
      'utf8',
    )
    const customRate = resolveModelPricing('claude-sonnet-4-5-20250929', customDataDir)
    expect(customRate).toEqual({ input: 10, output: 50, cacheWrite: 10, cacheRead: 1 })
  })

  it('2. computes Codex rollout cumulative total_token_usage deltas without double counting and parses rate_limits', () => {
    const homeDir = makeTempDir('lework-codex-usage-')
    cleanupDirs.push(homeDir)

    const nowMs = Date.parse('2026-11-19T15:00:00.000Z')
    const codexDayDir = path.join(homeDir, '.codex', 'sessions', '2026', '11', '19')
    fs.mkdirSync(codexDayDir, { recursive: true })

    const nativeId = '01a1efa2-adc1-7e92-8794-c343ac103892'
    const rolloutPath = path.join(
      codexDayDir,
      `rollout-2026-11-19T14-00-00-${nativeId}.jsonl`,
    )

    const primaryResetsSec = Math.floor((nowMs + 2 * 3600_000) / 1000)
    const secondaryResetsInSec = 4 * 24 * 3600

    const rolloutLines = [
      JSON.stringify({
        timestamp: '2026-11-19T14:00:00.000Z',
        type: 'session_meta',
        payload: { id: nativeId, cwd: '/Users/dev/codex-app' },
      }),
      JSON.stringify({
        timestamp: '2026-11-19T14:00:01.000Z',
        type: 'turn_context',
        payload: { model: 'gpt-5.2', cwd: '/Users/dev/codex-app' },
      }),
      // Snapshot 1: cumulative in=20000 (of which cached=15000), out=500, total=20500
      JSON.stringify({
        timestamp: '2026-11-19T14:01:00.000Z',
        type: 'event_msg',
        payload: {
          type: 'token_count',
          info: {
            total_token_usage: {
              input_tokens: 20_000,
              cached_input_tokens: 15_000,
              cache_write_input_tokens: 0,
              output_tokens: 500,
              total_tokens: 20_500,
            },
            last_token_usage: {
              input_tokens: 20_000,
              cached_input_tokens: 15_000,
              cache_write_input_tokens: 0,
              output_tokens: 500,
              total_tokens: 20_500,
            },
            model_context_window: 258_400,
          },
          rate_limits: {
            primary: { used_percent: 25, window_minutes: 300, resets_at: primaryResetsSec },
            secondary: { used_percent: 12.5, window_minutes: 10_080, resets_in_seconds: secondaryResetsInSec },
          },
        },
      }),
      // Duplicate snapshot with identical total_tokens (20500) -> MUST be skipped!
      JSON.stringify({
        timestamp: '2026-11-19T14:01:02.000Z',
        type: 'event_msg',
        payload: {
          type: 'token_count',
          info: {
            total_token_usage: {
              input_tokens: 20_000,
              cached_input_tokens: 15_000,
              cache_write_input_tokens: 0,
              output_tokens: 500,
              total_tokens: 20_500,
            },
            last_token_usage: {
              input_tokens: 20_000,
              cached_input_tokens: 15_000,
              cache_write_input_tokens: 0,
              output_tokens: 500,
              total_tokens: 20_500,
            },
            model_context_window: 258_400,
          },
        },
      }),
      // Snapshot 2: cumulative in=50000 (cached=35000), out=1200, total=51200
      // Delta should be: rawIn=30000 (cached=20000 -> uncached=10000), out=700, total=30700
      JSON.stringify({
        timestamp: '2026-11-19T14:10:00.000Z',
        type: 'event_msg',
        payload: {
          type: 'token_count',
          info: {
            total_token_usage: {
              input_tokens: 50_000,
              cached_input_tokens: 35_000,
              cache_write_input_tokens: 0,
              output_tokens: 1_200,
              total_tokens: 51_200,
            },
            last_token_usage: {
              input_tokens: 30_000,
              cached_input_tokens: 20_000,
              cache_write_input_tokens: 0,
              output_tokens: 700,
              total_tokens: 30_700,
            },
            model_context_window: 258_400,
          },
          rate_limits: {
            primary: { used_percent: 42.5, window_minutes: 300, resets_at: primaryResetsSec },
            secondary: { used_percent: 18, window_minutes: 10_080, resets_in_seconds: secondaryResetsInSec },
          },
        },
      }),
    ]
    fs.writeFileSync(rolloutPath, rolloutLines.join('\n') + '\n', 'utf8')

    const service = new UsageService({
      cpaEndpoint: 'http://127.0.0.1:8317',
      homeDir,
      memoTtlMs: 0,
      now: () => nowMs,
    })

    const summary = service.getSummary({ days: 7, engineId: 'codex' })
    expect(summary.totals.requests).toBe(2)
    // Uncached input = 50000 - 35000 = 15000; cacheRead = 35000; output = 1200; total = 51200 (NO double counting!)
    expect(summary.totals.inputTokens).toBe(15_000)
    expect(summary.totals.cacheReadTokens).toBe(35_000)
    expect(summary.totals.outputTokens).toBe(1_200)
    expect(summary.totals.totalTokens).toBe(51_200)

    const quota = service.getQuota()
    expect(Methods['usage.quota'].result.parse(quota)).toBeTruthy()
    const codex5h = quota.windows.find(w => w.id === 'codex:5h')!
    const codex7d = quota.windows.find(w => w.id === 'codex:7d')!

    expect(codex5h.source).toBe('reported')
    expect(codex5h.active).toBe(true)
    expect(codex5h.usedPercent).toBe(42.5)
    expect(codex5h.usedTokens).toBe(51_200)
    expect(codex5h.resetsAt).toBe(primaryResetsSec * 1000)

    expect(codex7d.source).toBe('reported')
    expect(codex7d.active).toBe(true)
    expect(codex7d.usedPercent).toBe(18)
    expect(codex7d.usedTokens).toBe(51_200)
    expect(codex7d.resetsAt).toBe(
      Date.parse('2026-11-19T14:10:00.000Z') + secondaryResetsInSec * 1000,
    )

    // Verify expired `resets_at < now` resets usedPercent to 0 and active to false
    const futureNow = primaryResetsSec * 1000 + 60_000
    const serviceAfterReset = new UsageService({
      cpaEndpoint: 'http://127.0.0.1:8317',
      homeDir,
      memoTtlMs: 0,
      now: () => futureNow,
    })
    const quotaAfter = serviceAfterReset.getQuota()
    const expired5h = quotaAfter.windows.find(w => w.id === 'codex:5h')!
    expect(expired5h.usedPercent).toBe(0)
    expect(expired5h.active).toBe(false)
  })

  it('3. computes Claude 5-hour billing blocks (active, resetsAt, burnRateTokensPerMin, projectedTokens)', () => {
    const homeDir = makeTempDir('lework-blocks-')
    cleanupDirs.push(homeDir)

    const projDir = path.join(homeDir, '.claude', 'projects', '-work')
    fs.mkdirSync(projDir, { recursive: true })

    // Use local timestamps so hour-flooring is timezone-independent
    const baseHour = new Date(2026, 10, 19, 10, 0, 0, 0).getTime() // 10:00:00 local
    const oldBlockTs = baseHour - 8 * 3600_000 + 15 * 60_000 // 02:15 local (expired block: 02:00..07:00)
    const activeTurn1Ts = baseHour + 20 * 60_000 // 10:20 local -> starts block [10:00 .. 15:00]
    const activeTurn2Ts = baseHour + 90 * 60_000 // 11:30 local -> inside [10:00 .. 15:00]
    const nowMs = baseHour + 150 * 60_000 // 12:30 local (150m elapsed, 150m remaining in 300m block)

    const rows = [
      {
        type: 'assistant',
        requestId: 'r_old',
        timestamp: new Date(oldBlockTs).toISOString(),
        message: {
          id: 'm_old',
          role: 'assistant',
          model: 'claude-sonnet-4-5',
          usage: { input_tokens: 50_000, output_tokens: 10_000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
        },
      },
      {
        type: 'assistant',
        requestId: 'r_cur_1',
        timestamp: new Date(activeTurn1Ts).toISOString(),
        message: {
          id: 'm_cur_1',
          role: 'assistant',
          model: 'claude-sonnet-4-5',
          usage: { input_tokens: 100_000, output_tokens: 20_000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
        },
      },
      {
        type: 'assistant',
        requestId: 'r_cur_2',
        timestamp: new Date(activeTurn2Ts).toISOString(),
        message: {
          id: 'm_cur_2',
          role: 'assistant',
          model: 'claude-sonnet-4-5',
          usage: { input_tokens: 150_000, output_tokens: 30_000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
        },
      },
    ]
    fs.writeFileSync(
      path.join(projDir, 'sess-blocks.jsonl'),
      rows.map(r => JSON.stringify(r)).join('\n') + '\n',
      'utf8',
    )

    const service = new UsageService({
      cpaEndpoint: 'http://127.0.0.1:8317',
      homeDir,
      memoTtlMs: 0,
      now: () => nowMs,
    })

    const quota = service.getQuota()
    const claude5h = quota.windows.find(w => w.id === 'claude:5h')!
    const claude7d = quota.windows.find(w => w.id === 'claude:7d')!

    expect(claude5h.active).toBe(true)
    expect(claude5h.startedAt).toBe(baseHour)
    expect(claude5h.resetsAt).toBe(baseHour + 18_000_000)
    // Active block tokens = 120_000 + 180_000 = 300_000
    expect(claude5h.usedTokens).toBe(300_000)
    // 150 min elapsed -> 300_000 / 150 = 2_000 tokens/min; 150 min remaining -> projected = 600_000
    expect(claude5h.burnRateTokensPerMin).toBe(2_000)
    expect(claude5h.projectedTokens).toBe(600_000)

    // 7d window includes both old block (60_000) + active block (300_000) = 360_000
    expect(claude7d.active).toBe(true)
    expect(claude7d.usedTokens).toBe(360_000)
  })

  it('4. calculates accurate usage.session contextTokens / contextPercent for both 200k and [1m] models', () => {
    expect(resolveContextWindow('claude', 'claude-sonnet-4-5')).toBe(200_000)
    expect(resolveContextWindow('claude', 'claude-sonnet-4-5[1m]')).toBe(1_000_000)
    expect(resolveContextWindow('claude', 'claude-opus-4-6-1m')).toBe(1_000_000)
    expect(resolveContextWindow('agy', 'gemini-2.5-pro')).toBe(1_000_000)
    expect(resolveContextWindow('codex', 'gpt-5-codex')).toBe(258_400)
    expect(resolveContextWindow('codex', 'gpt-5-codex', 400_000)).toBe(400_000)

    const homeDir = makeTempDir('lework-ctx-win-')
    cleanupDirs.push(homeDir)
    const projDir = path.join(homeDir, '.claude', 'projects', '-work')
    fs.mkdirSync(projDir, { recursive: true })

    // 200k standard model session: latest turn input(10k) + cacheWrite(20k) + cacheRead(120k) = 150k -> 75.0%
    fs.writeFileSync(
      path.join(projDir, 'sess-200k.jsonl'),
      [
        JSON.stringify({
          type: 'assistant',
          requestId: 'r1',
          timestamp: '2026-11-19T10:00:00.000Z',
          message: {
            id: 'm1',
            role: 'assistant',
            model: 'claude-sonnet-4-5',
            usage: { input_tokens: 5_000, output_tokens: 500, cache_creation_input_tokens: 45_000, cache_read_input_tokens: 0 },
          },
        }),
        JSON.stringify({
          type: 'assistant',
          requestId: 'r2',
          timestamp: '2026-11-19T10:05:00.000Z',
          message: {
            id: 'm2',
            role: 'assistant',
            model: 'claude-sonnet-4-5',
            usage: { input_tokens: 10_000, output_tokens: 800, cache_creation_input_tokens: 20_000, cache_read_input_tokens: 120_000 },
          },
        }),
      ].join('\n') + '\n',
      'utf8',
    )

    // [1m] extended context model session: latest turn prompt = 150k / 1_000_000 -> 15.0%
    fs.writeFileSync(
      path.join(projDir, 'sess-1m.jsonl'),
      JSON.stringify({
        type: 'assistant',
        requestId: 'r1',
        timestamp: '2026-11-19T10:05:00.000Z',
        message: {
          id: 'm1',
          role: 'assistant',
          model: 'claude-opus-4-6[1m]',
          usage: { input_tokens: 15_000, output_tokens: 1_200, cache_creation_input_tokens: 35_000, cache_read_input_tokens: 100_000 },
        },
      }) + '\n',
      'utf8',
    )

    const service = new UsageService({
      cpaEndpoint: 'http://127.0.0.1:8317',
      homeDir,
      memoTtlMs: 0,
    })

    const res200k = service.getSessionUsage('host:claude:sess-200k')
    expect(Methods['usage.session'].result.parse(res200k)).toBeTruthy()
    expect(res200k.contextWindow).toBe(200_000)
    expect(res200k.contextTokens).toBe(150_000)
    expect(res200k.contextPercent).toBe(75)
    expect(res200k.lastInputTokens).toBe(10_000)
    expect(res200k.lastOutputTokens).toBe(800)

    const res1m = service.getSessionUsage('host:claude:sess-1m')
    expect(res1m.contextWindow).toBe(1_000_000)
    expect(res1m.contextTokens).toBe(150_000)
    expect(res1m.contextPercent).toBe(15)
  })

  it('5. reads only newly appended bytes via incremental byte-offset cache (including multi-byte UTF-8)', () => {
    const homeDir = makeTempDir('lework-incremental-')
    cleanupDirs.push(homeDir)

    const projDir = path.join(homeDir, '.claude', 'projects', '-utf8')
    fs.mkdirSync(projDir, { recursive: true })
    const filePath = path.join(projDir, 'sess-inc.jsonl')

    const turn1 =
      JSON.stringify({
        type: 'assistant',
        requestId: 'req_inc_1',
        cwd: '/Users/dev/中文目录/项目',
        timestamp: new Date().toISOString(),
        message: {
          id: 'msg_inc_1',
          role: 'assistant',
          model: 'claude-sonnet-4-5',
          content: [{ type: 'text', text: '第一轮中文回复：已完成增量字节偏移缓存设计' }],
          usage: { input_tokens: 4_000, output_tokens: 300, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
        },
      }) + '\n'

    fs.writeFileSync(filePath, turn1, 'utf8')
    const turn1Bytes = Buffer.byteLength(turn1, 'utf8')

    const scanner = new UsageScanner({ homeDir, memoTtlMs: 0 })
    const service = new UsageService({
      cpaEndpoint: 'http://127.0.0.1:8317',
      scanner,
    })

    const first = service.getSessionUsage('host:claude:sess-inc')
    expect(first.totals.requests).toBe(1)
    expect(first.totals.totalTokens).toBe(4_300)
    expect(scanner.totalBytesRead).toBe(turn1Bytes)
    expect(scanner.getCachedFileState(filePath)?.byteOffset).toBe(turn1Bytes)

    // Re-querying unchanged file reads ZERO additional bytes
    const cachedRepeat = service.getSessionUsage('host:claude:sess-inc')
    expect(cachedRepeat.totals.totalTokens).toBe(4_300)
    expect(scanner.totalBytesRead).toBe(turn1Bytes)

    // Append a second turn -> scanner reads ONLY `turn2Bytes` starting from `turn1Bytes`
    const turn2 =
      JSON.stringify({
        type: 'assistant',
        requestId: 'req_inc_2',
        cwd: '/Users/dev/中文目录/项目',
        timestamp: new Date().toISOString(),
        message: {
          id: 'msg_inc_2',
          role: 'assistant',
          model: 'claude-sonnet-4-5',
          content: [{ type: 'text', text: '第二轮追加回复：只读取新增字节！' }],
          usage: { input_tokens: 1_500, output_tokens: 250, cache_creation_input_tokens: 0, cache_read_input_tokens: 4_000 },
        },
      }) + '\n'
    const turn2Bytes = Buffer.byteLength(turn2, 'utf8')
    fs.appendFileSync(filePath, turn2, 'utf8')

    const second = service.getSessionUsage('host:claude:sess-inc')
    expect(second.totals.requests).toBe(2)
    expect(second.totals.inputTokens).toBe(5_500)
    expect(second.totals.outputTokens).toBe(550)
    expect(second.totals.cacheReadTokens).toBe(4_000)
    expect(second.totals.totalTokens).toBe(10_050)
    expect(second.contextTokens).toBe(5_500) // 1500 + 4000
    expect(scanner.totalBytesRead).toBe(turn1Bytes + turn2Bytes)
    expect(scanner.getCachedFileState(filePath)?.byteOffset).toBe(turn1Bytes + turn2Bytes)
  })

  it('6. serves usage.summary, usage.quota, usage.session & usage.proxy end-to-end over HubServer WSS', async () => {
    const fakeHome = makeTempDir('lework-wss-usage-')
    cleanupDirs.push(fakeHome)

    const nowIso = new Date().toISOString()

    // Seed Claude host log
    const claudeDir = path.join(fakeHome, '.claude', 'projects', '-repo')
    fs.mkdirSync(claudeDir, { recursive: true })
    fs.writeFileSync(
      path.join(claudeDir, 'claude-e2e-1.jsonl'),
      [
        JSON.stringify({
          type: 'user',
          cwd: repoRoot,
          timestamp: nowIso,
          message: { role: 'user', content: 'Check usage stats' },
        }),
        JSON.stringify({
          type: 'assistant',
          requestId: 'req_e2e_1',
          cwd: repoRoot,
          timestamp: nowIso,
          message: {
            id: 'msg_e2e_1',
            role: 'assistant',
            model: 'claude-sonnet-4-5',
            content: [{ type: 'text', text: 'Done.' }],
            usage: {
              input_tokens: 12_000,
              output_tokens: 1_500,
              cache_creation_input_tokens: 8_000,
              cache_read_input_tokens: 30_000,
            },
          },
        }),
      ].join('\n') + '\n',
      'utf8',
    )

    // Seed Codex host rollout log
    const codexDir = path.join(fakeHome, '.codex', 'sessions', '2026', '11', '19')
    fs.mkdirSync(codexDir, { recursive: true })
    const codexUuid = '22222222-3333-4444-5555-666666666666'
    fs.writeFileSync(
      path.join(codexDir, `rollout-2026-11-19T12-00-00-${codexUuid}.jsonl`),
      [
        JSON.stringify({
          timestamp: nowIso,
          type: 'turn_context',
          payload: { model: 'gpt-5-codex', cwd: repoRoot },
        }),
        JSON.stringify({
          timestamp: nowIso,
          type: 'event_msg',
          payload: {
            type: 'token_count',
            info: {
              total_token_usage: {
                input_tokens: 25_840,
                cached_input_tokens: 10_000,
                cache_write_input_tokens: 0,
                output_tokens: 1_160,
                total_tokens: 27_000,
              },
              last_token_usage: {
                input_tokens: 25_840,
                cached_input_tokens: 10_000,
                cache_write_input_tokens: 0,
                output_tokens: 1_160,
                total_tokens: 27_000,
              },
              model_context_window: 258_400,
            },
            rate_limits: {
              primary: {
                used_percent: 19.5,
                window_minutes: 300,
                resets_at: Math.floor((Date.now() + 3600_000) / 1000),
              },
              secondary: {
                used_percent: 7.2,
                window_minutes: 10_080,
                resets_at: Math.floor((Date.now() + 86_400_000) / 1000),
              },
            },
          },
        }),
      ].join('\n') + '\n',
      'utf8',
    )

    // Seed AGY transcript without token counts to verify graceful Chinese note fallback
    const agyLogDir = path.join(
      fakeHome,
      '.gemini',
      'antigravity-cli',
      'brain',
      'agy-conv-legacy',
      '.system_generated',
      'logs',
    )
    fs.mkdirSync(agyLogDir, { recursive: true })
    fs.writeFileSync(
      path.join(agyLogDir, 'transcript.jsonl'),
      [
        JSON.stringify({ step_index: 0, type: 'USER_INPUT', created_at: nowIso, content: 'hi' }),
        JSON.stringify({ step_index: 1, type: 'PLANNER_RESPONSE', created_at: nowIso, content: 'hello' }),
      ].join('\n') + '\n',
      'utf8',
    )

    // Start ephemeral mock CPA server for `usage.proxy`
    let sawAuthHeader = ''
    mockCpaServer = http.createServer((req, res) => {
      if (req.url === '/v1/models') {
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(
          JSON.stringify({
            data: [
              { id: 'claude-sonnet-4-5' },
              { id: 'claude-opus-4-6' },
              { id: 'gpt-5.2' },
            ],
          }),
        )
        return
      }
      if (req.url === '/v0/management/usage') {
        sawAuthHeader = req.headers.authorization ?? ''
        if (sawAuthHeader !== 'Bearer test-mgmt-secret') {
          res.writeHead(401, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ error: 'unauthorized' }))
          return
        }
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(
          JSON.stringify({
            usage: {
              total_requests: 42,
              success_count: 40,
              failure_count: 2,
              total_tokens: 980_000,
              apis: {
                '/v1/messages': {
                  total_requests: 30,
                  total_tokens: 700_000,
                  models: {
                    'claude-sonnet-4-5': { total_requests: 30, total_tokens: 700_000 },
                  },
                },
                '/v1/responses': {
                  total_requests: 12,
                  total_tokens: 280_000,
                  models: {
                    'gpt-5.2': { total_requests: 12, total_tokens: 280_000 },
                  },
                },
              },
            },
          }),
        )
        return
      }
      res.writeHead(404)
      res.end()
    })
    await new Promise<void>(resolve => mockCpaServer!.listen(0, '127.0.0.1', () => resolve()))
    const cpaPort = (mockCpaServer.address() as { port: number }).port
    const cpaEndpoint = `http://127.0.0.1:${cpaPort}`

    const mockAcpScript = path.join(__dirname, 'mock-acp-agent.mjs')
    const claudeAdapter = new ClaudeCodeAdapter({
      acpBinary: process.execPath,
      acpArgs: [mockAcpScript],
    })

    const usageService = new UsageService({
      cpaEndpoint,
      cpaManagementKey: 'test-mgmt-secret',
      homeDir: fakeHome,
    })

    activeServer = new HubServer({
      config: {
        hubId: 'hub_usage_e2e',
        name: 'UsageE2EMac',
        role: 'slave',
        host: '127.0.0.1',
        port: 0,
        token: 'usage-e2e-secret-token',
        workspaceRoots: [repoRoot],
        cpaEndpoint,
        cpaManagementKey: 'test-mgmt-secret',
      },
      adapters: [claudeAdapter, new DiagnosticEchoAdapter()],
      hostInspector: new HostTaskInspector({ homeDir: fakeHome }),
      usageService,
    })

    const { port } = await activeServer.start()
    const client = new TestWsClient()
    await client.connect(`wss://127.0.0.1:${port}/v1/ws`)

    // Unauthenticated usage calls are rejected
    await expect(client.call('usage.summary')).rejects.toMatchObject({
      code: ErrorCode.Unauthorized,
    })

    const hello = await client.call('hub.hello', {
      protocolVersion: PROTOCOL_VERSION,
      token: 'usage-e2e-secret-token',
      client: { name: 'lework-test', version: '0.4.0', platform: 'ios' },
    })
    expect(hello.capabilities).toContain('usage.stats.v1')

    // 6a. `usage.summary`
    const summary = await client.call<UsageSummaryResult>('usage.summary', { days: 7 })
    expect(Methods['usage.summary'].result.parse(summary)).toBeTruthy()
    expect(summary.days).toBe(7)
    // Claude (51_500) + Codex (27_000) = 78_500 tokens; requests = 1 + 1 + 1 (AGY turn) = 3
    expect(summary.totals.totalTokens).toBe(78_500)
    expect(summary.totals.requests).toBe(3)
    expect(summary.daily.length).toBe(1)
    const agyEngine = summary.byEngine.find(e => e.engineId === 'agy')!
    expect(agyEngine.supported).toBe(false)
    expect(agyEngine.requests).toBe(1)
    expect(agyEngine.note).toBe('AGY 本地日志未记录 Token 明细（仅统计交互轮次）')

    // 6b. `usage.quota`
    const quota = await client.call<UsageQuotaResult>('usage.quota')
    expect(Methods['usage.quota'].result.parse(quota)).toBeTruthy()
    expect(quota.windows.map(w => w.id)).toEqual([
      'claude:5h',
      'claude:7d',
      'codex:5h',
      'codex:7d',
    ])
    expect(quota.windows.find(w => w.id === 'claude:5h')!.active).toBe(true)
    expect(quota.windows.find(w => w.id === 'codex:5h')!.usedPercent).toBe(19.5)

    // 6c. `usage.session` for both `host:*` AND Hub-managed session
    const hostClaudeSess = await client.call<SessionUsageResult>('usage.session', {
      sessionId: 'host:claude:claude-e2e-1',
    })
    expect(hostClaudeSess.contextTokens).toBe(50_000) // 12k + 8k + 30k
    expect(hostClaudeSess.contextWindow).toBe(200_000)
    expect(hostClaudeSess.contextPercent).toBe(25)

    const hostCodexSess = await client.call<SessionUsageResult>('usage.session', {
      sessionId: `host:codex:${codexUuid}`,
    })
    expect(hostCodexSess.contextTokens).toBe(25_840)
    expect(hostCodexSess.contextWindow).toBe(258_400)
    expect(hostCodexSess.contextPercent).toBe(10)

    // Create a Hub-managed session (`mock` emits a `usage` event on prompt)
    const { session: echoSession } = await client.call('session.create', {
      engineId: 'mock',
      cwd: repoRoot,
    })
    await client.call('session.prompt', {
      sessionId: echoSession.id,
      text: 'Hello Hub usage!',
    })
    await client.waitForEvent('turn.done', ev => ev.sessionId === echoSession.id)

    const managedUsage = await client.call<SessionUsageResult>('usage.session', {
      sessionId: echoSession.id,
    })
    expect(Methods['usage.session'].result.parse(managedUsage)).toBeTruthy()
    expect(managedUsage.sessionId).toBe(echoSession.id)
    expect(managedUsage.supported).toBe(true)
    expect(managedUsage.totals.requests).toBeGreaterThanOrEqual(1)
    expect(managedUsage.totals.totalTokens).toBeGreaterThan(0)

    // Unknown session returns NotFound
    await expect(
      client.call('usage.session', { sessionId: 'host:claude:nonexistent' }),
    ).rejects.toMatchObject({ code: ErrorCode.NotFound })

    // 6d. `usage.proxy`
    const proxyUsage = await client.call<ProxyUsageResult>('usage.proxy')
    expect(Methods['usage.proxy'].result.parse(proxyUsage)).toBeTruthy()
    expect(sawAuthHeader).toBe('Bearer test-mgmt-secret')
    expect(proxyUsage.reachable).toBe(true)
    expect(proxyUsage.exposedModels).toBe(3)
    expect(proxyUsage.managementEnabled).toBe(true)
    expect(proxyUsage.totalRequests).toBe(42)
    expect(proxyUsage.successCount).toBe(40)
    expect(proxyUsage.failureCount).toBe(2)
    expect(proxyUsage.totalTokens).toBe(980_000)
    expect(proxyUsage.byModel).toEqual([
      { model: 'claude-sonnet-4-5', requests: 30, totalTokens: 700_000 },
      { model: 'gpt-5.2', requests: 12, totalTokens: 280_000 },
    ])

    client.close()
  })

  it('7. parses real AGY PLANNER_RESPONSE token metrics and skips stale files older than cutoffMs - 24h', () => {
    const homeDir = makeTempDir('lework-agy-tokens-')
    cleanupDirs.push(homeDir)

    const nowMs = Date.parse('2026-11-19T12:00:00.000Z')
    const agyConvDir = path.join(
      homeDir,
      '.gemini',
      'antigravity-cli',
      'brain',
      'conv-with-tokens',
      '.system_generated',
      'logs',
    )
    fs.mkdirSync(agyConvDir, { recursive: true })
    fs.writeFileSync(
      path.join(agyConvDir, 'transcript.jsonl'),
      [
        JSON.stringify({
          step_index: 1,
          type: 'PLANNER_RESPONSE',
          created_at: '2026-11-19T10:53:53Z',
          model: 'gemini-4-carbon-high',
          input_tokens: 16_000,
          cache_read_tokens: 4_000,
          output_tokens: 500,
        }),
        JSON.stringify({
          step_index: 5,
          type: 'PLANNER_RESPONSE',
          created_at: '2026-11-19T10:55:00Z',
          model: 'gemini-4-carbon-high',
          input_tokens: 10_000,
          cache_read_tokens: 20_000,
          output_tokens: 800,
        }),
      ].join('\n') + '\n',
      'utf8',
    )

    // Also create a stale Claude file with mtime 10 days ago -> must be skipped without reading when querying days=1
    const staleDir = path.join(homeDir, '.claude', 'projects', '-stale')
    fs.mkdirSync(staleDir, { recursive: true })
    const staleFile = path.join(staleDir, 'stale.jsonl')
    fs.writeFileSync(
      staleFile,
      JSON.stringify({
        type: 'assistant',
        requestId: 'r_stale',
        timestamp: '2026-11-01T10:00:00Z',
        message: {
          id: 'm_stale',
          role: 'assistant',
          model: 'claude-sonnet-4-5',
          usage: { input_tokens: 99_000, output_tokens: 1_000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
        },
      }) + '\n',
      'utf8',
    )
    const tenDaysAgoSec = (nowMs - 10 * 86_400_000) / 1000
    fs.utimesSync(staleFile, tenDaysAgoSec, tenDaysAgoSec)

    const scanner = new UsageScanner({ homeDir, memoTtlMs: 0, now: () => nowMs })
    const service = new UsageService({
      cpaEndpoint: 'http://127.0.0.1:8317',
      scanner,
      now: () => nowMs,
    })

    const summary = service.getSummary({ days: 1 })
    expect(scanner.getCachedFileState(staleFile)).toBeUndefined()

    const agy = summary.byEngine.find(e => e.engineId === 'agy')!
    expect(agy.supported).toBe(true)
    expect(agy.note).toBeUndefined()
    expect(agy.requests).toBe(2)
    expect(agy.inputTokens).toBe(26_000)
    expect(agy.cacheReadTokens).toBe(24_000)
    expect(agy.outputTokens).toBe(1_300)
    expect(agy.totalTokens).toBe(51_300)

    const agySess = service.getSessionUsage('host:agy:conv-with-tokens')
    expect(agySess.supported).toBe(true)
    expect(agySess.contextWindow).toBe(1_000_000)
    expect(agySess.contextTokens).toBe(30_000) // 10k + 20k
    expect(agySess.contextPercent).toBe(3)
    expect(agySess.cacheHitPercent).toBe(48) // 24k / 50k
  })
})
