package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.PlanEntry
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.protocol.SubagentSummary
import dev.lework.protocol.ToolKind
import dev.lework.protocol.ToolStatus
import dev.lework.store.ChatTimelineItem
import dev.lework.store.SessionTranscript
import dev.lework.store.reduceTranscript
import dev.lework.store.reduceTranscriptBatch
import dev.lework.store.withFallbackLatestPlan
import dev.lework.ui.ConversationStreamView
import dev.lework.ui.EverforestColorScheme
import dev.lework.ui.SubagentStatusStrip
import dev.lework.ui.cleanLiveStatusForSteps
import dev.lework.ui.isSubagentDispatchTool
import dev.lework.ui.matchSubagentForToolCall
import dev.lework.ui.parseSubagentToolMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ExecutionStepsAndInlineSubagentTest {

    @Test
    fun transcriptReducerTracksPlanStepsAndAppliesTurnHygiene() {
        val t0 = SessionTranscript()
        val planRunning = AgentEvent.PlanUpdated(
            seq = 1,
            sessionId = "s1",
            ts = 1_000L,
            entries = listOf(
                PlanEntry("Sync local main", "completed"),
                PlanEntry("Update Mac Hub", "in_progress"),
                PlanEntry("Deploy Devbox", "pending"),
            ),
        )
        val t1 = reduceTranscript(t0, planRunning)
        assertEquals(3, t1.planEntries.size)
        assertEquals(1_000L, t1.planUpdatedAt)

        // Mid-turn follow-up keeps in-progress steps visible
        val t2 = reduceTranscript(
            t1,
            AgentEvent.UserMessage(seq = 2, sessionId = "s1", ts = 2_000L, messageId = "u2", text = "顺便看下日志"),
        )
        assertEquals(3, t2.planEntries.size)

        // Once all steps become terminal, the next user turn clears stale steps
        val t3 = reduceTranscript(
            t2,
            AgentEvent.PlanUpdated(
                seq = 3,
                sessionId = "s1",
                ts = 3_000L,
                entries = listOf(
                    PlanEntry("Sync local main", "completed"),
                    PlanEntry("Update Mac Hub", "completed"),
                    PlanEntry("Deploy Devbox", "skipped"),
                ),
            ),
        )
        assertEquals(3, t3.planEntries.size)

        val t4 = reduceTranscriptBatch(
            t3,
            listOf(
                AgentEvent.UserMessage(seq = 4, sessionId = "s1", ts = 4_000L, messageId = "u3", text = "开始新任务"),
            ),
        )
        assertTrue(t4.planEntries.isEmpty())

        // Fallback hydration from `StreamSubscribeResult.latestPlan`
        val hydrated = t4.withFallbackLatestPlan(
            listOf(PlanEntry("Hydrated step", "in_progress")),
            fallbackTs = 5_000L,
        )
        assertEquals(1, hydrated.planEntries.size)
        assertEquals(5_000L, hydrated.planUpdatedAt)
    }

    @Test
    fun subagentToolParsingAndMatchingSupportsInvokeSwarmAndWorkflow() {
        val invokeTool = ChatTimelineItem.Tool(
            id = "tc_sub_1",
            title = "Task",
            kind = ToolKind.OTHER,
            status = ToolStatus.IN_PROGRESS,
            input = """{"Role":"Codebase Researcher","TypeName":"research","Prompt":"Inspect parser"}""",
            output = """{"conversationId":"conv-child-99"}""",
            ts = 1_700_000_000_000L,
            seq = 5,
        )
        assertTrue(isSubagentDispatchTool(invokeTool))
        val meta = parseSubagentToolMeta(invokeTool)
        assertEquals("SubAgent", meta.kindLabel)
        assertEquals("Codebase Researcher", meta.title)
        assertEquals("research", meta.roleBadge)
        assertEquals("conv-child-99", meta.referencedId)

        val swarmAdd = ChatTimelineItem.Tool(
            id = "tc_sw_1",
            title = "Swarm",
            kind = ToolKind.OTHER,
            status = ToolStatus.COMPLETED,
            input = """{"action":"add","name":"auditor","n":2,"brief":"Check KMP files"}""",
            ts = 1_700_000_005_000L,
            seq = 6,
        )
        assertTrue(isSubagentDispatchTool(swarmAdd))
        assertEquals("Swarm", parseSubagentToolMeta(swarmAdd).kindLabel)
        assertEquals("auditor ×2", parseSubagentToolMeta(swarmAdd).title)

        val swarmStatus = swarmAdd.copy(id = "tc_sw_2", input = """{"action":"status"}""")
        assertFalse(isSubagentDispatchTool(swarmStatus))

        val workflow = ChatTimelineItem.Tool(
            id = "tc_wf_1",
            title = "Workflow",
            kind = ToolKind.OTHER,
            status = ToolStatus.COMPLETED,
            input = """{"Description":"Fan out lint checks"}""",
            ts = 1_700_000_010_000L,
            seq = 7,
        )
        assertTrue(isSubagentDispatchTool(workflow))
        assertEquals("Workflow", parseSubagentToolMeta(workflow).kindLabel)

        val sub = SubagentSummary(
            id = "host:agy:conv-child-99",
            title = "Research Session",
            role = "research",
            state = SessionState.RUNNING,
            statusText = "查阅 ConversationTimeline.kt",
            createdAt = 1_700_000_000_500L,
            updatedAt = 1_700_000_012_000L,
            stepsDone = 2,
            stepsTotal = 4,
        )
        assertEquals(sub, matchSubagentForToolCall(invokeTool, meta, listOf(sub)))

        // Live status cleanup strips duplicate braille spinner, step counter, and elapsed suffix
        assertEquals(
            "Running command...",
            cleanLiveStatusForSteps("⠇ 0/3 steps · Running command... 17s"),
        )
    }

    @Test
    fun executionStepsCardAnchorsAtBottomAndSupportsLiveRunningAndAutoCollapse() = runComposeUiTest {
        val sid = "host:agy:conv-live"
        val runningSession = SessionMeta(
            id = sid,
            engineId = "agy",
            cwd = "/tmp/lework",
            title = "Live Steps Demo",
            state = SessionState.RUNNING,
            liveStatusText = "⠇ 1/3 steps · Running command... 17s",
            createdAt = 1_730_000_000_000L,
            updatedAt = 1_730_000_010_000L,
            turnStartedAt = 1_730_000_000_000L,
            lastSeq = 30,
        )
        // Generate enough messages to verify the step card is at the visual bottom (not buried at the top!).
        val manyItems = (1..24).map { i ->
            ChatTimelineItem.Assistant(
                id = "a_$i",
                text = "历史对话消息 #$i：验证执行步骤树在长会话底部始终可见。",
                ts = 1_730_000_000_000L + i * 1_000L,
                seq = i,
            )
        }
        val runningTranscript = SessionTranscript(
            earliestSeq = 1,
            lastSeq = 30,
            items = manyItems,
            planEntries = listOf(
                PlanEntry("Sync local main and clean merged branches", "completed"),
                PlanEntry("Update and verify local Mac Hub service", "in_progress"),
                PlanEntry("Deploy and verify DevboxCN Hub Master", "pending"),
            ),
        )

        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(680.dp, 520.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = runningSession,
                        transcript = runningTranscript,
                        isSessionRunning = true,
                        onLoadOlder = {},
                    )
                }
            }
        }
        waitForIdle()

        // Step card is immediately visible at the bottom of a 24-message session
        onNodeWithTag("execution_steps_card", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("1/3 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Running command...", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Sync local main and clean merged branches", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Update and verify local Mac Hub service", useUnmergedTree = true).assertIsDisplayed()

        // Clicking header collapses the step tree cleanly
        onNodeWithTag("execution_steps_header", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Deploy and verify DevboxCN Hub Master", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun finishedStepsAutoCollapseAndExpandOnTap() = runComposeUiTest {
        val sid = "host:agy:conv-done"
        val idleSession = SessionMeta(
            id = sid,
            engineId = "agy",
            cwd = "/tmp/lework",
            title = "Done Steps Demo",
            state = SessionState.IDLE,
            createdAt = 1_730_000_000_000L,
            updatedAt = 1_730_000_020_000L,
            lastSeq = 5,
        )
        val doneTranscript = SessionTranscript(
            earliestSeq = 1,
            lastSeq = 5,
            items = listOf(
                ChatTimelineItem.Assistant(id = "a_done", text = "全部执行完成。", ts = 1_730_000_020_000L, seq = 5),
            ),
            planEntries = listOf(
                PlanEntry("Step One Complete", "completed"),
                PlanEntry("Step Two Skipped", "skipped"),
            ),
        )

        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(640.dp, 460.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = idleSession,
                        transcript = doneTranscript,
                        isSessionRunning = false,
                        onLoadOlder = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag("execution_steps_card", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("2/2 步", substring = true, useUnmergedTree = true).assertIsDisplayed()
        // Collapsed by default once all steps finished and session is idle
        onNodeWithText("Step One Complete", useUnmergedTree = true).assertDoesNotExist()

        // Tapping header unfolds full completed checklist
        onNodeWithTag("execution_steps_header", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Step One Complete", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Step Two Skipped", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun inlineSubagentDispatchCardOpensInspectorAndStatusStripShowsStepProgress() = runComposeUiTest {
        val sid = "host:agy:conv-parent"
        val subRunning = SubagentSummary(
            id = "host:agy:sub-db-1",
            title = "Database Debugger",
            role = "research",
            state = SessionState.RUNNING,
            statusText = "查阅 ProtocolModels.kt",
            createdAt = 1_730_000_000_000L,
            updatedAt = 1_730_000_014_000L,
            depth = 2,
            stepsDone = 2,
            stepsTotal = 4,
        )
        val parentSession = SessionMeta(
            id = sid,
            engineId = "agy",
            cwd = "/tmp/lework",
            title = "Parent Session",
            state = SessionState.RUNNING,
            createdAt = 1_730_000_000_000L,
            updatedAt = 1_730_000_015_000L,
            lastSeq = 3,
            subagents = listOf(subRunning),
        )
        val subTool = ChatTimelineItem.Tool(
            id = "tc_sub_inline",
            title = "Task",
            kind = ToolKind.OTHER,
            status = ToolStatus.IN_PROGRESS,
            input = """{"Role":"Database Debugger","TypeName":"research","Prompt":"Inspect schema"}""",
            output = """{"conversationId":"sub-db-1"}""",
            ts = 1_730_000_000_200L,
            seq = 2,
        )
        val transcript = SessionTranscript(
            earliestSeq = 1,
            lastSeq = 3,
            items = listOf(
                ChatTimelineItem.User(id = "u1", text = "排查一下协议字段", ts = 1_730_000_000_000L, seq = 1),
                subTool,
            ),
        )

        var openedSubagent: SubagentSummary? = null
        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Column(modifier = Modifier.size(720.dp, 560.dp)) {
                    SubagentStatusStrip(
                        sessionId = sid,
                        subagents = listOf(subRunning),
                        onSelectSubagent = { openedSubagent = it },
                    )
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = parentSession,
                        transcript = transcript,
                        isSessionRunning = true,
                        onLoadOlder = {},
                        subagents = listOf(subRunning),
                        onOpenSubagent = { openedSubagent = it },
                    )
                }
            }
        }
        waitForIdle()

        // Inline subagent card is visible directly in the timeline with step pill and live activity
        onNodeWithTag("inline_subagent_card_tc_sub_inline", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("SubAgent", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("查阅 ProtocolModels.kt", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("inline_subagent_open_tc_sub_inline", useUnmergedTree = true).assertIsDisplayed()

        // Clicking inline card opens the matched SubagentSummary directly
        onNodeWithTag("inline_subagent_card_tc_sub_inline", useUnmergedTree = true).performClick()
        waitForIdle()
        assertNotNull(openedSubagent)
        assertEquals("host:agy:sub-db-1", openedSubagent?.id)
    }
}
