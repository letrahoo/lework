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
}
