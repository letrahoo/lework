package dev.lework

import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.PermissionOption
import dev.lework.protocol.PermissionOptionKind
import dev.lework.protocol.PlanEntry
import dev.lework.protocol.PromptImageAttachment
import dev.lework.protocol.ReasoningEffort
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionMode
import dev.lework.protocol.SessionState
import dev.lework.protocol.StreamSubscribeResult
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleStore
import dev.lework.store.findRemappedHostSession
import dev.lework.transport.HubTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionIsolationTest {

    @Test
    fun findRemappedHostSessionNeverHijacksRunningSiblingInSameCwd() {
        val prevA = SessionMeta(
            id = "sess_aaaa1111",
            engineId = "agy",
            cwd = "/workspace/lework",
            title = "Fix login redirect",
            state = SessionState.IDLE,
            createdAt = 1_730_000_100_000L,
            updatedAt = 1_730_000_110_000L,
            lastSeq = 3,
        )
        // Hub sorts RUNNING sessions BEFORE IDLE sessions even when they share the same cwd!
        val runningB = SessionMeta(
            id = "host:agy:conv-b",
            engineId = "agy",
            cwd = "/workspace/lework",
            title = "Build release pipeline",
            state = SessionState.RUNNING,
            createdAt = 1_730_000_000_000L,
            updatedAt = 1_730_000_200_000L,
            lastSeq = 12,
        )
        val persistedA = SessionMeta(
            id = "host:agy:conv-a",
            engineId = "agy",
            cwd = "/workspace/lework",
            title = "Fix login redirect",
            state = SessionState.IDLE,
            createdAt = 1_730_000_101_000L,
            updatedAt = 1_730_000_120_000L,
            lastSeq = 4,
        )

        // 1. Exact title + cwd selects persistedA even though runningB comes first in listSessions
        val matched = findRemappedHostSession(
            prevMeta = prevA,
            fallbackEngineId = "agy",
            candidates = listOf(runningB, persistedA),
        )
        assertEquals("host:agy:conv-a", matched?.id)

        // 2. If persistedA is not on disk yet, MUST return null instead of hijacking older runningB in same cwd!
        val noHijack = findRemappedHostSession(
            prevMeta = prevA,
            fallbackEngineId = "agy",
            candidates = listOf(runningB),
        )
        assertNull(noHijack)
    }

    @Test
    fun desktopWarmBackgroundSessionEventsAndForeignSnapshotEventsNeverLeakIntoActiveSessionA() = runBlocking {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.simulateDesktopLayout = true
        try {
            val sidA = "host:agy:conv-a"
            val sidB = "host:agy:conv-b"
            val stepsA = listOf(PlanEntry("Session A own step", "completed"))
            val stepsB = listOf(
                PlanEntry("Session B background step 1", "completed"),
                PlanEntry("Session B background step 2", "in_progress"),
            )

            val base = FakeHubTransport()
            base.sessions.addAll(
                listOf(
                    SessionMeta(
                        id = sidB,
                        engineId = "agy",
                        cwd = "/workspace/lework",
                        title = "AGY Session B (running)",
                        state = SessionState.RUNNING,
                        createdAt = 1_730_000_000_000L,
                        updatedAt = 1_730_000_020_000L,
                        lastSeq = 1,
                    ),
                    SessionMeta(
                        id = sidA,
                        engineId = "agy",
                        cwd = "/workspace/lework",
                        title = "AGY Session A (active)",
                        state = SessionState.IDLE,
                        createdAt = 1_730_000_000_000L,
                        updatedAt = 1_730_000_010_000L,
                        lastSeq = 2,
                    ),
                ),
            )
            base.sessionEventsMap[sidA] = mutableListOf(
                AgentEvent.UserMessage(0, sidA, 1_730_000_001_000L, "ua1", "Task A"),
                AgentEvent.PlanUpdated(1, sidA, 1_730_000_002_000L, stepsA),
                // Deliberately inject a foreign sessionId event into snapshot to verify client defense-in-depth!
                AgentEvent.PlanUpdated(2, sidB, 1_730_000_003_000L, stepsB),
                AgentEvent.PermissionRequested(
                    seq = 2,
                    sessionId = sidB,
                    ts = 1_730_000_003_500L,
                    requestId = "tty_perm_foreign_b",
                    title = "Foreign B approval",
                    options = listOf(PermissionOption("tty_key:1", "1. Yes", PermissionOptionKind.ALLOW_ONCE)),
                ),
            )
            base.sessionEventsMap[sidB] = mutableListOf(
                AgentEvent.UserMessage(0, sidB, 1_730_000_005_000L, "ub1", "Task B"),
            )

            val transport = object : HubTransport by base {
                override suspend fun subscribeSession(
                    sessionId: String,
                    afterSeq: Int,
                    beforeSeq: Int?,
                    aroundSeq: Int?,
                    limit: Int?,
                ): StreamSubscribeResult = base.subscribeSession(sessionId, afterSeq, beforeSeq, aroundSeq, limit)
            }

            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val store = ConsoleStore(scope, transport)
            try {
                store.bootstrapOrConnect()
                var tries = 0
                while (store.state.value.connectionStatus != ConnectionStatus.CONNECTED && tries++ < 80) {
                    delay(20L)
                }

                store.selectSession(sidA)
                tries = 0
                while (store.state.value.activeTranscript.items.isEmpty() && tries++ < 80) {
                    delay(20L)
                }

                // Active session A keeps ONLY A's steps and zero foreign permissions
                assertEquals(stepsA, store.state.value.activeTranscript.planEntries)
                assertTrue(store.state.value.activeTranscript.pendingPermissions.isEmpty())

                // Background warm-subscribed session B streams live steps + permission -> MUST stay in B only!
                base.emitEvent(AgentEvent.PlanUpdated(5, sidB, 1_730_000_030_000L, stepsB))
                base.emitEvent(
                    AgentEvent.PermissionRequested(
                        seq = 5,
                        sessionId = sidB,
                        ts = 1_730_000_031_000L,
                        requestId = "tty_perm_b_live",
                        title = "Run rm -rf build?",
                        options = listOf(PermissionOption("tty_key:1", "1. Yes", PermissionOptionKind.ALLOW_ONCE)),
                    ),
                )
                tries = 0
                while ((store.state.value.transcripts[sidB]?.planEntries != stepsB) && tries++ < 80) {
                    delay(20L)
                }

                assertEquals(stepsA, store.state.value.activeTranscript.planEntries)
                assertTrue(store.state.value.activeTranscript.pendingPermissions.isEmpty())
                assertEquals(stepsB, store.state.value.transcripts[sidB]?.planEntries)
                assertEquals(1, store.state.value.transcripts[sidB]?.pendingPermissions?.size)

                // When B transitions to IDLE via session.updated, stale `tty_perm_*` clears automatically
                base.emitSessionUpdate(
                    base.sessions.first { it.id == sidB }.copy(state = SessionState.IDLE, updatedAt = 1_730_000_040_000L),
                )
                tries = 0
                while (store.state.value.transcripts[sidB]?.pendingPermissions?.isNotEmpty() == true && tries++ < 80) {
                    delay(20L)
                }
                assertTrue(store.state.value.transcripts[sidB]?.pendingPermissions.orEmpty().isEmpty())
            } finally {
                scope.cancel()
            }
        } finally {
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun sendPromptAndApprovalErrorsStayBoundToOriginatingSessionAcrossSessionSwitches() = runBlocking {
        JvmPlatformTestHooks.reset()
        try {
            val sidA = "host:agy:origin-a"
            val sidB = "host:agy:other-b"
            val base = FakeHubTransport()
            base.sessions.addAll(
                listOf(
                    SessionMeta(
                        id = sidA,
                        engineId = "agy",
                        cwd = "/workspace/lework",
                        title = "Session A",
                        state = SessionState.WAITING_PERMISSION,
                        createdAt = 1_730_000_000_000L,
                        updatedAt = 1_730_000_010_000L,
                        lastSeq = 1,
                    ),
                    SessionMeta(
                        id = sidB,
                        engineId = "agy",
                        cwd = "/workspace/lework",
                        title = "Session B",
                        state = SessionState.IDLE,
                        createdAt = 1_730_000_000_000L,
                        updatedAt = 1_730_000_011_000L,
                        lastSeq = 1,
                    ),
                ),
            )
            base.sessionEventsMap[sidA] = mutableListOf(
                AgentEvent.UserMessage(0, sidA, 1_730_000_001_000L, "ua", "Hello A"),
                AgentEvent.PermissionRequested(
                    seq = 1,
                    sessionId = sidA,
                    ts = 1_730_000_002_000L,
                    requestId = "tty_perm_a_fail",
                    title = "Approve A?",
                    options = listOf(PermissionOption("tty_key:1", "1. Yes", PermissionOptionKind.ALLOW_ONCE)),
                ),
            )
            base.sessionEventsMap[sidB] = mutableListOf(
                AgentEvent.UserMessage(0, sidB, 1_730_000_001_000L, "ub", "Hello B"),
            )

            val releaseApproval = CompletableDeferred<Unit>()
            val releasePrompt = CompletableDeferred<Unit>()
            val sentPrompts = mutableListOf<Pair<String, String>>()

            val transport = object : HubTransport by base {
                override suspend fun respondApproval(sessionId: String, requestId: String, optionId: String?) {
                    releaseApproval.await()
                    error("终端审批已过期")
                }

                override suspend fun sendPrompt(
                    sessionId: String,
                    text: String,
                    images: List<PromptImageAttachment>?,
                    providerId: String?,
                    modelId: String?,
                    reasoningEffort: ReasoningEffort?,
                    mode: SessionMode?,
                ): String {
                    releasePrompt.await()
                    sentPrompts.add(sessionId to text)
                    return "turn_iso"
                }
            }

            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val store = ConsoleStore(scope, transport)
            try {
                store.bootstrapOrConnect()
                var tries = 0
                while (store.state.value.connectionStatus != ConnectionStatus.CONNECTED && tries++ < 80) {
                    delay(20L)
                }
                store.selectSession(sidA)
                tries = 0
                while (store.state.value.activeTranscript.pendingPermissions.isEmpty() && tries++ < 80) {
                    delay(20L)
                }

                // Trigger both approval and prompt on A, then immediately switch to B before RPCs complete
                store.respondApproval("tty_perm_a_fail", "tty_key:1", sidA)
                store.sendPrompt("Only for session A")
                delay(20L)
                store.selectSession(sidB)
                tries = 0
                while (store.state.value.activeSessionId != sidB && tries++ < 80) {
                    delay(20L)
                }

                releaseApproval.complete(Unit)
                releasePrompt.complete(Unit)
                tries = 0
                while (sentPrompts.isEmpty() && tries++ < 80) {
                    delay(20L)
                }
                delay(60L)

                // Prompt sent from A MUST target sidA (never sidB), and A's approval error MUST NOT appear in B!
                assertEquals(listOf(sidA to "Only for session A"), sentPrompts)
                assertEquals(sidB, store.state.value.activeSessionId)
                assertNull(store.state.value.approvalErrorText)
            } finally {
                scope.cancel()
            }
        } finally {
            JvmPlatformTestHooks.reset()
        }
    }
}
