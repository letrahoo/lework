import { execFileSync } from 'node:child_process'
import crypto from 'node:crypto'
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
const MAX_DIRECT_PREVIEW_FILE_BYTES = 210_000
const MAX_PREVIEW_B64_CHARS = 260_000
const MAX_FALLBACK_RAW_B64_CHARS = 320_000
const MAX_THUMB_JPEG_BYTES = 260_000
const MAX_CACHED_THUMB_B64_BYTES = 360_000
const MAX_SOURCE_IMAGE_BYTES = 20 * 1024 * 1024
const MAX_INLINE_IMAGE_B64_CHARS = 28_000_000
const SIPS_BIN = '/usr/bin/sips'
const SUPPORTED_IMAGE_EXTS = new Set([
  '.png',
  '.jpg',
  '.jpeg',
  '.webp',
  '.gif',
  '.bmp',
  '.heic',
  '.tiff',
  '.tif',
])
const DIRECT_DECODABLE_IMAGE_EXTS = new Set(['.png', '.jpg', '.jpeg', '.webp', '.gif'])

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

function thumbCacheDir(): string {
  const dir = path.join(persistentAttachmentsBaseDir(), '_thumb_cache')
  ensurePrivateOwnerDir(dir)
  return dir
}

function readCachedThumb(cacheKey: string): string | undefined {
  try {
    const cachePath = path.join(thumbCacheDir(), `${cacheKey}.b64`)
    if (!fs.existsSync(cachePath)) return undefined
    const st = fs.lstatSync(cachePath)
    if (st.isSymbolicLink() || !st.isFile() || st.size <= 4 || st.size > MAX_CACHED_THUMB_B64_BYTES) {
      return undefined
    }
    const b64 = fs.readFileSync(cachePath, 'utf8').trim()
    return b64.length > 4 ? b64 : undefined
  } catch {
    return undefined
  }
}

function writeCachedThumb(cacheKey: string, previewBase64: string): void {
  try {
    const cachePath = path.join(thumbCacheDir(), `${cacheKey}.b64`)
    fs.writeFileSync(cachePath, previewBase64, { mode: 0o600 })
  } catch {
    // best effort
  }
}

function downscaleFileWithSips(srcPath: string): string | undefined {
  if (process.platform !== 'darwin' || !fs.existsSync(SIPS_BIN)) return undefined
  const dstPath = path.join(
    os.tmpdir(),
    `lework-sips-out-${process.pid}-${crypto.randomBytes(6).toString('hex')}.jpg`,
  )
  try {
    const passes: Array<[string, string]> = [
      ['640', '76'],
      ['480', '62'],
    ]
    for (const [maxDim, quality] of passes) {
      execFileSync(
        SIPS_BIN,
        ['-Z', maxDim, '-s', 'format', 'jpeg', '-s', 'formatOptions', quality, srcPath, '--out', dstPath],
        { timeout: 3_000, stdio: 'ignore' },
      )
      const st = fs.lstatSync(dstPath)
      if (!st.isSymbolicLink() && st.isFile() && st.size > 0 && st.size <= MAX_THUMB_JPEG_BYTES) {
        return fs.readFileSync(dstPath).toString('base64')
      }
    }
    return undefined
  } catch {
    return undefined
  } finally {
    try {
      fs.rmSync(dstPath, { force: true })
    } catch {
      // ignore cleanup failure
    }
  }
}

function inferTempExtFromMimeOrMagic(bytes: Buffer, mimeType?: string): string {
  const lower = (mimeType ?? '').toLowerCase().trim()
  if (lower === 'image/png') return '.png'
  if (lower === 'image/jpeg' || lower === 'image/jpg') return '.jpg'
  if (lower === 'image/webp') return '.webp'
  if (lower === 'image/gif') return '.gif'
  if (lower === 'image/bmp') return '.bmp'
  if (lower === 'image/heic' || lower === 'image/heif') return '.heic'
  if (lower === 'image/tiff') return '.tiff'
  if (bytes.length >= 4 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) {
    return '.png'
  }
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return '.jpg'
  }
  if (bytes.length >= 4 && bytes.subarray(0, 4).toString('ascii') === 'GIF8') {
    return '.gif'
  }
  if (
    bytes.length >= 12 &&
    bytes.subarray(0, 4).toString('ascii') === 'RIFF' &&
    bytes.subarray(8, 12).toString('ascii') === 'WEBP'
  ) {
    return '.webp'
  }
  return '.jpg'
}

