package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lework.platform.FilePickOutcome
import dev.lework.platform.JvmDesktopRuntime
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.MAX_ATTACHED_FILE_BYTES
import dev.lework.platform.PickedFilePayload
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.EngineCapabilities
import dev.lework.protocol.EngineInfo
import dev.lework.protocol.EngineKind
import dev.lework.protocol.EngineStatus
import dev.lework.protocol.MessageFileSummary
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import dev.lework.ui.mobileComposerPlaceholder
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalEncodingApi::class)
class FileAttachmentComposerTest {

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

    private fun testEngines(imagesSupported: Boolean = true): List<EngineInfo> = listOf(
        EngineInfo(
            id = "claude",
            displayName = "Claude Code",
            kind = EngineKind.CLI,
            status = EngineStatus(installed = true, ready = true, version = "2.1.328"),
            capabilities = EngineCapabilities(
                permissions = true,
                images = imagesSupported,
                modelSwitch = true,
                modes = true,
                history = true,
                cancel = true,
            ),
        ),
    )

    @Test
    fun mobileComposerSupportsPickingRemovingSendingAndDisplayingFileAttachments() = runComposeUiTest {
        JvmPlatformTestHooks.simulateDesktopLayout = false
        val transport = FakeHubTransport().apply {
            customEngines = testEngines()
        }
        val sid = "sess_mobile_files"
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "claude",
                cwd = "/workspace/lework",
                title = "文件附件测试",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastSeq = 0,
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
        runOnIdle { store.selectSession(sid) }
        waitForIdle()

        // 1. Tap attachment button -> menu exposes both "选择文件" and "从相册选择"
        JvmPlatformTestHooks.nextFilePickOutcome = FilePickOutcome(
            files = listOf(
                PickedFilePayload(
                    fileName = "quarterly-report.pdf",
                    mimeType = "application/pdf",
                    dataBase64 = Base64.encode("%PDF-1.7 report".encodeToByteArray()),
                    sizeBytes = 245_760L,
                    isImage = false,
                ),
                PickedFilePayload(
                    fileName = "schema.json",
                    mimeType = "application/json",
                    dataBase64 = Base64.encode("{\"ok\":true}".encodeToByteArray()),
                    sizeBytes = 2048L,
                    isImage = false,
                ),
            ),
            skippedOversizedCount = 1,
        )

        onNodeWithTag("composer_add_image_btn").performClick()
        waitForIdle()
        onNodeWithTag("composer_pick_files").assertIsDisplayed()
        onNodeWithTag("composer_pick_library").assertIsDisplayed()
        onNodeWithTag("composer_pick_files").performClick()
        waitForIdle()

        // 2. Both file chips appear, placeholder adapts, oversized skip notice is shown
        onNodeWithTag("composer_file_chip_0").assertIsDisplayed()
        onNodeWithTag("composer_file_chip_1").assertIsDisplayed()
        onNodeWithTag("composer_placeholder").assertTextEquals("添加文件说明，或直接发送…")
        assertTrue(store.state.value.promptErrorText?.contains("5 MB") == true)

        // 3. Remove second file chip -> only first remains
        onNodeWithTag("composer_remove_file_1").performClick()
        waitForIdle()
        assertEquals(1, store.state.value.composerAttachedFiles.size)
        assertEquals("quarterly-report.pdf", store.state.value.composerAttachedFiles.first().fileName)

        // 4. Send file-only prompt (empty text) -> dispatches PromptFileAttachment over RPC
        onNodeWithTag("composer_send_btn").performClick()
        waitUntil(timeoutMillis = 3_000L) { transport.sentPromptFiles.isNotEmpty() }
        val sentFiles = transport.sentPromptFiles.single().second
        assertEquals(1, sentFiles.size)
        assertEquals("quarterly-report.pdf", sentFiles.first().fileName)
        assertEquals("application/pdf", sentFiles.first().mimeType)
        assertTrue(store.state.value.composerAttachedFiles.isEmpty())

        // 5. Receiving message.user with files renders file chip in the conversation bubble
        runOnIdle {
            transport.emitEvent(
                AgentEvent.UserMessage(
                    seq = 1,
                    sessionId = sid,
                    ts = 1_730_000_000_000L,
                    messageId = "u_file_1",
                    text = "（已发送 1 个文件）",
                    files = listOf(
                        MessageFileSummary(
                            fileName = "quarterly-report.pdf",
                            mimeType = "application/pdf",
                            sizeBytes = 245_760,
                            stagedPath = "/tmp/lework-attachments-501/$sid/turn_1_f1_quarterly-report.pdf",
                        ),
                    ),
                ),
            )
        }
        waitForIdle()
        onNodeWithTag("user_bubble_file_u_file_1_0", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("quarterly-report.pdf", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun desktopComposerDirectFileButtonAttachesFilesAndRoutesPickedImagesSmartly() = runComposeUiTest {
        JvmPlatformTestHooks.simulateDesktopLayout = true
        val transport = FakeHubTransport().apply {
            customEngines = testEngines()
        }
        val sid = "sess_desktop_files"
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "claude",
                cwd = "/workspace/lework",
                title = "桌面端文件附件",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastSeq = 0,
            ),
        )

        lateinit var store: ConsoleStore
        setContent {
            val scope = rememberCoroutineScope()
            val created = remember { ConsoleStore(scope, transport) }
            store = created
            Box(modifier = Modifier.size(width = 1280.dp, height = 820.dp)) {
                ConsoleApp(externalStore = created, autoBootstrap = true)
            }
        }
        waitUntil(timeoutMillis = 5_000L) { store.state.value.hasEnteredWorkspace }
        runOnIdle { store.selectSession(sid) }
        waitForIdle()

        // Picking an image + a text file from the file picker routes the image to images and file to files
        JvmPlatformTestHooks.nextPickedFiles = listOf(
            PickedFilePayload(
                fileName = "screenshot.png",
                mimeType = "image/png",
                dataBase64 = tinyPngB64,
                sizeBytes = 68L,
                thumbnailBase64 = tinyPngB64,
                isImage = true,
            ),
            PickedFilePayload(
                fileName = "server.log",
                mimeType = "text/plain",
                dataBase64 = Base64.encode("FATAL timeout".encodeToByteArray()),
                sizeBytes = 13L,
                isImage = false,
            ),
        )

        onNodeWithTag("composer_add_file_btn").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, JvmPlatformTestHooks.filePickCallCount)
        assertEquals(1, store.state.value.composerAttachedImages.size)
        assertEquals(1, store.state.value.composerAttachedFiles.size)
        onNodeWithTag("composer_thumb_0").assertIsDisplayed()
        onNodeWithTag("composer_file_chip_0").assertIsDisplayed()
        assertEquals(
            "添加附件说明，或直接发送…",
            mobileComposerPlaceholder("claude", hasAttachedImages = true, canQueueFollowUp = false, hasAttachedFiles = true),
        )
    }

    @Test
    fun jvmLocalFileEncoderHandlesNormalAndOversizedFiles() {
        val dir = Files.createTempDirectory("lework-file-enc-").toFile()
        try {
            val mdFile = File(dir, "README.md").apply { writeText("# Hello Lework") }
            val bigFile = File(dir, "huge.bin").apply {
                writeBytes(ByteArray((MAX_ATTACHED_FILE_BYTES + 16L).toInt()))
            }

            val outcome = JvmDesktopRuntime.encodePickedLocalFiles(listOf(mdFile, bigFile), maxCount = 4)
            assertEquals(1, outcome.files.size)
            assertEquals("README.md", outcome.files.first().fileName)
            assertEquals("text/markdown", outcome.files.first().mimeType)
            assertEquals(14L, outcome.files.first().sizeBytes)
            assertEquals(false, outcome.files.first().isImage)
            assertNotNull(outcome.files.first().dataBase64)
            assertEquals(1, outcome.skippedOversizedCount)
            assertEquals(0, outcome.skippedUnreadableCount)
        } finally {
            dir.deleteRecursively()
        }
    }
}
