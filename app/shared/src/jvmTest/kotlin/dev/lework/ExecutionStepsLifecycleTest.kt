package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.PermissionOption
import dev.lework.protocol.PermissionOptionKind
import dev.lework.protocol.PlanEntry
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ChatTimelineItem
import dev.lework.store.SessionTranscript
import dev.lework.store.clearStalePlanBeforeNewPrompt
import dev.lework.store.reduceTranscript
import dev.lework.store.reduceTranscriptBatch
import dev.lework.store.syncPlanLifecycleWithSessionState
import dev.lework.store.withFallbackLatestPlan
import dev.lework.ui.ConversationStreamView
import dev.lework.ui.EverforestColorScheme
import dev.lework.ui.ExecutionStepsLifecycle
import dev.lework.ui.defaultExpandedForLifecycle
import dev.lework.ui.resolveExecutionStepsLifecycle
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
            resolveExecutionStepsLifecycle(mixedRunning, SessionState.RUNNING, isSessionRunning = true, isWaitingPermission = false, hasActionButtons = false),
        )
        assertTrue(defaultExpandedForLifecycle(ExecutionStepsLifecycle.RUNNING, hasApprovePlanAction = false))

        // Waiting permission overrides running / in_progress / failed
        assertEquals(
            ExecutionStepsLifecycle.WAITING_PERMISSION,
            resolveExecutionStepsLifecycle(mixedRunning, SessionState.WAITING_PERMISSION, isSessionRunning = false, isWaitingPermission = false, hasActionButtons = false),
        )
        assertEquals(
            ExecutionStepsLifecycle.WAITING_PERMISSION,
            resolveExecutionStepsLifecycle(withFailed, SessionState.RUNNING, isSessionRunning = true, isWaitingPermission = true, hasActionButtons = false),
        )
        assertTrue(defaultExpandedForLifecycle(ExecutionStepsLifecycle.WAITING_PERMISSION, hasApprovePlanAction = false))

        // Inactive with leftover in_progress -> STOPPED (collapsed by default even if old plan artifact exists)
        assertEquals(
            ExecutionStepsLifecycle.STOPPED,
            resolveExecutionStepsLifecycle(mixedRunning, SessionState.IDLE, isSessionRunning = false, isWaitingPermission = false, hasActionButtons = false),
        )
        assertEquals(
            ExecutionStepsLifecycle.STOPPED,
            resolveExecutionStepsLifecycle(mixedRunning, SessionState.CLOSED, isSessionRunning = false, isWaitingPermission = false, hasActionButtons = true),
        )
        assertFalse(defaultExpandedForLifecycle(ExecutionStepsLifecycle.STOPPED, hasApprovePlanAction = true))

        // Inactive with pending only & no actions -> INCOMPLETE (collapsed by default)
        assertEquals(
            ExecutionStepsLifecycle.INCOMPLETE,
            resolveExecutionStepsLifecycle(partialPending, SessionState.IDLE, isSessionRunning = false, isWaitingPermission = false, hasActionButtons = false),
        )
        assertFalse(defaultExpandedForLifecycle(ExecutionStepsLifecycle.INCOMPLETE, hasApprovePlanAction = false))

        // Inactive with pending steps & action buttons -> PLAN_APPROVAL_PENDING (expanded by default)
        assertEquals(
            ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING,
            resolveExecutionStepsLifecycle(pendingOnly, SessionState.IDLE, isSessionRunning = false, isWaitingPermission = false, hasActionButtons = true),
        )
        assertTrue(defaultExpandedForLifecycle(ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING, hasApprovePlanAction = true))

        // Completed: collapsed normally, expanded when approve-plan action is presented
        assertEquals(
            ExecutionStepsLifecycle.COMPLETED,
            resolveExecutionStepsLifecycle(allDone, SessionState.IDLE, isSessionRunning = false, isWaitingPermission = false, hasActionButtons = false),
        )
        assertEquals(
            ExecutionStepsLifecycle.COMPLETED,
            resolveExecutionStepsLifecycle(allDone, SessionState.ERROR, isSessionRunning = false, isWaitingPermission = false, hasActionButtons = false),
        )
        assertFalse(defaultExpandedForLifecycle(ExecutionStepsLifecycle.COMPLETED, hasApprovePlanAction = false))
        assertTrue(defaultExpandedForLifecycle(ExecutionStepsLifecycle.COMPLETED, hasApprovePlanAction = true))

        // Failed: explicit failed entry or SessionState.ERROR while unfinished -> FAILED (collapsed by default)
        assertEquals(
            ExecutionStepsLifecycle.FAILED,
            resolveExecutionStepsLifecycle(withFailed, SessionState.IDLE, isSessionRunning = false, isWaitingPermission = false, hasActionButtons = false),
        )
        assertEquals(
            ExecutionStepsLifecycle.FAILED,
            resolveExecutionStepsLifecycle(mixedRunning, SessionState.ERROR, isSessionRunning = false, isWaitingPermission = false, hasActionButtons = false),
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
}
