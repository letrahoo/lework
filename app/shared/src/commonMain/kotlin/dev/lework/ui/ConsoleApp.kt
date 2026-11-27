package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.isDesktopPlatform
import dev.lework.protocol.*
import dev.lework.store.*
import dev.lework.ui.desktop.DesktopConsoleApp
import kotlinx.coroutines.launch

/**
 * Top-level UI router separating Mobile (iOS / Android touch shell) and Desktop (macOS keyboard/mouse shell)
 * into two completely independent presentation systems sharing only [ConsoleStore] & domain renderers.
 */
@Composable
fun ConsoleApp(
    externalStore: ConsoleStore? = null,
    autoBootstrap: Boolean = true,
    openSettingsSignal: Int = 0,
    toggleSidebarSignal: Int = 0,
    openGlobalSearchSignal: Int = 0,
    openInSessionFindSignal: Int = 0,
    registerGlobalCliSignal: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val store = remember(externalStore) { externalStore ?: ConsoleStore(scope) }
    val state by store.state.collectAsState()
    val isDesktop = remember { isDesktopPlatform() }

    LaunchedEffect(store, autoBootstrap) {
        if (autoBootstrap) {
            store.bootstrapOrConnect()
        }
    }

    MaterialTheme(colorScheme = EverforestColorScheme) {
        CompositionLocalProvider(LocalTextSelectionColors provides EverforestTextSelectionColors) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Everforest.Bg0,
            ) {
                if (isDesktop) {
                    DesktopConsoleApp(
                        store = store,
                        state = state,
                        openSettingsSignal = openSettingsSignal,
                        toggleSidebarSignal = toggleSidebarSignal,
                        openGlobalSearchSignal = openGlobalSearchSignal,
                        openInSessionFindSignal = openInSessionFindSignal,
                        registerGlobalCliSignal = registerGlobalCliSignal,
                    )
                } else {
                    MobileConsoleApp(
                        store = store,
                        state = state,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileConsoleApp(
    store: ConsoleStore,
    state: ConsoleUiState,
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val dismissKeyboard = rememberKeyboardDismisser()
    var showModelSheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showFilesSheet by remember { mutableStateOf(false) }
    var showUsageSheet by remember { mutableStateOf(false) }
    var showDiffSheet by remember { mutableStateOf(false) }
    var showArtifactsSheet by remember { mutableStateOf(false) }
    var renamingSessionId by remember { mutableStateOf<String?>(null) }
    val activeDraftKey = state.activeDraftKey
    val activeDraftText = state.activeDraftText
    var promptField by remember(activeDraftKey) {
        mutableStateOf(TextFieldValue(text = activeDraftText, selection = TextRange(activeDraftText.length)))
    }
    var cursorSyncRequest by remember(activeDraftKey) { mutableStateOf<Triple<Long, String, Int>?>(null) }

    LaunchedEffect(activeDraftKey, activeDraftText) {
        if (promptField.text != activeDraftText) {
            cursorSyncRequest = null
            promptField = TextFieldValue(
                text = activeDraftText,
                selection = TextRange(activeDraftText.length),
                composition = null,
            )
        }
    }

    LaunchedEffect(cursorSyncRequest) {
        val req = cursorSyncRequest ?: return@LaunchedEffect
        withFrameNanos {}
        if (promptField.text == req.second &&
            (promptField.selection != TextRange(req.third) || promptField.composition != null)
        ) {
            promptField = TextFieldValue(
                text = req.second,
                selection = TextRange(req.third),
                composition = null,
            )
        }
        withFrameNanos {}
        if (cursorSyncRequest?.first == req.first) {
            cursorSyncRequest = null
        }
    }

    // Automatically resign software keyboard & focus whenever the navigation drawer opens or closes
    // (covers both tapping the top-left Menu button AND edge-swipe drawer gestures).
    LaunchedEffect(drawerState.targetValue, drawerState.isOpen) {
        if (drawerState.targetValue == DrawerValue.Open || drawerState.isOpen) {
            dismissKeyboard()
        } else if (drawerState.targetValue == DrawerValue.Closed && !drawerState.isOpen) {
            dismissKeyboard()
        }
    }

    // Automatically resign software keyboard before any modal bottom sheet or pairing dialog appears
    val anyOverlaySheetOpen =
        showModelSheet || showWorkspaceSheet || showFilesSheet || showUsageSheet || showDiffSheet ||
            showArtifactsSheet || state.pendingDeepLinkPairing != null ||
            state.inspectedSubagent != null || state.activeFilePreview != null
    LaunchedEffect(anyOverlaySheetOpen) {
        if (anyOverlaySheetOpen) {
            dismissKeyboard()
        }
    }

    LaunchedEffect(state.unsentDraftToRestore) {
        val restore = state.unsentDraftToRestore
        if (!restore.isNullOrEmpty()) {
            if (state.activeDraftText.isBlank()) {
                store.updateSessionDraft(restore, updateMentions = false)
            }
            store.consumeRestoredDraft()
        }
    }

    if (!state.hasEnteredWorkspace) {
        AuthEntryView(
            state = state,
            onLaunchOAuth = store::launchOAuthApp,
            onOpenBrowser = store::openOAuthBrowserFallback,
            onManualConnect = store::connectManualEndpoint,
            onRetry = store::bootstrapOrConnect,
            onResetCredentials = store::resetCredentialsAndReconnect,
            onScanQr = store::scanQrAndPair,
            onStartTsnetLogin = store::startMobileTsnetLogin,
            onSearchTsnetPeers = store::searchAndConnectViaTsnet,
            onLogoutTsnet = store::logoutMobileTsnetAccount,
            onSelectPairedHost = store::switchPairedHost,
        )
    } else {
        CompositionLocalProvider(
            LocalFileLinkClickHandler provides { rawTarget ->
                dismissKeyboard()
                store.openLinkedFilePreview(rawTarget)
            },
        ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Everforest.BgDim,
                    drawerContentColor = Everforest.Fg,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.width(316.dp),
                ) {
                    // Avoid recomposing 60+ off-screen drawer rows on every streaming event while closed
                    if (drawerState.isOpen || drawerState.currentValue == DrawerValue.Open || drawerState.targetValue == DrawerValue.Open) {
                        TasksSidebarContent(
                            state = state,
                            onNewSession = {
                                store.startNewDraftSession()
                                scope.launch { drawerState.close() }
                            },
                            onOpenUsage = {
                                store.refreshUsageDashboard()
                                showUsageSheet = true
                                scope.launch { drawerState.close() }
                            },
                            onSelectFilter = store::setDrawerEngineFilter,
                            onSelectSession = { sid ->
                                store.selectSession(sid)
                                scope.launch { drawerState.close() }
                            },
                            onRefresh = store::refreshHostSessions,
                            onLogout = store::logout,
                            onSwitchHost = { hubId ->
                                scope.launch { drawerState.close() }
                                store.switchPairedHost(hubId)
                            },
                            onRenameHost = store::renamePairedHost,
                            onPairAnotherHost = {
                                scope.launch { drawerState.close() }
                                store.preparePairAnotherHost()
                            },
                            onDismissSigningReminder = store::dismissSigningExpiryReminder,
                            onSearchQueryChange = store::setDrawerSearchQuery,
                            onToggleArchivedView = store::setDrawerShowArchived,
                            onRequestRenameSession = { sess -> renamingSessionId = sess.id },
                            onToggleArchiveSession = store::archiveSession,
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Everforest.BgDim))
                    }
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Everforest.Bg0)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                MinimalTopBar(
                    state = state,
                    onOpenDrawer = {
                        dismissKeyboard()
                        store.refreshHostSessions()
                        scope.launch { drawerState.open() }
                    },
                    onOpenModelSheet = {
                        dismissKeyboard()
                        showModelSheet = true
                    },
                    onOpenDiffSheet = {
                        dismissKeyboard()
                        store.refreshDiffAndCheckpoints()
                        showDiffSheet = true
                    },
                    onOpenFilesSheet = {
                        dismissKeyboard()
                        store.browseDirectory()
                        showFilesSheet = true
                    },
                )

                val topActiveSess = state.activeSession?.takeIf { it.id == state.activeSessionId }
                if (topActiveSess != null) {
                    ActiveSessionTitleStrip(
                        session = topActiveSess,
                        artifactCount = state.activeSessionArtifacts.size,
                        onOpenArtifacts = {
                            dismissKeyboard()
                            store.refreshSessionArtifacts()
                            showArtifactsSheet = true
                        },
                        onRename = {
                            dismissKeyboard()
                            renamingSessionId = topActiveSess.id
                        },
                        onToggleArchive = {
                            dismissKeyboard()
                            store.archiveSession(topActiveSess.id, !topActiveSess.archived)
                        },
                    )
                    ArtifactsQuickBar(
                        state = state,
                        onOpenArtifacts = { artId ->
                            dismissKeyboard()
                            store.refreshSessionArtifacts()
                            if (artId != null) store.openArtifact(artId)
                            showArtifactsSheet = true
                        },
                    )
                }

                if (state.connectionStatus == ConnectionStatus.RECONNECTING) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Everforest.Bg1,
                        border = BorderStroke(1.dp, Everforest.BoldGold.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                color = Everforest.BoldGold,
                                strokeWidth = 1.8.dp,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = "正在校验权限并续连当前会话上下文…",
                                fontSize = 12.sp,
                                color = Everforest.BoldGold,
                            )
                        }
                    }
                }

                val activeSubagents = topActiveSess?.subagents.orEmpty()
                if (topActiveSess != null && activeSubagents.isNotEmpty()) {
                    SubagentStatusStrip(
                        sessionId = topActiveSess.id,
                        subagents = activeSubagents,
                        onSelectSubagent = { sub ->
                            dismissKeyboard()
                            store.openSubagentInspector(sub)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { dismissKeyboard() },
                ) {
                    val activeKey = state.activeSessionId ?: "draft"
                    val activeSess = state.activeSession?.takeIf { it.id == state.activeSessionId }
                    val transcript = state.activeTranscript
                    if (state.activeSessionId == null && transcript.items.isEmpty()) {
                        CalmHomeEmptyState(
                            state = state,
                            onOpenRunningSession = { sid ->
                                dismissKeyboard()
                                store.selectSession(sid)
                            },
                        )
                    } else if (
                        state.activeSessionId != null &&
                        transcript.items.isEmpty() &&
                        state.loadingSessionId == state.activeSessionId
                    ) {
                        ConversationLoadingSkeleton(title = activeSess?.title)
                    } else {
                        val sessRunning = activeSess != null &&
                            (activeSess.state == SessionState.RUNNING || activeSess.state == SessionState.WAITING_PERMISSION)
                        val planArtifact = state.activeSessionArtifacts.firstOrNull {
                            it.actionable || it.kind == ArtifactKind.PLAN
                        }
                        val awaitingPlanApproval = !sessRunning && (
                            state.selectedMode == SessionMode.PLAN ||
                                (planArtifact != null &&
                                    activeSess?.state == SessionState.IDLE &&
                                    transcript.planEntries.isNotEmpty() &&
                                    transcript.planEntries.all { it.status == "pending" })
                        )
                        key(activeKey) {
                            ConversationStreamView(
                                sessionKey = activeKey,
                                activeSession = activeSess,
                                transcript = transcript,
                                isSessionRunning = sessRunning,
                                onLoadOlder = { store.loadOlderHistory(activeKey) },
                                onCancelQueuedPrompt = store::cancelQueuedPrompt,
                                onClearQueuedPrompts = store::clearQueuedPrompts,
                                onDismissKeyboard = dismissKeyboard,
                                showInlineErrorRetry = state.turnRecoveryBanner == null,
                                onRetryLastTurn = {
                                    dismissKeyboard()
                                    store.retryLastTurn()
                                },
                                onOpenPlanArtifact = planArtifact?.let { art ->
                                    {
                                        dismissKeyboard()
                                        store.openArtifact(art.id)
                                        showArtifactsSheet = true
                                    }
                                },
                                onApprovePlan = if (awaitingPlanApproval) {
                                    { store.approveAndExecutePlan(planArtifact?.title) }
                                } else {
                                    null
                                },
                                subagents = activeSubagents,
                                onOpenSubagent = { sub ->
                                    dismissKeyboard()
                                    store.openSubagentInspector(sub)
                                },
                            )
                        }
                    }
                }

                val activeSessForPerm = state.activeSession?.takeIf { it.id == state.activeSessionId }
                val pendingPerm = state.activeTranscript.pendingPermissions
                    .lastOrNull { it.sessionId == state.activeSessionId }
                    ?: if (activeSessForPerm?.state == SessionState.WAITING_PERMISSION) {
                        AgentEvent.PermissionRequested(
                            seq = state.activeTranscript.lastSeq,
                            sessionId = activeSessForPerm.id,
                            ts = 0L,
                            requestId = "fallback_waiting_${activeSessForPerm.id}",
                            title = "Agent 正在等待确认\n正在同步终端选项，若未显示操作按钮请点击右侧刷新或回电脑窗口确认。",
                            options = emptyList(),
                        )
                    } else {
                        null
                    }
                if (pendingPerm != null) {
                    InlineApprovalBanner(
                        permission = pendingPerm,
                        isSubmitting = state.submittingApprovalRequestId == pendingPerm.requestId,
                        errorText = state.approvalErrorText,
                        onRefresh = {
                            pendingPerm.sessionId.takeIf { it.isNotBlank() }?.let { store.selectSession(it) }
                        },
                        onDecision = { reqId, optId, sessId ->
                            store.respondApproval(reqId, optId, sessId)
                        },
                    )
                }

                if (state.activeSessionId != null) {
                    SessionContextStatusStrip(
                        sessionUsage = state.activeSessionUsage?.takeIf { it.sessionId == state.activeSessionId },
                        fallbackInputTokens = state.activeTranscript.inputTokens,
                        fallbackOutputTokens = state.activeTranscript.outputTokens,
                        onClick = {
                            dismissKeyboard()
                            store.refreshUsageDashboard()
                            showUsageSheet = true
                        },
                    )
                }

                if (!state.promptNoticeText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Everforest.BoldGold.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Everforest.BoldGold.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .testTag("prompt_notice_banner"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = Everforest.BoldGold,
                                modifier = Modifier.size(16.dp),
                            )
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.promptNoticeText.orEmpty(),
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp,
                                    color = Everforest.Fg,
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭提示",
                                tint = Everforest.CommentGrey,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { store.dismissPromptNotice() }
                                    .testTag("prompt_notice_dismiss_btn"),
                            )
                        }
                    }
                }

                val recoveryBanner = state.turnRecoveryBanner
                if (recoveryBanner != null) {
                    NetworkErrorRecoveryBanner(
                        banner = recoveryBanner,
                        connectionStatus = state.connectionStatus,
                        isRetrying = state.retryingTurn,
                        onRetry = {
                            dismissKeyboard()
                            store.retryLastTurn()
                        },
                        onDismiss = store::dismissTurnRecoveryBanner,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                } else if (!state.promptErrorText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Everforest.Red.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Everforest.Red.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = Everforest.Red,
                                modifier = Modifier.size(16.dp),
                            )
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.promptErrorText.orEmpty(),
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp,
                                    color = Everforest.Fg,
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭提示",
                                tint = Everforest.CommentGrey,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { store.dismissPromptError() },
                            )
                        }
                    }
                }

                ClaudeStyleComposer(
                    state = state,
                    value = promptField,
                    onValueChange = { nextField ->
                        val syncReq = cursorSyncRequest
                        if (syncReq != null &&
                            nextField.text == syncReq.second &&
                            nextField.composition == null &&
                            nextField.selection != TextRange(syncReq.third)
                        ) {
                            // Guard against iOS UIKit IME unmarkText() snapping caret back to pre-mention index
                            promptField = TextFieldValue(
                                text = syncReq.second,
                                selection = TextRange(syncReq.third),
                                composition = null,
                            )
                            cursorSyncRequest = null
                        } else {
                            if (syncReq != null && nextField.text != syncReq.second) {
                                cursorSyncRequest = null
                            }
                            promptField = nextField
                            store.updateSessionDraft(nextField.text, nextField.selection.end)
                        }
                    },
                    onOpenWorkspaceSheet = {
                        dismissKeyboard()
                        showWorkspaceSheet = true
                    },
                    onCycleMode = {
                        val modes = state.availableModes
                        val idx = modes.indexOf(state.selectedMode)
                        val next = modes.getOrElse((idx + 1) % modes.size) { SessionMode.AUTO }
                        store.selectMode(next)
                    },
                    onCycleEffort = {
                        val efforts = state.availableEfforts
                        val idx = efforts.indexOf(state.selectedEffort)
                        val next = efforts.getOrElse((idx + 1) % efforts.size) { ReasoningEffort.MEDIUM }
                        store.selectEffort(next)
                    },
                    onSend = {
                        dismissKeyboard()
                        val msg = promptField.text
                        cursorSyncRequest = null
                        promptField = TextFieldValue("")
                        store.dismissMentionSuggestions()
                        store.sendPrompt(msg)
                    },
                    onCancel = store::cancelActiveTurn,
                    onClearQueuedPrompts = store::clearQueuedPrompts,
                    onPickImage = {
                        dismissKeyboard()
                        store.pickAndAttachImage()
                    },
                    onCaptureCamera = {
                        dismissKeyboard()
                        store.captureAndAttachImage()
                    },
                    onPasteClipboardImages = { notifyWhenEmpty ->
                        if (notifyWhenEmpty) dismissKeyboard()
                        store.pasteImagesFromClipboard(notifyWhenEmpty = notifyWhenEmpty)
                    },
                    onRemoveImage = store::removeAttachedImage,
                    onSelectMentionFile = { relPath ->
                        val (nextText, nextCursor) = replaceMentionAtCursor(
                            text = promptField.text,
                            relativeFilePath = relPath,
                            cursorIndex = promptField.selection.end,
                        )
                        promptField = TextFieldValue(
                            text = nextText,
                            selection = TextRange(nextCursor),
                            composition = null,
                        )
                        cursorSyncRequest = Triple((cursorSyncRequest?.first ?: 0L) + 1L, nextText, nextCursor)
                        store.updateSessionDraft(nextText, nextCursor, updateMentions = false)
                        store.dismissMentionSuggestions()
                    },
                    onInsertAtMention = {
                        val (nextText, nextCursor) = insertAtTriggerAtCursor(
                            text = promptField.text,
                            cursorIndex = promptField.selection.end,
                        )
                        promptField = TextFieldValue(
                            text = nextText,
                            selection = TextRange(nextCursor),
                            composition = null,
                        )
                        cursorSyncRequest = Triple((cursorSyncRequest?.first ?: 0L) + 1L, nextText, nextCursor)
                        store.updateSessionDraft(nextText, nextCursor)
                    },
                    onDismissMention = store::dismissMentionSuggestions,
                )
            }
        }

        if (showModelSheet) {
            ModalBottomSheet(
                onDismissRequest = { showModelSheet = false },
                containerColor = Everforest.Bg1,
                contentColor = Everforest.Fg,
            ) {
                EngineModelPickerSheet(
                    state = state,
                    onSelectEngineAndModel = { engId, modelSpec, effort ->
                        store.selectEngineAndModel(engId, modelSpec, effort)
                    },
                    onDone = { showModelSheet = false },
                )
            }
        }

        if (showWorkspaceSheet) {
            ModalBottomSheet(
                onDismissRequest = { showWorkspaceSheet = false },
                containerColor = Everforest.Bg1,
                contentColor = Everforest.Fg,
            ) {
                WorkspacePickerSheet(
                    state = state,
                    onSelectPrimary = { path ->
                        store.selectWorkspace(path)
                        if (state.selectedExtraWorkspaces.isEmpty() && !state.draftUseWorktree) {
                            showWorkspaceSheet = false
                        }
                    },
                    onToggleExtra = store::toggleExtraWorkspace,
                    onToggleUseWorktree = store::setDraftUseWorktree,
                    onChangeWorktreeBranch = store::setDraftWorktreeBranch,
                    onBrowseWorkspaceFiles = { path ->
                        store.switchBrowseWorkspace(path)
                        showWorkspaceSheet = false
                        showFilesSheet = true
                    },
                    onStartNewInWorkspace = { path ->
                        store.startNewDraftInWorkspace(path)
                        showWorkspaceSheet = false
                    },
                    onDone = { showWorkspaceSheet = false },
                )
            }
        }

        if (showFilesSheet) {
            ModalBottomSheet(
                onDismissRequest = { showFilesSheet = false },
                containerColor = Everforest.Bg1,
                contentColor = Everforest.Fg,
            ) {
                WorkspaceFileTreeSheet(
                    state = state,
                    onNavigateUp = store::navigateUpDirectory,
                    onOpenEntry = store::openEntry,
                    onCloseFile = store::closeOpenedFile,
                    onSelectActiveWorkspaceTab = store::switchBrowseWorkspace,
                    onSwitchWorkspace = {
                        showFilesSheet = false
                        showWorkspaceSheet = true
                    },
                )
            }
        }

        if (showUsageSheet) {
            ModalBottomSheet(
                onDismissRequest = { showUsageSheet = false },
                containerColor = Everforest.Bg1,
                contentColor = Everforest.Fg,
            ) {
                UsageDashboardSheet(
                    state = state,
                    onSelectDays = store::refreshUsageDashboard,
                    onRefresh = { store.refreshUsageDashboard() },
                )
            }
        }

        if (showDiffSheet) {
            ModalBottomSheet(
                onDismissRequest = { showDiffSheet = false },
                containerColor = Everforest.Bg1,
                contentColor = Everforest.Fg,
            ) {
                DiffAndCheckpointsSheet(
                    state = state,
                    onSelectWorkspace = store::switchDiffWorkspace,
                    onRefresh = { store.refreshDiffAndCheckpoints() },
                    onOpenFileDiff = store::openGitDiff,
                    onCloseDiff = store::closeGitDiff,
                    onCreateCheckpoint = store::createManualCheckpoint,
                    onRestoreCheckpoint = store::restoreCheckpoint,
                )
            }
        }

        if (showArtifactsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showArtifactsSheet = false },
                containerColor = Everforest.Bg1,
                contentColor = Everforest.Fg,
            ) {
                ArtifactsInspectorSheet(
                    state = state,
                    onRefresh = { store.refreshSessionArtifacts() },
                    onOpenArtifact = store::openArtifact,
                    onCloseArtifact = store::closeOpenedArtifact,
                    onApprovePlan = { title ->
                        store.approveAndExecutePlan(title)
                        showArtifactsSheet = false
                    },
                    onRevisePlan = { title ->
                        val prefix = "关于方案《$title》，请调整以下部分："
                        promptField = TextFieldValue(prefix, TextRange(prefix.length))
                        store.updateSessionDraft(prefix, prefix.length, updateMentions = false)
                        showArtifactsSheet = false
                    },
                )
            }
        }

        val inspectedSub = state.inspectedSubagent
        if (inspectedSub != null) {
            SubagentInspectorDialog(
                subagent = inspectedSub,
                transcript = state.inspectedSubagentTranscript,
                loading = state.inspectedSubagentLoading,
                error = state.inspectedSubagentError,
                onRefresh = store::refreshInspectedSubagent,
                onClose = store::closeSubagentInspector,
            )
        }

        val activePreview = state.activeFilePreview
        if (activePreview != null) {
            ModalBottomSheet(
                onDismissRequest = store::closeFilePreview,
                containerColor = Everforest.Bg1,
                contentColor = Everforest.Fg,
            ) {
                FileLinkPreviewContent(
                    entry = activePreview,
                    canNavigateBack = state.canNavigateBackFilePreview,
                    showCloseButton = false,
                    onNavigateBack = store::navigateBackFilePreview,
                    onOpenNestedLink = { rawTarget, baseDir ->
                        store.openLinkedFilePreview(
                            rawTarget = rawTarget,
                            baseDirectory = baseDir,
                            pushToStack = true,
                        )
                    },
                    onClose = store::closeFilePreview,
                )
            }
        }
        }
    }

    val pendingPair = state.pendingDeepLinkPairing
    if (pendingPair != null) {
        PendingDeepLinkPairingDialog(
            pendingPair = pendingPair,
            onConfirm = store::confirmPendingDeepLinkPairing,
            onDismiss = store::dismissPendingDeepLinkPairing,
        )
    }

    val renamingTarget = renamingSessionId?.let { targetId ->
        state.sessions.firstOrNull { it.id == targetId }
    }
    if (renamingTarget != null) {
        RenameSessionDialog(
            session = renamingTarget,
            onDismiss = { renamingSessionId = null },
            onConfirm = { nextTitle ->
                renamingSessionId = null
                store.renameSession(renamingTarget.id, nextTitle)
            },
        )
    }
}

