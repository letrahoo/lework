#!/usr/bin/env node
/**
 * Lework · Stateless TCP/WebSocket Relay (0.0.0.0:64450 -> 127.0.0.1:16450)
 *
 * Bridges mobile clients over an overlay network / VPN / cloud host (where Wi-Fi AP
 * client isolation or NAT blocks direct phone->workstation packets) to the workstation
 * Slave Hub via SSH reverse tunnel. End-to-end authentication is enforced by hubd.
 *
 * Security model: this relay is deliberately a dumb TCP pipe. TLS terminates at the Hub and the
 * mobile app pins the Hub certificate fingerprint, so the relay (and anything that hijacks the
 * upstream port while the tunnel is down) only ever sees ciphertext and can never obtain tokens.
 * Per-source-IP connection caps limit abuse from the wider network.
 */
import net from 'node:net'

const LISTEN_HOST = process.env.QPRC_LISTEN_HOST ?? '0.0.0.0'
const LISTEN_PORT = Number(process.env.QPRC_LISTEN_PORT ?? 64450)
const UPSTREAM_HOST = process.env.QPRC_UPSTREAM_HOST ?? '127.0.0.1'
const UPSTREAM_PORT = Number(process.env.QPRC_UPSTREAM_PORT ?? 16450)

const MAX_CONN_PER_IP = Number(process.env.QPRC_MAX_CONN_PER_IP ?? 16)
const MAX_CONN_TOTAL = Number(process.env.QPRC_MAX_CONN_TOTAL ?? 256)
const perIp = new Map()
let total = 0

const server = net.createServer(clientSock => {
  const ip = clientSock.remoteAddress ?? 'unknown'
  const n = perIp.get(ip) ?? 0
  if (n >= MAX_CONN_PER_IP || total >= MAX_CONN_TOTAL) {
    clientSock.destroy()
    return
  }
  perIp.set(ip, n + 1)
  total++
  let released = false
  const release = () => {
    if (released) return
    released = true
    total--
    const left = (perIp.get(ip) ?? 1) - 1
    if (left <= 0) perIp.delete(ip)
    else perIp.set(ip, left)
  }
  clientSock.once('close', release)
  clientSock.setNoDelay(true)
  const upstreamSock = net.connect(UPSTREAM_PORT, UPSTREAM_HOST, () => {
    upstreamSock.setNoDelay(true)
    clientSock.pipe(upstreamSock)
    upstreamSock.pipe(clientSock)
  })

  // Hub speaks TLS end-to-end; when the workstation tunnel is offline just drop the connection.
  upstreamSock.on('error', () => clientSock.destroy())

  clientSock.on('error', () => upstreamSock.destroy())
  clientSock.on('close', () => upstreamSock.destroy())
  upstreamSock.on('close', () => clientSock.destroy())
})

server.listen(LISTEN_PORT, LISTEN_HOST, () => {
  console.log(`[lework-relay] listening on ${LISTEN_HOST}:${LISTEN_PORT} -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}`)
})
