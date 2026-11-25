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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
) {
    var showModelPicker by remember { mutableStateOf(false) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }
    var showUsageDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showGlobalSearchDialog by remember { mutableStateOf(false) }
    var activeInspectorTab by remember { mutableStateOf<DesktopInspectorTab?>(null) }
    var sidebarExpanded by remember { mutableStateOf(true) }
    var renamingSessionId by remember { mutableStateOf<String?>(null) }
    var promptText by remember { mutableStateOf("") }

    fun openGlobalSearchPalette(prefill: String? = null) {
        val nextQuery = prefill?.trim()
            ?: state.drawerSearchQuery.trim().takeIf { it.isNotEmpty() }
            ?: state.searchQuery
        showGlobalSearchDialog = true
        if (nextQuery.isNotBlank()) {
            store.searchConversations(query = nextQuery, immediate = true)
        }
    }

    LaunchedEffect(openSettingsSignal) {
        if (openSettingsSignal > 0 && state.hasEnteredWorkspace) {
            showSettingsDialog = true
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
                store.openInSessionFind()
            } else {
                openGlobalSearchPalette()
            }
        }
    }

    LaunchedEffect(state.unsentDraftToRestore) {
        val restore = state.unsentDraftToRestore
        if (!restore.isNullOrEmpty()) {
            if (promptText.isBlank()) {
                promptText = restore
            }
            store.consumeRestoredDraft()
        }
    }

    if (!state.hasEnteredWorkspace) {
        DesktopAuthScreen(
            state = state,
            onLaunchOAuth = store::launchOAuthApp,
            onOpenBrowser = store::openOAuthBrowserFallback,
            onManualConnect = store::connectManualEndpoint,
            onRetry = store::bootstrapOrConnect,
            onResetCredentials = store::resetCredentialsAndReconnect,
            onPasteClipboardPairing = store::pastePairingFromClipboard,
            onPairViaSsh = store::pairViaSshHost,
            onStartTsnetLogin = store::startMobileTsnetLogin,
            onSearchTsnetPeers = store::searchAndConnectViaTsnet,
            onLogoutTsnet = store::logoutMobileTsnetAccount,
            onSelectPairedHost = store::switchPairedHost,
        )
    } else {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Everforest.Bg0)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (ev.key == Key.Escape && state.inSessionFindOpen) {
                        store.closeInSessionFind()
                        return@onPreviewKeyEvent true
                    }
                    val cmdOrCtrl = ev.isMetaPressed || ev.isCtrlPressed
                    if (!cmdOrCtrl) return@onPreviewKeyEvent false
                    when {
                        ev.key == Key.F && ev.isShiftPressed -> {
                            openGlobalSearchPalette()
                            true
                        }
                        ev.key == Key.F && !ev.isShiftPressed -> {
                            if (state.activeSessionId != null) {
                                store.openInSessionFind()
                            } else {
                                openGlobalSearchPalette()
                            }
                            true
                        }
                        ev.key == Key.N && !ev.isShiftPressed -> {
                            store.startNewDraftSession()
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
                            } else {
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
                            onNewSession = store::startNewDraftSession,
                            onOpenUsage = {
                                store.refreshUsageDashboard()
                                showUsageDialog = true
                            },
                            onOpenSettings = { showSettingsDialog = true },
                            onCollapseSidebar = { sidebarExpanded = false },
                            onSelectFilter = store::setDrawerEngineFilter,
                            onSelectSession = store::selectSession,
                            onRefresh = store::refreshHostSessions,
                            onSwitchHost = store::switchPairedHost,
                            onRenameHost = store::renamePairedHost,
                            onPairAnotherHost = store::preparePairAnotherHost,
                            onSearchQueryChange = store::setDrawerSearchQuery,
                            onOpenGlobalSearch = { query -> openGlobalSearchPalette(query) },
                            onToggleArchivedView = store::setDrawerShowArchived,
                            onTogglePinSession = store::togglePinSession,
                            onRequestRenameSession = { sess -> renamingSessionId = sess.id },
                            onToggleArchiveSession = store::archiveSession,
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
                                        text = "正在校验权限并续连当前会话上下文…",
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
                                        onApprovePlan = if (!sessRunning && (planArtifact != null || state.selectedMode == SessionMode.PLAN)) {
                                            { store.approveAndExecutePlan(planArtifact?.title) }
                                        } else {
                                            null
                                        },
                                        pendingJumpSeq = state.pendingJumpSeq,
                                        pendingJumpNonce = state.pendingJumpNonce,
                                        highlightedSeq = state.highlightedSeq,
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

                        DesktopComposer(
                            state = state,
                            text = promptText,
                            onTextChange = { next ->
                                promptText = next
                                store.updateComposerTextForMentions(next)
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
                                val msg = promptText
                                promptText = ""
                                store.sendPrompt(msg)
                            },
                            onCancel = store::cancelActiveTurn,
                            onClearQueuedPrompts = store::clearQueuedPrompts,
                            onPickImage = { store.pickAndAttachImage() },
                            onPasteClipboardImages = { store.pasteImagesFromClipboard(notifyWhenEmpty = false) },
                            onRemoveImage = store::removeAttachedImage,
                            onSelectMentionFile = { relPath ->
                                promptText = replaceTrailingMention(promptText, relPath)
                                store.dismissMentionSuggestions()
                            },
                            onDismissMention = store::dismissMentionSuggestions,
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
                            promptText = "关于方案《$title》，请调整以下部分："
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
                                promptText = "关于方案《$title》，请调整以下部分："
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
                    )
                    DesktopInspectorTab.FILES -> WorkspaceFileTreeSheet(
                        state = state,
                        onNavigateUp = store::navigateUpDirectory,
                        onOpenEntry = store::openEntry,
                        onCloseFile = store::closeOpenedFile,
                        onSelectActiveWorkspaceTab = store::switchBrowseWorkspace,
                        onSwitchWorkspace = onOpenWorkspaceDialog,
                    )
                    DesktopInspectorTab.ARTIFACTS -> ArtifactsInspectorSheet(
                        state = state,
                        onRefresh = { store.refreshSessionArtifacts() },
                        onOpenArtifact = store::openArtifact,
                        onCloseArtifact = store::closeOpenedArtifact,
                        onApprovePlan = { title -> store.approveAndExecutePlan(title) },
                        onRevisePlan = onPrefillRevisePrompt,
                    )
                }
            }
        }
    }
}
