import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

export type SupportedPlatform = 'darwin' | 'linux' | 'win32' | 'other'

export function detectPlatform(): SupportedPlatform {
  const p = process.platform
  if (p === 'darwin' || p === 'linux' || p === 'win32') return p
  return 'other'
}

let legacyMigrated = false

/**
 * Hub data directory (`$LEWORK_HOME`, default `~/.lework`). Older builds used `~/.qprc`; it is
 * moved over once on first use and a compatibility symlink is left behind.
 */
export function defaultDataDir(): string {
  if (process.env.LEWORK_HOME) return process.env.LEWORK_HOME
  const dir = path.join(os.homedir(), '.lework')
  if (!legacyMigrated) {
    legacyMigrated = true
    const legacy = path.join(os.homedir(), '.qprc')
    try {
      if (!fs.existsSync(dir) && fs.existsSync(legacy) && !fs.lstatSync(legacy).isSymbolicLink()) {
        fs.renameSync(legacy, dir)
        fs.symlinkSync(dir, legacy)
        console.log(`[lework] Migrated data dir ${legacy} -> ${dir}`)
      }
    } catch {
      // best effort
    }
  }
  return dir
}

export function extraSearchPaths(): string[] {
  const home = os.homedir()
  return [
    path.join(home, '.local', 'bin'),
    path.join(home, 'Library', 'pnpm'),
    '/opt/homebrew/bin',
    '/usr/local/bin',
    '/usr/bin',
    '/bin',
  ]
}

export function augmentedPathEnv(baseEnv: NodeJS.ProcessEnv = process.env): NodeJS.ProcessEnv {
  const existing = (baseEnv.PATH ?? '').split(path.delimiter).filter(Boolean)
  const merged = [...new Set([...extraSearchPaths(), ...existing])]
  const lang = baseEnv.LANG || 'en_US.UTF-8'
  const lcCtype = baseEnv.LC_CTYPE || lang
  return {
    ...baseEnv,
    LANG: lang,
    LC_CTYPE: lcCtype,
    PATH: merged.join(path.delimiter),
  }
}

export function findExecutable(name: string, candidatePaths: string[] = []): string | null {
  for (const candidate of candidatePaths) {
    if (candidate && isExecutableFile(candidate)) return candidate
  }
  const envPath = augmentedPathEnv().PATH ?? ''
  for (const dir of envPath.split(path.delimiter)) {
    if (!dir) continue
    const full = path.join(dir, name)
    if (isExecutableFile(full)) return full
  }
  return null
}

function isExecutableFile(filePath: string): boolean {
  try {
    const st = fs.statSync(filePath)
    if (!st.isFile()) return false
    fs.accessSync(filePath, fs.constants.X_OK)
    return true
  } catch {
    return false
  }
}

export function probeCommandVersion(binPath: string, args: string[] = ['--version']): string | undefined {
  try {
    const res = spawnSync(binPath, args, {
      env: augmentedPathEnv(),
      encoding: 'utf8',
      timeout: 5_000,
    })
    if (res.status === 0) {
      const out = `${res.stdout ?? ''}\n${res.stderr ?? ''}`.trim().split('\n')[0]?.trim()
      return out || undefined
    }
  } catch {
    // ignore
  }
  return undefined
}

export function listLanAddresses(): string[] {
  const nets = os.networkInterfaces()
  const addrs: string[] = []
  for (const entries of Object.values(nets)) {
    for (const entry of entries ?? []) {
      if (entry.family === 'IPv4' && !entry.internal) {
        addrs.push(entry.address)
      }
    }
  }
  return addrs
}

/**
 * Prevents macOS idle sleep while an agent turn is actively running.
 * No-op on Linux / Devbox.
 */
export class SleepInhibitor {
  private refCount = 0
  private proc: ChildProcess | null = null

  acquire(): void {
    this.refCount++
    if (this.refCount === 1 && detectPlatform() === 'darwin' && !this.proc) {
      const caffeinate = findExecutable('caffeinate', ['/usr/bin/caffeinate'])
      if (caffeinate) {
        try {
          this.proc = spawn(caffeinate, ['-i', '-w', String(process.pid)], {
            stdio: 'ignore',
            detached: false,
          })
          this.proc.unref()
          this.proc.on('exit', () => {
            this.proc = null
          })
        } catch {
          this.proc = null
        }
      }
    }
  }

