package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.appVersionLabel
import dev.lework.platform.epochMillis
import dev.lework.platform.loadSavedRelayUrls
import dev.lework.protocol.*
import dev.lework.store.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleApp(
    externalStore: ConsoleStore? = null,
    autoBootstrap: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val store = remember(externalStore) { externalStore ?: ConsoleStore(scope) }
    val state by store.state.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showModelSheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showFilesSheet by remember { mutableStateOf(false) }
    var showUsageSheet by remember { mutableStateOf(false) }
    var showDiffSheet by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }

    LaunchedEffect(store, autoBootstrap) {
        if (autoBootstrap) {
            store.bootstrapOrConnect()
        }
    }

    MaterialTheme(colorScheme = EverforestColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Everforest.Bg0,
        ) {
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
                    onLogoutTsnet = store::logoutMobileTsnetAccount,
                    onSelectPairedHost = store::switchPairedHost,
                )
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = Everforest.BgDim,
                            drawerContentColor = Everforest.Fg,
                            modifier = Modifier.width(316.dp),
                        ) {
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
                                onPairAnotherHost = {
                                    scope.launch { drawerState.close() }
                                    store.preparePairAnotherHost()
                                },
                            )
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
                                store.refreshHostSessions()
                                scope.launch { drawerState.open() }
                            },
                            onOpenModelSheet = { showModelSheet = true },
                            onOpenDiffSheet = {
                                store.refreshDiffAndCheckpoints()
                                showDiffSheet = true
                            },
                            onOpenFilesSheet = {
                                store.browseDirectory()
                                showFilesSheet = true
                            },
                        )

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

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            val activeKey = state.activeSessionId ?: "draft"
                            val activeSess = state.activeSession?.takeIf { it.id == state.activeSessionId }
                            val transcript = state.activeTranscript
                            if (state.activeSessionId == null && transcript.items.isEmpty()) {
                                CalmHomeEmptyState(
                                    state = state,
                                    onOpenRunningSession = { sid -> store.selectSession(sid) },
                                )
                            } else {
                                val sessRunning = activeSess != null &&
                                    (activeSess.state == SessionState.RUNNING || activeSess.state == SessionState.WAITING_PERMISSION)
                                key(activeKey) {
                                    ConversationStreamView(
                                        sessionKey = activeKey,
                                        activeSession = activeSess,
                                        transcript = transcript,
                                        isSessionRunning = sessRunning,
                                        onLoadOlder = { store.loadOlderHistory(activeKey) },
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
                                    store.refreshUsageDashboard()
                                    showUsageSheet = true
                                },
                            )
                        }

                        ClaudeStyleComposer(
                            state = state,
                            text = promptText,
                            onTextChange = { promptText = it },
                            onOpenWorkspaceSheet = { showWorkspaceSheet = true },
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
                                val msg = promptText
                                promptText = ""
                                store.sendPrompt(msg)
                            },
                            onCancel = store::cancelActiveTurn,
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
                                if (state.selectedExtraWorkspaces.isEmpty()) {
                                    showWorkspaceSheet = false
                                }
                            },
                            onToggleExtra = store::toggleExtraWorkspace,
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
    val modelLabel = state.selectedModelSpec?.name
        ?.removeSuffix(" (Proxy)")
        ?.let { if (it.length > 24) it.take(23) + "…" else it }
        ?: "默认模型"
    val hasActiveTasks = state.runningSessions.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box {
            IconButton(onClick = onOpenDrawer) {
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
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Everforest.CodeGreen.copy(alpha = 0.8f)),
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Everforest.Fg, fontWeight = FontWeight.SemiBold)) {
                            append(engineLabel)
                        }
                        withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                            append(" · $modelLabel")
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
private fun CalmHomeEmptyState(
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
            text = "在下方直接发起新对话，或从左上角接管本机会话",
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

