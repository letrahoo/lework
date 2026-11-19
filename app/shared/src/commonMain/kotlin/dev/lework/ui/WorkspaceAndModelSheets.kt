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
internal fun EngineModelPickerSheet(
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
internal fun WorkspacePickerSheet(
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
internal fun WorkspaceFileTreeSheet(
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

