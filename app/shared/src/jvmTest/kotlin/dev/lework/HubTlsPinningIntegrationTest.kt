package dev.lework

import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.security.HubTrust
import dev.lework.transport.HubRpcClient
import dev.lework.transport.HubTrustException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real end-to-end check of TLS + certificate pinning + token-bound attestation between the Kotlin
 * client (OkHttp engine) and the TypeScript Hub. Spawns `hub/test/fixtures/tls-it-hub.ts` (a
 * genuine Hub and an impostor Hub). Skipped automatically when Node / tsx is unavailable.
 */
class HubTlsPinningIntegrationTest {
    private val token = "it-genuine-token-0123456789"
    private var proc: Process? = null
    private var genuinePort = 0
    private var impostorPort = 0
    private var fingerprint = ""

    @BeforeTest
    fun startHubs() {
        JvmPlatformTestHooks.reset()
        HubTrust.resetForTests()
        val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "hub/test/fixtures/tls-it-hub.ts").exists() } ?: return
        val tsx = File(repoRoot, "hub/node_modules/.bin/tsx")
        if (!tsx.exists()) return
        val p = ProcessBuilder(tsx.path, "test/fixtures/tls-it-hub.ts")
            .directory(File(repoRoot, "hub"))
            .redirectErrorStream(false)
            .apply { environment()["LEWORK_IT_TOKEN"] = token }
            .start()
        proc = p
        val line = p.inputStream.bufferedReader().readLine() ?: return
        genuinePort = Regex("\"genuinePort\":(\\d+)").find(line)!!.groupValues[1].toInt()
        impostorPort = Regex("\"impostorPort\":(\\d+)").find(line)!!.groupValues[1].toInt()
        fingerprint = Regex("\"fingerprint\":\"([0-9a-f]+)\"").find(line)!!.groupValues[1]
    }

    @AfterTest
    fun stopHubs() {
        proc?.destroy()
        proc?.waitFor(5, TimeUnit.SECONDS)
        HubTrust.resetForTests()
    }

    @Test
    fun tokenIsOnlyEverSentToTheAttestedPinnedHub() = runBlocking {
        if (genuinePort == 0) return@runBlocking // Node toolchain unavailable — skip
        println("[tls-it] genuine=$genuinePort impostor=$impostorPort fp=${fingerprint.take(16)}")
        val genuine = "https://127.0.0.1:$genuinePort"
        val impostor = "https://127.0.0.1:$impostorPort"
        val client = HubRpcClient(this)

        // 1. Discovery works over TLS (legacy http:// candidates are upgraded) and records the cert.
        val found = client.discoverIntranetHub(listOf("http://127.0.0.1:$genuinePort"))
        assertNotNull(found)
        assertEquals("wss://127.0.0.1:$genuinePort/v1/ws", found.wsUrl)
        assertTrue(found.discover.relayUrls.isEmpty())

        // 2. Nothing pinned yet: the token-carrying (pinned-only) WebSocket refuses the handshake.
        assertFailsWith<Throwable> { client.connectAndHello(found.wsUrl, token, "JVM") }

        // 3. Impostor cannot prove knowledge of our token → refused, nothing pinned.
        client.discoverIntranetHub(listOf(impostor))
        assertFailsWith<HubTrustException> { client.ensureHubTrusted(impostor, token) }
        assertTrue(HubTrust.pinned().isEmpty())

        // 4. Genuine Hub attests → its certificate is pinned → hello succeeds; relays only now.
        client.ensureHubTrusted(genuine, token)
        assertEquals(setOf(fingerprint), HubTrust.pinned())
        assertEquals(setOf(fingerprint), JvmPlatformTestHooks.pinnedFingerprints)
        val hello = client.connectAndHello(found.wsUrl, token, "JVM")
        assertEquals("hub_it", hello.hub.id)
        assertEquals(listOf("https://relay.example:64450"), hello.relayUrls)
        client.disconnect()

        // 5. Even holding the token, a connection to the impostor's (unpinned) cert is refused at TLS.
        assertFailsWith<Throwable> { client.connectAndHello("wss://127.0.0.1:$impostorPort/v1/ws", token, "JVM") }

        // 6. With a pin present, discovery prefers the pinned Hub over a faster impostor.
        val preferred = client.discoverIntranetHub(listOf(impostor, genuine))
        assertEquals(genuine, preferred?.baseUrl)

        // 7. Cross-host isolation: even though `genuine` is globally pinned, connecting with a
        //    different PairedHost's expectedFingerprint MUST fail before sending Host B's token!
        assertFailsWith<HubTrustException> {
            client.ensureHubTrusted(genuine, token, expectedFingerprint = "b".repeat(64))
        }
        client.disconnect()
    }
}
