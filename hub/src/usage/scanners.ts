import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

export type ScannedEngineId = 'claude' | 'codex' | 'agy'

export interface UsageRecord {
  engineId: ScannedEngineId
  sessionId: string
  nativeId: string
  filePath: string
  isSubagent?: boolean
  timestamp: number
  model?: string
  cwd?: string
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  totalTokens: number
  requests: number
  promptContextTokens: number
  reportedContextWindow?: number
  hasTokenMetrics: boolean
}

export interface CodexRateLimitWindowSnapshot {
  usedPercent: number
  windowMinutes: number
  resetsAt?: number
}

export interface CodexRateLimitSnapshot {
  timestamp: number
  primary?: CodexRateLimitWindowSnapshot
  secondary?: CodexRateLimitWindowSnapshot
  planType?: string
}

interface CumulativeTokenState {
  input: number
  cached: number
  cacheWrite: number
  output: number
  total: number
}

export interface FileScanCacheEntry {
  filePath: string
  engineId: ScannedEngineId
  nativeId: string
  altIds: Set<string>
  isSubagent: boolean
  size: number
  mtimeMs: number
  byteOffset: number
  records: UsageRecord[]
  // Engine-specific incremental parser state:
  dedupeIndex: Map<string, number>
  currentModel?: string
  currentCwd?: string
  prevTotalUsage?: CumulativeTokenState
  latestRateLimits?: CodexRateLimitSnapshot
  latestContextTokens?: number
  latestContextWindow?: number
  anyHasTokenMetrics: boolean
}

export interface ScanSnapshot {
  records: UsageRecord[]
  latestCodexRateLimits?: CodexRateLimitSnapshot
  agySawFiles: boolean
  agyHasTokenMetrics: boolean
}

export interface UsageScannerOptions {
  homeDir?: string
  claudeConfigDir?: string
  /** TTL in ms for directory-walk scan memoization (default 10_000; set 0 in tests to disable). */
  memoTtlMs?: number
  now?: () => number
}

const TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000

interface ScanMemo {
  scannedAt: number
  minMtimeMs: number
  engineFilter: string | null
  snapshot: ScanSnapshot
}

export class UsageScanner {
  private readonly homeDir: string
  private readonly claudeConfigDir?: string
  private readonly memoTtlMs: number
  private readonly now: () => number
  private readonly fileCache = new Map<string, FileScanCacheEntry>()
  private lastMemo: ScanMemo | null = null
  /** Diagnostic counter of raw bytes read from disk across all incremental scans. */
  public totalBytesRead = 0

  constructor(opts: UsageScannerOptions = {}) {
    this.homeDir = opts.homeDir ?? os.homedir()
    this.claudeConfigDir =
      opts.claudeConfigDir ?? (opts.homeDir ? undefined : process.env.CLAUDE_CONFIG_DIR)
    this.memoTtlMs = opts.memoTtlMs ?? 10_000
    this.now = opts.now ?? (() => Date.now())
  }

  invalidateMemo(): void {
    this.lastMemo = null
  }

  getCachedFileState(filePath: string): Readonly<FileScanCacheEntry> | undefined {
    return this.fileCache.get(filePath)
  }

