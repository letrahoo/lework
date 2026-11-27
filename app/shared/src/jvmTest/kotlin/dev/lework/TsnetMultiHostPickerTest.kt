package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.MobileTsnetPeer
import dev.lework.platform.MobileTsnetStatus
import dev.lework.platform.PairedHost
import dev.lework.protocol.DirectTokenAuthResult
import dev.lework.protocol.ErrorCode
import dev.lework.protocol.HubDiscoverResult
import dev.lework.protocol.HubHelloResult
import dev.lework.protocol.HubInfo
import dev.lework.protocol.HubRole
import dev.lework.protocol.HubStatusResult
import dev.lework.protocol.OAuthPollResponse
import dev.lework.protocol.OAuthUser
import dev.lework.protocol.PROTOCOL_VERSION
import dev.lework.protocol.PlatformType
import dev.lework.security.hubBaseUrlOfWs
import dev.lework.security.hubWsUrlOf
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleStore
import dev.lework.store.ConsoleUiState
import dev.lework.store.DiscoveredTsnetHub
import dev.lework.transport.DiscoveredEndpoint
import dev.lework.transport.HubTransport
import dev.lework.transport.RpcCallException
import dev.lework.ui.AuthEntryView
import dev.lework.ui.desktop.DesktopAuthScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class TsnetMultiHostPickerTest {
    private val macHub = HubInfo(
        id = "hub_mac",
        name = "Studio Mac mini",
        role = HubRole.STANDALONE,
        platform = PlatformType.DARWIN,
        version = "0.8.14",
    )
    private val devboxHub = HubInfo(
        id = "hub_devbox",
        name = "Linux Devbox",
        role = HubRole.STANDALONE,
        platform = PlatformType.LINUX,
        version = "0.8.14",
    )
    private val ssoHub = HubInfo(
        id = "hub_team_sso",
        name = "Team GPU Server",
        role = HubRole.STANDALONE,
        platform = PlatformType.LINUX,
        version = "0.8.14",
    )

    private val macOldUrl = "https://127.0.0.1:52001"
    private val macMainUrl = "https://127.0.0.1:52002"
    private val devboxUrl = "https://127.0.0.1:52003"
    private val ssoUrl = "https://127.0.0.1:52004"

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @AfterTest
    fun tearDown() {
        JvmPlatformTestHooks.reset()
    }

    private data class EndpointConfig(
        val hub: HubInfo,
        val tailscaleSameUser: Boolean,
        val tailscaleUser: String? = if (tailscaleSameUser) "Owner" else null,
        val oauthEnabled: Boolean = false,
        val oauthProvider: String? = null,
        val oauthDisplayName: String? = null,
        val tailscaleEndpoint: String = "https://100.88.1.2:64450",
        val handshakeError: Throwable? = null,
        val handshakeGate: CompletableDeferred<Unit>? = null,
        val connectError: Throwable? = null,
    )

    private fun buildMultiHostTransport(
        fake: FakeHubTransport,
        endpoints: Map<String, EndpointConfig>,
        handshakeUrls: MutableList<String> = mutableListOf(),
        connectCalls: MutableList<Pair<String, String>> = mutableListOf(),
        oauthPollCalls: MutableList<String> = mutableListOf(),
    ): HubTransport {
        var connectedHub: HubInfo = fake.hubInfo
        return object : HubTransport by fake {
            override suspend fun probeDirectEndpoint(baseUrl: String, timeoutMs: Long): DiscoveredEndpoint? {
                val cfg = endpoints[baseUrl] ?: return null
                return DiscoveredEndpoint(
                    baseUrl = baseUrl,
                    wsUrl = hubWsUrlOf(baseUrl),
                    discover = HubDiscoverResult(
                        ok = true,
                        hub = cfg.hub,
                        oauthEnabled = cfg.oauthEnabled,
                        oauthProvider = cfg.oauthProvider,
                        oauthDisplayName = cfg.oauthDisplayName,
                        tailscaleSameUser = cfg.tailscaleSameUser,
                        tailscaleUser = cfg.tailscaleUser,
                    ),
                )
            }

            override suspend fun authenticateViaTailscale(
                baseUrl: String,
                deviceName: String,
            ): DirectTokenAuthResult {
                handshakeUrls += baseUrl
                val cfg = endpoints[baseUrl]
                    ?: throw IllegalStateException("Unexpected handshake URL: $baseUrl")
                cfg.handshakeGate?.await()
                cfg.handshakeError?.let { throw it }
                fake.tailscaleHandshakeCount++
                return DirectTokenAuthResult(
                    ok = true,
                    accessToken = "lework_dt.${cfg.hub.id.ifBlank { "blank" }}_token",
                    user = OAuthUser(
                        subject = "tailscale:owner@example.com",
                        name = cfg.tailscaleUser ?: "Owner",
                        email = "owner@example.com",
                    ),
                    fingerprint = "a".repeat(64),
                    proof = "b".repeat(64),
                    hub = cfg.hub,
                    endpoints = listOf(cfg.tailscaleEndpoint),
                )
            }

            override suspend fun pollOAuthDeviceFlow(baseUrl: String, sid: String): OAuthPollResponse {
                oauthPollCalls += baseUrl
                return fake.pollOAuthDeviceFlow(baseUrl, sid)
            }

            override suspend fun connectAndHello(
                wsUrl: String,
                token: String,
                platformName: String,
            ): HubHelloResult {
                connectCalls += wsUrl to token
                val baseUrl = hubBaseUrlOfWs(wsUrl)
                endpoints[baseUrl]?.connectError?.let { throw it }
                connectedHub = endpoints[baseUrl]?.hub ?: fake.hubInfo
                fake.connectAndHello(wsUrl, token, platformName)
                return HubHelloResult(
                    protocolVersion = PROTOCOL_VERSION,
                    hub = connectedHub,
                    capabilities = listOf("stream", "approvals"),
                )
            }

            override suspend fun fetchStatus(): HubStatusResult {
                val base = fake.fetchStatus()
                return base.copy(hub = connectedHub)
            }
        }
    }

    @Test
    fun `single discovered tsnet hub auto-handshakes and enters workspace immediately`() = runTest {
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        JvmPlatformTestHooks.savedToken = null
        JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = listOf(
                MobileTsnetPeer(
                    hostName = "studio-mac-mini",
                    tailscaleIp = "100.88.1.11",
                    os = "macOS",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = macMainUrl,
                ),
            ),
        )
        val fake = FakeHubTransport()
        val handshakeUrls = mutableListOf<String>()
        val transport = buildMultiHostTransport(
            fake = fake,
            endpoints = mapOf(
                macMainUrl to EndpointConfig(
                    hub = macHub,
                    tailscaleSameUser = true,
                    tailscaleEndpoint = "https://100.88.1.11:64450",
                ),
            ),
            handshakeUrls = handshakeUrls,
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.searchAndConnectViaTsnet()
            runCurrent()

            assertEquals(listOf(macMainUrl), handshakeUrls)
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)
            assertEquals("hub_mac", store.state.value.hub?.id)
            assertTrue(store.state.value.discoveredTsnetHubs.isEmpty())
            assertNull(store.state.value.connectingTsnetHubId)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `multiple discovered tsnet hubs deduplicate by hub id preferring same-user and pause for selection`() = runTest {
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        JvmPlatformTestHooks.savedToken = null
        JvmPlatformTestHooks.activeHostId = "hub_mac"
        JvmPlatformTestHooks.pairedHosts = listOf(
            PairedHost(
                hubId = "hub_mac",
                name = "Studio Mac mini",
                endpoints = listOf("https://100.88.1.10:64450", "https://100.88.1.11:64450"),
            ),
        )
        JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = listOf(
                MobileTsnetPeer(
                    hostName = "studio-mac-old",
                    tailscaleIp = "100.88.1.10",
                    os = "macOS",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = macOldUrl,
                ),
                MobileTsnetPeer(
                    hostName = "studio-mac-main",
                    tailscaleIp = "100.88.1.11",
                    os = "macOS",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = macMainUrl,
                ),
                MobileTsnetPeer(
                    hostName = "devbox-01",
                    tailscaleIp = "100.88.2.20",
                    os = "linux",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = devboxUrl,
                ),
            ),
        )

        val fake = FakeHubTransport()
        val handshakeUrls = mutableListOf<String>()
        val transport = buildMultiHostTransport(
            fake = fake,
            endpoints = mapOf(
                macOldUrl to EndpointConfig(hub = macHub, tailscaleSameUser = false),
                macMainUrl to EndpointConfig(
                    hub = macHub,
                    tailscaleSameUser = true,
                    tailscaleEndpoint = "https://100.88.1.11:64450",
                ),
                devboxUrl to EndpointConfig(
                    hub = devboxHub,
                    tailscaleSameUser = true,
                    tailscaleEndpoint = "https://100.88.2.20:64450",
                ),
            ),
            handshakeUrls = handshakeUrls,
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.searchAndConnectViaTsnet()
            runCurrent()

            val st = store.state.value
            assertFalse(st.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.NEEDS_AUTH, st.connectionStatus)
            assertFalse(st.oauthEnabled)
            assertNull(st.connectionError)
            assertNull(st.connectingTsnetHubId)
            assertEquals("发现 2 台可用主机，请选择要连接的设备", st.authStatusText)
            assertTrue(handshakeUrls.isEmpty(), "Should not blindly handshake when multiple hubs exist")

            assertEquals(2, st.discoveredTsnetHubs.size)
            val first = st.discoveredTsnetHubs[0]
            assertEquals("hub_mac", first.hub.id)
            assertEquals(macMainUrl, first.baseUrl)
            assertEquals("studio-mac-main", first.hostName)
            assertEquals("100.88.1.11", first.tailscaleIp)
            assertTrue(first.tailscaleSameUser)
            assertTrue(first.alreadyPaired)
            assertTrue(first.isCurrentActive)

            val second = st.discoveredTsnetHubs[1]
            assertEquals("hub_devbox", second.hub.id)
            assertEquals(devboxUrl, second.baseUrl)
            assertEquals("devbox-01", second.hostName)
            assertEquals("100.88.2.20", second.tailscaleIp)
            assertTrue(second.tailscaleSameUser)
            assertFalse(second.alreadyPaired)
            assertFalse(second.isCurrentActive)

            // Explicitly select the second hub (Devbox)
            store.selectDiscoveredTsnetHub("hub_devbox")
            runCurrent()

            val afterSelect = store.state.value
            assertEquals(listOf(devboxUrl), handshakeUrls)
            assertTrue(afterSelect.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.CONNECTED, afterSelect.connectionStatus)
            assertEquals("hub_devbox", afterSelect.hub?.id)
            assertEquals(devboxUrl, afterSelect.discoveredBaseUrl)
            assertEquals("hub_devbox", afterSelect.activeHostId)
            assertTrue(afterSelect.pairedHosts.any { it.hubId == "hub_devbox" })
            assertEquals("lework_dt.hub_devbox_token", JvmPlatformTestHooks.hostTokens["hub_devbox"])
            assertTrue(afterSelect.discoveredTsnetHubs.isEmpty())
            assertNull(afterSelect.connectingTsnetHubId)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `handshake failure on first hub keeps picker list intact so choosing second hub succeeds immediately`() = runTest {
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        // Keep an unrelated global saved token to prove a failed same-user handshake does not leak it to another hub.
        JvmPlatformTestHooks.savedToken = "lework_dt.unrelated_host_token"
        JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = listOf(
                MobileTsnetPeer(
                    hostName = "studio-mac-main",
                    tailscaleIp = "100.88.1.11",
                    os = "macOS",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = macMainUrl,
                ),
                MobileTsnetPeer(
                    hostName = "devbox-01",
                    tailscaleIp = "100.88.2.20",
                    os = "linux",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = devboxUrl,
                ),
            ),
        )

        val fake = FakeHubTransport()
        val handshakeUrls = mutableListOf<String>()
        val transport = buildMultiHostTransport(
            fake = fake,
            endpoints = mapOf(
                macMainUrl to EndpointConfig(
                    hub = macHub,
                    tailscaleSameUser = true,
                    handshakeError = RuntimeException("Tailscale handshake refused on Studio Mac"),
                ),
                devboxUrl to EndpointConfig(
                    hub = devboxHub,
                    tailscaleSameUser = true,
                    tailscaleEndpoint = "https://100.88.2.20:64450",
                ),
            ),
            handshakeUrls = handshakeUrls,
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.searchAndConnectViaTsnet()
            runCurrent()
            assertEquals(2, store.state.value.discoveredTsnetHubs.size)

            // Click Hub 1 -> fails handshake
            store.selectDiscoveredTsnetHub("hub_mac")
            runCurrent()

            val afterFail = store.state.value
            assertFalse(afterFail.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.ERROR, afterFail.connectionStatus)
            assertNotNull(afterFail.connectionError)
            assertTrue(afterFail.connectionError!!.contains("handshake refused"))
            assertNull(afterFail.connectingTsnetHubId)
            assertEquals(2, afterFail.discoveredTsnetHubs.size)

            // Bonjour LAN broadcast while showing error on picker must NOT wipe discoveredTsnetHubs
            JvmPlatformTestHooks.setBonjourUrls(listOf("https://192.168.1.44:64450"))
            runCurrent()
            assertEquals(2, store.state.value.discoveredTsnetHubs.size)

            // Click Hub 2 immediately without re-scanning -> connects cleanly
            store.selectDiscoveredTsnetHub("hub_devbox")
            runCurrent()

            val afterRetry = store.state.value
            assertEquals(listOf(macMainUrl, devboxUrl), handshakeUrls)
            assertTrue(afterRetry.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.CONNECTED, afterRetry.connectionStatus)
            assertEquals("hub_devbox", afterRetry.hub?.id)
            assertTrue(afterRetry.discoveredTsnetHubs.isEmpty())
            assertNull(afterRetry.connectingTsnetHubId)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `selecting non-same-user oauth hub starts oauth login for that host and double-click is guarded while connecting`() = runTest {
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        JvmPlatformTestHooks.savedToken = null
        JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = listOf(
                MobileTsnetPeer(
                    hostName = "studio-mac-main",
                    tailscaleIp = "100.88.1.11",
                    os = "macOS",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = macMainUrl,
                ),
                MobileTsnetPeer(
                    hostName = "team-gpu-01",
                    tailscaleIp = "100.88.9.9",
                    os = "linux",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = ssoUrl,
                ),
            ),
        )

        val macGate = CompletableDeferred<Unit>()
        val fake = FakeHubTransport()
        val handshakeUrls = mutableListOf<String>()
        val transport = buildMultiHostTransport(
            fake = fake,
            endpoints = mapOf(
                macMainUrl to EndpointConfig(
                    hub = macHub,
                    tailscaleSameUser = true,
                    handshakeGate = macGate,
                ),
                ssoUrl to EndpointConfig(
                    hub = ssoHub,
                    tailscaleSameUser = false,
                    oauthEnabled = true,
                    oauthProvider = "feishu",
                    oauthDisplayName = "飞书",
                ),
            ),
            handshakeUrls = handshakeUrls,
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.searchAndConnectViaTsnet()
            runCurrent()
            assertEquals(2, store.state.value.discoveredTsnetHubs.size)

            // First test OAuth fallback when picking non-same-user SSO hub
            store.selectDiscoveredTsnetHub("hub_team_sso")
            runCurrent()
            assertEquals(ssoUrl, store.state.value.discoveredBaseUrl)
            assertTrue(store.state.value.oauthEnabled)
            assertNotNull(store.state.value.oauthChallenge)
            assertNull(store.state.value.connectingTsnetHubId)

            // Now pick Mac hub while gated -> verify connectingTsnetHubId blocks concurrent second click
            store.selectDiscoveredTsnetHub("hub_mac")
            runCurrent()
            assertEquals("hub_mac", store.state.value.connectingTsnetHubId)
            assertEquals(ConnectionStatus.CONNECTING, store.state.value.connectionStatus)

            // Second click while in-flight is ignored
            store.selectDiscoveredTsnetHub("hub_team_sso")
            runCurrent()
            assertEquals("hub_mac", store.state.value.connectingTsnetHubId)

            macGate.complete(Unit)
            runCurrent()
            assertEquals(listOf(macMainUrl), handshakeUrls)
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals("hub_mac", store.state.value.hub?.id)
            assertTrue(store.state.value.discoveredTsnetHubs.isEmpty())
            assertNull(store.state.value.connectingTsnetHubId)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `logoutMobileTsnetAccount and preparePairAnotherHost clear discoveredTsnetHubs and connectingTsnetHubId`() = runTest {
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        JvmPlatformTestHooks.savedToken = null
        JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = listOf(
                MobileTsnetPeer(
                    hostName = "studio-mac-main",
                    tailscaleIp = "100.88.1.11",
                    os = "macOS",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = macMainUrl,
                ),
                MobileTsnetPeer(
                    hostName = "devbox-01",
                    tailscaleIp = "100.88.2.20",
                    os = "linux",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = devboxUrl,
                ),
            ),
        )

        val fake = FakeHubTransport()
        val transport = buildMultiHostTransport(
            fake = fake,
            endpoints = mapOf(
                macMainUrl to EndpointConfig(hub = macHub, tailscaleSameUser = true),
                devboxUrl to EndpointConfig(hub = devboxHub, tailscaleSameUser = true),
            ),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.searchAndConnectViaTsnet()
            runCurrent()
            assertEquals(2, store.state.value.discoveredTsnetHubs.size)

            // preparePairAnotherHost clears picker state cleanly
            store.preparePairAnotherHost()
            runCurrent()
            assertTrue(store.state.value.discoveredTsnetHubs.isEmpty())
            assertNull(store.state.value.connectingTsnetHubId)

            // Discover again, then logoutMobileTsnetAccount clears picker state cleanly
            store.searchAndConnectViaTsnet()
            runCurrent()
            assertEquals(2, store.state.value.discoveredTsnetHubs.size)

            store.logoutMobileTsnetAccount()
            runCurrent()
            assertTrue(store.state.value.discoveredTsnetHubs.isEmpty())
            assertNull(store.state.value.connectingTsnetHubId)
            assertEquals("NeedsLogin", store.state.value.tsnetStatus.backendState)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `unpaired non-same-user hub does not leak paired active hub global savedToken`() = runTest {
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        JvmPlatformTestHooks.activeHostId = "hub_mac"
        JvmPlatformTestHooks.pairedHosts = listOf(
            PairedHost(
                hubId = "hub_mac",
                name = "Studio Mac mini",
                endpoints = listOf("https://100.88.1.11:64450"),
            ),
        )
        JvmPlatformTestHooks.savedToken = "lework_dt.hub_mac_secret_global"
        JvmPlatformTestHooks.hostTokens["hub_mac"] = "lework_dt.hub_mac_secret_host"
        JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = listOf(
                MobileTsnetPeer(
                    hostName = "studio-mac-main",
                    tailscaleIp = "100.88.1.11",
                    os = "macOS",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = macMainUrl,
                ),
                MobileTsnetPeer(
                    hostName = "team-gpu-01",
                    tailscaleIp = "100.88.9.9",
                    os = "linux",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = ssoUrl,
                ),
            ),
        )

        val fake = FakeHubTransport()
        val connectCalls = mutableListOf<Pair<String, String>>()
        val transport = buildMultiHostTransport(
            fake = fake,
            endpoints = mapOf(
                macMainUrl to EndpointConfig(hub = macHub, tailscaleSameUser = true),
                ssoUrl to EndpointConfig(
                    hub = ssoHub,
                    tailscaleSameUser = false,
                    oauthEnabled = true,
                    oauthProvider = "feishu",
                    oauthDisplayName = "飞书",
                ),
            ),
            connectCalls = connectCalls,
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.searchAndConnectViaTsnet()
            runCurrent()
            assertEquals(2, store.state.value.discoveredTsnetHubs.size)

            // Selecting unpaired non-same-user hub must NOT attempt connectWithToken with hub_mac's savedToken
            store.selectDiscoveredTsnetHub("hub_team_sso")
            runCurrent()

            assertTrue(connectCalls.isEmpty(), "Should not leak hub_mac's global token to hub_team_sso")
            assertFalse(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.NEEDS_AUTH, store.state.value.connectionStatus)
            assertTrue(store.state.value.oauthEnabled)
            assertNotNull(store.state.value.oauthChallenge)
            assertEquals(ssoUrl, store.state.value.discoveredBaseUrl)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `logoutMobileTsnetAccount cleans up error state, stops oauth polling, and resets stale tsnet oauth state`() = runTest {
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        JvmPlatformTestHooks.savedToken = null
        val runningStatus = MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = listOf(
                MobileTsnetPeer(
                    hostName = "studio-mac-main",
                    tailscaleIp = "100.88.1.11",
                    os = "macOS",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = macMainUrl,
                ),
                MobileTsnetPeer(
                    hostName = "team-gpu-01",
                    tailscaleIp = "100.88.9.9",
                    os = "linux",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = ssoUrl,
                ),
            ),
        )
        JvmPlatformTestHooks.tsnetStatus = runningStatus

        val fake = FakeHubTransport()
        val oauthPollCalls = mutableListOf<String>()
        val transport = buildMultiHostTransport(
            fake = fake,
            endpoints = mapOf(
                macMainUrl to EndpointConfig(
                    hub = macHub,
                    tailscaleSameUser = true,
                    handshakeError = RuntimeException("handshake denied"),
                ),
                ssoUrl to EndpointConfig(
                    hub = ssoHub,
                    tailscaleSameUser = false,
                    oauthEnabled = true,
                    oauthProvider = "feishu",
                    oauthDisplayName = "飞书",
                ),
            ),
            oauthPollCalls = oauthPollCalls,
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)

            // 1) Logout after handshake error clears connectionError, discoveredBaseUrl/WsUrl, and picker state
            store.searchAndConnectViaTsnet()
            runCurrent()
            store.selectDiscoveredTsnetHub("hub_mac")
            runCurrent()
            assertEquals(ConnectionStatus.ERROR, store.state.value.connectionStatus)
            assertNotNull(store.state.value.connectionError)

            store.logoutMobileTsnetAccount()
            runCurrent()
            val afterErrorLogout = store.state.value
            assertEquals(ConnectionStatus.NEEDS_AUTH, afterErrorLogout.connectionStatus)
            assertNull(afterErrorLogout.connectionError)
            assertFalse(afterErrorLogout.oauthEnabled)
            assertNull(afterErrorLogout.oauthChallenge)
            assertEquals("", afterErrorLogout.discoveredBaseUrl)
            assertEquals("", afterErrorLogout.discoveredWsUrl)
            assertTrue(afterErrorLogout.discoveredTsnetHubs.isEmpty())
            assertEquals("已退出 Tailscale 账号，请重新登录或选择其他方式连接", afterErrorLogout.authStatusText)

            // 2) Logout after non-same-user OAuth selection cancels oauthPollJob and resets OAuth UI
            JvmPlatformTestHooks.tsnetStatus = runningStatus
            store.searchAndConnectViaTsnet()
            runCurrent()
            store.selectDiscoveredTsnetHub("hub_team_sso")
            runCurrent()
            assertTrue(store.state.value.oauthEnabled)
            assertNotNull(store.state.value.oauthChallenge)

            advanceTimeBy(2_500L)
            runCurrent()
            val pollsBeforeLogout = oauthPollCalls.size
            assertTrue(pollsBeforeLogout >= 1, "Expected OAuth polling to be active before logout")

            store.logoutMobileTsnetAccount()
            runCurrent()
            advanceTimeBy(6_000L)
            runCurrent()

            val afterOAuthLogout = store.state.value
            assertEquals(pollsBeforeLogout, oauthPollCalls.size, "OAuth poll loop must stop after tsnet logout")
            assertEquals(ConnectionStatus.NEEDS_AUTH, afterOAuthLogout.connectionStatus)
            assertFalse(afterOAuthLogout.oauthEnabled)
            assertNull(afterOAuthLogout.oauthChallenge)
            assertNull(afterOAuthLogout.connectionError)
            assertEquals("", afterOAuthLogout.discoveredBaseUrl)
            assertEquals("", afterOAuthLogout.discoveredWsUrl)
            assertTrue(afterOAuthLogout.discoveredTsnetHubs.isEmpty())
            assertEquals("已退出 Tailscale 账号，请重新登录或选择其他方式连接", afterOAuthLogout.authStatusText)

            // 3) Starting a new tsnet scan when tsnet is NeedsLogin clears stale oauthEnabled (no infinite spinner)
            JvmPlatformTestHooks.tsnetStatus = runningStatus
            store.searchAndConnectViaTsnet()
            runCurrent()
            store.selectDiscoveredTsnetHub("hub_team_sso")
            runCurrent()
            assertTrue(store.state.value.oauthEnabled)

            JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(available = true, backendState = "NeedsLogin")
            store.searchAndConnectViaTsnet()
            runCurrent()
            assertEquals(ConnectionStatus.NEEDS_AUTH, store.state.value.connectionStatus)
            assertFalse(store.state.value.oauthEnabled)
            assertNull(store.state.value.oauthChallenge)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `same-user handshake followed by UNAUTHORIZED ws connect shows accurate auth error without blaming tsnet config`() = runTest {
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        JvmPlatformTestHooks.savedToken = null
        JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = listOf(
                MobileTsnetPeer(
                    hostName = "studio-mac-main",
                    tailscaleIp = "100.88.1.11",
                    os = "macOS",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = macMainUrl,
                ),
                MobileTsnetPeer(
                    hostName = "devbox-01",
                    tailscaleIp = "100.88.2.20",
                    os = "linux",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = devboxUrl,
                ),
            ),
        )

        val fake = FakeHubTransport()
        val transport = buildMultiHostTransport(
            fake = fake,
            endpoints = mapOf(
                macMainUrl to EndpointConfig(
                    hub = macHub,
                    tailscaleSameUser = true,
                    connectError = RpcCallException(ErrorCode.UNAUTHORIZED, "token rejected"),
                ),
                devboxUrl to EndpointConfig(hub = devboxHub, tailscaleSameUser = true),
            ),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.searchAndConnectViaTsnet()
            runCurrent()
            assertEquals(2, store.state.value.discoveredTsnetHubs.size)

            store.selectDiscoveredTsnetHub("hub_mac")
            runCurrent()

            val st = store.state.value
            assertFalse(st.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.ERROR, st.connectionStatus)
            assertNull(st.connectingTsnetHubId)
            assertEquals(2, st.discoveredTsnetHubs.size)
            val err = assertNotNull(st.connectionError)
            assertTrue(err.contains("已通过同账号校验"), "Expected accurate post-handshake auth failure message, got: $err")
            assertFalse(err.contains("未开启同账号校验"), "Must not mislead user about tsnet login config, got: $err")
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `selectionKey allows selecting a discovered hub even when hub id is blank`() = runTest {
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        JvmPlatformTestHooks.savedToken = null
        val blankIdHub = macHub.copy(id = "", name = "Legacy Hub")
        JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = listOf(
                MobileTsnetPeer(
                    hostName = "legacy-mac",
                    tailscaleIp = "100.88.1.11",
                    os = "macOS",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = macMainUrl,
                ),
                MobileTsnetPeer(
                    hostName = "devbox-01",
                    tailscaleIp = "100.88.2.20",
                    os = "linux",
                    online = true,
                    isLeworkNode = true,
                    localProxyUrl = devboxUrl,
                ),
            ),
        )

        val fake = FakeHubTransport()
        val handshakeUrls = mutableListOf<String>()
        val transport = buildMultiHostTransport(
            fake = fake,
            endpoints = mapOf(
                macMainUrl to EndpointConfig(hub = blankIdHub, tailscaleSameUser = true),
                devboxUrl to EndpointConfig(hub = devboxHub, tailscaleSameUser = true),
            ),
            handshakeUrls = handshakeUrls,
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.searchAndConnectViaTsnet()
            runCurrent()
            val discovered = store.state.value.discoveredTsnetHubs
            assertEquals(2, discovered.size)
            assertEquals(macMainUrl, discovered[0].selectionKey)

            store.selectDiscoveredTsnetHub(discovered[0].selectionKey)
            runCurrent()

            assertEquals(listOf(macMainUrl), handshakeUrls)
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertTrue(store.state.value.discoveredTsnetHubs.isEmpty())
            assertNull(store.state.value.connectingTsnetHubId)
        } finally {
            storeScope.cancel()
        }
    }

    private fun sampleDiscoveredHubs(): List<DiscoveredTsnetHub> = listOf(
        DiscoveredTsnetHub(
            hub = macHub,
            baseUrl = macMainUrl,
            wsUrl = hubWsUrlOf(macMainUrl),
            hostName = "studio-mac-main",
            tailscaleIp = "100.88.1.11",
            os = "macOS",
            tailscaleSameUser = true,
            tailscaleUser = "Owner",
            alreadyPaired = true,
            isCurrentActive = true,
        ),
        DiscoveredTsnetHub(
            hub = devboxHub,
            baseUrl = devboxUrl,
            wsUrl = hubWsUrlOf(devboxUrl),
            hostName = "devbox-01",
            tailscaleIp = "100.88.2.20",
            os = "linux",
            tailscaleSameUser = true,
            tailscaleUser = "Owner",
            alreadyPaired = true,
            isCurrentActive = false,
        ),
        DiscoveredTsnetHub(
            hub = HubInfo(
                id = "hub_win_gpu",
                name = "Win GPU Rig",
                role = HubRole.STANDALONE,
                platform = PlatformType.WIN32,
                version = "0.8.14",
            ),
            baseUrl = "https://127.0.0.1:52005",
            wsUrl = "wss://127.0.0.1:52005/ws",
            hostName = "win-gpu",
            tailscaleIp = "100.88.3.30",
            os = "windows",
            tailscaleSameUser = false,
            alreadyPaired = false,
            isCurrentActive = false,
        ),
    )

    @Test
    fun `mobile AuthEntryView renders discovered tsnet hubs with badges and invokes callback on click`() = runComposeUiTest {
        val selectedIds = mutableListOf<String>()
        val hubs = sampleDiscoveredHubs()
        val uiState = ConsoleUiState(
            hasEnteredWorkspace = false,
            connectionStatus = ConnectionStatus.NEEDS_AUTH,
            authStatusText = "发现 3 台可用主机，请选择要连接的设备",
            tsnetStatus = MobileTsnetStatus(
                available = true,
                backendState = "Running",
                loginName = "owner@example.com",
            ),
            discoveredTsnetHubs = hubs,
        )

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AuthEntryView(
                        state = uiState,
                        onLaunchOAuth = {},
                        onOpenBrowser = {},
                        onManualConnect = { _, _ -> },
                        onRetry = {},
                        onSelectTsnetHub = { selectedIds += it },
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithTag("tsnet_discovered_hub_list", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        onNodeWithText("发现 3 台可用主机 · 点击选择连接", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("tsnet_search_button").performScrollTo().assertTextContains("重新搜索节点")

        onNodeWithTag("tsnet_discovered_hub_hub_mac").performScrollTo().assertIsDisplayed()
        onNodeWithText("Studio Mac mini").assertIsDisplayed()
        onNodeWithText("studio-mac-main · 100.88.1.11").assertIsDisplayed()
        onNodeWithText("当前主机").assertIsDisplayed()

        onNodeWithTag("tsnet_discovered_hub_hub_devbox").performScrollTo().assertIsDisplayed()
        onNodeWithText("Linux Devbox").assertIsDisplayed()
        onNodeWithText("devbox-01 · 100.88.2.20").assertIsDisplayed()
        onNodeWithText("已配对").assertIsDisplayed()

        onNodeWithTag("tsnet_discovered_hub_hub_win_gpu").performScrollTo().assertIsDisplayed()
        onNodeWithText("Win GPU Rig").assertIsDisplayed()
        onNodeWithText("win-gpu · 100.88.3.30").assertIsDisplayed()

        onNodeWithTag("tsnet_discovered_hub_hub_devbox").performScrollTo().performClick()
        waitForIdle()

        assertEquals(listOf("hub_devbox"), selectedIds)
    }

    @Test
    fun `mobile AuthEntryView keeps picker visible on connecting spinner and error recovery`() = runComposeUiTest {
        val selectedIds = mutableListOf<String>()
        val hubs = sampleDiscoveredHubs()
        var currentState by mutableStateOf(
            ConsoleUiState(
                hasEnteredWorkspace = false,
                connectionStatus = ConnectionStatus.CONNECTING,
                authStatusText = "正在通过 Tailscale 连接主机 Studio Mac mini…",
                tsnetStatus = MobileTsnetStatus(
                    available = true,
                    backendState = "Running",
                    loginName = "owner@example.com",
                ),
                discoveredTsnetHubs = hubs,
                connectingTsnetHubId = "hub_mac",
            ),
        )

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AuthEntryView(
                        state = currentState,
                        onLaunchOAuth = {},
                        onOpenBrowser = {},
                        onManualConnect = { _, _ -> },
                        onRetry = {},
                        onSelectTsnetHub = { selectedIds += it },
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithTag("tsnet_discovered_hub_list", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        onNodeWithTag("tsnet_discovered_hub_loading_hub_mac").performScrollTo().assertIsDisplayed()
        onNodeWithTag("tsnet_discovered_hub_hub_devbox").assertIsNotEnabled()
        onNodeWithTag("tsnet_search_button").assertIsNotEnabled()
        assertTrue(selectedIds.isEmpty())

        // Transition to ERROR (as after handshake failure): error shown AND list stays clickable
        currentState = currentState.copy(
            connectionStatus = ConnectionStatus.ERROR,
            connectionError = "握手失败：请改选其他主机",
            connectingTsnetHubId = null,
        )
        waitForIdle()

        onNodeWithText("握手失败：请改选其他主机", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("tsnet_discovered_hub_hub_devbox").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("hub_devbox"), selectedIds)
    }

    @Test
    fun `desktop DesktopAuthScreen renders discovered tsnet hubs and handles click even after error`() = runComposeUiTest {
        val selectedIds = mutableListOf<String>()
        val hubs = sampleDiscoveredHubs() + listOf(
            DiscoveredTsnetHub(
                hub = HubInfo("hub_extra_4", "Build Server 4", HubRole.STANDALONE, PlatformType.LINUX, "0.8.14"),
                baseUrl = "https://127.0.0.1:52006",
                wsUrl = "wss://127.0.0.1:52006/ws",
                hostName = "build-04",
                tailscaleIp = "100.88.4.40",
                tailscaleSameUser = true,
            ),
            DiscoveredTsnetHub(
                hub = HubInfo("hub_extra_5", "Build Server 5", HubRole.STANDALONE, PlatformType.LINUX, "0.8.14"),
                baseUrl = "https://127.0.0.1:52007",
                wsUrl = "wss://127.0.0.1:52007/ws",
                hostName = "build-05",
                tailscaleIp = "100.88.5.50",
                tailscaleSameUser = true,
            ),
        )
        val uiState = ConsoleUiState(
            hasEnteredWorkspace = false,
            connectionStatus = ConnectionStatus.ERROR,
            connectionError = "连接 Studio Mac mini 失败，请选择其他主机",
            tsnetStatus = MobileTsnetStatus(
                available = true,
                backendState = "Running",
                loginName = "owner@example.com",
            ),
            discoveredTsnetHubs = hubs,
        )

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    DesktopAuthScreen(
                        state = uiState,
                        onLaunchOAuth = {},
                        onOpenBrowser = {},
                        onManualConnect = { _, _ -> },
                        onRetry = {},
                        onResetCredentials = {},
                        onPasteClipboardPairing = {},
                        onPairViaSsh = {},
                        onStartTsnetLogin = {},
                        onSearchTsnetPeers = {},
                        onSelectTsnetHub = { selectedIds += it },
                        onLogoutTsnet = {},
                        onSelectPairedHost = {},
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithText("连接 Studio Mac mini 失败，请选择其他主机").assertIsDisplayed()
        onNodeWithTag("tsnet_discovered_hub_list").performScrollTo().assertIsDisplayed()
        onNodeWithText("发现 5 台可用主机 · 点击选择连接").performScrollTo().assertIsDisplayed()
        onNodeWithTag("tsnet_search_button").performScrollTo().assertTextContains("重新搜索节点")

        onNodeWithTag("tsnet_discovered_hub_hub_extra_5").performScrollTo().assertIsDisplayed()
        onNodeWithText("Build Server 5").assertIsDisplayed()
        onNodeWithText("build-05 · 100.88.5.50").assertIsDisplayed()

        onNodeWithTag("tsnet_discovered_hub_hub_devbox").performScrollTo().performClick()
        waitForIdle()

        assertEquals(listOf("hub_devbox"), selectedIds)
    }
}
