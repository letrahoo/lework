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
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.ArtifactContentResult
import dev.lework.protocol.ArtifactInfo
import dev.lework.protocol.ArtifactKind
import dev.lework.protocol.ArtifactSource
import dev.lework.protocol.PermissionOption
import dev.lework.protocol.PermissionOptionKind
import dev.lework.protocol.PlanEntry
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ChatTimelineItem
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleUiState
import dev.lework.store.SessionTranscript
import dev.lework.store.TurnRecoveryBannerState
import dev.lework.ui.ArtifactsInspectorSheet
import dev.lework.ui.ConversationStreamView
import dev.lework.ui.EverforestColorScheme
import dev.lework.ui.EverforestMarkdownContent
import dev.lework.ui.InlineApprovalBanner
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

    @Test
    fun `drag selection preserves formatting across lists, alert quotes, tables, and excludes mermaid pie labels`() =
        runComposeUiTest {
            val md = buildString {
                appendLine("StartToken")
                appendLine()
                appendLine("1. OrderedOne")
                appendLine("2. OrderedTwo")
                appendLine()
                appendLine("- BulletAlpha")
                appendLine()
                appendLine("> [!WARNING]")
                appendLine("> AlertBodyLine")
                appendLine()
                appendLine("| ColA | ColB |")
                appendLine("| --- | --- |")
                appendLine("| Val1 | Val2 |")
                appendLine()
                appendLine("```mermaid")
                appendLine("pie showData")
                appendLine("    title PieChartTitle")
                appendLine("    \"SliceA\" : 60")
                appendLine("    \"SliceB\" : 40")
                appendLine("```")
                appendLine()
                appendLine("EndToken")
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
                override val nativeClipboard: Any get() = this
            }

            setContent {
                CompositionLocalProvider(LocalClipboard provides fakeClipboard) {
                    MaterialTheme(colorScheme = EverforestColorScheme) {
                        Box(modifier = Modifier.size(720.dp, 900.dp)) {
                            EverforestMarkdownContent(markdown = md)
                        }
                    }
                }
            }
            waitForIdle()

            val startBounds = onNodeWithText("StartToken", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            val endBounds = onNodeWithText("EndToken", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot

            val startPt = Offset(startBounds.left + 2f, startBounds.center.y)
            val endPt = Offset(endBounds.right - 2f, endBounds.center.y)
            onRoot().performMouseInput {
                moveTo(startPt)
                press()
                for (step in 1..14) {
                    val f = step.toFloat() / 14f
                    moveTo(
                        Offset(
                            startPt.x + (endPt.x - startPt.x) * f,
                            startPt.y + (endPt.y - startPt.y) * f,
                        ),
                        delayMillis = 12L,
                    )
                }
                release()
            }
            waitForIdle()

            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.C) } }
            waitForIdle()
            if (capturedSelection == null) {
                onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
                waitForIdle()
            }

            val copied = capturedSelection.orEmpty()
            assertTrue(copied.contains("1. OrderedOne"), "Expected space after ordered list number; got: <$copied>")
            assertTrue(copied.contains("2. OrderedTwo"), "Expected space after ordered list number; got: <$copied>")
            assertTrue(copied.contains("· BulletAlpha"), "Expected space after bullet marker; got: <$copied>")
            assertTrue(
                copied.contains("WARNING\nAlertBodyLine"),
                "Expected newline between alert kind and quote body; got: <$copied>",
            )
            assertTrue(
                copied.contains("ColA\tColB\nVal1\tVal2"),
                "Expected TSV separators across table cells/rows; got: <$copied>",
            )
            assertFalse(
                copied.contains("PieChartTitle") || copied.contains("SliceA"),
                "Expected Mermaid pie canvas labels to be excluded from text selection; got: <$copied>",
            )
        }

    @Test
    fun `markdown links remain clickable inside SelectionContainer`() =
        runComposeUiTest {
            setContent {
                MaterialTheme(colorScheme = EverforestColorScheme) {
                    Box(modifier = Modifier.size(600.dp, 200.dp)) {
                        EverforestMarkdownContent(markdown = "查看 [Lework 文档](https://example.com/lework)")
                    }
                }
            }
            waitForIdle()

            val bounds = onNodeWithText("Lework 文档", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            onRoot().performMouseInput {
                moveTo(Offset(bounds.right - 8f, bounds.center.y))
                press()
                release()
            }
            waitForIdle()

            assertEquals(listOf("https://example.com/lework"), JvmPlatformTestHooks.openedUrls)
        }

    @Test
    fun `markdown artifact preview supports drag selection without nested SelectionContainer interference`() =
        runComposeUiTest {
            var capturedSelection: String? = null
            val fakeClipboard = object : Clipboard {
                override suspend fun getClipEntry(): ClipEntry? = null
                override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                    val transferable = clipEntry?.nativeClipEntry as? Transferable
                    capturedSelection = runCatching {
                        transferable?.getTransferData(DataFlavor.stringFlavor) as? String
                    }.getOrNull()
                }
                override val nativeClipboard: Any get() = this
            }

            val artifact = ArtifactInfo(
                id = "art_plan_1",
                sessionId = "host:claude:s1",
                kind = ArtifactKind.PLAN,
                source = ArtifactSource.CLAUDE_PLAN,
                title = "实施方案",
                fileName = "plan.md",
                mimeType = "text/markdown",
                updatedAt = 1_730_000_000_000L,
                sizeBytes = 64L,
                actionable = false,
            )
            val uiState = ConsoleUiState(
                activeSessionArtifacts = listOf(artifact),
                openedArtifact = ArtifactContentResult(
                    artifact = artifact,
                    content = "## ArtifactHeading\n\nArtifactBodyToken",
                ),
            )

            setContent {
                CompositionLocalProvider(LocalClipboard provides fakeClipboard) {
                    MaterialTheme(colorScheme = EverforestColorScheme) {
                        Box(modifier = Modifier.size(720.dp, 640.dp)) {
                            ArtifactsInspectorSheet(
                                state = uiState,
                                onRefresh = {},
                                onOpenArtifact = {},
                                onCloseArtifact = {},
                                onApprovePlan = {},
                                onRevisePlan = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()

            val startBounds = onNodeWithText("ArtifactHeading", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            val endBounds = onNodeWithText("ArtifactBodyToken", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            val startPt = Offset(startBounds.left + 2f, startBounds.center.y)
            val endPt = Offset(endBounds.right - 2f, endBounds.center.y)
            onRoot().performMouseInput {
                moveTo(startPt)
                press()
                for (step in 1..8) {
                    val f = step.toFloat() / 8f
                    moveTo(Offset(startPt.x + (endPt.x - startPt.x) * f, startPt.y + (endPt.y - startPt.y) * f), delayMillis = 12L)
                }
                release()
            }
            waitForIdle()
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.C) } }
            waitForIdle()
            if (capturedSelection == null) {
                onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
                waitForIdle()
            }

            assertEquals(
                listOf("ArtifactHeading", "ArtifactBodyToken"),
                capturedSelection.orEmpty().lines().filter { it.isNotBlank() },
                "Expected drag selection inside Markdown artifact preview to copy cleanly; got: <$capturedSelection>",
            )
        }

    @Test
    fun `queued prompts card supports tap to expand and drag selection without collapsing`() =
        runComposeUiTest {
            val sid = "host:claude:queued-select-test"
            val longPrompt = "QueuedFirstToken " + "详细排队任务描述内容 ".repeat(14) + "QueuedTailToken"
            val session = SessionMeta(
                id = sid,
                engineId = "claude",
                cwd = "/workspace/demo",
                title = "Queued Prompt Test",
                state = SessionState.RUNNING,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                lastSeq = 1,
                queuedPrompts = listOf(longPrompt, "SecondQueuedItem", "ThirdHiddenQueuedItem"),
            )
            val transcript = SessionTranscript(
                earliestSeq = 0,
                lastSeq = 1,
                items = listOf(
                    ChatTimelineItem.User(id = "u_q", text = "当前任务", ts = 1_730_000_001_000L, seq = 1),
                ),
            )

            var capturedSelection: String? = null
            val fakeClipboard = object : Clipboard {
                override suspend fun getClipEntry(): ClipEntry? = null
                override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                    val transferable = clipEntry?.nativeClipEntry as? Transferable
                    capturedSelection = runCatching {
                        transferable?.getTransferData(DataFlavor.stringFlavor) as? String
                    }.getOrNull()
                }
                override val nativeClipboard: Any get() = this
            }

            setContent {
                CompositionLocalProvider(LocalClipboard provides fakeClipboard) {
                    MaterialTheme(colorScheme = EverforestColorScheme) {
                        Box(modifier = Modifier.size(640.dp, 600.dp)) {
                            ConversationStreamView(
                                sessionKey = sid,
                                activeSession = session,
                                transcript = transcript,
                                isSessionRunning = true,
                                onLoadOlder = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // Initially collapsed: 3rd prompt is hidden
            onNodeWithText("ThirdHiddenQueuedItem", substring = true, useUnmergedTree = true).assertDoesNotExist()

            // Clicking the truncated prompt body should expand the queued prompts card
            onNodeWithText("QueuedFirstToken", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
                .performClick()
            waitForIdle()
            onNodeWithText("ThirdHiddenQueuedItem", substring = true, useUnmergedTree = true).assertIsDisplayed()

            // Drag-selecting text on the expanded prompt must copy text and NOT collapse the card back!
            val bounds = onNodeWithText("ThirdHiddenQueuedItem", substring = true, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
            val startPt = Offset(bounds.left + 2f, bounds.center.y)
            val endPt = Offset(bounds.right - 2f, bounds.center.y)
            onRoot().performMouseInput {
                moveTo(startPt)
                press()
                for (step in 1..6) {
                    val f = step.toFloat() / 6f
                    moveTo(Offset(startPt.x + (endPt.x - startPt.x) * f, startPt.y), delayMillis = 12L)
                }
                release()
            }
            waitForIdle()
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.C) } }
            waitForIdle()
            if (capturedSelection == null) {
                onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
                waitForIdle()
            }

            assertTrue(
                capturedSelection.orEmpty().contains("ThirdHiddenQueuedItem"),
                "Expected drag selection inside expanded queued prompt to copy text; got: <$capturedSelection>",
            )
            onNodeWithText("ThirdHiddenQueuedItem", substring = true, useUnmergedTree = true).assertIsDisplayed()
        }

    @Test
    fun `inline approval banner strips blank separator lines while keeping indentation and internal blank lines`() =
        runComposeUiTest {
            val permission = AgentEvent.PermissionRequested(
                seq = 5,
                sessionId = "host:claude:perm",
                ts = 1_730_000_000_000L,
                requestId = "perm_1",
                title = "Execute Bash\n\n  echo first\n\n  echo second\n\n",
                options = listOf(
                    PermissionOption(id = "allow", name = "允许", kind = PermissionOptionKind.ALLOW_ONCE),
                ),
            )
            setContent {
                MaterialTheme(colorScheme = EverforestColorScheme) {
                    Box(modifier = Modifier.size(640.dp, 300.dp)) {
                        InlineApprovalBanner(
                            permission = permission,
                            onDecision = { _, _, _ -> },
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithText("  echo first\n\n  echo second", useUnmergedTree = true).assertIsDisplayed()
        }

    @Test
    fun `jumping to a tall non-virtualized assistant reply on initial mount reveals its beginning`() =
        runComposeUiTest {
            val sid = "host:claude:tall-jump"
            val tallReply = buildString {
                appendLine("## TallReplyTopHeading")
                appendLine()
                for (i in 1..18) {
                    appendLine("第 $i 段内容：为了构造一个高度远超视口但未触发虚拟化的长回复，这里补充多行可读段落内容，用于验证跳转时能正确定位到回复顶部而不是底部。")
                    appendLine()
                }
                appendLine("TallReplyBottomFooter")
            }.trim()

            val session = SessionMeta(
                id = sid,
                engineId = "claude",
                cwd = "/workspace/demo",
                title = "Tall Jump",
                state = SessionState.IDLE,
                createdAt = 1_730_000_000_000L,
                updatedAt = 1_730_000_010_000L,
                lastSeq = 2,
            )
            val transcript = SessionTranscript(
                earliestSeq = 0,
                lastSeq = 2,
                items = listOf(
                    ChatTimelineItem.User(id = "u_1", text = "提问", ts = 1_730_000_001_000L, seq = 1),
                    ChatTimelineItem.Assistant(id = "a_tall", text = tallReply, ts = 1_730_000_002_000L, seq = 2),
                ),
            )

            setContent {
                MaterialTheme(colorScheme = EverforestColorScheme) {
                    Box(modifier = Modifier.size(520.dp, 360.dp)) {
                        ConversationStreamView(
                            sessionKey = sid,
                            activeSession = session,
                            transcript = transcript,
                            isSessionRunning = false,
                            onLoadOlder = {},
                            pendingJumpSeq = 2,
                            pendingJumpNonce = 1L,
                        )
                    }
                }
            }
            waitForIdle()

            val topBounds = onNodeWithText("TallReplyTopHeading", substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue(
                topBounds.top >= 0f && topBounds.top < 120f,
                "Expected tall reply top heading near top of viewport, got bounds=$topBounds",
            )
        }

    @Test
    fun `copy confirmation does not appear when platform clipboard rejects write, and plan card supports cross-step drag copy`() =
        runComposeUiTest {
            val sid = "host:claude:clipboard-fail-and-plan"
            val session = SessionMeta(
                id = sid,
                engineId = "claude",
                cwd = "/workspace/demo",
                title = "Clipboard Failure & Plan Selection",
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
                        id = "a_fail",
                        text = "检查剪贴板写入失败回退",
                        ts = 1_730_000_002_000L,
                        seq = 1,
                    ),
                ),
                planEntries = listOf(
                    PlanEntry(content = "PlanStepAlpha", status = "completed"),
                    PlanEntry(content = "PlanStepBeta", status = "in_progress"),
                ),
            )

            var capturedSelection: String? = null
            val fakeClipboard = object : Clipboard {
                override suspend fun getClipEntry(): ClipEntry? = null
                override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                    val transferable = clipEntry?.nativeClipEntry as? Transferable
                    capturedSelection = runCatching {
                        transferable?.getTransferData(DataFlavor.stringFlavor) as? String
                    }.getOrNull()
                }
                override val nativeClipboard: Any get() = this
            }

            setContent {
                CompositionLocalProvider(LocalClipboard provides fakeClipboard) {
                    MaterialTheme(colorScheme = EverforestColorScheme) {
                        Box(modifier = Modifier.size(640.dp, 500.dp)) {
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

            // 1. When platform clipboard rejects write, button must NOT transition to "已复制"
            JvmPlatformTestHooks.clipboardWriteSucceeds = false
            onNodeWithTag("msg_copy_assistant_a_fail", useUnmergedTree = true).assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals("", JvmPlatformTestHooks.clipboardText)
            onNodeWithText("已复制", useUnmergedTree = true).assertDoesNotExist()

            // 2. When clipboard succeeds, button transitions to "已复制"
            JvmPlatformTestHooks.clipboardWriteSucceeds = true
            onNodeWithTag("msg_copy_assistant_a_fail", useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals("检查剪贴板写入失败回退", JvmPlatformTestHooks.clipboardText)
            onNodeWithText("已复制", useUnmergedTree = true).assertIsDisplayed()

            // 3. PlanCard supports drag-selecting across multiple plan steps with newline separation
            val startBounds = onNodeWithText("PlanStepAlpha", useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            val endBounds = onNodeWithText("PlanStepBeta", useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            val startPt = Offset(startBounds.left + 2f, startBounds.center.y)
            val endPt = Offset(endBounds.right - 2f, endBounds.center.y)
            onRoot().performMouseInput {
                moveTo(startPt)
                press()
                for (step in 1..6) {
                    val f = step.toFloat() / 6f
                    moveTo(
                        Offset(
                            startPt.x + (endPt.x - startPt.x) * f,
                            startPt.y + (endPt.y - startPt.y) * f,
                        ),
                        delayMillis = 12L,
                    )
                }
                release()
            }
            waitForIdle()
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.C) } }
            waitForIdle()
            if (capturedSelection == null) {
                onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
                waitForIdle()
            }
            assertEquals(
                listOf("PlanStepAlpha", "PlanStepBeta"),
                capturedSelection.orEmpty().lines().filter { it.isNotBlank() },
                "Expected PlanCard drag selection to span multiple steps cleanly; got: <$capturedSelection>",
            )
        }
}
