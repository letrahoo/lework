import { execFile } from 'node:child_process'
import type { EngineStatus } from '@lework/protocol'
import { augmentedPathEnv } from '../platform/index.ts'

/** Return only a coarse status: CLI output may include an account or a masked API key. */
export function probeCliAuth(bin: string, cli: 'claude' | 'codex'): Promise<NonNullable<EngineStatus['authStatus']>> {
  const args = cli === 'claude' ? ['auth', 'status', '--json'] : ['login', 'status']
  return new Promise(resolve => {
    const child = execFile(bin, args, {
      env: augmentedPathEnv(), timeout: 3000, killSignal: 'SIGKILL', maxBuffer: 16 * 1024,
    }, (error, stdout, stderr) => {
      const code = error ? error.code : 0
      if (code !== 0 && code !== 1) return resolve('unknown')
      if (cli === 'claude') {
        try {
          const { loggedIn } = JSON.parse(stdout)
          if (loggedIn === true && code === 0) return resolve('authenticated')
          if (loggedIn === false) return resolve('required')
        } catch { /* Older CLI or non-JSON diagnostic. */ }
      } else {
        const result = `${stdout}\n${stderr}`.trim()
        if (code === 0 && /^Logged in (using|with)\b/i.test(result)) return resolve('authenticated')
        if (code === 1 && /^Not logged in\.?$/i.test(result)) return resolve('required')
      }
      resolve('unknown')
    })
    child.stdin?.end()
  })
}
