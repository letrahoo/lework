import { spawn, type ChildProcess } from 'node:child_process'
import dgram from 'node:dgram'
import fs from 'node:fs'
import os from 'node:os'

export interface BonjourAdvertiseOptions {
  name: string
  hubId: string
  host: string
  port: number
  shortFingerprint?: string
}

const DNS_SD_BIN = '/usr/bin/dns-sd'
const MDNS_MULTICAST_ADDR = '224.0.0.251'
const MDNS_PORT = 5353
const SERVICE_FQDN = '_lework._tcp.local'

export function collectLanIpv4Addresses(
  nets: NodeJS.Dict<os.NetworkInterfaceInfo[]> = os.networkInterfaces(),
): string[] {
  const out: string[] = []
  for (const [ifName, addrs] of Object.entries(nets)) {
    if (!addrs) continue
    const lower = ifName.toLowerCase()
    // Exclude loopback, VPN tunnels (utun/tun/tap/ppp/tailscale), and virtual bridge interfaces
    if (
      lower.startsWith('lo') ||
      lower.startsWith('utun') ||
      lower.startsWith('tun') ||
      lower.startsWith('tap') ||
      lower.startsWith('tailscale') ||
      lower.startsWith('ppp') ||
      lower.startsWith('docker') ||
      lower.startsWith('podman') ||
      lower.startsWith('veth') ||
      lower.startsWith('br-') ||
      lower.startsWith('virbr') ||
      lower.startsWith('cni') ||
      lower.startsWith('flannel')
    ) {
      continue
    }
    for (const addr of addrs) {
      if (addr.family !== 'IPv4' || addr.internal) continue
      if (addr.address.startsWith('169.254.')) continue
      // Exclude Tailscale CGNAT 100.64.0.0/10 from LAN mDNS records
      const octets = addr.address.split('.').map(Number)
      if (octets[0] === 100 && octets[1]! >= 64 && octets[1]! <= 127) continue
      if (!out.includes(addr.address)) {
        out.push(addr.address)
      }
    }
  }
  return out
}

/** Collects Tailscale CGNAT (`100.64.0.0/10`) IPv4 addresses present on local tunnel interfaces. */
export function collectTailscaleIpv4Addresses(
  nets: NodeJS.Dict<os.NetworkInterfaceInfo[]> = os.networkInterfaces(),
): string[] {
  const out: string[] = []
  for (const addrs of Object.values(nets)) {
    if (!addrs) continue
    for (const addr of addrs) {
      if (addr.family !== 'IPv4' || addr.internal) continue
      const octets = addr.address.split('.').map(Number)
      if (octets[0] === 100 && octets[1]! >= 64 && octets[1]! <= 127) {
        if (!out.includes(addr.address)) out.push(addr.address)
      }
    }
  }
  return out
}

/**
 * Publishes `_lework._tcp.local.` across macOS, Linux, and Windows:
 * - macOS: delegates to native `mDNSResponder` (`/usr/bin/dns-sd -R`) so port 5353 is shared cleanly.
 * - Linux / Windows: runs a zero-dependency RFC 6762 UDP multicast mDNS responder on `224.0.0.251:5353`.
 */
export class BonjourAdvertiser {
  private readonly opts: BonjourAdvertiseOptions
  private proc: ChildProcess | null = null
  private udpSocket: dgram.Socket | null = null
  private refreshTimer: ReturnType<typeof setInterval> | null = null
  private lastIpsCsv = ''
  private stopped = false

  constructor(opts: BonjourAdvertiseOptions) {
    this.opts = opts
  }

  start(): void {
    if (this.opts.host !== '0.0.0.0' && this.opts.host !== '::') return
    if (this.opts.port <= 0) return

    this.stopped = false
    if (process.platform === 'darwin' && fs.existsSync(DNS_SD_BIN)) {
      this.publishDarwinDnsSd()
      this.refreshTimer = setInterval(() => {
        if (this.stopped) return
        const nextCsv = collectLanIpv4Addresses().join(',')
        if (nextCsv !== this.lastIpsCsv) {
          this.publishDarwinDnsSd()
        }
      }, 15_000)
      this.refreshTimer.unref()
      process.once('exit', () => this.killChild())
      return
    }

    this.startCrossPlatformUdpMdns()
  }

