import fs from 'node:fs'
import path from 'node:path'
import type { ReasoningEffort, SessionMeta, SessionMode, SessionWorktreeInfo } from '@lework/protocol'
import { defaultDataDir } from '../platform/index.ts'

export interface SessionMetadataRecord {
  customTitle?: string
  archived?: boolean
  archivedAt?: number
  worktree?: SessionWorktreeInfo
  preferredModel?: string
  preferredEffort?: ReasoningEffort
  preferredMode?: SessionMode
  diskModelAtOverride?: string
  updatedAt: number
  filePath?: string
  nativeId?: string
  snapshotMeta?: SessionMeta
}

interface PersistedSessionMetadataFile {
  version: 1
  records: Record<string, SessionMetadataRecord>
}

export class SessionMetadataStore {
  private loadedFromPath: string | null = null
  private records = new Map<string, SessionMetadataRecord>()

  constructor(private readonly explicitFilePath?: string) {}

  private resolveFilePath(): string {
    return this.explicitFilePath ?? path.join(defaultDataDir(), 'session-meta.json')
  }

  private ensureLoaded(): void {
    const target = this.resolveFilePath()
    if (this.loadedFromPath === target) return
    this.loadedFromPath = target
    this.records.clear()
    try {
      if (!fs.existsSync(target)) return
      const raw = JSON.parse(fs.readFileSync(target, 'utf8')) as Partial<PersistedSessionMetadataFile>
      if (raw && typeof raw.records === 'object' && raw.records !== null) {
        for (const [id, rec] of Object.entries(raw.records)) {
          if (rec && typeof rec === 'object') {
            this.records.set(id, rec)
          }
        }
      }
    } catch {
      // Corrupted or empty file: start clean in memory without crashing
    }
  }

  private persist(): void {
    const target = this.resolveFilePath()
    const dir = path.dirname(target)
    try {
      fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
      const payload: PersistedSessionMetadataFile = {
        version: 1,
        records: Object.fromEntries(this.records.entries()),
      }
      const tmp = `${target}.tmp.${process.pid}`
      fs.writeFileSync(tmp, JSON.stringify(payload, null, 2) + '\n', { mode: 0o600 })
      fs.renameSync(tmp, target)
    } catch {
      // Best-effort persistence (e.g. read-only sandbox)
    }
  }

  get(sessionId: string): SessionMetadataRecord | undefined {
    this.ensureLoaded()
    return this.records.get(sessionId)
  }

  applyToMeta(meta: SessionMeta): SessionMeta {
    this.ensureLoaded()
    const rec = this.records.get(meta.id)
    const cleanCustom = rec?.customTitle?.trim()
    const isArchived = rec?.archived === true
    const persistedWorktree = rec?.worktree ?? meta.worktree
    const diskModelChangedSinceOverride =
      Boolean(rec?.preferredModel) &&
      Boolean(meta.model) &&
      Boolean(rec?.diskModelAtOverride) &&
      meta.model !== rec?.diskModelAtOverride &&
      meta.model !== rec?.preferredModel

    const effectiveModel =
      !diskModelChangedSinceOverride && rec?.preferredModel ? rec.preferredModel : meta.model
    const effectiveEffort =
      !diskModelChangedSinceOverride && rec?.preferredEffort
        ? rec.preferredEffort
        : meta.reasoningEffort
    const effectiveMode = rec?.preferredMode ?? meta.mode

    if (
      !cleanCustom &&
      !isArchived &&
      !persistedWorktree &&
      effectiveModel === meta.model &&
      effectiveEffort === meta.reasoningEffort &&
      effectiveMode === meta.mode
    ) {
      if (!meta.customTitle && !meta.archived && meta.archivedAt === undefined && !meta.worktree) return meta
      const copy = { ...meta }
      delete copy.customTitle
      delete copy.archived
      delete copy.archivedAt
      delete copy.worktree
      return copy
    }
    const copy: SessionMeta = {
      ...meta,
      title: cleanCustom || meta.title,
      ...(effectiveModel ? { model: effectiveModel } : {}),
      ...(effectiveEffort ? { reasoningEffort: effectiveEffort } : {}),
      ...(effectiveMode ? { mode: effectiveMode } : {}),
    }
    if (cleanCustom) {
      copy.customTitle = cleanCustom
    } else {
      delete copy.customTitle
    }
    if (isArchived) {
      copy.archived = true
      copy.archivedAt = rec!.archivedAt ?? rec!.updatedAt
    } else {
      delete copy.archived
      delete copy.archivedAt
    }
    if (persistedWorktree) {
      copy.worktree = persistedWorktree
    } else {
      delete copy.worktree
    }
    return copy
  }

