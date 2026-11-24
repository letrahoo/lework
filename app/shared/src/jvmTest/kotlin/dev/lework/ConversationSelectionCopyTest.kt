package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ChatTimelineItem
import dev.lework.store.ConnectionStatus
import dev.lework.store.SessionTranscript
import dev.lework.store.TurnRecoveryBannerState
import dev.lework.ui.ConversationStreamView
import dev.lework.ui.EverforestColorScheme
import dev.lework.ui.NetworkErrorRecoveryBanner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConversationSelectionCopyTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.simulateDesktopLayout = true
    }

    @Test
    fun `user, assistant markdown, and error recovery banner support one-click full copy`() =
        runComposeUiTest {
            val sid = "host:claude:copy-actions-test"
            val userText = "请帮我检查这段配置并给出修复建议"
            val assistantMarkdown = buildString {
                appendLine("## 诊断结论")
                appendLine()
                appendLine("发现了两处核心问题，详情如下：")
                appendLine()
                appendLine("- 第一项：锁文件由自身子进程持有")
                appendLine("- 第二项：缺少异步关闭等待")
                appendLine()
                appendLine("```kotlin")
                appendLine("fun verifyCopy() = true")
                appendLine("```")
                appendLine()
                appendLine("| 模块 | 状态 |")
                appendLine("| --- | --- |")
                appendLine("| Hub | 正常 |")
            }.trim()
            val errorDetail = "该 AGY 会话正在终端窗口中打开并占用会话锁，请关闭对应窗口后重试。"

            val session = SessionMeta(
                id = sid,
                engineId = "claude",
                cwd = "/workspace/demo",
                title = "Copy Test",
                state = SessionState.IDLE,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                lastSeq = 3,
            )
            val transcript = SessionTranscript(
                earliestSeq = 0,
                lastSeq = 3,
                items = listOf(
                    ChatTimelineItem.User(
                        id = "u_1",
                        text = userText,
                        ts = 1_730_000_001_000L,
                        seq = 1,
                    ),
                    ChatTimelineItem.Assistant(
                        id = "a_1",
                        text = assistantMarkdown,
                        ts = 1_730_000_002_000L,
                        seq = 2,
                    ),
                    ChatTimelineItem.Notice(
                        id = "n_1",
                        text = "提示：连接已自动恢复",
                        isError = false,
                        seq = 3,
                    ),
                ),
            )
            val recoveryBanner = TurnRecoveryBannerState(
                sessionId = sid,
                headline = "上一轮任务执行中断",
                detail = errorDetail,
                isNetworkError = false,
                errorCount = 1,
            )

            setContent {
                MaterialTheme(colorScheme = EverforestColorScheme) {
                    Column(modifier = Modifier.size(960.dp, 760.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            ConversationStreamView(
                                sessionKey = sid,
                                activeSession = session,
                                transcript = transcript,
                                isSessionRunning = false,
                                onLoadOlder = {},
                            )
                        }
                        NetworkErrorRecoveryBanner(
                            banner = recoveryBanner,
                            connectionStatus = ConnectionStatus.CONNECTED,
                            isRetrying = false,
                            onRetry = {},
                            onDismiss = {},
                        )
                    }
                }
            }
            waitForIdle()

            // 1. Copy user message
            onNodeWithTag("msg_copy_user_u_1", useUnmergedTree = true).assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(userText, JvmPlatformTestHooks.clipboardText)

            // 2. Copy full multi-block assistant markdown reply
            onNodeWithTag("msg_copy_assistant_a_1", useUnmergedTree = true).assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(assistantMarkdown, JvmPlatformTestHooks.clipboardText)

            // 3. Copy error recovery banner detail
            onNodeWithTag("copy_recovery_banner_button", useUnmergedTree = true).assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(errorDetail, JvmPlatformTestHooks.clipboardText)
        }

    @Test
    fun `mobile virtualized ultra-long assistant reply still copies complete raw markdown`() =
        runComposeUiTest {
            JvmPlatformTestHooks.simulateDesktopLayout = false
            val sid = "host:claude:virtualized-copy-test"
            val hugeMarkdown = buildString {
                for (section in 1..14) {
                    appendLine("### 第 $section 节分析")
                    appendLine()
                    appendLine("这是第 $section 节的详细排查说明内容，用于验证超长多块虚拟化消息在移动端仍支持一键复制完整原文与分块选字。".repeat(6))
                    appendLine()
                }
            }.trim()
            assertTrue(hugeMarkdown.length > 3_200, "Fixture must exceed mobile virtualization threshold")

            val session = SessionMeta(
                id = sid,
                engineId = "claude",
                cwd = "/workspace/demo",
                title = "Long Virtualized Copy",
                state = SessionState.IDLE,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                lastSeq = 1,
            )
            val transcript = SessionTranscript(
                earliestSeq = 0,
                lastSeq = 1,
                items = listOf(
                    ChatTimelineItem.Assistant(
                        id = "a_huge",
                        text = hugeMarkdown,
                        ts = 1_730_000_005_000L,
                        seq = 1,
                    ),
                ),
            )

            setContent {
                MaterialTheme(colorScheme = EverforestColorScheme) {
                    Box(modifier = Modifier.size(390.dp, 844.dp)) {
                        ConversationStreamView(
                            sessionKey = sid,
                            activeSession = session,
                            transcript = transcript,
                            isSessionRunning = false,
                            onLoadOlder = {},
                        )
                    }
                }
            }
            waitForIdle()

            val firstHeading = onNodeWithText("第 1 节分析", useUnmergedTree = true)
            firstHeading.assertDoesNotExist()
            onNodeWithTag("msg_copy_assistant_a_huge", useUnmergedTree = true).assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(hugeMarkdown, JvmPlatformTestHooks.clipboardText)
            onNodeWithTag("conversation_lazy_list").performScrollToKey("a_huge_b0")
            firstHeading.assertIsDisplayed()
        }

    @Test
    fun `desktop mouse drag selection spans multiple markdown blocks and excludes code chrome`() =
        runComposeUiTest {
            val sid = "host:agy:drag-selection-test"
            val assistantMarkdown = buildString {
                appendLine("## AlphaHeadingToken")
                appendLine()
                appendLine("MiddleParagraphToken with details.")
                appendLine()
                appendLine("```kotlin")
                appendLine("val omegaCodeToken = 42")
                appendLine("```")
            }.trim()

            var capturedSelection: String? = null
            val fakeClipboard = object : Clipboard {
                override suspend fun getClipEntry(): ClipEntry? = null

                override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                    val transferable = clipEntry?.nativeClipEntry as? Transferable
                    capturedSelection = runCatching {
                        transferable?.getTransferData(DataFlavor.stringFlavor) as? String
                    }.getOrNull()
                }

                override val nativeClipboard: Any
                    get() = this
            }

            val session = SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/demo",
                title = "Drag Select Test",
                state = SessionState.IDLE,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                lastSeq = 1,
            )
            val transcript = SessionTranscript(
                earliestSeq = 0,
                lastSeq = 1,
                items = listOf(
                    ChatTimelineItem.Assistant(
                        id = "a_drag",
                        text = assistantMarkdown,
                        ts = 1_730_000_005_000L,
                        seq = 1,
                    ),
                ),
            )

            setContent {
                CompositionLocalProvider(LocalClipboard provides fakeClipboard) {
                    MaterialTheme(colorScheme = EverforestColorScheme) {
                        Box(modifier = Modifier.size(860.dp, 640.dp)) {
                            ConversationStreamView(
                                sessionKey = sid,
                                activeSession = session,
                                transcript = transcript,
                                isSessionRunning = false,
                                onLoadOlder = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()

            val startBounds = onNodeWithText("AlphaHeadingToken", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            val endBounds = onNodeWithText("omegaCodeToken", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot

            val startPt = Offset(startBounds.left + 2f, startBounds.center.y)
            val endPt = Offset(endBounds.right - 2f, endBounds.center.y)
            onRoot().performMouseInput {
                moveTo(startPt)
                press()
                val steps = 10
                for (step in 1..steps) {
                    val f = step.toFloat() / steps
                    moveTo(
                        Offset(
                            startPt.x + (endPt.x - startPt.x) * f,
                            startPt.y + (endPt.y - startPt.y) * f,
                        ),
                        delayMillis = 16L,
                    )
                }
                release()
            }
            waitForIdle()

            // Trigger both Meta+C (macOS) and Ctrl+C (Linux/Windows) to exercise Compose's SelectionContainer copy shortcut
            onRoot().performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.C) }
            }
            waitForIdle()
            if (capturedSelection == null) {
                onRoot().performKeyInput {
                    withKeyDown(Key.CtrlLeft) { pressKey(Key.C) }
                }
                waitForIdle()
            }

            val copied = capturedSelection.orEmpty()
            assertEquals(listOf("AlphaHeadingToken", "MiddleParagraphToken with details.", "val omegaCodeToken = 42"),
                copied.lines().filter { it.isNotBlank() }, "Selected blocks should remain separate when pasted")
            assertFalse(
                copied.contains("复制"),
                "Expected DisableSelection to exclude code header copy button label; got: <$copied>",
            )
        }
}
