import fs from 'node:fs'
import path from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import type {
  AgentEvent,
  SearchRoleFilter,
  SessionMeta,
  SessionSearchHit,
  SessionSearchMatch,
  SessionSearchParams,
  SessionSearchResult,
} from '@lework/protocol'
import { defaultDataDir } from '../platform/index.ts'

export interface SearchableSessionSource {
  meta: SessionMeta
  filePath?: string
  fileMtimeMs: number
  fileSize: number
  loadEvents: () => AgentEvent[]
}

export interface IndexableMessage {
  seq: number
  role: 'user' | 'assistant'
  ts: number
  text: string
}

interface IndexedSessionRow {
  session_id: string
  engine_id: string
  title: string
  cwd: string
  updated_at: number
  archived: number
  file_mtime_ms: number
  file_size: number
  last_seq: number
}

interface SearchMessageRow {
  session_id: string
  seq: number
  role: string
  ts: number
  body: string
}

const MAX_MESSAGE_CHARS = 20_000
const DEFAULT_SYNC_BUDGET_MS = 450
const BG_SYNC_BUDGET_MS = 180
const SNIPPET_MAX_LEN = 136

function cleanUserTextForIndex(raw: string): string {
  let text = raw
  // Strip AGY wrapper tags if any leaked into raw text
  const reqMatch = text.match(/<USER_REQUEST>\s*([\s\S]*?)\s*<\/USER_REQUEST>/)
  if (reqMatch?.[1]) {
    text = reqMatch[1]
  }
  text = text.replace(/<ADDITIONAL_METADATA>[\s\S]*?<\/ADDITIONAL_METADATA>/g, '')
  return text.trim()
}

/**
 * Extracts only human-meaningful conversation text (user prompts + assistant replies),
 * intentionally skipping tool call inputs/outputs, reasoning traces, and base64 blobs
 * so MCP tool schemas and huge diffs never pollute search results.
 */
export function extractIndexableMessages(events: AgentEvent[]): IndexableMessage[] {
  const out: IndexableMessage[] = []
  const assistantById = new Map<string, number>()

  for (const ev of events) {
    if (ev.type === 'message.user') {
      const cleaned = cleanUserTextForIndex(ev.text)
      if (!cleaned) continue
      out.push({
        seq: ev.seq,
        role: 'user',
        ts: ev.ts,
        text: cleaned.length > MAX_MESSAGE_CHARS ? cleaned.slice(0, MAX_MESSAGE_CHARS) : cleaned,
      })
    } else if (ev.type === 'message.assistant.delta') {
      const chunk = ev.text
      if (!chunk) continue
      const existingIdx = assistantById.get(ev.messageId)
      if (existingIdx !== undefined) {
        const entry = out[existingIdx]!
        if (entry.text.length < MAX_MESSAGE_CHARS) {
          const remain = MAX_MESSAGE_CHARS - entry.text.length
          entry.text += chunk.length > remain ? chunk.slice(0, remain) : chunk
        }
      } else {
        assistantById.set(ev.messageId, out.length)
        out.push({
          seq: ev.seq,
          role: 'assistant',
          ts: ev.ts,
          text: chunk.length > MAX_MESSAGE_CHARS ? chunk.slice(0, MAX_MESSAGE_CHARS) : chunk,
        })
      }
    }
  }

  return out
    .map(m => ({ ...m, text: m.text.trim() }))
    .filter(m => m.text.length > 0)
}

export function splitSearchTerms(rawQuery: string): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const piece of rawQuery.trim().split(/\s+/)) {
    const clean = piece.trim()
    if (!clean) continue
    const lower = clean.toLowerCase()
    if (seen.has(lower)) continue
    seen.add(lower)
    out.push(clean)
    if (out.length >= 8) break
  }
  return out
}