export function resolveContainedImageFile(
  filePath: string,
  allowedRootDir?: string,
): { realPath: string; ext: string; size: number; mtimeMs: number } | null {
  if (!filePath || typeof filePath !== 'string') return null
  try {
    const lst = fs.lstatSync(filePath)
    if (lst.isSymbolicLink() || !lst.isFile()) return null
    const realPath = fs.realpathSync(filePath)
    if (allowedRootDir) {
      const realRoot = fs.realpathSync(allowedRootDir)
      const rootPrefix = realRoot.endsWith(path.sep) ? realRoot : `${realRoot}${path.sep}`
      if (!realPath.startsWith(rootPrefix)) return null
    }
    const ext = path.extname(realPath).toLowerCase()
    if (!SUPPORTED_IMAGE_EXTS.has(ext)) return null
    const st = fs.statSync(realPath)
    if (!st.isFile() || st.size <= 0 || st.size > MAX_SOURCE_IMAGE_BYTES) return null
    return { realPath, ext, size: st.size, mtimeMs: st.mtimeMs }
  } catch {
    return null
  }
}

export function loadImagePreviewBase64FromFile(
  filePath: string,
  allowedRootDir?: string,
): string | undefined {
  const resolved = resolveContainedImageFile(filePath, allowedRootDir)
  if (!resolved) return undefined

  try {
    if (
      resolved.size <= MAX_DIRECT_PREVIEW_FILE_BYTES &&
      DIRECT_DECODABLE_IMAGE_EXTS.has(resolved.ext)
    ) {
      return fs.readFileSync(resolved.realPath).toString('base64')
    }

    const cacheKey = crypto
      .createHash('sha256')
      .update(`${resolved.realPath}\0${resolved.mtimeMs}\0${resolved.size}`)
      .digest('hex')
    const cached = readCachedThumb(cacheKey)
    if (cached) return cached

    const downscaled = downscaleFileWithSips(resolved.realPath)
    if (downscaled) {
      writeCachedThumb(cacheKey, downscaled)
      return downscaled
    }

    if (DIRECT_DECODABLE_IMAGE_EXTS.has(resolved.ext) && resolved.size <= 245_000) {
      const rawB64 = fs.readFileSync(resolved.realPath).toString('base64')
      if (rawB64.length <= MAX_FALLBACK_RAW_B64_CHARS) {
        return rawB64
      }
    }
    return undefined
  } catch {
    return undefined
  }
}

export function downscaleInlineImageBase64(
  rawBase64: string,
  mimeType?: string,
): string | undefined {
  if (!rawBase64 || typeof rawBase64 !== 'string') return undefined
  let cleanB64 = rawBase64.trim()
  let effectiveMime = mimeType
  const dataUriMatch = cleanB64.match(/^data:(image\/[^;]+);base64,([\s\S]+)$/i)
  if (dataUriMatch) {
    effectiveMime = effectiveMime || dataUriMatch[1]
    cleanB64 = dataUriMatch[2]!.trim()
  }
  if (!cleanB64 || cleanB64.length > MAX_INLINE_IMAGE_B64_CHARS) return undefined

  const lowerMime = (effectiveMime ?? '').toLowerCase().trim()
  const isDirectlyDecodableMime =
    !lowerMime ||
    lowerMime === 'image/png' ||
    lowerMime === 'image/jpeg' ||
    lowerMime === 'image/jpg' ||
    lowerMime === 'image/webp' ||
    lowerMime === 'image/gif'

  if (cleanB64.length <= MAX_PREVIEW_B64_CHARS && isDirectlyDecodableMime) {
    return cleanB64
  }

  const cacheKey = crypto.createHash('sha256').update(cleanB64).digest('hex')
  const cached = readCachedThumb(cacheKey)
  if (cached) return cached

  if (process.platform === 'darwin' && fs.existsSync(SIPS_BIN)) {
    const buf = Buffer.from(cleanB64, 'base64')
    if (buf.length > 0 && buf.length <= MAX_SOURCE_IMAGE_BYTES) {
      const ext = inferTempExtFromMimeOrMagic(buf, effectiveMime)
      const tmpSrc = path.join(
        os.tmpdir(),
        `lework-sips-in-${process.pid}-${crypto.randomBytes(6).toString('hex')}${ext}`,
      )
      try {
        fs.writeFileSync(tmpSrc, buf, { mode: 0o600 })
        const downscaled = downscaleFileWithSips(tmpSrc)
        if (downscaled) {
          writeCachedThumb(cacheKey, downscaled)
          return downscaled
        }
      } catch {
        // fall through to graceful fallback
      } finally {
        try {
          fs.rmSync(tmpSrc, { force: true })
        } catch {
          // ignore cleanup failure
        }
      }
    }
  }

  return isDirectlyDecodableMime && cleanB64.length <= MAX_FALLBACK_RAW_B64_CHARS
    ? cleanB64
    : undefined
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
      (img.dataBase64.length <= MAX_PREVIEW_B64_CHARS
        ? img.dataBase64.trim()
        : downscaleInlineImageBase64(img.dataBase64, img.mimeType))
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
      const hasPersistImg = fs.existsSync(persistImg)
      const fallbackImg = hasPersistImg ? persistImg : fs.existsSync(p) ? p : null
      if (fallbackImg) {
        const allowedRoot = hasPersistImg ? persistDir : runtimeAttachmentsBaseDir()
        const preview = loadImagePreviewBase64FromFile(fallbackImg, allowedRoot)
        if (preview) {
          previews.push(preview)
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
