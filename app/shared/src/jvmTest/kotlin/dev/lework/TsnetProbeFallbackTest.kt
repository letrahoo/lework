package dev.lework

import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.MobileTsnetPeer
import dev.lework.platform.MobileTsnetStatus
import dev.lework.store.ConsoleStore
import dev.lework.transport.DiscoveredEndpoint
import dev.lework.transport.HubTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TsnetProbeFallbackTest {
    private val primary = "https://127.0.0.1:51001"
    private val fallback = "https://127.0.0.1:51002"

    @Test
    fun `without a saved token an authenticatable fallback beats a reachable primary`() =
        checkSelection(sameUserUrls = setOf(fallback), expectedUrl = fallback, expectedProbes = listOf(primary, fallback))

    @Test
    fun `when neither batch can handshake the primary is retained for the saved token`() =
        checkSelection(
            sameUserUrls = emptySet(),
            expectedUrl = primary,
            expectedProbes = listOf(primary, fallback),
            savedToken = "lework_dt.saved",
        )

    @Test
    fun `an authenticatable primary does not wait for a fallback probe`() =
        checkSelection(sameUserUrls = setOf(primary, fallback), expectedUrl = primary, expectedProbes = listOf(primary))

    private fun checkSelection(
        sameUserUrls: Set<String>,
        expectedUrl: String,
        expectedProbes: List<String>,
        savedToken: String? = null,
    ) = runTest {
        JvmPlatformTestHooks.reset()
        try {
            JvmPlatformTestHooks.savedToken = savedToken
            JvmPlatformTestHooks.tsnetAvailable = true
            JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(
                available = true,
                backendState = "Running",
                peers = listOf(
                    MobileTsnetPeer(hostName = "desktop", os = "macOS", localProxyUrl = primary),
                    MobileTsnetPeer(hostName = "mobile", os = "android", localProxyUrl = fallback),
                ),
            )
            val fake = FakeHubTransport()
            val probes = mutableListOf<String>()
            val transport = object : HubTransport by fake {
                override suspend fun probeDirectEndpoint(baseUrl: String, timeoutMs: Long): DiscoveredEndpoint? {
                    probes += baseUrl
                    val endpoint = fake.probeDirectEndpoint(baseUrl, timeoutMs) ?: return null
                    return endpoint.copy(discover = endpoint.discover.copy(tailscaleSameUser = baseUrl in sameUserUrls))
                }
            }
            val store = ConsoleStore(backgroundScope, transport)
            store.searchAndConnectViaTsnet()
            advanceTimeBy(3_000L) // Includes the two retry delays before the saved-token path.

            assertEquals(expectedProbes, probes.distinct())
            assertEquals(expectedUrl, store.state.value.discoveredBaseUrl)
            assertEquals(if (expectedUrl in sameUserUrls) 1 else 0, fake.tailscaleHandshakeCount)
            assertTrue(store.state.value.hasEnteredWorkspace)
        } finally {
            JvmPlatformTestHooks.reset()
        }
    }
}
