package dev.lework.platform

import dev.lework.security.toHex
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmLocalHubSeedingTest {

    private val testCertPem = """
        -----BEGIN CERTIFICATE-----
        MIIBjDCCATKgAwIBAgIUJyWTF2eopZCpM16mm0gDTpqpS5EwCgYIKoZIzj0EAwIw
        LDEZMBcGA1UEAwwQbGV3b3JrLXVuaXQtdGVzdDEPMA0GA1UECgwGTGV3b3JrMB4X
        DTI2MTEyNzAyMzQzMloXDTM2MTEyNDAyMzQzMlowLDEZMBcGA1UEAwwQbGV3b3Jr
        LXVuaXQtdGVzdDEPMA0GA1UECgwGTGV3b3JrMFkwEwYHKoZIzj0CAQYIKoZIzj0D
        AQcDQgAE31zc+TTJVockvBIP15n0dkR9MsH3DIzjzqbCPhfiYyEZt3kPIsfSBaKl
        SQPjAnT7cC43ww3IpX7bigaobudWtaMyMDAwHQYDVR0OBBYEFEIdCWVPMMgurGzK
        sso7XQ4HOwMqMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAIv0
        d2owaXavjVB9uzEgacpJk1PrH5xD/HMV3aiwwrqzAiBFgCbE1Jrf77fEqbMVrVvV
        mVGIOSOMrOh6997V93lHeg==
        -----END CERTIFICATE-----
    """.trimIndent() + "\n"

    private fun expectedSha256OfPem(pem: String): String {
        val cf = CertificateFactory.getInstance("X.509")
        val x509 = ByteArrayInputStream(pem.toByteArray()).use { cf.generateCertificate(it) as X509Certificate }
        return MessageDigest.getInstance("SHA-256").digest(x509.encoded).toHex().lowercase()
    }

    @Test
    fun `reads hub-cert pem written by hub tls module and seeds fresh desktop state`() {
        val tempHome = Files.createTempDirectory("lework-home-seed-").toFile()
        try {
            File(tempHome, "config.json").writeText(
                """
                {
                  "hubId": "hub_local_mac_01",
                  "name": "Dev Mac mini",
                  "port": 64450,
                  "token": "local-admin-token-0123456789"
                }
                """.trimIndent(),
            )
            val tlsDir = File(tempHome, "tls").apply { mkdirs() }
            File(tlsDir, "hub-cert.pem").writeText(testCertPem)

            val seed = JvmDesktopRuntime.readLocalMacHubSeed(tempHome)
            assertNotNull(seed)
            assertEquals("hub_local_mac_01", seed.hubId)
            assertEquals("Dev Mac mini", seed.name)
            assertEquals(64450, seed.port)
            assertEquals("local-admin-token-0123456789", seed.token)
            assertEquals("https://127.0.0.1:64450", seed.loopbackUrl)
            assertEquals("hub-cert.pem", seed.certFileName)
            assertEquals(expectedSha256OfPem(testCertPem), seed.fingerprint)

            val seeded = JvmDesktopRuntime.applyLocalMacHubSeed(PersistedDesktopState(), seed, nowMs = 1_700_000_000_000L)
            assertEquals("hub_local_mac_01", seeded.activeHostId)
            assertEquals("local-admin-token-0123456789", seeded.savedToken)
            assertEquals("local-admin-token-0123456789", seeded.hostTokens["hub_local_mac_01"])
            assertTrue(seed.fingerprint in seeded.pinnedFingerprints)
            assertEquals(1, seeded.pairedHosts.size)
            assertEquals(listOf("https://127.0.0.1:64450"), seeded.pairedHosts.first().endpoints)
        } finally {
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun `falls back to legacy cert pem and preserves existing custom host alias and remote activeHostId`() {
        val tempHome = Files.createTempDirectory("lework-home-legacy-").toFile()
        try {
            File(tempHome, "config.json").writeText(
                """
                {
                  "hubId": "hub_local_mac_02",
                  "name": "Studio Mac",
                  "port": 64451,
                  "token": "local-token-9876543210"
                }
                """.trimIndent(),
            )
            val tlsDir = File(tempHome, "tls").apply { mkdirs() }
            File(tlsDir, "cert.pem").writeText(testCertPem)

            val seed = JvmDesktopRuntime.readLocalMacHubSeed(tempHome)
            assertNotNull(seed)
            assertEquals("cert.pem", seed.certFileName)

            val initial = PersistedDesktopState(
                activeHostId = "hub_remote_devbox",
                savedToken = "remote-token",
                pairedHosts = listOf(
                    PairedHost(
                        hubId = "hub_remote_devbox",
                        name = "Remote Box",
                        endpoints = listOf("https://100.64.0.9:64450"),
                    ),
                    PairedHost(
                        hubId = "hub_local_mac_02",
                        name = "Studio Mac",
                        customName = "My Local Mini",
                        endpoints = listOf("https://192.168.1.50:64451"),
                    ),
                ),
            )

            val updated = JvmDesktopRuntime.applyLocalMacHubSeed(initial, seed, nowMs = 1_700_000_000_000L)
            assertEquals("hub_remote_devbox", updated.activeHostId)
            assertEquals("remote-token", updated.savedToken)
            assertEquals("local-token-9876543210", updated.hostTokens["hub_local_mac_02"])
            val localHost = updated.pairedHosts.first { it.hubId == "hub_local_mac_02" }
            assertEquals("My Local Mini", localHost.customName)
            assertEquals(listOf("https://127.0.0.1:64451", "https://192.168.1.50:64451"), localHost.endpoints)
        } finally {
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun `returns null when tls certificate or config is missing`() {
        val tempHome = Files.createTempDirectory("lework-home-empty-").toFile()
        try {
            File(tempHome, "config.json").writeText("""{"hubId":"hub_1","token":"tok"}""")
            assertNull(JvmDesktopRuntime.readLocalMacHubSeed(tempHome))
        } finally {
            tempHome.deleteRecursively()
        }
    }
}