  private hasPersistedFields(rec: SessionMetadataRecord): boolean {
    return Boolean(
      rec.customTitle ||
        rec.archived ||
        rec.worktree ||
        rec.preferredModel ||
        rec.preferredEffort ||
        rec.preferredMode,
    )
  }

  setWorktree(
    sessionId: string,
    worktree: SessionWorktreeInfo | undefined,
    ctx?: {
      hostAliasId?: string
      filePath?: string
      nativeId?: string
      baseMeta?: SessionMeta
    },
  ): void {
    this.ensureLoaded()
    const now = Date.now()
    const ids = [sessionId, ctx?.hostAliasId].filter((v): v is string => Boolean(v && v.trim()))
    for (const id of new Set(ids)) {
      const prev = this.records.get(id)
      const next: SessionMetadataRecord = {
        ...prev,
        updatedAt: now,
        ...(ctx?.filePath ? { filePath: ctx.filePath } : {}),
        ...(ctx?.nativeId ? { nativeId: ctx.nativeId } : {}),
        ...(ctx?.baseMeta ? { snapshotMeta: stripOverrides(ctx.baseMeta, id) } : {}),
      }
      if (worktree) {
        next.worktree = worktree
      } else {
        delete next.worktree
      }
      if (!this.hasPersistedFields(next)) {
        this.records.delete(id)
      } else {
        this.records.set(id, next)
      }
    }
    this.persist()
  }

  setSessionPrefs(
    sessionId: string,
    prefs: {
      model?: string
      reasoningEffort?: ReasoningEffort
      mode?: SessionMode
    },
    ctx?: {
      hostAliasId?: string
      filePath?: string
      nativeId?: string
      baseMeta?: SessionMeta
    },
  ): void {
    this.ensureLoaded()
    const now = Date.now()
    const ids = [sessionId, ctx?.hostAliasId].filter((v): v is string => Boolean(v && v.trim()))
    for (const id of new Set(ids)) {
      const prev = this.records.get(id)
      const next: SessionMetadataRecord = {
        ...prev,
        updatedAt: now,
        ...(ctx?.filePath ? { filePath: ctx.filePath } : {}),
        ...(ctx?.nativeId ? { nativeId: ctx.nativeId } : {}),
        ...(ctx?.baseMeta ? { snapshotMeta: stripOverrides(ctx.baseMeta, id) } : {}),
      }
      if (prefs.model?.trim()) {
        next.preferredModel = prefs.model.trim()
        next.diskModelAtOverride = ctx?.baseMeta?.model ?? prev?.diskModelAtOverride
      }
      if (prefs.reasoningEffort) {
        next.preferredEffort = prefs.reasoningEffort
      }
      if (prefs.mode) {
        next.preferredMode = prefs.mode
      }
      if (!this.hasPersistedFields(next)) {
        this.records.delete(id)
      } else {
        this.records.set(id, next)
      }
    }
    this.persist()
  }

  rename(
    sessionId: string,
    rawTitle: string,
    ctx?: {
      hostAliasId?: string
      filePath?: string
      nativeId?: string
      baseMeta?: SessionMeta
    },
  ): void {
    this.ensureLoaded()
    const cleanTitle = rawTitle.replace(/\s+/g, ' ').trim().slice(0, 120)
    const now = Date.now()
    const ids = [sessionId, ctx?.hostAliasId].filter((v): v is string => Boolean(v && v.trim()))
    for (const id of new Set(ids)) {
      const prev = this.records.get(id)
      const next: SessionMetadataRecord = {
        ...prev,
        updatedAt: now,
        ...(ctx?.filePath ? { filePath: ctx.filePath } : {}),
        ...(ctx?.nativeId ? { nativeId: ctx.nativeId } : {}),
        ...(ctx?.baseMeta ? { snapshotMeta: stripOverrides(ctx.baseMeta, id) } : {}),
      }
      if (cleanTitle) {
        next.customTitle = cleanTitle
      } else {
        delete next.customTitle
      }
      if (!this.hasPersistedFields(next)) {
        this.records.delete(id)
      } else {
        this.records.set(id, next)
      }
    }
    this.persist()
  }

