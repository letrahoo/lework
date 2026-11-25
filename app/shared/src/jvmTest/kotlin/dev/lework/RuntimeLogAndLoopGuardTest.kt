package dev.lework

import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.formatRuntimeLogLine
import dev.lework.platform.redactUrlForLog
import dev.lework.platform.sanitizeRuntimeLogMessage
import dev.lework.protocol.ErrorCode
import dev.lework.protocol.HubHelloResult
import dev.lework.protocol.OAuthPollResponse
import dev.lework.protocol.OAuthStartResult
import dev.lework.protocol.SessionMeta
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleStore
import dev.lework.transport.DiscoveredEndpoint
import dev.lework.transport.HubTransport
import dev.lework.transport.RpcCallException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeLogAndLoopGuardTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @Test
    fun `runtime log sanitizer strips tokens, bearer headers, and URL query secrets`() {
        val raw = "auth failed Bearer secret-jwt-12345 token=abc999 lework_oa.payload.sig pairCode=XY123\nnext line"
        val sanitized = sanitizeRuntimeLogMessage(raw)
        assertFalse(sanitized.contains("secret-jwt-12345"))
        assertFalse(sanitized.contains("abc999"))
        assertFalse(sanitized.contains("lework_oa.payload.sig"))
        assertFalse(sanitized.contains("XY123"))
        assertFalse(sanitized.contains("\n"))
        assertTrue(sanitized.contains("<redacted>"))

        val redactedUrl = redactUrlForLog("lework://pair?url=https://192.168.1.10:64450&pairCode=SECRET99&token=lework_dt.xyz#frag")
        assertEquals("lework://pair", redactedUrl)

        val redactedHttps = redactUrlForLog("https://user:pass@127.0.0.1:64450/v1/ws?token=abc")
        assertEquals("https://127.0.0.1:64450/v1/ws", redactedHttps)

        val formatted = formatRuntimeLogLine(1_700_000_000_123L, "STATE", "entered=false->true")
        assertTrue(formatted.contains("[STATE] entered=false->true"))
    }

    @Test
    fun `ConsoleStore logs lifecycle and state transitions without leaking saved access token`() = runTest {
        val secretToken = "lework_oa.super_secret_token_987654321"
        JvmPlatformTestHooks.savedToken = secretToken
        val transport = FakeHubTransport()

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)

            val logs = JvmPlatformTestHooks.snapshotRuntimeLogs()
            assertTrue(logs.any { it.contains("[CONNECT]") && it.contains("connected ok") })
            assertTrue(logs.any { it.contains("[STATE]") && it.contains("entered=false->true") })
            assertTrue(logs.none { it.contains(secretToken) }, "Runtime logs must never leak access tokens")
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `Bonjour emissions in ERROR state respect cooldown and consecutive retry cap instead of looping forever`() = runTest {
        val base = FakeHubTransport()
        var discoverCalls = 0
        val transport = object : HubTransport by base {
            override suspend fun discoverIntranetHub(
                candidates: List<String>,
                dynamicCandidates: StateFlow<List<String>>?,
                waitWindowMs: Long,
                preferredHubId: String?,
            ): DiscoveredEndpoint? {
                discoverCalls++
                return null
            }
        }

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            assertEquals(1, discoverCalls)
            assertEquals(ConnectionStatus.ERROR, store.state.value.connectionStatus)

            // 1st Bonjour update triggers an immediate auto-retry
            JvmPlatformTestHooks.setBonjourUrls(listOf("https://192.168.1.10:64450"))
            runCurrent()
            assertEquals(2, discoverCalls)

            // Immediate subsequent Bonjour update inside the cooldown window must be throttled
            JvmPlatformTestHooks.setBonjourUrls(listOf("https://192.168.1.11:64450"))
            runCurrent()
            assertEquals(2, discoverCalls)

            val logs = JvmPlatformTestHooks.snapshotRuntimeLogs()
            assertTrue(logs.any { it.contains("[BONJOUR]") && it.contains("skip auto-retry cooldown") })

            // Manual bootstrapOrConnect resets the cooldown/cap cleanly
            store.bootstrapOrConnect()
            runCurrent()
            assertEquals(3, discoverCalls)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `Desktop Local Direct 127_0_0_1_64450 stays on fast path during network handoff instead of disconnecting`() = runTest {
        JvmPlatformTestHooks.simulateDesktopLayout = true
        JvmPlatformTestHooks.candidateUrls = listOf(
            "https://127.0.0.1:64450",
            "https://192.168.31.44:64450",
        )
        val base = FakeHubTransport()
        var disconnectCalls = 0
        var discoverCalls = 0
        val transport = object : HubTransport by base {
            override suspend fun discoverIntranetHub(
                candidates: List<String>,
                dynamicCandidates: StateFlow<List<String>>?,
                waitWindowMs: Long,
                preferredHubId: String?,
            ): DiscoveredEndpoint? {
                discoverCalls++
                return base.discoverIntranetHub(candidates, dynamicCandidates, waitWindowMs, preferredHubId)
            }

            override suspend fun disconnect() {
                disconnectCalls++
                base.disconnect()
            }
        }

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)
            assertEquals("https://127.0.0.1:64450", store.state.value.discoveredBaseUrl)
            val discoverBeforeHandoff = discoverCalls
            val disconnectBeforeHandoff = disconnectCalls

            // Network handoff must NOT misclassify Desktop Local Direct (127.0.0.1:64450) as an embedded tsnet proxy!
            store.verifyAndResumeForegroundSession(fromNetworkHandoff = true)
            runCurrent()

            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)
            assertEquals(discoverBeforeHandoff, discoverCalls, "Local Direct should not trigger LAN-upgrade rediscovery")
            assertEquals(disconnectBeforeHandoff, disconnectCalls, "Local Direct should stay connected via fast-path")
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `Desktop brief window focus bounce skips redundant foreground resume probe`() = runTest {
        JvmPlatformTestHooks.simulateDesktopLayout = true
        val base = FakeHubTransport()
        var listSessionsCalls = 0
        val transport = object : HubTransport by base {
            override suspend fun listSessions(engineId: String?): List<SessionMeta> {
                listSessionsCalls++
                return base.listSessions(engineId)
            }
        }

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            val baselineListCalls = listSessionsCalls

            // Simulate quick focus loss & regain (e.g. system sheet or dialog)
            JvmPlatformTestHooks.setForeground(false)
            runCurrent()
            JvmPlatformTestHooks.setForeground(true)
            runCurrent()

            assertEquals(baselineListCalls, listSessionsCalls, "Brief Desktop focus bounce must skip verify probe")
            val logs = JvmPlatformTestHooks.snapshotRuntimeLogs()
            assertTrue(logs.any { it.contains("[LIFECYCLE]") && it.contains("desktop brief focus bounce") })
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `OAuth expired poll loop is capped instead of recursing forever`() = runTest {
        val base = FakeHubTransport()
        var oauthStartCount = 0
        val transport = object : HubTransport by base {
            override suspend fun startOAuthDeviceFlow(baseUrl: String): OAuthStartResult {
                oauthStartCount++
                return OAuthStartResult(
                    sid = "sid_$oauthStartCount",
                    userCode = "CODE-$oauthStartCount",
                    verifyUrl = "https://open.feishu.cn/device?code=$oauthStartCount",
                    appLaunchUrl = "https://open.feishu.cn/device?code=$oauthStartCount",
                    expiresIn = 60,
                )
            }

            override suspend fun pollOAuthDeviceFlow(baseUrl: String, sid: String): OAuthPollResponse =
                OAuthPollResponse(status = "expired")
        }

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.startOAuthLoginFlow("https://127.0.0.1:64450", "wss://127.0.0.1:64450/v1/ws")
            advanceTimeBy(60_000L)
            runCurrent()

            // Initial attempt (0) + MAX_CONSECUTIVE_OAUTH_EXPIRED_RESTARTS (4) = 5 total attempts, then stops!
            assertEquals(ConsoleStore.MAX_CONSECUTIVE_OAUTH_EXPIRED_RESTARTS + 1, oauthStartCount)
            assertTrue(store.state.value.authStatusText.contains("多次过期"))
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `Tailscale same-user fallback during foreground resume avoids flashing AuthScreen on expired token`() = runTest {
        val base = FakeHubTransport()
        base.simulateTailscaleSameUser = true
        var helloAttempt = 0
        var failNextListSessions = false
        val transport = object : HubTransport by base {
            override suspend fun listSessions(engineId: String?): List<SessionMeta> {
                if (failNextListSessions) {
                    failNextListSessions = false
                    error("Socket stale")
                }
                return base.listSessions(engineId)
            }

            override suspend fun connectAndHello(
                wsUrl: String,
                token: String,
                platformName: String,
            ): HubHelloResult {
                helloAttempt++
                if (helloAttempt == 2) {
                    throw RpcCallException(ErrorCode.UNAUTHORIZED, "Expired token")
                }
                return base.connectAndHello(wsUrl, token, platformName)
            }
        }

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            assertTrue(store.state.value.hasEnteredWorkspace)

            // Force full reconnect path (fast-path fails once -> reconnects with expired token -> Tailscale fallback)
            failNextListSessions = true
            store.verifyAndResumeForegroundSession(fromNetworkHandoff = true)
            runCurrent()

            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)
            assertEquals(1, base.tailscaleHandshakeCount)
            val logs = JvmPlatformTestHooks.snapshotRuntimeLogs()
            assertTrue(logs.none { it.contains("entered=true->false") }, "Should not bounce out to AuthScreen during Tailscale fallback")
        } finally {
            storeScope.cancel()
        }
    }
}
