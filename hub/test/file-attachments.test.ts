import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { EngineAdapter, EngineSession, StagedPromptFile, StagedPromptImage } from '../src/engines/types.ts'
import { SessionManager } from '../src/session-manager.ts'
import {
  buildPiFileContextBlock,
  buildPromptTextWithAttachments,
  buildTerminalImagePrompt,
  parseStagedImagePromptHeader,
  removeSessionAttachments,
  sanitizeAttachmentFileName,
  stagePromptFilesForSession,
  stagePromptImagesForSession,
} from '../src/storage/attachment-store.ts'

describe('Hub file attachment staging, parsing & session dispatch', () => {
  const prevHome = process.env.LEWORK_HOME
  let tmpHome = ''
  const cleanupSids: string[] = []

  beforeEach(() => {
    tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-file-attach-test-'))
    process.env.LEWORK_HOME = tmpHome
    cleanupSids.length = 0
  })

  afterEach(() => {
    for (const sid of cleanupSids) {
      removeSessionAttachments(sid)
    }
    if (prevHome === undefined) {
      delete process.env.LEWORK_HOME
    } else {
      process.env.LEWORK_HOME = prevHome
    }
    fs.rmSync(tmpHome, { recursive: true, force: true })
  })

  it('sanitizes unsafe filenames and stages files to both runtime and persistent roots', () => {
    expect(sanitizeAttachmentFileName('../../etc/passwd', 1)).toBe('passwd')
    expect(sanitizeAttachmentFileName('my [report],v2.pdf', 1)).toBe('my _report_v2.pdf')
    expect(sanitizeAttachmentFileName('...', 2)).toBe('attachment_2.bin')

    const sid = `sess_stage_${Date.now()}`
    cleanupSids.push(sid)
    const content = 'hello world from attached markdown'
    const staged = stagePromptFilesForSession(sid, 'turn_abc', [
      {
        fileName: 'design-doc.md',
        mimeType: 'text/markdown',
        dataBase64: Buffer.from(content, 'utf8').toString('base64'),
        sizeBytes: Buffer.byteLength(content, 'utf8'),
      },
    ])

    expect(staged).toHaveLength(1)
    expect(staged[0]!.fileName).toBe('design-doc.md')
    expect(staged[0]!.sizeBytes).toBe(Buffer.byteLength(content, 'utf8'))
    expect(fs.readFileSync(staged[0]!.stagedPath, 'utf8')).toBe(content)

    // Removing runtime copy and parsing header re-materializes from persistent dir
    fs.rmSync(staged[0]!.stagedPath, { force: true })
    expect(fs.existsSync(staged[0]!.stagedPath)).toBe(false)

    const promptText = buildPromptTextWithAttachments('请帮我总结这份文档', staged, false)
    const parsed = parseStagedImagePromptHeader(promptText, true)
    expect(parsed.cleanText).toBe('请帮我总结这份文档')
    expect(parsed.imageCount).toBe(0)
    expect(parsed.files).toHaveLength(1)
    expect(parsed.files?.[0]?.fileName).toBe('design-doc.md')
    expect(parsed.files?.[0]?.sizeBytes).toBe(Buffer.byteLength(content, 'utf8'))
    expect(fs.existsSync(staged[0]!.stagedPath)).toBe(true)
  })

  it('round-trips combined image + file terminal headers and builds Pi text file context', () => {
    const sid = `sess_combo_${Date.now()}`
    cleanupSids.push(sid)
    const tinyPng =
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
    const stagedImgs = stagePromptImagesForSession(sid, 'turn_mix', [
      { mimeType: 'image/png', dataBase64: tinyPng, fileName: 'ui.png' },
    ])
    const stagedFiles = stagePromptFilesForSession(sid, 'turn_mix', [
      {
        fileName: 'config.json',
        mimeType: 'application/json',
        dataBase64: Buffer.from('{"ok":true}', 'utf8').toString('base64'),
      },
      {
        fileName: 'archive.bin',
        mimeType: 'application/octet-stream',
        dataBase64: Buffer.from([0x00, 0x01, 0x02, 0xff]).toString('base64'),
      },
    ])

    const terminalText = buildTerminalImagePrompt(stagedImgs, '', stagedFiles)
    const parsed = parseStagedImagePromptHeader(terminalText, true)
    expect(parsed.cleanText).toBe('（已发送 1 张图片、2 个文件）')
    expect(parsed.imageCount).toBe(1)
    expect(parsed.files?.map(f => f.fileName)).toEqual(['config.json', 'archive.bin'])

    const piBlock = buildPiFileContextBlock(stagedFiles)
    expect(piBlock).toContain('config.json')
    expect(piBlock).toContain('{"ok":true}')
    expect(piBlock).not.toContain('--- 附件文件：archive.bin')
  })

  it('dispatches file-only prompts through SessionManager and emits message.user file summaries', async () => {
    const received: Array<{ text: string; images?: StagedPromptImage[]; files?: StagedPromptFile[] }> = []
    const emittedEvents: Array<{ sessionId: string; type: string; files?: unknown[]; text?: string }> = []

    const fakeAdapter: EngineAdapter = {
      id: 'fake-file-engine',
      displayName: 'Fake',
      kind: 'cli',
      probe: async () => ({ installed: true, ready: true }),
      capabilities: () => ({
        permissions: true,
        images: true,
        modelSwitch: false,
        modes: false,
        history: false,
        cancel: true,
      }),
      openSession: async init => {
        const sess: EngineSession = {
          sessionId: init.sessionId,
          cwd: init.cwd,
          prompt: async (turnId, text, images, files) => {
            received.push({ text, images, files })
            init.emit({ type: 'turn.done', turnId, stopReason: 'end_turn' })
            init.emit({ type: 'session.state', state: 'idle' })
          },
          cancel: async () => {},
          respondPermission: async () => {},
          close: async () => {},
        }
        return sess
      },
    }

    const workspaceDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lework-file-ws-'))
    const mgr = new SessionManager([workspaceDir], [fakeAdapter], {
      onEvent: ev => {
        emittedEvents.push({
          sessionId: ev.sessionId,
          type: ev.type,
          ...(ev.type === 'message.user' ? { files: ev.files, text: ev.text } : {}),
        })
      },
      onSessionUpdated: () => {},
    })

    try {
      const created = await mgr.createSession({
        engineId: 'fake-file-engine',
        cwd: workspaceDir,
      })
      cleanupSids.push(created.id)

      await mgr.prompt(created.id, '', {
        files: [
          {
            fileName: 'crash.log',
            mimeType: 'text/plain',
            dataBase64: Buffer.from('ERROR: boom\n', 'utf8').toString('base64'),
          },
        ],
      })

      await new Promise(resolve => setTimeout(resolve, 40))

      expect(received).toHaveLength(1)
      expect(received[0]!.files).toHaveLength(1)
      expect(received[0]!.files![0]!.fileName).toBe('crash.log')
      expect(fs.readFileSync(received[0]!.files![0]!.stagedPath, 'utf8')).toBe('ERROR: boom\n')

      const userEvt = emittedEvents.find(e => e.type === 'message.user')
      expect(userEvt?.text).toBe('（已发送 1 个文件）')
      expect(userEvt?.files).toHaveLength(1)
    } finally {
      await mgr.closeAll()
      fs.rmSync(workspaceDir, { recursive: true, force: true })
    }
  })
})
