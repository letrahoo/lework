package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.MobileTsnetStatus
import dev.lework.platform.PairedHost
import dev.lework.protocol.*
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleStore
import dev.lework.store.ConsoleUiState
import dev.lework.store.PendingDeepLinkPairing
import dev.lework.ui.DiffAndCheckpointsSheet
import dev.lework.ui.Everforest
import dev.lework.ui.LocalModalEscapeDispatcher
import dev.lework.ui.ModalEscapeDispatcher
import dev.lework.ui.TasksSidebarContent
import dev.lework.ui.WorkspaceFileTreeSheet
import dev.lework.ui.desktop.DesktopAuthScreen
import dev.lework.ui.desktop.DesktopModelPickerDialog
import dev.lework.ui.desktop.DesktopSettingsDialog
import dev.lework.ui.desktop.DesktopSidebar
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class UxPrinciplesAndHostSwitcherTest {

    private fun testCapabilities(): EngineCapabilities = EngineCapabilities(
        permissions = true,
        images = false,
        modelSwitch = true,
        modes = true,
        history = true,
        cancel = true,
    )

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @AfterTest
    fun tearDown() {
        JvmPlatformTestHooks.reset()
    }

    @Test
    fun desktopHostSwitcherShowsHeaderRightConnectOtherButtonAndDismissesOnEscape() = runComposeUiTest {
        val dispatcher = ModalEscapeDispatcher()
        var pairOtherClicks = 0
        val state = ConsoleUiState(
            hasEnteredWorkspace = true,
            connectionStatus = ConnectionStatus.CONNECTED,
            hub = HubInfo(
                id = "hub_mac",
                name = "MacBook Pro",
                role = HubRole.SLAVE,
                platform = PlatformType.DARWIN,
                version = "0.8.22",
            ),
            pairedHosts = listOf(
                PairedHost(
                    hubId = "hub_mac",
                    name = "MacBook Pro",
                    endpoints = listOf("https://127.0.0.1:64450"),
                ),
            ),
            discoveredBaseUrl = "https://127.0.0.1:64450",
        )

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalModalEscapeDispatcher provides dispatcher) {
                    androidx.compose.material3.Surface(
                        color = Everforest.BgDim,
                        modifier = Modifier
                            .size(width = 300.dp, height = 640.dp)
                            .testTag("ux_sidebar_capture_root"),
                    ) {
                        DesktopSidebar(
                            state = state,
                            onNewSession = {},
                            onOpenUsage = {},
                            onOpenSettings = {},
                            onCollapseSidebar = {},
                            onSelectFilter = {},
                            onSelectSession = {},
                            onRefresh = {},
                            onSwitchHost = {},
                            onPairAnotherHost = { pairOtherClicks++ },
                            onSearchQueryChange = {},
                            onToggleArchivedView = {},
                            onTogglePinSession = {},
                            onRequestRenameSession = {},
                            onToggleArchiveSession = { _, _ -> },
                        )
                    }
                }
            }
        }

        waitForIdle()
        onAllNodesWithTag("desktop_host_switcher_panel").assertCountEquals(0)

        onNodeWithTag("drawer_host_switcher_btn").performClick()
        waitForIdle()
        onAllNodesWithTag("desktop_host_switcher_panel").assertCountEquals(1)
        onNodeWithText("Lework Hub 主机").assertExists()
        onNodeWithTag("host_switcher_btn_connect_other").assertTextContains("连接其他", substring = true)

        System.getenv("LEWORK_CAPTURE_DIR")?.takeIf { it.isNotBlank() }?.let { dir ->
            val outDir = File(dir).apply { mkdirs() }
            SkiaImage.makeFromBitmap(onNodeWithTag("ux_sidebar_capture_root").captureToImage().asSkiaBitmap())
                .encodeToData(EncodedImageFormat.PNG)
                ?.let { File(outDir, "desktop_host_switcher_expanded.png").writeBytes(it.bytes) }
        }

        // Pressing Esc via dispatcher must close the expanded switcher panel cleanly
        assertTrue(dispatcher.dispatchEscape())
        waitForIdle()
        onAllNodesWithTag("desktop_host_switcher_panel").assertCountEquals(0)

        // Re-open and click right-side Connect Other button
        onNodeWithTag("drawer_host_switcher_btn").performClick()
        waitForIdle()
        onNodeWithTag("host_switcher_btn_connect_other").performClick()
        waitForIdle()
        assertEquals(1, pairOtherClicks)
        onAllNodesWithTag("desktop_host_switcher_panel").assertCountEquals(0)
    }

    @Test
    fun mobileDrawerShowsUnifiedHostSwitcherHeaderAndRequiresConfirmationToDisconnect() = runComposeUiTest {
        var pairOtherClicks = 0
        var logoutClicks = 0
        val state = ConsoleUiState(
            hasEnteredWorkspace = true,
            connectionStatus = ConnectionStatus.CONNECTED,
            hub = HubInfo(
                id = "hub_mac",
                name = "MacBook Pro",
                role = HubRole.SLAVE,
                platform = PlatformType.DARWIN,
                version = "0.8.22",
            ),
            pairedHosts = listOf(
                PairedHost(
                    hubId = "hub_mac",
                    name = "MacBook Pro",
                    endpoints = listOf("https://100.88.1.2:64450"),
                ),
            ),
        )

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TasksSidebarContent(
                        state = state,
                        onNewSession = {},
                        onOpenUsage = {},
                        onSelectFilter = {},
                        onSelectSession = {},
                        onRefresh = {},
                        onLogout = { logoutClicks++ },
                        onPairAnotherHost = { pairOtherClicks++ },
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithTag("drawer_host_switcher_btn").performClick()
        waitForIdle()
        onAllNodesWithTag("mobile_host_switcher_panel", useUnmergedTree = true).assertCountEquals(1)
        onNodeWithText("Lework Hub 主机", useUnmergedTree = true).assertExists()
        onNodeWithTag("host_switcher_btn_connect_other").performClick()
        waitForIdle()
        assertEquals(1, pairOtherClicks)

        // Clicking disconnect requires confirmation before invoking onLogout
        onNodeWithTag("drawer_btn_logout").performClick()
        waitForIdle()
        assertEquals(0, logoutClicks)
        onNodeWithTag("drawer_confirm_logout_btn").performClick()
        waitForIdle()
        assertEquals(1, logoutClicks)
    }

    @Test
    fun preparePairAnotherHostIsNonDestructiveAndRestoresWorkspaceAndDraftOnCancelOrSameHostPick() = runTest {
        val transport = FakeHubTransport()
        val sess = SessionMeta(
            id = "host:claude:sess_keep",
            engineId = "claude",
            cwd = "/workspace/lework",
            title = "Keep Active Work",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 0,
        )
        transport.sessions.add(sess)

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            assertTrue(store.state.value.hasEnteredWorkspace)

            store.selectSession(sess.id)
            runCurrent()
            store.updateSessionDraft("未发送的重要草稿")
            assertEquals("未发送的重要草稿", store.state.value.activeDraftText)

            // Open "Connect Another Lework Hub Host" screen
            store.preparePairAnotherHost()
            runCurrent()
            assertFalse(store.state.value.hasEnteredWorkspace)
            assertTrue(store.state.value.canReturnToWorkspace)
            assertEquals("TestMacBook", store.state.value.returnWorkspaceHostName)

            // Cancel and return to workspace -> session, draft, and connection stay intact
            store.cancelPairAnotherHost()
            runCurrent()
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertFalse(store.state.value.canReturnToWorkspace)
            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)
            assertEquals(sess.id, store.state.value.activeSessionId)
            assertEquals("未发送的重要草稿", store.state.value.activeDraftText)

            // Open again and click the already-connected host -> instant return
            store.preparePairAnotherHost()
            runCurrent()
            assertFalse(store.state.value.hasEnteredWorkspace)
            store.switchPairedHost(transport.hubInfo.id)
            runCurrent()
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertFalse(store.state.value.canReturnToWorkspace)
            assertEquals(sess.id, store.state.value.activeSessionId)
            assertEquals("未发送的重要草稿", store.state.value.activeDraftText)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun desktopAuthScreenSupportsReturnBarClipboardQuickPairSmartManualFormAndConfirmations() = runComposeUiTest {
        var returned = 0
        var quickPaired = 0
        var resetConfirmed = 0
        var tsnetLoggedOut = 0
        val state = ConsoleUiState(
            hasEnteredWorkspace = false,
            connectionStatus = ConnectionStatus.ERROR,
            connectionError = "连接超时，请检查主机状态",
            canReturnToWorkspace = true,
            returnWorkspaceHostName = "WorkMac",
            clipboardPairingPreview = PendingDeepLinkPairing(
                rawUri = "lework://pair?host=https://100.88.12.34:64450&code=abc",
                hostName = "RemoteLinux",
                primaryUrl = "https://100.88.12.34:64450",
                shortFingerprint = "abcd1234",
            ),
            tsnetStatus = MobileTsnetStatus(
                available = true,
                backendState = "Running",
                tailscaleIps = listOf("100.88.12.1"),
                displayName = "Owner",
            ),
        )

        setContent {
            MaterialTheme {
                DesktopAuthScreen(
                    state = state,
                    onLaunchOAuth = {},
                    onOpenBrowser = {},
                    onManualConnect = { _, _ -> },
                    onRetry = {},
                    onResetCredentials = { resetConfirmed++ },
                    onPasteClipboardPairing = {},
                    onConfirmClipboardPairing = { quickPaired++ },
                    onDismissClipboardPairing = {},
                    onPairViaSsh = {},
                    onStartTsnetLogin = {},
                    onSearchTsnetPeers = {},
                    onLogoutTsnet = { tsnetLoggedOut++ },
                    onSelectPairedHost = {},
                    onCancelPairAnotherHost = { returned++ },
                )
            }
        }

        waitForIdle()
        System.getenv("LEWORK_CAPTURE_DIR")?.takeIf { it.isNotBlank() }?.let { dir ->
            val outDir = File(dir).apply { mkdirs() }
            SkiaImage.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
                .encodeToData(EncodedImageFormat.PNG)
                ?.let { File(outDir, "desktop_auth_return_and_clipboard.png").writeBytes(it.bytes) }
        }
        // 1. Return bar
        onNodeWithTag("auth_btn_return_workspace").assertTextContains("返回「WorkMac」").performClick()
        waitForIdle()
        assertEquals(1, returned)

        // 2. Clipboard auto-detected quick pair card
        onAllNodesWithTag("auth_clipboard_quick_pair_card").assertCountEquals(1)
        onNodeWithTag("auth_clipboard_quick_pair_btn").performClick()
        waitForIdle()
        assertEquals(1, quickPaired)

        // 3. Smart manual form hides token input when pasting a lework:// pairing link
        onNodeWithTag("auth_btn_toggle_manual").performScrollTo().performClick()
        waitForIdle()
        onAllNodesWithTag("auth_manual_token_input").assertCountEquals(1)
        onNodeWithTag("auth_manual_host_input").performScrollTo().performTextInput("lework://pair?host=https://10.0.0.2:64450&code=abc")
        waitForIdle()
        onAllNodesWithTag("auth_manual_token_input").assertCountEquals(0)
        onNodeWithTag("auth_manual_submit_btn").assertTextContains("验证并配对")

        // 4. Reset credentials requires confirmation
        onNodeWithTag("auth_btn_reset_credentials").performScrollTo().performClick()
        waitForIdle()
        assertEquals(0, resetConfirmed)
        onNodeWithTag("confirm_reset_credentials_btn").performClick()
        waitForIdle()
        assertEquals(1, resetConfirmed)

        // 5. Tsnet logout requires confirmation
        onNodeWithTag("auth_btn_logout_tsnet").performScrollTo().performClick()
        waitForIdle()
        assertEquals(0, tsnetLoggedOut)
        onNodeWithTag("confirm_logout_tsnet_btn").performClick()
        waitForIdle()
        assertEquals(1, tsnetLoggedOut)
    }

    @Test
    fun archiveSessionOffersUndoNoticeAndTriggerRestoresSession() = runTest {
        val transport = FakeHubTransport()
        val sess = SessionMeta(
            id = "host:claude:sess_arch",
            engineId = "claude",
            cwd = "/workspace/lework",
            title = "需要归档的任务",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 0,
        )
        transport.sessions.add(sess)

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            store.archiveSession(sess.id, archived = true)
            runCurrent()

            assertTrue(store.state.value.sessions.first { it.id == sess.id }.archived)
            assertEquals("撤销", store.state.value.promptNoticeActionLabel)
            assertEquals(sess.id, store.state.value.promptNoticeUndoArchiveSessionId)
            assertTrue(store.state.value.promptNoticeText.orEmpty().contains("已归档"))

            store.triggerPromptNoticeAction()
            runCurrent()

            assertFalse(store.state.value.sessions.first { it.id == sess.id }.archived)
            assertNull(store.state.value.promptNoticeActionLabel)
            assertNull(store.state.value.promptNoticeUndoArchiveSessionId)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun cancellingSingleQueuedPromptOffersOneClickRestoreWithoutPollutingComposer() = runTest {
        val transport = FakeHubTransport()
        val sess = SessionMeta(
            id = "host:claude:sess_q",
            engineId = "claude",
            cwd = "/workspace/lework",
            title = "Queue Restore",
            state = SessionState.RUNNING,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 0,
            queuedPrompts = listOf("帮我补一下单测", "再跑一遍构建"),
        )
        transport.sessions.add(sess)

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sess.id)
            runCurrent()

            // Cancelling keeps composer clean and offers 1-click "放回输入框"
            store.cancelQueuedPrompt(0)
            runCurrent()
            assertEquals("", store.state.value.activeDraftText)
            assertEquals("已取消排队消息", store.state.value.promptNoticeText)
            assertEquals("放回输入框", store.state.value.promptNoticeActionLabel)

            // Triggering the action puts the cancelled message back into the blank composer
            store.triggerPromptNoticeAction()
            runCurrent()
            assertEquals("帮我补一下单测", store.state.value.activeDraftText)
            assertNull(store.state.value.promptNoticeText)

            // If user already typed in composer, restoring appends instead of overwriting
            store.updateSessionDraft("用户正在写的新指令")
            store.cancelQueuedPrompt(0)
            runCurrent()
            assertEquals("用户正在写的新指令", store.state.value.activeDraftText)
            store.triggerPromptNoticeAction()
            runCurrent()
            assertEquals("用户正在写的新指令\n再跑一遍构建", store.state.value.activeDraftText)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun browseDirectorySurfacesErrorAndClearsOnRetry() = runTest {
        val transport = FakeHubTransport()
        transport.listDirectoryHandler = { throw RuntimeException("Permission denied") }

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            store.browseDirectory("/restricted")
            runCurrent()
            assertFalse(store.state.value.fsLoading)
            assertNotNull(store.state.value.fsError)
            assertTrue(store.state.value.fsError!!.contains("Permission denied"))

            transport.listDirectoryHandler = { path -> FsListResult(path = path, entries = emptyList()) }
            store.browseDirectory("/workspace/lework")
            runCurrent()
            assertFalse(store.state.value.fsLoading)
            assertNull(store.state.value.fsError)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun nonGitDiffHidesSnapshotTabsAndCreateCheckpointButton() = runComposeUiTest {
        val nonGitState = ConsoleUiState(
            hasEnteredWorkspace = true,
            selectedWorkspacePath = "/tmp/plain",
            currentDiffWorkspacePath = "/tmp/plain",
            gitStatus = GitStatusResult(
                isGitRepo = false,
                path = "/tmp/plain",
                files = emptyList(),
            ),
        )

        setContent {
            MaterialTheme {
                DiffAndCheckpointsSheet(
                    state = nonGitState,
                    onSelectWorkspace = {},
                    onRefresh = {},
                    onOpenFileDiff = { _, _, _ -> },
                    onCloseDiff = {},
                    onCreateCheckpoint = {},
                    onRestoreCheckpoint = {},
                    showHeaderTitle = false,
                )
            }
        }
        waitForIdle()
        onAllNodesWithTag("diff_segmented_tabs").assertCountEquals(0)
        onAllNodesWithTag("btn_create_checkpoint").assertCountEquals(0)
    }

    @Test
    fun workspaceFilesSheetRendersErrorAndRetryAction() = runComposeUiTest {
        var retriedPath: String? = null
        setContent {
            MaterialTheme {
                WorkspaceFileTreeSheet(
                    state = ConsoleUiState(
                        selectedWorkspacePath = "/tmp/empty",
                        currentBrowsePath = "/tmp/empty",
                        fsLoading = false,
                        fsError = "无法读取目录：权限不足",
                        dirEntries = emptyList(),
                    ),
                    onNavigateUp = {},
                    onOpenEntry = {},
                    onCloseFile = {},
                    onSelectActiveWorkspaceTab = { retriedPath = it },
                    onSwitchWorkspace = {},
                )
            }
        }
        waitForIdle()
        onAllNodesWithTag("workspace_files_error_state").assertCountEquals(1)
        onNodeWithText("重试").performClick()
        waitForIdle()
        assertEquals("/tmp/empty", retriedPath)
    }

    @Test
    fun workspaceFilesSheetRendersEmptyDirectoryState() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WorkspaceFileTreeSheet(
                    state = ConsoleUiState(
                        selectedWorkspacePath = "/tmp/empty",
                        currentBrowsePath = "/tmp/empty",
                        fsLoading = false,
                        fsError = null,
                        dirEntries = emptyList(),
                    ),
                    onNavigateUp = {},
                    onOpenEntry = {},
                    onCloseFile = {},
                    onSelectActiveWorkspaceTab = {},
                    onSwitchWorkspace = {},
                )
            }
        }
        waitForIdle()
        onAllNodesWithTag("workspace_files_empty_state").assertCountEquals(1)
        onNodeWithText("此目录为空").assertExists()
    }

    @Test
    fun desktopModelPickerSupportsArrowNavigationAndEnterSelection() = runComposeUiTest {
        val modelA = EngineModelSpec(
            id = "claude-sonnet-4-5",
            name = "Claude Sonnet 4.5",
            efforts = listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            defaultEffort = ReasoningEffort.HIGH,
        )
        val modelB = EngineModelSpec(
            id = "claude-opus-4-6",
            name = "Claude Opus 4.6",
            efforts = listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            defaultEffort = ReasoningEffort.HIGH,
        )
        val claudeEngine = EngineInfo(
            id = "claude",
            displayName = "Claude Code",
            kind = EngineKind.CLI,
            status = EngineStatus(installed = true, ready = true),
            capabilities = testCapabilities(),
            models = listOf(modelA, modelB),
            defaultModel = modelA.id,
        )
        var pickedModelId: String? = null

        setContent {
            MaterialTheme {
                DesktopModelPickerDialog(
                    state = ConsoleUiState(
                        hasEnteredWorkspace = true,
                        connectionStatus = ConnectionStatus.CONNECTED,
                        engines = listOf(claudeEngine),
                        selectedEngineId = "claude",
                        selectedModelId = modelA.id,
                    ),
                    onSelectEngineAndModel = { _, spec, _ -> pickedModelId = spec?.id },
                    onSelectEffort = {},
                    onSelectMode = {},
                    onToggleFavoriteModel = { _, _, _ -> },
                    onDismiss = {},
                )
            }
        }

        waitForIdle()
        onNodeWithTag("model_picker_search_input").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()
        assertEquals(modelB.id, pickedModelId)
    }

    @Test
    fun desktopSettingsDialogRequiresLogoutConfirmationAndFoldsProviderAdvancedOptions() = runComposeUiTest {
        var logoutClicks = 0
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    DesktopSettingsDialog(
                        state = ConsoleUiState(
                            hasEnteredWorkspace = true,
                            connectionStatus = ConnectionStatus.CONNECTED,
                            hub = HubInfo(
                                id = "hub_mac",
                                name = "MacBook Pro",
                                role = HubRole.SLAVE,
                                platform = PlatformType.DARWIN,
                                version = "0.8.22",
                            ),
                            engines = listOf(
                                EngineInfo(
                                    id = "claude",
                                    displayName = "Claude Code",
                                    kind = EngineKind.CLI,
                                    status = EngineStatus(installed = true, ready = true),
                                    capabilities = testCapabilities(),
                                ),
                            ),
                        ),
                        onDismiss = {},
                        onSwitchHost = {},
                        onRemoveHost = {},
                        onPairAnotherHost = {},
                        onLogout = { logoutClicks++ },
                    )
                }
            }
        }

        waitForIdle()
        // Open add provider editor -> advanced options are collapsed by default and can be toggled open
        onNodeWithTag("settings_btn_add_provider").performScrollTo().performClick()
        waitForIdle()
        onAllNodesWithTag("provider_editor_dialog").assertCountEquals(1)
        onNodeWithTag("provider_toggle_advanced").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("provider_field_env").assertExists()
        // Dismiss editor via Esc
        onNodeWithTag("provider_editor_dialog").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()
        onAllNodesWithTag("provider_editor_dialog").assertCountEquals(0)

        // Disconnect requires explicit confirmation
        onNodeWithTag("settings_btn_logout").performClick()
        waitForIdle()
        assertEquals(0, logoutClicks)
        onNodeWithTag("settings_confirm_logout_btn").performClick()
        waitForIdle()
        assertEquals(1, logoutClicks)
    }

    @Test
    fun repeatedPreparePairAnotherHostPreservesReturnSnapshotAndRollsBackFailedPairingIdentity() = runTest {
        val transport = FakeHubTransport()
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            assertTrue(store.state.value.hasEnteredWorkspace)
            val originalHostId = JvmPlatformTestHooks.activeHostId
            val originalToken = JvmPlatformTestHooks.savedToken

            // Pressing ⇧⌘P twice must not wipe previousWorkspaceSnapshot
            store.preparePairAnotherHost()
            runCurrent()
            store.preparePairAnotherHost()
            runCurrent()
            assertTrue(store.state.value.canReturnToWorkspace)

            // Simulate a pairing attempt to host B whose WS connect fails after saving auth
            transport.connectHelloException = RuntimeException("WS unreachable")
            store.redeemPairingInput("lework://pair?url=https://10.0.0.9:64450&code=abc&name=HostB")
            runCurrent()
            assertEquals(ConnectionStatus.ERROR, store.state.value.connectionStatus)

            // Cancel & return to A -> persisted activeHostId and savedToken must roll back to A
            transport.connectHelloException = null
            store.cancelPairAnotherHost()
            runCurrent()
            assertTrue(store.state.value.hasEnteredWorkspace)
            assertEquals(originalHostId, JvmPlatformTestHooks.activeHostId)
            assertEquals(originalToken, JvmPlatformTestHooks.savedToken)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun retryLastFsOperationRetriesFailedFileReadAndBrowseClearsStaleEntriesOnPathChange() = runTest {
        val transport = FakeHubTransport()
        val fileEntry = FsEntry(name = "README.md", path = "/workspace/lework/README.md", isDirectory = false, size = 12L)
        transport.listDirectoryHandler = { path ->
            if (path == "/dirA") {
                FsListResult(path = path, entries = listOf(fileEntry))
            } else {
                FsListResult(path = path, entries = emptyList())
            }
        }
        var readAttempts = 0
        transport.readFileHandler = { path ->
            readAttempts++
            if (readAttempts == 1) {
                throw RuntimeException("Transient IO failure")
            }
            FsReadResult(path = path, content = "# Lework", truncated = false)
        }

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            store.browseDirectory("/dirA")
            runCurrent()
            assertEquals(1, store.state.value.dirEntries.size)

            // Failing file read records failed entry; retryLastFsOperation re-reads the file instead of listing dir
            store.openEntry(fileEntry)
            runCurrent()
            assertNotNull(store.state.value.fsError)
            assertNull(store.state.value.openedFileContent)

            store.retryLastFsOperation()
            runCurrent()
            assertEquals(2, readAttempts)
            assertNull(store.state.value.fsError)
            assertEquals("# Lework", store.state.value.openedFileContent)

            // Switching to /dirB clears /dirA entries
            store.browseDirectory("/dirB")
            runCurrent()
            assertEquals("/dirB", store.state.value.currentBrowsePath)
            assertTrue(store.state.value.dirEntries.isEmpty())
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun ollamaPresetDoesNotFallbackToIncompatibleClaudeEngine() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    DesktopSettingsDialog(
                        state = ConsoleUiState(
                            hasEnteredWorkspace = true,
                            connectionStatus = ConnectionStatus.CONNECTED,
                            engines = listOf(
                                EngineInfo(
                                    id = "claude",
                                    displayName = "Claude Code",
                                    kind = EngineKind.CLI,
                                    status = EngineStatus(installed = true, ready = true),
                                    capabilities = testCapabilities(),
                                ),
                            ),
                        ),
                        onDismiss = {},
                        onSwitchHost = {},
                        onRemoveHost = {},
                        onPairAnotherHost = {},
                        onLogout = {},
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithTag("settings_btn_add_provider").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Ollama").performClick()
        waitForIdle()
        onNodeWithText("当前主机未检测到与该预设协议兼容的引擎", substring = true).assertExists()
    }
}
