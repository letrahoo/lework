package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.PickedImagePayload
import dev.lework.protocol.*
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class PastedImageDisplayTest {

    private val tinyPngB64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @AfterTest
    fun tearDown() {
        JvmPlatformTestHooks.reset()
    }

    private fun imageCapableEngines(): List<EngineInfo> = listOf(
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
            models = listOf(EngineModelSpec(id = "claude-sonnet-4-6", name = "Claude Sonnet 4.6")),
            defaultModel = "claude-sonnet-4-6",
        ),
    )

    @Test
    fun `pasteImagesFromClipboard notifies only when notifyWhenEmpty is true`() = runTest {
        val transport = FakeHubTransport().apply {
            enginesOverride = imageCapableEngines()
        }
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            // 1. Silent keyboard shortcut path (notifyWhenEmpty = false) leaves promptErrorText untouched
            JvmPlatformTestHooks.nextClipboardImages = emptyList()
            assertFalse(store.pasteImagesFromClipboard(notifyWhenEmpty = false))
            assertNull(store.state.value.promptErrorText)

            // 2. Explicit Mobile touch paste button (notifyWhenEmpty = true) displays clear Chinese guidance
            assertFalse(store.pasteImagesFromClipboard(notifyWhenEmpty = true))
            assertEquals(
                "剪贴板中暂无图片，请先复制图片或截图",
                store.state.value.promptErrorText,
            )

            // 3. Once clipboard has an image, explicit paste attaches it and clears previous error
            JvmPlatformTestHooks.nextClipboardImages = listOf(
                PickedImagePayload(
                    fileName = "mobile_clip.png",
                    mimeType = "image/png",
                    dataBase64 = tinyPngB64,
                    thumbnailBase64 = tinyPngB64,
                ),
            )
            assertTrue(store.pasteImagesFromClipboard(notifyWhenEmpty = true))
            assertEquals(1, store.state.value.composerAttachedImages.size)
            assertEquals("mobile_clip.png", store.state.value.composerAttachedImages.first().fileName)
            assertNull(store.state.value.promptErrorText)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `mobile composer menu pastes clipboard image into thumbnail, sends, and renders user bubble preview`() =
        runComposeUiTest {
            JvmPlatformTestHooks.simulateDesktopLayout = false
            JvmPlatformTestHooks.cameraCaptureSupported = false
            val transport = FakeHubTransport().apply {
                customEngines = imageCapableEngines()
            }
            val sid = "host:agy:mobile_paste_display"
            transport.sessions.add(
                SessionMeta(
                    id = sid,
                    engineId = "claude",
                    cwd = "/workspace/lework",
                    title = "手机剪贴板粘贴验证",
                    state = SessionState.IDLE,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    lastSeq = 0,
                ),
            )
            transport.sessionEventsMap[sid] = mutableListOf(
                AgentEvent.UserMessage(0, sid, 1_000L, "u_init", "准备从手机粘贴截图"),
            )

            lateinit var store: ConsoleStore
            setContent {
                val scope: CoroutineScope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 402.dp, height = 874.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitForIdle()
            runOnIdle { store.selectSession(sid) }
            waitForIdle()

            // 1. Even without camera support, tapping "添加图片" opens dropdown with "粘贴剪贴板图片" & "从相册选择"
            JvmPlatformTestHooks.nextClipboardImages = emptyList()
            onNodeWithTag("composer_add_image_btn").performClick()
            waitForIdle()
            onNodeWithTag("composer_paste_clipboard_image").assertIsDisplayed()
            onNodeWithTag("composer_pick_library").assertIsDisplayed()
            onNodeWithTag("composer_pick_camera").assertDoesNotExist()

            // Tapping paste with empty clipboard displays clear Chinese hint
            onNodeWithTag("composer_paste_clipboard_image").performClick()
            waitForIdle()
            onNodeWithText("剪贴板中暂无图片，请先复制图片或截图").assertIsDisplayed()

            // 2. Populate clipboard image -> tap menu -> tap paste -> thumbnail composer_thumb_0 appears
            JvmPlatformTestHooks.nextClipboardImages = listOf(
                PickedImagePayload(
                    fileName = "phone_shot.png",
                    mimeType = "image/png",
                    dataBase64 = tinyPngB64,
                    thumbnailBase64 = tinyPngB64,
                ),
            )
            onNodeWithTag("composer_add_image_btn").performClick()
            waitForIdle()
            onNodeWithTag("composer_paste_clipboard_image").performClick()
            waitForIdle()
            onNodeWithTag("composer_thumb_0").assertIsDisplayed()
            assertEquals(1, store.state.value.composerAttachedImages.size)

            // 3. Send image -> Hub echoes message.user with imagePreviews -> user bubble renders thumbnail
            onNodeWithTag("composer_send_btn").performClick()
            waitForIdle()
            assertEquals(1, transport.sentPromptImages.size)
            assertEquals(0, store.state.value.composerAttachedImages.size)

            transport.emitEvent(
                AgentEvent.UserMessage(
                    seq = 1,
                    sessionId = sid,
                    ts = 3_000L,
                    messageId = "u_mobile_paste",
                    text = "（已发送 1 张图片）",
                    imageCount = 1,
                    imagePreviews = listOf(tinyPngB64),
                ),
            )
            waitForIdle()

            onNodeWithTag("user_bubble_img_u_mobile_paste_0").assertIsDisplayed()
        }

    @Test
    fun `desktop timeline displays single and multi-image user bubble previews crisply and opens lightbox`() =
        runDesktopComposeUiTest(width = 1320, height = 840) {
            JvmPlatformTestHooks.simulateDesktopLayout = true
            val transport = FakeHubTransport().apply {
                customEngines = imageCapableEngines()
            }
            val sid = "host:agy:desktop_pasted_bubbles"
            transport.sessions.add(
                SessionMeta(
                    id = sid,
                    engineId = "claude",
                    cwd = "/workspace/lework",
                    title = "桌面端用户气泡图片展示验证",
                    state = SessionState.IDLE,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    lastSeq = 1,
                ),
            )
            transport.sessionEventsMap[sid] = mutableListOf(
                AgentEvent.UserMessage(
                    seq = 0,
                    sessionId = sid,
                    ts = 1_500L,
                    messageId = "u_desk_single",
                    text = "看下这张单图截图",
                    imageCount = 1,
                    imagePreviews = listOf(tinyPngB64),
                ),
                AgentEvent.UserMessage(
                    seq = 1,
                    sessionId = sid,
                    ts = 2_000L,
                    messageId = "u_desk_multi",
                    text = "（已发送 2 张图片）",
                    imageCount = 2,
                    imagePreviews = listOf(tinyPngB64, tinyPngB64),
                ),
            )

            lateinit var store: ConsoleStore
            setContent {
                val scope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                ConsoleApp(externalStore = created, autoBootstrap = true)
            }
            waitForIdle()
            runOnIdle { store.selectSession(sid) }
            waitForIdle()

            onNodeWithTag("user_bubble_img_u_desk_single_0").assertIsDisplayed()
            onNodeWithTag("user_bubble_img_u_desk_multi_0").assertIsDisplayed()
            onNodeWithTag("user_bubble_img_u_desk_multi_1").assertIsDisplayed()

            // Verify clicking a user bubble thumbnail opens ImageLightboxDialog cleanly
            onNodeWithTag("user_bubble_img_u_desk_single_0").performClick()
            waitForIdle()
            onNodeWithText("1 / 1").assertIsDisplayed()
        }
}
