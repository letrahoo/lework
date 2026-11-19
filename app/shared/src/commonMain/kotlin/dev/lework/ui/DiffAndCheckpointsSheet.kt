package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.copyTextToClipboard
import dev.lework.platform.epochMillis
import dev.lework.protocol.*
import dev.lework.store.ConsoleUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun DiffAndCheckpointsSheet(
    state: ConsoleUiState,
    onSelectWorkspace: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenFileDiff: (filePath: String?, baseCommitSha: String?, title: String) -> Unit,
    onCloseDiff: () -> Unit,
    onCreateCheckpoint: (String?) -> Unit,
    onRestoreCheckpoint: (CheckpointInfo) -> Unit,
) {
    var activeTab by remember { mutableStateOf(0) } // 0 = 变更对比, 1 = 快照回退
    var confirmingRestoreId by remember { mutableStateOf<String?>(null) }
    var copiedDiff by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val gitStatus = state.gitStatus
    val activeWsList = state.activeWorkspaces
    val currentDiffPath = state.currentDiffWorkspacePath.ifBlank { activeWsList.firstOrNull().orEmpty() }
    val openedDiff = state.openedDiffContent

    // Automatically switch to diff tab when user clicks "对比差异" from a checkpoint row
    LaunchedEffect(openedDiff) {
        if (openedDiff != null) {
            activeTab = 0
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 420.dp, max = 640.dp)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "变更对比与版本快照",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.Fg,
                )
                val subText = buildString {
                    val shortWs = currentDiffPath.trimEnd('/').substringAfterLast('/').ifBlank { "当前工作区" }
                    append(shortWs)
                    gitStatus?.branch?.let { append(" · $it") }
                    if (gitStatus != null && gitStatus.isGitRepo && gitStatus.files.isNotEmpty()) {
                        append(" · +${gitStatus.totalAdditions} / -${gitStatus.totalDeletions}")
                    }
                }
                Text(
                    text = subText,
                    fontSize = 11.5.sp,
                    color = Everforest.CommentGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.gitLoading) {
                    CircularProgressIndicator(
                        color = Everforest.Green,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新变更",
                        tint = Everforest.Grey2,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        // Optional multi-workspace switcher
        if (activeWsList.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (wsPath in activeWsList) {
                    val wsName = wsPath.trimEnd('/').substringAfterLast('/').ifBlank { wsPath }
                    SidebarFilterPill(
                        label = wsName,
                        selected = currentDiffPath == wsPath,
                        onClick = { onSelectWorkspace(wsPath) },
                    )
                }
            }
        }

        // Top Segmented Tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val fileCount = gitStatus?.files?.size ?: 0
            SidebarFilterPill(
                label = if (fileCount > 0) "未提交变更 ($fileCount)" else "未提交变更",
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
            )
            SidebarFilterPill(
                label = if (state.checkpoints.isNotEmpty()) "版本快照 (${state.checkpoints.size})" else "版本快照",
                selected = activeTab == 1,
                onClick = {
                    onCloseDiff()
                    activeTab = 1
                },
            )
        }

        state.checkpointNotice?.let { notice ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Everforest.BgGreen,
                border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = notice,
                    fontSize = 11.5.sp,
                    color = Everforest.Green,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        if (activeTab == 0) {
            if (openedDiff != null) {
                // Detailed Unified Diff Viewer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onCloseDiff() }
                            .padding(vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回列表",
                            tint = Everforest.Green,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            text = state.openedDiffTitle ?: "返回变更列表",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Everforest.BoldFg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = {
                            copyTextToClipboard(openedDiff)
                            copiedDiff = true
                            scope.launch {
                                delay(1_500L)
                                copiedDiff = false
                            }
                        },
                    ) {
                        Text(
                            text = if (copiedDiff) "已复制" else "复制对比",
                            fontSize = 11.5.sp,
                            color = if (copiedDiff) Everforest.Green else Everforest.Grey2,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Everforest.BgDim,
                    border = BorderStroke(1.dp, Everforest.Bg2),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (openedDiff.isBlank()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("该目标与基准版本无代码差异", fontSize = 12.5.sp, color = Everforest.CommentGrey)
                        }
                    } else {
                        val styledDiff = remember(openedDiff) {
                            highlightEverforestCode(openedDiff, "diff")
                        }
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = styledDiff,
                                    fontSize = 11.sp,
                                    lineHeight = 16.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                if (state.openedDiffTruncated) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "… 差异内容较长，已展示前 96 KB",
                                        fontSize = 11.sp,
                                        color = Everforest.CommentGrey,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Changed Files List
                if (gitStatus != null && !gitStatus.isGitRepo) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("当前目录不是 Git 仓库，暂不支持变更对比", fontSize = 12.5.sp, color = Everforest.CommentGrey)
                    }
                } else if (gitStatus == null || gitStatus.files.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircleOutline,
                                contentDescription = null,
                                tint = Everforest.Green,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                text = if (state.gitLoading) "正在扫描工作区变更…" else "当前工作区很干净，没有未提交的代码改动",
                                fontSize = 12.5.sp,
                                color = Everforest.CommentGrey,
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Everforest.Bg2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenFileDiff(null, null, "全部未提交变更 (${gitStatus.files.size} 个文件)") },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.CompareArrows,
                                    contentDescription = null,
                                    tint = Everforest.Green,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "查看整仓合并对比 (${gitStatus.files.size} 个文件)",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Everforest.Fg,
                                )
                            }
                            Text(
                                text = "+${gitStatus.totalAdditions} / -${gitStatus.totalDeletions}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Everforest.Grey2,
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(gitStatus.files, key = { it.path }) { f ->
                            ChangedFileRow(
                                file = f,
                                onClick = { onOpenFileDiff(f.path, null, f.path) },
                            )
                        }
                    }
                }
            }
        } else {
            // Checkpoints / Snapshot Rollback Tab
            val canOperateSession = state.activeSessionId != null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (canOperateSession) {
                        "每次发消息前会自动保存快照，回退前也会自动备份"
                    } else {
                        "请先进入或选择一个会话后再保存/回退快照"
                    },
                    fontSize = 11.sp,
                    color = Everforest.CommentGrey,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onCreateCheckpoint(null) },
                    enabled = canOperateSession && !state.gitLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Everforest.Green,
                        contentColor = Everforest.BgDim,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("btn_create_checkpoint"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("保存当前快照", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (state.checkpoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无版本快照，点击右上角「保存当前快照」即可创建还原点",
                        fontSize = 12.sp,
                        color = Everforest.CommentGrey,
                    )
                }
            } else {
                val nowMs = remember(state.checkpoints) { epochMillis() }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.checkpoints, key = { it.id }) { ckpt ->
                        CheckpointRowCard(
                            checkpoint = ckpt,
                            nowMs = nowMs,
                            isConfirming = confirmingRestoreId == ckpt.id,
                            onDiff = {
                                onOpenFileDiff(null, ckpt.commitSha, "对比快照 · ${ckpt.label}")
                            },
                            onAskRestore = { confirmingRestoreId = ckpt.id },
                            onCancelRestore = { confirmingRestoreId = null },
                            onConfirmRestore = {
                                confirmingRestoreId = null
                                onRestoreCheckpoint(ckpt)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangedFileRow(
    file: GitChangedFileEntry,
    onClick: () -> Unit,
) {
    val (badgeText, badgeColor) = when (file.status) {
        "added" -> "新增" to Everforest.SoftSage
        "untracked" -> "新建" to Everforest.SoftSage
        "deleted" -> "删除" to Everforest.SoftRed
        "renamed" -> "重命名" to Everforest.Aqua
        else -> "修改" to Everforest.BoldGold
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = badgeColor.copy(alpha = 0.16f),
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text = file.path,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.Fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (file.additions > 0) {
                    Text(
                        text = "+${file.additions}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.SoftSage,
                    )
                }
                if (file.deletions > 0) {
                    Text(
                        text = "-${file.deletions}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.SoftRed,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Everforest.Grey0,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun CheckpointRowCard(
    checkpoint: CheckpointInfo,
    nowMs: Long,
    isConfirming: Boolean,
    onDiff: () -> Unit,
    onAskRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onConfirmRestore: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, if (isConfirming) Everforest.BoldGold.copy(alpha = 0.55f) else Everforest.Bg2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = checkpoint.label,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${checkpoint.commitSha.take(8)} · ${formatRelativeAgo(checkpoint.createdAt, nowMs)}",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CommentGrey,
                    )
                }

                if (!isConfirming) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onDiff) {
                            Text("对比差异", fontSize = 11.5.sp, color = Everforest.Grey2)
                        }
                        TextButton(onClick = onAskRestore) {
                            Text("回退到此版本", fontSize = 11.5.sp, color = Everforest.BoldGold)
                        }
                    }
                }
            }

            if (isConfirming) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "确认将工作区已跟踪文件回退到此快照？（回退前会自动备份）",
                        fontSize = 11.sp,
                        color = Everforest.BoldGold,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onCancelRestore) {
                            Text("取消", fontSize = 11.5.sp, color = Everforest.CommentGrey)
                        }
                        Button(
                            onClick = onConfirmRestore,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Everforest.BoldGold,
                                contentColor = Everforest.BgDim,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("确认回退", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

private fun formatRelativeAgo(ts: Long, nowMs: Long): String {
    val diffSec = ((nowMs - ts) / 1_000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "刚刚"
        diffSec < 3_600L -> "${diffSec / 60L} 分钟前"
        diffSec < 86_400L -> "${diffSec / 3_600L} 小时前"
        else -> "${diffSec / 86_400L} 天前"
    }
}
