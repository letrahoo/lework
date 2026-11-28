import { execFileSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { MessageFileSummary, PromptFileAttachment, PromptImageAttachment } from '@lework/protocol'
import type { StagedPromptFile, StagedPromptImage } from '../engines/types.ts'
import { defaultDataDir } from '../platform/index.ts'
import type { SessionMetadataStore } from './session-metadata.ts'

export const ARCHIVED_ATTACHMENT_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000
export const MAX_ATTACHMENT_FILE_BYTES = 5 * 1024 * 1024

const SAFE_SID_RE = /^[A-Za-z0-9_-]{1,80}$/
const SAFE_IMG_FILE_RE = /^([A-Za-z0-9_.-]{1,80})\.(jpg|jpeg|png|webp|gif)$/i
const STAGED_FILE_PREFIX_RE = /^(turn_[A-Za-z0-9_-]+)_f(\d+)_(.+)$/
const STAGED_IMG_HEADER_RE = /^\[用户附加了本地图片文件，请使用[^\]]*查看：([^\]]+)\]\s*([\s\S]*)$/
const STAGED_FILE_HEADER_RE = /^\[用户附加了本地文件，请使用[^\]]*查看：([^\]]+)\]\s*([\s\S]*)$/
const MAX_PI_INLINE_FILE_BYTES = 32 * 1024
const MAX_PI_TOTAL_INLINE_BYTES = 64 * 1024
const TEXT_LIKE_FILE_EXTS = new Set([
  '.txt', '.md', '.markdown', '.json', '.jsonl', '.yaml', '.yml', '.toml', '.ini', '.conf', '.cfg',
  '.env', '.csv', '.tsv', '.xml', '.html', '.htm', '.css', '.scss', '.less', '.svg',
  '.js', '.mjs', '.cjs', '.ts', '.mts', '.cts', '.jsx', '.tsx',
  '.py', '.rb', '.go', '.rs', '.java', '.kt', '.kts', '.swift', '.c', '.h', '.cc', '.cpp', '.hpp',
  '.cs', '.php', '.sh', '.bash', '.zsh', '.fish', '.ps1', '.sql', '.graphql', '.proto',
  '.dockerfile', '.gitignore', '.properties', '.gradle', '.log', '.diff', '.patch',
])
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

