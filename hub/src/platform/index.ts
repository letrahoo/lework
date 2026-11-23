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

/**
 * Hub data directory (`$LEWORK_HOME`, default `~/.lework`).
 */
export function defaultDataDir(): string {
  if (process.env.LEWORK_HOME) return process.env.LEWORK_HOME
  return path.join(os.homedir(), '.lework')
}

export function extraSearchPaths(): string[] {
  const home = os.homedir()
  return [
    path.join(home, '.local', 'bin'),
    path.join(home, '.cargo', 'bin'),
    path.join(home, 'Library', 'pnpm'),
    '/opt/homebrew/bin',
    '/usr/local/go/bin',
    '/usr/local/bin',
    '/usr/bin',
    '/bin',
    '/usr/sbin',
    '/sbin',
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

const VIRTUAL_IFACE_PREFIXES = [
  'lo',
  'utun',
  'tun',
  'tap',
  'tailscale',
  'ppp',
  'docker',
  'podman',
  'veth',
  'br-',
  'virbr',
  'cni',
  'flannel',
]

export function listLanAddresses(
  nets: NodeJS.Dict<os.NetworkInterfaceInfo[]> = os.networkInterfaces(),
): string[] {
  const addrs: string[] = []
  for (const [ifName, entries] of Object.entries(nets)) {
    const lower = ifName.toLowerCase()
    if (VIRTUAL_IFACE_PREFIXES.some(p => lower.startsWith(p))) continue
    for (const entry of entries ?? []) {
      if (entry.family === 'IPv4' && !entry.internal && !entry.address.startsWith('169.254.')) {
        if (!addrs.includes(entry.address)) addrs.push(entry.address)
      }
    }
  }
  return addrs
}

/**
 * Extracts the server IPv4/IPv6 address from `$SSH_CONNECTION` (`<client_ip> <client_port> <server_ip> <server_port>`)
 * so headless remote Devboxes prioritize the exact routable IP the user connected to.
 */
export function resolveSshServerAddress(env: NodeJS.ProcessEnv = process.env): string | undefined {
  const raw = env.SSH_CONNECTION?.trim()
  if (!raw) return undefined
  const parts = raw.split(/\s+/)
  if (parts.length < 3) return undefined
  const serverIp = parts[2]!.replace(/^::ffff:/i, '').trim()
  if (!serverIp || serverIp === '127.0.0.1' || serverIp === '::1') return undefined
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(serverIp)) return serverIp
  return undefined
}

/**
 * Prevents macOS idle sleep while an agent turn is actively running.
 * No-op on Linux.
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
  role: 'master' | 'slave' | 'standalone'
  repoRoot: string
  nodeBin?: string
  platformOverride?: SupportedPlatform
}): { platform: SupportedPlatform; unitOrLabel: string; pathHint: string; content: string } {
  const platform = opts.platformOverride ?? detectPlatform()
  const nodeBin = opts.nodeBin ?? process.execPath
  const hubTsx = path.join(opts.repoRoot, 'hub', 'node_modules', '.bin', 'tsx')
  const rootTsx = path.join(opts.repoRoot, 'node_modules', '.bin', 'tsx')
  const tsxBin = [hubTsx, rootTsx].find(p => fs.existsSync(p))
  const cliEntry = path.join(opts.repoRoot, 'hub', 'src', 'cli.ts')
  const home = os.homedir()
  const servicePathDirs = [
    ...new Set([
      path.dirname(nodeBin),
      ...extraSearchPaths(),
      ...(process.env.PATH ?? '').split(path.delimiter).filter(p => p && !p.includes('/tmp/')),
    ]),
  ]
  const pathValue = servicePathDirs.join(':')
  const execArgs = tsxBin
    ? [tsxBin, cliEntry, 'serve', '--role', opts.role]
    : [nodeBin, '--experimental-transform-types', cliEntry, 'serve', '--role', opts.role]

  if (platform === 'darwin') {
    const label = `dev.lework.hub-${opts.role}`
    const pathHint = path.join(home, 'Library', 'LaunchAgents', `${label}.plist`)
    const logDir = path.join(home, 'Library', 'Logs')
    const xmlArgs = execArgs.map(a => `    <string>${a}</string>`).join('\n')
    const content = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${label}</string>
  <key>ProgramArguments</key>
  <array>
${xmlArgs}
  </array>
  <key>WorkingDirectory</key>
  <string>${opts.repoRoot}</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>${pathValue}</string>
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
    return { platform, unitOrLabel: label, pathHint, content }
  }

  // Linux / systemd --user (Master or Linux Slave / Devbox)
  const unitName = `lework-hub-${opts.role}.service`
  const pathHint = path.join(home, '.config', 'systemd', 'user', unitName)
  const execStart = execArgs.map(a => (a.includes(' ') ? `"${a}"` : a)).join(' ')
  const content = `[Unit]
Description=Lework Hub (${opts.role})
After=network-online.target

[Service]
Type=simple
WorkingDirectory=${opts.repoRoot}
Environment="PATH=${pathValue}"
ExecStart=${execStart}
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
`
  return { platform, unitOrLabel: unitName, pathHint, content }
}
