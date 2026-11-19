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
                FeishuAuthView(
                    state = state,
                    onLaunchFeishu = store::launchFeishuAppForAuth,
                    onOpenBrowser = store::openFeishuBrowserFallback,
                    onManualConnect = store::connectManualEndpoint,
                    onRetry = store::bootstrapOrConnect,
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
                                onSelectFilter = store::setDrawerEngineFilter,
                                onSelectSession = { sid ->
                                    store.selectSession(sid)
                                    scope.launch { drawerState.close() }
                                },
                                onRefresh = store::refreshHostSessions,
                                onLogout = store::logoutFeishu,
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
                            onOpenFilesSheet = {
                                store.browseDirectory()
                                showFilesSheet = true
                            },
                            onNewSession = store::startNewDraftSession,
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
                                    onOpenWorkspaceSheet = { showWorkspaceSheet = true },
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
                            .firstOrNull { it.sessionId == state.activeSessionId }
                            ?: if (activeSessForPerm?.state == SessionState.WAITING_PERMISSION) {
                                AgentEvent.PermissionRequested(
                                    seq = state.activeTranscript.lastSeq,
                                    sessionId = activeSessForPerm.id,
                                    ts = 0L,
                                    requestId = "fallback_waiting_${activeSessForPerm.id}",
                                    title = "Agent 正在等待确认\n正在同步终端选项，若未显示操作按钮请回电脑对应窗口确认。",
                                    options = emptyList(),
                                )
                            } else {
                                null
                            }
                        if (pendingPerm != null) {
                            InlineApprovalBanner(
                                permission = pendingPerm,
                                onDecision = { optId ->
                                    store.respondApproval(pendingPerm.requestId, optId, pendingPerm.sessionId)
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
                            onSelectEffort = store::selectEffort,
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
            }
        }
    }
}

@Composable
private fun MinimalTopBar(
    state: ConsoleUiState,
    onOpenDrawer: () -> Unit,
    onOpenModelSheet: () -> Unit,
    onOpenFilesSheet: () -> Unit,
    onNewSession: () -> Unit,
) {
    val engineLabel = shortEngineLabel(state.selectedEngineId)
    val modelLabel = state.selectedModelSpec?.name
        ?.removeSuffix(" (Proxy)")
        ?.let { if (it.length > 18) it.take(17) + "…" else it }
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
            IconButton(onClick = onOpenFilesSheet) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = "工作区文件",
                    tint = Everforest.Grey2,
                    modifier = Modifier.size(21.dp),
                )
            }
            IconButton(onClick = onNewSession) {
                Icon(
                    imageVector = Icons.Rounded.EditNote,
                    contentDescription = "新建对话",
                    tint = Everforest.Fg,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
    }
}

