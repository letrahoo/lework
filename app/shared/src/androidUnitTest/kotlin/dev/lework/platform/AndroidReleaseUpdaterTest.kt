package dev.lework.platform

import dev.lework.store.formatReleaseSizeMb
import dev.lework.store.resolveApkCandidateFromSha256Sums
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidReleaseUpdaterTest {
    @Test
    fun sanitizeApkFileNamePreventsTraversalAndEnforcesSuffix() {
        assertEquals(
            "lework-v0.9.3-android-release.apk",
            sanitizeApkFileName("lework-v0.9.3-android-release.apk"),
        )
        assertEquals(
            "evil.apk",
            sanitizeApkFileName("../../etc/evil.apk"),
        )
        assertEquals(
            "lework_v0.9.3.apk",
            sanitizeApkFileName("lework v0.9.3"),
        )
        assertEquals(
            "lework-update.apk",
            sanitizeApkFileName("..."),
        )
    }

    @Test
    fun rewriteReleaseAssetDownloadUrlOnlyAcceptsTrustedGitHubHosts() {
        val original = "https://github.com/letrahoo/lework/releases/download/v0.9.3/lework-v0.9.3-android-release.apk"
        assertEquals(
            "https://github.com/letrahoo/lework/releases/download/v0.9.3/lework-v0.9.3-android-debug.apk",
            rewriteReleaseAssetDownloadUrl(original, "lework-v0.9.3-android-debug.apk"),
        )
        assertNull(
            rewriteReleaseAssetDownloadUrl(
                "https://evil.example/releases/download/v0.9.3/lework.apk",
                "lework-v0.9.3-android-release.apk",
            ),
        )
    }

    @Test
    fun resolveApkCandidateFromSha256SumsHandlesExactMatchAndDebugFallback() {
        val debugOnlySums = """
            45fb1eae74494488241af714fb2dfa4e4dede3f081f71aea4a23a8afcba601ff  lework-v0.9.3-android-debug.apk
            3576139add0c0a28831237cec52431539015fadc4017b8a7f695830d5c0dae49  Lework-v0.9.3-macos-arm64.dmg
        """.trimIndent()
        assertEquals(
            "lework-v0.9.3-android-debug.apk" to "45fb1eae74494488241af714fb2dfa4e4dede3f081f71aea4a23a8afcba601ff",
            resolveApkCandidateFromSha256Sums(debugOnlySums, "lework-v0.9.3-android-release.apk"),
        )

        val bothSums = """
            1111111111111111111111111111111111111111111111111111111111111111  lework-v0.9.3-android-debug.apk
            2222222222222222222222222222222222222222222222222222222222222222  lework-v0.9.3-android-release.apk
        """.trimIndent()
        assertEquals(
            "lework-v0.9.3-android-release.apk" to "2222222222222222222222222222222222222222222222222222222222222222",
            resolveApkCandidateFromSha256Sums(bothSums, "lework-v0.9.3-android-release.apk"),
        )
        assertEquals(
            "lework-v0.9.3-android-release.apk" to "2222222222222222222222222222222222222222222222222222222222222222",
            resolveApkCandidateFromSha256Sums(bothSums, "other-name.apk"),
        )
    }

    @Test
    fun formatTransferAndReleaseSizeHelpersStayHumanReadable() {
        assertEquals("0.0", formatTransferMegabytes(0L))
        assertEquals("0.1", formatTransferMegabytes(4096L))
        assertEquals("41.0", formatTransferMegabytes(41L * 1024L * 1024L))
        assertNull(formatReleaseSizeMb(0L))
        assertEquals("41.0 MB", formatReleaseSizeMb(41L * 1024L * 1024L))
    }

    @Test
    fun sha256HelpersComputeExpectedDigestAndHonorCancellationCheck() {
        val emptyExpected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(emptyExpected, sha256HexOfBytes(ByteArray(0)))

        val sample = "abc".encodeToByteArray()
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, sha256HexOfBytes(sample))

        val tmp = File.createTempFile("lework-sha-test-", ".apk")
        try {
            tmp.writeBytes(sample)
            var checkCount = 0
            assertEquals(expected, sha256HexOfFile(tmp) { checkCount++ })
            assertTrue(checkCount > 0)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun evaluateApkCompatibilityDetectsCorruptionPackageMismatchDowngradeAndSigningMismatch() {
        val certA = setOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val certB = setOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

        assertIs<ApkCompatibilityResult.Compatible>(
            evaluateApkCompatibility(
                expectedPackageName = "dev.lework.app",
                currentVersionCode = 90200L,
                installedSignerCerts = certA,
                archivePackageName = "dev.lework.app",
                archiveVersionCode = 90300L,
                archiveSignerCerts = certA,
            ),
        )

        // When OEM PackageManager omits signer info on archive, avoid false-positive rejections
        assertIs<ApkCompatibilityResult.Compatible>(
            evaluateApkCompatibility(
                expectedPackageName = "dev.lework.app",
                currentVersionCode = 90200L,
                installedSignerCerts = certA,
                archivePackageName = "dev.lework.app",
                archiveVersionCode = 90300L,
                archiveSignerCerts = emptySet(),
            ),
        )

        val corrupt = assertIs<ApkCompatibilityResult.Incompatible>(
            evaluateApkCompatibility(
                expectedPackageName = "dev.lework.app",
                currentVersionCode = 90200L,
                installedSignerCerts = certA,
                archivePackageName = null,
                archiveVersionCode = 0L,
                archiveSignerCerts = emptySet(),
            ),
        )
        assertTrue(corrupt.reason.contains("损坏"))

        val wrongPkg = assertIs<ApkCompatibilityResult.Incompatible>(
            evaluateApkCompatibility(
                expectedPackageName = "dev.lework.app",
                currentVersionCode = 90200L,
                installedSignerCerts = certA,
                archivePackageName = "com.other.app",
                archiveVersionCode = 90300L,
                archiveSignerCerts = certA,
            ),
        )
        assertTrue(wrongPkg.reason.contains("com.other.app"))

        val downgrade = assertIs<ApkCompatibilityResult.Incompatible>(
            evaluateApkCompatibility(
                expectedPackageName = "dev.lework.app",
                currentVersionCode = 90300L,
                installedSignerCerts = certA,
                archivePackageName = "dev.lework.app",
                archiveVersionCode = 90200L,
                archiveSignerCerts = certA,
            ),
        )
        assertTrue(downgrade.reason.contains("低于当前已安装版本"))

        val badCert = assertIs<ApkCompatibilityResult.Incompatible>(
            evaluateApkCompatibility(
                expectedPackageName = "dev.lework.app",
                currentVersionCode = 90200L,
                installedSignerCerts = certA,
                archivePackageName = "dev.lework.app",
                archiveVersionCode = 90300L,
                archiveSignerCerts = certB,
            ),
        )
        assertTrue(badCert.reason.contains("签名证书"))
    }
}
