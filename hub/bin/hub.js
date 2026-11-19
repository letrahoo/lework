#!/usr/bin/env node
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const realSelf = fs.realpathSync(fileURLToPath(import.meta.url))
const hubDir = path.resolve(path.dirname(realSelf), '..')
const repoRoot = path.resolve(hubDir, '..')
const cliEntry = path.join(hubDir, 'src', 'cli.ts')

const tsxBin = [
  path.join(hubDir, 'node_modules', '.bin', 'tsx'),
  path.join(repoRoot, 'node_modules', '.bin', 'tsx'),
].find(p => fs.existsSync(p))

const cmd = tsxBin ?? process.execPath
const cmdArgs = tsxBin
  ? [cliEntry, ...process.argv.slice(2)]
  : ['--experimental-strip-types', cliEntry, ...process.argv.slice(2)]

const child = spawn(cmd, cmdArgs, {
  cwd: repoRoot,
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

child.on('error', err => {
  console.error(`[lework] Failed to launch CLI: ${err.message}`)
  process.exit(1)
})

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal)
    return
  }
  process.exit(code ?? 0)
})
