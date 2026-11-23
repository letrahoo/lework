package dev.lework

import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.*
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostCancelSyncTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @Test
    fun `cancelActiveTurn shows stopping feedback, immediately transitions to IDLE clearing permissions, and surfaces RPC errors`() = runTest {
        val transport = FakeHubTransport()
        val sid = "host:agy:cancel_sync_test"
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/lework",
                title = "Cancel Sync Test",
                state = SessionState.WAITING_PERMISSION,
                liveStatusText = "等待执行审批…",
                turnStartedAt = 1_500L,
                queuedPrompts = listOf("排队中的跟进消息"),
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastSeq = 1,
            ),
        )
        transport.sessionEventsMap[sid] = mutableListOf(
            AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "执行长时间任务"),
            AgentEvent.PermissionRequested(
                seq = 1,
                sessionId = sid,
                ts = 1_500L,
                requestId = "tty_perm_cancel1",
                title = "Bash command\npnpm test",
                options = listOf(
                    PermissionOption("tty_key:1", "1. Yes", PermissionOptionKind.ALLOW_ONCE),
                    PermissionOption("tty_key:2", "2. No", PermissionOptionKind.REJECT_ONCE),
                ),
            ),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sid)
            runCurrent()

            assertEquals(SessionState.WAITING_PERMISSION, store.state.value.activeSession?.state)
            assertEquals(1, store.state.value.activeTranscript.pendingPermissions.size)

            // 1. Failure path: restores liveStatusText and surfaces Chinese error instead of swallowing it!
            transport.cancelSessionDelayMs = 100L
            transport.cancelSessionException = RpcCallException(ErrorCode.NOT_FOUND, "未找到正在运行的本机终端会话")
            store.cancelActiveTurn()
            assertEquals("正在停止…", store.state.value.activeSession?.liveStatusText)

            advanceTimeBy(120L)
            runCurrent()
            assertEquals("等待执行审批…", store.state.value.activeSession?.liveStatusText)
            assertEquals(SessionState.WAITING_PERMISSION, store.state.value.activeSession?.state)
            assertEquals("未找到正在运行的本机终端会话", store.state.value.promptErrorText)

            // 2. Success path: immediately transitions to IDLE and clears liveStatusText, turnStartedAt, queuedPrompts & permissions!
            transport.cancelSessionException = null
            store.cancelActiveTurn()
            assertEquals("正在停止…", store.state.value.activeSession?.liveStatusText)
            assertNull(store.state.value.promptErrorText)

            advanceTimeBy(120L)
            runCurrent()
            val afterCancel = store.state.value.activeSession
            assertEquals(SessionState.IDLE, afterCancel?.state)
            assertNull(afterCancel?.liveStatusText)
            assertNull(afterCancel?.turnStartedAt)
            assertNull(afterCancel?.queuedPrompts)
            assertTrue(store.state.value.activeTranscript.pendingPermissions.isEmpty())
            assertNull(store.state.value.promptErrorText)
            assertEquals(listOf(sid, sid), transport.cancelledSessionIds)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }
}
