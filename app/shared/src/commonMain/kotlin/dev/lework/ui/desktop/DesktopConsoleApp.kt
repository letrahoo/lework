package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.installGlobalCliCommand
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.SessionMode
import dev.lework.protocol.SessionState
import dev.lework.store.*
import dev.lework.ui.*

@Composable
internal fun DesktopConsoleApp(
    store: ConsoleStore,
    state: ConsoleUiState,
    openSettingsSignal: Int = 0,
    toggleSidebarSignal: Int = 0,
    openGlobalSearchSignal: Int = 0,
    openInSessionFindSignal: Int = 0,
    registerGlobalCliSignal: Int = 0,
) {
    var showModelPicker by remember { mutableStateOf(false) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }
    var showUsageDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var autoInstallGlobalCliNonce by remember { mutableIntStateOf(0) }
    var showGlobalSearchDialog by remember { mutableStateOf(false) }
    var globalSearchOpenNonce by remember { mutableIntStateOf(0) }
    var inSessionFindOpenNonce by remember { mutableIntStateOf(0) }
    var activeInspectorTab by remember { mutableStateOf<DesktopInspectorTab?>(null) }
    var sidebarExpanded by remember { mutableStateOf(true) }
    var renamingSessionId by remember { mutableStateOf<String?>(null) }
    var focusComposerNonce by remember { mutableIntStateOf(0) }
    val composerFocusRequester = remember { FocusRequester() }
    val promptText = state.activeDraftText
    val escapeDispatcher = LocalModalEscapeDispatcher.current ?: remember { ModalEscapeDispatcher() }
    val anyModalBlockingComposerFocus =
        showGlobalSearchDialog || showModelPicker || showWorkspaceDialog ||
            showUsageDialog || showSettingsDialog ||
            state.inSessionFindOpen || state.inspectedSubagent != null ||
            state.activeFilePreview != null || state.pendingDeepLinkPairing != null ||
            state.appUpdate.showUpdateDialog || renamingSessionId != null

    LaunchedEffect(state.activeSessionId) {
        if (state.activeSessionId == null && activeInspectorTab == DesktopInspectorTab.ARTIFACTS) {
            activeInspectorTab = null
        }
    }

    LaunchedEffect(
        state.hasEnteredWorkspace,
        state.activeDraftKey,
        focusComposerNonce,
        anyModalBlockingComposerFocus,
    ) {
        if (state.hasEnteredWorkspace && !anyModalBlockingComposerFocus) {
            withFrameNanos {}
            runCatching { composerFocusRequester.requestFocus() }
        }
    }

    fun openGlobalSearchPalette(prefill: String? = null) {
        val nextQuery = prefill?.trim()
            ?: state.drawerSearchQuery.trim().takeIf { it.isNotEmpty() }
            ?: state.searchQuery
        showGlobalSearchDialog = true
        globalSearchOpenNonce++
        if (nextQuery.isNotBlank()) {
            store.searchConversations(query = nextQuery, immediate = true)
        }
    }

    LaunchedEffect(openSettingsSignal) {
        if (openSettingsSignal > 0 && state.hasEnteredWorkspace) {
            showSettingsDialog = true
        }
    }

    LaunchedEffect(registerGlobalCliSignal) {
        if (registerGlobalCliSignal > 0) {
            if (state.hasEnteredWorkspace) {
                showSettingsDialog = true
                autoInstallGlobalCliNonce++
            } else {
                installGlobalCliCommand()
            }
        }
    }

    LaunchedEffect(toggleSidebarSignal) {
        if (toggleSidebarSignal > 0 && state.hasEnteredWorkspace) {
            sidebarExpanded = !sidebarExpanded
        }
    }

    LaunchedEffect(openGlobalSearchSignal) {
        if (openGlobalSearchSignal > 0 && state.hasEnteredWorkspace) {
            openGlobalSearchPalette()
        }
    }

    LaunchedEffect(openInSessionFindSignal) {
        if (openInSessionFindSignal > 0 && state.hasEnteredWorkspace) {
            if (state.activeSessionId != null) {
                inSessionFindOpenNonce++
                store.openInSessionFind()
            } else {
                openGlobalSearchPalette()
            }
        }
    }

    LaunchedEffect(state.activeSessionId) {
        if (state.activeSessionId == null && activeInspectorTab == DesktopInspectorTab.ARTIFACTS) {
            activeInspectorTab = null
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

    CompositionLocalProvider(
        LocalModalEscapeDispatcher provides escapeDispatcher,
        LocalFileLinkClickHandler provides { rawTarget ->
            store.openLinkedFilePreview(rawTarget)
        },
    ) {
    ModalEscapeEffect(
        enabled = state.inSessionFindOpen,
        onDismiss = store::closeInSessionFind,
    )
    if (!state.hasEnteredWorkspace) {
        DesktopAuthScreen(
            state = state,
            onLaunchOAuth = store::launchOAuthApp,
            onOpenBrowser = store::openOAuthBrowserFallback,
            onManualConnect = store::connectManualEndpoint,
            onRetry = store::bootstrapOrConnect,
            onResetCredentials = store::resetCredentialsAndReconnect,
            onPasteClipboardPairing = store::pastePairingFromClipboard,
            onConfirmClipboardPairing = store::confirmClipboardPairing,
            onDismissClipboardPairing = store::dismissClipboardPairing,
            onPairViaSsh = store::pairViaSshHost,
            onStartTsnetLogin = store::startMobileTsnetLogin,
            onSearchTsnetPeers = store::searchAndConnectViaTsnet,
            onSelectTsnetHub = store::selectDiscoveredTsnetHub,
            onLogoutTsnet = store::logoutMobileTsnetAccount,
            onSelectPairedHost = store::switchPairedHost,
            onCancelPairAnotherHost = store::cancelPairAnotherHost,
        )
    } else {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Everforest.Bg0)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (ev.key == Key.Escape) {
                        if (escapeDispatcher.dispatchEscape()) {
                            return@onPreviewKeyEvent true
                        }
                        if (state.inSessionFindOpen) {
                            store.closeInSessionFind()
                            return@onPreviewKeyEvent true
                        }
                    }
                    // Option + ↑ / ↓: switch adjacent session in visible sidebar order (no conflict with macOS ⌘↑/⌘↓ cursor jump)
                    if (
                        ev.isAltPressed &&
                        !ev.isMetaPressed &&
                        !ev.isCtrlPressed &&
                        !ev.isShiftPressed &&
                        !anyModalBlockingComposerFocus
                    ) {
                        when (ev.key) {
                            Key.DirectionUp -> {
                                store.selectAdjacentSession(-1)
                                focusComposerNonce++
                                return@onPreviewKeyEvent true
                            }
                            Key.DirectionDown -> {
                                store.selectAdjacentSession(+1)
                                focusComposerNonce++
                                return@onPreviewKeyEvent true
                            }
                            else -> Unit
                        }
                    }
                    val cmdOrCtrl = ev.isMetaPressed || ev.isCtrlPressed
                    if (!cmdOrCtrl || ev.isAltPressed) return@onPreviewKeyEvent false
                    if (!ev.isShiftPressed && !anyModalBlockingComposerFocus) {
                        val quickIndex = when (ev.key) {
                            Key.One -> 0
                            Key.Two -> 1
                            Key.Three -> 2
                            Key.Four -> 3
                            Key.Five -> 4
                            Key.Six -> 5
                            Key.Seven -> 6
                            Key.Eight -> 7
                            Key.Nine -> 8
                            else -> -1
                        }
                        if (quickIndex >= 0) {
                            store.selectSessionByIndex(quickIndex)
                            focusComposerNonce++
                            return@onPreviewKeyEvent true
                        }
                        if (ev.key == Key.LeftBracket) {
                            store.navigateSessionHistory(-1)
                            focusComposerNonce++
                            return@onPreviewKeyEvent true
                        }
                        if (ev.key == Key.RightBracket) {
                            store.navigateSessionHistory(+1)
                            focusComposerNonce++
                            return@onPreviewKeyEvent true
                        }
                    }
                    when {
                        ev.key == Key.F && ev.isShiftPressed -> {
                            openGlobalSearchPalette()
                            true
                        }
                        ev.key == Key.F && !ev.isShiftPressed -> {
                            if (state.activeSessionId != null) {
                                inSessionFindOpenNonce++
                                store.openInSessionFind()
                            } else {
                                openGlobalSearchPalette()
                            }
                            true
                        }
                        ev.key == Key.N && !ev.isShiftPressed -> {
                            store.startNewDraftSession()
                            focusComposerNonce++
                            true
                        }
                        ev.key == Key.B && !ev.isShiftPressed -> {
                            sidebarExpanded = !sidebarExpanded
                            true
                        }
                        ev.key == Key.R && !ev.isShiftPressed -> {
                            store.refreshHostSessions()
                            true
                        }
                        ev.key == Key.Comma && !ev.isShiftPressed -> {
                            showSettingsDialog = true
                            true
                        }
                        ev.key == Key.K && !ev.isShiftPressed -> {
                            showModelPicker = true
                            true
                        }
                        ev.key == Key.D && ev.isShiftPressed -> {
                            if (activeInspectorTab == DesktopInspectorTab.DIFF) {
                                activeInspectorTab = null
                            } else {
                                store.refreshDiffAndCheckpoints()
                                activeInspectorTab = DesktopInspectorTab.DIFF
                            }
                            true
                        }
                        ev.key == Key.A && ev.isShiftPressed -> {
                            if (activeInspectorTab == DesktopInspectorTab.ARTIFACTS) {
                                activeInspectorTab = null
                            } else if (state.activeSessionId != null) {
                                store.refreshSessionArtifacts()
                                activeInspectorTab = DesktopInspectorTab.ARTIFACTS
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            val canDockInspector = maxWidth >= 1040.dp
            val topActiveSess = state.activeSession?.takeIf { it.id == state.activeSessionId }

            Row(modifier = Modifier.fillMaxSize()) {
                // ── Left Permanent Collapsible Sidebar ──
                if (sidebarExpanded) {
                    Surface(
                        color = Everforest.BgDim,
                        contentColor = Everforest.Fg,
                        modifier = Modifier
                            .width(292.dp)
                            .fillMaxHeight()
                            .testTag("desktop_permanent_sidebar"),
                    ) {
                        DesktopSidebar(
                            state = state,
                            onNewSession = {
                                store.startNewDraftSession()
                                focusComposerNonce++
                            },
                            onNewSessionInWorkspace = { path ->
                                store.startNewDraftInWorkspace(path)
                                focusComposerNonce++
                            },
                            onOpenUsage = {
                                store.refreshUsageDashboard()
                                showUsageDialog = true
                            },
                            onOpenSettings = { showSettingsDialog = true },
                            onCollapseSidebar = { sidebarExpanded = false },
                            onSelectFilter = store::setDrawerEngineFilter,
                            onSelectSession = { sid ->
                                store.selectSession(sid)
                                focusComposerNonce++
                            },
                            onRefresh = store::refreshHostSessions,
                            onSwitchHost = store::switchPairedHost,
                            onRenameHost = store::renamePairedHost,
                            onPairAnotherHost = store::preparePairAnotherHost,
                            onSearchQueryChange = store::setDrawerSearchQuery,
                            onOpenGlobalSearch = { query -> openGlobalSearchPalette(query) },
                            onToggleArchivedView = store::setDrawerShowArchived,
                            onToggleOnlyRunning = store::toggleDrawerOnlyRunning,
                            onTogglePinnedCollapsed = store::togglePinnedSectionCollapsed,
                            onToggleGroupingMode = store::toggleSidebarGroupingMode,
                            onToggleProjectGroupCollapsed = store::toggleProjectGroupCollapsed,
                            onSetAllProjectGroupsCollapsed = store::setAllProjectGroupsCollapsed,
                            onTogglePinSession = store::togglePinSession,
                            onRequestRenameSession = { sess -> renamingSessionId = sess.id },
                            onToggleArchiveSession = store::archiveSession,
                            onOpenAppUpdate = store::openAppUpdateDialog,
                            onDismissAppUpdate = store::dismissAppUpdateReminder,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Everforest.Bg2),
                    )
                }

                // ── Center Conversation Workspace ──
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Everforest.Bg0),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DesktopWorkspaceHeader(
                        state = state,
                        activeSession = topActiveSess,
                        showExpandSidebarButton = !sidebarExpanded,
                        activeInspectorTab = activeInspectorTab,
                        onExpandSidebar = { sidebarExpanded = true },
                        onOpenWorkspaceDialog = { showWorkspaceDialog = true },
                        onToggleDiffInspector = {
                            if (activeInspectorTab == DesktopInspectorTab.DIFF) {
                                activeInspectorTab = null
                            } else {
                                store.refreshDiffAndCheckpoints()
                                activeInspectorTab = DesktopInspectorTab.DIFF
                            }
                        },
                        onToggleFilesInspector = {
                            if (activeInspectorTab == DesktopInspectorTab.FILES) {
                                activeInspectorTab = null
                            } else {
                                store.browseDirectory()
                                activeInspectorTab = DesktopInspectorTab.FILES
                            }
                        },
                        onToggleArtifactsInspector = {
                            if (activeInspectorTab == DesktopInspectorTab.ARTIFACTS) {
                                activeInspectorTab = null
                            } else {
                                store.refreshSessionArtifacts()
                                activeInspectorTab = DesktopInspectorTab.ARTIFACTS
                            }
                        },
                        onRenameSession = {
                            if (topActiveSess != null) renamingSessionId = topActiveSess.id
                        },
                        onToggleArchiveSession = {
                            if (topActiveSess != null) {
                                store.archiveSession(topActiveSess.id, !topActiveSess.archived)
                            }
                        },
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 980.dp)
                            .fillMaxWidth(),
                    ) {
                        if (state.connectionStatus == ConnectionStatus.RECONNECTING) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Everforest.Bg1,
                                border = BorderStroke(1.dp, Everforest.BoldGold.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 4.dp),
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
                                        text = "正在重新连接主机并同步最新消息…",
                                        fontSize = 12.sp,
                                        color = Everforest.BoldGold,
                                    )
                                }
                            }
                        }

                        ArtifactsQuickBar(
                            state = state,
                            onOpenArtifacts = { artId ->
                                store.refreshSessionArtifacts()
                                if (artId != null) store.openArtifact(artId)
                                activeInspectorTab = DesktopInspectorTab.ARTIFACTS
                            },
                        )

                        val activeSubagents = topActiveSess?.subagents.orEmpty()
                        if (topActiveSess != null && activeSubagents.isNotEmpty()) {
                            SubagentStatusStrip(
                                sessionId = topActiveSess.id,
                                subagents = activeSubagents,
                                onSelectSubagent = store::openSubagentInspector,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            val activeKey = state.activeSessionId ?: "draft"
                            val transcript = state.activeTranscript
                            if (state.activeSessionId == null && transcript.items.isEmpty()) {
                                CalmHomeEmptyState(
                                    state = state,
                                    onOpenRunningSession = store::selectSession,
                                    onQuickPrompt = { starter ->
                                        val localAct = parseLocalSlashAction(starter, state)
                                        if (localAct != null) {
                                            executeLocalSlashAction(
                                                action = localAct,
                                                state = state,
                                                onNewSession = {
                                                    store.startNewDraftSession()
                                                    focusComposerNonce++
                                                },
                                                onOpenModelPicker = { showModelPicker = true },
                                                onOpenDiff = {
                                                    store.refreshDiffAndCheckpoints()
                                                    activeInspectorTab = DesktopInspectorTab.DIFF
                                                },
                                                onOpenFiles = {
                                                    store.browseDirectory()
                                                    activeInspectorTab = DesktopInspectorTab.FILES
                                                },
                                                onOpenArtifacts = {
                                                    store.refreshSessionArtifacts()
                                                    activeInspectorTab = DesktopInspectorTab.ARTIFACTS
                                                },
                                                onOpenUsage = {
                                                    store.refreshUsageDashboard()
                                                    showUsageDialog = true
                                                },
                                                onOpenSearch = { q -> openGlobalSearchPalette(q.ifBlank { null }) },
                                                onRenameSession = {},
                                                onSelectMode = store::selectMode,
                                                onSelectEffort = store::selectEffort,
                                                onShowNotice = store::showPromptNotice,
                                            )
                                        } else {
                                            store.updateSessionDraft(starter, updateMentions = false)
                                            focusComposerNonce++
                                        }
                                    },
                                )
                            } else if (
                                state.activeSessionId != null &&
                                transcript.items.isEmpty() &&
                                state.loadingSessionId == state.activeSessionId
                            ) {
                                ConversationLoadingSkeleton(title = topActiveSess?.title)
                            } else {
                                val sessRunning = topActiveSess != null &&
                                    (topActiveSess.state == SessionState.RUNNING || topActiveSess.state == SessionState.WAITING_PERMISSION)
                                val planArtifact = state.activeSessionArtifacts.firstOrNull {
                                    it.actionable || it.kind == dev.lework.protocol.ArtifactKind.PLAN
                                }
                                val awaitingPlanApproval = !sessRunning && (
                                    state.selectedMode == SessionMode.PLAN ||
                                        (planArtifact != null &&
                                            topActiveSess?.state == SessionState.IDLE &&
                                            transcript.planEntries.isNotEmpty() &&
                                            transcript.planEntries.all { it.status == "pending" })
                                )
                                key(activeKey) {
                                    ConversationStreamView(
                                        sessionKey = activeKey,
                                        activeSession = topActiveSess,
                                        transcript = transcript,
                                        isSessionRunning = sessRunning,
                                        onLoadOlder = { store.loadOlderHistory(activeKey) },
                                        onCancelQueuedPrompt = store::cancelQueuedPrompt,
                                        onClearQueuedPrompts = store::clearQueuedPrompts,
                                        onDismissKeyboard = {},
                                        showInlineErrorRetry = state.turnRecoveryBanner == null,
                                        onRetryLastTurn = store::retryLastTurn,
                                        onOpenPlanArtifact = planArtifact?.let { art ->
                                            {
                                                store.openArtifact(art.id)
                                                activeInspectorTab = DesktopInspectorTab.ARTIFACTS
                                            }
                                        },
                                        onApprovePlan = if (awaitingPlanApproval) {
                                            { store.approveAndExecutePlan(planArtifact?.title) }
                                        } else {
                                            null
                                        },
                                        subagents = activeSubagents,
                                        onOpenSubagent = store::openSubagentInspector,
                                        pendingJumpSeq = state.pendingJumpSeq,
                                        pendingJumpNonce = state.pendingJumpNonce,
                                        highlightedSeq = state.highlightedSeq,
                                        highlightedQuery = state.highlightedQuery,
                                        keepHighlightPersistent = state.inSessionFindOpen,
                                        onJumpHandled = store::consumePendingJump,
                                        onClearHighlight = store::clearHighlightedSeq,
                                        onJumpToLatest = { store.jumpBackToLatest(activeKey) },
                                    )
                                }
                            }
                            if (state.inSessionFindOpen && state.activeSessionId != null) {
                                DesktopInSessionFindBar(
                                    state = state,
                                    openNonce = inSessionFindOpenNonce,
                                    onQueryChange = { q -> store.updateInSessionFindQuery(q) },
                                    onPrevMatch = { store.navigateInSessionFind(-1) },
                                    onNextMatch = { store.navigateInSessionFind(+1) },
                                    onClose = store::closeInSessionFind,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 10.dp, end = 16.dp),
                                )
                            }
                        }

                        val pendingPerm = state.activeTranscript.pendingPermissions
                            .lastOrNull { it.sessionId == state.activeSessionId }
                            ?: if (topActiveSess?.state == SessionState.WAITING_PERMISSION) {
                                AgentEvent.PermissionRequested(
                                    seq = state.activeTranscript.lastSeq,
                                    sessionId = topActiveSess.id,
                                    ts = 0L,
                                    requestId = "fallback_waiting_${topActiveSess.id}",
                                    title = "正在等待你的确认\n正在同步操作选项，若未显示按钮请点击右侧刷新或回主机终端确认。",
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
                                onCustomAnswer = { reqId, customInput, sessId ->
                                    store.respondApproval(reqId, "__custom__", sessId, customInput)
                                },
                            )
                        }

                        if (state.activeSessionId != null) {
                            SessionContextStatusStrip(
                                sessionUsage = state.activeSessionUsage?.takeIf { it.sessionId == state.activeSessionId },
                                fallbackInputTokens = state.activeTranscript.inputTokens,
                                fallbackOutputTokens = state.activeTranscript.outputTokens,
                                onClick = {
                                    store.refreshUsageDashboard()
                                    showUsageDialog = true
                                },
                            )
                        }

                        if (!state.promptNoticeText.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Everforest.BoldGold.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Everforest.BoldGold.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 2.dp)
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
                                    val actionLabel = state.promptNoticeActionLabel
                                    if (!actionLabel.isNullOrBlank()) {
                                        Text(
                                            text = actionLabel,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Everforest.Green,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { store.triggerPromptNoticeAction() }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                .testTag("prompt_notice_action_btn"),
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
                                onRetry = store::retryLastTurn,
                                onDismiss = store::dismissTurnRecoveryBanner,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            )
                        } else if (!state.promptErrorText.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Everforest.Red.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Everforest.Red.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 2.dp),
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

                        val runDesktopLocalSlash: (LocalSlashAction) -> Unit = { localAct ->
                            executeLocalSlashAction(
                                action = localAct,
                                state = state,
                                onNewSession = {
                                    store.startNewDraftSession()
                                    focusComposerNonce++
                                },
                                onOpenModelPicker = { showModelPicker = true },
                                onOpenDiff = {
                                    store.refreshDiffAndCheckpoints()
                                    activeInspectorTab = DesktopInspectorTab.DIFF
                                },
                                onOpenFiles = {
                                    store.browseDirectory()
                                    activeInspectorTab = DesktopInspectorTab.FILES
                                },
                                onOpenArtifacts = {
                                    store.refreshSessionArtifacts()
                                    activeInspectorTab = DesktopInspectorTab.ARTIFACTS
                                },
                                onOpenUsage = {
                                    store.refreshUsageDashboard()
                                    showUsageDialog = true
                                },
                                onOpenSearch = { q -> openGlobalSearchPalette(q.ifBlank { null }) },
                                onRenameSession = { title ->
                                    val sid = state.activeSessionId
                                    if (sid != null) {
                                        if (!title.isNullOrBlank()) {
                                            store.renameSession(sid, title)
                                            store.showPromptNotice("已将当前会话重命名为「$title」")
                                        } else {
                                            renamingSessionId = sid
                                        }
                                    }
                                },
                                onSelectMode = store::selectMode,
                                onSelectEffort = store::selectEffort,
                                onShowNotice = store::showPromptNotice,
                            )
                        }

                        val currentDraftKey = state.activeDraftKey
                        DesktopComposer(
                            state = state,
                            draftKey = currentDraftKey,
                            composerFocusRequester = composerFocusRequester,
                            text = promptText,
                            onTextChange = { next ->
                                store.updateSessionDraft(next, expectedDraftKey = currentDraftKey)
                            },
                            onOpenWorkspaceDialog = { showWorkspaceDialog = true },
                            onOpenModelPicker = { showModelPicker = true },
                            onCycleMode = {
                                val modes = state.availableModes
                                val idx = modes.indexOf(state.selectedMode)
                                val next = modes.getOrElse((idx + 1) % modes.size) { SessionMode.AUTO }
                                store.selectMode(next)
                            },
                            onSend = {
                                val raw = state.activeDraftText
                                val localAct = if (!state.composerHasAttachments) {
                                    parseLocalSlashAction(raw, state)
                                } else {
                                    null
                                }
                                if (localAct != null) {
                                    store.updateSessionDraft("", updateMentions = false)
                                    runDesktopLocalSlash(localAct)
                                } else {
                                    store.sendPrompt(raw)
                                }
                            },
                            onCancel = store::cancelActiveTurn,
                            onClearQueuedPrompts = store::clearQueuedPrompts,
                            onPickImage = { store.pickAndAttachImage() },
                            onPickFiles = { store.pickAndAttachFiles() },
                            onPasteClipboardImages = { store.pasteImagesFromClipboard(notifyWhenEmpty = false) },
                            onRemoveImage = store::removeAttachedImage,
                            onRemoveFile = store::removeAttachedFile,
                            onSelectMentionFile = { relPath ->
                                val replaced = replaceTrailingMention(state.activeDraftText, relPath)
                                store.updateSessionDraft(replaced, updateMentions = false)
                                store.dismissMentionSuggestions()
                            },
                            onDismissMention = store::dismissMentionSuggestions,
                            onSelectSlashCommand = { cmd ->
                                val cur = state.activeDraftText
                                val isTypingCommand = extractSlashCommandQuery(cur) != null
                                if (cmd.executeImmediatelyOnSelect) {
                                    val localAct = parseLocalSlashAction("/${cmd.name}", state)
                                    if (localAct != null) {
                                        if (isTypingCommand) {
                                            store.updateSessionDraft("", updateMentions = false)
                                        }
                                        runDesktopLocalSlash(localAct)
                                    }
                                } else if (!cmd.isLocal || isTypingCommand || cur.isBlank()) {
                                    val nextText = if (isTypingCommand || cur.isBlank()) {
                                        "/${cmd.name} "
                                    } else {
                                        "/${cmd.name} ${cur.trimStart()}"
                                    }
                                    store.updateSessionDraft(nextText, updateMentions = false)
                                    focusComposerNonce++
                                }
                            },
                        )
                    }
                }

                // ── Right Docked Inspector Pane (Diff / Files, T3 Code-Style Side Panel!) ──
                val inspectorTab = activeInspectorTab
                if (inspectorTab != null && canDockInspector) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Everforest.Bg2),
                    )
                    DesktopRightInspectorPane(
                        tab = inspectorTab,
                        state = state,
                        onSelectTab = { nextTab ->
                            activeInspectorTab = nextTab
                            when (nextTab) {
                                DesktopInspectorTab.DIFF -> store.refreshDiffAndCheckpoints()
                                DesktopInspectorTab.FILES -> store.browseDirectory()
                                DesktopInspectorTab.ARTIFACTS -> store.refreshSessionArtifacts()
                            }
                        },
                        onClose = { activeInspectorTab = null },
                        onOpenWorkspaceDialog = { showWorkspaceDialog = true },
                        onPrefillRevisePrompt = { title ->
                            store.updateSessionDraft("关于方案《$title》，请调整以下部分：", updateMentions = false)
                        },
                        store = store,
                    )
                }
            }

            // Narrow desktop window fallback: render Inspector in DesktopModalFrame instead of mobile sheet
            val modalInspectorTab = activeInspectorTab
            if (modalInspectorTab != null && !canDockInspector) {
                DesktopModalFrame(
                    onDismiss = { activeInspectorTab = null },
                    maxWidth = 760.dp,
                    testTag = "desktop_right_inspector_pane",
                ) {
                    when (modalInspectorTab) {
                        DesktopInspectorTab.DIFF -> DiffAndCheckpointsSheet(
                            state = state,
                            onSelectWorkspace = store::switchDiffWorkspace,
                            onRefresh = { store.refreshDiffAndCheckpoints() },
                            onOpenFileDiff = store::openGitDiff,
                            onCloseDiff = store::closeGitDiff,
                            onCreateCheckpoint = store::createManualCheckpoint,
                            onRestoreCheckpoint = store::restoreCheckpoint,
                        )
                        DesktopInspectorTab.FILES -> WorkspaceFileTreeSheet(
                            state = state,
                            onNavigateUp = store::navigateUpDirectory,
                            onOpenEntry = store::openEntry,
                            onCloseFile = store::closeOpenedFile,
                            onSelectActiveWorkspaceTab = store::switchBrowseWorkspace,
                            onSwitchWorkspace = {
                                activeInspectorTab = null
                                showWorkspaceDialog = true
                            },
                            onRetryFs = store::retryLastFsOperation,
                        )
                        DesktopInspectorTab.ARTIFACTS -> ArtifactsInspectorSheet(
                            state = state,
                            onRefresh = { store.refreshSessionArtifacts() },
                            onOpenArtifact = store::openArtifact,
                            onCloseArtifact = store::closeOpenedArtifact,
                            onApprovePlan = { title ->
                                store.approveAndExecutePlan(title)
                                activeInspectorTab = null
                            },
                            onRevisePlan = { title ->
                                store.updateSessionDraft("关于方案《$title》，请调整以下部分：", updateMentions = false)
                                activeInspectorTab = null
                            },
                        )
                    }
                }
            }
        }

        // ── Native Desktop Modal Dialogs (Zero Mobile ModalBottomSheets!) ──
        if (showGlobalSearchDialog) {
            DesktopSearchDialog(
                state = state,
                openNonce = globalSearchOpenNonce,
                onSearch = { q, eng, role, curWs, archived, immediate ->
                    store.searchConversations(
                        query = q,
                        engineFilter = eng,
                        roleFilter = role,
                        scopeCurrentWorkspace = curWs,
                        includeArchived = archived,
                        immediate = immediate,
                    )
                },
                onJumpToHit = { sid, seq ->
                    store.jumpToSearchHit(sid, seq)
                },
                onDismiss = { showGlobalSearchDialog = false },
            )
        }

        if (showModelPicker) {
            DesktopModelPickerDialog(
                state = state,
                onSelectEngineAndModel = { engId, modelSpec, effort ->
                    store.selectEngineAndModel(engId, modelSpec, effort)
                },
                onSelectEffort = store::selectEffort,
                onSelectMode = store::selectMode,
                onToggleFavoriteModel = { engId, modelId, providerId ->
                    store.toggleFavoriteModel(engId, modelId, providerId)
                },
                onDismiss = { showModelPicker = false },
            )
        }

        if (showWorkspaceDialog) {
            DesktopModalFrame(
                onDismiss = { showWorkspaceDialog = false },
                maxWidth = 560.dp,
                testTag = "desktop_workspace_dialog",
            ) {
                WorkspacePickerSheet(
                    state = state,
                    onSelectPrimary = { path ->
                        store.selectWorkspace(path)
                        if (state.selectedExtraWorkspaces.isEmpty() && !state.draftUseWorktree) {
                            showWorkspaceDialog = false
                        }
                    },
                    onToggleExtra = store::toggleExtraWorkspace,
                    onToggleUseWorktree = store::setDraftUseWorktree,
                    onChangeWorktreeBranch = store::setDraftWorktreeBranch,
                    onBrowseWorkspaceFiles = { path ->
                        store.switchBrowseWorkspace(path)
                        showWorkspaceDialog = false
                        activeInspectorTab = DesktopInspectorTab.FILES
                    },
                    onStartNewInWorkspace = { path ->
                        store.startNewDraftInWorkspace(path)
                        showWorkspaceDialog = false
                    },
                    onDone = { showWorkspaceDialog = false },
                )
            }
        }

        if (showUsageDialog) {
            DesktopModalFrame(
                onDismiss = { showUsageDialog = false },
                maxWidth = 680.dp,
                testTag = "desktop_usage_dialog",
            ) {
                UsageDashboardSheet(
                    state = state,
                    onSelectDays = store::refreshUsageDashboard,
                    onRefresh = { store.refreshUsageDashboard() },
                )
            }
        }

        if (showSettingsDialog) {
            DesktopSettingsDialog(
                state = state,
                onDismiss = {
                    store.clearProviderFeedback()
                    autoInstallGlobalCliNonce = 0
                    showSettingsDialog = false
                },
                onSwitchHost = store::switchPairedHost,
                onRenameHost = store::renamePairedHost,
                onRemoveHost = store::removePairedHost,
                onPairAnotherHost = store::preparePairAnotherHost,
                onRefreshProviders = store::refreshProviders,
                onProbeProviderDraft = store::probeProviderDraft,
                onUpsertProvider = store::upsertProvider,
                onRemoveProvider = store::removeProvider,
                onClearProviderFeedback = store::clearProviderFeedback,
                onCheckForUpdates = { store.checkForAppUpdates(manual = true) },
                onOpenAppUpdateDialog = {
                    showSettingsDialog = false
                    store.openAppUpdateDialog()
                },
                autoInstallGlobalCliNonce = autoInstallGlobalCliNonce,
                onLogout = store::logout,
            )
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
            DesktopFilePreviewDialog(
                entry = activePreview,
                canNavigateBack = state.canNavigateBackFilePreview,
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

        if (state.appUpdate.showUpdateDialog) {
            AppUpdateDialog(
                updateState = state.appUpdate,
                onCheckAgain = { store.checkForAppUpdates(manual = true, openDialog = true) },
                onStartUpdate = store::startAppUpdateAction,
                onOpenReleasePage = store::openLatestReleasePage,
                onDismissVersion = store::dismissAppUpdateReminder,
                onClose = store::closeAppUpdateDialog,
            )
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
            isRegeneratingTitle = state.regeneratingTitleSessionId == renamingTarget.id,
            onRegenerateTitle = {
                renamingSessionId = null
                store.regenerateSessionTitle(renamingTarget.id)
            },
            onDismiss = { renamingSessionId = null },
            onConfirm = { nextTitle ->
                renamingSessionId = null
                store.renameSession(renamingTarget.id, nextTitle)
            },
        )
    }
    }
}

