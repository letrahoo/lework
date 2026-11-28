package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.ArtifactContentResult
import dev.lework.protocol.ArtifactInfo
import dev.lework.protocol.ArtifactKind
import dev.lework.protocol.ArtifactSource
import dev.lework.protocol.PermissionOption
import dev.lework.protocol.PermissionOptionKind
import dev.lework.protocol.PlanEntry
import dev.lework.protocol.PromptFileAttachment
import dev.lework.protocol.PromptImageAttachment
import dev.lework.protocol.ReasoningEffort
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionMode
import dev.lework.protocol.SessionState
import dev.lework.store.ChatTimelineItem
import dev.lework.store.ConsoleStore
import dev.lework.store.SessionTranscript
import dev.lework.store.clearStalePlanBeforeNewPrompt
import dev.lework.store.reduceTranscript
import dev.lework.store.reduceTranscriptBatch
import dev.lework.store.syncPlanLifecycleWithSessionState
import dev.lework.store.withFallbackLatestPlan
import dev.lework.transport.HubTransport
import dev.lework.ui.ConsoleApp
import dev.lework.ui.ConversationStreamView
import dev.lework.ui.EverforestColorScheme
import dev.lework.ui.ExecutionStepsLifecycle
import dev.lework.ui.defaultExpandedForLifecycle
import dev.lework.ui.resolveExecutionStepsLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ExecutionStepsLifecycleTest {

    @Test
    fun resolveExecutionStepsLifecycleCoversAllStatesAndDefaultExpansionRules() {
        val mixedRunning = listOf(
            PlanEntry("Step 1", "completed"),
            PlanEntry("Step 2", "in_progress"),
            PlanEntry("Step 3", "pending"),
        )
        val pendingOnly = listOf(
            PlanEntry("Plan 1", "pending"),
            PlanEntry("Plan 2", "pending"),
        )
        val partialPending = listOf(
            PlanEntry("Plan 1", "completed"),
            PlanEntry("Plan 2", "pending"),
        )
        val allDone = listOf(
            PlanEntry("Step 1", "completed"),
            PlanEntry("Step 2", "skipped"),
        )
        val withFailed = listOf(
            PlanEntry("Step 1", "completed"),
            PlanEntry("Step 2", "failed"),
            PlanEntry("Step 3", "in_progress"),
        )

        // Running
        assertEquals(
            ExecutionStepsLifecycle.RUNNING,
            resolveExecutionStepsLifecycle(mixedRunning, SessionState.RUNNING, isSessionRunning = true, isWaitingPermission = false, hasApprovePlanAction = false),
        )
        assertTrue(defaultExpandedForLifecycle(ExecutionStepsLifecycle.RUNNING, hasApprovePlanAction = false))

        // Waiting permission overrides running / in_progress / failed
        assertEquals(
            ExecutionStepsLifecycle.WAITING_PERMISSION,
            resolveExecutionStepsLifecycle(mixedRunning, SessionState.WAITING_PERMISSION, isSessionRunning = false, isWaitingPermission = false, hasApprovePlanAction = false),
        )
        assertEquals(
            ExecutionStepsLifecycle.WAITING_PERMISSION,
            resolveExecutionStepsLifecycle(withFailed, SessionState.RUNNING, isSessionRunning = true, isWaitingPermission = true, hasApprovePlanAction = false),
        )
        assertTrue(defaultExpandedForLifecycle(ExecutionStepsLifecycle.WAITING_PERMISSION, hasApprovePlanAction = false))

        // Inactive with leftover in_progress -> STOPPED (collapsed by default even if old plan artifact exists)
        assertEquals(
            ExecutionStepsLifecycle.STOPPED,
            resolveExecutionStepsLifecycle(mixedRunning, SessionState.IDLE, isSessionRunning = false, isWaitingPermission = false, hasApprovePlanAction = false),
        )
        assertEquals(
            ExecutionStepsLifecycle.STOPPED,
            resolveExecutionStepsLifecycle(mixedRunning, SessionState.CLOSED, isSessionRunning = false, isWaitingPermission = false, hasApprovePlanAction = true),
        )
        assertFalse(defaultExpandedForLifecycle(ExecutionStepsLifecycle.STOPPED, hasApprovePlanAction = true))

        // Inactive with pending only & no approve-plan action -> INCOMPLETE (collapsed by default)
        assertEquals(
            ExecutionStepsLifecycle.INCOMPLETE,
            resolveExecutionStepsLifecycle(partialPending, SessionState.IDLE, isSessionRunning = false, isWaitingPermission = false, hasApprovePlanAction = false),
        )
        assertFalse(defaultExpandedForLifecycle(ExecutionStepsLifecycle.INCOMPLETE, hasApprovePlanAction = false))

        // Inactive with pending steps & approve-plan action -> PLAN_APPROVAL_PENDING (expanded by default)
        assertEquals(
            ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING,
            resolveExecutionStepsLifecycle(pendingOnly, SessionState.IDLE, isSessionRunning = false, isWaitingPermission = false, hasApprovePlanAction = true),
        )
        assertTrue(defaultExpandedForLifecycle(ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING, hasApprovePlanAction = true))

        // Completed: collapsed normally, expanded when approve-plan action is presented
        assertEquals(
            ExecutionStepsLifecycle.COMPLETED,
            resolveExecutionStepsLifecycle(allDone, SessionState.IDLE, isSessionRunning = false, isWaitingPermission = false, hasApprovePlanAction = false),
        )
        assertEquals(
            ExecutionStepsLifecycle.COMPLETED,
            resolveExecutionStepsLifecycle(allDone, SessionState.ERROR, isSessionRunning = false, isWaitingPermission = false, hasApprovePlanAction = false),
        )
        assertFalse(defaultExpandedForLifecycle(ExecutionStepsLifecycle.COMPLETED, hasApprovePlanAction = false))
        assertTrue(defaultExpandedForLifecycle(ExecutionStepsLifecycle.COMPLETED, hasApprovePlanAction = true))

        // Failed: explicit failed entry or SessionState.ERROR while unfinished -> FAILED (collapsed by default)
        assertEquals(
            ExecutionStepsLifecycle.FAILED,
            resolveExecutionStepsLifecycle(withFailed, SessionState.IDLE, isSessionRunning = false, isWaitingPermission = false, hasApprovePlanAction = false),
        )
        assertEquals(
            ExecutionStepsLifecycle.FAILED,
            resolveExecutionStepsLifecycle(mixedRunning, SessionState.ERROR, isSessionRunning = false, isWaitingPermission = false, hasApprovePlanAction = false),
        )
        assertFalse(defaultExpandedForLifecycle(ExecutionStepsLifecycle.FAILED, hasApprovePlanAction = false))
    }

    @Test
    fun transcriptTurnHygieneClearsInterruptedPlansAcrossTurnsWithFoldBatchParity() {
        val sid = "sess_hygiene"
        val interruptedEntries = listOf(
            PlanEntry("检查最新版本与已连接的 iPhone 设备", "completed"),
            PlanEntry("同步最新代码准备 iOS 构建环境", "completed"),
            PlanEntry("编译签名并安装 Lework 到 iPhone", "in_progress"),
        )

        fun assertParity(events: List<AgentEvent>): SessionTranscript {
            val sequential = events.fold(SessionTranscript()) { acc, ev -> reduceTranscript(acc, ev) }
            val batched = reduceTranscriptBatch(SessionTranscript(), events)
            assertEquals(sequential, batched)
            return batched
        }

        // 1. Interrupted turn ends (TurnDone cancelled + SessionState IDLE): keeps plan for current turn summary
        val turn1EndedEvents = listOf<AgentEvent>(
            AgentEvent.UserMessage(seq = 1, sessionId = sid, ts = 1_000L, messageId = "u1", text = "安装到 iPhone"),
            AgentEvent.PlanUpdated(seq = 2, sessionId = sid, ts = 1_100L, entries = interruptedEntries),
            AgentEvent.TurnDone(seq = 3, sessionId = sid, ts = 1_500L, turnId = "t1", stopReason = "cancelled"),
            AgentEvent.SessionStateChanged(seq = 4, sessionId = sid, ts = 1_501L, state = SessionState.IDLE),
        )
        val afterTurn1 = assertParity(turn1EndedEvents)
        assertEquals(3, afterTurn1.planEntries.size)
        assertTrue(afterTurn1.planTurnEnded)
        assertFalse(afterTurn1.planSeenPostUpdateUserTurn)

        // 2. Starting Turn 2 clears the stale interrupted plan from Turn 1 immediately
        val turn2Events = turn1EndedEvents + AgentEvent.UserMessage(
            seq = 5,
            sessionId = sid,
            ts = 2_000L,
            messageId = "u2",
            text = "现在帮我看看登录页",
        )
        val afterTurn2Start = assertParity(turn2Events)
        assertTrue(afterTurn2Start.planEntries.isEmpty())
        assertEquals(0L, afterTurn2Start.planUpdatedAt)
        assertFalse(afterTurn2Start.planTurnEnded)

        // 3. Ending via SessionStateChanged(ERROR) or CLOSED also marks plan ended and clears on next turn
        for (inactiveState in listOf(SessionState.ERROR, SessionState.CLOSED)) {
            val endedByState = listOf<AgentEvent>(
                AgentEvent.PlanUpdated(seq = 1, sessionId = sid, ts = 1_000L, entries = interruptedEntries),
                AgentEvent.SessionStateChanged(seq = 2, sessionId = sid, ts = 1_500L, state = inactiveState),
            )
            val tEnded = assertParity(endedByState)
            assertEquals(3, tEnded.planEntries.size)
            assertTrue(tEnded.planTurnEnded)

            val tNext = assertParity(
                endedByState + AgentEvent.UserMessage(seq = 3, sessionId = sid, ts = 2_000L, messageId = "u_next", text = "新任务"),
            )
            assertTrue(tNext.planEntries.isEmpty())
        }

        // 4. Mid-turn follow-up keeps active plan, then clears if turn ends without updating plan OR a 2nd user turn arrives
        val midTurnEvents = listOf<AgentEvent>(
            AgentEvent.PlanUpdated(seq = 1, sessionId = sid, ts = 1_000L, entries = interruptedEntries),
            AgentEvent.UserMessage(seq = 2, sessionId = sid, ts = 1_200L, messageId = "u_mid", text = "顺便看下日志"),
        )
        val afterMidTurn = assertParity(midTurnEvents)
        assertEquals(3, afterMidTurn.planEntries.size)
        assertTrue(afterMidTurn.planSeenPostUpdateUserTurn)

        val midTurnEndedWithoutUpdate = assertParity(
            midTurnEvents + AgentEvent.TurnDone(seq = 3, sessionId = sid, ts = 1_800L, turnId = "t1", stopReason = "end_turn"),
        )
        assertTrue(midTurnEndedWithoutUpdate.planEntries.isEmpty())

        val secondUserTurnWithoutUpdate = assertParity(
            midTurnEvents + AgentEvent.UserMessage(seq = 3, sessionId = sid, ts = 1_900L, messageId = "u_third", text = "再换个任务"),
        )
        assertTrue(secondUserTurnWithoutUpdate.planEntries.isEmpty())

        // 5. Fallback hydration & session-state sync / pre-prompt clearing
        val hydratedIdle = SessionTranscript().withFallbackLatestPlan(
            latestPlan = interruptedEntries,
            fallbackTs = 5_000L,
            sessionState = SessionState.IDLE,
        )
        assertEquals(3, hydratedIdle.planEntries.size)
        assertTrue(hydratedIdle.planTurnEnded)
        assertTrue(hydratedIdle.clearStalePlanBeforeNewPrompt().planEntries.isEmpty())

        val hydratedRunning = SessionTranscript().withFallbackLatestPlan(
            latestPlan = interruptedEntries,
            fallbackTs = 5_000L,
            sessionState = SessionState.RUNNING,
        )
        assertEquals(3, hydratedRunning.planEntries.size)
        assertFalse(hydratedRunning.planTurnEnded)
        // Active non-ended plan is NOT cleared before a mid-turn follow-up prompt
        assertEquals(3, hydratedRunning.clearStalePlanBeforeNewPrompt().planEntries.size)
        // Syncing to IDLE marks turn ended while keeping collapsed steps
        val syncedIdle = hydratedRunning.syncPlanLifecycleWithSessionState(SessionState.IDLE)
        assertEquals(3, syncedIdle.planEntries.size)
        assertTrue(syncedIdle.planTurnEnded)
    }

    @Test
    fun inactiveIdleSessionWithLeftoverInProgressAutoCollapsesAndShowsStoppedRowIndicator() = runComposeUiTest {
        val sid = "host:agy:conv-denied-perm"
        val idleSession = SessionMeta(
            id = sid,
            engineId = "agy",
            cwd = "/tmp/lework",
            title = "Deploy to iPhone",
            state = SessionState.IDLE,
            createdAt = 1_730_000_000_000L,
            updatedAt = 1_730_000_020_000L,
            lastSeq = 4,
        )
        val transcript = SessionTranscript(
            earliestSeq = 1,
            lastSeq = 4,
            items = listOf(
                ChatTimelineItem.Assistant(id = "a1", text = "好的，已取消安装到 iPhone。", ts = 1_730_000_020_000L, seq = 4),
            ),
            planEntries = listOf(
                PlanEntry("检查最新版本与已连接的 iPhone 设备", "completed"),
                PlanEntry("同步最新代码准备 iOS 构建环境", "completed"),
                PlanEntry("编译签名并安装 Lework 到 iPhone", "in_progress"),
            ),
        )

        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(640.dp, 480.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = idleSession,
                        transcript = transcript,
                        isSessionRunning = false,
                        onLoadOlder = {},
                    )
                }
            }
        }
        waitForIdle()

        // Must auto-collapse to a quiet 1-line stopped summary pill (NOT expanded, NOT looking active!)
        onNodeWithTag("execution_steps_card", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("2/3 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("已中断", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("编译签名并安装 Lework 到 iPhone", useUnmergedTree = true).assertDoesNotExist()

        // Tapping header unfolds steps and shows a muted stopped indicator (NEVER the live running dot!)
        onNodeWithTag("execution_steps_header", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("检查最新版本与已连接的 iPhone 设备", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("编译签名并安装 Lework 到 iPhone", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("execution_step_status_completed_0", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("execution_step_status_completed_1", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("execution_step_status_stopped_2", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("已中断")
        onNodeWithTag("execution_step_status_running_2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun waitingPermissionRemainsExpandedWithCalmPausedCueForBothStateAndPendingPerms() = runComposeUiTest {
        val sid = "host:agy:conv-waiting-perm"
        var sessionState by mutableStateOf(SessionState.WAITING_PERMISSION)
        var pendingPerms by mutableStateOf<List<AgentEvent.PermissionRequested>>(emptyList())

        val baseEntries = listOf(
            PlanEntry("检查最新版本与已连接的 iPhone 设备", "completed"),
            PlanEntry("同步最新代码准备 iOS 构建环境", "completed"),
            PlanEntry("编译签名并安装 Lework 到 iPhone", "in_progress"),
        )

        setContent {
            val sess = SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/tmp/lework",
                title = "Waiting Permission Demo",
                state = sessionState,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                lastSeq = 5,
            )
            val transcript = SessionTranscript(
                earliestSeq = 1,
                lastSeq = 5,
                items = listOf(
                    ChatTimelineItem.Assistant(id = "a1", text = "准备执行真机编译安装。", ts = 1_730_000_005_000L, seq = 5),
                ),
                pendingPermissions = pendingPerms,
                planEntries = baseEntries,
            )
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(640.dp, 480.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = sess,
                        transcript = transcript,
                        isSessionRunning = sessionState == SessionState.RUNNING || sessionState == SessionState.WAITING_PERMISSION,
                        onLoadOlder = {},
                    )
                }
            }
        }
        waitForIdle()

        // 1. SessionState.WAITING_PERMISSION: expanded by default with clear permission pause cue
        onNodeWithText("2/3 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("等待权限确认", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("编译签名并安装 Lework 到 iPhone", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("execution_step_status_waiting_2", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("等待权限确认")
        onNodeWithTag("execution_step_status_stopped_2", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("execution_step_status_running_2", useUnmergedTree = true).assertDoesNotExist()

        // 2. SessionState.RUNNING with pendingPermissions in transcript also resolves to WAITING_PERMISSION
        sessionState = SessionState.RUNNING
        pendingPerms = listOf(
            AgentEvent.PermissionRequested(
                seq = 6,
                sessionId = sid,
                ts = 1_730_000_011_000L,
                requestId = "perm_1",
                title = "Allow xcodebuild?",
                options = listOf(PermissionOption("allow", "允许", PermissionOptionKind.ALLOW_ONCE)),
            ),
        )
        waitForIdle()

        onNodeWithText("等待权限确认", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("execution_step_status_waiting_2", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun planApprovalPendingStaysExpandedAndTriggersActionButtons() = runComposeUiTest {
        val sid = "host:claude:conv-plan-approval"
        val idleSession = SessionMeta(
            id = sid,
            engineId = "claude",
            cwd = "/tmp/lework",
            title = "Plan Mode Demo",
            state = SessionState.IDLE,
            createdAt = 1_730_000_000_000L,
            updatedAt = 1_730_000_010_000L,
            lastSeq = 3,
        )
        val transcript = SessionTranscript(
            earliestSeq = 1,
            lastSeq = 3,
            items = listOf(
                ChatTimelineItem.Assistant(id = "a_plan", text = "方案已整理完毕，请确认。", ts = 1_730_000_010_000L, seq = 3),
            ),
            planEntries = listOf(
                PlanEntry("重构执行步骤状态机", "pending"),
                PlanEntry("补齐 KMP 与 Hub 回归测试", "pending"),
            ),
        )
        var openedPlan = false
        var approvedPlan = false

        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(680.dp, 480.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = idleSession,
                        transcript = transcript,
                        isSessionRunning = false,
                        onLoadOlder = {},
                        onOpenPlanArtifact = { openedPlan = true },
                        onApprovePlan = { approvedPlan = true },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithText("0/2 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("待确认方案", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("重构执行步骤状态机", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("补齐 KMP 与 Hub 回归测试", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithText("查看方案", useUnmergedTree = true).assertIsDisplayed().performClick()
        onNodeWithTag("plan_card_approve_btn", useUnmergedTree = true).assertIsDisplayed().performClick()
        waitForIdle()
        assertTrue(openedPlan)
        assertTrue(approvedPlan)
    }

    @Test
    fun failedErrorAndIncompleteStatesAutoCollapseAndRenderNonActiveIndicators() = runComposeUiTest {
        val sid = "host:codex:conv-failed"
        var sessionState by mutableStateOf(SessionState.IDLE)
        var entries by mutableStateOf(
            listOf(
                PlanEntry("拉取代码", "completed"),
                PlanEntry("运行单测", "failed"),
                PlanEntry("发布构建", "in_progress"),
            ),
        )

        setContent {
            val sess = SessionMeta(
                id = sid,
                engineId = "codex",
                cwd = "/tmp/lework",
                title = "Error & Incomplete Demo",
                state = sessionState,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                lastSeq = 3,
            )
            val transcript = SessionTranscript(
                earliestSeq = 1,
                lastSeq = 3,
                items = listOf(
                    ChatTimelineItem.Assistant(id = "a1", text = "执行过程中遇到错误。", ts = 1_730_000_010_000L, seq = 3),
                ),
                planEntries = entries,
            )
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(640.dp, 480.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = sess,
                        transcript = transcript,
                        isSessionRunning = false,
                        onLoadOlder = {},
                    )
                }
            }
        }
        waitForIdle()

        // 1. Explicit failed step: collapsed by default with red failure cue
        onNodeWithText("1/3 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("含 1 项失败", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("发布构建", useUnmergedTree = true).assertDoesNotExist()

        onNodeWithTag("execution_steps_header", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("execution_step_status_failed_1", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("execution_step_status_stopped_2", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("execution_step_status_running_2", useUnmergedTree = true).assertDoesNotExist()

        // 2. SessionState.ERROR without explicit failed entry: auto-collapses with error cue
        sessionState = SessionState.ERROR
        entries = listOf(
            PlanEntry("拉取代码", "completed"),
            PlanEntry("发布构建", "in_progress"),
        )
        waitForIdle()
        onNodeWithText("1/2 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("执行异常中断", substring = true, useUnmergedTree = true).assertIsDisplayed()

        // 3. SessionState.CLOSED with completed + pending only: auto-collapses as incomplete
        sessionState = SessionState.CLOSED
        entries = listOf(
            PlanEntry("拉取代码", "completed"),
            PlanEntry("发布构建", "pending"),
        )
        waitForIdle()
        onNodeWithText("1/2 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("未完成", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("发布构建", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("execution_steps_header", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("execution_step_status_pending_1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun manualToggleSurvivesProgressUpdatesWhileLifecycleTransitionResetsSensibly() = runComposeUiTest {
        val sid = "host:agy:conv-transitions"
        var sessionState by mutableStateOf(SessionState.RUNNING)
        var outputTokens by mutableStateOf(120)
        var entries by mutableStateOf(
            listOf(
                PlanEntry("Step Alpha", "completed"),
                PlanEntry("Step Beta", "in_progress"),
                PlanEntry("Step Gamma", "pending"),
            ),
        )

        setContent {
            val sess = SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/tmp/lework",
                title = "Transition Demo",
                state = sessionState,
                liveStatusText = "Running tests...",
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                turnStartedAt = 1_730_000_000_000L,
                lastSeq = 4,
            )
            val transcript = SessionTranscript(
                earliestSeq = 1,
                lastSeq = 4,
                items = listOf(
                    ChatTimelineItem.Assistant(id = "a1", text = "正在执行中。", ts = 1_730_000_002_000L, seq = 4),
                ),
                planEntries = entries,
                outputTokens = outputTokens,
            )
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(640.dp, 480.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = sess,
                        transcript = transcript,
                        isSessionRunning = sessionState == SessionState.RUNNING || sessionState == SessionState.WAITING_PERMISSION,
                        onLoadOlder = {},
                    )
                }
            }
        }
        waitForIdle()

        // Starts expanded while RUNNING
        onNodeWithText("Step Beta", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("execution_step_status_running_1", useUnmergedTree = true).assertIsDisplayed()

        // User collapses manually while RUNNING
        onNodeWithTag("execution_steps_header", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Step Beta", useUnmergedTree = true).assertDoesNotExist()

        // Token & step progress updates within RUNNING must NOT clobber user's manual collapse
        outputTokens = 480
        entries = listOf(
            PlanEntry("Step Alpha", "completed"),
            PlanEntry("Step Beta", "completed"),
            PlanEntry("Step Gamma", "in_progress"),
        )
        waitForIdle()
        onNodeWithText("2/3 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Step Gamma", useUnmergedTree = true).assertDoesNotExist()

        // Genuine lifecycle transition RUNNING -> WAITING_PERMISSION resets override and expands
        sessionState = SessionState.WAITING_PERMISSION
        waitForIdle()
        onNodeWithText("等待权限确认", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Step Gamma", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("execution_step_status_waiting_2", useUnmergedTree = true).assertIsDisplayed()

        // Denying permission transitions WAITING_PERMISSION -> IDLE (STOPPED) and auto-collapses
        sessionState = SessionState.IDLE
        waitForIdle()
        onNodeWithText("已中断", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Step Gamma", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun readOnlyPlanArtifactDoesNotForceExpandCompletedStoppedOrIncompleteCards() = runComposeUiTest {
        val sid = "host:agy:conv-historical-artifact"
        var entries by mutableStateOf(
            listOf(
                PlanEntry("步骤一：准备环境", "completed"),
                PlanEntry("步骤二：完成验证", "completed"),
            ),
        )
        var artifactOpenedCount = 0

        setContent {
            val sess = SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/tmp/lework",
                title = "Historical Plan Artifact Demo",
                state = SessionState.IDLE,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_020_000L,
                lastSeq = 5,
            )
            val transcript = SessionTranscript(
                earliestSeq = 1,
                lastSeq = 5,
                items = listOf(
                    ChatTimelineItem.Assistant(id = "a_hist", text = "执行完毕。", ts = 1_730_000_020_000L, seq = 5),
                ),
                planEntries = entries,
            )
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(680.dp, 480.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = sess,
                        transcript = transcript,
                        isSessionRunning = false,
                        onLoadOlder = {},
                        onOpenPlanArtifact = { artifactOpenedCount++ },
                        onApprovePlan = null,
                    )
                }
            }
        }
        waitForIdle()

        // 1. COMPLETED + read-only `onOpenPlanArtifact` -> auto-collapsed, `查看方案` still accessible
        onNodeWithText("执行步骤已完成", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("步骤一：准备环境", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("plan_card_approve_btn", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("查看方案", useUnmergedTree = true).assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, artifactOpenedCount)

        // 2. INCOMPLETE (completed + pending) + read-only `onOpenPlanArtifact` -> auto-collapsed as `未完成`
        entries = listOf(
            PlanEntry("步骤一：准备环境", "completed"),
            PlanEntry("步骤二：待执行", "pending"),
        )
        waitForIdle()
        onNodeWithText("未完成", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("步骤二：待执行", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("查看方案", useUnmergedTree = true).assertIsDisplayed()

        // 3. STOPPED (completed + in_progress) + read-only `onOpenPlanArtifact` -> auto-collapsed as `已中断`
        entries = listOf(
            PlanEntry("步骤一：准备环境", "completed"),
            PlanEntry("步骤二：中断项", "in_progress"),
        )
        waitForIdle()
        onNodeWithText("已中断", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("步骤二：中断项", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("查看方案", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun clearingPlanEntriesWhileRunningFallsBackSmoothlyToLiveSpinnerRow() = runComposeUiTest {
        val sid = "host:agy:conv-turn2-fallback"
        var entries by mutableStateOf(
            listOf(
                PlanEntry("Turn 1 Step 1", "completed"),
                PlanEntry("Turn 1 Step 2", "in_progress"),
            ),
        )
        var sessionState by mutableStateOf(SessionState.IDLE)

        setContent {
            val sess = SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/tmp/lework",
                title = "Turn 2 Spinner Fallback",
                state = sessionState,
                liveStatusText = "Thinking about Turn 2…",
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                turnStartedAt = 1_730_000_009_000L,
                lastSeq = 6,
            )
            val transcript = SessionTranscript(
                earliestSeq = 1,
                lastSeq = 6,
                items = listOf(
                    ChatTimelineItem.User(id = "u2", text = "排查新问题", ts = 1_730_000_009_000L, seq = 6),
                ),
                planEntries = entries,
            )
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(640.dp, 460.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = sess,
                        transcript = transcript,
                        isSessionRunning = sessionState == SessionState.RUNNING,
                        onLoadOlder = {},
                    )
                }
            }
        }
        waitForIdle()

        // Initially IDLE with stale Turn 1 plan -> collapsed step card, no live spinner row
        onNodeWithTag("execution_steps_card", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("live_spinner_row", useUnmergedTree = true).assertDoesNotExist()

        // Turn 2 starts RUNNING and clears stale planEntries -> smoothly falls back to ClaudeLiveSpinnerRow
        sessionState = SessionState.RUNNING
        entries = emptyList()
        waitForIdle()
        onNodeWithTag("execution_steps_card", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("live_spinner_row", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Thinking about Turn 2…", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun sendPromptPreSendSnapshotGuardPreservesConcurrentFreshTurn2PlanWhileClearingStalePlan() = runBlocking {
        JvmPlatformTestHooks.reset()
        try {
            val sid = "host:agy:race-guard"
            val staleEntries = listOf(
                PlanEntry("Turn 1 Step 1", "completed"),
                PlanEntry("Turn 1 Interrupted", "in_progress"),
            )
            val freshTurn2Entries = listOf(
                PlanEntry("Turn 2 Brand New Step", "in_progress"),
            )
            val baseTransport = FakeHubTransport()
            baseTransport.sessions.add(
                SessionMeta(
                    id = sid,
                    engineId = "agy",
                    cwd = "/workspace/lework",
                    title = "Race Guard Session",
                    state = SessionState.IDLE,
                    createdAt = 1_730_000_000_000L,
                    updatedAt = 1_730_000_010_000L,
                    lastSeq = 2,
                ),
            )
            baseTransport.sessionEventsMap[sid] = mutableListOf(
                AgentEvent.UserMessage(0, sid, 1_730_000_001_000L, "u1", "Turn 1"),
                AgentEvent.PlanUpdated(1, sid, 1_730_000_002_000L, staleEntries),
                AgentEvent.TurnDone(2, sid, 1_730_000_005_000L, "t1", "cancelled"),
            )

            var emitFreshPlanDuringSend = false
            val transport = object : HubTransport by baseTransport {
                override suspend fun sendPrompt(
                    sessionId: String,
                    text: String,
                    images: List<PromptImageAttachment>?,
                    files: List<PromptFileAttachment>?,
                    providerId: String?,
                    modelId: String?,
                    reasoningEffort: ReasoningEffort?,
                    mode: SessionMode?,
                ): String {
                    val turnId = baseTransport.sendPrompt(
                        sessionId = sessionId,
                        text = text,
                        images = images,
                        files = files,
                        providerId = providerId,
                        modelId = modelId,
                        reasoningEffort = reasoningEffort,
                        mode = mode,
                    )
                    if (emitFreshPlanDuringSend) {
                        // Simulate Hub pushing Turn 2's fresh PlanUpdated BEFORE `session.prompt` RPC returns!
                        baseTransport.emitEvent(
                            AgentEvent.PlanUpdated(
                                seq = 10,
                                sessionId = sessionId,
                                ts = 1_730_000_050_000L,
                                entries = freshTurn2Entries,
                            ),
                        )
                        delay(40L)
                    }
                    return turnId
                }
            }

            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val store = ConsoleStore(scope, transport)
            try {
                store.bootstrapOrConnect()
                var tries = 0
                while (store.state.value.connectionStatus != dev.lework.store.ConnectionStatus.CONNECTED && tries++ < 80) {
                    delay(20L)
                }
                store.selectSession(sid)
                tries = 0
                while (store.state.value.activeTranscript.planEntries.size != 2 && tries++ < 80) {
                    delay(20L)
                }
                assertEquals(staleEntries, store.state.value.activeTranscript.planEntries)
                assertTrue(store.state.value.activeTranscript.planTurnEnded)

                // Case 1: Fresh Turn 2 plan arrives concurrently while dispatchPromptRpc is in flight -> MUST NOT be clobbered!
                emitFreshPlanDuringSend = true
                store.sendPrompt("开始 Turn 2 并立刻更新步骤")
                tries = 0
                while (store.state.value.activeTranscript.planEntries != freshTurn2Entries && tries++ < 80) {
                    delay(20L)
                }
                delay(60L)
                assertEquals(freshTurn2Entries, store.state.value.activeTranscript.planEntries)
                assertEquals(1_730_000_050_000L, store.state.value.activeTranscript.planUpdatedAt)

                // Case 2: Turn 2 ends (IDLE) and Turn 3 is sent WITHOUT concurrent fresh plan -> stale Turn 2 plan clears immediately!
                baseTransport.emitEvent(AgentEvent.TurnDone(11, sid, 1_730_000_060_000L, "t2", "cancelled"))
                baseTransport.emitSessionUpdate(
                    baseTransport.sessions.first { it.id == sid }.copy(state = SessionState.IDLE, lastSeq = 11),
                )
                tries = 0
                while (!store.state.value.activeTranscript.planTurnEnded && tries++ < 80) {
                    delay(20L)
                }
                emitFreshPlanDuringSend = false
                store.sendPrompt("Turn 3 无步骤任务")
                tries = 0
                while (store.state.value.activeTranscript.planEntries.isNotEmpty() && tries++ < 80) {
                    delay(20L)
                }
                assertTrue(store.state.value.activeTranscript.planEntries.isEmpty())
            } finally {
                scope.cancel()
            }
        } finally {
            JvmPlatformTestHooks.reset()
        }
    }

    @Test
    fun planModeApprovalEndToEndTransitionsToRunningAndAutoCollapsesAfterExecutionWithHistoricalArtifact() = runComposeUiTest {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.simulateDesktopLayout = false
        try {
            val sid = "host:agy:plan-e2e"
            val planArt = ArtifactInfo(
                id = "agy_file:implementation_plan.md",
                sessionId = sid,
                kind = ArtifactKind.PLAN,
                title = "步骤卡片修复方案",
                summary = "显式建模生命周期并清理跨轮次过期步骤",
                fileName = "implementation_plan.md",
                mimeType = "text/markdown",
                sizeBytes = 512L,
                updatedAt = 1_730_000_005_000L,
                source = ArtifactSource.AGY_BRAIN,
                actionable = true,
            )
            val transport = FakeHubTransport()
            val initialMeta = SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/lework",
                title = "Plan Mode Lifecycle E2E",
                state = SessionState.IDLE,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_005_000L,
                lastSeq = 2,
                mode = SessionMode.PLAN,
                artifactCount = 1,
            )
            transport.sessions.add(initialMeta)
            transport.artifactsBySession[sid] = mutableListOf(planArt)
            transport.artifactContents[sid to planArt.id] = ArtifactContentResult(
                artifact = planArt,
                content = "# 步骤卡片修复方案\n\n- 步骤一\n- 步骤二",
                isBase64 = false,
                truncated = false,
            )
            transport.sessionEventsMap[sid] = mutableListOf(
                AgentEvent.UserMessage(0, sid, 1_730_000_001_000L, "u0", "帮我设计修复方案"),
                AgentEvent.PlanUpdated(
                    seq = 1,
                    sessionId = sid,
                    ts = 1_730_000_002_000L,
                    entries = listOf(
                        PlanEntry("设计状态机枚举", "pending"),
                        PlanEntry("补齐端到端单测", "pending"),
                    ),
                ),
                AgentEvent.AssistantDelta(2, sid, 1_730_000_005_000L, "a0", "方案已生成，请确认。"),
            )

            lateinit var store: ConsoleStore
            setContent {
                val scope: CoroutineScope = rememberCoroutineScope()
                val created = remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 430.dp, height = 840.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitForIdle()
            runOnIdle { store.selectSession(sid) }
            waitUntil(timeoutMillis = 4_000L) {
                onAllNodesWithTag("plan_card_approve_btn", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            // 1. In PLAN mode with pending steps + plan artifact: expanded as PLAN_APPROVAL_PENDING with `确认执行`
            onNodeWithText("待确认方案", substring = true, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("设计状态机枚举", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("plan_card_approve_btn", useUnmergedTree = true).performClick()

            waitUntil(timeoutMillis = 4_000L) {
                transport.sentPrompts.any { it.first == sid && it.second.contains("确认执行方案") }
            }
            assertEquals(SessionMode.AUTO, store.state.value.selectedMode)

            // 2. Execution starts RUNNING with fresh Turn 2 plan updates
            runOnIdle {
                val uExec = AgentEvent.UserMessage(3, sid, 1_730_000_010_000L, "u_exec", "已确认执行方案《步骤卡片修复方案》，请开始按方案实施。")
                val pRunning = AgentEvent.PlanUpdated(
                    seq = 4,
                    sessionId = sid,
                    ts = 1_730_000_011_000L,
                    entries = listOf(
                        PlanEntry("设计状态机枚举", "completed"),
                        PlanEntry("补齐端到端单测", "in_progress"),
                    ),
                )
                transport.sessionEventsMap[sid]?.addAll(listOf(uExec, pRunning))
                transport.emitSessionUpdate(
                    initialMeta.copy(
                        state = SessionState.RUNNING,
                        mode = SessionMode.AUTO,
                        liveStatusText = "Implementing fix...",
                        turnStartedAt = 1_730_000_010_000L,
                        lastSeq = 4,
                    ),
                )
                transport.emitEvent(uExec)
                transport.emitEvent(pRunning)
            }
            waitUntil(timeoutMillis = 4_000L) {
                onAllNodesWithTag("execution_step_status_running_1", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            onNodeWithText("1/2 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("execution_step_status_running_1", useUnmergedTree = true).assertIsDisplayed()

            // 3. Execution completes cleanly (`AUTO` mode + historical `implementation_plan.md` STILL in artifacts!):
            //    Must NOT show `确认执行` and MUST auto-collapse as `执行步骤已完成` while keeping `查看方案`!
            runOnIdle {
                val pDone = AgentEvent.PlanUpdated(
                    seq = 5,
                    sessionId = sid,
                    ts = 1_730_000_020_000L,
                    entries = listOf(
                        PlanEntry("设计状态机枚举", "completed"),
                        PlanEntry("补齐端到端单测", "completed"),
                    ),
                )
                val tDone = AgentEvent.TurnDone(6, sid, 1_730_000_021_000L, "t_exec", "end_turn")
                transport.sessionEventsMap[sid]?.addAll(listOf(pDone, tDone))
                transport.emitEvent(pDone)
                transport.emitEvent(tDone)
                transport.emitSessionUpdate(
                    initialMeta.copy(
                        state = SessionState.IDLE,
                        mode = SessionMode.AUTO,
                        liveStatusText = null,
                        turnStartedAt = null,
                        lastSeq = 6,
                    ),
                )
            }
            waitUntil(timeoutMillis = 4_000L) {
                store.state.value.activeSession?.state == SessionState.IDLE &&
                    store.state.value.activeTranscript.planEntries.all { it.status == "completed" } &&
                    store.state.value.activeTranscript.planEntries.size == 2
            }
            waitForIdle()
            onNodeWithText("2/2 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("执行步骤已完成", substring = true, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("查看方案", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("plan_card_approve_btn", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("补齐端到端单测", useUnmergedTree = true).assertDoesNotExist()
        } finally {
            JvmPlatformTestHooks.reset()
        }
    }
}
