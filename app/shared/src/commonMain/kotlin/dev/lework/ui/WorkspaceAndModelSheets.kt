package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.protocol.*
import dev.lework.store.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EngineModelPickerSheet(
    state: ConsoleUiState,
    onSelectEngineAndModel: (String, EngineModelSpec?, ReasoningEffort?) -> Unit,
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
        Text("编码引擎", fontSize = 12.sp, color = Everforest.Grey1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.taskEngines.isEmpty()) Text("当前主机没有可选引擎", color = Everforest.Grey1)
            for (eng in state.taskEngines) {
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
                        .clickable(enabled = eng.canStartTask) {
                            onSelectEngineAndModel(eng.id, null, null)
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        EngineGlyphIcon(
                            engineId = eng.id,
                            size = 15.dp,
                            tintOverride = if (eng.canStartTask) null else Everforest.Grey0.copy(alpha = 0.45f),
                        )
                        Text(
                            text = "${shortEngineLabel(eng.id)} · ${eng.readinessLabel}",
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (eng.canStartTask) Everforest.Fg else Everforest.Grey0,
                        )
                    }
                }
            }
        }

        // 2. Model List inside the chosen Engine
        val selectable = state.taskEngines.firstOrNull { it.id == state.selectedEngineId && it.canStartTask }
        Text(if (selectable != null && selectable.models.isEmpty()) "模型由主机 CLI 决定" else "可用模型", fontSize = 12.sp, color = Everforest.Grey1)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val engineModels = selectable?.models.orEmpty()
            items(engineModels, key = { "${it.providerId}_${it.id}" }) { m ->
                val activeSpec = state.selectedModelSpec
                val selected = m.id == activeSpec?.id &&
                    (m.providerId == activeSpec?.providerId ||
                        (activeSpec?.providerId == null && !m.isExternalProvider()))
                val sourceTag = m.sourceBadgeLabel(engineModels, state.providers).orEmpty()
                val isExternal = m.isExternalProvider()
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
                                    color = if (isExternal) Everforest.Aqua else Everforest.Green,
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
    onToggleUseWorktree: (Boolean) -> Unit = {},
    onChangeWorktreeBranch: (String) -> Unit = {},
    onBrowseWorkspaceFiles: (String) -> Unit,
    onStartNewInWorkspace: (String) -> Unit,
    onDone: () -> Unit,
) {
    val activeSess = state.activeSession
    val activeWt = activeSess?.worktree
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
                text = if (activeSess != null) "当前会话目录" else "选择工作区目录",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Everforest.Fg,
            )
            TextButton(onClick = onDone) {
                Text("完成", color = Everforest.CodeGreen)
            }
        }

        if (activeSess != null && sessionWsList.isNotEmpty()) {
            if (activeWt != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Everforest.BgGreen,
                    border = BorderStroke(1.dp, Everforest.Aqua.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workspace_active_worktree_card"),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AccountTree,
                                contentDescription = null,
                                tint = Everforest.Aqua,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "独立分支工作区 · ${activeWt.branch}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.Aqua,
                            )
                        }
                        Text(
                            text = if (activeWt.cleanedUp == true) {
                                "归档后已自动清理临时目录 · 主仓库：${activeWt.baseRepoPath}"
                            } else {
                                "主仓库：${activeWt.baseRepoPath}"
                            },
                            fontSize = 11.sp,
                            color = Everforest.Grey1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
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
                                            text = if (isInitial) "主目录" else "关联目录",
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
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (state.draftUseWorktree) Everforest.BgGreen else Everforest.Bg2.copy(alpha = 0.65f),
                border = BorderStroke(
                    1.dp,
                    if (state.draftUseWorktree) Everforest.Aqua.copy(alpha = 0.6f) else Everforest.Bg3,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("workspace_worktree_toggle_card"),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AccountTree,
                                contentDescription = null,
                                tint = if (state.draftUseWorktree) Everforest.Aqua else Everforest.Grey1,
                                modifier = Modifier.size(18.dp),
                            )
                            Column {
                                Text(
                                    text = "独立分支工作区",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Everforest.Fg,
                                )
                                Text(
                                    text = "在独立 Git 分支目录中运行此会话，与主工作区代码互不干扰",
                                    fontSize = 11.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                        Switch(
                            checked = state.draftUseWorktree,
                            onCheckedChange = onToggleUseWorktree,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Everforest.BgDim,
                                checkedTrackColor = Everforest.Aqua,
                                uncheckedThumbColor = Everforest.Grey1,
                                uncheckedTrackColor = Everforest.Bg0,
                            ),
                            modifier = Modifier.testTag("workspace_worktree_switch"),
                        )
                    }

                    if (state.draftUseWorktree) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Everforest.Bg0,
                            border = BorderStroke(1.dp, Everforest.Bg3),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (state.draftWorktreeBranch.isEmpty()) {
                                    Text(
                                        text = "分支名称（留空自动生成，如 lework/1123-a4f2）",
                                        fontSize = 12.sp,
                                        color = Everforest.CommentGrey,
                                    )
                                }
                                BasicTextField(
                                    value = state.draftWorktreeBranch,
                                    onValueChange = onChangeWorktreeBranch,
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontSize = 12.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Everforest.Fg,
                                    ),
                                    cursorBrush = SolidColor(Everforest.Aqua),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("workspace_worktree_branch_input"),
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "点击行设为主目录，点右侧「+ 关联」可附加多个目录",
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
                                        text = "主目录",
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
    val normalizedCurPath = state.currentBrowsePath.trimEnd('/')
    val atWorkspaceRoot = activeWsList.any { it.trimEnd('/') == normalizedCurPath }
    val canGoBack = state.openedFilePath != null || (!atWorkspaceRoot && normalizedCurPath.isNotEmpty())

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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (canGoBack) {
                    IconButton(
                        onClick = {
                            if (state.openedFilePath != null) onCloseFile() else onNavigateUp()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回上级",
                            tint = Everforest.Fg,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = Everforest.Aqua,
                        modifier = Modifier.size(20.dp),
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
                Text("切换目录", fontSize = 12.sp, color = Everforest.CodeGreen)
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
                        label = if (idx == 0) "$short · 主目录" else short,
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
                    val parentHandler = LocalFileLinkClickHandler.current
                    val fileBaseDir = remember(filePath) { dev.lework.store.parentDirectoryOf(filePath) }
                    CompositionLocalProvider(
                        LocalFileLinkClickHandler provides parentHandler?.let { handler ->
                            { target ->
                                val resolved = dev.lework.store.parseLocalFileLink(target)?.let { parsed ->
                                    dev.lework.store.resolveLocalFilePath(parsed.path, fileBaseDir)
                                } ?: target
                                handler(resolved)
                            }
                        },
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(14.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            EverforestMarkdownContent(markdown = content)
                        }
                    }
                } else {
                    val highlightedFile = remember(content, filePath) {
                        highlightFileContent(content = content, filePathOrLang = filePath)
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

