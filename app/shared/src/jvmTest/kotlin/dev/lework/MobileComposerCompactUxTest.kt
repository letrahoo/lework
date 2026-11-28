package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.PickedImagePayload
import dev.lework.protocol.EngineCapabilities
import dev.lework.protocol.EngineInfo
import dev.lework.protocol.EngineKind
import dev.lework.protocol.EngineModelSpec
import dev.lework.protocol.EngineStatus
import dev.lework.protocol.ReasoningEffort
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import dev.lework.ui.mobileComposerPlaceholder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MobileComposerCompactUxTest {

    private val tinyPngB64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.simulateDesktopLayout = false
    }

    @AfterTest
    fun tearDown() {
        JvmPlatformTestHooks.reset()
    }

    private fun testEngines(): List<EngineInfo> = listOf(
        EngineInfo(
            id = "claude",
            displayName = "Claude Code",
            kind = EngineKind.CLI,
            status = EngineStatus(installed = true, ready = true, version = "2.1.328"),
            capabilities = EngineCapabilities(
                permissions = true,
                images = true,
                modelSwitch = true,
                modes = true,
                history = true,
                cancel = true,
            ),
            models = listOf(
                EngineModelSpec(
                    id = "claude-sonnet-4-6",
                    name = "Claude Sonnet 4.6",
                    efforts = listOf(
                        ReasoningEffort.LOW,
                        ReasoningEffort.MEDIUM,
                        ReasoningEffort.HIGH,
                    ),
                ),
            ),
            defaultModel = "claude-sonnet-4-6",
        ),
    )

    @Test
    fun mobileComposerPlaceholderAdaptsConciseCopyToIdleImageAndRunningStates() {
        assertEquals(
            "发消息给 Claude…",
            mobileComposerPlaceholder(engineId = "claude", hasAttachedImages = false, canQueueFollowUp = false),
        )
        assertEquals(
            "发消息给 AGY…",
            mobileComposerPlaceholder(engineId = "agy", hasAttachedImages = false, canQueueFollowUp = false),
        )
        assertEquals(
            "补充下一条指令，结束后自动发送…",
            mobileComposerPlaceholder(engineId = "claude", hasAttachedImages = false, canQueueFollowUp = true),
        )
        assertEquals(
            "添加图片说明，或直接发送…",
            mobileComposerPlaceholder(engineId = "claude", hasAttachedImages = true, canQueueFollowUp = false),
        )
        assertEquals(
            "添加图片说明，或直接发送…",
            mobileComposerPlaceholder(engineId = "claude", hasAttachedImages = true, canQueueFollowUp = true),
        )
    }

    @Test
    fun mobileComposerRendersCompactIconActionsWithoutVerboseLabelsAndUpdatesPlaceholderByContext() =
        runComposeUiTest {
            val transport = FakeHubTransport().apply {
                customEngines = testEngines()
            }
            val idleSid = "sess_mobile_compact_idle"
            val runningSid = "sess_mobile_compact_running"
            transport.sessions.addAll(
                listOf(
                    SessionMeta(
                        id = idleSid,
                        engineId = "claude",
                        cwd = "/workspace/lework",
                        title = "手机端输入栏验证",
                        state = SessionState.IDLE,
                        createdAt = 1_000L,
                        updatedAt = 2_000L,
                        lastSeq = 0,
                    ),
                    SessionMeta(
                        id = runningSid,
                        engineId = "claude",
                        cwd = "/workspace/lework",
                        title = "运行中会话占位文案验证",
                        state = SessionState.RUNNING,
                        createdAt = 1_000L,
                        updatedAt = 3_000L,
                        lastSeq = 0,
                    ),
                ),
            )

            lateinit var store: ConsoleStore
            setContent {
                val scope = rememberCoroutineScope()
                val created = remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 390.dp, height = 844.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitUntil(timeoutMillis = 5_000L) { store.state.value.hasEnteredWorkspace }
            runOnIdle { store.selectSession(idleSid) }
            waitForIdle()

            // 1. Static action labels are removed; icon-only buttons remain displayed with accessibility descriptions
            onNodeWithText("添加图片").assertDoesNotExist()
            onNodeWithText("命令").assertDoesNotExist()
            onNodeWithText("引用文件").assertDoesNotExist()

            onNodeWithTag("composer_add_image_btn").assertIsDisplayed()
            onNodeWithTag("composer_slash_btn").assertIsDisplayed()
            onNodeWithTag("composer_mention_btn").assertIsDisplayed()
            onNodeWithContentDescription("添加图片或文件", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithContentDescription("命令", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithContentDescription("引用文件", useUnmergedTree = true).assertIsDisplayed()

            // Dynamic workspace & effort chips fit cleanly alongside the compact icon actions on a 390dp phone screen
            onNodeWithTag("composer_workspace_chip").assertIsDisplayed()
            onNodeWithTag("composer_effort_chip").assertIsDisplayed()
            onNodeWithTag("composer_placeholder").assertIsDisplayed().assertTextEquals("发消息给 Claude…")

            // 2. Tapping slash icon toggles command popup and updates accessibility label
            onNodeWithTag("composer_slash_btn").performClick()
            waitForIdle()
            onNodeWithContentDescription("收起命令面板", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("composer_slash_btn").performClick()
            waitForIdle()
            onNodeWithContentDescription("命令", useUnmergedTree = true).assertIsDisplayed()

            // 3. Attaching an image switches button to compact "1/4" count (no "图片" prefix) & image placeholder
            JvmPlatformTestHooks.nextClipboardImages = listOf(
                PickedImagePayload(
                    fileName = "shot.png",
                    mimeType = "image/png",
                    dataBase64 = tinyPngB64,
                    thumbnailBase64 = tinyPngB64,
                ),
            )
            onNodeWithTag("composer_add_image_btn").performClick()
            waitForIdle()
            onNodeWithTag("composer_paste_clipboard_image").performClick()
            waitForIdle()
            onNodeWithText("1/4", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("图片 1/4", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("composer_placeholder").assertTextEquals("添加图片说明，或直接发送…")

            // 4. Switching to a running session shows follow-up queue placeholder when input is empty
            runOnIdle { store.selectSession(runningSid) }
            waitForIdle()
            onNodeWithTag("composer_placeholder").assertTextEquals("补充下一条指令，结束后自动发送…")
        }
}