  /**
   * Scans local logs lazily for a given cutoff timestamp (`cutoffMs`), skipping files
   * whose `mtimeMs < cutoffMs - 24h`, and reading only newly appended bytes per file.
   */
  scanWindow(opts: {
    cutoffMs: number
    engineId?: string
    bypassMemo?: boolean
  }): ScanSnapshot {
    const now = this.now()
    const minMtimeMs = Math.max(0, opts.cutoffMs - TWENTY_FOUR_HOURS_MS)
    const engineFilter = opts.engineId?.trim() || null

    if (
      !opts.bypassMemo &&
      this.memoTtlMs > 0 &&
      this.lastMemo &&
      now - this.lastMemo.scannedAt < this.memoTtlMs &&
      this.lastMemo.minMtimeMs <= minMtimeMs &&
      (this.lastMemo.engineFilter === null || this.lastMemo.engineFilter === engineFilter)
    ) {
      return this.lastMemo.snapshot
    }

    const records: UsageRecord[] = []
    let latestCodexRateLimits: CodexRateLimitSnapshot | undefined
    let agySawFiles = false
    let agyHasTokenMetrics = false

    // 1. Claude Code
    if (!engineFilter || engineFilter === 'claude') {
      for (const root of this.getClaudeProjectsDirs()) {
        const files = collectJsonlFiles(root, minMtimeMs)
        for (const f of files) {
          const entry = this.scanFileIncremental(f.filePath, 'claude', f.stat)
          for (const r of entry.records) {
            records.push(r)
          }
        }
      }
    }

    // 2. OpenAI Codex
    if (!engineFilter || engineFilter === 'codex') {
      const codexDir = path.join(this.homeDir, '.codex', 'sessions')
      const files = collectJsonlFiles(codexDir, minMtimeMs)
      for (const f of files) {
        const entry = this.scanFileIncremental(f.filePath, 'codex', f.stat)
        for (const r of entry.records) {
          records.push(r)
        }
        if (
          entry.latestRateLimits &&
          (!latestCodexRateLimits || entry.latestRateLimits.timestamp >= latestCodexRateLimits.timestamp)
        ) {
          latestCodexRateLimits = entry.latestRateLimits
        }
      }
    }

    // 3. Antigravity (AGY)
    if (!engineFilter || engineFilter === 'agy') {
      const agyFiles = this.collectAgyTranscriptFiles(minMtimeMs)
      if (agyFiles.length > 0) agySawFiles = true
      for (const f of agyFiles) {
        const entry = this.scanFileIncremental(f.filePath, 'agy', f.stat)
        if (entry.anyHasTokenMetrics) agyHasTokenMetrics = true
        for (const r of entry.records) {
          records.push(r)
        }
      }
    }

    const snapshot: ScanSnapshot = {
      records,
      latestCodexRateLimits,
      agySawFiles,
      agyHasTokenMetrics,
    }

    this.lastMemo = {
      scannedAt: now,
      minMtimeMs,
      engineFilter,
      snapshot,
    }
    return snapshot
  }

  /**
   * Scans a specific host session (`host:<engineId>:<nativeId>`) directly, bypassing the
   * 10s directory-walk memo while still utilizing the incremental byte-offset cache.
   */
  scanHostSession(engineId: ScannedEngineId, nativeId: string): FileScanCacheEntry | null {
    const located = this.findSessionFile(engineId, nativeId)
    if (!located) return null
    return this.scanFileIncremental(located.filePath, engineId, located.stat)
  }

  private getClaudeProjectsDirs(): string[] {
    const out: string[] = []
    const seen = new Set<string>()
    const addCandidate = (dir: string) => {
      const normalized = path.resolve(dir)
      if (seen.has(normalized)) return
      try {
        const st = fs.statSync(normalized)
        if (st.isDirectory()) {
          seen.add(normalized)
          out.push(normalized)
        }
      } catch {
        // ignore missing directory
      }
    }

    addCandidate(path.join(this.homeDir, '.claude', 'projects'))
    if (this.claudeConfigDir) {
      for (const part of this.claudeConfigDir.split(',')) {
        const trimmed = part.trim()
        if (!trimmed) continue
        if (path.basename(trimmed) === 'projects') {
          addCandidate(trimmed)
        } else {
          addCandidate(path.join(trimmed, 'projects'))
        }
      }
    }
    return out
  }

  private collectAgyTranscriptFiles(
    minMtimeMs: number,
  ): Array<{ filePath: string; stat: fs.Stats }> {
    const brainDir = path.join(this.homeDir, '.gemini', 'antigravity-cli', 'brain')
    let dirs: fs.Dirent[]
    try {
      dirs = fs.readdirSync(brainDir, { withFileTypes: true })
    } catch {
      return []
    }
    const out: Array<{ filePath: string; stat: fs.Stats }> = []
    for (const d of dirs) {
      if (!d.isDirectory()) continue
      const tp = path.join(brainDir, d.name, '.system_generated', 'logs', 'transcript.jsonl')
      try {
        const st = fs.statSync(tp)
        if (st.isFile() && st.mtimeMs >= minMtimeMs) {
          out.push({ filePath: tp, stat: st })
        }
      } catch {
        // ignore missing transcript
      }
    }
    return out
  }

