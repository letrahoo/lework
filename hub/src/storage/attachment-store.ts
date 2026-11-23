import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { PromptImageAttachment } from '@lework/protocol'
import type { StagedPromptImage } from '../engines/types.ts'
import { defaultDataDir } from '../platform/index.ts'
import type { SessionMetadataStore } from './session-metadata.ts'

export const ARCHIVED_ATTACHMENT_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000

const SAFE_SID_RE = /^[A-Za-z0-9_-]{1,80}$/
const SAFE_IMG_FILE_RE = /^([A-Za-z0-9_.-]{1,80})\.(jpg|jpeg|png|webp|gif)$/i
const STAGED_HEADER_RE = /^\[用户附加了本地图片文件，请使用[^\]]*查看：([^\]]+)\]\s*([\s\S]*)$/

export function safeAttachmentSessionKey(sessionId: string): string {
  return sessionId.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 80) || 'default'
}

function currentUidSuffix(): string {
  return typeof process.getuid === 'function' ? String(process.getuid()) : 'local'
}

function ensurePrivateOwnerDir(dirPath: string): void {
  if (fs.existsSync(dirPath)) {
    const st = fs.lstatSync(dirPath)
    if (!st.isDirectory() || st.isSymbolicLink()) {
      throw new Error(`Refusing insecure attachment directory "${dirPath}"`)
    }
    if (typeof process.getuid === 'function' && st.uid !== process.getuid()) {
      throw new Error(`Attachment directory "${dirPath}" is not owned by current user`)
    }
  } else {
    fs.mkdirSync(dirPath, { recursive: true, mode: 0o700 })
  }
  try {
    fs.chmodSync(dirPath, 0o700)
  } catch {
    // best-effort on non-POSIX filesystems
  }
}

/**
 * Sandbox-whitelisted runtime staging root (`/tmp/lework-attachments-<uid>` on macOS/Linux)
 * so AGY's Standard Sandbox (`read_file`), Claude Code, and Codex can inspect attached images
 * natively with zero permission popups and without polluting workspace directories.
 */
export function runtimeAttachmentsBaseDir(): string {
  const baseTmp = process.platform !== 'win32' && fs.existsSync('/tmp') ? '/tmp' : os.tmpdir()
  const root = path.join(baseTmp, `lework-attachments-${currentUidSuffix()}`)
  ensurePrivateOwnerDir(root)
  return root
}

/**
 * Persistent backing root (`~/.lework/attachments`) so active conversations never lose their
 * images or thumbnails across macOS reboots or OS `/tmp` periodic sweeps.
 */
export function persistentAttachmentsBaseDir(): string {
  const root = path.join(defaultDataDir(), 'attachments')
  ensurePrivateOwnerDir(root)
  return root
}

export function stagePromptImagesForSession(
  sessionId: string,
  turnId: string,
  images: PromptImageAttachment[],
): StagedPromptImage[] {
  if (images.length === 0) return []
  const safeSid = safeAttachmentSessionKey(sessionId)
  const runtimeDir = path.join(runtimeAttachmentsBaseDir(), safeSid)
  const persistDir = path.join(persistentAttachmentsBaseDir(), safeSid)
  ensurePrivateOwnerDir(runtimeDir)
  ensurePrivateOwnerDir(persistDir)

  const out: StagedPromptImage[] = []
  for (let i = 0; i < images.length; i++) {
    const img = images[i]!
    const ext =
      img.mimeType === 'image/png'
        ? 'png'
        : img.mimeType === 'image/webp'
          ? 'webp'
          : img.mimeType === 'image/gif'
            ? 'gif'
            : 'jpg'
    const stem = `${turnId}_${i + 1}`
    const fileName = `${stem}.${ext}`
    const runtimePath = path.join(runtimeDir, fileName)
    const persistPath = path.join(persistDir, fileName)
    const bytes = Buffer.from(img.dataBase64, 'base64')
    fs.writeFileSync(runtimePath, bytes, { mode: 0o600 })
    fs.writeFileSync(persistPath, bytes, { mode: 0o600 })

    const previewBase64 =
      img.thumbnailBase64?.trim() ||
      (img.dataBase64.length <= 260_000 ? img.dataBase64.trim() : undefined)
    if (previewBase64) {
      const thumbPath = path.join(persistDir, `${stem}.thumb.b64`)
      try {
        fs.writeFileSync(thumbPath, previewBase64, { mode: 0o600 })
      } catch {
        // best effort
      }
    }

    out.push({
      ...img,
      stagedPath: runtimePath,
      ...(previewBase64 ? { previewBase64 } : {}),
    })
  }
  return out
}

export function buildTerminalImagePrompt(stagedImages: StagedPromptImage[], rawUserText: string): string {
  const cleanUser = rawUserText.trim()
  if (stagedImages.length === 0) return cleanUser
  const pathsCsv = stagedImages.map(i => i.stagedPath).join(', ')
  return `[用户附加了本地图片文件，请使用 read_file 工具查看：${pathsCsv}]\n\n${cleanUser || '请结合所附图片进行分析。'}`
}

export function linkSessionAttachmentAlias(hubSessionId: string, hostSessionId: string): void {
  const hubKey = safeAttachmentSessionKey(hubSessionId)
  const hostKey = safeAttachmentSessionKey(hostSessionId)
  if (hubKey === hostKey) return
  try {
    const persistBase = persistentAttachmentsBaseDir()
    const src = path.join(persistBase, hubKey)
    const dst = path.join(persistBase, hostKey)
    if (fs.existsSync(src) && !fs.existsSync(dst)) {
      fs.cpSync(src, dst, { recursive: true })
      ensurePrivateOwnerDir(dst)
    }
  } catch {
    // best effort
  }
}