export function sanitizeAttachmentFileName(rawName: string | undefined, index: number): string {
  const base = path.basename((rawName ?? '').replace(/\\/g, '/')).trim()
  const cleaned = base
    .replace(/[\x00-\x1f\x7f<>:"/\\|?*,\[\]]+/g, '_')
    .replace(/^\.+/, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (!cleaned) return `attachment_${index}.bin`
  if (cleaned.length <= 120) return cleaned
  const ext = path.extname(cleaned)
  if (ext && ext.length <= 16) {
    const stem = cleaned.slice(0, Math.max(1, 120 - ext.length))
    return `${stem}${ext}`
  }
  return cleaned.slice(0, 120)
}

export function stagePromptFilesForSession(
  sessionId: string,
  turnId: string,
  files: PromptFileAttachment[],
): StagedPromptFile[] {
  if (files.length === 0) return []
  const safeSid = safeAttachmentSessionKey(sessionId)
  const runtimeDir = path.join(runtimeAttachmentsBaseDir(), safeSid)
  const persistDir = path.join(persistentAttachmentsBaseDir(), safeSid)
  ensurePrivateOwnerDir(runtimeDir)
  ensurePrivateOwnerDir(persistDir)

  const out: StagedPromptFile[] = []
  for (let i = 0; i < files.length; i++) {
    const file = files[i]!
    const bytes = Buffer.from(file.dataBase64, 'base64')
    if (bytes.length > MAX_ATTACHMENT_FILE_BYTES) {
      throw new Error(`文件「${file.fileName}」超过 5 MB 上限`)
    }
    const cleanName = sanitizeAttachmentFileName(file.fileName, i + 1)
    const stagedFileName = `${turnId}_f${i + 1}_${cleanName}`
    const runtimePath = path.join(runtimeDir, stagedFileName)
    const persistPath = path.join(persistDir, stagedFileName)
    fs.writeFileSync(runtimePath, bytes, { mode: 0o600 })
    fs.writeFileSync(persistPath, bytes, { mode: 0o600 })

    out.push({
      ...file,
      fileName: cleanName,
      sizeBytes: bytes.length,
      stagedPath: runtimePath,
    })
  }
  return out
}

export function formatDisplayAttachmentSummary(imageCount: number, fileCount: number): string {
  if (imageCount > 0 && fileCount > 0) {
    return `（已发送 ${imageCount} 张图片、${fileCount} 个文件）`
  }
  if (imageCount > 0) {
    return `（已发送 ${imageCount} 张图片）`
  }
  if (fileCount > 0) {
    return `（已发送 ${fileCount} 个文件）`
  }
  return ''
}

export function buildStagedFilesPromptHeader(stagedFiles: StagedPromptFile[]): string {
  if (stagedFiles.length === 0) return ''
  const pathsCsv = stagedFiles.map(f => f.stagedPath).join(', ')
  return `[用户附加了本地文件，请使用文件读取工具查看：${pathsCsv}]`
}

export function buildPromptTextWithAttachments(
  rawUserText: string,
  stagedFiles?: StagedPromptFile[],
  hasImages = false,
): string {
  const cleanUser = rawUserText.trim()
  const files = stagedFiles ?? []
  const fileHeader = buildStagedFilesPromptHeader(files)
  const fallback =
    cleanUser ||
    (hasImages && files.length > 0
      ? '请结合所附图片和文件进行分析。'
      : files.length > 0
        ? '请结合所附文件进行分析。'
        : hasImages
          ? '请结合所附图片进行分析。'
          : '')
  return fileHeader ? `${fileHeader}\n\n${fallback}` : fallback
}

export function isTextLikeAttachment(fileName: string, mimeType?: string): boolean {
  const lowerMime = (mimeType ?? '').toLowerCase().trim()
  if (
    lowerMime.startsWith('text/') ||
    lowerMime === 'application/json' ||
    lowerMime === 'application/xml' ||
    lowerMime === 'application/javascript' ||
    lowerMime === 'application/typescript' ||
    lowerMime === 'application/x-yaml' ||
    lowerMime === 'application/yaml' ||
    lowerMime === 'application/x-sh' ||
    lowerMime === 'image/svg+xml'
  ) {
    return true
  }
  const ext = path.extname(fileName).toLowerCase()
  return TEXT_LIKE_FILE_EXTS.has(ext)
}

export function buildPiFileContextBlock(stagedFiles: StagedPromptFile[]): string {
  if (stagedFiles.length === 0) return ''
  const lines: string[] = [buildStagedFilesPromptHeader(stagedFiles)]
  let remainingBudget = MAX_PI_TOTAL_INLINE_BYTES
  for (const file of stagedFiles) {
    if (remainingBudget <= 0 || !isTextLikeAttachment(file.fileName, file.mimeType)) continue
    try {
      const buf = Buffer.from(file.dataBase64, 'base64')
      if (buf.includes(0)) continue // skip binary payload mislabeled as text
      const sliceLen = Math.min(buf.length, MAX_PI_INLINE_FILE_BYTES, remainingBudget)
      const content = buf.subarray(0, sliceLen).toString('utf8')
      remainingBudget -= sliceLen
      const truncatedNote = sliceLen < buf.length ? `\n...（文件共 ${buf.length} 字节，已节选前 ${sliceLen} 字节）` : ''
      lines.push(`\n--- 附件文件：${file.fileName} (${file.stagedPath}) ---\n${content}${truncatedNote}`)
    } catch {
      // ignore decode error
    }
  }
  return lines.join('\n')
}

export function buildTerminalImagePrompt(
  stagedImages: StagedPromptImage[],
  rawUserText: string,
  stagedFiles: StagedPromptFile[] = [],
): string {
  const cleanUser = rawUserText.trim()
  if (stagedImages.length === 0 && stagedFiles.length === 0) return cleanUser
  const headers: string[] = []
  if (stagedImages.length > 0) {
    const pathsCsv = stagedImages.map(i => i.stagedPath).join(', ')
    headers.push(`[用户附加了本地图片文件，请使用 read_file 工具查看：${pathsCsv}]`)
  }
  if (stagedFiles.length > 0) {
    headers.push(buildStagedFilesPromptHeader(stagedFiles))
  }
  const fallback =
    cleanUser ||
    (stagedImages.length > 0 && stagedFiles.length > 0
      ? '请结合所附图片和文件进行分析。'
      : stagedFiles.length > 0
        ? '请结合所附文件进行分析。'
        : '请结合所附图片进行分析。')
  return `${headers.join('\n')}\n\n${fallback}`
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
 * and/or `[用户附加了本地文件，请使用...查看：...]` (written into AGY `transcript.jsonl`,
 * Claude `.jsonl`, or Codex history), returning clean display text, image count/previews,
 * and file summaries (while re-materializing `/tmp` files if needed).
 */
export function parseStagedImagePromptHeader(
  rawText: string,
  loadThumbnails = true,
): {
  cleanText: string
  imageCount: number
  imagePreviews?: string[]
  files?: MessageFileSummary[]
} {
  let working = rawText
  const rawImagePaths: string[] = []
  const rawFilePaths: string[] = []

  // Strip up to 2 leading attachment headers (image header + file header in either order)
  for (let pass = 0; pass < 2; pass++) {
    const imgMatch = working.match(STAGED_IMG_HEADER_RE)
    if (imgMatch) {
      const paths = (imgMatch[1] ?? '')
        .split(',')
        .map(p => p.trim())
        .filter(Boolean)
      for (const p of paths) {
        if (rawImagePaths.length < 4) rawImagePaths.push(p)
      }
      working = (imgMatch[2] ?? '').trim()
      continue
    }
    const fileMatch = working.match(STAGED_FILE_HEADER_RE)
    if (fileMatch) {
      const paths = (fileMatch[1] ?? '')
        .split(',')
        .map(p => p.trim())
        .filter(Boolean)
      for (const p of paths) {
        if (rawFilePaths.length < 4) rawFilePaths.push(p)
      }
      working = (fileMatch[2] ?? '').trim()
      continue
    }
    break
  }

  if (rawImagePaths.length === 0 && rawFilePaths.length === 0) {
    return { cleanText: rawText, imageCount: 0 }
  }

  const remainder = working.trim()
  const autoFallback =
    remainder === '请结合所附图片进行分析。' ||
    remainder === '请结合所附文件进行分析。' ||
    remainder === '请结合所附图片和文件进行分析。' ||
    remainder === '请分析所附图片。'
  const summaryFallback = formatDisplayAttachmentSummary(rawImagePaths.length, rawFilePaths.length)
  const cleanText = autoFallback ? summaryFallback : remainder || summaryFallback

  const parsedFiles: MessageFileSummary[] = []
  for (const p of rawFilePaths) {
    const baseName = path.basename(p)
    const safeSid = path.basename(path.dirname(p))
    const prefixMatch = baseName.match(STAGED_FILE_PREFIX_RE)
    const displayFileName = prefixMatch ? prefixMatch[3]! : baseName
    let sizeBytes: number | undefined
    if (SAFE_SID_RE.test(safeSid) && p.includes('lework-attachments')) {
      const persistDir = path.join(defaultDataDir(), 'attachments', safeSid)
      const persistFile = path.join(persistDir, baseName)
      if (!fs.existsSync(p) && fs.existsSync(persistFile)) {
        try {
          const rtDir = path.join(runtimeAttachmentsBaseDir(), safeSid)
          ensurePrivateOwnerDir(rtDir)
          fs.copyFileSync(persistFile, path.join(rtDir, baseName))
          fs.chmodSync(path.join(rtDir, baseName), 0o600)
        } catch {
          // best effort
        }
      }
      try {
        const target = fs.existsSync(p) ? p : fs.existsSync(persistFile) ? persistFile : null
        if (target) {
          const st = fs.statSync(target)
          if (st.isFile()) sizeBytes = st.size
        }
      } catch {
        // ignore stat failure
      }
    }
    parsedFiles.push({
      fileName: displayFileName.slice(0, 180) || 'file',
      ...(sizeBytes !== undefined ? { sizeBytes } : {}),
      stagedPath: p,
    })
  }

  if (!loadThumbnails || rawImagePaths.length === 0) {
    return {
      cleanText,
      imageCount: rawImagePaths.length,
      ...(parsedFiles.length > 0 ? { files: parsedFiles } : {}),
    }
  }

  const previews: string[] = []
  for (const p of rawImagePaths) {
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
    imageCount: rawImagePaths.length,
    ...(previews.length > 0 ? { imagePreviews: previews } : {}),
    ...(parsedFiles.length > 0 ? { files: parsedFiles } : {}),
  }
}