export function buildHighlightedSnippet(
  rawText: string,
  terms: string[],
  maxLen = SNIPPET_MAX_LEN,
): { snippet: string; matchStart: number; matchEnd: number } {
  const collapsed = rawText.replace(/\s+/g, ' ').trim()
  if (!collapsed) {
    return { snippet: '', matchStart: 0, matchEnd: 0 }
  }
  const lower = collapsed.toLowerCase()
  let bestIdx = -1
  let bestLen = 0
  for (const term of terms) {
    const idx = lower.indexOf(term.toLowerCase())
    if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
      bestIdx = idx
      bestLen = term.length
    }
  }

  if (bestIdx < 0) {
    const clipped = collapsed.length > maxLen ? `${collapsed.slice(0, maxLen - 1)}…` : collapsed
    return { snippet: clipped, matchStart: 0, matchEnd: 0 }
  }

  const leadContext = Math.min(36, Math.floor((maxLen - bestLen) / 3))
  const windowStart = Math.max(0, bestIdx - leadContext)
  const windowEnd = Math.min(collapsed.length, windowStart + maxLen)
  const prefix = windowStart > 0 ? '…' : ''
  const suffix = windowEnd < collapsed.length ? '…' : ''
  const snippet = `${prefix}${collapsed.slice(windowStart, windowEnd)}${suffix}`
  const matchStart = prefix.length + (bestIdx - windowStart)
  const matchEnd = Math.min(snippet.length, matchStart + bestLen)
  return { snippet, matchStart, matchEnd }
}

function escapeFtsPhrase(term: string): string {
  return `"${term.replace(/"/g, '""')}"`
}

function escapeLikePattern(term: string): string {
  return `%${term.toLowerCase().replace(/[\\%_]/g, '\\$&')}%`
}

function textMatchesAllTerms(text: string, lowerTerms: string[]): boolean {
  const lower = text.toLowerCase()
  for (const term of lowerTerms) {
    if (!lower.includes(term)) return false
  }
  return true
}

export class ConversationSearchIndex {
  private db: DatabaseSync | null = null
  private dbPath: string | null = null
  private bgTimer: ReturnType<typeof setTimeout> | null = null
  private bgPendingSources: SearchableSessionSource[] = []
  private closed = false

  constructor(private readonly explicitDbPath?: string) {}

  private resolveDbPath(): string {
    return this.explicitDbPath ?? path.join(defaultDataDir(), 'search-index.db')
  }

