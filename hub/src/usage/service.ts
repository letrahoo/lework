import {
  ErrorCode,
  type AgentEvent,
  type DailyUsageEntry,
  type EngineUsageEntry,
  type ModelUsageEntry,
  type ProxyModelUsageEntry,
  type ProxyUsageResult,
  type QuotaWindowInfo,
  type SessionMeta,
  type SessionUsageResult,
  type UsageQuotaResult,
  type UsageSummaryResult,
  type UsageTotals,
} from '@lework/protocol'
import { HubRpcError } from '../security/path-guard.ts'
import {
  calculateTokenCostUsd,
  resolveContextWindow,
  roundUsd,
} from './pricing.ts'
import {
  UsageScanner,
  type CodexRateLimitWindowSnapshot,
  type ScannedEngineId,
  type UsageRecord,
} from './scanners.ts'

const FIVE_HOURS_MS = 5 * 60 * 60 * 1000
const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000
const EIGHT_DAYS_MS = 8 * 24 * 60 * 60 * 1000
const PROXY_TIMEOUT_MS = 2_500

export interface ManagedSessionSnapshot {
  session: SessionMeta
  events: AgentEvent[]
}

export interface UsageServiceOptions {
  cpaEndpoint: string
  cpaManagementKey?: string
  getCpaEndpoint?: () => string
  getCpaApiKey?: () => string | undefined
  homeDir?: string
  claudeConfigDir?: string
  dataDir?: string
  memoTtlMs?: number
  now?: () => number
  scanner?: UsageScanner
  getSessionSnapshot?: (sessionId: string) => ManagedSessionSnapshot | null
  fetchImpl?: typeof fetch
}

interface MutableAccumulator {
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  totalTokens: number
  requests: number
  costUsd: number
  hasPricedRecord: boolean
}

function createAccumulator(): MutableAccumulator {
  return {
    inputTokens: 0,
    outputTokens: 0,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    totalTokens: 0,
    requests: 0,
    costUsd: 0,
    hasPricedRecord: false,
  }
}

function addRecordToAccumulator(
  acc: MutableAccumulator,
  r: UsageRecord,
  recordCost: number | undefined,
): void {
  acc.inputTokens += r.inputTokens
  acc.outputTokens += r.outputTokens
  acc.cacheReadTokens += r.cacheReadTokens
  acc.cacheWriteTokens += r.cacheWriteTokens
  acc.totalTokens += r.totalTokens
  acc.requests += r.requests
  if (recordCost !== undefined) {
    acc.costUsd += recordCost
    acc.hasPricedRecord = true
  }
}

function toUsageTotals(acc: MutableAccumulator): UsageTotals {
  return {
    inputTokens: acc.inputTokens,
    outputTokens: acc.outputTokens,
    cacheReadTokens: acc.cacheReadTokens,
    cacheWriteTokens: acc.cacheWriteTokens,
    totalTokens: acc.totalTokens,
    requests: acc.requests,
    ...(acc.hasPricedRecord || acc.requests === 0
      ? { estimatedCostUsd: acc.requests === 0 ? 0 : roundUsd(acc.costUsd) }
      : {}),
  }
}

export class UsageService {
  private readonly cpaEndpoint: string
  private readonly cpaManagementKey?: string
  private readonly getCpaEndpointFn?: () => string
  private readonly getCpaApiKeyFn?: () => string | undefined
  private readonly dataDir?: string
  private readonly now: () => number
  private readonly scanner: UsageScanner
  private getSessionSnapshotFn?: (sessionId: string) => ManagedSessionSnapshot | null
  private readonly fetchImpl: typeof fetch

  constructor(opts: UsageServiceOptions) {
    this.cpaEndpoint = opts.cpaEndpoint.replace(/\/+$/, '')
    this.cpaManagementKey = opts.cpaManagementKey?.trim() || undefined
    this.getCpaEndpointFn = opts.getCpaEndpoint
    this.getCpaApiKeyFn = opts.getCpaApiKey
    this.dataDir = opts.dataDir
    this.now = opts.now ?? (() => Date.now())
    this.scanner =
      opts.scanner ??
      new UsageScanner({
        homeDir: opts.homeDir,
        claudeConfigDir: opts.claudeConfigDir,
        memoTtlMs: opts.memoTtlMs,
        now: this.now,
      })
    this.getSessionSnapshotFn = opts.getSessionSnapshot
    this.fetchImpl = opts.fetchImpl ?? fetch
  }

