import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { Buffer } from 'node:buffer'
import { augmentedPathEnv } from '../platform/index.ts'

export interface JsonRpcNotification {
  kind: 'notification'
  method: string
  params: Record<string, unknown>
}

export interface JsonRpcInboundRequest {
  kind: 'request'
  id: string | number
  method: string
  params: Record<string, unknown>
}

export type JsonRpcInbound = JsonRpcNotification | JsonRpcInboundRequest

interface PendingCall {
  resolve: (value: unknown) => void
  reject: (err: Error) => void
  timer: ReturnType<typeof setTimeout>
}

export interface StdioJsonRpcOptions {
  command: string
  args?: string[]
  cwd?: string
  env?: NodeJS.ProcessEnv
  onInbound?: (msg: JsonRpcInbound) => void
  onExit?: (code: number | null, signal: NodeJS.Signals | null) => void
}

/**
 * Lightweight newline-delimited JSON-RPC 2.0 peer over a child process's stdio.
 * Used by ACP engine adapters (such as `claude-agent-acp`).
 */
export class StdioJsonRpcProcess {
  private proc: ChildProcessWithoutNullStreams | null = null
  private nextId = 1
  private readonly pending = new Map<string | number, PendingCall>()
  private buf: Buffer = Buffer.alloc(0)
  private closed = false

  constructor(private readonly opts: StdioJsonRpcOptions) {}

  start(): void {
    if (this.proc) return
    const child = spawn(this.opts.command, this.opts.args ?? [], {
      cwd: this.opts.cwd,
      env: augmentedPathEnv(this.opts.env ?? process.env),
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    this.proc = child

    child.stdout.on('data', (chunk: Buffer) => {
      this.consumeStdout(Buffer.from(chunk))
    })

    child.stderr.on('data', () => {
      // Drain stderr so child never blocks on pipe buffer
    })

    child.on('error', err => {
      this.failAll(err instanceof Error ? err : new Error(String(err)))
    })

    child.on('exit', (code, signal) => {
      this.proc = null
      this.failAll(new Error(`ACP process exited (code=${code ?? 'null'}, signal=${signal ?? 'null'})`))
      if (!this.closed) {
        this.opts.onExit?.(code, signal)
      }
    })
  }

  request(method: string, params: unknown, timeoutMs = 60_000): Promise<unknown> {
    const id = this.nextId++
    return new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`JSON-RPC request "${method}" timed out after ${timeoutMs}ms`))
      }, timeoutMs)
      timer.unref?.()
      this.pending.set(id, { resolve, reject, timer })
      try {
        this.write({ jsonrpc: '2.0', id, method, params })
      } catch (err) {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(err instanceof Error ? err : new Error(String(err)))
      }
    })
  }

  notify(method: string, params: unknown): void {
    this.write({ jsonrpc: '2.0', method, params })
  }

  respond(id: string | number, result: unknown): void {
    this.write({ jsonrpc: '2.0', id, result })
  }

  respondError(id: string | number, code: number, message: string): void {
    this.write({ jsonrpc: '2.0', id, error: { code, message } })
  }

  async close(): Promise<void> {
    if (this.closed) return
    this.closed = true
    this.failAll(new Error('ACP process closed'))
    const child = this.proc
    this.proc = null
    if (!child || child.exitCode !== null || child.killed) return
    await new Promise<void>(resolve => {
      const timer = setTimeout(() => {
        try {
          child.kill('SIGKILL')
        } catch {
          // ignore
        }
        resolve()
      }, 2_000)
      timer.unref?.()
      child.once('exit', () => {
        clearTimeout(timer)
        resolve()
      })
      try {
        child.kill('SIGTERM')
      } catch {
        clearTimeout(timer)
        resolve()
      }
    })
  }

  private write(msg: unknown): void {
    if (!this.proc || this.proc.stdin.destroyed || !this.proc.stdin.writable) {
      throw new Error('ACP child process stdin is not writable')
    }
    this.proc.stdin.write(JSON.stringify(msg) + '\n')
  }

  private consumeStdout(chunk: Buffer): void {
    this.buf = this.buf.length === 0 ? chunk : Buffer.concat([this.buf, chunk])
    let nl = this.buf.indexOf(0x0a)
    while (nl >= 0) {
      const line = this.buf.subarray(0, nl).toString('utf8').trim()
      this.buf = this.buf.subarray(nl + 1)
      if (line.length > 0) {
        this.handleLine(line)
      }
      nl = this.buf.indexOf(0x0a)
    }
  }

  private handleLine(line: string): void {
    let parsed: unknown
    try {
      parsed = JSON.parse(line)
    } catch {
      return
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return
    const obj = parsed as Record<string, unknown>

    if ((typeof obj.id === 'number' || typeof obj.id === 'string') && ('result' in obj || 'error' in obj)) {
      const entry = this.pending.get(obj.id)
      if (!entry) return
      this.pending.delete(obj.id)
      clearTimeout(entry.timer)
      if (obj.error !== undefined && obj.error !== null) {
        const errObj = obj.error as { message?: string }
        entry.reject(new Error(typeof errObj?.message === 'string' ? errObj.message : 'Upstream ACP error'))
      } else {
        entry.resolve(obj.result)
      }
      return
    }

    if (typeof obj.method === 'string') {
      const params =
        obj.params && typeof obj.params === 'object' && !Array.isArray(obj.params)
          ? (obj.params as Record<string, unknown>)
          : {}
      if (obj.method !== 'session/update' && (typeof obj.id === 'string' || typeof obj.id === 'number')) {
        this.opts.onInbound?.({ kind: 'request', id: obj.id, method: obj.method, params })
      } else {
        this.opts.onInbound?.({ kind: 'notification', method: obj.method, params })
      }
    }
  }

  private failAll(err: Error): void {
    for (const [id, entry] of this.pending.entries()) {
      clearTimeout(entry.timer)
      entry.reject(err)
      this.pending.delete(id)
    }
  }
}