  release(): void {
    this.refCount = Math.max(0, this.refCount - 1)
    if (this.refCount === 0 && this.proc) {
      try {
        this.proc.kill('SIGTERM')
      } catch {
        // ignore
      }
      this.proc = null
    }
  }

  dispose(): void {
    this.refCount = 0
    if (this.proc) {
      try {
        this.proc.kill('SIGTERM')
      } catch {
        // ignore
      }
      this.proc = null
    }
  }
}

export function ensureUserCliLinks(
  repoRoot: string,
  targetBinDir: string = path.join(os.homedir(), '.local', 'bin'),
): string[] {
  const launcher = path.join(repoRoot, 'hub', 'bin', 'hub.js')
  if (!fs.existsSync(launcher)) return []
  try {
    fs.chmodSync(launcher, 0o755)
    fs.mkdirSync(targetBinDir, { recursive: true, mode: 0o755 })
  } catch {
    return []
  }

  const linked: string[] = []
  for (const name of ['lework', 'hub']) {
    const linkPath = path.join(targetBinDir, name)
    try {
      const lst = fs.lstatSync(linkPath)
      if (lst.isSymbolicLink()) {
        const curTarget = fs.readlinkSync(linkPath)
        const resolvedCur = path.resolve(path.dirname(linkPath), curTarget)
        if (resolvedCur === launcher) {
          linked.push(linkPath)
          continue
        }
        if (!fs.existsSync(resolvedCur) || curTarget.includes('hub/bin/hub.js')) {
          fs.unlinkSync(linkPath)
          fs.symlinkSync(launcher, linkPath)
          linked.push(linkPath)
        }
      }
    } catch {
      try {
        fs.symlinkSync(launcher, linkPath)
        linked.push(linkPath)
      } catch {
        // ignore permission errors
      }
    }
  }
  return linked
}

export function generateServiceDescriptor(opts: {
  role: 'master' | 'slave'
  repoRoot: string
  nodeBin?: string
}): { platform: SupportedPlatform; pathHint: string; content: string } {
  const platform = detectPlatform()
  const nodeBin = opts.nodeBin ?? process.execPath
  const hubTsx = path.join(opts.repoRoot, 'hub', 'node_modules', '.bin', 'tsx')
  const rootTsx = path.join(opts.repoRoot, 'node_modules', '.bin', 'tsx')
  const tsxBin = fs.existsSync(hubTsx) ? hubTsx : rootTsx
  const cliEntry = path.join(opts.repoRoot, 'hub', 'src', 'cli.ts')
  const home = os.homedir()

  if (platform === 'darwin') {
    const label = `dev.lework.hub-${opts.role}`
    const pathHint = path.join(home, 'Library', 'LaunchAgents', `${label}.plist`)
    const logDir = path.join(home, 'Library', 'Logs')
    const content = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${label}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${tsxBin}</string>
    <string>${cliEntry}</string>
    <string>serve</string>
    <string>--role</string>
    <string>${opts.role}</string>
  </array>
  <key>WorkingDirectory</key>
  <string>${opts.repoRoot}</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>${path.dirname(nodeBin)}:${home}/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>${logDir}/${label}.log</string>
  <key>StandardErrorPath</key>
  <string>${logDir}/${label}.err.log</string>
</dict>
</plist>
`
    return { platform, pathHint, content }
  }

  // Linux / systemd --user (Devbox Master or Linux Slave)
  const unitName = `lework-hub-${opts.role}.service`
  const pathHint = path.join(home, '.config', 'systemd', 'user', unitName)
  const content = `[Unit]
Description=lework Hub (${opts.role})
After=network-online.target

[Service]
Type=simple
WorkingDirectory=${opts.repoRoot}
Environment="PATH=${path.dirname(nodeBin)}:${home}/.local/bin:/usr/local/bin:/usr/bin:/bin"
ExecStart=${tsxBin} ${cliEntry} serve --role ${opts.role}
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
`
  return { platform, pathHint, content }
}