  setSessionSnapshotProvider(fn: (sessionId: string) => ManagedSessionSnapshot | null): void {
    this.getSessionSnapshotFn = fn
  }

  getScanner(): UsageScanner {
    return this.scanner
  }

  getSummary(params?: {
    days?: number
    engineId?: string
    bypassMemo?: boolean
  }): UsageSummaryResult {
    const now = this.now()
    const days = Math.min(90, Math.max(1, Math.floor(params?.days ?? 14)))
    const engineFilter = params?.engineId?.trim() || undefined

    const startDayLocal = new Date(now)
    startDayLocal.setHours(0, 0, 0, 0)
    startDayLocal.setDate(startDayLocal.getDate() - (days - 1))
    const cutoffMs = startDayLocal.getTime()

    const snapshot = this.scanner.scanWindow({
      cutoffMs,
      engineId: engineFilter,
      bypassMemo: params?.bypassMemo,
    })

    const maxTs = now + 5 * 60_000
    const filtered = snapshot.records.filter(
      r =>
        r.timestamp >= cutoffMs &&
        r.timestamp <= maxTs &&
        (!engineFilter || r.engineId === engineFilter),
    )

    const totalAcc = createAccumulator()
    const dailyMap = new Map<string, MutableAccumulator>()
    const engineMap = new Map<string, MutableAccumulator>()
    const modelMap = new Map<string, { engineId: string; model: string; acc: MutableAccumulator }>()

    const targetEngines = engineFilter ? [engineFilter] : ['claude', 'codex', 'agy']
    for (const eng of targetEngines) {
      engineMap.set(eng, createAccumulator())
    }

    let agyWindowHasTokens = false
    for (const r of filtered) {
      if (r.engineId === 'agy' && r.hasTokenMetrics) {
        agyWindowHasTokens = true
      }
      const cost =
        r.totalTokens > 0
          ? calculateTokenCostUsd(
              r.model,
              {
                inputTokens: r.inputTokens,
                outputTokens: r.outputTokens,
                cacheWriteTokens: r.cacheWriteTokens,
                cacheReadTokens: r.cacheReadTokens,
              },
              this.dataDir,
            )
          : undefined

      addRecordToAccumulator(totalAcc, r, cost)

      const dateKey = formatLocalDate(r.timestamp)
      let dayAcc = dailyMap.get(dateKey)
      if (!dayAcc) {
        dayAcc = createAccumulator()
        dailyMap.set(dateKey, dayAcc)
      }
      addRecordToAccumulator(dayAcc, r, cost)

      let engAcc = engineMap.get(r.engineId)
      if (!engAcc) {
        engAcc = createAccumulator()
        engineMap.set(r.engineId, engAcc)
      }
      addRecordToAccumulator(engAcc, r, cost)

      if (r.totalTokens > 0 || r.model) {
        const modelLabel = r.model || 'unknown'
        const modelKey = `${r.engineId}\u0000${modelLabel}`
        let modelEntry = modelMap.get(modelKey)
        if (!modelEntry) {
          modelEntry = { engineId: r.engineId, model: modelLabel, acc: createAccumulator() }
          modelMap.set(modelKey, modelEntry)
        }
        addRecordToAccumulator(modelEntry.acc, r, cost)
      }
    }

    const daily: DailyUsageEntry[] = [...dailyMap.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([date, acc]) => ({
        date,
        inputTokens: acc.inputTokens,
        outputTokens: acc.outputTokens,
        cacheReadTokens: acc.cacheReadTokens,
        cacheWriteTokens: acc.cacheWriteTokens,
        totalTokens: acc.totalTokens,
        requests: acc.requests,
        ...(acc.hasPricedRecord ? { estimatedCostUsd: roundUsd(acc.costUsd) } : {}),
      }))

    const byEngine: EngineUsageEntry[] = [...engineMap.entries()]
      .map(([engineId, acc]) => {
        let supported = engineId === 'claude' || engineId === 'codex' || engineId === 'agy'
        let note: string | undefined

        if (engineId === 'agy') {
          const lacksTokenDetails =
            !agyWindowHasTokens &&
            !snapshot.agyHasTokenMetrics &&
            (acc.requests > 0 || snapshot.agySawFiles) &&
            acc.totalTokens === 0
          if (lacksTokenDetails) {
            supported = false
            note = 'AGY 本地日志未记录 Token 明细（仅统计交互轮次）'
          }
        } else if (!supported) {
          note = '该引擎本地日志暂不支持 Token 明细解析'
        }

        return {
          engineId,
          supported,
          inputTokens: acc.inputTokens,
          outputTokens: acc.outputTokens,
          cacheReadTokens: acc.cacheReadTokens,
          cacheWriteTokens: acc.cacheWriteTokens,
          totalTokens: acc.totalTokens,
          requests: acc.requests,
          ...(acc.hasPricedRecord
            ? { estimatedCostUsd: roundUsd(acc.costUsd) }
            : supported && acc.requests === 0
              ? { estimatedCostUsd: 0 }
              : {}),
          ...(note ? { note } : {}),
        }
      })
      .sort((a, b) => {
        if (b.totalTokens !== a.totalTokens) return b.totalTokens - a.totalTokens
        return b.requests - a.requests
      })

    const byModel: ModelUsageEntry[] = [...modelMap.values()]
      .map(({ engineId, model, acc }) => ({
        model,
        engineId,
        inputTokens: acc.inputTokens,
        outputTokens: acc.outputTokens,
        cacheReadTokens: acc.cacheReadTokens,
        cacheWriteTokens: acc.cacheWriteTokens,
        totalTokens: acc.totalTokens,
        requests: acc.requests,
        ...(acc.hasPricedRecord ? { estimatedCostUsd: roundUsd(acc.costUsd) } : {}),
      }))
      .sort((a, b) => {
        if (b.totalTokens !== a.totalTokens) return b.totalTokens - a.totalTokens
        if (b.requests !== a.requests) return b.requests - a.requests
        return a.model.localeCompare(b.model)
      })

    return {
      days,
      generatedAt: now,
      totals: toUsageTotals(totalAcc),
      daily,
      byEngine,
      byModel,
    }
  }