  private findSessionFile(
    engineId: ScannedEngineId,
    nativeId: string,
  ): { filePath: string; stat: fs.Stats } | null {
    // Fast path: check already-cached files first
    for (const entry of this.fileCache.values()) {
      if (
        entry.engineId === engineId &&
        !entry.isSubagent &&
        (entry.nativeId === nativeId || entry.altIds.has(nativeId))
      ) {
        try {
          const st = fs.statSync(entry.filePath)
          if (st.isFile()) return { filePath: entry.filePath, stat: st }
        } catch {
          this.fileCache.delete(entry.filePath)
        }
      }
    }

    if (engineId === 'agy') {
      const tp = path.join(
        this.homeDir,
        '.gemini',
        'antigravity-cli',
        'brain',
        nativeId,
        '.system_generated',
        'logs',
        'transcript.jsonl',
      )
      try {
        const st = fs.statSync(tp)
        if (st.isFile()) return { filePath: tp, stat: st }
      } catch {
        return null
      }
      return null
    }

    if (engineId === 'claude') {
      for (const projectsDir of this.getClaudeProjectsDirs()) {
        let projectSlugs: fs.Dirent[]
        try {
          projectSlugs = fs.readdirSync(projectsDir, { withFileTypes: true })
        } catch {
          continue
        }
        for (const slug of projectSlugs) {
          if (!slug.isDirectory()) continue
          const candidate = path.join(projectsDir, slug.name, `${nativeId}.jsonl`)
          try {
            const st = fs.statSync(candidate)
            if (st.isFile()) return { filePath: candidate, stat: st }
          } catch {
            // continue searching
          }
        }
        // Fallback: check nested jsonl files whose JSON `sessionId` matches `nativeId`
        const allFiles = collectJsonlFiles(projectsDir, 0)
        for (const f of allFiles) {
          if (isClaudeSubagentPath(f.filePath)) continue
          const entry = this.scanFileIncremental(f.filePath, 'claude', f.stat)
          if (entry.nativeId === nativeId || entry.altIds.has(nativeId)) {
            return f
          }
        }
      }
      return null
    }

    if (engineId === 'codex') {
      const codexDir = path.join(this.homeDir, '.codex', 'sessions')
      const files = collectJsonlFiles(codexDir, 0)
      // Prefer filename suffix / basename match before scanning unrelated old files
      for (const f of files) {
        const base = path.basename(f.filePath, '.jsonl')
        const extracted = extractCodexNativeId(f.filePath)
        if (extracted === nativeId || base === nativeId || base.endsWith(`-${nativeId}`)) {
          return f
        }
      }
      for (const f of files) {
        const entry = this.scanFileIncremental(f.filePath, 'codex', f.stat)
        if (entry.nativeId === nativeId || entry.altIds.has(nativeId)) {
          return f
        }
      }
      return null
    }

    return null
  }

  private scanFileIncremental(
    filePath: string,
    engineId: ScannedEngineId,
    stat: fs.Stats,
  ): FileScanCacheEntry {
    let entry = this.fileCache.get(filePath)

    // Unchanged file fast-path: zero file opens / reads
    if (entry && entry.size === stat.size && entry.mtimeMs === stat.mtimeMs) {
      return entry
    }

    // Reset cache if missing or file shrunk/truncated
    if (!entry || stat.size < entry.byteOffset) {
      const nativeId = inferInitialNativeId(engineId, filePath)
      entry = {
        filePath,
        engineId,
        nativeId,
        altIds: new Set([path.basename(filePath, '.jsonl')]),
        isSubagent: engineId === 'claude' ? isClaudeSubagentPath(filePath) : false,
        size: 0,
        mtimeMs: 0,
        byteOffset: 0,
        records: [],
        dedupeIndex: new Map(),
        anyHasTokenMetrics: false,
      }
      this.fileCache.set(filePath, entry)
    }

    if (stat.size > entry.byteOffset) {
      const bytesToRead = stat.size - entry.byteOffset
      let fd: number | null = null
      try {
        fd = fs.openSync(filePath, 'r')
        const buf = Buffer.allocUnsafe(bytesToRead)
        const bytesRead = fs.readSync(fd, buf, 0, bytesToRead, entry.byteOffset)
        this.totalBytesRead += bytesRead
        const slice = buf.subarray(0, bytesRead)

        let cursor = 0
        const startOffset = entry.byteOffset

        while (true) {
          const nlIdx = slice.indexOf(0x0a, cursor)
          if (nlIdx === -1) break
          const lineByteOffset = startOffset + cursor
          const line = slice.subarray(cursor, nlIdx).toString('utf8').trim()
          if (line) {
            this.consumeJsonLine(entry, line, lineByteOffset, stat.mtimeMs)
          }
          cursor = nlIdx + 1
        }

        if (cursor < slice.length) {
          const tail = slice.subarray(cursor).toString('utf8').trim()
          if (!tail) {
            cursor = slice.length
          } else if (isValidJsonObjectLine(tail)) {
            this.consumeJsonLine(entry, tail, startOffset + cursor, stat.mtimeMs)
            cursor = slice.length
          }
          // Otherwise keep `cursor` before incomplete trailing line so next scan re-reads it once flushed.
        }

        entry.byteOffset = startOffset + cursor
      } catch {
        // Ignore transient read error
      } finally {
        if (fd !== null) {
          try {
            fs.closeSync(fd)
          } catch {
            // ignore
          }
        }
      }
    }

    entry.size = stat.size
    entry.mtimeMs = stat.mtimeMs
    return entry
  }

