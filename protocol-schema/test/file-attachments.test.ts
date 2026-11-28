import { describe, expect, it } from 'vitest'
import { AgentEvent, Methods, PromptFileAttachment } from '../src/index.ts'

describe('Prompt file attachments protocol schema', () => {
  it('validates PromptFileAttachment payloads and rejects oversized files', () => {
    const ok = PromptFileAttachment.safeParse({
      fileName: 'architecture.pdf',
      mimeType: 'application/pdf',
      dataBase64: Buffer.from('%PDF-1.7 sample').toString('base64'),
      sizeBytes: 15,
    })
    expect(ok.success).toBe(true)

    const tooLarge = PromptFileAttachment.safeParse({
      fileName: 'huge.bin',
      mimeType: 'application/octet-stream',
      dataBase64: 'AAAA',
      sizeBytes: 5 * 1024 * 1024 + 1,
    })
    expect(tooLarge.success).toBe(false)

    const emptyName = PromptFileAttachment.safeParse({
      fileName: '',
      mimeType: 'text/plain',
      dataBase64: 'SGVsbG8=',
    })
    expect(emptyName.success).toBe(false)
  })

  it('accepts session.prompt with files and message.user with file summaries', () => {
    const promptParams = Methods['session.prompt'].params.parse({
      sessionId: 'sess_1',
      text: '',
      files: [
        {
          fileName: 'notes.md',
          mimeType: 'text/markdown',
          dataBase64: Buffer.from('# Notes').toString('base64'),
          sizeBytes: 7,
        },
      ],
    })
    expect(promptParams.files).toHaveLength(1)
    expect(promptParams.files?.[0]?.fileName).toBe('notes.md')

    const tooManyFiles = Methods['session.prompt'].params.safeParse({
      sessionId: 'sess_1',
      text: 'hi',
      files: Array.from({ length: 5 }, (_, idx) => ({
        fileName: `f_${idx}.txt`,
        mimeType: 'text/plain',
        dataBase64: 'QQ==',
      })),
    })
    expect(tooManyFiles.success).toBe(false)

    const evt = AgentEvent.parse({
      seq: 2,
      sessionId: 'sess_1',
      ts: 1_730_000_000_000,
      type: 'message.user',
      messageId: 'u_turn_1',
      text: '（已发送 1 个文件）',
      files: [
        {
          fileName: 'notes.md',
          mimeType: 'text/markdown',
          sizeBytes: 7,
          stagedPath: '/tmp/lework-attachments-501/sess_1/turn_1_f1_notes.md',
        },
      ],
    })
    expect(evt.type).toBe('message.user')
    if (evt.type === 'message.user') {
      expect(evt.files?.[0]?.fileName).toBe('notes.md')
    }
  })
})