  getQuota(opts?: { bypassMemo?: boolean }): UsageQuotaResult {
    const now = this.now()
    const cutoffMs = now - EIGHT_DAYS_MS
    const maxTs = now + 5 * 60_000
    const snapshot = this.scanner.scanWindow({ cutoffMs, bypassMemo: opts?.bypassMemo })

    const claudeRecords = snapshot.records
      .filter(r => r.engineId === 'claude' && r.timestamp <= maxTs)
      .sort((a, b) => a.timestamp - b.timestamp)

    const codexRecords = snapshot.records.filter(r => r.engineId === 'codex' && r.timestamp <= maxTs)

    // 1. Claude 5h billing block (`ccusage blocks` style)
    const claude5h = this.buildClaudeFiveHourWindow(claudeRecords, now)

    // 2. Claude 7d rolling window
    const sevenDaysCutoff = now - SEVEN_DAYS_MS
    const claude7dAcc = this.accumulateRecords(
      claudeRecords.filter(r => r.timestamp >= sevenDaysCutoff),
    )
    const claude7d: QuotaWindowInfo = {
      id: 'claude:7d',
      engineId: 'claude',
      windowKind: 'weekly',
      label: 'Claude · 近 7 天累计',
      source: 'estimated',
      active: claude7dAcc.totalTokens > 0,
      usedTokens: claude7dAcc.totalTokens,
      estimatedCostUsd: claude7dAcc.hasPricedRecord ? roundUsd(claude7dAcc.costUsd) : 0,
      windowMinutes: 10_080,
      ...(claude7dAcc.totalTokens > 0 ? { startedAt: sevenDaysCutoff } : {}),
    }

    // 3. Codex 5h & 7d windows (reported `rate_limits` + local rollout token totals)
    const codex5hCutoff = now - FIVE_HOURS_MS
    const codex5hAcc = this.accumulateRecords(codexRecords.filter(r => r.timestamp >= codex5hCutoff))
    const codex7dAcc = this.accumulateRecords(
      codexRecords.filter(r => r.timestamp >= sevenDaysCutoff),
    )

    const rl = snapshot.latestCodexRateLimits
    let reported5h: CodexRateLimitWindowSnapshot | undefined
    let reported7d: CodexRateLimitWindowSnapshot | undefined
    if (rl) {
      if (rl.primary && !rl.secondary && rl.primary.windowMinutes >= 7_200) {
        // Plans like `prolite` expose a single weekly primary window (10080 min)
        reported7d = rl.primary
      } else {
        reported5h = rl.primary
        reported7d = rl.secondary
      }
    }

    const codex5h = this.buildCodexQuotaWindow({
      id: 'codex:5h',
      windowKind: 'five_hour',
      label: 'Codex · 5 小时额度',
      defaultWindowMinutes: 300,
      reported: reported5h,
      acc: codex5hAcc,
      now,
      includeBurnRate: true,
    })

    const codex7d = this.buildCodexQuotaWindow({
      id: 'codex:7d',
      windowKind: 'weekly',
      label: 'Codex · 周额度',
      defaultWindowMinutes: 10_080,
      reported: reported7d,
      acc: codex7dAcc,
      now,
      includeBurnRate: false,
    })

    return {
      generatedAt: now,
      windows: [claude5h, claude7d, codex5h, codex7d],
    }
  }

