@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.lework.security

import dev.lework.platform.loadPinnedHubFingerprints
import dev.lework.platform.savePinnedHubFingerprints
import kotlin.concurrent.atomics.AtomicReference

/**
 * Certificate pinning state shared by the TLS engines.
 *
 * - **Probe** connections (discovery `/v1/auth/discover`, attestation, OAuth device flow) accept any
 *   certificate but record the leaf fingerprint per `host:port` in [observed]. No secret is ever
 *   sent over a probe connection except during the explicit OAuth TOFU flow.
 * - **Secure** connections (WebSocket carrying the access token and all RPC traffic) are only
 *   established when the leaf fingerprint is in [pinned]; anything else is rejected during the TLS
 *   handshake, so the token can never reach an impostor (rogue Bonjour advertiser, hijacked relay…).
 *
 * Pins are global (not per host) because one Hub is reachable via several addresses (LAN IPs,
 * `.local` name, relay URL) while always presenting the same certificate.
 */
object HubTrust {
    private val pinnedRef = AtomicReference<Set<String>?>(null)
    private val observedRef = AtomicReference<Map<String, String>>(emptyMap())

    fun pinned(): Set<String> {
        pinnedRef.load()?.let { return it }
        val loaded = runCatching { loadPinnedHubFingerprints() }.getOrDefault(emptySet())
            .map { it.lowercase() }.toSet()
        pinnedRef.compareAndSet(null, loaded)
        return pinnedRef.load() ?: loaded
    }

    fun isPinned(fingerprint: String): Boolean = fingerprint.lowercase() in pinned()

    fun pin(fingerprint: String) {
        val fp = fingerprint.lowercase()
        while (true) {
            val cur = pinned()
            if (fp in cur) return
            val next = cur + fp
            if (pinnedRef.compareAndSet(cur, next)) {
                runCatching { savePinnedHubFingerprints(next) }
                return
            }
        }
    }

    fun clearPins() {
        pinnedRef.store(emptySet())
        runCatching { savePinnedHubFingerprints(emptySet()) }
    }

    fun recordObserved(host: String, port: Int, fingerprint: String) {
        val key = hostPortKey(host, port)
        while (true) {
            val cur = observedRef.load()
            if (cur[key] == fingerprint) return
            if (observedRef.compareAndSet(cur, cur + (key to fingerprint.lowercase()))) return
        }
    }

    fun observedFor(url: String): String? = observedRef.load()[hostPortKeyOf(url)]

    /** Test hook. */
    fun resetForTests(pins: Set<String> = emptySet()) {
        pinnedRef.store(pins)
        observedRef.store(emptyMap())
    }

    fun hostPortKey(host: String, port: Int): String =
        "${host.lowercase().trim('[', ']').trimEnd('.')}:$port"

    fun hostPortKeyOf(url: String): String {
        val noScheme = url.substringAfter("://")
        val authority = noScheme.substringBefore('/').substringAfter('@')
        val isTls = url.startsWith("https://") || url.startsWith("wss://")
        val defaultPort = if (isTls) 443 else 80
        val host: String
        val port: Int
        if (authority.startsWith("[")) {
            host = authority.substringBefore(']') + "]"
            port = authority.substringAfter("]:", "").toIntOrNull() ?: defaultPort
        } else {
            host = authority.substringBefore(':')
            port = authority.substringAfter(':', "").toIntOrNull() ?: defaultPort
        }
        return hostPortKey(host, port)
    }
}

/**
 * Lework Hubs only speak TLS. Normalises any stored / typed / discovered address to `https://`
 * (legacy `http://` relay URLs saved by older builds are upgraded transparently).
 */
fun normalizeHubBaseUrl(raw: String): String {
    val t = raw.trim().trimEnd('/')
    if (t.isEmpty()) return t
    return when {
        t.startsWith("https://") -> t
        t.startsWith("http://") -> "https://" + t.removePrefix("http://")
        t.startsWith("wss://") -> "https://" + t.removePrefix("wss://").substringBefore('/')
        t.startsWith("ws://") -> "https://" + t.removePrefix("ws://").substringBefore('/')
        else -> "https://$t"
    }
}

fun hubWsUrlOf(baseUrl: String, wsPath: String = "/v1/ws"): String =
    "wss://" + normalizeHubBaseUrl(baseUrl).removePrefix("https://") + wsPath

fun hubBaseUrlOfWs(wsUrl: String): String = normalizeHubBaseUrl(wsUrl)

private val TRUSTED_OAUTH_HOST_SUFFIXES = listOf(".feishu.cn", ".larksuite.com", ".feishu.net", ".larkoffice.com")

/** Only open OAuth pages on the IdP's own HTTPS hosts (a rogue Hub cannot redirect to a phishing page). */
fun isTrustedOAuthUrl(url: String): Boolean {
    if (!url.startsWith("https://")) return false
    val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
    if (host.contains('@')) return false
    return TRUSTED_OAUTH_HOST_SUFFIXES.any { host.endsWith(it) }
}

/** Links rendered from agent output may only open as plain web links. */
fun isSafeExternalLink(url: String): Boolean {
    val lower = url.trim().lowercase()
    return lower.startsWith("https://") || lower.startsWith("http://")
}
