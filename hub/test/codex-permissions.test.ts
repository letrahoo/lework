import { expect, it, vi } from 'vitest'
import { CodexEngineAdapter } from '../src/engines/codex-adapter.ts'
import { StdioJsonRpcProcess, type JsonRpcInbound } from '../src/engines/acp-jsonrpc.ts'

it.each([false, true])('applies modes and replies to approvals (resume=%s)', async resume => {
  let receive!: (msg: JsonRpcInbound) => void
  let requestId = ''
  const calls: Array<{ method: string, params: unknown }> = []
  const respond = vi.spyOn(StdioJsonRpcProcess.prototype, 'respond').mockImplementation(() => {})
  vi.spyOn(StdioJsonRpcProcess.prototype, 'start').mockImplementation(() => {})
  vi.spyOn(StdioJsonRpcProcess.prototype, 'notify').mockImplementation(() => {})
  vi.spyOn(StdioJsonRpcProcess.prototype, 'isAlive').mockReturnValue(true)
  vi.spyOn(StdioJsonRpcProcess.prototype, 'request').mockImplementation(async function (method, params) {
    calls.push({ method, params })
    if (method === 'turn/start') {
      receive({ kind: 'notification', method: 'turn/completed', params: {} })
    }
    return { thread: { id: 'thread' } }
  })
  try {
    const session = await new CodexEngineAdapter({ binary: process.execPath }).openSession({
      cwd: process.cwd(), sessionId: 'test', mode: 'plan',
      resumeNativeId: resume ? 'thread' : undefined,
      emit(event) { if (event.type === 'permission.requested') requestId = event.requestId },
    })
    // Drive the same inbound messages that the stdio transport delivers.
    receive = (session as unknown as { handleInbound(msg: JsonRpcInbound): void }).handleInbound.bind(session)
    expect(calls.at(-1)).toMatchObject({
      method: resume ? 'thread/resume' : 'thread/start',
      params: { sandbox: 'read-only', approvalPolicy: 'never' },
    })
    await session.setMode!('acceptEdits')
    await session.prompt('next', 'edit')
    expect(calls.at(-1)).toMatchObject({
      method: 'turn/start', params: { sandboxPolicy: { type: 'workspaceWrite' }, approvalPolicy: 'untrusted' },
    })
    for (const method of ['item/commandExecution/requestApproval', 'item/fileChange/requestApproval']) {
      for (const [option, decision] of [['allow', 'accept'], ['reject', 'decline'], [null, 'cancel']] as const) {
        receive({ kind: 'request', id: 42, method, params: {} })
        await session.respondPermission(requestId, option)
        expect(respond).toHaveBeenLastCalledWith(42, { decision })
      }
    }
    await session.close()
  } finally {
    vi.restoreAllMocks()
  }
})
