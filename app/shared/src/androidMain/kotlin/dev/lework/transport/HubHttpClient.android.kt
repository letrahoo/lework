package dev.lework.transport

import dev.lework.security.HubTrust
import dev.lework.security.toHex
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/** Fingerprint-pinning trust manager shared by JVM tests & Android (OkHttp engine). */
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
        install(WebSockets)
        followRedirects = false
        engine {
            config {
                sslSocketFactory(ctx.socketFactory, tm)
                hostnameVerifier { _, _ -> true }
                followRedirects(false)
                followSslRedirects(false)
            }
        }
    }
}
