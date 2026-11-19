import { spawn, type ChildProcess } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'

export interface BonjourAdvertiseOptions {
  name: string
  hubId: string
  host: string
  port: number
}

const DNS_SD_BIN = '/usr/bin/dns-sd'

export function collectLanIpv4Addresses(
  nets: NodeJS.Dict<os.NetworkInterfaceInfo[]> = os.networkInterfaces(),
): string[] {
  const out: string[] = []
  for (const [ifName, addrs] of Object.entries(nets)) {
    if (!addrs) continue
    const lower = ifName.toLowerCase()
    // Exclude loopback, VPN tunnels (utun/tun/tap/ppp), and virtual bridge interfaces
    if (
      lower.startsWith('lo') ||
      lower.startsWith('utun') ||
      lower.startsWith('tun') ||
      lower.startsWith('tap') ||
      lower.startsWith('ppp') ||
      lower.startsWith('docker') ||
      lower.startsWith('veth') ||
      lower.startsWith('br-')
    ) {
      continue
    }
    for (const addr of addrs) {
      if (addr.family !== 'IPv4' || addr.internal) continue
      if (addr.address.startsWith('169.254.')) continue
      if (!out.includes(addr.address)) {
        out.push(addr.address)
      }
    }
  }
  return out
}

/**
 * Publishes `_qprc._tcp.local.` via macOS native mDNSResponder (`dns-sd -R`)
 * and automatically refreshes TXT `ips=` when Wi-Fi / LAN addresses change.
 */
export class BonjourAdvertiser {
  private readonly opts: BonjourAdvertiseOptions
  private proc: ChildProcess | null = null
  private refreshTimer: ReturnType<typeof setInterval> | null = null
  private lastIpsCsv = ''
  private stopped = false

  constructor(opts: BonjourAdvertiseOptions) {
    this.opts = opts
  }

  start(): void {
    if (process.platform !== 'darwin') return
    if (this.opts.host !== '0.0.0.0' && this.opts.host !== '::') return
    if (this.opts.port <= 0) return
    if (!fs.existsSync(DNS_SD_BIN)) return

    this.stopped = false
    this.publishCurrent()

    this.refreshTimer = setInterval(() => {
      if (this.stopped) return
      const nextCsv = collectLanIpv4Addresses().join(',')
      if (nextCsv !== this.lastIpsCsv) {
        this.publishCurrent()
      }
    }, 15_000)
    this.refreshTimer.unref()

    process.once('exit', () => this.killChild())
  }

  stop(): void {
    this.stopped = true
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer)
      this.refreshTimer = null
    }
    this.killChild()
  }

  private publishCurrent(): void {
    this.killChild()
    const ips = collectLanIpv4Addresses()
    this.lastIpsCsv = ips.join(',')
    const cleanHost = os.hostname().replace(/\.local\.?$/i, '')
    const instanceName = `Lework Hub (${cleanHost})`
    const txtRecords = [
      'v=1',
      'path=/v1/auth/discover',
      `hubId=${this.opts.hubId}`,
    ]
    if (this.lastIpsCsv) {
      txtRecords.push(`ips=${this.lastIpsCsv}`)
    }

    try {
      const child = spawn(
        DNS_SD_BIN,
        ['-R', instanceName, '_qprc._tcp', 'local', String(this.opts.port), ...txtRecords],
        { stdio: 'ignore' },
      )
      child.on('error', () => {
        // Ignore if mDNSResponder is unavailable
      })
      child.unref()
      this.proc = child
      console.log(
        `[qprc-bonjour] Advertising "${instanceName}" _qprc._tcp.local:${this.opts.port} (LAN IPs: ${this.lastIpsCsv || 'none'})`,
      )
    } catch {
      // Ignore spawn failure
    }
  }

  private killChild(): void {
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
