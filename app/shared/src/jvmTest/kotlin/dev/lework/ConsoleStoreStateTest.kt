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
}