  getSessionUsage(sessionId: string): SessionUsageResult {
    const trimmedId = sessionId.trim()
    if (!trimmedId) {
      throw new HubRpcError(ErrorCode.InvalidParams, 'sessionId is required')
    }

    const hostMatch = trimmedId.match(/^host:([^:]+):(.+)$/)
    if (hostMatch) {
      const engineId = hostMatch[1]!
      const nativeId = hostMatch[2]!

      if (engineId === 'claude' || engineId === 'codex' || engineId === 'agy') {
        const fileEntry = this.scanner.scanHostSession(engineId as ScannedEngineId, nativeId)
        const managedSnap = this.getSessionSnapshotFn?.(trimmedId) ?? null

        if (fileEntry) {
          const acc = this.accumulateRecords(fileEntry.records)
          const last = fileEntry.records[fileEntry.records.length - 1]
          const model = fileEntry.currentModel ?? last?.model ?? managedSnap?.session.model
          const contextWindow = resolveContextWindow(
            engineId,
            model,
            engineId === 'codex'
              ? (fileEntry.latestContextWindow ?? last?.reportedContextWindow)
              : undefined,
          )

          if (
            engineId === 'agy' &&
            !fileEntry.anyHasTokenMetrics &&
            fileEntry.records.length > 0 &&
            acc.totalTokens === 0
          ) {
            return {
              sessionId: trimmedId,
              engineId: 'agy',
              ...(model ? { model } : {}),
              supported: false,
              contextTokens: 0,
              contextWindow,
              contextPercent: 0,
              lastInputTokens: 0,
              lastOutputTokens: 0,
              totals: toUsageTotals(acc),
              note: 'AGY 本地日志未记录 Token 明细（仅统计交互轮次）',
            }
          }

          let contextTokens = 0
          if (engineId === 'claude' || engineId === 'agy') {
            contextTokens = last
              ? last.inputTokens + last.cacheReadTokens + last.cacheWriteTokens
              : 0
          } else if (engineId === 'codex') {
            const rawCtx = fileEntry.latestContextTokens ?? (last ? last.promptContextTokens : 0)
            contextTokens = Math.min(contextWindow, Math.max(0, rawCtx))
          }

          const contextPercent = round1(
            contextWindow > 0 ? Math.min(100, Math.max(0, (contextTokens / contextWindow) * 100)) : 0,
          )
          const promptSum = acc.inputTokens + acc.cacheReadTokens + acc.cacheWriteTokens
          const cacheHitPercent =
            promptSum > 0 ? round1(Math.min(100, (acc.cacheReadTokens / promptSum) * 100)) : undefined

          return {
            sessionId: trimmedId,
            engineId,
            ...(model ? { model } : {}),
            supported: true,
            contextTokens,
            contextWindow,
            contextPercent,
            ...(cacheHitPercent !== undefined ? { cacheHitPercent } : {}),
            lastInputTokens: last?.inputTokens ?? 0,
            lastOutputTokens: last?.outputTokens ?? 0,
            totals: toUsageTotals(acc),
          }
        }

        if (managedSnap) {
          return this.buildManagedSessionUsage(trimmedId, managedSnap)
        }
        throw new HubRpcError(ErrorCode.NotFound, `Session "${trimmedId}" not found`)
      }

      // Other host engines (e.g. `host:dsh:...`)
      const managedSnap = this.getSessionSnapshotFn?.(trimmedId) ?? null
      if (!managedSnap) {
        throw new HubRpcError(ErrorCode.NotFound, `Session "${trimmedId}" not found`)
      }
      return {
        sessionId: trimmedId,
        engineId,
        ...(managedSnap.session.model ? { model: managedSnap.session.model } : {}),
        supported: false,
        contextTokens: 0,
        contextWindow: resolveContextWindow(engineId, managedSnap.session.model),
        contextPercent: 0,
        lastInputTokens: 0,
        lastOutputTokens: 0,
        totals: {
          inputTokens: 0,
          outputTokens: 0,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
          totalTokens: 0,
          requests: 0,
        },
        note: '该引擎本地日志暂不支持 Token 明细解析',
      }
    }

    const managedSnap = this.getSessionSnapshotFn?.(trimmedId) ?? null
    if (!managedSnap) {
      throw new HubRpcError(ErrorCode.NotFound, `Session "${trimmedId}" not found`)
    }
    return this.buildManagedSessionUsage(trimmedId, managedSnap)
  }

