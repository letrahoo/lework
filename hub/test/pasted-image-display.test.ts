import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import zlib from 'node:zlib'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  buildTerminalImagePrompt,
  downscaleInlineImageBase64,
  loadImagePreviewBase64FromFile,
  parseStagedImagePromptHeader,
  persistentAttachmentsBaseDir,
  removeSessionAttachments,
  safeAttachmentSessionKey,
  stagePromptImagesForSession,
} from '../src/storage/attachment-store.ts'
import { HostTaskInspector } from '../src/storage/host-history.ts'
import { parseAgyTranscript } from '../src/storage/host-history/agy-history.ts'
import { parseClaudeTranscript } from '../src/storage/host-history/claude-history.ts'
import { parseCodexTranscript } from '../src/storage/host-history/codex-history.ts'

const TINY_PNG_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
const TINY_PNG_BYTES = Buffer.from(TINY_PNG_B64, 'base64')
const HAS_MACOS_SIPS = process.platform === 'darwin' && fs.existsSync('/usr/bin/sips')

function crc32(buf: Buffer): number {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i]!
    for (let k = 0; k < 8; k++) {
      c = (c >>> 1) ^ (0xedb88320 & -(c & 1))
    }
  }
  return (c ^ 0xffffffff) >>> 0
}

function makePngChunk(type: string, data: Buffer): Buffer {
  const lenBuf = Buffer.alloc(4)
  lenBuf.writeUInt32BE(data.length, 0)
  const typeBuf = Buffer.from(type, 'ascii')
  const crcBuf = Buffer.alloc(4)
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0)
  return Buffer.concat([lenBuf, typeBuf, data, crcBuf])
}

/**
 * Generates a valid RGB PNG > 260KB raw (> 350KB base64, realistic gradient UI screenshot)
 * so macOS `sips -Z 640` genuinely decodes and downscales it into a compact JPEG thumbnail.
 */
function generateLargeValidPng(width = 960, height = 600): Buffer {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 2 // RGB color type
  ihdr[10] = 0
  ihdr[11] = 0
  ihdr[12] = 0

  const stride = 1 + width * 3
  const raw = Buffer.alloc(stride * height)
  for (let y = 0; y < height; y++) {
    const rowStart = y * stride
    raw[rowStart] = 0 // filter none
    const g = Math.floor((y * 255) / height)
    for (let x = 0; x < width; x++) {
      const px = rowStart + 1 + x * 3
      raw[px] = Math.floor((x * 255) / width)
      raw[px + 1] = g
      raw[px + 2] = ((x >> 4) ^ (y >> 4)) & 1 ? 80 : 40
    }
  }
  // Level 0 (stored valid DEFLATE stream) keeps raw PNG > 1.7MB like an uncompressed Retina screenshot
  const compressed = zlib.deflateSync(raw, { level: 0 })
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    makePngChunk('IHDR', ihdr),
    makePngChunk('IDAT', compressed),
    makePngChunk('IEND', Buffer.alloc(0)),
  ])
}