@Composable
private fun CalmHomeEmptyState(
    state: ConsoleUiState,
    onOpenWorkspaceSheet: () -> Unit,
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

        Spacer(Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { onOpenWorkspaceSheet() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = Everforest.Aqua,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = state.workspaceDisplayName,
                    fontSize = 12.sp,
                    color = Everforest.Grey2,
                )
                Icon(
                    imageVector = Icons.Rounded.UnfoldMore,
                    contentDescription = null,
                    tint = Everforest.Grey0,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        val running = state.runningSessions.firstOrNull()
        if (running != null) {
            val isWaiting = running.state == SessionState.WAITING_PERMISSION
            val bannerText = when {
                isWaiting -> "${shortEngineLabel(running.engineId)} 等待审批: ${running.title}"
                !running.liveStatusText.isNullOrBlank() ->
                    "${shortEngineLabel(running.engineId)} · ${running.title} (${running.liveStatusText})"
                else -> "${shortEngineLabel(running.engineId)} 正在执行: ${running.title}"
            }
            Spacer(Modifier.height(20.dp))
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

private sealed interface TimelineSegment {
    val key: String
    data class Single(val item: ChatTimelineItem) : TimelineSegment {
        override val key: String = "${item.id}_${item::class.simpleName}"
    }
    data class ProcessTrace(val steps: List<ChatTimelineItem>) : TimelineSegment {
        override val key: String = "trace_${steps.first().id}"
    }
}

private fun groupTimelineSegments(items: List<ChatTimelineItem>): List<TimelineSegment> {
    if (items.isEmpty()) return emptyList()
    val out = mutableListOf<TimelineSegment>()
    val pendingProcess = mutableListOf<ChatTimelineItem>()

    fun flushProcess() {
        if (pendingProcess.isNotEmpty()) {
            out.add(TimelineSegment.ProcessTrace(pendingProcess.toList()))
            pendingProcess.clear()
        }
    }

    for (item in items) {
        if (item is ChatTimelineItem.Tool || item is ChatTimelineItem.Reasoning) {
            pendingProcess.add(item)
        } else {
            flushProcess()
            out.add(TimelineSegment.Single(item))
        }
    }
    flushProcess()
    return out
}

@Composable
private fun ConversationStreamView(
    sessionKey: String,
    activeSession: SessionMeta?,
    transcript: SessionTranscript,
    isSessionRunning: Boolean,
    onLoadOlder: () -> Unit,
) {
    val listState = rememberLazyListState()
    val segments = remember(transcript.items) { groupTimelineSegments(transcript.items) }
    val reversedSegments = remember(segments) { segments.asReversed() }
    val showLiveSpinner = activeSession != null &&
        activeSession.id == sessionKey &&
        activeSession.state == SessionState.RUNNING &&
        transcript.pendingPermissions.isEmpty()
    val queuedPrompts = if (
        activeSession != null &&
        activeSession.id == sessionKey &&
        (activeSession.state == SessionState.RUNNING || activeSession.state == SessionState.WAITING_PERMISSION)
    ) {
        activeSession.queuedPrompts.orEmpty()
    } else {
        emptyList()
    }

    var prevLastSeq by remember(sessionKey) { mutableStateOf(transcript.lastSeq) }
    var prevSpinnerVisible by remember(sessionKey) { mutableStateOf(showLiveSpinner) }
    var prevQueuedCount by remember(sessionKey) { mutableStateOf(queuedPrompts.size) }

    // With `reverseLayout = true`:
    // - Index 0 is ALWAYS the exact pixel bottom (no initial jump or landing mid-message!).
    // - Prepending older history appends items to the END of `reversedSegments` (above viewport),
    //   keeping indices 0..current and ongoing fling velocity 100% untouched (never jumps to top!).
    // - Only when new live events or queued prompts arrive at index 0 while near bottom do we pin to 0.
    LaunchedEffect(transcript.lastSeq, showLiveSpinner, queuedPrompts.size) {
        val hasNewTail = transcript.lastSeq > prevLastSeq ||
            (showLiveSpinner && !prevSpinnerVisible) ||
            queuedPrompts.size > prevQueuedCount
        if (hasNewTail) {
            val nearBottom = listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset < 120
            if (nearBottom) {
                listState.animateScrollToItem(0)
            }
        }
        prevLastSeq = transcript.lastSeq
        prevSpinnerVisible = showLiveSpinner
        prevQueuedCount = queuedPrompts.size
    }

    // Smoothly trigger reverse pagination when scrolling upward near the oldest loaded item
    val nearOldestEdge by remember(reversedSegments.size) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            reversedSegments.isNotEmpty() &&
                listState.isScrollInProgress &&
                lastVisible >= (reversedSegments.size - 3).coerceAtLeast(1)
        }
    }
    LaunchedEffect(nearOldestEdge, transcript.hasMoreBefore, transcript.loadingOlder) {
        if (nearOldestEdge && transcript.hasMoreBefore && !transcript.loadingOlder) {
            onLoadOlder()
        }
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("conversation_lazy_list"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
    ) {
        if (queuedPrompts.isNotEmpty()) {
            item(key = "queued_prompts_card") {
                QueuedPromptsCard(prompts = queuedPrompts)
            }
        }

        if (showLiveSpinner) {
            item(key = "live_spinner_status") {
                ClaudeLiveSpinnerRow(
                    session = activeSession,
                    outputTokens = transcript.outputTokens,
                )
            }
        }

        items(
            items = reversedSegments,
            key = { it.key },
        ) { segment ->
            when (segment) {
                is TimelineSegment.ProcessTrace -> ClaudeProcessTraceView(
                    steps = segment.steps,
                    isSessionRunning = isSessionRunning,
                )
                is TimelineSegment.Single -> MessageTimelineRow(segment.item)
            }
        }

        if (transcript.planEntries.isNotEmpty()) {
            item(key = "plan_card") {
                PlanCard(entries = transcript.planEntries)
            }
        }

        if (transcript.hasMoreBefore || transcript.loadingOlder) {
            item(key = "history_older_loader") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Everforest.Bg1,
                        border = BorderStroke(1.dp, Everforest.Bg2),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = !transcript.loadingOlder) { onLoadOlder() },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (transcript.loadingOlder) {
                                CircularProgressIndicator(
                                    color = Everforest.CommentGrey,
                                    strokeWidth = 1.6.dp,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "正在加载更早记录…",
                                    fontSize = 11.5.sp,
                                    color = Everforest.CommentGrey,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = Everforest.CommentGrey,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = "继续上滑加载更早对话",
                                    fontSize = 11.5.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CLAUDE_SPINNER_FRAMES = listOf("·", "✻", "✽", "✢", "✳", "✶")

@Composable
private fun QueuedPromptsCard(prompts: List<String>) {
    var expanded by remember(prompts) { mutableStateOf(false) }
    val hasLongContent = prompts.size > 2 || prompts.any { it.length > 90 || it.contains('\n') }
    val displayed = if (expanded) prompts else prompts.take(2)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasLongContent) {
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (prompts.size == 1) "↳ 队列待执行" else "↳ 队列待执行 (${prompts.size})",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.CommentGrey,
                )
                if (hasLongContent) {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        fontSize = 11.sp,
                        color = Everforest.CommentGrey,
                    )
                }
            }
            displayed.forEachIndexed { idx, prompt ->
                val cleanText = if (expanded) prompt else prompt.replace('\n', ' ')
                Text(
                    text = if (prompts.size > 1) "${idx + 1}. $cleanText" else cleanText,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    maxLines = if (expanded) 12 else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Everforest.Fg,
                )
            }
            if (!expanded && prompts.size > displayed.size) {
                Text(
                    text = "还有 ${prompts.size - displayed.size} 条待执行…",
                    fontSize = 11.5.sp,
                    color = Everforest.CommentGrey,
                )
            }
        }
    }
}

@Composable
private fun ClaudeLiveSpinnerRow(
    session: SessionMeta,
    outputTokens: Int,
) {
    var tick by remember(session.id) { mutableStateOf(0) }
    var nowMs by remember(session.id) { mutableStateOf(epochMillis()) }

    LaunchedEffect(session.id) {
        while (true) {
            delay(250L)
            tick++
            nowMs = epochMillis()
        }
    }

    val rawLive = session.liveStatusText?.trim().orEmpty()
    val styledLine = if (rawLive.isNotEmpty()) {
        val parenIdx = rawLive.indexOf(" (")
        buildAnnotatedString {
            if (parenIdx > 0) {
                withStyle(SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)) {
                    append(rawLive.substring(0, parenIdx))
                }
                withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                    append(rawLive.substring(parenIdx))
                }
            } else {
                withStyle(SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)) {
                    append(rawLive)
                }
            }
        }
    } else {
        val startMs = session.turnStartedAt ?: session.updatedAt
        val elapsedSec = ((nowMs - startMs) / 1000L).coerceAtLeast(0L)
        val elapsedStr = formatCliDuration(elapsedSec)
        val tokenStr = if (outputTokens > 0) " · ↓ ${formatCliTokens(outputTokens)} tokens" else ""
        val glyph = CLAUDE_SPINNER_FRAMES[tick % CLAUDE_SPINNER_FRAMES.size]
        buildAnnotatedString {
            withStyle(SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)) {
                append("$glyph Thinking…")
            }
            withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                append(" ($elapsedStr$tokenStr)")
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = styledLine,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun formatCliDuration(sec: Long): String {
    if (sec < 60L) return "${sec}s"
    val mins = sec / 60L
    val rem = sec % 60L
    return "${mins}m ${rem}s"
}

private fun formatCliTokens(tokens: Int): String {
    if (tokens < 1000) return tokens.toString()
    val tenths = (tokens * 10) / 1000
    val whole = tenths / 10
    val frac = tenths % 10
    return "$whole.${frac}k"
}

@Composable
private fun PlanCard(entries: List<PlanEntry>) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Checklist,
                    contentDescription = null,
                    tint = Everforest.Aqua,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "执行计划",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.Aqua,
                )
            }
            for (e in entries) {
                val (icon, tint) = when (e.status) {
                    "completed" -> Icons.Rounded.CheckCircle to Everforest.Green
                    "in_progress" -> Icons.Rounded.Autorenew to Everforest.Yellow
                    else -> Icons.Rounded.RadioButtonUnchecked to Everforest.Grey0
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    Text(e.content, fontSize = 12.sp, color = Everforest.Fg)
                }
            }
        }
    }
}