  async getProxyUsage(): Promise<ProxyUsageResult> {
    const endpoint = (this.getCpaEndpointFn?.() ?? this.cpaEndpoint).replace(/\/+$/, '')
    const managementKey = this.cpaManagementKey
    const managementEnabled = Boolean(managementKey)

    let reachable = false
    let exposedModels = 0
    let probeNote: string | undefined

    // 1. Probe `${cpaEndpoint}/v1/models` (2.5s timeout)
    try {
      const headers: Record<string, string> = {}
      const apiKey = this.getCpaApiKeyFn?.()?.trim() || process.env.LEWORK_CPA_KEY?.trim()
      if (apiKey) {
        headers.Authorization = `Bearer ${apiKey}`
      }
      const res = await this.fetchWithTimeout(`${endpoint}/v1/models`, { headers })
      if (res.ok) {
        reachable = true
        const body = (await res.json()) as { data?: unknown }
        if (Array.isArray(body?.data)) {
          exposedModels = body.data.length
        }
      } else if (res.status === 401 || res.status === 403) {
        reachable = true
        probeNote = '代理服务在线（模型列表需要访问密钥）'
      } else {
        probeNote = `代理模型探测返回 HTTP ${res.status}`
      }
    } catch {
      return {
        endpoint,
        reachable: false,
        exposedModels: 0,
        managementEnabled,
        byModel: [],
        note: '本地代理未启动或无法连接',
      }
    }

    if (!reachable) {
      return {
        endpoint,
        reachable: false,
        exposedModels: 0,
        managementEnabled,
        byModel: [],
        note: probeNote ?? '本地代理不可用',
      }
    }

    if (!managementKey) {
      return {
        endpoint,
        reachable: true,
        exposedModels,
        managementEnabled: false,
        byModel: [],
        note: probeNote ?? '未配置 LEWORK_CPA_MANAGEMENT_KEY，仅展示模型连通状态',
      }
    }

    // 2. Query `${cpaEndpoint}/v0/management/usage` with Bearer management key (2.5s timeout)
    try {
      const mgmtRes = await this.fetchWithTimeout(`${endpoint}/v0/management/usage`, {
        headers: {
          Authorization: `Bearer ${managementKey}`,
        },
      })
      if (!mgmtRes.ok) {
        const note =
          mgmtRes.status === 401 || mgmtRes.status === 403
            ? '代理管理密钥无效或无权限访问用量接口'
            : mgmtRes.status === 404
              ? '当前代理未开启 Management Usage 统计接口'
              : `代理用量查询失败 (HTTP ${mgmtRes.status})`
        return {
          endpoint,
          reachable: true,
          exposedModels,
          managementEnabled: true,
          byModel: [],
          note,
        }
      }

      const raw = (await mgmtRes.json()) as unknown
      const parsed = parseCpaManagementUsage(raw)
      return {
        endpoint,
        reachable: true,
        exposedModels,
        managementEnabled: true,
        ...(parsed.totalRequests !== undefined ? { totalRequests: parsed.totalRequests } : {}),
        ...(parsed.successCount !== undefined ? { successCount: parsed.successCount } : {}),
        ...(parsed.failureCount !== undefined ? { failureCount: parsed.failureCount } : {}),
        ...(parsed.totalTokens !== undefined ? { totalTokens: parsed.totalTokens } : {}),
        byModel: parsed.byModel,
        ...(probeNote ? { note: probeNote } : {}),
      }
    } catch {
      return {
        endpoint,
        reachable: true,
        exposedModels,
        managementEnabled: true,
        byModel: [],
        note: '查询代理统计超时或解析失败',
      }
    }
  }