  private consumeJsonLine(
    entry: FileScanCacheEntry,
    line: string,
    lineByteOffset: number,
    fallbackTsMs: number,
  ): void {
    let parsed: Record<string, unknown>
    try {
      const val = JSON.parse(line) as unknown
      if (!val || typeof val !== 'object' || Array.isArray(val)) return
      parsed = val as Record<string, unknown>
    } catch {
      return
    }

    switch (entry.engineId) {
      case 'claude':
        this.consumeClaudeLine(entry, parsed, lineByteOffset, fallbackTsMs)
        break
      case 'codex':
        this.consumeCodexLine(entry, parsed, fallbackTsMs)
        break
      case 'agy':
        this.consumeAgyLine(entry, parsed, lineByteOffset, fallbackTsMs)
        break
    }
  }

  private consumeClaudeLine(
    entry: FileScanCacheEntry,
    o: Record<string, unknown>,
    lineByteOffset: number,
    fallbackTsMs: number,
  ): void {
    if (o.type === 'api-request-blob') return
    if (typeof o.cwd === 'string' && o.cwd.trim()) {
      entry.currentCwd = o.cwd.trim()
    }
    const jsonSid =
      typeof o.sessionId === 'string' && o.sessionId.trim()
        ? o.sessionId.trim()
        : typeof o.session_id === 'string' && o.session_id.trim()
          ? o.session_id.trim()
          : undefined
    if (jsonSid) {
      entry.altIds.add(jsonSid)
    }

    const msg = o.message && typeof o.message === 'object' ? (o.message as Record<string, unknown>) : null
    if (!msg) return
    if (o.type !== 'assistant' && msg.role !== 'assistant') return

    const usage = msg.usage && typeof msg.usage === 'object' ? (msg.usage as Record<string, unknown>) : null
    if (!usage) return

    const model = typeof msg.model === 'string' && msg.model.trim() ? msg.model.trim() : undefined
    if (model === '<synthetic>') return

    const inputTokens = toNonNegInt(usage.input_tokens)
    const outputTokens = toNonNegInt(usage.output_tokens)
    const cacheWriteTokens = toNonNegInt(usage.cache_creation_input_tokens)
    const cacheReadTokens = toNonNegInt(usage.cache_read_input_tokens)
    const totalTokens = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens
    if (totalTokens === 0 && o.isApiErrorMessage === true) return
    if (totalTokens === 0 && !model) return

    entry.anyHasTokenMetrics = true
    const ts = parseTimestampMs(o.timestamp, fallbackTsMs)
    const sessionId = `host:claude:${entry.nativeId}`

    const record: UsageRecord = {
      engineId: 'claude',
      sessionId,
      nativeId: entry.nativeId,
      filePath: entry.filePath,
      isSubagent: entry.isSubagent,
      timestamp: ts,
      model,
      cwd: entry.currentCwd,
      inputTokens,
      outputTokens,
      cacheReadTokens,
      cacheWriteTokens,
      totalTokens,
      requests: 1,
      promptContextTokens: inputTokens + cacheReadTokens + cacheWriteTokens,
      hasTokenMetrics: true,
    }

    const msgId = typeof msg.id === 'string' && msg.id.trim() ? msg.id.trim() : undefined
    const reqId =
      typeof o.requestId === 'string' && o.requestId.trim()
        ? o.requestId.trim()
        : typeof o.requestRef === 'string' && o.requestRef.trim()
          ? o.requestRef.trim()
          : undefined

    // Deduplicate streaming chunks by `${message.id}:${requestId}`, keeping highest `output_tokens`.
    // Fall back to line byte-offset key when either id is absent.
    const dedupeKey = msgId && reqId ? `${msgId}:${reqId}` : `@offset:${lineByteOffset}`
    const existingIdx = entry.dedupeIndex.get(dedupeKey)
    if (existingIdx !== undefined) {
      const prev = entry.records[existingIdx]!
      if (
        record.outputTokens > prev.outputTokens ||
        (record.outputTokens === prev.outputTokens && record.totalTokens >= prev.totalTokens)
      ) {
        entry.records[existingIdx] = record
      }
    } else {
      entry.dedupeIndex.set(dedupeKey, entry.records.length)
      entry.records.push(record)
    }
  }