@Composable
private fun MessageTimelineRow(item: ChatTimelineItem) {
    when (item) {
        is ChatTimelineItem.User -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    color = Everforest.Bg2,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    border = BorderStroke(1.dp, Everforest.Bg3),
                    modifier = Modifier.widthIn(max = 316.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = item.text,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = Everforest.Fg,
                        )
                    }
                }
            }
        }
        is ChatTimelineItem.Reasoning -> {
            ClaudeProcessTraceView(steps = listOf(item))
        }
        is ChatTimelineItem.Assistant -> {
            EverforestMarkdownContent(
                markdown = item.text,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        is ChatTimelineItem.Tool -> {
            ClaudeCliToolCallRow(item = item)
        }
        is ChatTimelineItem.Notice -> {
            Surface(
                color = if (item.isError) Everforest.BgRed else Everforest.Bg1,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = if (item.isError) Everforest.Red else Everforest.Grey2,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = item.text,
                        fontSize = 12.sp,
                        color = if (item.isError) Everforest.Red else Everforest.Fg,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineApprovalBanner(
    permission: AgentEvent.PermissionRequested,
    onDecision: (String?) -> Unit,
) {
    val nonBlankLines = remember(permission.title) {
        permission.title.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
    val headerTitle = nonBlankLines.firstOrNull() ?: "需要执行审批"
    val cmdPreview = if (nonBlankLines.size > 1) nonBlankLines.drop(1).joinToString("\n") else null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("inline_approval_banner"),
        shape = RoundedCornerShape(16.dp),
        color = Everforest.BgYellow,
        border = BorderStroke(1.dp, Everforest.Yellow.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = Everforest.BoldGold,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = headerTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.BoldGold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!cmdPreview.isNullOrBlank()) {
                if (permission.options.isEmpty()) {
                    Text(
                        text = cmdPreview,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = Everforest.Fg,
                    )
                } else {
                    val highlightedCmd = remember(cmdPreview) { highlightEverforestCode(cmdPreview, "zsh") }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Everforest.BgDim,
                        border = BorderStroke(1.dp, Everforest.Bg3),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = highlightedCmd,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Everforest.Fg,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            if (permission.options.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (opt in permission.options) {
                        val isReject = opt.kind == PermissionOptionKind.REJECT_ONCE ||
                            opt.kind == PermissionOptionKind.REJECT_ALWAYS
                        if (isReject) {
                            OutlinedButton(
                                onClick = { onDecision(opt.id) },
                                border = BorderStroke(1.dp, Everforest.Red.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Everforest.Red),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(opt.name, fontSize = 12.sp)
                            }
                        } else {
                            Button(
                                onClick = { onDecision(opt.id) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Everforest.Green,
                                    contentColor = Everforest.BgDim,
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(opt.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (permission.requestId.startsWith("tty_perm_")) {
                        TextButton(
                            onClick = { onDecision(null) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("取消 (Esc)", fontSize = 11.5.sp, color = Everforest.CommentGrey)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaudeStyleComposer(
    state: ConsoleUiState,
    text: String,
    onTextChange: (String) -> Unit,
    onOpenWorkspaceSheet: () -> Unit,
    onCycleMode: () -> Unit,
    onCycleEffort: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val activeSess = state.activeSession?.takeIf { it.id == state.activeSessionId }
    val isHubRunning = activeSess != null &&
        activeSess.source == SessionSource.HUB &&
        (activeSess.state == SessionState.RUNNING || activeSess.state == SessionState.WAITING_PERMISSION)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg3),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 28.dp, max = 120.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "发消息给 ${shortEngineLabel(state.selectedEngineId)}…",
                        color = Everforest.CommentGrey,
                        fontSize = 15.sp,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = TextStyle(
                        color = Everforest.Fg,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    ),
                    cursorBrush = SolidColor(Everforest.CodeGreen),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerMetaChip(
                        icon = Icons.Rounded.Folder,
                        label = state.workspaceDisplayName,
                        tint = Everforest.CommentGrey,
                        labelColor = Everforest.Fg,
                        onClick = onOpenWorkspaceSheet,
                    )
                    val modeColor = when (state.selectedMode) {
                        SessionMode.PLAN -> Everforest.CodeGreen
                        SessionMode.AUTO, SessionMode.ACCEPT_EDITS -> Everforest.BoldGold
                        SessionMode.BYPASS_PERMISSIONS -> Everforest.Red
                        SessionMode.DEFAULT -> Everforest.Fg
                    }
                    ComposerMetaChip(
                        icon = null,
                        label = modeDisplayLabel(state.selectedMode),
                        tint = modeColor,
                        labelColor = modeColor,
                        onClick = onCycleMode,
                    )
                    ComposerMetaChip(
                        icon = Icons.Rounded.Bolt,
                        label = effortDisplayLabel(state.selectedEffort),
                        tint = Everforest.CommentGrey,
                        labelColor = Everforest.Grey2,
                        onClick = onCycleEffort,
                    )
                }

                Spacer(Modifier.width(8.dp))

                if (isHubRunning) {
                    Surface(
                        shape = CircleShape,
                        color = Everforest.Red,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { onCancel() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = "停止",
                                tint = Everforest.BgDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    val enabled = text.isNotBlank()
                    Surface(
                        shape = CircleShape,
                        color = if (enabled) Everforest.Green else Everforest.Bg2,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(enabled = enabled) { onSend() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = "发送",
                                tint = if (enabled) Everforest.BgDim else Everforest.Grey0,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerMetaChip(
    icon: ImageVector?,
    label: String,
    tint: Color,
    labelColor: Color = Everforest.Fg,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Everforest.Bg2,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            }
            Text(
                text = label,
                fontSize = 11.sp,
                color = labelColor,
                fontWeight = if (labelColor != Everforest.Fg && labelColor != Everforest.Grey2) {
                    FontWeight.Medium
                } else {
                    FontWeight.Normal
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TasksSidebarContent(
    state: ConsoleUiState,
    onNewSession: () -> Unit,
    onSelectFilter: (String?) -> Unit,
    onSelectSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = state.hub?.name ?: "本机工作站",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.Fg,
                )
                Text(
                    text = "${connectionChannelLabel(state.discoveredBaseUrl)} · ${state.feishuUserName ?: "本人"}",
                    fontSize = 11.sp,
                    color = Everforest.Grey1,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "刷新任务",
                    tint = Everforest.Grey2,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Everforest.BgGreen,
            border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onNewSession() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = Everforest.Green,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "新建会话",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Everforest.Fg,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Filter by Engine
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SidebarFilterPill(
                label = "全部",
                selected = state.drawerEngineFilter == null,
                onClick = { onSelectFilter(null) },
            )
            for (eng in state.engines) {
                SidebarFilterPill(
                    label = shortEngineLabel(eng.id),
                    selected = state.drawerEngineFilter == eng.id,
                    onClick = { onSelectFilter(eng.id) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val runningList = state.runningSessions.filter {
                state.drawerEngineFilter == null || it.engineId == state.drawerEngineFilter
            }
            if (runningList.isNotEmpty()) {
                item(key = "hdr_running") {
                    Text(
                        text = "正在执行",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.Orange,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                    )
                }
                items(runningList, key = { "run_${it.id}" }) { sess ->
                    SessionDrawerRow(
                        session = sess,
                        selected = sess.id == state.activeSessionId,
                        onClick = { onSelectSession(sess.id) },
                    )
                }
                item(key = "sp_running") { Spacer(Modifier.height(8.dp)) }
            }

            item(key = "hdr_history") {
                Text(
                    text = "本机历史任务",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.Grey1,
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                )
            }

            val historyList = state.filteredHistorySessions
            if (historyList.isEmpty()) {
                item(key = "empty_history") {
                    Text(
                        text = "暂无该引擎的历史记录",
                        fontSize = 12.sp,
                        color = Everforest.Grey0,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(historyList, key = { it.id }) { sess ->
                    SessionDrawerRow(
                        session = sess,
                        selected = sess.id == state.activeSessionId,
                        onClick = { onSelectSession(sess.id) },
                    )
                }
            }
        }

        HorizontalDivider(color = Everforest.Bg2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Computer,
                    contentDescription = null,
                    tint = Everforest.Green,
                    modifier = Modifier.size(15.dp),
                )
                Column {
                    Text(
                        text = "${state.engines.count { it.status.ready }} 个引擎在线",
                        fontSize = 12.sp,
                        color = Everforest.Grey2,
                    )
                    Text(
                        text = appVersionLabel(),
                        fontSize = 10.sp,
                        color = Everforest.Grey0,
                    )
                    state.hubFingerprint?.let { fp ->
                        // Same short code as `hub pair` prints on the computer — compare to rule out impostors
                        Text(
                            text = "已加密 · 主机指纹 $fp",
                            fontSize = 10.sp,
                            color = Everforest.Grey0,
                        )
                    }
                }
            }
            TextButton(onClick = onLogout) {
                Text("切换账号", fontSize = 12.sp, color = Everforest.Grey1)
            }
        }
    }
}

@Composable
private fun SidebarFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) Everforest.Bg2 else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) Everforest.CodeGreen.copy(alpha = 0.6f) else Everforest.Bg2),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable { onClick() },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 11.sp,
            color = if (selected) Everforest.CodeGreen else Everforest.Grey1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SessionDrawerRow(
    session: SessionMeta,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val wsList = session.workspaces?.filter { it.isNotBlank() }?.distinct().orEmpty()
    val primaryPath = wsList.firstOrNull() ?: session.cwd
    val wsShort = primaryPath.trimEnd('/').substringAfterLast('/').ifBlank { "~" }
    val wsBadge = if (wsList.size > 1) "$wsShort +${wsList.size - 1}" else wsShort
    val isWaiting = session.state == SessionState.WAITING_PERMISSION
    val isRunning = session.state == SessionState.RUNNING

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Everforest.Bg2 else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isWaiting -> Everforest.Yellow
                            isRunning -> Everforest.Orange
                            else -> engineAccentColor(session.engineId).copy(alpha = 0.75f)
                        },
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    fontSize = 13.sp,
                    color = if (selected) Everforest.BoldGold else Everforest.Fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                val subtitle = if (isRunning && !session.liveStatusText.isNullOrBlank()) {
                    session.liveStatusText
                } else {
                    "${shortEngineLabel(session.engineId)} · $wsBadge"
                }
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = if (isRunning && !session.liveStatusText.isNullOrBlank()) {
                        Everforest.BoldGold
                    } else {
                        Everforest.Grey0
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isWaiting) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Everforest.BgYellow,
                    border = BorderStroke(1.dp, Everforest.Yellow.copy(alpha = 0.55f)),
                ) {
                    Text(
                        text = "待审批",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.BoldGold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            } else if (isRunning && !session.queuedPrompts.isNullOrEmpty()) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Everforest.Bg1,
                    border = BorderStroke(1.dp, Everforest.Bg3),
                ) {
                    Text(
                        text = "队列 ${session.queuedPrompts.size}",
                        fontSize = 10.sp,
                        color = Everforest.CommentGrey,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineModelPickerSheet(
    state: ConsoleUiState,
    onSelectEngineAndModel: (String, EngineModelSpec?, ReasoningEffort?) -> Unit,
    onSelectEffort: (ReasoningEffort) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "选择引擎与模型",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Everforest.Fg,
            )
            TextButton(onClick = onDone) {
                Text("完成", color = Everforest.Green)
            }
        }

        // 1. Engine Selector
        Text("引擎底座", fontSize = 12.sp, color = Everforest.Grey1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (eng in state.engines) {
                val selected = eng.id == state.selectedEngineId
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) Everforest.BgGreen else Everforest.Bg2,
                    border = BorderStroke(
                        1.dp,
                        if (selected) Everforest.Green else Everforest.Bg3,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = eng.status.ready) {
                            onSelectEngineAndModel(eng.id, null, null)
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(engineAccentColor(eng.id)),
                        )
                        Text(
                            text = shortEngineLabel(eng.id),
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (eng.status.ready) Everforest.Fg else Everforest.Grey0,
                        )
                    }
                }
            }
        }

        // 2. Reasoning Effort Selector
        val efforts = state.availableEfforts
        if (efforts.isNotEmpty()) {
            Text("推理等级 (Reasoning Effort)", fontSize = 12.sp, color = Everforest.Grey1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (eff in efforts) {
                    val selected = state.selectedEffort == eff
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) Everforest.BgBlue else Everforest.Bg2,
                        border = BorderStroke(
                            1.dp,
                            if (selected) Everforest.Blue else Everforest.Bg3,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectEffort(eff) },
                    ) {
                        Text(
                            text = effortDisplayLabel(eff),
                            modifier = Modifier.padding(vertical = 8.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Everforest.Blue else Everforest.Fg,
                        )
                    }
                }
            }
        }

        // 3. Model List inside the chosen Engine
        Text("可用模型", fontSize = 12.sp, color = Everforest.Grey1)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.availableModelsForEngine, key = { "${it.providerId}_${it.id}" }) { m ->
                val selected = m.id == state.selectedModelSpec?.id
                val sourceTag = when (m.providerId) {
                    "cpa_local" -> "CLIProxyAPI"
                    null -> ""
                    else -> "官方订阅"
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) Everforest.Bg2 else Everforest.Bg0.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        if (selected) Everforest.Green.copy(alpha = 0.6f) else Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onSelectEngineAndModel(state.selectedEngineId, m, null)
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = m.name.removeSuffix(" (Proxy)"),
                                fontSize = 14.sp,
                                color = Everforest.Fg,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            )
                            if (sourceTag.isNotEmpty()) {
                                Text(
                                    text = sourceTag,
                                    fontSize = 11.sp,
                                    color = if (m.providerId == "cpa_local") Everforest.Aqua else Everforest.Grey1,
                                )
                            }
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Everforest.Green,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspacePickerSheet(
    state: ConsoleUiState,
    onSelectPrimary: (String) -> Unit,
    onToggleExtra: (String) -> Unit,
    onBrowseWorkspaceFiles: (String) -> Unit,
    onStartNewInWorkspace: (String) -> Unit,
    onDone: () -> Unit,
) {
    val activeSess = state.activeSession
    val sessionWsList = state.activeWorkspaces

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (activeSess != null) "会话工作空间" else "选择工作区目录（支持多选关联）",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Everforest.Fg,
            )
            TextButton(onClick = onDone) {
                Text("完成", color = Everforest.CodeGreen)
            }
        }

        if (activeSess != null && sessionWsList.isNotEmpty()) {
            Text(
                text = "当前会话绑定的工作上下文（点击可浏览文件）",
                fontSize = 12.sp,
                color = Everforest.CommentGrey,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sessionWsList.forEachIndexed { idx, wsPath ->
                    val folderName = wsPath.trimEnd('/').substringAfterLast('/').ifBlank { wsPath }
                    val isInitial = idx == 0
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isInitial) Everforest.BgGreen else Everforest.Bg2,
                        border = BorderStroke(
                            1.dp,
                            if (isInitial) Everforest.CodeGreen.copy(alpha = 0.6f) else Everforest.Bg3,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onBrowseWorkspaceFiles(wsPath) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = if (isInitial) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = if (isInitial) Everforest.CodeGreen else Everforest.Aqua,
                                modifier = Modifier.size(19.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = folderName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isInitial) Everforest.BoldGold else Everforest.Fg,
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Everforest.Bg1,
                                    ) {
                                        Text(
                                            text = if (isInitial) "初始目录" else "关联目录",
                                            fontSize = 10.sp,
                                            color = if (isInitial) Everforest.CodeGreen else Everforest.CommentGrey,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = wsPath,
                                    fontSize = 11.sp,
                                    color = Everforest.CommentGrey,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "浏览文件",
                                fontSize = 11.sp,
                                color = Everforest.CodeGreen,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = Everforest.Bg3)
            Text(
                text = "或选择目录启动新会话",
                fontSize = 12.sp,
                color = Everforest.CommentGrey,
            )
        } else {
            Text(
                text = "点击行设为主工作区，点右侧「+ 关联」可附加多个工作区",
                fontSize = 12.sp,
                color = Everforest.CommentGrey,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (activeSess != null) 240.dp else 360.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.workspaces, key = { it.id }) { ws ->
                val isPrimary = activeSess == null && ws.path == state.selectedWorkspacePath
                val isExtra = activeSess == null && state.selectedExtraWorkspaces.contains(ws.path)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        isPrimary -> Everforest.BgGreen
                        isExtra -> Everforest.Bg2
                        else -> Everforest.Bg2.copy(alpha = 0.45f)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isPrimary -> Everforest.CodeGreen.copy(alpha = 0.65f)
                            isExtra -> Everforest.BoldGold.copy(alpha = 0.45f)
                            else -> Color.Transparent
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            if (activeSess != null) {
                                onStartNewInWorkspace(ws.path)
                            } else {
                                onSelectPrimary(ws.path)
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (isPrimary) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = when {
                                isPrimary -> Everforest.CodeGreen
                                isExtra -> Everforest.BoldGold
                                else -> Everforest.Aqua
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ws.name,
                                fontSize = 14.sp,
                                fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (isPrimary) Everforest.BoldGold else Everforest.Fg,
                            )
                            Text(
                                text = ws.path,
                                fontSize = 11.sp,
                                color = Everforest.Grey1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (activeSess == null) {
                            if (isPrimary) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Everforest.Bg1,
                                ) {
                                    Text(
                                        text = "初始目录",
                                        fontSize = 11.sp,
                                        color = Everforest.CodeGreen,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    )
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isExtra) Everforest.BgYellow else Everforest.Bg1,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isExtra) Everforest.BoldGold.copy(alpha = 0.5f) else Everforest.Bg3,
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onToggleExtra(ws.path) },
                                ) {
                                    Text(
                                        text = if (isExtra) "✓ 已关联" else "+ 关联",
                                        fontSize = 11.sp,
                                        color = if (isExtra) Everforest.BoldGold else Everforest.CommentGrey,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFileTreeSheet(
    state: ConsoleUiState,
    onNavigateUp: () -> Unit,
    onOpenEntry: (FsEntry) -> Unit,
    onCloseFile: () -> Unit,
    onSelectActiveWorkspaceTab: (String) -> Unit,
    onSwitchWorkspace: () -> Unit,
) {
    val activeWsList = state.activeWorkspaces

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 380.dp, max = 580.dp)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                IconButton(
                    onClick = {
                        if (state.openedFilePath != null) onCloseFile() else onNavigateUp()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = Everforest.Fg,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column {
                    Text(
                        text = state.workspaceDisplayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.Fg,
                    )
                    Text(
                        text = state.openedFilePath ?: state.currentBrowsePath,
                        fontSize = 11.sp,
                        color = Everforest.Grey1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TextButton(onClick = onSwitchWorkspace) {
                Text("管理工作区", fontSize = 12.sp, color = Everforest.CodeGreen)
            }
        }

        if (activeWsList.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                activeWsList.forEachIndexed { idx, wsPath ->
                    val short = wsPath.trimEnd('/').substringAfterLast('/').ifBlank { wsPath }
                    val isCurrentRoot = state.currentBrowsePath.startsWith(wsPath)
                    SidebarFilterPill(
                        label = if (idx == 0) "$short (主)" else short,
                        selected = isCurrentRoot,
                        onClick = { onSelectActiveWorkspaceTab(wsPath) },
                    )
                }
            }
        }

        HorizontalDivider(color = Everforest.Bg3)

        if (state.openedFilePath != null) {
            val filePath = state.openedFilePath
            val content = state.openedFileContent ?: ""
            val ext = filePath.substringAfterLast('.', "").lowercase()
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Everforest.BgDim,
                border = BorderStroke(1.dp, Everforest.Bg3),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (ext == "md" || ext == "markdown") {
                    Column(
                        modifier = Modifier
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        EverforestMarkdownContent(markdown = content)
                    }
                } else {
                    val highlightedFile = remember(content, ext) {
                        highlightEverforestCode(content, ext)
                    }
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            Text(
                                text = highlightedFile,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Everforest.Fg,
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.dirEntries, key = { it.path }) { entry ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenEntry(entry) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = fileMaterialIcon(entry),
                                contentDescription = null,
                                tint = fileMaterialIconTint(entry),
                                modifier = Modifier.size(19.dp),
                            )
                            Text(
                                text = entry.name,
                                fontSize = 13.sp,
                                color = Everforest.Fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (entry.isDirectory) {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = Everforest.Grey0,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Text(
                                    text = formatBytes(entry.size),
                                    fontSize = 11.sp,
                                    color = Everforest.Grey0,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeishuAuthView(
    state: ConsoleUiState,
    onLaunchFeishu: () -> Unit,
    onOpenBrowser: () -> Unit,
    onManualConnect: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    var showManualForm by remember(state.feishuEnabled, state.connectionStatus) {
        mutableStateOf(!state.feishuEnabled && state.connectionStatus == ConnectionStatus.NEEDS_FEISHU_AUTH)
    }
    var manualHost by remember(state.discoveredBaseUrl) {
        mutableStateOf(state.discoveredBaseUrl)
    }
    var manualToken by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Everforest.Bg0)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = Everforest.BgGreen,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = Everforest.Green,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Text(
                    text = "Lework",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.Fg,
                )

                Text(
                    text = "同一 Wi‑Fi 免配直连 · 异地支持自定义中继与令牌接入",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Everforest.Grey2,
                    textAlign = TextAlign.Center,
                )

                when (state.connectionStatus) {
                    ConnectionStatus.DISCOVERING,
                    ConnectionStatus.CONNECTING,
                    ConnectionStatus.RECONNECTING -> {
                        CircularProgressIndicator(
                            color = Everforest.Green,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = state.feishuStatusText,
                            fontSize = 12.sp,
                            color = Everforest.Grey1,
                        )
                    }
                    ConnectionStatus.NEEDS_FEISHU_AUTH -> {
                        val challenge = state.feishuChallenge
                        if (state.feishuEnabled) {
                            if (challenge != null) {
                                Button(
                                    onClick = onLaunchFeishu,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Everforest.Green,
                                        contentColor = Everforest.BgDim,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp),
                                ) {
                                    Text("唤起授权一键确认", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(onClick = onOpenBrowser) {
                                    Text("在浏览器中打开授权页 (${challenge.userCode})", fontSize = 12.sp, color = Everforest.Grey2)
                                }
                            } else {
                                CircularProgressIndicator(
                                    color = Everforest.Green,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                        Text(
                            text = state.feishuStatusText,
                            fontSize = 12.sp,
                            color = Everforest.Grey1,
                            textAlign = TextAlign.Center,
                        )
                    }
                    ConnectionStatus.ERROR -> {
                        Text(
                            text = state.connectionError ?: "连接失败",
                            fontSize = 13.sp,
                            color = Everforest.Red,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Everforest.Green,
                                contentColor = Everforest.BgDim,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("重试自动发现", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    ConnectionStatus.CONNECTED -> Unit
                }

                TextButton(onClick = { showManualForm = !showManualForm }) {
                    Text(
                        text = if (showManualForm) "收起手动连接配置" else "手动指定主机地址 / 访问令牌",
                        fontSize = 12.sp,
                        color = Everforest.CommentGrey,
                    )
                }

                if (showManualForm) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            label = { Text("主机或中继地址 (如 http://192.168.1.10:64450)", fontSize = 11.5.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = manualToken,
                            onValueChange = { manualToken = it },
                            label = { Text("访问令牌 (可选，留空则仅保存地址重新探测)", fontSize = 11.5.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onManualConnect(manualHost, manualToken) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Everforest.Bg3,
                                contentColor = Everforest.Fg,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保存并连接", fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Text(
                    text = appVersionLabel(),
                    fontSize = 11.sp,
                    color = Everforest.Grey0,
                )
            }
        }
    }
}

private fun connectionChannelLabel(baseUrl: String): String = when {
    loadSavedRelayUrls().any { it.isNotBlank() && baseUrl.startsWith(it) } -> "中继通道直连"
    baseUrl.isNotBlank() -> "局域网 Wi‑Fi 直连"
    else -> "已认证连接"
}

// ---------------------------------------------------------------------------
// Visual Helpers & Material Icon Mapping
// ---------------------------------------------------------------------------

private fun shortEngineLabel(engineId: String): String = when (engineId) {
    "claude" -> "Claude"
    "codex" -> "Codex"
    "agy" -> "AGY"
    "pi" -> "Pi"
    "dsh" -> "DSH"
    else -> engineId
}

private fun engineAccentColor(engineId: String): Color = when (engineId) {
    "claude" -> Everforest.Orange
    "codex" -> Everforest.Green
    "agy" -> Everforest.Blue
    "pi" -> Everforest.Aqua
    "dsh" -> Everforest.Purple
    else -> Everforest.Grey2
}

private fun modeDisplayLabel(mode: SessionMode): String = when (mode) {
    SessionMode.AUTO -> "⏵⏵ Auto"
    SessionMode.ACCEPT_EDITS -> "⏵⏵ Accept edits"
    SessionMode.PLAN -> "⏸ Plan"
    SessionMode.DEFAULT -> "Default"
    SessionMode.BYPASS_PERMISSIONS -> "⏵⏵ Bypass"
}

private fun effortDisplayLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.LOW -> "Low"
    ReasoningEffort.MEDIUM -> "Medium"
    ReasoningEffort.HIGH -> "High"
    ReasoningEffort.XHIGH -> "XHigh"
    ReasoningEffort.MAX -> "Max"
}

private fun fileMaterialIcon(entry: FsEntry): ImageVector {
    if (entry.isDirectory) return Icons.Rounded.Folder
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts", "ts", "tsx", "js", "mjs", "swift", "py", "go", "rs", "java", "c", "cpp", "h" ->
            Icons.Rounded.Code
        "json", "yaml", "yml", "toml", "xml", "plist" ->
            Icons.Rounded.DataObject
        "md", "txt", "rst" ->
            Icons.Rounded.Description
        "sh", "zsh", "bash" ->
            Icons.Rounded.Terminal
        "png", "jpg", "jpeg", "webp", "svg", "gif" ->
            Icons.Rounded.Image
        else ->
            Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

private fun fileMaterialIconTint(entry: FsEntry): Color {
    if (entry.isDirectory) return Everforest.Aqua
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts", "swift" -> Everforest.Orange
        "ts", "tsx", "js", "mjs" -> Everforest.Blue
        "json", "yaml", "yml", "toml" -> Everforest.Yellow
        "md", "txt" -> Everforest.Green
        "sh", "zsh" -> Everforest.Purple
        else -> Everforest.Grey2
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