  private buildManagedSessionUsage(
    sessionId: string,
    snap: ManagedSessionSnapshot,
  ): SessionUsageResult {
    const engineId = snap.session.engineId
    const model = snap.session.model
    const contextWindow = resolveContextWindow(engineId, model)
    const usageEvents = snap.events.filter(
      (ev): ev is Extract<AgentEvent, { type: 'usage' }> => ev.type === 'usage',
    )

    let inputTokens = 0
    let outputTokens = 0
    for (const u of usageEvents) {
      inputTokens += Math.max(0, Math.floor(u.inputTokens ?? 0))
      outputTokens += Math.max(0, Math.floor(u.outputTokens ?? 0))
    }
    const totalTokens = inputTokens + outputTokens
    const requests = usageEvents.length
    const lastU = usageEvents[usageEvents.length - 1]
    const lastInputTokens = Math.max(0, Math.floor(lastU?.inputTokens ?? 0))
    const lastOutputTokens = Math.max(0, Math.floor(lastU?.outputTokens ?? 0))

    const contextTokens = Math.min(
      contextWindow,
      lastInputTokens > 0 ? lastInputTokens : inputTokens,
    )
    const contextPercent = round1(
      contextWindow > 0 ? Math.min(100, Math.max(0, (contextTokens / contextWindow) * 100)) : 0,
    )

    const cost =
      totalTokens > 0
        ? calculateTokenCostUsd(model, { inputTokens, outputTokens }, this.dataDir)
        : requests === 0
          ? 0
          : undefined

    const hasCompletedWithoutUsage =
      usageEvents.length === 0 && snap.events.some(e => e.type === 'turn.done')
    const supported = !hasCompletedWithoutUsage && engineId !== 'dsh'

    return {
      sessionId,
      engineId,
      ...(model ? { model } : {}),
      supported,
      contextTokens,
      contextWindow,
      contextPercent,
      lastInputTokens,
      lastOutputTokens,
      totals: {
        inputTokens,
        outputTokens,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        totalTokens,
        requests,
        ...(cost !== undefined ? { estimatedCostUsd: roundUsd(cost) } : {}),
      },
      ...(hasCompletedWithoutUsage ? { note: '当前会话引擎未上报实时 Token 明细' } : {}),
    }
  }

  private buildClaudeFiveHourWindow(sortedRecords: UsageRecord[], now: number): QuotaWindowInfo {
    interface BillingBlock {
      startMs: number
      endMs: number
      records: UsageRecord[]
    }

    const blocks: BillingBlock[] = []
    let current: BillingBlock | null = null

    for (const r of sortedRecords) {
      if (!current || r.timestamp >= current.endMs) {
        const floored = floorToHourMs(r.timestamp)
        current = {
          startMs: floored,
          endMs: floored + FIVE_HOURS_MS,
          records: [r],
        }
        blocks.push(current)
      } else {
        current.records.push(r)
      }
    }

    const latest = blocks[blocks.length - 1]
    const isActive = Boolean(latest && latest.startMs <= now && now < latest.endMs)

    if (!isActive || !latest) {
      return {
        id: 'claude:5h',
        engineId: 'claude',
        windowKind: 'five_hour',
        label: 'Claude · 5 小时窗口',
        source: 'estimated',
        active: false,
        usedTokens: 0,
        estimatedCostUsd: 0,
        windowMinutes: 300,
      }
    }

    const acc = this.accumulateRecords(latest.records)
    const elapsedMinutes = Math.max(1, (now - latest.startMs) / 60_000)
    const remainingMinutes = Math.max(0, (latest.endMs - now) / 60_000)
    const rawBurnRate = acc.totalTokens / elapsedMinutes
    const burnRateTokensPerMin = round1(rawBurnRate)
    const projectedTokens = Math.round(acc.totalTokens + rawBurnRate * remainingMinutes)

    return {
      id: 'claude:5h',
      engineId: 'claude',
      windowKind: 'five_hour',
      label: 'Claude · 5 小时窗口',
      source: 'estimated',
      active: true,
      usedTokens: acc.totalTokens,
      ...(acc.hasPricedRecord ? { estimatedCostUsd: roundUsd(acc.costUsd) } : {}),
      burnRateTokensPerMin,
      projectedTokens,
      windowMinutes: 300,
      startedAt: latest.startMs,
      resetsAt: latest.endMs,
    }
  }