  private consumeCodexLine(
    entry: FileScanCacheEntry,
    o: Record<string, unknown>,
    fallbackTsMs: number,
  ): void {
    const ts = parseTimestampMs(o.timestamp, fallbackTsMs)
    const payload =
      o.payload && typeof o.payload === 'object' ? (o.payload as Record<string, unknown>) : null
    if (!payload) return

    if (typeof payload.cwd === 'string' && payload.cwd.trim()) {
      entry.currentCwd = payload.cwd.trim()
    }
    if (o.type === 'session_meta' && typeof payload.id === 'string' && payload.id.trim()) {
      entry.altIds.add(payload.id.trim())
    }
    if (
      (o.type === 'turn_context' || o.type === 'session_meta') &&
      typeof payload.model === 'string' &&
      payload.model.trim()
    ) {
      entry.currentModel = payload.model.trim()
    }

    if (o.type !== 'event_msg' || payload.type !== 'token_count') return

    // 1. Parse `rate_limits` if reported
    if (payload.rate_limits && typeof payload.rate_limits === 'object') {
      const rl = payload.rate_limits as Record<string, unknown>
      const primary = parseCodexRateWindow(rl.primary, ts, 300)
      const secondary = parseCodexRateWindow(rl.secondary, ts, 10_080)
      if (primary || secondary) {
        entry.latestRateLimits = {
          timestamp: ts,
          primary,
          secondary,
          planType: typeof rl.plan_type === 'string' ? rl.plan_type : undefined,
        }
      }
    }

    // 2. Parse `info` (`total_token_usage`, `last_token_usage`, `model_context_window`)
    const info = payload.info && typeof payload.info === 'object' ? (payload.info as Record<string, unknown>) : null
    if (!info) return

    const modelCtxWin =
      typeof info.model_context_window === 'number' && info.model_context_window > 0
        ? Math.floor(info.model_context_window)
        : undefined
    if (modelCtxWin) {
      entry.latestContextWindow = modelCtxWin
    }

    const totalU =
      info.total_token_usage && typeof info.total_token_usage === 'object'
        ? (info.total_token_usage as Record<string, unknown>)
        : null
    const lastU =
      info.last_token_usage && typeof info.last_token_usage === 'object'
        ? (info.last_token_usage as Record<string, unknown>)
        : null

    let uncachedInput = 0
    let cacheReadTokens = 0
    let cacheWriteTokens = 0
    let outputTokens = 0
    let totalTokens = 0

    if (totalU) {
      const rawInput = toNonNegInt(totalU.input_tokens)
      const rawCached = toNonNegInt(totalU.cached_input_tokens ?? totalU.cache_read_input_tokens)
      const rawCacheWrite = toNonNegInt(
        totalU.cache_write_input_tokens ?? totalU.cache_creation_input_tokens,
      )
      const rawOutput = toNonNegInt(totalU.output_tokens)
      const rawTotal =
        typeof totalU.total_tokens === 'number'
          ? toNonNegInt(totalU.total_tokens)
          : rawInput + rawOutput

      const prev = entry.prevTotalUsage
      // Skip duplicate cumulative snapshots where `total_tokens` did not increase
      if (prev && rawTotal === prev.total) {
        if (lastU) {
          entry.latestContextTokens = extractCodexPromptContext(lastU)
        }
        return
      }

      // If counter restarted from a lower number (e.g. process resume), treat previous baseline as 0
      const base: CumulativeTokenState =
        prev && rawTotal > prev.total
          ? prev
          : { input: 0, cached: 0, cacheWrite: 0, output: 0, total: 0 }

      const dRawInput = Math.max(0, rawInput - base.input)
      const dCached = Math.max(0, rawCached - base.cached)
      const dCacheWrite = Math.max(0, rawCacheWrite - base.cacheWrite)
      const dOutput = Math.max(0, rawOutput - base.output)
      const dTotal = Math.max(0, rawTotal - base.total)

      entry.prevTotalUsage = {
        input: rawInput,
        cached: rawCached,
        cacheWrite: rawCacheWrite,
        output: rawOutput,
        total: rawTotal,
      }

      const normalized = normalizeOpenAiUsageBreakdown(
        dRawInput,
        dCached,
        dCacheWrite,
        dOutput,
        dTotal,
      )
      uncachedInput = normalized.inputTokens
      cacheReadTokens = normalized.cacheReadTokens
      cacheWriteTokens = normalized.cacheWriteTokens
      outputTokens = normalized.outputTokens
      totalTokens = normalized.totalTokens
    } else if (lastU) {
      const lInput = toNonNegInt(lastU.input_tokens)
      const lCached = toNonNegInt(lastU.cached_input_tokens ?? lastU.cache_read_input_tokens)
      const lCacheWrite = toNonNegInt(
        lastU.cache_write_input_tokens ?? lastU.cache_creation_input_tokens,
      )
      const lOutput = toNonNegInt(lastU.output_tokens)
      const lTotal =
        typeof lastU.total_tokens === 'number' ? toNonNegInt(lastU.total_tokens) : lInput + lOutput
      const normalized = normalizeOpenAiUsageBreakdown(lInput, lCached, lCacheWrite, lOutput, lTotal)
      uncachedInput = normalized.inputTokens
      cacheReadTokens = normalized.cacheReadTokens
      cacheWriteTokens = normalized.cacheWriteTokens
      outputTokens = normalized.outputTokens
      totalTokens = normalized.totalTokens
    } else {
      return
    }

    if (totalTokens === 0) return

    const promptContextTokens = lastU
      ? extractCodexPromptContext(lastU)
      : uncachedInput + cacheReadTokens + cacheWriteTokens
    entry.latestContextTokens = promptContextTokens
    entry.anyHasTokenMetrics = true

    entry.records.push({
      engineId: 'codex',
      sessionId: `host:codex:${entry.nativeId}`,
      nativeId: entry.nativeId,
      filePath: entry.filePath,
      timestamp: ts,
      model: entry.currentModel,
      cwd: entry.currentCwd,
      inputTokens: uncachedInput,
      outputTokens,
      cacheReadTokens,
      cacheWriteTokens,
      totalTokens,
      requests: 1,
      promptContextTokens,
      reportedContextWindow: entry.latestContextWindow,
      hasTokenMetrics: true,
    })
  }

