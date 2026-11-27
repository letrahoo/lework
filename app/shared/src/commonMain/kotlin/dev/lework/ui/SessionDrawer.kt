package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.PairedHost
import dev.lework.platform.appVersionLabel
import dev.lework.platform.copyTextToClipboard
import dev.lework.platform.epochMillis
import dev.lework.platform.signingExpiryRemainingHours
import dev.lework.protocol.*
import dev.lework.store.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksSidebarContent(
    state: ConsoleUiState,
    onNewSession: () -> Unit,
    onNewSessionInWorkspace: (String) -> Unit = { onNewSession() },
    onOpenUsage: () -> Unit,
    onSelectFilter: (String?) -> Unit,
    onSelectSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onSwitchHost: (String) -> Unit = {},
    onRenameHost: (hubId: String, newName: String) -> Unit = { _, _ -> },
    onPairAnotherHost: () -> Unit = {},
    onDismissSigningReminder: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onToggleArchivedView: (Boolean) -> Unit = {},
    onToggleOnlyRunning: () -> Unit = {},
    onToggleGroupingMode: () -> Unit = {},
    onToggleProjectGroupCollapsed: (String) -> Unit = {},
    onRequestRenameSession: (SessionMeta) -> Unit = {},
    onRegenerateSessionTitle: (String) -> Unit = {},
    onToggleArchiveSession: (sessionId: String, archived: Boolean) -> Unit = { _, _ -> },
    onOpenAppUpdate: () -> Unit = {},
    onCheckAppUpdate: () -> Unit = {},
) {
    var showHostSwitcher by remember { mutableStateOf(false) }
    var renamingHost by remember { mutableStateOf<PairedHost?>(null) }
    var confirmingLogout by remember { mutableStateOf(false) }
    var copiedResignCmd by remember { mutableStateOf(false) }
    LaunchedEffect(copiedResignCmd) {
        if (copiedResignCmd) {
            delay(1_600L)
            copiedResignCmd = false
        }
    }
    val remainingSignHours = remember { signingExpiryRemainingHours() }
    val dismissKeyboard = rememberKeyboardDismisser()
    val drawerListState = rememberLazyListState()
    val isDraggingDrawerList by drawerListState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDraggingDrawerList) {
        if (isDraggingDrawerList) {
            dismissKeyboard()
        }
    }

    val hasRunning = state.runningSessions.any { it.state == SessionState.RUNNING }
    val hasWaiting = state.runningSessions.any { it.state == SessionState.WAITING_PERMISSION }

    ProvideSidebarAnimationPhase(
        hasRunning = hasRunning,
        hasWaiting = hasWaiting,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { dismissKeyboard() }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
        if (remainingSignHours != null && remainingSignHours <= 36 && !state.signingExpiryDismissed) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Everforest.BgYellow,
                border = BorderStroke(1.dp, Everforest.BoldGold.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .testTag("drawer_signing_expiry_banner"),
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
                            text = if (remainingSignHours <= 0) "应用签名已临近失效" else "应用签名约剩 ${remainingSignHours} 小时到期",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Everforest.BoldGold,
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭提醒",
                            tint = Everforest.Grey1,
                            modifier = Modifier
                                .size(15.dp)
                                .clickable { onDismissSigningReminder() },
                        )
                    }
                    Text(
                        text = "个人开发描述文件有效期 7 天，连电脑执行重装即可无损续期。",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Everforest.Grey2,
                    )
                    Text(
                        text = if (copiedResignCmd) "已复制续签命令" else "复制续签命令",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Green,
                        modifier = Modifier.clickable {
                            copyTextToClipboard("./scripts/ios-install.sh --install")
                            copiedResignCmd = true
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showHostSwitcher = !showHostSwitcher }
                    .padding(vertical = 2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = state.activeHostDisplayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.Fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = if (showHostSwitcher) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "切换主机",
                        tint = Everforest.Grey1,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = "${connectionChannelLabel(state.discoveredBaseUrl, state.tsnetStatus)} · ${state.authUserName ?: "本人"}",
                    fontSize = 11.sp,
                    color = Everforest.Grey1,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "刷新会话",
                    tint = Everforest.Grey2,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        if (showHostSwitcher) {
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Everforest.Bg1,
                border = BorderStroke(1.dp, Everforest.Bg3),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val hostList = if (state.hub != null && state.pairedHosts.none { it.hubId == state.hub.id }) {
                        listOf(PairedHost(hubId = state.hub.id, name = state.hub.name)) + state.pairedHosts
                    } else {
                        state.pairedHosts
                    }
                    for (host in hostList) {
                        val isCurrent = host.hubId == state.hub?.id
                        val hasCustomAlias = !host.customName.isNullOrBlank() && host.customName.trim() != host.name
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isCurrent) Everforest.BgGreen else Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showHostSwitcher = false
                                    if (!isCurrent) onSwitchHost(host.hubId)
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = host.displayName,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isCurrent) Everforest.Green else Everforest.Fg,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (hasCustomAlias) {
                                        Text(
                                            text = host.name,
                                            fontSize = 10.sp,
                                            color = Everforest.Grey0,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (isCurrent) {
                                    Text("当前", fontSize = 10.5.sp, color = Everforest.Green)
                                }
                                IconButton(
                                    onClick = {
                                        showHostSwitcher = false
                                        renamingHost = host
                                    },
                                    modifier = Modifier
                                        .size(26.dp)
                                        .testTag("drawer_rename_host_${host.hubId}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "重命名主机",
                                        tint = Everforest.Grey1,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showHostSwitcher = false
                                onPairAnotherHost()
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddLink,
                                contentDescription = null,
                                tint = Everforest.BoldGold,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "配对或连接其他主机",
                                fontSize = 12.sp,
                                color = Everforest.BoldGold,
                            )
                        }
                    }
                }
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

        Spacer(Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpenUsage() }
                .testTag("drawer_btn_usage"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueryStats,
                        contentDescription = null,
                        tint = Everforest.Green,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "用量与额度",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Fg,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Everforest.Grey0,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Quick Search Input (Title / Custom Name / Workspace)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "搜索会话",
                    tint = Everforest.CommentGrey,
                    modifier = Modifier.size(15.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (state.drawerSearchQuery.isEmpty()) {
                        Text(
                            text = "搜索会话名称或工作区…",
                            fontSize = 12.sp,
                            color = Everforest.Grey0,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = state.drawerSearchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { dismissKeyboard() }),
                        textStyle = TextStyle(
                            fontSize = 12.5.sp,
                            color = Everforest.Fg,
                        ),
                        cursorBrush = SolidColor(Everforest.Green),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drawer_search_input"),
                    )
                }
                if (state.drawerSearchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "清空搜索",
                        tint = Everforest.CommentGrey,
                        modifier = Modifier
                            .size(15.dp)
                            .clickable { onSearchQueryChange("") },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

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
                    engineId = eng.id,
                    onClick = { onSelectFilter(eng.id) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Active List vs Archived List Switcher
        val archivedCount = state.archivedSessionsCount
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val activeSelected = !state.drawerShowArchived
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (activeSelected) Everforest.Bg2 else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { onToggleArchivedView(false) }
                        .testTag("drawer_tab_active"),
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 5.5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "会话列表",
                            fontSize = 11.5.sp,
                            fontWeight = if (activeSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (activeSelected) Everforest.Fg else Everforest.Grey1,
                        )
                    }
                }
                val archSelected = state.drawerShowArchived
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (archSelected) Everforest.Bg2 else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { onToggleArchivedView(true) }
                        .testTag("drawer_tab_archived"),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 5.5.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Inventory2,
                            contentDescription = null,
                            tint = if (archSelected) Everforest.BoldGold else Everforest.Grey1,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (archivedCount > 0) "已归档 ($archivedCount)" else "已归档",
                            fontSize = 11.5.sp,
                            fontWeight = if (archSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (archSelected) Everforest.BoldGold else Everforest.Grey1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        val isGroupedByProject = state.sidebarGroupingMode == SidebarGroupingMode.PROJECT
        val collapsedGroupKeys = state.effectiveCollapsedProjectGroupKeys
        val waitingList = remember(
            state.sessions,
            state.drawerSearchQuery,
            state.drawerEngineFilter,
            state.drawerOnlyRunning,
        ) {
            state.floatingWaitingSessions(excludePinned = false)
        }
        val projectGroups = remember(
            state.sessions,
            state.drawerSearchQuery,
            state.drawerEngineFilter,
            state.drawerOnlyRunning,
            state.workspaces,
            state.sessionDrafts,
            state.sessionAttachedImages,
            state.activeSessionId,
        ) {
            state.sidebarProjectGroups(excludePinned = false, excludeFloatingWaiting = true)
        }
        val flatTimeList = remember(
            state.sessions,
            state.drawerSearchQuery,
            state.drawerEngineFilter,
            state.drawerOnlyRunning,
        ) {
            state.flatTimeSidebarSessions(excludePinned = false, excludeFloatingWaiting = true)
        }
        val archivedList = state.filteredArchivedSessions
        val archivedGroups = remember(archivedList, state.workspaces, state.sessionDrafts, state.sessionAttachedImages, state.activeSessionId) {
            state.archivedProjectGroups
        }

        LazyColumn(
            state = drawerListState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.drawerShowArchived) {
                item(key = "hdr_archived_hint") {
                    MobileSidebarSectionHeader(
                        title = if (isGroupedByProject && archivedGroups.isNotEmpty()) {
                            "已归档 · ${archivedGroups.size} 个项目"
                        } else {
                            "已归档会话"
                        },
                        isGroupedByProject = isGroupedByProject,
                        onToggleGroupingMode = onToggleGroupingMode,
                    )
                }
                if (archivedList.isEmpty()) {
                    item(key = "empty_archived") {
                        Text(
                            text = if (state.drawerSearchQuery.isNotBlank()) {
                                "未找到匹配的已归档会话"
                            } else {
                                "暂无归档会话\n在任意会话右侧点「⋯」即可归档收纳，记录不会删除"
                            },
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Everforest.Grey0,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 14.dp),
                        )
                    }
                } else if (isGroupedByProject) {
                    for (group in archivedGroups) {
                        val collapsed = group.key in collapsedGroupKeys
                        item(key = "arch_grp_${group.key}") {
                            MobileProjectGroupHeader(
                                group = group,
                                collapsed = collapsed,
                                showNewSessionButton = false,
                                onToggleCollapse = { onToggleProjectGroupCollapsed(group.key) },
                                onNewSessionInProject = {},
                            )
                        }
                        if (!collapsed) {
                            items(group.sessions, key = { "arch_${it.id}" }) { sess ->
                                SessionDrawerRow(
                                    session = sess,
                                    selected = sess.id == state.activeSessionId,
                                    showQuickUnarchive = true,
                                    groupedProjectDisplayName = group.displayName,
                                    draftPreview = state.draftPreviewFor(sess.id),
                                    onClick = {
                                        dismissKeyboard()
                                        onSelectSession(sess.id)
                                    },
                                    onRename = {
                                        dismissKeyboard()
                                        onRequestRenameSession(sess)
                                    },
                                    onRegenerateTitle = {
                                        dismissKeyboard()
                                        onRegenerateSessionTitle(sess.id)
                                    },
                                    onToggleArchive = {
                                        dismissKeyboard()
                                        onToggleArchiveSession(sess.id, false)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    items(archivedList, key = { "arch_${it.id}" }) { sess ->
                        SessionDrawerRow(
                            session = sess,
                            selected = sess.id == state.activeSessionId,
                            showQuickUnarchive = true,
                            draftPreview = state.draftPreviewFor(sess.id),
                            onClick = {
                                dismissKeyboard()
                                onSelectSession(sess.id)
                            },
                            onRename = {
                                dismissKeyboard()
                                onRequestRenameSession(sess)
                            },
                            onRegenerateTitle = {
                                dismissKeyboard()
                                onRegenerateSessionTitle(sess.id)
                            },
                            onToggleArchive = {
                                dismissKeyboard()
                                onToggleArchiveSession(sess.id, false)
                            },
                        )
                    }
                }
            } else {
                val newDraftPreview = if (state.activeSessionId != null) state.draftPreviewFor(null) else null
                val query = state.drawerSearchQuery.trim()
                if (
                    newDraftPreview != null &&
                    (query.isEmpty() || newDraftPreview.contains(query, ignoreCase = true) || "新会话草稿".contains(query))
                ) {
                    item(key = "drawer_new_session_draft_row") {
                        MobileNewSessionDraftRow(
                            draftPreview = newDraftPreview,
                            selected = state.activeSessionId == null,
                            onClick = {
                                dismissKeyboard()
                                onNewSession()
                            },
                        )
                    }
                }
                if (waitingList.isNotEmpty()) {
                    item(key = "hdr_waiting") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                                .testTag("drawer_hdr_waiting"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                text = "等待确认",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.BoldGold,
                            )
                            Text(
                                text = "${waitingList.size}",
                                fontSize = 10.sp,
                                color = Everforest.BoldGold.copy(alpha = 0.78f),
                            )
                        }
                    }
                    items(waitingList, key = { "wait_${it.id}" }) { sess ->
                        SessionDrawerRow(
                            session = sess,
                            selected = sess.id == state.activeSessionId,
                            draftPreview = state.draftPreviewFor(sess.id),
                            onClick = {
                                dismissKeyboard()
                                onSelectSession(sess.id)
                            },
                            onRename = {
                                dismissKeyboard()
                                onRequestRenameSession(sess)
                            },
                            onRegenerateTitle = {
                                dismissKeyboard()
                                onRegenerateSessionTitle(sess.id)
                            },
                            onToggleArchive = {
                                dismissKeyboard()
                                onToggleArchiveSession(sess.id, !sess.archived)
                            },
                        )
                    }
                    item(key = "sp_waiting") { Spacer(Modifier.height(6.dp)) }
                }

                item(key = "hdr_history") {
                    MobileSidebarSectionHeader(
                        title = if (isGroupedByProject && projectGroups.isNotEmpty()) {
                            "项目 · ${projectGroups.size}"
                        } else {
                            "会话"
                        },
                        isGroupedByProject = isGroupedByProject,
                        runningCount = state.totalActiveRunningCount,
                        onlyRunning = state.drawerOnlyRunning,
                        showRunningFilter = state.totalActiveRunningCount > 0 || state.drawerOnlyRunning,
                        onToggleOnlyRunning = onToggleOnlyRunning,
                        onToggleGroupingMode = onToggleGroupingMode,
                    )
                }

                val noMainItems = if (isGroupedByProject) projectGroups.isEmpty() else flatTimeList.isEmpty()
                if (noMainItems && waitingList.isEmpty()) {
                    item(key = "empty_history") {
                        Text(
                            text = when {
                                state.drawerOnlyRunning -> "当前没有进行中或待审批的会话"
                                state.drawerSearchQuery.isNotBlank() -> "未找到匹配的会话记录"
                                else -> "暂无该引擎的历史记录"
                            },
                            fontSize = 12.sp,
                            color = Everforest.Grey0,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                        )
                    }
                } else if (isGroupedByProject) {
                    for (group in projectGroups) {
                        val collapsed = group.key in collapsedGroupKeys
                        item(key = "grp_${group.key}") {
                            MobileProjectGroupHeader(
                                group = group,
                                collapsed = collapsed,
                                showNewSessionButton = !group.isFallback && group.fullPath.isNotEmpty(),
                                onToggleCollapse = { onToggleProjectGroupCollapsed(group.key) },
                                onNewSessionInProject = {
                                    dismissKeyboard()
                                    onNewSessionInWorkspace(group.fullPath)
                                },
                            )
                        }
                        if (!collapsed) {
                            items(group.sessions, key = { it.id }) { sess ->
                                SessionDrawerRow(
                                    session = sess,
                                    selected = sess.id == state.activeSessionId,
                                    groupedProjectDisplayName = group.displayName,
                                    draftPreview = state.draftPreviewFor(sess.id),
                                    onClick = {
                                        dismissKeyboard()
                                        onSelectSession(sess.id)
                                    },
                                    onRename = {
                                        dismissKeyboard()
                                        onRequestRenameSession(sess)
                                    },
                                    onRegenerateTitle = {
                                        dismissKeyboard()
                                        onRegenerateSessionTitle(sess.id)
                                    },
                                    onToggleArchive = {
                                        dismissKeyboard()
                                        onToggleArchiveSession(sess.id, !sess.archived)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    items(flatTimeList, key = { it.id }) { sess ->
                        SessionDrawerRow(
                            session = sess,
                            selected = sess.id == state.activeSessionId,
                            draftPreview = state.draftPreviewFor(sess.id),
                            onClick = {
                                dismissKeyboard()
                                onSelectSession(sess.id)
                            },
                            onRename = {
                                dismissKeyboard()
                                onRequestRenameSession(sess)
                            },
                            onRegenerateTitle = {
                                dismissKeyboard()
                                onRegenerateSessionTitle(sess.id)
                            },
                            onToggleArchive = {
                                dismissKeyboard()
                                onToggleArchiveSession(sess.id, !sess.archived)
                            },
                        )
                    }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = appVersionLabel(),
                            fontSize = 10.sp,
                            color = Everforest.Grey0,
                        )
                        val latest = state.appUpdate.latestRelease
                        if (state.appUpdate.hasUpdate && latest != null) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Everforest.BgGreen,
                                border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable { onOpenAppUpdate() }
                                    .testTag("drawer_update_pill"),
                            ) {
                                Text(
                                    text = "新版本 ${latest.tagName}",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Everforest.Green,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        } else {
                            Text(
                                text = if (state.appUpdate.checking) "检查更新中…" else "检查更新",
                                fontSize = 10.sp,
                                color = Everforest.CommentGrey,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(enabled = !state.appUpdate.checking) { onCheckAppUpdate() }
                                    .padding(horizontal = 3.dp, vertical = 1.dp)
                                    .testTag("drawer_check_update_btn"),
                            )
                        }
                    }
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
            TextButton(
                onClick = { confirmingLogout = true },
                modifier = Modifier.testTag("drawer_btn_logout"),
            ) {
                Text("断开连接", fontSize = 12.sp, color = Everforest.Grey1)
            }
        }
        }
    }

    if (confirmingLogout) {
        ModalEscapeEffect(onDismiss = { confirmingLogout = false })
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            containerColor = Everforest.Bg1,
            titleContentColor = Everforest.Fg,
            textContentColor = Everforest.Grey2,
            title = { Text("确认断开当前主机连接？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    text = "断开后将退出当前连接，主机上的历史会话和项目文件均不受影响。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmingLogout = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Everforest.Red,
                        contentColor = Everforest.BgDim,
                    ),
                    modifier = Modifier.testTag("drawer_confirm_logout_btn"),
                ) {
                    Text("确认断开", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLogout = false }) {
                    Text("取消", color = Everforest.Grey1)
                }
            },
        )
    }

    val targetHost = renamingHost
    if (targetHost != null) {
        RenameHostDialog(
            host = targetHost,
            onDismiss = { renamingHost = null },
            onConfirm = { nextName ->
                renamingHost = null
                onRenameHost(targetHost.hubId, nextName)
            },
        )
    }
}

@Composable
internal fun SidebarFilterPill(
    label: String,
    selected: Boolean,
    engineId: String? = null,
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
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (engineId != null) {
                EngineGlyphIcon(engineId = engineId, size = 12.dp)
            }
            Text(
                text = label,
                fontSize = 11.sp,
                color = if (selected) Everforest.CodeGreen else Everforest.Grey1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun MobileNewSessionDraftRow(
    draftPreview: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val subtitleAnnotated = remember(draftPreview) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = Everforest.SoftRed, fontWeight = FontWeight.SemiBold)) {
                append("[草稿] ")
            }
            withStyle(SpanStyle(color = Everforest.Grey2)) {
                append(draftPreview)
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Everforest.Bg2 else Everforest.Bg1.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Everforest.SoftRed.copy(alpha = if (selected) 0.48f else 0.28f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .testTag("session_row_new_draft")
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Everforest.SoftRed),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "新会话",
                    fontSize = 13.sp,
                    color = if (selected) Everforest.BoldGold else Everforest.Fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitleAnnotated,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Everforest.BgRed,
                border = BorderStroke(1.dp, Everforest.SoftRed.copy(alpha = 0.45f)),
                modifier = Modifier.testTag("session_draft_badge_new"),
            ) {
                Text(
                    text = "草稿",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.SoftRed,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MobileSidebarSectionHeader(
    title: String,
    isGroupedByProject: Boolean,
    runningCount: Int = 0,
    onlyRunning: Boolean = false,
    showRunningFilter: Boolean = false,
    onToggleOnlyRunning: () -> Unit = {},
    onToggleGroupingMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Everforest.Grey1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showRunningFilter) {
                SidebarRunningFilterPill(
                    runningCount = runningCount,
                    active = onlyRunning,
                    onClick = onToggleOnlyRunning,
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (isGroupedByProject) Everforest.Bg1 else Color.Transparent,
                border = BorderStroke(
                    1.dp,
                    if (isGroupedByProject) Everforest.Green.copy(alpha = 0.4f) else Everforest.Bg2,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onToggleGroupingMode)
                    .testTag("drawer_btn_toggle_grouping"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (isGroupedByProject) Icons.Rounded.FolderCopy else Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = if (isGroupedByProject) Everforest.Green else Everforest.CommentGrey,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = if (isGroupedByProject) "按项目" else "按时间",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isGroupedByProject) Everforest.Green else Everforest.Grey1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileProjectGroupHeader(
    group: SessionProjectGroup,
    collapsed: Boolean,
    showNewSessionButton: Boolean,
    onToggleCollapse: () -> Unit,
    onNewSessionInProject: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Everforest.Bg1.copy(alpha = 0.45f))
            .clickable(onClick = onToggleCollapse)
            .padding(start = 6.dp, end = 4.dp, top = 5.dp, bottom = 5.dp)
            .testTag("project_group_header_${group.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (collapsed) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (collapsed) "展开项目" else "折叠项目",
            tint = Everforest.CommentGrey,
            modifier = Modifier.size(15.dp),
        )
        Icon(
            imageVector = if (collapsed) Icons.Rounded.Folder else Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = if (group.isFallback) Everforest.Grey1 else Everforest.Aqua.copy(alpha = 0.88f),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = group.displayName,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (group.isFallback) Everforest.Grey1 else Everforest.HeadingWarm,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        SidebarHeaderActivityBadges(
            runningCount = group.runningCount,
            waitingCount = group.waitingPermissionCount,
        )
        if (collapsed && group.draftCount > 0) {
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .clip(CircleShape)
                    .background(Everforest.SoftRed),
            )
        }
        Text(
            text = "${group.sessions.size}",
            fontSize = 10.5.sp,
            color = Everforest.CommentGrey,
        )
        if (showNewSessionButton) {
            IconButton(
                onClick = onNewSessionInProject,
                modifier = Modifier
                    .size(24.dp)
                    .testTag("project_group_new_${group.key}"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "在 ${group.displayName} 中新建会话",
                    tint = Everforest.Green,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionDrawerRow(
    session: SessionMeta,
    selected: Boolean,
    showQuickUnarchive: Boolean = false,
    groupedProjectDisplayName: String? = null,
    draftPreview: String? = null,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onRegenerateTitle: () -> Unit = {},
    onToggleArchive: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isWaiting = session.state == SessionState.WAITING_PERMISSION
    val isRunning = session.state == SessionState.RUNNING
    val titleColor = if (selected) Everforest.BoldGold else Everforest.Fg
    val scopeSplit = remember(session.title, groupedProjectDisplayName) {
        splitMatchingScopePrefix(session.title, groupedProjectDisplayName)
    }
    val nowMs = if (isRunning) {
        LocalSidebarAnimationPhase.current.nowMs.value
    } else {
        remember(session.id, session.updatedAt) { epochMillis() }
    }
    val subtitleAnnotated = remember(session, selected, groupedProjectDisplayName, draftPreview, nowMs) {
        buildSidebarSessionSubtitle(
            session = session,
            selected = selected,
            groupedProjectDisplayName = groupedProjectDisplayName,
            draftPreview = draftPreview,
            nowMs = nowMs,
        )
    }
    val showsDraftPreviewTag = !selected && draftPreview != null && !isWaiting && !isRunning

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = when {
                selected -> Everforest.Bg2
                isWaiting -> Everforest.BgYellow.copy(alpha = 0.24f)
                else -> Color.Transparent
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("session_row_${session.id}")
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true },
                ),
        ) {
            Row(
                modifier = Modifier.padding(
                    start = if (groupedProjectDisplayName != null) 18.dp else 9.dp,
                    end = 6.dp,
                    top = 7.5.dp,
                    bottom = 7.5.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SidebarSessionLeadingIndicator(session = session)
                Column(modifier = Modifier.weight(1f)) {
                    if (scopeSplit != null) {
                        val annotatedTitle = remember(scopeSplit, selected) {
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = if (selected) Everforest.BoldGold.copy(alpha = 0.65f) else Everforest.CommentGrey)) {
                                    append(scopeSplit.first)
                                }
                                withStyle(SpanStyle(color = titleColor)) {
                                    append(scopeSplit.second)
                                }
                            }
                        }
                        Text(
                            text = annotatedTitle,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    } else {
                        Text(
                            text = session.title,
                            fontSize = 13.sp,
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    Text(
                        text = subtitleAnnotated,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (showsDraftPreviewTag) {
                            Modifier.testTag("session_draft_preview_${session.id}")
                        } else {
                            Modifier
                        },
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
                } else if (!selected && draftPreview != null) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Everforest.BgRed,
                        border = BorderStroke(1.dp, Everforest.SoftRed.copy(alpha = 0.45f)),
                        modifier = Modifier.testTag("session_draft_badge_${session.id}"),
                    ) {
                        Text(
                            text = "草稿",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Everforest.SoftRed,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                if (showQuickUnarchive) {
                    IconButton(
                        onClick = onToggleArchive,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("session_unarchive_${session.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Unarchive,
                            contentDescription = "移出归档",
                            tint = Everforest.Green,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("session_more_${session.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = "会话操作",
                        tint = Everforest.CommentGrey,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = Everforest.Bg1,
        ) {
            DropdownMenuItem(
                text = { Text("重命名会话", fontSize = 13.sp, color = Everforest.Fg) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = Everforest.BoldGold,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    menuExpanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("智能生成标题", fontSize = 13.sp, color = Everforest.Fg) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Everforest.Green,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    menuExpanded = false
                    onRegenerateTitle()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (session.archived) "移出归档" else "归档会话",
                        fontSize = 13.sp,
                        color = Everforest.Fg,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (session.archived) Icons.Rounded.Unarchive else Icons.Rounded.Inventory2,
                        contentDescription = null,
                        tint = if (session.archived) Everforest.Green else Everforest.CommentGrey,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    menuExpanded = false
                    onToggleArchive()
                },
            )
        }
    }
}

@Composable
internal fun RenameSessionDialog(
    session: SessionMeta,
    isRegeneratingTitle: Boolean = false,
    onRegenerateTitle: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(session.id, session.title) { mutableStateOf(session.title) }
    val dismissKeyboard = rememberKeyboardDismisser()

    ModalEscapeEffect(
        onDismiss = {
            dismissKeyboard()
            onDismiss()
        },
    )

    AlertDialog(
        onDismissRequest = {
            dismissKeyboard()
            onDismiss()
        },
        containerColor = Everforest.Bg1,
        titleContentColor = Everforest.Fg,
        textContentColor = Everforest.Grey2,
        title = {
            Text(
                text = "重命名会话",
                fontSize = 16.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "设置清晰的任务名称便于随时查找（仅修改显示名称，不影响原始对话记录）：",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Everforest.CommentGrey,
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Everforest.BgDim,
                    border = BorderStroke(1.dp, Everforest.Bg3),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "输入自定义会话名称…",
                                    fontSize = 14.sp,
                                    color = Everforest.Grey0,
                                )
                            }
                            BasicTextField(
                                value = draft,
                                onValueChange = { if (it.length <= 120) draft = it },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (draft.trim().isNotEmpty()) {
                                            dismissKeyboard()
                                            onConfirm(draft.trim())
                                        }
                                    },
                                ),
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    color = Everforest.Fg,
                                ),
                                cursorBrush = SolidColor(Everforest.Green),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("rename_session_input"),
                            )
                        }
                        if (draft.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "清空",
                                tint = Everforest.CommentGrey,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { draft = "" },
                            )
                        }
                    }
                }
                if (onRegenerateTitle != null || !session.customTitle.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onRegenerateTitle != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = !isRegeneratingTitle) {
                                        dismissKeyboard()
                                        onRegenerateTitle()
                                    }
                                    .padding(vertical = 2.dp, horizontal = 2.dp)
                                    .testTag("rename_session_regenerate"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = Everforest.Green,
                                    modifier = Modifier.size(13.5.dp),
                                )
                                Text(
                                    text = if (isRegeneratingTitle) "正在生成标题…" else "智能生成标题",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Everforest.Green,
                                )
                            }
                        }
                        if (!session.customTitle.isNullOrBlank()) {
                            Text(
                                text = "恢复自动标题",
                                fontSize = 12.sp,
                                color = Everforest.BoldGold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onConfirm("") }
                                    .padding(vertical = 2.dp)
                                    .testTag("rename_session_restore_auto"),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = draft.trim().isNotEmpty(),
                onClick = {
                    dismissKeyboard()
                    onConfirm(draft.trim())
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Everforest.Green,
                    contentColor = Everforest.BgDim,
                ),
                modifier = Modifier.testTag("rename_session_confirm"),
            ) {
                Text("保存", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    dismissKeyboard()
                    onDismiss()
                },
            ) {
                Text("取消", color = Everforest.Grey1)
            }
        },
    )
}

@Composable
internal fun RenameHostDialog(
    host: PairedHost,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(host.hubId, host.displayName) { mutableStateOf(host.displayName) }
    val dismissKeyboard = rememberKeyboardDismisser()

    ModalEscapeEffect(
        onDismiss = {
            dismissKeyboard()
            onDismiss()
        },
    )

    AlertDialog(
        onDismissRequest = {
            dismissKeyboard()
            onDismiss()
        },
        containerColor = Everforest.Bg1,
        titleContentColor = Everforest.Fg,
        textContentColor = Everforest.Grey2,
        title = {
            Text(
                text = "重命名主机",
                fontSize = 16.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "设置易于识别的主机名称（默认名称：${host.name}，仅保存在本客户端）：",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Everforest.CommentGrey,
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Everforest.BgDim,
                    border = BorderStroke(1.dp, Everforest.Bg3),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "输入主机名称…",
                                    fontSize = 14.sp,
                                    color = Everforest.Grey0,
                                )
                            }
                            BasicTextField(
                                value = draft,
                                onValueChange = { if (it.length <= 64) draft = it },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (draft.trim().isNotEmpty()) {
                                            dismissKeyboard()
                                            onConfirm(draft.trim())
                                        }
                                    },
                                ),
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    color = Everforest.Fg,
                                ),
                                cursorBrush = SolidColor(Everforest.Green),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("rename_host_input"),
                            )
                        }
                        if (draft.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "清空",
                                tint = Everforest.CommentGrey,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { draft = "" },
                            )
                        }
                    }
                }
                if (!host.customName.isNullOrBlank()) {
                    Text(
                        text = "恢复默认名称",
                        fontSize = 12.sp,
                        color = Everforest.BoldGold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                dismissKeyboard()
                                onConfirm("")
                            }
                            .padding(vertical = 2.dp)
                            .testTag("rename_host_reset"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = draft.trim().isNotEmpty(),
                onClick = {
                    dismissKeyboard()
                    onConfirm(draft.trim())
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Everforest.Green,
                    contentColor = Everforest.BgDim,
                ),
                modifier = Modifier.testTag("rename_host_confirm"),
            ) {
                Text("保存", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    dismissKeyboard()
                    onDismiss()
                },
            ) {
                Text("取消", color = Everforest.Grey1)
            }
        },
    )
}

