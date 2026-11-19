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
internal fun TasksSidebarContent(
    state: ConsoleUiState,
    onNewSession: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenDiffAndCheckpoints: () -> Unit,
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
                    text = "${connectionChannelLabel(state.discoveredBaseUrl)} · ${state.authUserName ?: "本人"}",
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

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Everforest.Bg1,
                border = BorderStroke(1.dp, Everforest.Bg3),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenUsage() }
                    .testTag("drawer_btn_usage"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueryStats,
                        contentDescription = null,
                        tint = Everforest.Green,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "用量与额度",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Fg,
                        maxLines = 1,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Everforest.Bg1,
                border = BorderStroke(1.dp, Everforest.Bg3),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenDiffAndCheckpoints() }
                    .testTag("drawer_btn_diff"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.CompareArrows,
                        contentDescription = null,
                        tint = Everforest.BoldGold,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "变更与快照",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Fg,
                        maxLines = 1,
                    )
                }
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