  setArchived(
    sessionId: string,
    archived: boolean,
    ctx?: {
      hostAliasId?: string
      filePath?: string
      nativeId?: string
      baseMeta?: SessionMeta
    },
  ): void {
    this.ensureLoaded()
    const now = Date.now()
    const ids = [sessionId, ctx?.hostAliasId].filter((v): v is string => Boolean(v && v.trim()))
    for (const id of new Set(ids)) {
      const prev = this.records.get(id)
      const next: SessionMetadataRecord = {
        ...prev,
        updatedAt: now,
        ...(ctx?.filePath ? { filePath: ctx.filePath } : {}),
        ...(ctx?.nativeId ? { nativeId: ctx.nativeId } : {}),
        ...(ctx?.baseMeta ? { snapshotMeta: stripOverrides(ctx.baseMeta, id) } : {}),
      }
      if (archived) {
        next.archived = true
        next.archivedAt = prev?.archivedAt ?? now
      } else {
        delete next.archived
        delete next.archivedAt
      }
      if (!this.hasPersistedFields(next)) {
        this.records.delete(id)
      } else {
        this.records.set(id, next)
      }
    }
    this.persist()
  }

  linkAlias(hubSessionId: string, hostSessionId: string): void {
    this.ensureLoaded()
    const hubRec = this.records.get(hubSessionId)
    const hostRec = this.records.get(hostSessionId)
    if (!hubRec && !hostRec) return
    const merged: SessionMetadataRecord = {
      ...hostRec,
      ...hubRec,
      customTitle: hubRec?.customTitle ?? hostRec?.customTitle,
      archived: hubRec?.archived ?? hostRec?.archived,
      archivedAt: hubRec?.archivedAt ?? hostRec?.archivedAt,
      worktree: hubRec?.worktree ?? hostRec?.worktree,
      preferredModel: hubRec?.preferredModel ?? hostRec?.preferredModel,
      preferredEffort: hubRec?.preferredEffort ?? hostRec?.preferredEffort,
      preferredMode: hubRec?.preferredMode ?? hostRec?.preferredMode,
      diskModelAtOverride: hubRec?.diskModelAtOverride ?? hostRec?.diskModelAtOverride,
      updatedAt: Math.max(hubRec?.updatedAt ?? 0, hostRec?.updatedAt ?? 0, Date.now()),
    }
    this.records.set(hubSessionId, merged)
    this.records.set(hostSessionId, merged)
    this.persist()
  }

  pinnedHostEntries(): Array<{ sessionId: string; record: SessionMetadataRecord }> {
    this.ensureLoaded()
    const out: Array<{ sessionId: string; record: SessionMetadataRecord }> = []
    for (const [sessionId, record] of this.records.entries()) {
      if (sessionId.startsWith('host:') && (record.archived || record.customTitle)) {
        out.push({ sessionId, record })
      }
    }
    return out
  }

  /**
   * Returns all session IDs that are currently archived (`archived === true`) AND whose
   * `archivedAt` timestamp is at or before `cutoffMs`. Active (`!archived`) sessions are NEVER returned.
   */
  expiredArchivedSessionIds(cutoffMs: number): string[] {
    this.ensureLoaded()
    const out: string[] = []
    for (const [sessionId, record] of this.records.entries()) {
      if (record.archived !== true) continue
      const archivedTs = record.archivedAt ?? record.updatedAt
      if (typeof archivedTs === 'number' && archivedTs > 0 && archivedTs <= cutoffMs) {
        out.push(sessionId)
      }
    }
    return out
  }
}

function stripOverrides(meta: SessionMeta, overrideId: string): SessionMeta {
  const copy: SessionMeta = { ...meta, id: overrideId }
  delete copy.customTitle
  delete copy.archived
  delete copy.archivedAt
  return copy
}
