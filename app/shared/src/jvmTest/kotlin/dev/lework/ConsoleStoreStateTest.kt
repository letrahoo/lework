package dev.lework

import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.*
import dev.lework.store.ChatTimelineItem
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleStore
import dev.lework.transport.RpcCallException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleStoreStateTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @Test
    fun `T4_1 and T4_2 discards stale subscribe snapshots on rapid session switch and isolates live events`() = runTest {
        val transport = FakeHubTransport()
        val sessA = SessionMeta(
            id = "host:claude:sess_A",
            engineId = "claude",
            cwd = "/workspace/repoA",
            title = "Session A",
            state = SessionState.RUNNING,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 0,
        )
        val sessB = SessionMeta(
            id = "host:claude:sess_B",
            engineId = "claude",
            cwd = "/workspace/repoB",
            title = "Session B",
            state = SessionState.RUNNING,
            createdAt = 1_000L,
            updatedAt = 3_000L,
            lastSeq = 0,
        )
        transport.sessions.addAll(listOf(sessA, sessB))
        transport.sessionEventsMap[sessA.id] = mutableListOf(
            AgentEvent.AssistantDelta(0, sessA.id, 1_000L, "a_msg_1", "来自会话 A 的旧快照"),
        )
        transport.sessionEventsMap[sessB.id] = mutableListOf(
            AgentEvent.AssistantDelta(0, sessB.id, 1_000L, "b_msg_1", "来自会话 B 的真实内容"),
        )
        // Session A responds slowly (300ms), Session B responds immediately
        transport.subscribeDelayMsBySession[sessA.id] = 300L
        transport.subscribeDelayMsBySession[sessB.id] = 0L

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            assertTrue(store.state.value.hasEnteredWorkspace)

            // Rapidly switch A -> B before A finishes subscribing
            store.selectSession(sessA.id)
            advanceTimeBy(40L)
            store.selectSession(sessB.id)
            advanceTimeBy(350L)
            runCurrent()

            // 1. Session A must have been explicitly unsubscribed
            assertTrue(sessA.id in transport.unsubscribedIds)
            assertEquals(sessB.id, store.state.value.activeSessionId)

            // 2. Active transcript MUST belong strictly to Session B (stale Session A snapshot discarded)
            val activeItems = store.state.value.activeTranscript.items
            assertEquals(1, activeItems.size)
            assertEquals("来自会话 B 的真实内容", (activeItems.first() as ChatTimelineItem.Assistant).text)

            // 3. Stray live stream event for Session A MUST NOT leak into Session B
            transport.emitEvent(
                AgentEvent.AssistantDelta(1, sessA.id, 4_000L, "a_msg_2", "A 后台继续输出不应串台"),
            )
            runCurrent()
            assertEquals(1, store.state.value.activeTranscript.items.size)
            assertNull(store.state.value.transcripts[sessA.id])
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `T5_1 and T5_2 preserves workspace on iOS lock screen disconnect and verifies permission on foreground return`() = runTest {
        val transport = FakeHubTransport()
        val sess = SessionMeta(
            id = "host:agy:conv_1",
            engineId = "agy",
            cwd = "/workspace/lework",
            title = "Lock Screen Test",
            state = SessionState.RUNNING,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 0,
        )
        transport.sessions.add(sess)
        transport.sessionEventsMap[sess.id] = mutableListOf(
            AgentEvent.UserMessage(0, sess.id, 1_000L, "u_0", "初始提问"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sess.id)
            runCurrent()
            assertEquals(1, store.state.value.activeTranscript.items.size)

            // 1. User locks iPhone (`isForeground = false`) and OS drops WebSocket
            JvmPlatformTestHooks.setForeground(false)
            runCurrent()
            transport.emitDisconnected("iOS background socket closed")
            runCurrent()

            // Workspace & active session MUST stay intact (never kicked back to login home!)
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals(sess.id, store.state.value.activeSessionId)
            assertEquals(ConnectionStatus.RECONNECTING, store.state.value.connectionStatus)

            // While locked, Hub appended seq=1
            transport.sessionEventsMap[sess.id]!!.add(
                AgentEvent.AssistantDelta(1, sess.id, 3_000L, "a_1", "锁屏期间产生的新增回复"),
            )

            // 2. User unlocks phone (`isForeground = true`) -> silent permission verify & afterSeq catch-up!
            JvmPlatformTestHooks.setForeground(true)
            runCurrent()

            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)
            assertEquals(2, store.state.value.activeTranscript.items.size)
            assertEquals(
                "锁屏期间产生的新增回复",
                (store.state.value.activeTranscript.items[1] as ChatTimelineItem.Assistant).text,
            )

            // 3. Now simulate token revocation (`ErrorCode.UNAUTHORIZED`) on next lock/unlock cycle
            JvmPlatformTestHooks.setForeground(false)
            runCurrent()
            transport.listSessionsException = RpcCallException(ErrorCode.UNAUTHORIZED, "Token expired")
            JvmPlatformTestHooks.setForeground(true)
            runCurrent()

            assertFalse(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.NEEDS_AUTH, store.state.value.connectionStatus)
            assertNull(JvmPlatformTestHooks.savedToken)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `T6_1 reverse pagination prepends older history without mutating lastSeq`() = runTest {
        val transport = FakeHubTransport()
        val sess = SessionMeta(
            id = "host:claude:long_hist",
            engineId = "claude",
            cwd = "/workspace/lework",
            title = "Long History",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 5_000L,
            lastSeq = 299,
        )
        transport.sessions.add(sess)
        val allEvents = (0 until 300).map { seq ->
            if (seq % 2 == 0) {
                AgentEvent.UserMessage(seq, sess.id, 1_000L + seq, "u_$seq", "提问 #$seq")
            } else {
                AgentEvent.AssistantDelta(seq, sess.id, 1_000L + seq, "a_$seq", "回答 #$seq")
            }
        }.toMutableList()
        transport.sessionEventsMap[sess.id] = allEvents

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sess.id)
            runCurrent()

            // Initial page loads latest 220 events (seq 80..299)
            val initial = store.state.value.activeTranscript
            assertEquals(220, initial.items.size)
            assertEquals(299, initial.lastSeq)
            assertEquals(80, initial.earliestSeq)
            assertTrue(initial.hasMoreBefore)

            // Load older history -> prepends seq 0..79 while keeping lastSeq == 299!
            store.loadOlderHistory(sess.id)
            runCurrent()

            val afterPage = store.state.value.activeTranscript
            assertEquals(300, afterPage.items.size)
            assertEquals(299, afterPage.lastSeq)
            assertEquals(0, afterPage.earliestSeq)
            assertFalse(afterPage.hasMoreBefore)
            assertEquals("提问 #0", (afterPage.items.first() as ChatTimelineItem.User).text)
            assertEquals("回答 #299", (afterPage.items.last() as ChatTimelineItem.Assistant).text)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `T9_1 loads session context window occupancy and global usage quota dashboard`() = runTest {
        val transport = FakeHubTransport()
        val sess = SessionMeta(
            id = "host:claude:ctx_demo",
            engineId = "claude",
            cwd = "/workspace/lework",
            title = "Context Demo",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 1,
        )
        transport.sessions.add(sess)
        transport.sessionEventsMap[sess.id] = mutableListOf(
            AgentEvent.UserMessage(0, sess.id, 1_000L, "u_0", "hello"),
            AgentEvent.AssistantDelta(1, sess.id, 1_100L, "a_1", "world"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sess.id)
            runCurrent()

            val sessUsage = store.state.value.activeSessionUsage
            assertEquals("host:claude:ctx_demo", sessUsage?.sessionId)
            assertEquals(34.2, sessUsage?.contextPercent)
            assertEquals(68_400L, sessUsage?.contextTokens)

            store.refreshUsageDashboard(days = 7)
            runCurrent()

            assertEquals(7, store.state.value.usageSelectedDays)
            assertEquals(1_040_000L, store.state.value.usageSummary?.totals?.totalTokens)
            assertEquals(2, store.state.value.usageQuota?.windows?.size)
            assertEquals(true, store.state.value.proxyUsage?.reachable)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `T9_2 loads workspace git diff and creates and restores checkpoints across host sessions`() = runTest {
        val transport = FakeHubTransport()
        val sess = SessionMeta(
            id = "host:claude:diff_demo",
            engineId = "claude",
            cwd = "/workspace/lework",
            title = "Diff Demo",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 0,
        )
        transport.sessions.add(sess)
        transport.sessionEventsMap[sess.id] = mutableListOf(
            AgentEvent.UserMessage(0, sess.id, 1_000L, "u_0", "refactor"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sess.id)
            runCurrent()

            store.refreshDiffAndCheckpoints()
            runCurrent()
            assertEquals("main", store.state.value.gitStatus?.branch)
            assertEquals(1, store.state.value.gitStatus?.files?.size)

            store.openGitDiff("hub/src/server.ts", null, "hub/src/server.ts")
            runCurrent()
            assertTrue(store.state.value.openedDiffContent.orEmpty().contains("+new line"))

            store.createManualCheckpoint("重要节点")
            runCurrent()
            assertEquals(1, store.state.value.checkpoints.size)
            val created = store.state.value.checkpoints.first()
            assertEquals("重要节点", created.label)

            store.restoreCheckpoint(created)
            runCurrent()
            assertEquals(listOf(sess.id to created.id), transport.restoredCheckpoints)
            assertTrue(store.state.value.checkpointNotice.orEmpty().contains("已回退至"))
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `T10_1 zero-touch Tailscale same-account handshake enters workspace and saves PairedHost`() = runTest {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.savedToken = null
        val transport = FakeHubTransport().apply {
            simulateTailscaleSameUser = true
        }
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            assertEquals(1, transport.tailscaleHandshakeCount)
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals("lework_dt.tailscale_mesh_token", JvmPlatformTestHooks.savedToken)
            assertEquals(1, store.state.value.pairedHosts.size)
            assertEquals("hub_fake_1", store.state.value.pairedHosts.first().hubId)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T10_2 parses lework pair deep link and redeems single-use pairCode into PairedHost`() = runTest {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.savedToken = null
        val transport = FakeHubTransport()
        val rawJson = """{"v":2,"hubId":"hub_fake_1","name":"TestMacBook","role":"slave","urls":["https://192.168.1.20:64450"],"url":"https://192.168.1.20:64450","fp":"${"a".repeat(64)}","pairCode":"lwp_0123456789ab_secret1234567890"}"""
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val b64 = kotlin.io.encoding.Base64.UrlSafe.encode(rawJson.encodeToByteArray()).trimEnd('=')
        val deepLink = "lework://pair?d=$b64"

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            JvmPlatformTestHooks.emitIncomingPairingUri(deepLink)
            runCurrent()

            // External deep link MUST stage pending confirmation first instead of silently pairing!
            assertEquals(0, transport.pairRedeemCount)
            assertEquals("TestMacBook", store.state.value.pendingDeepLinkPairing?.hostName)

            store.dismissPendingDeepLinkPairing()
            assertNull(store.state.value.pendingDeepLinkPairing)
            assertEquals(0, transport.pairRedeemCount)

            // Re-emit and explicitly confirm
            JvmPlatformTestHooks.emitIncomingPairingUri(deepLink)
            runCurrent()
            store.confirmPendingDeepLinkPairing()
            runCurrent()

            assertNull(store.state.value.pendingDeepLinkPairing)
            assertEquals(1, transport.pairRedeemCount)
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals("lework_dt.paired_qr_token", JvmPlatformTestHooks.hostTokens["hub_fake_1"])
            assertEquals(1, store.state.value.pairedHosts.size)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T10_3 searchAndConnectViaTsnet exclusively probes tsnet peers without LAN OAuth hijack`() = runTest {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        JvmPlatformTestHooks.setBonjourUrls(listOf("https://192.168.1.44:64450"))
        JvmPlatformTestHooks.tsnetStatus = dev.lework.platform.MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = listOf(
                dev.lework.platform.MobileTsnetPeer(
                    hostName = "lework-hub-mac",
                    tailscaleIp = "100.88.1.2",
                    online = false, // even before NetMap flips online, forwarder is ready
                    isLeworkNode = true,
                    localProxyUrl = "https://127.0.0.1:59123",
                ),
            ),
        )
        val transport = FakeHubTransport().apply {
            simulateTailscaleSameUser = true
        }
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.searchAndConnectViaTsnet()
            runCurrent()

            assertEquals(1, transport.tailscaleHandshakeCount)
            assertEquals("https://127.0.0.1:59123", store.state.value.discoveredBaseUrl)
            assertTrue(store.state.value.hasEnteredWorkspace)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T10_4 preparePairAnotherHost stays on clean NEEDS_AUTH screen without auto-reconnecting via disconnect or Bonjour`() = runTest {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.savedToken = "lework_dt.existing_mac_token"
        JvmPlatformTestHooks.tsnetAvailable = true
        JvmPlatformTestHooks.tsnetEnabled = true
        JvmPlatformTestHooks.tsnetStatus = dev.lework.platform.MobileTsnetStatus(
            available = true,
            backendState = "Running",
            loginName = "owner@example.com",
            peers = emptyList(),
        )
        val transport = FakeHubTransport().apply {
            emitDisconnectOnClientClose = true
        }
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            assertTrue(store.state.value.hasEnteredWorkspace)

            // User taps "配对或连接其他主机" in drawer
            store.preparePairAnotherHost()
            runCurrent()

            assertFalse(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.NEEDS_AUTH, store.state.value.connectionStatus)
            assertFalse(store.state.value.oauthEnabled)
            assertEquals("", store.state.value.discoveredBaseUrl)

            // Even if user probes tsnet (which errors with 0 peers) and Bonjour emits local Mac URLs,
            // it MUST NOT yank the user back into the previous Mac workspace automatically!
            store.searchAndConnectViaTsnet()
            advanceTimeBy(6_000L)
            runCurrent()
            assertEquals(ConnectionStatus.ERROR, store.state.value.connectionStatus)
            assertFalse(store.state.value.hasEnteredWorkspace)

            JvmPlatformTestHooks.setBonjourUrls(listOf("https://192.168.1.44:64450"))
            runCurrent()
            assertFalse(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.ERROR, store.state.value.connectionStatus)

            // Explicit tap on paired host reconnects cleanly
            val macHostId = store.state.value.pairedHosts.first().hubId
            store.switchPairedHost(macHostId)
            runCurrent()
            assertTrue(store.state.value.hasEnteredWorkspace)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T11_1 lework session deep link opens target session both on cold start and while connected`() = runTest {
        JvmPlatformTestHooks.reset()
        val transport = FakeHubTransport().apply {
            sessions.add(
                SessionMeta(
                    id = "host:claude:deep_1",
                    engineId = "claude",
                    cwd = "/workspace/lework",
                    title = "DeepLinked Session",
                    state = SessionState.WAITING_PERMISSION,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    lastSeq = 0,
                ),
            )
            sessions.add(
                SessionMeta(
                    id = "host:claude:deep_2",
                    engineId = "claude",
                    cwd = "/workspace/lework",
                    title = "Second Session",
                    state = SessionState.IDLE,
                    createdAt = 1_000L,
                    updatedAt = 3_000L,
                    lastSeq = 0,
                ),
            )
        }
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            // Cold-start deep link before workspace connection
            JvmPlatformTestHooks.emitIncomingPairingUri("lework://session?id=host%3Aclaude%3Adeep_1&hubId=hub_fake_1")
            runCurrent()

            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals("host:claude:deep_1", store.state.value.activeSessionId)

            // In-workspace deep link switches immediately
            JvmPlatformTestHooks.emitIncomingPairingUri("lework://session/host%3Aclaude%3Adeep_2")
            runCurrent()
            assertEquals("host:claude:deep_2", store.state.value.activeSessionId)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T11_2 composer @file mention queries workspace files and replaces trailing token cleanly`() = runTest {
        JvmPlatformTestHooks.reset()
        val transport = FakeHubTransport()
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            store.updateComposerTextForMentions("请检查一下 @serv")
            advanceTimeBy(150L)
            runCurrent()

            assertEquals("serv", store.state.value.mentionQuery)
            assertEquals(listOf("hub/src/server.ts"), store.state.value.workspaceFileSuggestions)

            val replaced = dev.lework.store.replaceTrailingMention("请检查一下 @serv", "hub/src/server.ts")
            assertEquals("请检查一下 @hub/src/server.ts ", replaced)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T11_3 pickAndAttachImage attaches screenshot and supports sending image-only prompt`() = runTest {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.nextPickedImage = dev.lework.platform.PickedImagePayload(
            fileName = "ui_issue.jpg",
            mimeType = "image/jpeg",
            dataBase64 = "aGVsbG8=",
        )
        val transport = FakeHubTransport()
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            store.pickAndAttachImage()
            assertEquals(1, store.state.value.composerAttachedImages.size)
            assertEquals("ui_issue.jpg", store.state.value.composerAttachedImages.first().fileName)

            store.sendPrompt("")
            runCurrent()

            assertTrue(store.state.value.composerAttachedImages.isEmpty())
            assertEquals(1, transport.sentPromptImages.size)
            assertEquals("ui_issue.jpg", transport.sentPromptImages.first().second.first().fileName)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T11_4 cold-start lework pair deep link is not cancelled when LaunchedEffect calls bootstrapOrConnect`() = runTest {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.savedToken = null
        val transport = FakeHubTransport()
        val rawJson = """{"v":2,"hubId":"hub_fake_1","name":"TestMacBook","role":"slave","urls":["https://192.168.1.20:64450"],"url":"https://192.168.1.20:64450","fp":"${"a".repeat(64)}","pairCode":"lwp_0123456789ab_secret1234567890"}"""
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val b64 = kotlin.io.encoding.Base64.UrlSafe.encode(rawJson.encodeToByteArray()).trimEnd('=')
        // Pre-populate incoming URI before ConsoleStore init (simulates Android MainActivity.onCreate cold start)
        JvmPlatformTestHooks.emitIncomingPairingUri("lework://pair?d=$b64")

        val storeScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            // ConsoleApp LaunchedEffect(store, autoBootstrap) immediately calls bootstrapOrConnect()
            store.bootstrapOrConnect()
            runCurrent()

            // Must preserve pendingDeepLinkPairing across cold-start bootstrapOrConnect()
            assertEquals("TestMacBook", store.state.value.pendingDeepLinkPairing?.hostName)
            assertEquals(0, transport.pairRedeemCount)

            store.confirmPendingDeepLinkPairing()
            runCurrent()

            assertEquals(1, transport.pairRedeemCount)
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals("lework_dt.paired_qr_token", JvmPlatformTestHooks.hostTokens["hub_fake_1"])
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T12_1 canonicalizes IPv6 hostPortKey variants and prunes removed host pins`() = runTest {
        dev.lework.security.HubTrust.resetForTests()
        assertEquals(
            "fe80:0:0:0:1:0:0:2:64450",
            dev.lework.security.HubTrust.hostPortKeyOf("https://[FE80::0001:0:0:2%en0]:64450/v1/ws"),
        )
        assertEquals(
            dev.lework.security.HubTrust.hostPortKeyOf("https://[fe80:0:0:0:1:0:0:2]:64450"),
            dev.lework.security.HubTrust.hostPortKeyOf("wss://[fe80::1:0:0:2]:64450/v1/ws"),
        )
        val fpA = "a".repeat(64)
        val fpB = "b".repeat(64)
        dev.lework.security.HubTrust.pin(fpA)
        dev.lework.security.HubTrust.pin(fpB)
        dev.lework.security.HubTrust.replacePins(setOf(fpA))
        assertEquals(setOf(fpA), dev.lework.security.HubTrust.pinned())
        dev.lework.security.HubTrust.resetForTests()
    }

    @Test
    fun `T12_2 live network handoff upgrades from tsnet forwarder to direct LAN seamlessly without dropping session`() = runTest {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.candidateUrls = listOf("https://127.0.0.1:59123")
        val transport = FakeHubTransport()
        val sess = SessionMeta(
            id = "host:claude:roaming_1",
            engineId = "claude",
            cwd = "/workspace/lework",
            title = "Roaming Session",
            state = SessionState.RUNNING,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 0,
        )
        transport.sessions.add(sess)
        transport.sessionEventsMap[sess.id] = mutableListOf(
            AgentEvent.UserMessage(0, sess.id, 1_000L, "u_0", "出门测试切网"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sess.id)
            runCurrent()
            assertEquals("https://127.0.0.1:59123", store.state.value.discoveredBaseUrl)

            // User walks back onto home Wi-Fi: Bonjour advertises LAN IP, new event appended, network epoch bumps
            JvmPlatformTestHooks.setBonjourUrls(listOf("https://192.168.1.44:64450"))
            transport.sessionEventsMap[sess.id]!!.add(
                AgentEvent.AssistantDelta(1, sess.id, 3_000L, "a_1", "回家切回局域网满速直连"),
            )
            JvmPlatformTestHooks.bumpNetworkEpoch()
            advanceTimeBy(800L)
            runCurrent()

            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)
            assertEquals("https://192.168.1.44:64450", store.state.value.discoveredBaseUrl)
            assertEquals(2, store.state.value.activeTranscript.items.size)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T9_1 supports session rename, non-destructive archiving, archived list view, and search filtering`() = runTest {
        val transport = FakeHubTransport()
        val sess1 = SessionMeta(
            id = "host:agy:task_alpha",
            engineId = "agy",
            cwd = "/workspace/lework",
            title = "Initial Alpha Prompt",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 1,
        )
        val sess2 = SessionMeta(
            id = "host:claude:task_beta",
            engineId = "claude",
            cwd = "/workspace/monee",
            title = "Initial Beta Prompt",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 3_000L,
            lastSeq = 1,
        )
        transport.sessions.addAll(listOf(sess2, sess1))
        transport.sessionEventsMap[sess1.id] = mutableListOf(
            AgentEvent.UserMessage(0, sess1.id, 1_000L, "u_0", "历史记录永久保留校验"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            assertEquals(2, store.state.value.filteredHistorySessions.size)
            assertEquals(0, store.state.value.archivedSessionsCount)

            // 1. Rename sess1 -> immediately searchable by custom title
            store.renameSession(sess1.id, "iOS 渲染性能优化专项")
            runCurrent()
            assertEquals("iOS 渲染性能优化专项", store.state.value.sessions.first { it.id == sess1.id }.title)

            store.setDrawerSearchQuery("渲染性能")
            assertEquals(listOf(sess1.id), store.state.value.filteredHistorySessions.map { it.id })
            store.setDrawerSearchQuery("")

            // 2. Archive sess1 -> moves out of active history into Archived list without deleting transcript
            store.archiveSession(sess1.id, true)
            runCurrent()
            assertEquals(listOf(sess2.id), store.state.value.filteredHistorySessions.map { it.id })
            assertEquals(1, store.state.value.archivedSessionsCount)
            assertEquals(listOf(sess1.id), store.state.value.filteredArchivedSessions.map { it.id })

            // 3. Selecting archived session from Archived list still loads full transcript
            store.setDrawerShowArchived(true)
            store.selectSession(sess1.id)
            runCurrent()
            assertEquals(1, store.state.value.activeTranscript.items.size)

            // 4. Unarchive sess1 -> returns cleanly to active history list
            store.archiveSession(sess1.id, false)
            runCurrent()
            assertEquals(0, store.state.value.archivedSessionsCount)
            assertEquals(2, store.state.value.filteredHistorySessions.size)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T11_1 auto-recovers from transient wake-up Wi-Fi drop without bouncing out of workspace and resyncs rolled-back lastSeq`() = runTest {
        val transport = FakeHubTransport()
        val sid = "host:agy:live_conv"
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/lework",
                title = "Levi-app-dev",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastSeq = 10,
            ),
        )
        transport.sessionEventsMap[sid] = mutableListOf(
            AgentEvent.UserMessage(9, sid, 1_000L, "u_9", "旧序号提问"),
            AgentEvent.AssistantDelta(10, sid, 1_100L, "a_10", "旧序号回复"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sid)
            runCurrent()
            assertEquals(10, store.state.value.activeTranscript.lastSeq)

            // 1. iPhone locks (`isForeground = false`), WebSocket closes, and on immediate unlock
            //    Wi-Fi is not up yet (`discoverNullRemaining = 1`).
            JvmPlatformTestHooks.setForeground(false)
            runCurrent()
            transport.emitDisconnected("Background socket closed")
            runCurrent()

            // Simulate Hub restart / handback where disk transcript now has lower seq (seq 0..2) with new reply!
            transport.sessions[0] = transport.sessions[0].copy(lastSeq = 2, updatedAt = 5_000L)
            transport.sessionEventsMap[sid] = mutableListOf(
                AgentEvent.UserMessage(0, sid, 4_000L, "u_0", "终端最新提问"),
                AgentEvent.AssistantDelta(1, sid, 4_500L, "a_1", "电脑端已完成但序号重排的最新回复"),
                AgentEvent.TurnDone(2, sid, 4_600L, "t_2", "end_turn"),
            )

            transport.discoverNullRemaining = 1
            JvmPlatformTestHooks.setForeground(true)
            runCurrent()

            // Must stay inside workspace with RECONNECTING instead of kicking user out to ERROR screen!
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.RECONNECTING, store.state.value.connectionStatus)

            // 2. `reconnectRetryJob` fires automatically after 3.5s -> reconnects AND detects `lastSeq` rollback (2 < 10)!
            advanceTimeBy(4_000L)
            runCurrent()

            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)
            assertEquals(2, store.state.value.activeTranscript.lastSeq)
            val assistantTexts = store.state.value.activeTranscript.items
                .filterIsInstance<ChatTimelineItem.Assistant>()
                .map { it.text }
            assertEquals(listOf("电脑端已完成但序号重排的最新回复"), assistantTexts)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T12_1 supports multi-image album pick, camera capture, and user bubble imagePreviews decoding`() = runTest {
        val tinyPngB64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val transport = FakeHubTransport()
        val sid = "host:agy:vision_test"
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/lework",
                title = "照片直传识别验证",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastSeq = 0,
            ),
        )
        transport.sessionEventsMap[sid] = mutableListOf()

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sid)
            runCurrent()

            // 1. Multi-select 2 photos from album
            JvmPlatformTestHooks.nextPickedImages = listOf(
                dev.lework.platform.PickedImagePayload("p1.jpg", "image/jpeg", tinyPngB64, tinyPngB64),
                dev.lework.platform.PickedImagePayload("p2.jpg", "image/jpeg", tinyPngB64, tinyPngB64),
            )
            store.pickAndAttachImage()
            assertEquals(dev.lework.platform.ImagePickSource.LIBRARY, JvmPlatformTestHooks.lastImagePickSource)
            assertEquals(2, store.state.value.composerAttachedImages.size)

            // 2. Capture 1 photo directly via camera -> total 3 attached images
            JvmPlatformTestHooks.nextPickedImages = listOf(
                dev.lework.platform.PickedImagePayload("cam.jpg", "image/jpeg", tinyPngB64, tinyPngB64),
            )
            store.captureAndAttachImage()
            assertEquals(dev.lework.platform.ImagePickSource.CAMERA, JvmPlatformTestHooks.lastImagePickSource)
            assertEquals(3, store.state.value.composerAttachedImages.size)

            // 3. Send prompt -> dispatches all 3 images with thumbnailBase64 over direct RPC
            store.sendPrompt("帮我识别这三张图")
            runCurrent()
            assertEquals(0, store.state.value.composerAttachedImages.size)
            assertEquals(1, transport.sentPromptImages.size)
            assertEquals(3, transport.sentPromptImages.first().second.size)
            assertEquals(tinyPngB64, transport.sentPromptImages.first().second.first().thumbnailBase64)

            // 4. Hub emits `message.user` with `imagePreviews` -> timeline item carries all 3 previews & decodes bitmap!
            transport.emitEvent(
                AgentEvent.UserMessage(
                    seq = 1,
                    sessionId = sid,
                    ts = 3_000L,
                    messageId = "u_img_1",
                    text = "帮我识别这三张图",
                    imageCount = 3,
                    imagePreviews = listOf(tinyPngB64, tinyPngB64, tinyPngB64),
                ),
            )
            runCurrent()
            val userItem = store.state.value.activeTranscript.items.last() as ChatTimelineItem.User
            assertEquals(3, userItem.imageCount)
            assertEquals(3, userItem.imagePreviews.size)
            val decodedBmp = dev.lework.ui.decodeCachedBase64Bitmap(tinyPngB64)
            assertTrue(decodedBmp != null && decodedBmp.width == 1 && decodedBmp.height == 1)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T13_1 creates isolated worktree session, formats branch badge, filters by branch, and marks cleanedUp on archive`() = runTest {
        val transport = FakeHubTransport()
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            store.selectWorkspace("/Users/test/Projects/lework")
            store.setDraftUseWorktree(true)
            store.setDraftWorktreeBranch("feat-worktree")
            assertEquals("lework · feat-worktree", store.state.value.workspaceDisplayName)

            store.sendPrompt("在独立分支实现新功能")
            runCurrent()

            assertEquals(true, transport.lastCreateUseWorktree)
            assertEquals("feat-worktree", transport.lastCreateWorktreeBranch)

            val created = store.state.value.activeSession
            assertTrue(created != null)
            assertEquals("lework/feat-worktree", created.worktree?.branch)
            assertEquals(false, created.worktree?.cleanedUp)
            assertEquals("lework · feat-worktree", store.state.value.workspaceDisplayName)
            // Draft custom branch resets after creation while keeping worktree preference intact
            assertEquals("", store.state.value.draftWorktreeBranch)

            // Search by branch name in sidebar matches the worktree session
            store.setDrawerSearchQuery("feat-worktree")
            assertEquals(1, store.state.value.filteredHistorySessions.size)
            store.setDrawerSearchQuery("")

            // Archive session -> FakeHubTransport marks worktree cleanedUp = true
            store.archiveSession(created.id, true)
            runCurrent()
            val archived = store.state.value.sessions.first { it.id == created.id }
            assertEquals(true, archived.archived)
            assertEquals(true, archived.worktree?.cleanedUp)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T14_1 desktop SSH one-click pairing and background running-session stream pre-warming`() = runTest {
        val transport = FakeHubTransport()
        val runSidA = "sess_run_a"
        val runSidB = "sess_run_b"
        transport.sessions.addAll(
            listOf(
                SessionMeta(
                    id = runSidA,
                    engineId = "claude",
                    cwd = "/home/dev/service-a",
                    title = "Devbox Task A",
                    state = SessionState.RUNNING,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    lastSeq = 0,
                ),
                SessionMeta(
                    id = runSidB,
                    engineId = "claude",
                    cwd = "/home/dev/service-b",
                    title = "Devbox Task B",
                    state = SessionState.RUNNING,
                    createdAt = 1_000L,
                    updatedAt = 1_900L,
                    lastSeq = 0,
                ),
            ),
        )
        transport.sessionEventsMap[runSidA] = mutableListOf(
            AgentEvent.UserMessage(0, runSidA, 1_000L, "u_a", "编译服务 A"),
        )
        transport.sessionEventsMap[runSidB] = mutableListOf(
            AgentEvent.UserMessage(0, runSidB, 1_000L, "u_b", "压测服务 B"),
        )

        JvmPlatformTestHooks.simulateDesktopLayout = true
        JvmPlatformTestHooks.sshConnectSupported = true
        JvmPlatformTestHooks.sshConfigHosts = listOf("devbox", "devbox-01")
        // Build valid base64url pairing link for remote devbox
        val rawPairJson = """{"v":2,"hubId":"hub_fake_1","name":"Remote Devbox","role":"master","urls":["https://192.168.10.20:64450"],"url":"https://192.168.10.20:64450","fp":"${"a".repeat(64)}","pairCode":"lwp_0123456789ab_secret1234567890abcd"}"""
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rawPairJson.encodeToByteArray())
        JvmPlatformTestHooks.nextSshPairingResult = Result.success("lework://pair?d=$b64")

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            assertEquals(listOf("devbox", "devbox-01"), store.state.value.sshConfigHosts)

            // 1. One-click SSH pairing to `devbox`
            store.pairViaSshHost("devbox")
            runCurrent()
            assertEquals(1, transport.pairRedeemCount)
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)

            // 2. Select Task A while Task B continues streaming in the background on Desktop!
            store.selectSession(runSidA)
            runCurrent()

            transport.emitEvent(
                AgentEvent.AssistantDelta(
                    seq = 1,
                    sessionId = runSidB,
                    ts = 3_000L,
                    messageId = "m_b_live",
                    text = "后台实时流式压测报告：QPS 48,000",
                ),
            )
            runCurrent()

            // Task B's transcript is ALREADY warm in memory BEFORE the user even clicks it!
            val warmItemsB = store.state.value.transcripts[runSidB]?.items
                ?.filterIsInstance<ChatTimelineItem.Assistant>()
                ?.map { it.text }
                .orEmpty()
            assertEquals(listOf("后台实时流式压测报告：QPS 48,000"), warmItemsB)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T12_3 selectEngineAndModel updates active session model without snap-back and detaches when switching engine`() = runTest {
        val transport = FakeHubTransport()
        val sonnetSpec = EngineModelSpec(
            id = "claude-sonnet-4-6",
            name = "Claude Sonnet 4.6",
            efforts = emptyList(),
            defaultEffort = null,
        )
        val geminiProSpec = EngineModelSpec(
            id = "gemini-3.1-pro",
            name = "Gemini 3.1 Pro",
            efforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            defaultEffort = ReasoningEffort.HIGH,
        )
        val opusSpec = EngineModelSpec(
            id = "claude-opus-4-6",
            name = "Claude Opus 4.6",
            efforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            defaultEffort = ReasoningEffort.HIGH,
        )
        transport.enginesOverride = listOf(
            EngineInfo(
                id = "agy",
                displayName = "AGY",
                kind = EngineKind.CLI,
                status = EngineStatus(installed = true, ready = true, version = "0.2.3"),
                capabilities = EngineCapabilities(
                    permissions = true,
                    images = true,
                    modelSwitch = true,
                    modes = true,
                    history = true,
                    cancel = true,
                ),
                models = listOf(geminiProSpec, sonnetSpec),
                defaultModel = "gemini-3.1-pro",
                defaultMode = SessionMode.AUTO,
                defaultEffort = ReasoningEffort.HIGH,
                supportedModes = listOf(SessionMode.AUTO, SessionMode.PLAN),
            ),
            EngineInfo(
                id = "claude",
                displayName = "Claude Code",
                kind = EngineKind.CLI,
                status = EngineStatus(installed = true, ready = true, version = "2.1.328"),
                capabilities = EngineCapabilities(
                    permissions = true,
                    images = true,
                    modelSwitch = true,
                    modes = true,
                    history = true,
                    cancel = true,
                ),
                models = listOf(opusSpec),
                defaultModel = "claude-opus-4-6",
                defaultMode = SessionMode.ACCEPT_EDITS,
                defaultEffort = ReasoningEffort.MEDIUM,
                supportedModes = listOf(SessionMode.AUTO, SessionMode.ACCEPT_EDITS, SessionMode.PLAN),
            ),
        )
        val agySid = "host:agy:conv_switch_1"
        transport.sessions.add(
            SessionMeta(
                id = agySid,
                engineId = "agy",
                cwd = "/workspace/lework",
                title = "Model Switch Session",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastSeq = 0,
                model = "gemini-3.1-pro",
                reasoningEffort = ReasoningEffort.HIGH,
                mode = SessionMode.AUTO,
            ),
        )
        transport.sessionEventsMap[agySid] = mutableListOf(
            AgentEvent.UserMessage(0, agySid, 1_000L, "u_0", "现在是什么模型"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(agySid)
            runCurrent()
            assertEquals("gemini-3.1-pro", store.state.value.selectedModelId)

            // 1. Switch model within same engine (`agy`: gemini-3.1-pro -> claude-sonnet-4-6) and mode -> PLAN
            store.selectEngineAndModel("agy", sonnetSpec)
            runCurrent()
            assertEquals("claude-sonnet-4-6", store.state.value.selectedModelId)
            assertEquals("claude-sonnet-4-6", store.state.value.activeSession?.model)
            assertEquals(1, transport.setModelCalls.size)
            assertEquals("claude-sonnet-4-6", transport.setModelCalls.last().modelId)

            store.selectMode(SessionMode.PLAN)
            runCurrent()
            assertEquals(SessionMode.PLAN, store.state.value.selectedMode)
            assertEquals(2, transport.setModelCalls.size)
            assertEquals(SessionMode.PLAN, transport.setModelCalls.last().mode)

            // 2. Prompt in active session carries new model & mode; foreground reconnect does NOT snap back to defaultModel!
            store.sendPrompt("请用新模型回答")
            runCurrent()
            assertEquals("claude-sonnet-4-6", transport.sentPromptParams.last().modelId)
            assertEquals(SessionMode.PLAN, transport.sentPromptParams.last().mode)

            JvmPlatformTestHooks.setForeground(false)
            runCurrent()
            transport.emitDisconnected("lock screen")
            runCurrent()
            JvmPlatformTestHooks.setForeground(true)
            runCurrent()
            assertEquals("claude-sonnet-4-6", store.state.value.selectedModelId)
            assertEquals(SessionMode.PLAN, store.state.value.selectedMode)

            // 3. Switching to a DIFFERENT engine (`claude`) detaches from `host:agy:*` into a clean draft
            //    so the next prompt creates a `claude` session instead of sending to the old `agy` session!
            store.selectEngineAndModel("claude", opusSpec, ReasoningEffort.MEDIUM)
            runCurrent()
            assertNull(store.state.value.activeSessionId)
            assertEquals("claude", store.state.value.selectedEngineId)
            assertEquals("claude-opus-4-6", store.state.value.selectedModelId)
            assertEquals("/workspace/lework", store.state.value.selectedWorkspacePath)
            assertTrue(agySid in transport.unsubscribedIds)

            store.sendPrompt("在 Claude 新会话里继续")
            runCurrent()
            val createdClaudeSession = store.state.value.activeSession
            assertEquals("claude", createdClaudeSession?.engineId)
            assertEquals("claude-opus-4-6", createdClaudeSession?.model)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T12_4 replaceMentionAtCursor and insertAtTriggerAtCursor compute exact post-insertion caret index mid-sentence`() {
        // 1. Replacing @query in the middle of existing Chinese text places caret right after trailing space
        val (midText, midCaret) = dev.lework.store.replaceMentionAtCursor(
            text = "请对比 @serv 和客户端逻辑",
            relativeFilePath = "hub/src/server.ts",
            cursorIndex = "请对比 @serv".length,
        )
        assertEquals("请对比 @hub/src/server.ts 和客户端逻辑", midText)
        assertEquals("请对比 @hub/src/server.ts ".length, midCaret)

        // 2. Tapping `@ 引用文件` chip inserts `@` with smart leading space and places caret right after `@`
        val (atText, atCaret) = dev.lework.store.insertAtTriggerAtCursor(
            text = "检查一下",
            cursorIndex = 4,
        )
        assertEquals("检查一下 @", atText)
        assertEquals(6, atCaret)
        assertEquals("", dev.lework.store.extractMentionQueryAtCursor(atText, atCaret))
    }

    @Test
    fun `T12_5 parseSshConfigHosts skips User git and self-hosted forge hosts while keeping devbox aliases`() {
        val sampleConfig = """
            # Public GitHub & self-hosted Git forges (with or without explicit User git)
            Host github.com
                User git
                IdentityFile ~/.ssh/id_ed25519

            Host corp-forge
                HostName repo.internal.example
                User git

            Host code.company.internal
                IdentityFile ~/.ssh/id_work

            Host my-git-alias
                HostName git.company.internal
                Port 2222

            # Real devboxes (should be preserved)
            Host devbox devbox-01
                HostName 192.168.10.20
                User dev

            Host gpu-node
                HostName 192.168.10.30
                User=ubuntu

            Match host *.internal
                User git

            Host *
                ServerAliveInterval 60
        """.trimIndent()

        val hosts = dev.lework.platform.JvmDesktopRuntime.parseSshConfigHosts(sampleConfig)
        assertEquals(listOf("devbox", "devbox-01", "gpu-node"), hosts)
    }

    @Test
    fun `T14_2 renamePairedHost persists custom alias across reconnects and resets cleanly on blank`() = runTest {
        val transport = FakeHubTransport().apply {
            customHubName = "AB12CD34EF"
        }
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            val hubId = store.state.value.hub?.id ?: error("Expected connected hub")
            assertEquals("AB12CD34EF", store.state.value.activeHostDisplayName)

            // 1. Rename paired host -> updates activeHostDisplayName and persists customName
            store.renamePairedHost(hubId, "  MacBook Pro 工作站  ")
            assertEquals("MacBook Pro 工作站", store.state.value.activeHostDisplayName)
            assertEquals("MacBook Pro 工作站", JvmPlatformTestHooks.pairedHosts.first { it.hubId == hubId }.customName)
            assertEquals("AB12CD34EF", JvmPlatformTestHooks.pairedHosts.first { it.hubId == hubId }.name)

            // 2. Subsequent reconnect / hub.hello with raw hostname MUST NOT overwrite user's custom alias!
            JvmPlatformTestHooks.setForeground(false)
            runCurrent()
            transport.emitDisconnected("sleep")
            runCurrent()
            JvmPlatformTestHooks.setForeground(true)
            runCurrent()
            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)
            assertEquals("MacBook Pro 工作站", store.state.value.activeHostDisplayName)
            assertEquals("MacBook Pro 工作站", JvmPlatformTestHooks.pairedHosts.first { it.hubId == hubId }.customName)

            // 3. Passing blank clears customName and restores default Hub name
            store.renamePairedHost(hubId, "   ")
            assertEquals("AB12CD34EF", store.state.value.activeHostDisplayName)
            assertNull(JvmPlatformTestHooks.pairedHosts.first { it.hubId == hubId }.customName)

            // 4. Even if pairedHosts was emptied while connected, renamePairedHost upserts from state.hub
            JvmPlatformTestHooks.pairedHosts = emptyList()
            store.renamePairedHost(hubId, "备用开发机")
            assertEquals("备用开发机", store.state.value.activeHostDisplayName)
            assertEquals(1, JvmPlatformTestHooks.pairedHosts.size)
            assertEquals("备用开发机", JvmPlatformTestHooks.pairedHosts.first().displayName)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T14_3 desktop local Mac Hub prioritizes loopback endpoint ahead of LAN IPs while preserving remote host priority`() {
        val localHubId = "hub_local_mac"
        val remoteHubId = "hub_remote_devbox"
        val loopback = "https://127.0.0.1:64450"
        val hosts = listOf(
            dev.lework.platform.PairedHost(
                hubId = localHubId,
                name = "MacBook Pro",
                endpoints = listOf("https://192.168.10.15:64450", loopback),
            ),
            dev.lework.platform.PairedHost(
                hubId = remoteHubId,
                name = "Remote Devbox",
                endpoints = listOf("https://192.168.10.20:64450"),
            ),
        )

        val prioritized = dev.lework.platform.JvmDesktopRuntime.prioritizeLocalHostEndpoints(
            hosts = hosts,
            localHubId = localHubId,
            localLoopbackUrl = loopback,
        )
        assertEquals(
            listOf(loopback, "https://192.168.10.15:64450"),
            prioritized.first { it.hubId == localHubId }.endpoints,
        )
        assertEquals(
            listOf("https://192.168.10.20:64450"),
            prioritized.first { it.hubId == remoteHubId }.endpoints,
        )

        val localCandidates = dev.lework.platform.JvmDesktopRuntime.buildCandidateUrls(
            activeHostId = localHubId,
            pairedHosts = hosts,
            localHubId = localHubId,
            localLoopbackUrl = loopback,
            tunnelUrls = emptyList(),
            combinedDiscoveredUrls = listOf("https://192.168.10.15:64450"),
            lastGoodUrl = "https://192.168.10.15:64450",
            savedRelayUrls = emptyList(),
        )
        assertEquals(loopback, localCandidates.first())

        val remoteCandidates = dev.lework.platform.JvmDesktopRuntime.buildCandidateUrls(
            activeHostId = remoteHubId,
            pairedHosts = prioritized,
            localHubId = localHubId,
            localLoopbackUrl = loopback,
            tunnelUrls = emptyList(),
            combinedDiscoveredUrls = emptyList(),
            lastGoodUrl = null,
            savedRelayUrls = emptyList(),
        )
        assertEquals("https://192.168.10.20:64450", remoteCandidates.first())
    }

    @Test
    fun `T15_1 isLikelyNetworkError classifies English and Chinese connectivity errors without false positives`() {
        val networkMessages = listOf(
            "read ECONNRESET",
            "getaddrinfo ENOTFOUND api.anthropic.com",
            "connect ETIMEDOUT",
            "EAI_AGAIN temporary failure in name resolution",
            "connect EHOSTUNREACH",
            "connect ENETUNREACH",
            "write EPIPE",
            "TypeError: fetch failed",
            "socket hang up",
            "Connection refused",
            "Connection reset by peer",
            "Connection closed unexpectedly",
            "Request timed out after 30s",
            "Client timeout waiting for stream",
            "Device is offline",
            "DNS lookup failed",
            "HTTP 502 Bad Gateway",
            "503 Service Unavailable",
            "504 Gateway Timeout",
            "Unable to resolve host",
            "No route to host",
            "Unexpected EOF",
            "Broken pipe",
            "电脑断网导致请求失败",
            "网络连接异常: 代理不可达",
            "连接断开，响应超时",
        )
        for (msg in networkMessages) {
            assertTrue(dev.lework.store.isLikelyNetworkError(msg), "Expected network error match for: $msg")
        }

        val nonNetworkMessages = listOf(
            null,
            "",
            "Invalid params: cwd is required",
            "Permission denied on /etc/hosts",
            "SyntaxError: Unexpected token in JSON",
            "FAILED_PRECONDITION (code 400): region unsupported",
            "typeof x === 'string'",
        )
        for (msg in nonNetworkMessages) {
            assertFalse(dev.lework.store.isLikelyNetworkError(msg), "Expected non-network error for: $msg")
        }
    }

    @Test
    fun `T15_2 aggregates frequent network errors and collapses repeated timeline error notices`() = runTest {
        val transport = FakeHubTransport()
        val sid = "host:claude:net_spam"
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "claude",
                cwd = "/workspace/lework",
                title = "断网频繁报错测试",
                state = SessionState.RUNNING,
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastSeq = 0,
            ),
        )
        transport.sessionEventsMap[sid] = mutableListOf(
            AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "帮我重构网络模块"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sid)
            runCurrent()

            // Emit 3 consecutive network errors (plus matching TurnDone error frames which must be deduped!)
            transport.emitEvent(AgentEvent.ErrorEvent(1, sid, 2_001L, "网络连接异常: read ECONNRESET"))
            transport.emitEvent(AgentEvent.TurnDone(2, sid, 2_002L, "t_1", "error", "网络连接异常: read ECONNRESET"))
            runCurrent()

            val banner1 = store.state.value.turnRecoveryBanner
            assertTrue(banner1 != null && banner1.isNetworkError)
            assertEquals(1, banner1.errorCount)
            assertEquals("网络连接异常，任务已中断", banner1.headline)

            transport.emitEvent(AgentEvent.ErrorEvent(3, sid, 3_001L, "网络连接异常: TypeError: fetch failed (ENOTFOUND)"))
            transport.emitEvent(AgentEvent.ErrorEvent(4, sid, 4_001L, "网络连接异常: socket hang up"))
            runCurrent()

            val banner3 = store.state.value.turnRecoveryBanner
            assertTrue(banner3 != null && banner3.isNetworkError)
            assertEquals(3, banner3.errorCount)
            assertEquals("检测到网络频繁异常（已报错 3 次）", banner3.headline)
            assertTrue(banner3.detail.contains("请检查电脑网络或代理后点击重试"))

            // Timeline MUST collapse all 3 consecutive network errors into ONE single Notice with `(×3)`!
            val notices = store.state.value.activeTranscript.items.filterIsInstance<ChatTimelineItem.Notice>()
            assertEquals(1, notices.size)
            assertEquals(3, notices.first().repeatCount)
            assertTrue(notices.first().text.endsWith("(×3)"))
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `T15_3 retryLastTurn Case A reconnects and resends exact offline prompt and images with zero loss`() = runTest {
        val transport = FakeHubTransport()
        val sid = "host:claude:offline_send"
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "claude",
                cwd = "/workspace/lework",
                title = "断网发送重连测试",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastSeq = 0,
            ),
        )
        transport.sessionEventsMap[sid] = mutableListOf(
            AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "初始上下文"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sid)
            runCurrent()

            // Attach an image and drop connection (computer/network offline)
            val img = PromptImageAttachment(
                mimeType = "image/png",
                dataBase64 = "aGVsbG8=",
                fileName = "error_screenshot.png",
            )
            store.attachImage(img)
            transport.connectHelloException = RuntimeException("connect ENETUNREACH")
            transport.discoverNullRemaining = 10
            transport.emitDisconnected("Network dropped")
            runCurrent()
            assertEquals(ConnectionStatus.RECONNECTING, store.state.value.connectionStatus)

            // User taps Send while offline -> fails fast, preserves exact prompt + image in banner!
            store.sendPrompt("请分析这张报错截图并修复")
            runCurrent()

            val bannerAfterSend = store.state.value.turnRecoveryBanner
            assertTrue(bannerAfterSend != null && bannerAfterSend.isNetworkError)
            assertEquals("请分析这张报错截图并修复", bannerAfterSend.failedPromptText)
            assertEquals(listOf(img), bannerAfterSend.failedImages)
            assertEquals(0, transport.sentPrompts.size)

            // Tapping Retry while STILL offline increments errorCount and STILL preserves prompt + image
            val prevCount = bannerAfterSend.errorCount
            store.retryLastTurn()
            runCurrent()
            val bannerStillOffline = store.state.value.turnRecoveryBanner
            assertTrue(bannerStillOffline != null)
            assertEquals(prevCount + 1, bannerStillOffline.errorCount)
            assertEquals("请分析这张报错截图并修复", bannerStillOffline.failedPromptText)
            assertEquals(listOf(img), bannerStillOffline.failedImages)
            assertFalse(store.state.value.retryingTurn)

            // Network comes back online -> Retry reconnects, catches up session, and resends exact prompt + image!
            transport.connectHelloException = null
            transport.discoverNullRemaining = 0
            val subCountBefore = transport.subscribeSessionCount
            store.retryLastTurn()
            runCurrent()

            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)
            assertTrue(transport.subscribeSessionCount > subCountBefore)
            assertEquals(listOf(sid to "请分析这张报错截图并修复"), transport.sentPrompts)
            assertEquals(1, transport.sentPromptImages.size)
            assertEquals("error_screenshot.png", transport.sentPromptImages.first().second.first().fileName)
            assertNull(store.state.value.turnRecoveryBanner)
            assertFalse(store.state.value.retryingTurn)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `T15_4 retryLastTurn Case B seamlessly continues mid-turn upstream network failure in same session`() = runTest {
        val transport = FakeHubTransport()
        val sid = "host:agy:mid_turn_drop"
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/lework",
                title = "中途断网无缝续跑",
                state = SessionState.RUNNING,
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastSeq = 1,
            ),
        )
        transport.sessionEventsMap[sid] = mutableListOf(
            AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "重构整个鉴权层并补齐测试"),
            AgentEvent.AssistantDelta(1, sid, 1_500L, "a_0", "已完成第一步分析，正在修改核心文件…"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sid)
            runCurrent()

            // Computer loses internet mid-turn -> Hub emits ErrorEvent + TurnDone(error)
            transport.emitEvent(AgentEvent.ErrorEvent(2, sid, 2_500L, "网络连接异常: fetch failed (ENOTFOUND)"))
            transport.emitEvent(AgentEvent.TurnDone(3, sid, 2_510L, "turn_1", "error", "网络连接异常: fetch failed (ENOTFOUND)"))
            runCurrent()

            val banner = store.state.value.turnRecoveryBanner
            assertTrue(banner != null && banner.isNetworkError)
            assertNull(banner.failedPromptText)
            assertEquals(1, banner.errorCount)

            // User taps Retry -> sends seamless continuation prompt into the EXACT same session
            store.retryLastTurn()
            runCurrent()

            assertEquals(listOf(sid to ConsoleStore.SEAMLESS_CONTINUE_PROMPT), transport.sentPrompts)
            assertNull(store.state.value.turnRecoveryBanner)

            // Simulate another error followed by streaming progress -> streaming delta auto-clears banner
            transport.emitEvent(AgentEvent.ErrorEvent(4, sid, 3_500L, "网络连接异常: ETIMEDOUT"))
            runCurrent()
            assertTrue(store.state.value.turnRecoveryBanner != null)

            transport.emitEvent(AgentEvent.AssistantDelta(5, sid, 4_000L, "a_2", "继续完成剩余重构步骤。"))
            runCurrent()
            assertNull(store.state.value.turnRecoveryBanner)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `T14_4 pasteImagesFromClipboard attaches clipboard images, enforces 4-image cap, checks engine capability, and ignores text-only clipboard`() = runTest {
        val tinyPngB64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val transport = FakeHubTransport().apply {
            enginesOverride = listOf(
                EngineInfo(
                    id = "claude",
                    displayName = "Claude Code",
                    kind = EngineKind.CLI,
                    status = EngineStatus(installed = true, ready = true),
                    capabilities = EngineCapabilities(
                        permissions = true,
                        images = true,
                        modelSwitch = true,
                        modes = true,
                        history = true,
                        cancel = true,
                    ),
                    models = listOf(EngineModelSpec(id = "claude-sonnet-4-6", name = "Claude Sonnet 4.6")),
                    defaultModel = "claude-sonnet-4-6",
                ),
                EngineInfo(
                    id = "textonly",
                    displayName = "Text Only Engine",
                    kind = EngineKind.CLI,
                    status = EngineStatus(installed = true, ready = true),
                    capabilities = EngineCapabilities(
                        permissions = true,
                        images = false,
                        modelSwitch = true,
                        modes = true,
                        history = true,
                        cancel = true,
                    ),
                    models = listOf(EngineModelSpec(id = "text-1", name = "Text 1")),
                    defaultModel = "text-1",
                ),
            )
        }

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            // 1. Empty / text-only clipboard returns false and never alters attachments or errors
            JvmPlatformTestHooks.nextClipboardImages = emptyList()
            assertFalse(store.pasteImagesFromClipboard())
            assertTrue(store.state.value.composerAttachedImages.isEmpty())
            assertNull(store.state.value.promptErrorText)

            // 2. Pasting 2 images attaches both cleanly
            JvmPlatformTestHooks.nextClipboardImages = listOf(
                dev.lework.platform.PickedImagePayload("clip_1.png", "image/png", tinyPngB64, tinyPngB64),
                dev.lework.platform.PickedImagePayload("clip_2.png", "image/png", tinyPngB64, tinyPngB64),
            )
            assertTrue(store.pasteImagesFromClipboard())
            assertEquals(2, store.state.value.composerAttachedImages.size)
            assertNull(store.state.value.promptErrorText)

            // 3. Pasting 3 more images when 2 are attached caps at 4 and sets overflow hint
            JvmPlatformTestHooks.nextClipboardImages = listOf(
                dev.lework.platform.PickedImagePayload("clip_3.png", "image/png", tinyPngB64, tinyPngB64),
                dev.lework.platform.PickedImagePayload("clip_4.png", "image/png", tinyPngB64, tinyPngB64),
                dev.lework.platform.PickedImagePayload("clip_5.png", "image/png", tinyPngB64, tinyPngB64),
            )
            assertTrue(store.pasteImagesFromClipboard())
            assertEquals(4, store.state.value.composerAttachedImages.size)
            assertEquals(
                listOf("clip_1.png", "clip_2.png", "clip_3.png", "clip_4.png"),
                store.state.value.composerAttachedImages.map { it.fileName },
            )
            assertTrue(store.state.value.promptErrorText.orEmpty().contains("最多附带 4 张图片"))

            // 4. Pasting another image at 4/4 keeps 4 images and shows limit error
            JvmPlatformTestHooks.nextClipboardImages = listOf(
                dev.lework.platform.PickedImagePayload("clip_6.png", "image/png", tinyPngB64, tinyPngB64),
            )
            assertTrue(store.pasteImagesFromClipboard())
            assertEquals(4, store.state.value.composerAttachedImages.size)
            assertEquals("单条消息最多附带 4 张图片", store.state.value.promptErrorText)

            // 5. Even at 4/4, text-only clipboard still returns false so native text paste keeps working
            JvmPlatformTestHooks.nextClipboardImages = emptyList()
            assertFalse(store.pasteImagesFromClipboard())

            // 6. Engine without image support reports friendly Chinese error when pasting an image
            store.selectEngineAndModel("textonly")
            runCurrent()
            JvmPlatformTestHooks.nextClipboardImages = listOf(
                dev.lework.platform.PickedImagePayload("clip_7.png", "image/png", tinyPngB64, tinyPngB64),
            )
            assertTrue(store.pasteImagesFromClipboard())
            assertEquals("当前引擎暂不支持附带图片", store.state.value.promptErrorText)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun `T14_6 cancels single queued prompt, clears all, surfaces host notice, and rolls back on RPC failure`() = runTest {
        val transport = FakeHubTransport()
        val sid = "sess_queue_cancel_demo"
        val baseSession = SessionMeta(
            id = sid,
            engineId = "claude",
            cwd = "/workspace/lework",
            title = "队列取消测试",
            state = SessionState.RUNNING,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 0,
            queuedPrompts = listOf("排队一", "排队二", "排队三"),
        )
        transport.sessions.add(baseSession)
        transport.sessionEventsMap[sid] = mutableListOf(
            AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "当前运行任务"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sid)
            runCurrent()

            assertEquals(listOf("排队一", "排队二", "排队三"), store.state.value.activeSession?.queuedPrompts)

            // 1. Single-item cancel updates optimistically before RPC resolves
            transport.cancelQueuedDelayMs = 200L
            store.cancelQueuedPrompt(1)
            assertEquals(listOf("排队一", "排队三"), store.state.value.activeSession?.queuedPrompts)
            advanceTimeBy(200L)
            runCurrent()
            assertEquals(listOf(Triple<String, Int?, Boolean>(sid, 1, false)), transport.cancelQueuedCalls.toList())
            assertEquals(listOf("排队一", "排队三"), store.state.value.activeSession?.queuedPrompts)

            // 2. Clear all reconciles state and surfaces non-blank host notice
            transport.cancelQueuedDelayMs = 0L
            transport.cancelQueuedNotice = "已从 Lework 排队列表移除；若消息已写入电脑终端待执行区，请在终端内按需撤销"
            store.clearQueuedPrompts()
            runCurrent()
            assertEquals(Triple<String, Int?, Boolean>(sid, null, true), transport.cancelQueuedCalls.last())
            assertNull(store.state.value.activeSession?.queuedPrompts)
            assertEquals(transport.cancelQueuedNotice, store.state.value.promptNoticeText)

            store.dismissPromptNotice()
            assertNull(store.state.value.promptNoticeText)

            // 3. RPC failure rolls back optimistic removal and surfaces error banner
            transport.emitSessionUpdate(baseSession.copy(queuedPrompts = listOf("保留消息A", "保留消息B")))
            runCurrent()
            assertEquals(listOf("保留消息A", "保留消息B"), store.state.value.activeSession?.queuedPrompts)

            transport.cancelQueuedNotice = null
            transport.cancelQueuedException = RpcCallException(ErrorCode.INTERNAL, "网络超时")
            store.cancelQueuedPrompt(0)
            runCurrent()
            assertEquals(listOf("保留消息A", "保留消息B"), store.state.value.activeSession?.queuedPrompts)
            assertTrue(store.state.value.promptErrorText.orEmpty().contains("网络超时"))
        } finally {
            storeScope.cancel()
        }
    }
}
