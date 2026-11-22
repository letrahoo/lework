package dev.lework.transport

import dev.lework.platform.AndroidPlatformRuntime
import dev.lework.security.HubTrust
import dev.lework.security.toHex
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Authenticates raw TCP sockets bound for the embedded `lework-tsnet` localhost forwarder BEFORE
 * layering end-to-end TLS on top, preventing co-installed Android apps from piggybacking on
 * `127.0.0.1:<ephemeralPort>`.
 */
private class PreTlsForwarderSocketFactory : SocketFactory() {
    private class PreambleSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            super.connect(endpoint, timeout)
            val inet = endpoint as? InetSocketAddress ?: return
            val host = inet.address?.hostAddress ?: inet.hostString
            if ((host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) &&
                inet.port in AndroidPlatformRuntime.tsnetProxyPorts
            ) {
                val secret = AndroidPlatformRuntime.tsnetForwarderSecret
                if (secret.isNotEmpty()) {
                    val out = getOutputStream()
                    out.write("LEWORK-FWD $secret\n".encodeToByteArray())
                    out.flush()
                }
            }
        }
    }

    override fun createSocket(): Socket = PreambleSocket()
    override fun createSocket(host: String?, port: Int): Socket = PreambleSocket().apply {
        connect(InetSocketAddress(host, port))
    }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        PreambleSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }
    override fun createSocket(host: InetAddress?, port: Int): Socket = PreambleSocket().apply {
        connect(InetSocketAddress(host, port))
    }
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        PreambleSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

/** Fingerprint-pinning trust manager for Android (OkHttp engine). */
class HubPinningTrustManager(private val pinnedOnly: Boolean) : X509ExtendedTrustManager() {
    private fun verify(chain: Array<out X509Certificate>?, host: String?, port: Int) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        val fp = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).toHex()
        if (pinnedOnly) {
            if (!HubTrust.isPinned(fp)) {
                throw CertificateException("Hub certificate $fp is not pinned")
            }
        }
        if (host != null && port > 0) HubTrust.recordObserved(host, port, fp)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        val ssl = socket as? SSLSocket
        val host = ssl?.handshakeSession?.peerHost ?: socket?.inetAddress?.hostAddress
        verify(chain, host, socket?.port ?: -1)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        verify(chain, engine?.peerHost, engine?.peerPort ?: -1)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        verify(chain, null, -1)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("Client certificates not supported")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("Client certificates not supported")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

actual fun createHubHttpClient(pinnedOnly: Boolean): HttpClient {
    val tm = HubPinningTrustManager(pinnedOnly)
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
    return HttpClient(OkHttp) {
        install(WebSockets) {
            pingIntervalMillis = 20_000L
        }
        followRedirects = false
        engine {
            config {
                proxy(Proxy.NO_PROXY)
                pingInterval(20, TimeUnit.SECONDS)
                socketFactory(PreTlsForwarderSocketFactory())
                sslSocketFactory(ctx.socketFactory, tm)
                hostnameVerifier { _, _ -> true }
                followRedirects(false)
                followSslRedirects(false)
            }
        }
    }
}