  stop(): void {
    this.stopped = true
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer)
      this.refreshTimer = null
    }
    this.killChild()
    if (this.udpSocket) {
      try {
        this.udpSocket.close()
      } catch {
        // ignore
      }
      this.udpSocket = null
    }
  }

  private buildMetadata() {
    const ips = collectLanIpv4Addresses()
    const cleanHost = (this.opts.name || os.hostname())
      .replace(/\.local\.?$/i, '')
      .replace(/[^A-Za-z0-9_-]/g, '-')
      .replace(/-+/g, '-')
      .replace(/^-|-$/g, '') || 'lework-host'
    const instanceLabel = `Lework Hub (${cleanHost})`
    const hostFqdn = `${cleanHost}.local`
    const txtRecords = [
      'v=1',
      'path=/v1/auth/discover',
      `hubId=${this.opts.hubId}`,
    ]
    if (this.opts.shortFingerprint) {
      txtRecords.push(`fp=${this.opts.shortFingerprint}`)
    }
    if (ips.length > 0) {
      txtRecords.push(`ips=${ips.join(',')}`)
    }
    return { ips, cleanHost, instanceLabel, hostFqdn, txtRecords }
  }

  private publishDarwinDnsSd(): void {
    this.killChild()
    const { ips, instanceLabel, txtRecords } = this.buildMetadata()
    this.lastIpsCsv = ips.join(',')

    try {
      const child = spawn(
        DNS_SD_BIN,
        ['-R', instanceLabel, '_lework._tcp', 'local', String(this.opts.port), ...txtRecords],
        { stdio: 'ignore' },
      )
      child.on('error', () => {
        // Ignore if mDNSResponder is unavailable
      })
      child.unref()
      this.proc = child
      console.log(
        `[lework-bonjour] Advertising "${instanceLabel}" _lework._tcp.local:${this.opts.port} (LAN IPs: ${this.lastIpsCsv || 'none'})`,
      )
    } catch {
      // Ignore spawn failure
    }
  }

  private startCrossPlatformUdpMdns(): void {
    try {
      const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true })
      sock.on('error', () => {
        // Best-effort LAN discovery: ignore port-5353 permission or multicast errors
      })
      sock.on('message', msg => {
        if (this.stopped) return
        if (isLeworkMdnsQuery(msg)) {
          this.sendUdpAnnouncement(sock)
        }
      })
      sock.bind(MDNS_PORT, () => {
        if (this.stopped) return
        try {
          sock.setMulticastTTL(255)
          sock.setMulticastLoopback(true)
          const ips = collectLanIpv4Addresses()
          if (ips.length === 0) {
            try {
              sock.addMembership(MDNS_MULTICAST_ADDR)
            } catch {
              // ignore
            }
          } else {
            for (const ip of ips) {
              try {
                sock.addMembership(MDNS_MULTICAST_ADDR, ip)
              } catch {
                // ignore interface multicast error
              }
            }
          }
          const meta = this.buildMetadata()
          this.lastIpsCsv = meta.ips.join(',')
          console.log(
            `[lework-mdns] Advertising "${meta.instanceLabel}" _lework._tcp.local:${this.opts.port} via UDP multicast (LAN IPs: ${this.lastIpsCsv || 'none'})`,
          )
          this.sendUdpAnnouncement(sock)
        } catch {
          // ignore
        }
      })
      sock.unref()
      this.udpSocket = sock

      this.refreshTimer = setInterval(() => {
        if (this.stopped || !this.udpSocket) return
        const nextCsv = collectLanIpv4Addresses().join(',')
        if (nextCsv !== this.lastIpsCsv) {
          this.lastIpsCsv = nextCsv
          this.sendUdpAnnouncement(this.udpSocket)
        }
      }, 20_000)
      this.refreshTimer.unref()
    } catch {
      // ignore if UDP socket creation is disallowed
    }
  }

  private sendUdpAnnouncement(sock: dgram.Socket): void {
    const meta = this.buildMetadata()
    if (meta.ips.length === 0) return
    const pkt = buildMdnsAnnouncementPacket({
      instanceName: meta.instanceLabel,
      hostFqdn: meta.hostFqdn,
      port: this.opts.port,
      ips: meta.ips,
      txt: meta.txtRecords,
    })
    try {
      sock.send(pkt, 0, pkt.length, MDNS_PORT, MDNS_MULTICAST_ADDR)
    } catch {
      // ignore transient network errors
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

// ---------------------------------------------------------------------------
// Minimal RFC 6762 / RFC 1035 DNS wire-format helpers (zero dependencies)
// ---------------------------------------------------------------------------

function encodeDnsName(fqdn: string): Buffer {
  const labels = fqdn
    .replace(/\.$/, '')
    .split('.')
    .filter(Boolean)
  const parts: Buffer[] = []
  for (const label of labels) {
    const bytes = Buffer.from(label, 'utf8').subarray(0, 63)
    parts.push(Buffer.from([bytes.length]), bytes)
  }
  parts.push(Buffer.from([0]))
  return Buffer.concat(parts)
}

function encodeResourceRecord(
  name: string,
  type: number,
  rrClass: number,
  ttlSec: number,
  rdata: Buffer,
): Buffer {
  const nameBuf = encodeDnsName(name)
  const hdr = Buffer.alloc(10)
  hdr.writeUInt16BE(type, 0)
  hdr.writeUInt16BE(rrClass, 2)
  hdr.writeUInt32BE(ttlSec, 4)
  hdr.writeUInt16BE(rdata.length, 8)
  return Buffer.concat([nameBuf, hdr, rdata])
}

export function buildMdnsAnnouncementPacket(params: {
  instanceName: string
  hostFqdn: string
  port: number
  ips: string[]
  txt: string[]
  ttlSec?: number
}): Buffer {
  const ttl = params.ttlSec ?? 120
  const serviceInstanceFqdn = `${params.instanceName}.${SERVICE_FQDN}`
  const answers: Buffer[] = []

  // 1. PTR: _lework._tcp.local -> <Instance>._lework._tcp.local (class IN = 1)
  answers.push(encodeResourceRecord(SERVICE_FQDN, 12, 0x0001, ttl, encodeDnsName(serviceInstanceFqdn)))

  // 2. SRV (type 33, cache-flush IN = 0x8001): priority 0, weight 0, port, target hostFqdn
  const targetBuf = encodeDnsName(params.hostFqdn)
  const srvRdata = Buffer.alloc(6 + targetBuf.length)
  srvRdata.writeUInt16BE(0, 0)
  srvRdata.writeUInt16BE(0, 2)
  srvRdata.writeUInt16BE(params.port, 4)
  targetBuf.copy(srvRdata, 6)
  answers.push(encodeResourceRecord(serviceInstanceFqdn, 33, 0x8001, ttl, srvRdata))

  // 3. TXT (type 16, cache-flush IN = 0x8001)
  const txtChunks: Buffer[] = []
  for (const entry of params.txt) {
    const b = Buffer.from(entry, 'utf8').subarray(0, 255)
    txtChunks.push(Buffer.from([b.length]), b)
  }
  const txtRdata = txtChunks.length > 0 ? Buffer.concat(txtChunks) : Buffer.from([0])
  answers.push(encodeResourceRecord(serviceInstanceFqdn, 16, 0x8001, ttl, txtRdata))

  // 4. A records (type 1, cache-flush IN = 0x8001) for each LAN IPv4
  for (const ip of params.ips) {
    const octets = ip.split('.').map(Number)
    if (octets.length === 4 && octets.every(n => Number.isInteger(n) && n >= 0 && n <= 255)) {
      answers.push(encodeResourceRecord(params.hostFqdn, 1, 0x8001, ttl, Buffer.from(octets)))
    }
  }

  const header = Buffer.alloc(12)
  header.writeUInt16BE(0x0000, 0) // ID = 0 for mDNS
  header.writeUInt16BE(0x8400, 2) // QR=1 (Response), AA=1 (Authoritative)
  header.writeUInt16BE(0, 4) // QDCOUNT
  header.writeUInt16BE(answers.length, 6) // ANCOUNT
  header.writeUInt16BE(0, 8) // NSCOUNT
  header.writeUInt16BE(0, 10) // ARCOUNT

  return Buffer.concat([header, ...answers])
}

export function isLeworkMdnsQuery(packet: Buffer): boolean {
  if (packet.length < 18) return false
  const flags = packet.readUInt16BE(2)
  // Ignore responses (QR bit 15 == 1); only inspect queries (QR == 0)
  if ((flags & 0x8000) !== 0) return false
  const qdCount = packet.readUInt16BE(4)
  if (qdCount === 0) return false
  // Fast substring scan for `\x07_lework\x04_tcp` in DNS question section
  const needle = Buffer.from([0x07, ...Buffer.from('_lework'), 0x04, ...Buffer.from('_tcp')])
  return packet.indexOf(needle, 12) >= 12
}