@Composable
private fun DesktopRightInspectorPane(
    tab: DesktopInspectorTab,
    state: ConsoleUiState,
    onSelectTab: (DesktopInspectorTab) -> Unit,
    onClose: () -> Unit,
    onOpenWorkspaceDialog: () -> Unit,
    onPrefillRevisePrompt: (String) -> Unit,
    store: ConsoleStore,
) {
    Surface(
        color = Everforest.BgDim,
        contentColor = Everforest.Fg,
        modifier = Modifier
            .width(420.dp)
            .fillMaxHeight()
            .testTag("desktop_right_inspector_pane"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DesktopHeaderHeight)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (state.activeSessionId != null) {
                        LeworkIconActionButton(
                            icon = Icons.Rounded.Description,
                            tooltip = "会话产物",
                            shortcut = "⇧⌘A",
                            active = tab == DesktopInspectorTab.ARTIFACTS,
                            activeTint = Everforest.BoldGold,
                            showBadgeDot = state.activeSessionArtifacts.isNotEmpty(),
                            badgeColor = Everforest.BoldGold,
                            size = DesktopControlSize,
                            iconSize = 16.dp,
                            testTag = "inspector_tab_artifacts",
                            onClick = { onSelectTab(DesktopInspectorTab.ARTIFACTS) },
                        )
                    }
                    LeworkIconActionButton(
                        icon = Icons.AutoMirrored.Rounded.CompareArrows,
                        tooltip = "代码变更与快照",
                        shortcut = "⇧⌘D",
                        active = tab == DesktopInspectorTab.DIFF,
                        activeTint = Everforest.Green,
                        size = DesktopControlSize,
                        iconSize = 16.5.dp,
                        onClick = { onSelectTab(DesktopInspectorTab.DIFF) },
                    )
                    LeworkIconActionButton(
                        icon = Icons.Rounded.FolderOpen,
                        tooltip = "工作区文件",
                        active = tab == DesktopInspectorTab.FILES,
                        activeTint = Everforest.Aqua,
                        size = DesktopControlSize,
                        iconSize = 16.dp,
                        onClick = { onSelectTab(DesktopInspectorTab.FILES) },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when (tab) {
                            DesktopInspectorTab.ARTIFACTS -> "会话产物"
                            DesktopInspectorTab.DIFF -> "代码变更与快照"
                            DesktopInspectorTab.FILES -> "工作区文件"
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Fg,
                    )
                }

                LeworkIconActionButton(
                    icon = Icons.Rounded.Close,
                    tooltip = "关闭右侧面板",
                    size = DesktopControlSize,
                    iconSize = 16.dp,
                    testTag = "desktop_inspector_close_btn",
                    onClick = onClose,
                )
            }
            HorizontalDivider(color = Everforest.Bg2.copy(alpha = 0.65f))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                when (tab) {
                    DesktopInspectorTab.DIFF -> DiffAndCheckpointsSheet(
                        state = state,
                        onSelectWorkspace = store::switchDiffWorkspace,
                        onRefresh = { store.refreshDiffAndCheckpoints() },
                        onOpenFileDiff = store::openGitDiff,
                        onCloseDiff = store::closeGitDiff,
                        onCreateCheckpoint = store::createManualCheckpoint,
                        onRestoreCheckpoint = store::restoreCheckpoint,
                        showHeaderTitle = false,
                    )
                    DesktopInspectorTab.FILES -> WorkspaceFileTreeSheet(
                        state = state,
                        onNavigateUp = store::navigateUpDirectory,
                        onOpenEntry = store::openEntry,
                        onCloseFile = store::closeOpenedFile,
                        onSelectActiveWorkspaceTab = store::switchBrowseWorkspace,
                        onSwitchWorkspace = onOpenWorkspaceDialog,
                        onRetryFs = store::retryLastFsOperation,
                    )
                    DesktopInspectorTab.ARTIFACTS -> ArtifactsInspectorSheet(
                        state = state,
                        onRefresh = { store.refreshSessionArtifacts() },
                        onOpenArtifact = store::openArtifact,
                        onCloseArtifact = store::closeOpenedArtifact,
                        onApprovePlan = { title -> store.approveAndExecutePlan(title) },
                        onRevisePlan = onPrefillRevisePrompt,
                        showHeaderTitle = false,
                    )
                }
            }
        }
    }
}