  private consumeAgyLine(
    entry: FileScanCacheEntry,
    o: Record<string, unknown>,
    lineByteOffset: number,
    fallbackTsMs: number,
  ): void {
    if (o.type !== 'PLANNER_RESPONSE') return

    const ts = parseTimestampMs(o.created_at ?? o.timestamp, fallbackTsMs)
    const model = typeof o.model === 'string' && o.model.trim() ? o.model.trim() : undefined
    if (model) entry.currentModel = model

    const usageObj = o.usage && typeof o.usage === 'object' ? (o.usage as Record<string, unknown>) : null
    const rawIn = o.input_tokens ?? usageObj?.input_tokens
    const rawOut = o.output_tokens ?? usageObj?.output_tokens
    const rawCacheRead =
      o.cache_read_tokens ?? o.cache_read_input_tokens ?? usageObj?.cache_read_input_tokens
    const rawCacheWrite =
      o.cache_write_tokens ??
      o.cache_creation_input_tokens ??
      usageObj?.cache_creation_input_tokens

    const hasTokenMetrics =
      typeof rawIn === 'number' ||
      typeof rawOut === 'number' ||
      typeof rawCacheRead === 'number' ||
      typeof rawCacheWrite === 'number'

    const inputTokens = toNonNegInt(rawIn)
    const outputTokens = toNonNegInt(rawOut)
    const cacheReadTokens = toNonNegInt(rawCacheRead)
    const cacheWriteTokens = toNonNegInt(rawCacheWrite)
    const totalTokens = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens

    if (hasTokenMetrics) {
      entry.anyHasTokenMetrics = true
    }

    const record: UsageRecord = {
      engineId: 'agy',
      sessionId: `host:agy:${entry.nativeId}`,
      nativeId: entry.nativeId,
      filePath: entry.filePath,
      timestamp: ts,
      model: model ?? entry.currentModel,
      inputTokens,
      outputTokens,
      cacheReadTokens,
      cacheWriteTokens,
      totalTokens,
      requests: 1,
      promptContextTokens: hasTokenMetrics ? inputTokens + cacheReadTokens + cacheWriteTokens : 0,
      hasTokenMetrics,
    }

    const dedupeKey =
      typeof o.step_index === 'number' ? `step:${o.step_index}` : `@offset:${lineByteOffset}`
    const existingIdx = entry.dedupeIndex.get(dedupeKey)
    if (existingIdx !== undefined) {
      const prev = entry.records[existingIdx]!
      if (record.totalTokens >= prev.totalTokens) {
        entry.records[existingIdx] = record
      }
    } else {
      entry.dedupeIndex.set(dedupeKey, entry.records.length)
      entry.records.push(record)
    }
  }
}

