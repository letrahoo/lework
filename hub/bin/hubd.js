#!/usr/bin/env node
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const realSelf = fs.realpathSync(fileURLToPath(import.meta.url))
const hubLauncher = path.join(path.dirname(realSelf), 'hub.js')

const child = spawn(process.execPath, [hubLauncher, 'serve', ...process.argv.slice(2)], {
  stdio: 'inherit',
  env: process.env,
})

for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(sig, () => {
    try {
      child.kill(sig)
    } catch {
      // ignore
    }
  })
}

child.on('exit', code => process.exit(code ?? 0))
