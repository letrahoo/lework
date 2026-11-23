import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  ARCHIVED_ATTACHMENT_RETENTION_MS,
  buildTerminalImagePrompt,
  parseStagedImagePromptHeader,
  persistentAttachmentsBaseDir,
  removeSessionAttachments,
  runtimeAttachmentsBaseDir,
  safeAttachmentSessionKey,
  stagePromptImagesForSession,
  sweepExpiredArchivedAttachments,
} from '../src/storage/attachment-store.ts'
import { SessionMetadataStore } from '../src/storage/session-metadata.ts'

const TINY_PNG_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='

describe('Direct Local Attachment Staging, Thumbnail Recovery & 7-Day Archived-Only Cleanup', () => {
  let tmpHome = ''
  const prevLeworkHome = process.env.LEWORK_HOME
  const testSids = [
    'host:agy:active_long_lived_test',
    'host:agy:archived_recent_test',
    'host:agy:archived_expired_test',
  ]

  beforeEach(() => {
    tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-attach-test-'))
    process.env.LEWORK_HOME = tmpHome
  })

  afterEach(() => {
    for (const sid of testSids) {
      removeSessionAttachments(sid)
    }
    if (prevLeworkHome === undefined) {
      delete process.env.LEWORK_HOME
    } else {
      process.env.LEWORK_HOME = prevLeworkHome
    }
    if (tmpHome) {
      fs.rmSync(tmpHome, { recursive: true, force: true })
    }
  })

  it('stages images into /tmp/lework-attachments-<uid> + persistent backup, recovers thumbnails, and re-materializes after /tmp wipe', () => {
    const sid = testSids[0]!
    const staged = stagePromptImagesForSession(sid, 'turn_t1', [
      {
        mimeType: 'image/png',
        dataBase64: TINY_PNG_B64,
        thumbnailBase64: TINY_PNG_B64,
        fileName: 'screen_err.png',
      },
    ])

    expect(staged).toHaveLength(1)
    expect(staged[0]!.stagedPath).toContain('lework-attachments-')
    expect(fs.existsSync(staged[0]!.stagedPath)).toBe(true)

    const safeKey = safeAttachmentSessionKey(sid)
    const persistImg = path.join(persistentAttachmentsBaseDir(), safeKey, 'turn_t1_1.png')
    expect(fs.existsSync(persistImg)).toBe(true)

    const terminalPrompt = buildTerminalImagePrompt(staged, '请帮我看下这张截图里的报错')
    expect(terminalPrompt).toContain(staged[0]!.stagedPath)

    // Simulate OS reboot wiping `/tmp/lework-attachments-<uid>/<safeKey>/turn_t1_1.png` on an active session
    fs.rmSync(staged[0]!.stagedPath, { force: true })
    expect(fs.existsSync(staged[0]!.stagedPath)).toBe(false)

    const parsed = parseStagedImagePromptHeader(terminalPrompt, true)
    expect(parsed.cleanText).toBe('请帮我看下这张截图里的报错')
    expect(parsed.imageCount).toBe(1)
    expect(parsed.imagePreviews).toEqual([TINY_PNG_B64])
    // Active conversation runtime file is automatically re-materialized from persistent store!
    expect(fs.existsSync(staged[0]!.stagedPath)).toBe(true)
  })

  it('cleans up attachments ONLY for sessions archived >= 7 days while keeping active & recently-archived sessions untouched', () => {
    const activeSid = testSids[0]!
    const recentArchivedSid = testSids[1]!
    const expiredArchivedSid = testSids[2]!

    const sActive = stagePromptImagesForSession(activeSid, 'turn_a', [
      { mimeType: 'image/png', dataBase64: TINY_PNG_B64 },
    ])
    const sRecent = stagePromptImagesForSession(recentArchivedSid, 'turn_r', [
      { mimeType: 'image/png', dataBase64: TINY_PNG_B64 },
    ])
    const sExpired = stagePromptImagesForSession(expiredArchivedSid, 'turn_e', [
      { mimeType: 'image/png', dataBase64: TINY_PNG_B64 },
    ])

    const metaFile = path.join(tmpHome, 'session-meta.json')
    const now = Date.now()
    const twoDaysAgo = now - 2 * 24 * 60 * 60 * 1_000
    const eightDaysAgo = now - (ARCHIVED_ATTACHMENT_RETENTION_MS + 24 * 60 * 60 * 1_000)

    fs.writeFileSync(
      metaFile,
      JSON.stringify({
        version: 1,
        records: {
          [activeSid]: {
            customTitle: '长期活跃会话（30天前创建但未归档）',
            updatedAt: now - 30 * 24 * 60 * 60 * 1_000,
          },
          [recentArchivedSid]: {
            archived: true,
            archivedAt: twoDaysAgo,
            updatedAt: twoDaysAgo,
          },
          [expiredArchivedSid]: {
            archived: true,
            archivedAt: eightDaysAgo,
            updatedAt: eightDaysAgo,
          },
        },
      }),
    )

    const metaStore = new SessionMetadataStore(metaFile)
    const swept = sweepExpiredArchivedAttachments(metaStore, ARCHIVED_ATTACHMENT_RETENTION_MS, now)
    expect(swept).toBe(1)

    // 1. Active conversation is NEVER cleaned even if 30 days old!
    expect(fs.existsSync(sActive[0]!.stagedPath)).toBe(true)
    expect(
      fs.existsSync(path.join(persistentAttachmentsBaseDir(), safeAttachmentSessionKey(activeSid))),
    ).toBe(true)

    // 2. Recently archived conversation (2 days < 7 days) is NOT cleaned yet!
    expect(fs.existsSync(sRecent[0]!.stagedPath)).toBe(true)

    // 3. Expired archived conversation (8 days >= 7 days) HAS its attachments cleaned in both /tmp and ~/.lework,
    //    while its archived session metadata record remains preserved!
    expect(fs.existsSync(sExpired[0]!.stagedPath)).toBe(false)
    expect(
      fs.existsSync(
        path.join(persistentAttachmentsBaseDir(), safeAttachmentSessionKey(expiredArchivedSid)),
      ),
    ).toBe(false)
    expect(
      fs.existsSync(path.join(runtimeAttachmentsBaseDir(), safeAttachmentSessionKey(expiredArchivedSid))),
    ).toBe(false)
    expect(metaStore.get(expiredArchivedSid)?.archived).toBe(true)
  })
})
