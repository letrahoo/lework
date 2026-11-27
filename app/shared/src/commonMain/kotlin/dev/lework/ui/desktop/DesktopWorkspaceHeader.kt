package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.protocol.SessionMeta
import dev.lework.store.ConsoleUiState
import dev.lework.ui.Everforest

internal enum class DesktopInspectorTab {
    DIFF,
    FILES,
    ARTIFACTS,
}

@Composable
internal fun DesktopWorkspaceHeader(
    state: ConsoleUiState,
    activeSession: SessionMeta?,
    showExpandSidebarButton: Boolean,
    activeInspectorTab: DesktopInspectorTab?,
    onExpandSidebar: () -> Unit,
    onOpenWorkspaceDialog: () -> Unit,
    onToggleDiffInspector: () -> Unit,
    onToggleFilesInspector: () -> Unit,
    onToggleArtifactsInspector: () -> Unit,
    onRenameSession: () -> Unit,
    onToggleArchiveSession: () -> Unit,
) {
    val primaryPath = activeSession?.worktree?.baseRepoPath
        ?: activeSession?.workspaces?.firstOrNull()
        ?: activeSession?.cwd
        ?: state.selectedWorkspacePath
    val wsShort = primaryPath.trimEnd('/').substringAfterLast('/').ifBlank { "默认工作区" }
    val wsHoverSource = remember { MutableInteractionSource() }
    val wsHovered by wsHoverSource.collectIsHoveredAsState()
    val titleHoverSource = remember { MutableInteractionSource() }
    val titleHovered by titleHoverSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("desktop_workspace_header"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DesktopHeaderHeight)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: T3 Code-style Workspace / Thread Breadcrumb
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showExpandSidebarButton) {
                    LeworkIconActionButton(
                        icon = LeworkPanelLeftIcon,
                        tooltip = "展开侧边栏",
                        shortcut = "⌘B",
                        showBadgeDot = state.runningSessions.isNotEmpty(),
                        badgeColor = Everforest.Orange,
                        size = DesktopControlSize,
                        iconSize = 16.dp,
                        testTag = "desktop_btn_expand_sidebar",
                        onClick = onExpandSidebar,
                    )
                }

                LeworkTooltip(
                    label = if (activeSession != null) "查看当前会话目录" else "切换工作区目录",
                    subtitle = primaryPath.ifBlank { "默认工作区" },
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (wsHovered) Everforest.Bg2.copy(alpha = 0.7f) else Color.Transparent)
                            .hoverable(wsHoverSource)
                            .clickable(interactionSource = wsHoverSource, indication = null) {
                                onOpenWorkspaceDialog()
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = Everforest.CommentGrey,
                            modifier = Modifier.size(13.5.dp),
                        )
                        Text(
                            text = wsShort,
                            fontSize = 12.5.sp,
                            color = if (wsHovered) Everforest.Fg else Everforest.Grey1,
                            maxLines = 1,
                        )
                    }
                }

                Text(
                    text = "/",
                    fontSize = 12.5.sp,
                    color = Everforest.Grey0,
                )

                if (activeSession != null) {
                    if (activeSession.archived) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Everforest.BgYellow,
                        ) {
                            Text(
                                text = "已归档",
                                fontSize = 10.sp,
                                color = Everforest.BoldGold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                            )
                        }
                    }

                    activeSession.worktree?.let { wt ->
                        LeworkTooltip(
                            label = "独立分支工作区",
                            subtitle = "${wt.branch} · ${wt.worktreePath}",
                        ) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = Everforest.BgGreen,
                                border = BorderStroke(0.8.dp, Everforest.Aqua.copy(alpha = 0.45f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.5.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AccountTree,
                                        contentDescription = null,
                                        tint = Everforest.Aqua,
                                        modifier = Modifier.size(10.5.dp),
                                    )
                                    Text(
                                        text = wt.branch.removePrefix("lework/"),
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Everforest.Aqua,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }

                    LeworkTooltip(
                        label = "点击重命名会话",
                        subtitle = activeSession.title,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (titleHovered) Everforest.Bg2.copy(alpha = 0.7f) else Color.Transparent)
                                .hoverable(titleHoverSource)
                                .clickable(interactionSource = titleHoverSource, indication = null) {
                                    onRenameSession()
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .testTag("active_session_rename_trigger"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                text = activeSession.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Everforest.Fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (titleHovered) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "重命名当前会话",
                                    tint = Everforest.CommentGrey,
                                    modifier = Modifier.size(12.5.dp),
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "新会话",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Grey2,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    if (state.draftUseWorktree) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = Everforest.BgGreen,
                            border = BorderStroke(0.8.dp, Everforest.Aqua.copy(alpha = 0.45f)),
                        ) {
                            Text(
                                text = "独立分支",
                                fontSize = 10.sp,
                                color = Everforest.Aqua,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Right: Pure Iconified Session & Inspector Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (activeSession != null) {
                    LeworkIconActionButton(
                        icon = if (activeSession.archived) Icons.Rounded.Unarchive else Icons.Rounded.Inventory2,
                        tooltip = if (activeSession.archived) "移出归档" else "归档会话",
                        active = activeSession.archived,
                        activeTint = Everforest.Green,
                        size = DesktopControlSize,
                        iconSize = 16.5.dp,
                        testTag = "active_session_archive_btn",
                        onClick = onToggleArchiveSession,
                    )

                    val hasArtifacts = state.activeSessionArtifacts.isNotEmpty() ||
                        ((activeSession.artifactCount ?: 0) > 0)

                    LeworkIconActionButton(
                        icon = Icons.Rounded.Description,
                        tooltip = if (activeInspectorTab == DesktopInspectorTab.ARTIFACTS) "收起会话产物面板" else "查看执行方案与产物",
                        shortcut = "⇧⌘A",
                        active = activeInspectorTab == DesktopInspectorTab.ARTIFACTS,
                        activeTint = Everforest.BoldGold,
                        showBadgeDot = hasArtifacts,
                        badgeColor = Everforest.BoldGold,
                        size = DesktopControlSize,
                        iconSize = 16.5.dp,
                        testTag = "topbar_btn_artifacts",
                        onClick = onToggleArtifactsInspector,
                    )
                }

                LeworkIconActionButton(
                    icon = Icons.AutoMirrored.Rounded.CompareArrows,
                    tooltip = if (activeInspectorTab == DesktopInspectorTab.DIFF) "收起代码变更面板" else "查看代码变更与快照",
                    shortcut = "⇧⌘D",
                    active = activeInspectorTab == DesktopInspectorTab.DIFF,
                    activeTint = Everforest.Green,
                    size = DesktopControlSize,
                    iconSize = 17.5.dp,
                    testTag = "topbar_btn_diff",
                    onClick = onToggleDiffInspector,
                )

                LeworkIconActionButton(
                    icon = Icons.Rounded.FolderOpen,
                    tooltip = if (activeInspectorTab == DesktopInspectorTab.FILES) "收起工作区文件面板" else "浏览工作区文件",
                    active = activeInspectorTab == DesktopInspectorTab.FILES,
                    activeTint = Everforest.Aqua,
                    size = DesktopControlSize,
                    iconSize = 17.dp,
                    testTag = "topbar_btn_files",
                    onClick = onToggleFilesInspector,
                )
            }
        }
        HorizontalDivider(color = Everforest.Bg2.copy(alpha = 0.65f))
    }
}