  private buildCodexQuotaWindow(opts: {
    id: string
    windowKind: 'five_hour' | 'weekly'
    label: string
    defaultWindowMinutes: number
    reported?: CodexRateLimitWindowSnapshot
    acc: MutableAccumulator
    now: number
    includeBurnRate: boolean
  }): QuotaWindowInfo {
    const { id, windowKind, label, defaultWindowMinutes, reported, acc, now, includeBurnRate } = opts

    if (reported) {
      const expired = reported.resetsAt !== undefined && reported.resetsAt < now
      const usedPercent = expired ? 0 : round1(reported.usedPercent)
      const active = expired
        ? false
        : reported.resetsAt !== undefined || usedPercent > 0 || acc.totalTokens > 0
      const windowMinutes = reported.windowMinutes || defaultWindowMinutes
      const startedAt =
        !expired && reported.resetsAt !== undefined
          ? reported.resetsAt - windowMinutes * 60_000
          : undefined

      let burnRateTokensPerMin: number | undefined
      let projectedTokens: number | undefined
      if (
        includeBurnRate &&
        active &&
        acc.totalTokens > 0 &&
        startedAt !== undefined &&
        reported.resetsAt !== undefined &&
        reported.resetsAt > now
      ) {
        const elapsedMinutes = Math.max(1, (now - startedAt) / 60_000)
        const remainingMinutes = Math.max(0, (reported.resetsAt - now) / 60_000)
        const rawRate = acc.totalTokens / elapsedMinutes
        burnRateTokensPerMin = round1(rawRate)
        projectedTokens = Math.round(acc.totalTokens + rawRate * remainingMinutes)
      }

      return {
        id,
        engineId: 'codex',
        windowKind,
        label,
        source: 'reported',
        active,
        usedPercent,
        usedTokens: acc.totalTokens,
        ...(acc.hasPricedRecord ? { estimatedCostUsd: roundUsd(acc.costUsd) } : { estimatedCostUsd: 0 }),
        ...(burnRateTokensPerMin !== undefined ? { burnRateTokensPerMin } : {}),
        ...(projectedTokens !== undefined ? { projectedTokens } : {}),
        windowMinutes,
        ...(startedAt !== undefined ? { startedAt } : {}),
        ...(reported.resetsAt !== undefined ? { resetsAt: reported.resetsAt } : {}),
      }
    }

    return {
      id,
      engineId: 'codex',
      windowKind,
      label,
      source: 'estimated',
      active: acc.totalTokens > 0,
      usedTokens: acc.totalTokens,
      estimatedCostUsd: acc.hasPricedRecord ? roundUsd(acc.costUsd) : 0,
      windowMinutes: defaultWindowMinutes,
      ...(acc.totalTokens > 0 ? { startedAt: now - defaultWindowMinutes * 60_000 } : {}),
    }
  }

  private accumulateRecords(records: UsageRecord[]): MutableAccumulator {
    const acc = createAccumulator()
    for (const r of records) {
      const cost =
        r.totalTokens > 0
          ? calculateTokenCostUsd(
              r.model,
              {
                inputTokens: r.inputTokens,
                outputTokens: r.outputTokens,
                cacheWriteTokens: r.cacheWriteTokens,
                cacheReadTokens: r.cacheReadTokens,
              },
              this.dataDir,
            )
          : undefined
      addRecordToAccumulator(acc, r, cost)
    }
    return acc
  }

  private async fetchWithTimeout(url: string, init: RequestInit): Promise<Response> {
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), PROXY_TIMEOUT_MS)
    timer.unref?.()
    try {
      return await this.fetchImpl(url, { ...init, signal: ctl.signal })
    } finally {
      clearTimeout(timer)
    }
  }
}

function floorToHourMs(tsMs: number): number {
  const d = new Date(tsMs)
  d.setMinutes(0, 0, 0)
  return d.getTime()
}