function collectJsonlFiles(
  rootDir: string,
  minMtimeMs: number,
): Array<{ filePath: string; stat: fs.Stats }> {
  const out: Array<{ filePath: string; stat: fs.Stats }> = []
  const walk = (dir: string, depth: number) => {
    if (depth > 8) return
    let entries: fs.Dirent[]
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true })
    } catch {
      return
    }
    for (const e of entries) {
      const full = path.join(dir, e.name)
      if (e.isDirectory()) {
        walk(full, depth + 1)
      } else if (e.isFile() && e.name.endsWith('.jsonl')) {
        try {
          const st = fs.statSync(full)
          if (st.mtimeMs >= minMtimeMs) {
            out.push({ filePath: full, stat: st })
          }
        } catch {
          // ignore
        }
      }
    }
  }
  walk(rootDir, 0)
  return out
}

function inferInitialNativeId(engineId: ScannedEngineId, filePath: string): string {
  if (engineId === 'agy') {
    // .../.gemini/antigravity-cli/brain/<convId>/.system_generated/logs/transcript.jsonl
    return path.basename(path.resolve(filePath, '..', '..', '..'))
  }
  if (engineId === 'codex') {
    return extractCodexNativeId(filePath)
  }
  // Claude: `<projects>/<slug>/<sessionUuid>.jsonl` or `<projects>/<slug>/<sessionUuid>/subagents/agent-*.jsonl`
  if (isClaudeSubagentPath(filePath)) {
    return path.basename(path.resolve(filePath, '..', '..'))
  }
  return path.basename(filePath, '.jsonl')
}

function extractCodexNativeId(filePath: string): string {
  const base = path.basename(filePath)
  const uuidMatch = base.match(/([0-9a-f]{8}-[0-9a-f-]{27})\.jsonl$/i)
  if (uuidMatch?.[1]) return uuidMatch[1]
  const noExt = path.basename(filePath, '.jsonl')
  const stripped = noExt.replace(/^rollout-\d{4}-\d{2}-\d{2}(?:T\d{2}-\d{2}-\d{2})?-/, '')
  return stripped || noExt
}

function isClaudeSubagentPath(filePath: string): boolean {
  const normalized = filePath.split(path.sep)
  return normalized.includes('subagents') || path.basename(filePath).startsWith('agent-')
}