export function removeSessionAttachments(sessionId: string, aliasSessionId?: string): void {
  const keys = new Set([safeAttachmentSessionKey(sessionId)])
  if (aliasSessionId) keys.add(safeAttachmentSessionKey(aliasSessionId))
  for (const key of keys) {
    if (!SAFE_SID_RE.test(key)) continue
    try {
      const rtBase = path.join(
        process.platform !== 'win32' && fs.existsSync('/tmp') ? '/tmp' : os.tmpdir(),
        `lework-attachments-${currentUidSuffix()}`,
        key,
      )
      fs.rmSync(rtBase, { recursive: true, force: true })
    } catch {
      // ignore
    }
    try {
      const psBase = path.join(defaultDataDir(), 'attachments', key)
      fs.rmSync(psBase, { recursive: true, force: true })
    } catch {
      // ignore
    }
  }
}

/**
 * Cleans up image attachments ONLY for sessions that have been archived (`archived === true`)
 * for at least `retentionMs` (default 7 days). Active (`!archived`) conversations are NEVER cleaned!
 */
export function sweepExpiredArchivedAttachments(
  metaStore: SessionMetadataStore,
  retentionMs: number = ARCHIVED_ATTACHMENT_RETENTION_MS,
  now: number = Date.now(),
): number {
  const expiredIds = metaStore.expiredArchivedSessionIds(now - retentionMs)
  let removedCount = 0
  for (const sid of expiredIds) {
    const key = safeAttachmentSessionKey(sid)
    if (!SAFE_SID_RE.test(key)) continue
    const rtPath = path.join(
      process.platform !== 'win32' && fs.existsSync('/tmp') ? '/tmp' : os.tmpdir(),
      `lework-attachments-${currentUidSuffix()}`,
      key,
    )
    const psPath = path.join(defaultDataDir(), 'attachments', key)
    const existed = fs.existsSync(rtPath) || fs.existsSync(psPath)
    removeSessionAttachments(sid)
    if (existed) removedCount++
  }
  return removedCount
}

/**
 * Parses a user prompt string that may start with `[用户附加了本地图片文件，请使用...查看：...]`
 * (written into AGY `transcript.jsonl` or Claude `.jsonl`), returning clean display text,
 * image count, and cached thumbnail previews (while re-materializing `/tmp` files if needed).
 */
export function parseStagedImagePromptHeader(
  rawText: string,
  loadThumbnails = true,
): {
  cleanText: string
  imageCount: number
  imagePreviews?: string[]
} {
  const match = rawText.match(STAGED_HEADER_RE)
  if (!match) {
    return { cleanText: rawText, imageCount: 0 }
  }
  const rawPaths = (match[1] ?? '')
    .split(',')
    .map(p => p.trim())
    .filter(Boolean)
    .slice(0, 4)
  const remainder = (match[2] ?? '').trim()
  const cleanText =
    remainder === '请结合所附图片进行分析。'
      ? `（已发送 ${rawPaths.length} 张图片）`
      : remainder || `（已发送 ${rawPaths.length} 张图片）`

  if (!loadThumbnails || rawPaths.length === 0) {
    return { cleanText, imageCount: rawPaths.length }
  }

  const previews: string[] = []
  for (const p of rawPaths) {
    const fileName = path.basename(p)
    const safeSid = path.basename(path.dirname(p))
    const fileMatch = fileName.match(SAFE_IMG_FILE_RE)
    if (!fileMatch || !SAFE_SID_RE.test(safeSid)) continue
    const stem = fileMatch[1]!
    const persistDir = path.join(defaultDataDir(), 'attachments', safeSid)
    const persistImg = path.join(persistDir, fileName)

    // Re-materialize into `/tmp/lework-attachments-<uid>/<safeSid>/` if cleared by OS reboot on active session
    if (!fs.existsSync(p) && fs.existsSync(persistImg) && p.includes('lework-attachments')) {
      try {
        const rtDir = path.join(runtimeAttachmentsBaseDir(), safeSid)
        ensurePrivateOwnerDir(rtDir)
        fs.copyFileSync(persistImg, path.join(rtDir, fileName))
        fs.chmodSync(path.join(rtDir, fileName), 0o600)
      } catch {
        // best effort
      }
    }

    const thumbPath = path.join(persistDir, `${stem}.thumb.b64`)
    try {
      if (fs.existsSync(thumbPath)) {
        const st = fs.statSync(thumbPath)
        if (st.size > 4 && st.size <= 290_000) {
          previews.push(fs.readFileSync(thumbPath, 'utf8').trim())
          continue
        }
      }
      const fallbackImg = fs.existsSync(persistImg) ? persistImg : fs.existsSync(p) ? p : null
      if (fallbackImg) {
        const st = fs.statSync(fallbackImg)
        if (st.size > 0 && st.size <= 210_000) {
          previews.push(fs.readFileSync(fallbackImg).toString('base64'))
        }
      }
    } catch {
      // ignore unreadable thumbnail
    }
  }

  return {
    cleanText,
    imageCount: rawPaths.length,
    ...(previews.length > 0 ? { imagePreviews: previews } : {}),
  }
}