describe('Pasted & Attached Images Full-Stack Hub Parsing & Thumbnail Cache', () => {
  const tempDirs: string[] = []
  const stagedSids: string[] = []
  let prevLeworkHome: string | undefined

  function makeTempDir(prefix: string): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
    tempDirs.push(dir)
    return dir
  }

  beforeEach(() => {
    prevLeworkHome = process.env.LEWORK_HOME
    process.env.LEWORK_HOME = makeTempDir('lework-img-home-')
  })

  afterEach(() => {
    for (const sid of stagedSids.splice(0)) {
      removeSessionAttachments(sid)
    }
    if (prevLeworkHome === undefined) {
      delete process.env.LEWORK_HOME
    } else {
      process.env.LEWORK_HOME = prevLeworkHome
    }
    for (const dir of tempDirs.splice(0)) {
      fs.rmSync(dir, { recursive: true, force: true })
    }
  })

  it('enforces containment and generates cached thumbnails in loadImagePreviewBase64FromFile and parseStagedImagePromptHeader', () => {
    const sandboxRoot = makeTempDir('lework-contain-root-')
    const outsideDir = makeTempDir('lework-contain-outside-')
    const insideImg = path.join(sandboxRoot, 'sub', 'small.png')
    const insideTxt = path.join(sandboxRoot, 'sub', 'notes.txt')
    const outsideImg = path.join(outsideDir, 'secret.png')
    const symlinkEscape = path.join(sandboxRoot, 'escape.png')

    fs.mkdirSync(path.dirname(insideImg), { recursive: true })
    fs.writeFileSync(insideImg, TINY_PNG_BYTES)
    fs.writeFileSync(insideTxt, 'not an image')
    fs.writeFileSync(outsideImg, TINY_PNG_BYTES)
    fs.symlinkSync(outsideImg, symlinkEscape)

    // 1. Valid small image inside root succeeds directly
    expect(loadImagePreviewBase64FromFile(insideImg, sandboxRoot)).toBe(TINY_PNG_B64)

    // 2. Non-image extension, ../ escape, outside direct path, and symlink escape are ALL blocked
    expect(loadImagePreviewBase64FromFile(insideTxt, sandboxRoot)).toBeUndefined()
    expect(loadImagePreviewBase64FromFile(outsideImg, sandboxRoot)).toBeUndefined()
    expect(
      loadImagePreviewBase64FromFile(
        path.join(sandboxRoot, '..', path.basename(outsideDir), 'secret.png'),
        sandboxRoot,
      ),
    ).toBeUndefined()
    expect(loadImagePreviewBase64FromFile(symlinkEscape, sandboxRoot)).toBeUndefined()

    // 3. Large >210KB PNG downscales via sips on macOS, writes _thumb_cache, and recovers when .thumb.b64 is missing
    const largePng = generateLargeValidPng()
    expect(largePng.length).toBeGreaterThan(210_000)
    const largeImgPath = path.join(sandboxRoot, 'retina_shot.png')
    fs.writeFileSync(largeImgPath, largePng)

    const preview1 = loadImagePreviewBase64FromFile(largeImgPath, sandboxRoot)
    if (HAS_MACOS_SIPS) {
      expect(preview1).toBeDefined()
      const decoded = Buffer.from(preview1!, 'base64')
      // JPEG SOI magic bytes FF D8 FF
      expect(decoded[0]).toBe(0xff)
      expect(decoded[1]).toBe(0xd8)
      expect(decoded[2]).toBe(0xff)
      expect(decoded.length).toBeLessThanOrEqual(260_000)

      // Verify persistent _thumb_cache entry exists and repeat call hits cache cleanly
      const cacheDir = path.join(persistentAttachmentsBaseDir(), '_thumb_cache')
      expect(fs.readdirSync(cacheDir).some(f => f.endsWith('.b64'))).toBe(true)
      const preview2 = loadImagePreviewBase64FromFile(largeImgPath, sandboxRoot)
      expect(preview2).toBe(preview1)

      // Also verify parseStagedImagePromptHeader recovers thumbnail when .thumb.b64 is absent
      const sid = 'host:agy:large_staged_fallback'
      stagedSids.push(sid)
      const staged = stagePromptImagesForSession(sid, 'turn_big', [
        {
          mimeType: 'image/png',
          dataBase64: largePng.toString('base64'),
          fileName: 'retina_shot.png',
        },
      ])
      const thumbPath = path.join(
        persistentAttachmentsBaseDir(),
        safeAttachmentSessionKey(sid),
        'turn_big_1.thumb.b64',
      )
      fs.rmSync(thumbPath, { force: true })
      const promptText = buildTerminalImagePrompt(staged, '分析这张高清截图')
      const parsed = parseStagedImagePromptHeader(promptText, true)
      expect(parsed.cleanText).toBe('分析这张高清截图')
      expect(parsed.imageCount).toBe(1)
      expect(parsed.imagePreviews).toHaveLength(1)
      expect(Buffer.from(parsed.imagePreviews![0]!, 'base64')[0]).toBe(0xff)
    }
  })

  it('parses AGY native o.media & ADDITIONAL_METADATA images (including image-only turns) while blocking path traversal', () => {
    const fakeHome = makeTempDir('lework-agy-img-')
    const convId = '12345678-aaaa-4bbb-8ccc-000000000001'
    const convBrainDir = path.join(fakeHome, '.gemini', 'antigravity-cli', 'brain', convId)
    const uploadDir = path.join(convBrainDir, '.user_uploaded')
    const logsDir = path.join(convBrainDir, '.system_generated', 'logs')
    fs.mkdirSync(uploadDir, { recursive: true })
    fs.mkdirSync(logsDir, { recursive: true })

    const uploaded1 = path.join(uploadDir, 'uploaded_media_1.png')
    const uploaded2 = path.join(uploadDir, 'uploaded_media_2.png')
    fs.writeFileSync(uploaded1, TINY_PNG_BYTES)
    fs.writeFileSync(uploaded2, TINY_PNG_BYTES)

    // Outside file that MUST NEVER be read even if referenced via o.media or ../ traversal
    const outsideSecret = path.join(fakeHome, 'outside_secret.png')
    fs.writeFileSync(outsideSecret, TINY_PNG_BYTES)

    const transcriptPath = path.join(logsDir, 'transcript.jsonl')
    const lines = [
      // Turn 1: text + native o.media
      JSON.stringify({
        step_index: 0,
        type: 'USER_INPUT',
        created_at: '2026-11-23T10:00:00Z',
        content: `<USER_REQUEST>\n帮我看下这张终端截图\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\nThe user has uploaded 1 image(s):\n- ${uploaded1}\n</ADDITIONAL_METADATA>`,
        media: [{ mime_type: 'image/png', uri: uploaded1 }],
      }),
      // Turn 2: image-only message (empty <USER_REQUEST>) relying on ADDITIONAL_METADATA fallback
      JSON.stringify({
        step_index: 1,
        type: 'USER_INPUT',
        created_at: '2026-11-23T10:01:00Z',
        content: `<USER_REQUEST>\n\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\nThe user has uploaded 1 image(s):\n- ${uploaded2}\n</ADDITIONAL_METADATA>`,
      }),
      // Turn 3: malicious path traversal outside session brain directory
      JSON.stringify({
        step_index: 2,
        type: 'USER_INPUT',
        created_at: '2026-11-23T10:02:00Z',
        content: '<USER_REQUEST>\n尝试越权读取外部图片\n</USER_REQUEST>',
        media: [
          { mime_type: 'image/png', uri: outsideSecret },
          {
            mime_type: 'image/png',
            uri: path.join(convBrainDir, '..', '..', '..', '..', 'outside_secret.png'),
          },
        ],
      }),
      JSON.stringify({
        step_index: 3,
        type: 'PLANNER_RESPONSE',
        status: 'DONE',
        created_at: '2026-11-23T10:02:05Z',
        content: '已查看完毕。',
      }),
    ]
    fs.writeFileSync(transcriptPath, lines.join('\n') + '\n')

    const parsed = parseAgyTranscript(`host:agy:${convId}`, transcriptPath)
    const userEvents = parsed.events.filter(
      (e): e is Extract<(typeof parsed.events)[number], { type: 'message.user' }> =>
        e.type === 'message.user',
    )

    expect(userEvents).toHaveLength(3)

    // Turn 1: text + native preview
    expect(userEvents[0]!.text).toBe('帮我看下这张终端截图')
    expect(userEvents[0]!.imageCount).toBe(1)
    expect(userEvents[0]!.imagePreviews).toEqual([TINY_PNG_B64])

    // Turn 2: image-only turn still emitted with fallback text + preview
    expect(userEvents[1]!.text).toBe('（已发送 1 张图片）')
    expect(userEvents[1]!.imageCount).toBe(1)
    expect(userEvents[1]!.imagePreviews).toEqual([TINY_PNG_B64])

    // Turn 3: outside path traversal rejected completely
    expect(userEvents[2]!.text).toBe('尝试越权读取外部图片')
    expect(userEvents[2]!.imageCount).toBeUndefined()
    expect(userEvents[2]!.imagePreviews).toBeUndefined()
  })

  it('downscales oversized (>260KB) Claude inline base64 images while keeping small inline images intact', () => {
    const fakeHome = makeTempDir('lework-claude-img-')
    const projDir = path.join(fakeHome, '.claude', 'projects', '-tmp-demo')
    fs.mkdirSync(projDir, { recursive: true })
    const largePngB64 = generateLargeValidPng().toString('base64')
    expect(largePngB64.length).toBeGreaterThan(260_000)

    const sessionFile = path.join(projDir, 'claude-img-sess.jsonl')
    fs.writeFileSync(
      sessionFile,
      [
        JSON.stringify({
          type: 'user',
          uuid: 'u_small',
          timestamp: '2026-11-23T11:00:00Z',
          message: {
            role: 'user',
            content: [
              { type: 'image', source: { type: 'base64', media_type: 'image/png', data: TINY_PNG_B64 } },
              { type: 'text', text: '小图测试' },
            ],
          },
        }),
        JSON.stringify({
          type: 'user',
          uuid: 'u_large',
          timestamp: '2026-11-23T11:01:00Z',
          message: {
            role: 'user',
            content: [
              { type: 'image', source: { type: 'base64', media_type: 'image/png', data: largePngB64 } },
              { type: 'text', text: 'Retina 截图排查' },
            ],
          },
        }),
      ].join('\n') + '\n',
    )

    const parsed = parseClaudeTranscript('host:claude:claude-img-sess', sessionFile)
    const userEvents = parsed.events.filter(
      (e): e is Extract<(typeof parsed.events)[number], { type: 'message.user' }> =>
        e.type === 'message.user',
    )
    expect(userEvents).toHaveLength(2)

    expect(userEvents[0]!.text).toBe('小图测试')
    expect(userEvents[0]!.imageCount).toBe(1)
    expect(userEvents[0]!.imagePreviews).toEqual([TINY_PNG_B64])

    expect(userEvents[1]!.text).toBe('Retina 截图排查')
    expect(userEvents[1]!.imageCount).toBe(1)
    if (HAS_MACOS_SIPS) {
      expect(userEvents[1]!.imagePreviews).toHaveLength(1)
      const thumbBytes = Buffer.from(userEvents[1]!.imagePreviews![0]!, 'base64')
      expect(thumbBytes[0]).toBe(0xff)
      expect(thumbBytes[1]).toBe(0xd8)
      // Verify inline sha256 cache file was created
      const expectedCache = path.join(
        persistentAttachmentsBaseDir(),
        '_thumb_cache',
        `${crypto.createHash('sha256').update(largePngB64).digest('hex')}.b64`,
      )
      expect(fs.existsSync(expectedCache)).toBe(true)
      expect(downscaleInlineImageBase64(largePngB64, 'image/png')).toBe(userEvents[1]!.imagePreviews![0])
    }
  })

  it('parses Codex inline input_image (text + image-only) and Lework staged image headers', () => {
    const fakeHome = makeTempDir('lework-codex-img-')
    const codexSessDir = path.join(fakeHome, '.codex', 'sessions', '2026', '11', '23')
    fs.mkdirSync(codexSessDir, { recursive: true })

    const sid = 'host:codex:11111111-2222-3333-4444-555555555555'
    stagedSids.push(sid)
    const staged = stagePromptImagesForSession(sid, 'turn_cx1', [
      {
        mimeType: 'image/png',
        dataBase64: TINY_PNG_B64,
        thumbnailBase64: TINY_PNG_B64,
        fileName: 'codex_staged.png',
      },
    ])
    const stagedHeaderPrompt = buildTerminalImagePrompt(staged, '请结合截图定位 Codex 构建问题')

    const rolloutFile = path.join(
      codexSessDir,
      'rollout-2026-11-23-11111111-2222-3333-4444-555555555555.jsonl',
    )
    fs.writeFileSync(
      rolloutFile,
      [
        JSON.stringify({
          timestamp: '2026-11-23T12:00:00Z',
          type: 'response_item',
          payload: {
            type: 'message',
            id: 'msg_cx_1',
            role: 'user',
            content: [
              { type: 'input_text', text: '看看这个报错图' },
              { type: 'input_image', image_url: `data:image/png;base64,${TINY_PNG_B64}` },
            ],
          },
        }),
        JSON.stringify({
          timestamp: '2026-11-23T12:01:00Z',
          type: 'response_item',
          payload: {
            type: 'message',
            id: 'msg_cx_2',
            role: 'user',
            content: [
              { type: 'input_image', image_url: { url: `data:image/png;base64,${TINY_PNG_B64}` } },
            ],
          },
        }),
        JSON.stringify({
          timestamp: '2026-11-23T12:02:00Z',
          type: 'response_item',
          payload: {
            type: 'message',
            id: 'msg_cx_3',
            role: 'user',
            content: [{ type: 'input_text', text: stagedHeaderPrompt }],
          },
        }),
      ].join('\n') + '\n',
    )

    const parsed = parseCodexTranscript(sid, rolloutFile)
    const userEvents = parsed.events.filter(
      (e): e is Extract<(typeof parsed.events)[number], { type: 'message.user' }> =>
        e.type === 'message.user',
    )
    expect(userEvents).toHaveLength(3)

    // 1. Inline input_image + text
    expect(userEvents[0]!.text).toBe('看看这个报错图')
    expect(userEvents[0]!.imageCount).toBe(1)
    expect(userEvents[0]!.imagePreviews).toEqual([TINY_PNG_B64])

    // 2. Image-only input_image with nested { url }
    expect(userEvents[1]!.text).toBe('（已发送 1 张图片）')
    expect(userEvents[1]!.imageCount).toBe(1)
    expect(userEvents[1]!.imagePreviews).toEqual([TINY_PNG_B64])

    // 3. Staged image prompt header cleaned & thumbnail recovered
    expect(userEvents[2]!.text).toBe('请结合截图定位 Codex 构建问题')
    expect(userEvents[2]!.imageCount).toBe(1)
    expect(userEvents[2]!.imagePreviews).toEqual([TINY_PNG_B64])

    // Also verify end-to-end discovery through HostTaskInspector for Codex
    const inspector = new HostTaskInspector({ homeDir: fakeHome })
    const loaded = inspector.readHostSessionEvents(sid)
    expect(loaded?.events.filter(e => e.type === 'message.user')).toHaveLength(3)
  })
})