function formatLocalDate(tsMs: number): string {
  const d = new Date(tsMs)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function round1(val: number): number {
  if (!Number.isFinite(val)) return 0
  return Math.round(val * 10) / 10
}

function optNonNegInt(val: unknown): number | undefined {
  if (typeof val !== 'number' || !Number.isFinite(val) || val < 0) return undefined
  return Math.floor(val)
}

function parseCpaManagementUsage(raw: unknown): {
  totalRequests?: number
  successCount?: number
  failureCount?: number
  totalTokens?: number
  byModel: ProxyModelUsageEntry[]
} {
  if (!raw || typeof raw !== 'object') {
    return { byModel: [] }
  }
  const root = raw as Record<string, unknown>
  const usage =
    root.usage && typeof root.usage === 'object' && !Array.isArray(root.usage)
      ? (root.usage as Record<string, unknown>)
      : root

  let totalRequests = optNonNegInt(usage.total_requests ?? usage.totalRequests)
  const successCount = optNonNegInt(usage.success_count ?? usage.successCount)
  const failureCount = optNonNegInt(usage.failure_count ?? usage.failureCount)
  let totalTokens = optNonNegInt(usage.total_tokens ?? usage.totalTokens)

  const modelStats = new Map<string, { requests: number; totalTokens: number }>()
  const addModelStat = (modelName: string, reqs: unknown, toks: unknown) => {
    const clean = modelName.trim()
    if (!clean) return
    const prev = modelStats.get(clean) ?? { requests: 0, totalTokens: 0 }
    prev.requests += optNonNegInt(reqs) ?? 0
    prev.totalTokens += optNonNegInt(toks) ?? 0
    modelStats.set(clean, prev)
  }

  // Primary CLIProxyAPI shape: `usage.apis[apiKeyOrRoute].models[modelName]`
  // Also handles flat `usage.apis[modelName] = { total_requests, total_tokens }`.
  if (usage.apis && typeof usage.apis === 'object' && !Array.isArray(usage.apis)) {
    for (const [key, val] of Object.entries(usage.apis as Record<string, unknown>)) {
      if (!val || typeof val !== 'object' || Array.isArray(val)) continue
      const apiObj = val as Record<string, unknown>
      if (apiObj.models && typeof apiObj.models === 'object' && !Array.isArray(apiObj.models)) {
        for (const [mName, mVal] of Object.entries(apiObj.models as Record<string, unknown>)) {
          if (!mVal || typeof mVal !== 'object') continue
          const mObj = mVal as Record<string, unknown>
          addModelStat(
            mName,
            mObj.total_requests ?? mObj.requests,
            mObj.total_tokens ?? mObj.tokens,
          )
        }
      } else if (
        'total_requests' in apiObj ||
        'requests' in apiObj ||
        'total_tokens' in apiObj ||
        'tokens' in apiObj
      ) {
        addModelStat(
          key,
          apiObj.total_requests ?? apiObj.requests,
          apiObj.total_tokens ?? apiObj.tokens,
        )
      }
    }
  }

  // Secondary fallback: `usage.models` object or array
  if (modelStats.size === 0 && usage.models && typeof usage.models === 'object') {
    if (Array.isArray(usage.models)) {
      for (const item of usage.models) {
        if (!item || typeof item !== 'object') continue
        const obj = item as Record<string, unknown>
        if (typeof obj.model === 'string') {
          addModelStat(
            obj.model,
            obj.total_requests ?? obj.requests,
            obj.total_tokens ?? obj.tokens,
          )
        }
      }
    } else {
      for (const [mName, mVal] of Object.entries(usage.models as Record<string, unknown>)) {
        if (!mVal || typeof mVal !== 'object') continue
        const mObj = mVal as Record<string, unknown>
        addModelStat(
          mName,
          mObj.total_requests ?? mObj.requests,
          mObj.total_tokens ?? mObj.tokens,
        )
      }
    }
  }

  const byModel: ProxyModelUsageEntry[] = [...modelStats.entries()]
    .map(([model, stats]) => ({
      model,
      requests: stats.requests,
      totalTokens: stats.totalTokens,
    }))
    .sort((a, b) => {
      if (b.totalTokens !== a.totalTokens) return b.totalTokens - a.totalTokens
      if (b.requests !== a.requests) return b.requests - a.requests
      return a.model.localeCompare(b.model)
    })

  if (totalRequests === undefined && byModel.length > 0) {
    totalRequests = byModel.reduce((s, m) => s + m.requests, 0)
  }
  if (totalTokens === undefined && byModel.length > 0) {
    totalTokens = byModel.reduce((s, m) => s + m.totalTokens, 0)
  }

  return {
    totalRequests,
    successCount,
    failureCount,
    totalTokens,
    byModel,
  }
}
