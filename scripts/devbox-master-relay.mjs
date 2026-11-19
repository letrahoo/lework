#!/usr/bin/env node
/**
 * Lework · Stateless TCP/WebSocket Relay (0.0.0.0:64450 -> 127.0.0.1:16450)
 *
 * Bridges mobile clients over an overlay network / VPN / cloud host (where Wi-Fi AP
 * client isolation or NAT blocks direct phone->workstation packets) to the workstation
 * Slave Hub via SSH reverse tunnel. End-to-end authentication is enforced by hubd.
 */
import net from 'node:net'

const LISTEN_HOST = process.env.QPRC_LISTEN_HOST ?? '0.0.0.0'
const LISTEN_PORT = Number(process.env.QPRC_LISTEN_PORT ?? 64450)
const UPSTREAM_HOST = process.env.QPRC_UPSTREAM_HOST ?? '127.0.0.1'
const UPSTREAM_PORT = Number(process.env.QPRC_UPSTREAM_PORT ?? 16450)

const server = net.createServer(clientSock => {
  clientSock.setNoDelay(true)
  const upstreamSock = net.connect(UPSTREAM_PORT, UPSTREAM_HOST, () => {
    upstreamSock.setNoDelay(true)
    clientSock.pipe(upstreamSock)
    upstreamSock.pipe(clientSock)
  })

  upstreamSock.on('error', () => {
    if (!clientSock.destroyed) {
      const body = JSON.stringify({
        ok: false,
        error: `Workstation tunnel (${UPSTREAM_HOST}:${UPSTREAM_PORT}) is currently offline`,
      })
      clientSock.end(
        `HTTP/1.1 502 Bad Gateway\r\nContent-Type: application/json\r\nContent-Length: ${Buffer.byteLength(body)}\r\nConnection: close\r\n\r\n${body}`,
      )
    }
  })

  clientSock.on('error', () => upstreamSock.destroy())
  clientSock.on('close', () => upstreamSock.destroy())
  upstreamSock.on('close', () => clientSock.destroy())
})

server.listen(LISTEN_PORT, LISTEN_HOST, () => {
  console.log(`[lework-relay] listening on ${LISTEN_HOST}:${LISTEN_PORT} -> ${UPSTREAM_HOST}:${UPSTREAM_PORT}`)
})