@Composable
internal fun PendingDeepLinkPairingDialog(
    pendingPair: PendingDeepLinkPairing,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalEscapeEffect(onDismiss = onDismiss)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Everforest.Bg1,
        titleContentColor = Everforest.Fg,
        textContentColor = Everforest.Grey2,
        title = {
            Text(
                text = "确认配对新主机？",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "外部链接请求将当前客户端配对至以下主机，请核对名称与证书指纹：",
                    fontSize = 13.sp,
                    color = Everforest.Grey2,
                )
                Text(
                    text = "主机名称：${pendingPair.hostName}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Everforest.Fg,
                )
                Text(
                    text = "连接地址：${pendingPair.primaryUrl}",
                    fontSize = 12.sp,
                    color = Everforest.Grey1,
                )
                if (!pendingPair.shortFingerprint.isNullOrBlank()) {
                    Text(
                        text = "证书指纹：${pendingPair.shortFingerprint}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.Green,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Everforest.Green,
                    contentColor = Everforest.BgDim,
                ),
            ) {
                Text("确认配对", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Everforest.Grey1)
            }
        },
    )
}

@Composable
private fun ActiveSessionTitleStrip(
    session: SessionMeta,
    artifactCount: Int = 0,
    onOpenArtifacts: () -> Unit = {},
    onRename: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .clickable { onRename() }
                .padding(vertical = 3.dp, horizontal = 2.dp)
                .testTag("active_session_rename_trigger"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (session.archived) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Everforest.Bg2,
                ) {
                    Text(
                        text = "已归档",
                        fontSize = 10.sp,
                        color = Everforest.BoldGold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            session.worktree?.let { wt ->
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Everforest.BgGreen,
                    border = BorderStroke(0.8.dp, Everforest.Aqua.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccountTree,
                            contentDescription = null,
                            tint = Everforest.Aqua,
                            modifier = Modifier.size(10.dp),
                        )
                        Text(
                            text = wt.branch.removePrefix("lework/"),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Everforest.Aqua,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                text = session.title,
                fontSize = 12.sp,
                color = Everforest.Grey2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "重命名当前会话",
                tint = Everforest.CommentGrey,
                modifier = Modifier.size(13.dp),
            )
        }

        Spacer(Modifier.width(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpenArtifacts() }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .testTag("mobile_btn_artifacts"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = "会话产物",
                    tint = if (artifactCount > 0) Everforest.BoldGold else Everforest.CommentGrey,
                    modifier = Modifier.size(13.5.dp),
                )
                Text(
                    text = if (artifactCount > 0) "产物($artifactCount)" else "产物",
                    fontSize = 11.5.sp,
                    color = if (artifactCount > 0) Everforest.BoldGold else Everforest.CommentGrey,
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onToggleArchive() }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .testTag("active_session_archive_btn"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (session.archived) Icons.Rounded.Unarchive else Icons.Rounded.Inventory2,
                    contentDescription = if (session.archived) "移出归档" else "归档会话",
                    tint = if (session.archived) Everforest.Green else Everforest.CommentGrey,
                    modifier = Modifier.size(13.5.dp),
                )
                Text(
                    text = if (session.archived) "移出归档" else "归档",
                    fontSize = 11.5.sp,
                    color = if (session.archived) Everforest.Green else Everforest.CommentGrey,
                )
            }
        }
    }
}