function isValidJsonObjectLine(text: string): boolean {
  if (!text.startsWith('{') || !text.endsWith('}')) return false
  try {
    const parsed = JSON.parse(text) as unknown
    return Boolean(parsed && typeof parsed === 'object' && !Array.isArray(parsed))
  } catch {
    return false
  }
}

function toNonNegInt(val: unknown): number {
  if (typeof val !== 'number' || !Number.isFinite(val) || val <= 0) return 0
  return Math.floor(val)
}

function parseTimestampMs(raw: unknown, fallbackMs: number): number {
  if (typeof raw === 'number' && Number.isFinite(raw) && raw > 0) {
    return raw > 1e12 ? Math.floor(raw) : Math.floor(raw * 1000)
  }
  if (typeof raw === 'string' && raw.trim()) {
    const parsed = Date.parse(raw)
    if (Number.isFinite(parsed) && parsed > 0) return parsed
  }
  return Math.floor(fallbackMs)
}

function parseCodexRateWindow(
  raw: unknown,
  eventTsMs: number,
  defaultWindowMinutes: number,
): CodexRateLimitWindowSnapshot | undefined {
  if (!raw || typeof raw !== 'object') return undefined
  const w = raw as Record<string, unknown>
  if (typeof w.used_percent !== 'number' || !Number.isFinite(w.used_percent)) return undefined
  const windowMinutes =
    typeof w.window_minutes === 'number' && Number.isFinite(w.window_minutes) && w.window_minutes > 0
      ? Math.floor(w.window_minutes)
      : defaultWindowMinutes

  let resetsAt: number | undefined
  if (typeof w.resets_at === 'number' && Number.isFinite(w.resets_at) && w.resets_at > 0) {
    resetsAt = w.resets_at > 1e12 ? Math.floor(w.resets_at) : Math.floor(w.resets_at * 1000)
  } else if (
    typeof w.resets_in_seconds === 'number' &&
    Number.isFinite(w.resets_in_seconds) &&
    w.resets_in_seconds >= 0
  ) {
    resetsAt = Math.floor(eventTsMs + w.resets_in_seconds * 1000)
  }

  return {
    usedPercent: Math.max(0, Math.min(100, w.used_percent)),
    windowMinutes,
    resetsAt,
  }
}

/**
 * OpenAI Responses / Codex `input_tokens` includes `cached_input_tokens` (`input_tokens + output_tokens === total_tokens`).
 * Normalize so `inputTokens` is uncached input and `inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens === totalTokens`.
 */
function normalizeOpenAiUsageBreakdown(
  rawInput: number,
  cachedRead: number,
  cacheWrite: number,
  output: number,
  reportedTotal: number,
): {
  inputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  outputTokens: number
  totalTokens: number
} {
  const cacheSum = cachedRead + cacheWrite
  const includesCacheInInput =
    cacheSum > 0 &&
    rawInput >= cacheSum &&
    Math.abs(rawInput + output - reportedTotal) <=
      Math.abs(rawInput + cacheSum + output - reportedTotal)

  const uncachedInput = includesCacheInInput ? Math.max(0, rawInput - cacheSum) : rawInput
  const totalTokens = uncachedInput + output + cachedRead + cacheWrite
  return {
    inputTokens: uncachedInput,
    cacheReadTokens: cachedRead,
    cacheWriteTokens: cacheWrite,
    outputTokens: output,
    totalTokens,
  }
}

function extractCodexPromptContext(lastU: Record<string, unknown>): number {
  const lInput = toNonNegInt(lastU.input_tokens)
  const lCached = toNonNegInt(lastU.cached_input_tokens ?? lastU.cache_read_input_tokens)
  const lCacheWrite = toNonNegInt(
    lastU.cache_write_input_tokens ?? lastU.cache_creation_input_tokens,
  )
  const lOutput = toNonNegInt(lastU.output_tokens)
  const lTotal = typeof lastU.total_tokens === 'number' ? toNonNegInt(lastU.total_tokens) : lInput + lOutput
  if (lInput > 0) {
    const normalized = normalizeOpenAiUsageBreakdown(lInput, lCached, lCacheWrite, lOutput, lTotal)
    return normalized.inputTokens + normalized.cacheReadTokens + normalized.cacheWriteTokens
  }
  return lTotal
}
