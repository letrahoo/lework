package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.protocol.SubagentSummary
import dev.lework.protocol.ToolKind
import dev.lework.protocol.ToolStatus
import dev.lework.store.ChatTimelineItem
import dev.lework.store.SessionTranscript
import dev.lework.ui.ConversationStreamView
import dev.lework.ui.SubagentStatusStrip
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TimelineScrollStabilityTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.simulateDesktopLayout = true
    }

    @Test
    fun `live spinner row keeps exact fixed height and never bounces message above it across long and empty liveStatusText`() =
        runComposeUiTest {
            val sid = "host:agy:spinner-stability"
            val baseSession = SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/demo",
                title = "Spinner Stability",
                state = SessionState.RUNNING,
                liveStatusText = "⡿ Running command...",
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                turnStartedAt = 1_730_000_005_000L,
                lastSeq = 2,
            )
            var activeSession by mutableStateOf(baseSession)
            val transcript = SessionTranscript(
                earliestSeq = 0,
                lastSeq = 2,
                items = listOf(
                    ChatTimelineItem.User(
                        id = "u_1",
                        text = "检查对话流是否垂直稳定",
                        ts = 1_730_000_001_000L,
                    ),
                    ChatTimelineItem.Assistant(
                        id = "a_1",
                        text = "正在分析中，请稍候。",
                        ts = 1_730_000_002_000L,
                    ),
                ),
            )

            setContent {
                Box(modifier = Modifier.size(width = 520.dp, height = 640.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = activeSession,
                        transcript = transcript,
                        isSessionRunning = true,
                        onLoadOlder = {},
                    )
                }
            }
            waitForIdle()

            val initialSpinnerBounds =
                onNodeWithTag("live_spinner_row", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val initialMsgBounds =
                onNodeWithTag("msg_time_assistant_a_1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val initialSpinnerHeight = (initialSpinnerBounds.bottom - initialSpinnerBounds.top).value

            val statusSequence = listOf(
                "⡿ A very long live status line that would previously wrap onto two or three lines in a narrow viewport and bounce the entire chat timeline up and down every single poll cycle",
                null,
                "✻ Line one\nLine two\nLine three should still clamp to single line",
                null,
                "⣟ Running ./app/gradlew -p ./app :shared:jvmTest :desktopApp:compileKotlinJvm :androidApp:assembleDebug",
                null,
            )

            for (nextStatus in statusSequence) {
                activeSession = activeSession.copy(liveStatusText = nextStatus)
                mainClock.advanceTimeBy(300L)
                waitForIdle()

                val spinnerBounds =
                    onNodeWithTag("live_spinner_row", useUnmergedTree = true).getUnclippedBoundsInRoot()
                val msgBounds =
                    onNodeWithTag("msg_time_assistant_a_1", useUnmergedTree = true).getUnclippedBoundsInRoot()
                val spinnerHeight = (spinnerBounds.bottom - spinnerBounds.top).value

                assertTrue(
                    abs(spinnerHeight - initialSpinnerHeight) < 0.5f,
                    "Expected live_spinner_row height $initialSpinnerHeight dp, got $spinnerHeight dp for status=$nextStatus",
                )
                assertTrue(
                    abs(msgBounds.bottom.value - initialMsgBounds.bottom.value) < 0.5f,
                    "Expected message above spinner to stay pinned at ${initialMsgBounds.bottom}, jumped to ${msgBounds.bottom} for status=$nextStatus",
                )
            }
        }

    @Test
    fun `streaming updates while pinned at bottom do not spuriously trigger loadOlder on compact timeline`() =
        runComposeUiTest {
            val sid = "host:agy:compact-trace"
            val session = SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/demo",
                title = "Compact Trace",
                state = SessionState.RUNNING,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                lastSeq = 10,
            )
            var loadOlderCalls = 0
            var transcript by mutableStateOf(
                SessionTranscript(
                    earliestSeq = 5,
                    lastSeq = 10,
                    hasMoreBefore = true,
                    items = listOf(
                        ChatTimelineItem.Tool(
                            id = "tc_1",
                            title = "Read",
                            kind = ToolKind.READ,
                            status = ToolStatus.COMPLETED,
                            ts = 1_730_000_001_000L,
                        ),
                        ChatTimelineItem.Tool(
                            id = "tc_2",
                            title = "Bash",
                            kind = ToolKind.EXECUTE,
                            status = ToolStatus.IN_PROGRESS,
                            ts = 1_730_000_002_000L,
                        ),
                        ChatTimelineItem.Assistant(
                            id = "a_live",
                            text = "初始回复",
                            ts = 1_730_000_003_000L,
                        ),
                    ),
                ),
            )

            setContent {
                Box(modifier = Modifier.size(width = 760.dp, height = 720.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = session,
                        transcript = transcript,
                        isSessionRunning = true,
                        onLoadOlder = { loadOlderCalls++ },
                    )
                }
            }
            waitForIdle()

            // Simulate 15 rapid streaming deltas while user is passively watching at bottom
            for (step in 1..15) {
                transcript = transcript.copy(
                    lastSeq = 10 + step,
                    items = transcript.items.dropLast(1) + ChatTimelineItem.Assistant(
                        id = "a_live",
                        text = "初始回复 " + "增量内容 ".repeat(step),
                        ts = 1_730_000_003_000L + step * 100L,
                    ),
                )
                mainClock.advanceTimeBy(60L)
                waitForIdle()
            }

            assertEquals(
                0,
                loadOlderCalls,
                "Passive streaming at bottom must never spuriously invoke onLoadOlder()",
            )
        }

    @Test
    fun `streaming deltas do not yank scroll back to bottom after user scrolls upward`() =
        runComposeUiTest {
            val sid = "host:agy:user-scrolled-up"
            val session = SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/demo",
                title = "Long History",
                state = SessionState.RUNNING,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_050_000L,
                lastSeq = 40,
            )
            val initialItems = (1..24).flatMap { i ->
                listOf(
                    ChatTimelineItem.User(
                        id = "u_$i",
                        text = "历史提问第 $i 条，用于制造足够的纵向滚动高度。",
                        ts = 1_730_000_000_000L + i * 2_000L,
                    ),
                    ChatTimelineItem.Assistant(
                        id = "a_$i",
                        text = "历史回复第 $i 条。\n\n第二段说明文字，用于拉高整体时间线高度以便测试向上滚动阅读时的锚定稳定性。",
                        ts = 1_730_000_001_000L + i * 2_000L,
                    ),
                )
            }
            var transcript by mutableStateOf(
                SessionTranscript(
                    earliestSeq = 0,
                    lastSeq = 40,
                    hasMoreBefore = false,
                    items = initialItems,
                ),
            )

            setContent {
                Box(modifier = Modifier.size(width = 640.dp, height = 420.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = session,
                        transcript = transcript,
                        isSessionRunning = true,
                        onLoadOlder = {},
                    )
                }
            }
            waitForIdle()

            // Confirm spinner is visible at bottom before user scrolls upward
            val spinnerVisibleAtStart = runCatching {
                onNodeWithTag("live_spinner_row", useUnmergedTree = true).getUnclippedBoundsInRoot()
            }.isSuccess
            assertTrue(spinnerVisibleAtStart, "Expected bottom spinner visible before scrolling up")

            // Swipe down (pull older messages into view in reverseLayout = true) twice
            onNodeWithTag("conversation_lazy_list").performTouchInput {
                swipeDown(startY = top + 60f, endY = bottom - 40f, durationMillis = 150L)
            }
            waitForIdle()
            onNodeWithTag("conversation_lazy_list").performTouchInput {
                swipeDown(startY = top + 60f, endY = bottom - 40f, durationMillis = 150L)
            }
            waitForIdle()

            // Verify the bottom spinner is no longer pinned in view before streaming updates
            val spinnerGoneBefore = runCatching {
                onNodeWithTag("live_spinner_row", useUnmergedTree = true).getUnclippedBoundsInRoot()
            }.isFailure
            assertTrue(spinnerGoneBefore, "Expected user to be scrolled up away from bottom spinner")

            // Push 12 rapid streaming deltas to the newest message at the bottom
            for (step in 1..12) {
                transcript = transcript.copy(
                    lastSeq = 40 + step,
                    items = transcript.items.dropLast(1) + ChatTimelineItem.Assistant(
                        id = "a_24",
                        text = "历史回复第 24 条流式增量 $step",
                        ts = 1_730_000_050_000L + step * 50L,
                    ),
                )
                mainClock.advanceTimeBy(50L)
                waitForIdle()
            }

            val spinnerStillOffscreen = runCatching {
                onNodeWithTag("live_spinner_row", useUnmergedTree = true).getUnclippedBoundsInRoot()
            }.isFailure
            assertTrue(
                spinnerStillOffscreen,
                "Rapid streaming deltas must NOT yank scroll back to bottom while user is reading older messages",
            )
        }

    @Test
    fun `subagent status strip keeps stable height when switching between active chips and history-only state`() =
        runComposeUiTest {
            val activeSub = SubagentSummary(
                id = "host:agy:sub_1",
                title = "排查滚动弹跳",
                state = SessionState.RUNNING,
                statusText = "检查日志",
                createdAt = 1_000L,
                updatedAt = 2_000L,
            )
            val closedSub = activeSub.copy(state = SessionState.CLOSED, statusText = "已完成")
            var subagents by mutableStateOf(listOf(activeSub))

            setContent {
                Box(modifier = Modifier.size(width = 700.dp, height = 160.dp)) {
                    SubagentStatusStrip(
                        sessionId = "host:agy:parent",
                        subagents = subagents,
                        onSelectSubagent = {},
                    )
                }
            }
            waitForIdle()
            val activeBounds = onNodeWithTag("subagent_status_strip").getUnclippedBoundsInRoot()
            val activeH = (activeBounds.bottom - activeBounds.top).value

            subagents = listOf(closedSub)
            waitForIdle()
            val historyBounds = onNodeWithTag("subagent_status_strip").getUnclippedBoundsInRoot()
            val historyH = (historyBounds.bottom - historyBounds.top).value

            assertTrue(
                abs(activeH - historyH) <= 2.5f,
                "Expected subagent strip height to stay stable (active=${activeH}dp, history=${historyH}dp)",
            )
        }
}