  private ensureDb(): DatabaseSync {
    const targetPath = this.resolveDbPath()
    if (this.db && this.dbPath === targetPath) {
      return this.db
    }
    this.closeDbHandle()
    this.closed = false
    let db: DatabaseSync
    try {
      if (targetPath !== ':memory:') {
        fs.mkdirSync(path.dirname(targetPath), { recursive: true, mode: 0o700 })
      }
      db = new DatabaseSync(targetPath)
      try {
        if (targetPath !== ':memory:' && fs.existsSync(targetPath)) {
          fs.chmodSync(targetPath, 0o600)
        }
      } catch {
        // ignore chmod failures on non-POSIX FS
      }
    } catch {
      // Fallback to in-memory SQLite so search stays available even if data dir is read-only
      db = new DatabaseSync(':memory:')
    }
    db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = NORMAL;
      CREATE TABLE IF NOT EXISTS indexed_sessions (
        session_id TEXT PRIMARY KEY,
        engine_id TEXT NOT NULL,
        title TEXT NOT NULL,
        cwd TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        archived INTEGER NOT NULL DEFAULT 0,
        file_mtime_ms REAL NOT NULL DEFAULT 0,
        file_size INTEGER NOT NULL DEFAULT 0,
        last_seq INTEGER NOT NULL DEFAULT -1,
        indexed_at INTEGER NOT NULL
      );
      CREATE VIRTUAL TABLE IF NOT EXISTS search_messages USING fts5(
        session_id UNINDEXED,
        seq UNINDEXED,
        role UNINDEXED,
        ts UNINDEXED,
        body,
        tokenize='trigram'
      );
    `)
    this.db = db
    this.dbPath = targetPath
    return db
  }

  private closeDbHandle(): void {
    if (this.db) {
      try {
        this.db.close()
      } catch {
        // ignore
      }
      this.db = null
      this.dbPath = null
    }
  }

  close(): void {
    this.closed = true
    if (this.bgTimer) {
      clearTimeout(this.bgTimer)
      this.bgTimer = null
    }
    this.bgPendingSources = []
    this.closeDbHandle()
  }

  /**
   * Gently warms up the search index in small non-blocking chunks after Hub startup.
   */
  scheduleBackgroundSync(sources: SearchableSessionSource[], delayMs = 1_500): void {
    if (this.closed) return
    this.bgPendingSources = sources
    if (this.bgTimer) return
    this.bgTimer = setTimeout(() => {
      this.bgTimer = null
      if (this.closed) return
      const pending = this.bgPendingSources
      if (pending.length === 0) return
      const stats = this.syncSources(pending, { budgetMs: BG_SYNC_BUDGET_MS })
      if (stats.remainingDirty > 0 && !this.closed) {
        this.scheduleBackgroundSync(pending, 35)
      } else {
        this.bgPendingSources = []
      }
    }, delayMs)
    this.bgTimer.unref?.()
  }

  private indexSingleSession(db: DatabaseSync, src: SearchableSessionSource): void {
    const events = src.loadEvents()
    const messages = extractIndexableMessages(events)
    const now = Date.now()
    const delMsgs = db.prepare('DELETE FROM search_messages WHERE session_id = ?')
    const insMsg = db.prepare(
      'INSERT INTO search_messages (session_id, seq, role, ts, body) VALUES (?, ?, ?, ?, ?)',
    )
    const upsertSess = db.prepare(`
      INSERT INTO indexed_sessions
        (session_id, engine_id, title, cwd, updated_at, archived, file_mtime_ms, file_size, last_seq, indexed_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(session_id) DO UPDATE SET
        engine_id = excluded.engine_id,
        title = excluded.title,
        cwd = excluded.cwd,
        updated_at = excluded.updated_at,
        archived = excluded.archived,
        file_mtime_ms = excluded.file_mtime_ms,
        file_size = excluded.file_size,
        last_seq = excluded.last_seq,
        indexed_at = excluded.indexed_at
    `)

    db.exec('BEGIN')
    try {
      delMsgs.run(src.meta.id)
      for (const msg of messages) {
        insMsg.run(src.meta.id, msg.seq, msg.role, msg.ts, msg.text)
      }
      upsertSess.run(
        src.meta.id,
        src.meta.engineId,
        src.meta.title,
        src.meta.cwd,
        src.meta.updatedAt,
        src.meta.archived ? 1 : 0,
        src.fileMtimeMs,
        src.fileSize,
        src.meta.lastSeq,
        now,
      )
      db.exec('COMMIT')
    } catch (err) {
      try {
        db.exec('ROLLBACK')
      } catch {
        // ignore rollback error
      }
      throw err
    }
  }

  private syncSources(
    sources: SearchableSessionSource[],
    opts: { budgetMs: number; prioritySessionId?: string; pruneMissing?: boolean },
  ): { indexedCount: number; totalCount: number; remainingDirty: number } {
    const db = this.ensureDb()
    const existingRows = db
      .prepare(
        'SELECT session_id, engine_id, title, cwd, updated_at, archived, file_mtime_ms, file_size, last_seq FROM indexed_sessions',
      )
      .all() as unknown as IndexedSessionRow[]
    const existingMap = new Map<string, IndexedSessionRow>()
    for (const row of existingRows) {
      existingMap.set(row.session_id, row)
    }

    const activeIds = new Set<string>()
    const dirty: SearchableSessionSource[] = []
    const metaUpdates: SearchableSessionSource[] = []

    for (const src of sources) {
      activeIds.add(src.meta.id)
      const prev = existingMap.get(src.meta.id)
      const isHostFile = Boolean(src.filePath)
      const contentUnchanged =
        prev !== undefined &&
        (isHostFile
          ? prev.file_mtime_ms === src.fileMtimeMs && prev.file_size === src.fileSize
          : prev.last_seq === src.meta.lastSeq && prev.updated_at === src.meta.updatedAt)

      if (!contentUnchanged) {
        dirty.push(src)
      } else if (
        prev.title !== src.meta.title ||
        prev.cwd !== src.meta.cwd ||
        prev.archived !== (src.meta.archived ? 1 : 0) ||
        prev.updated_at !== src.meta.updatedAt
      ) {
        metaUpdates.push(src)
      }
    }

    if (metaUpdates.length > 0) {
      const upd = db.prepare(
        'UPDATE indexed_sessions SET title = ?, cwd = ?, archived = ?, updated_at = ? WHERE session_id = ?',
      )
      db.exec('BEGIN')
      try {
        for (const src of metaUpdates) {
          upd.run(src.meta.title, src.meta.cwd, src.meta.archived ? 1 : 0, src.meta.updatedAt, src.meta.id)
        }
        db.exec('COMMIT')
      } catch {
        try {
          db.exec('ROLLBACK')
        } catch {
          // ignore
        }
      }
    }

    if (opts.pruneMissing) {
      const staleIds = existingRows.map(r => r.session_id).filter(id => !activeIds.has(id))
      if (staleIds.length > 0) {
        const delMsgs = db.prepare('DELETE FROM search_messages WHERE session_id = ?')
        const delSess = db.prepare('DELETE FROM indexed_sessions WHERE session_id = ?')
        db.exec('BEGIN')
        try {
          for (const id of staleIds) {
            delMsgs.run(id)
            delSess.run(id)
          }
          db.exec('COMMIT')
        } catch {
          try {
            db.exec('ROLLBACK')
          } catch {
            // ignore
          }
        }
      }
    }

    // Index priority session (in-session search) and most recently updated sessions first
    dirty.sort((a, b) => {
      if (opts.prioritySessionId) {
        if (a.meta.id === opts.prioritySessionId) return -1
        if (b.meta.id === opts.prioritySessionId) return 1
      }
      return b.meta.updatedAt - a.meta.updatedAt
    })

    const deadline = Date.now() + opts.budgetMs
    let processedDirty = 0
    for (const src of dirty) {
      const isPriority = opts.prioritySessionId !== undefined && src.meta.id === opts.prioritySessionId
      if (processedDirty > 0 && !isPriority && Date.now() >= deadline) {
        break
      }
      try {
        this.indexSingleSession(db, src)
      } catch {
        // Never let one corrupted transcript file block search across all other sessions
      }
      processedDirty++
    }

    const remainingDirty = Math.max(0, dirty.length - processedDirty)
    const indexedCount = Math.max(0, sources.length - remainingDirty)
    return {
      indexedCount,
      totalCount: sources.length,
      remainingDirty,
    }
  }

  search(params: SessionSearchParams, sources: SearchableSessionSource[]): SessionSearchResult {
    const rawQuery = params.query.trim()
    const targetSessionId = params.sessionId?.trim() || undefined
    const syncStats = this.syncSources(sources, {
      budgetMs: DEFAULT_SYNC_BUDGET_MS,
      prioritySessionId: targetSessionId,
      pruneMissing: !targetSessionId,
    })

    if (syncStats.remainingDirty > 0) {
      this.scheduleBackgroundSync(sources, 25)
    }

    if (!rawQuery) {
      return {
        query: '',
        hits: [],
        indexedSessions: syncStats.indexedCount,
        totalSessions: syncStats.totalCount,
        indexing: syncStats.remainingDirty > 0,
      }
    }

    const terms = splitSearchTerms(rawQuery)
    if (terms.length === 0) {
      return {
        query: rawQuery,
        hits: [],
        indexedSessions: syncStats.indexedCount,
        totalSessions: syncStats.totalCount,
        indexing: syncStats.remainingDirty > 0,
      }
    }

    const db = this.ensureDb()
    const lowerTerms = terms.map(t => t.toLowerCase())
    const trigramTerms = terms.filter(t => t.length >= 3)
    const roleFilter: SearchRoleFilter = params.role ?? 'all'
    const includeArchived = params.includeArchived === true || Boolean(targetSessionId)
    const maxSessions = params.limit ?? 30
    const matchesPerSession = params.matchesPerSession ?? (targetSessionId ? 50 : 3)
    const cwdFilter = params.cwd?.trim()

    // Build filtered session metadata lookup
    const metaById = new Map<string, SessionMeta>()
    for (const src of sources) {
      const m = src.meta
      if (targetSessionId && m.id !== targetSessionId) continue
      if (params.engineId && m.engineId !== params.engineId) continue
      if (!includeArchived && m.archived) continue
      if (cwdFilter && m.cwd !== cwdFilter && !(m.workspaces ?? []).includes(cwdFilter)) continue
      metaById.set(m.id, m)
    }

    if (metaById.size === 0) {
      return {
        query: rawQuery,
        hits: [],
        indexedSessions: syncStats.indexedCount,
        totalSessions: syncStats.totalCount,
        indexing: syncStats.remainingDirty > 0,
      }
    }

    const candidateRows: SearchMessageRow[] = []
    const whereClauses: string[] = []
    const bindValues: Array<string | number> = []

    if (targetSessionId) {
      whereClauses.push('session_id = ?')
      bindValues.push(targetSessionId)
    }
    if (roleFilter === 'user' || roleFilter === 'assistant') {
      whereClauses.push('role = ?')
      bindValues.push(roleFilter)
    }

    if (trigramTerms.length > 0) {
      const ftsQuery = trigramTerms.map(escapeFtsPhrase).join(' ')
      whereClauses.push('search_messages MATCH ?')
      bindValues.push(ftsQuery)
      const sql = `
        SELECT session_id, seq, role, ts, body
        FROM search_messages
        WHERE ${whereClauses.join(' AND ')}
        LIMIT 800
      `
      const rows = db.prepare(sql).all(...bindValues) as unknown as SearchMessageRow[]
      for (const row of rows) {
        if (!metaById.has(row.session_id)) continue
        if (textMatchesAllTerms(row.body, lowerTerms)) {
          candidateRows.push(row)
        }
      }
    } else {
      // Short queries (1-2 chars such as "鸿蒙" or "PR"): SQLite trigram cannot match < 3 chars,
      // so query indexed message text directly with escaped case-insensitive LIKE clauses.
      for (const term of lowerTerms) {
        whereClauses.push("LOWER(body) LIKE ? ESCAPE '\\'")
        bindValues.push(escapeLikePattern(term))
      }
      const sql = `
        SELECT session_id, seq, role, ts, body
        FROM search_messages
        WHERE ${whereClauses.join(' AND ')}
        LIMIT 800
      `
      const rows = db.prepare(sql).all(...bindValues) as unknown as SearchMessageRow[]
      for (const row of rows) {
        if (!metaById.has(row.session_id)) continue
        candidateRows.push(row)
      }
    }

    // Group matched messages by session
    const grouped = new Map<string, SearchMessageRow[]>()
    for (const row of candidateRows) {
      const list = grouped.get(row.session_id)
      if (list) list.push(row)
      else grouped.set(row.session_id, [row])
    }

    // For global search with role='all', also include sessions whose title or cwd matches all terms
    // even if no message body matched.
    if (!targetSessionId && roleFilter === 'all') {
      for (const [sid, meta] of metaById) {
        if (grouped.has(sid)) continue
        const titleAndCwd = `${meta.title} ${meta.cwd}`
        if (textMatchesAllTerms(titleAndCwd, lowerTerms)) {
          grouped.set(sid, [])
        }
      }
    }

    const hits: SessionSearchHit[] = []
    for (const [sid, rows] of grouped) {
      const meta = metaById.get(sid)
      if (!meta) continue

      // In-session find returns chronological seq ASC for predictable prev/next walking;
      // global search surfaces user prompts first and latest turns first within a session.
      const orderedRows = [...rows].sort((a, b) => {
        if (targetSessionId) return a.seq - b.seq
        const aUser = a.role === 'user' ? 1 : 0
        const bUser = b.role === 'user' ? 1 : 0
        if (aUser !== bUser) return bUser - aUser
        return b.seq - a.seq
      })

      const matches: SessionSearchMatch[] = orderedRows.slice(0, matchesPerSession).map(row => {
        const { snippet, matchStart, matchEnd } = buildHighlightedSnippet(row.body, terms)
        return {
          seq: row.seq,
          role: row.role === 'user' ? 'user' : 'assistant',
          ts: row.ts,
          snippet,
          matchStart,
          matchEnd,
        }
      })

      // Title/cwd-only hit gets a synthetic top-level match pointing to seq=0
      if (matches.length === 0) {
        const { snippet, matchStart, matchEnd } = buildHighlightedSnippet(
          `${meta.title} · ${meta.cwd}`,
          terms,
        )
        matches.push({
          seq: 0,
          role: 'user',
          ts: meta.updatedAt,
          snippet,
          matchStart,
          matchEnd,
        })
      }

      hits.push({
        sessionId: meta.id,
        engineId: meta.engineId,
        title: meta.title,
        cwd: meta.cwd,
        updatedAt: meta.updatedAt,
        archived: meta.archived,
        matchCount: Math.max(rows.length, 1),
        matches,
      })
    }

    // Rank: real message hits > title-only hits, then user-match hits, then most recently updated
    hits.sort((a, b) => {
      const aHasRealMsg = (grouped.get(a.sessionId)?.length ?? 0) > 0 ? 1 : 0
      const bHasRealMsg = (grouped.get(b.sessionId)?.length ?? 0) > 0 ? 1 : 0
      if (aHasRealMsg !== bHasRealMsg) return bHasRealMsg - aHasRealMsg
      const aHasUser = a.matches.some(m => m.role === 'user') ? 1 : 0
      const bHasUser = b.matches.some(m => m.role === 'user') ? 1 : 0
      if (aHasUser !== bHasUser) return bHasUser - aHasUser
      return b.updatedAt - a.updatedAt
    })

    return {
      query: rawQuery,
      hits: hits.slice(0, maxSessions),
      indexedSessions: syncStats.indexedCount,
      totalSessions: syncStats.totalCount,
      indexing: syncStats.remainingDirty > 0,
    }
  }
}
