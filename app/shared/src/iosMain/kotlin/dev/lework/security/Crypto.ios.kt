@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.lework.security

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

actual fun sha256(data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    val input = if (data.isEmpty()) ByteArray(1) else data
    input.usePinned { inPin ->
        out.usePinned { outPin ->
            CC_SHA256(inPin.addressOf(0), data.size.convert(), outPin.addressOf(0).reinterpret<UByteVar>())
        }
    }
    return out
}

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    val k = if (key.isEmpty()) ByteArray(1) else key
    val d = if (data.isEmpty()) ByteArray(1) else data
    k.usePinned { kp ->
        d.usePinned { dp ->
            out.usePinned { op ->
                CCHmac(
                    kCCHmacAlgSHA256,
                    kp.addressOf(0),
                    key.size.convert(),
                    dp.addressOf(0),
                    data.size.convert(),
                    op.addressOf(0),
                )
            }
        }
    }
    return out
}

actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    if (size == 0) return out
    out.usePinned { p ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.convert(), p.addressOf(0))
        check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    }
    return out
}
