package dev.lework.security

/** SHA-256 digest (platform-native: CommonCrypto on iOS, JCA on JVM/Android). */
expect fun sha256(data: ByteArray): ByteArray

/** HMAC-SHA256 (platform-native). */
expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** Cryptographically secure random bytes (SecRandomCopyBytes / SecureRandom). */
expect fun secureRandomBytes(size: Int): ByteArray

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0f]
    }
    return out.concatToString()
}

/** Constant-time comparison of two lowercase hex / ASCII strings. */
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}

/**
 * Token-bound Hub attestation, mirrored from `hub/src/security/tls.ts`.
 *
 * kid   = hex(SHA-256("lework-kid\0" + token))[0..16]
 * proof = hex(HMAC-SHA256(SHA-256(token), "lework-attest-v1\n" + fingerprint + "\n" + nonce))
 */
object HubAttestation {
    fun kid(token: String): String =
        sha256("lework-kid\u0000$token".encodeToByteArray()).toHex().substring(0, 16)

    fun proof(token: String, fingerprint: String, nonce: String): String =
        hmacSha256(
            sha256(token.encodeToByteArray()),
            "lework-attest-v1\n${fingerprint.lowercase()}\n$nonce".encodeToByteArray(),
        ).toHex()

    fun newNonce(): String = secureRandomBytes(16).toHex()

    /** `109B-CFF4-3059-00DB` style short code for out-of-band comparison. */
    fun shortFingerprint(fp: String): String =
        fp.take(16).chunked(4).joinToString("-").uppercase()
}
