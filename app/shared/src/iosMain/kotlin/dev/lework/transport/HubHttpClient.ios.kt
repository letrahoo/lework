@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.lework.transport

import dev.lework.security.HubTrust
import dev.lework.security.sha256
import dev.lework.security.toHex
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustRef

/** SHA-256 (hex) of the DER-encoded leaf certificate presented by the server. */
@Suppress("DEPRECATION")
private fun leafFingerprint(trust: SecTrustRef): String? {
    val cert = SecTrustGetCertificateAtIndex(trust, 0) ?: return null
    val data = SecCertificateCopyData(cert) ?: return null
    return try {
        val len = CFDataGetLength(data).toInt()
        val ptr = CFDataGetBytePtr(data) ?: return null
        sha256(ptr.reinterpret<ByteVar>().readBytes(len)).toHex()
    } finally {
        CFRelease(data)
    }
}

actual fun createHubHttpClient(pinnedOnly: Boolean): HttpClient = HttpClient(Darwin) {
    install(WebSockets)
    followRedirects = false
    engine {
        handleChallenge { _, _, challenge, completionHandler ->
            val space = challenge.protectionSpace
            if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
                completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
                return@handleChallenge
            }
            val trust = space.serverTrust
            val fp = trust?.let { leafFingerprint(it) }
            if (trust == null || fp == null || (pinnedOnly && !HubTrust.isPinned(fp))) {
                // Unknown certificate on a token-carrying connection: abort the handshake.
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                return@handleChallenge
            }
            HubTrust.recordObserved(space.host, space.port.toInt(), fp)
            // CA / hostname validation is replaced by SHA-256 certificate pinning (self-signed Hub cert).
            completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
        }
    }
}