@Composable
private fun MinimalTopBar(
    state: ConsoleUiState,
    onOpenDrawer: () -> Unit,
    onOpenModelSheet: () -> Unit,
    onOpenDiffSheet: () -> Unit,
    onOpenFilesSheet: () -> Unit,
) {
    val engineLabel = shortEngineLabel(state.selectedEngineId)
    val activeModelSpec = state.selectedModelSpec
    val modelLabel = activeModelSpec?.name
        ?.removeSuffix(" (Proxy)")
        ?.let { if (it.length > 24) it.take(23) + "…" else it }
        ?: "默认模型"
    val externalSourceLabel = activeModelSpec?.externalProviderLabel(state.providers)
    val hasActiveTasks = state.runningSessions.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.testTag("topbar_btn_menu"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "菜单",
                    tint = Everforest.Fg,
                )
            }
            if (hasActiveTasks) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Everforest.Orange),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { onOpenModelSheet() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EngineGlyphIcon(engineId = state.selectedEngineId, size = 14.dp)
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Everforest.Fg, fontWeight = FontWeight.SemiBold)) {
                            append(engineLabel)
                        }
                        withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                            append(" · $modelLabel")
                        }
                        if (!externalSourceLabel.isNullOrBlank()) {
                            withStyle(SpanStyle(color = Everforest.Aqua, fontWeight = FontWeight.Medium)) {
                                append(" · $externalSourceLabel")
                            }
                        }
                    },
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Everforest.Grey1,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onOpenDiffSheet,
                modifier = Modifier.size(36.dp).testTag("topbar_btn_diff"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.CompareArrows,
                    contentDescription = "变更与快照",
                    tint = Everforest.Grey2,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onOpenFilesSheet,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = "浏览文件",
                    tint = Everforest.Grey2,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun CalmHomeEmptyState(
    state: ConsoleUiState,
    onOpenRunningSession: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = Everforest.BgGreen,
            modifier = Modifier.size(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = Everforest.Green,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "今天想推进哪项工作？",
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            color = Everforest.Fg,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = if (isDesktopPlatform()) {
                "在下方直接发起新对话，或从左侧切换本机与远程开发机会话"
            } else {
                "在下方直接发起新对话，或从左上角接管本机会话"
            },
            fontSize = 12.5.sp,
            color = Everforest.CommentGrey,
            textAlign = TextAlign.Center,
        )

        val activeList = state.runningSessions.take(3)
        if (activeList.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (running in activeList) {
                    val isWaiting = running.state == SessionState.WAITING_PERMISSION
                    val bannerText = when {
                        isWaiting -> "${shortEngineLabel(running.engineId)} 待审批 · ${running.title}"
                        !running.liveStatusText.isNullOrBlank() ->
                            "${shortEngineLabel(running.engineId)} · ${running.title} (${running.liveStatusText})"
                        else -> "${shortEngineLabel(running.engineId)} 进行中 · ${running.title}"
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Everforest.BgYellow,
                        border = BorderStroke(1.dp, Everforest.Yellow.copy(alpha = if (isWaiting) 0.65f else 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenRunningSession(running.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (isWaiting) Icons.Rounded.Shield else Icons.Rounded.PlayCircle,
                                contentDescription = null,
                                tint = Everforest.Yellow,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = bannerText,
                                fontSize = 12.sp,
                                color = Everforest.Fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = Everforest.Yellow,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConversationLoadingSkeleton(title: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .width(210.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(Everforest.Bg2.copy(alpha = 0.55f)),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Everforest.Bg1.copy(alpha = 0.7f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Everforest.Bg1.copy(alpha = 0.55f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Everforest.Bg1.copy(alpha = 0.45f)),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            CircularProgressIndicator(
                color = Everforest.CommentGrey,
                strokeWidth = 1.6.dp,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = if (!title.isNullOrBlank()) "正在同步「${title.take(24)}」…" else "正在同步会话上下文…",
                fontSize = 12.sp,
                color = Everforest.CommentGrey,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
