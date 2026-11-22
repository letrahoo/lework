package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import dev.lework.platform.copyTextToClipboard
import dev.lework.platform.epochMillis
import dev.lework.platform.loadSavedRelayUrls
import dev.lework.platform.signingExpiryRemainingHours
import dev.lework.protocol.*
import dev.lework.store.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksSidebarContent(
    state: ConsoleUiState,
    onNewSession: () -> Unit,
    onOpenUsage: () -> Unit,
    onSelectFilter: (String?) -> Unit,
    onSelectSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onSwitchHost: (String) -> Unit = {},
    onPairAnotherHost: () -> Unit = {},
    onDismissSigningReminder: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onToggleArchivedView: (Boolean) -> Unit = {},
    onRequestRenameSession: (SessionMeta) -> Unit = {},
    onToggleArchiveSession: (sessionId: String, archived: Boolean) -> Unit = { _, _ -> },
) {
    var showHostSwitcher by remember { mutableStateOf(false) }
    val remainingSignHours = remember { signingExpiryRemainingHours() }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                        text = "复制续签命令",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Green,
                        modifier = Modifier.clickable {
                            copyTextToClipboard("./scripts/ios-install.sh --install")
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
                        text = state.hub?.name ?: "本机工作站",
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
                    for (host in state.pairedHosts) {
                        val isCurrent = host.hubId == state.hub?.id
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
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = host.name,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isCurrent) Everforest.Green else Everforest.Fg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isCurrent) {
                                    Text("当前", fontSize = 10.5.sp, color = Everforest.Green)
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

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.drawerShowArchived) {
                item(key = "hdr_archived_hint") {
                    Text(
                        text = "已归档会话（永久保留，点击可查看历史或继续对话）",
                        fontSize = 10.5.sp,
                        color = Everforest.CommentGrey,
                        modifier = Modifier.padding(vertical = 3.dp, horizontal = 4.dp),
                    )
                }
                val archivedList = state.filteredArchivedSessions
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
                } else {
                    items(archivedList, key = { "arch_${it.id}" }) { sess ->
                        SessionDrawerRow(
                            session = sess,
                            selected = sess.id == state.activeSessionId,
                            showQuickUnarchive = true,
                            onClick = { onSelectSession(sess.id) },
                            onRename = { onRequestRenameSession(sess) },
                            onToggleArchive = { onToggleArchiveSession(sess.id, false) },
                        )
                    }
                }
            } else {
                val runningList = state.filteredRunningSessions
                if (runningList.isNotEmpty()) {
                    item(key = "hdr_running") {
                        Text(
                            text = "进行中",
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
                            onRename = { onRequestRenameSession(sess) },
                            onToggleArchive = { onToggleArchiveSession(sess.id, !sess.archived) },
                        )
                    }
                    item(key = "sp_running") { Spacer(Modifier.height(8.dp)) }
                }

                item(key = "hdr_history") {
                    Text(
                        text = "历史会话",
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
                            text = if (state.drawerSearchQuery.isNotBlank()) {
                                "未找到匹配的会话记录"
                            } else {
                                "暂无该引擎的历史记录"
                            },
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
                            onRename = { onRequestRenameSession(sess) },
                            onToggleArchive = { onToggleArchiveSession(sess.id, !sess.archived) },
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
                Text("退出登录", fontSize = 12.sp, color = Everforest.Grey1)
            }
        }
    }
}

@Composable
internal fun SidebarFilterPill(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionDrawerRow(
    session: SessionMeta,
    selected: Boolean,
    showQuickUnarchive: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onToggleArchive: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val wsList = session.workspaces?.filter { it.isNotBlank() }?.distinct().orEmpty()
    val primaryPath = wsList.firstOrNull() ?: session.cwd
    val wsShort = primaryPath.trimEnd('/').substringAfterLast('/').ifBlank { "~" }
    val wsBadge = if (wsList.size > 1) "$wsShort +${wsList.size - 1}" else wsShort
    val isWaiting = session.state == SessionState.WAITING_PERMISSION
    val isRunning = session.state == SessionState.RUNNING

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (selected) Everforest.Bg2 else Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true },
                ),
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isWaiting -> Everforest.Yellow
                                isRunning -> Everforest.Orange
                                session.archived -> Everforest.CommentGrey.copy(alpha = 0.65f)
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
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(session.id, session.title) { mutableStateOf(session.title) }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                    text = "设置清晰的任务名称便于随时查找（同步保存在本机 Hub，不会影响底层会话数据）：",
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
                if (!session.customTitle.isNullOrBlank()) {
                    Text(
                        text = "恢复自动标题",
                        fontSize = 12.sp,
                        color = Everforest.BoldGold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onConfirm("") }
                            .padding(vertical = 2.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = draft.trim().isNotEmpty(),
                onClick = { onConfirm(draft.trim()) },
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
            TextButton(onClick = onDismiss) {
                Text("取消", color = Everforest.Grey1)
            }
        },
    )
}

