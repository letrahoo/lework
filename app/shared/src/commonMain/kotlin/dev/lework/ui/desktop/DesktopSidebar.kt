package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.PairedHost
import dev.lework.platform.epochMillis
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleUiState
import dev.lework.store.SessionProjectGroup
import dev.lework.store.SidebarGroupingMode
import dev.lework.store.splitMatchingScopePrefix
import dev.lework.ui.EngineGlyphIcon
import dev.lework.ui.Everforest
import dev.lework.ui.LocalSidebarAnimationPhase
import dev.lework.ui.ModalEscapeEffect
import dev.lework.ui.ProvideSidebarAnimationPhase
import dev.lework.ui.RenameHostDialog
import dev.lework.ui.SidebarAppUpdateBanner
import dev.lework.ui.SidebarHeaderActivityBadges
import dev.lework.ui.SidebarRunningFilterPill
import dev.lework.ui.SidebarSessionLeadingIndicator
import dev.lework.ui.buildSidebarSessionSubtitle
import dev.lework.ui.connectionChannelLabel
import dev.lework.ui.shortEngineLabel

@Composable
internal fun DesktopSidebar(
    state: ConsoleUiState,
    onNewSession: () -> Unit,
    onNewSessionInWorkspace: (String) -> Unit = { onNewSession() },
    onOpenUsage: () -> Unit,
    onOpenSettings: () -> Unit,
    onCollapseSidebar: () -> Unit,
    onSelectFilter: (String?) -> Unit,
    onSelectSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onSwitchHost: (String) -> Unit,
    onRenameHost: (hubId: String, newName: String) -> Unit = { _, _ -> },
    onPairAnotherHost: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOpenGlobalSearch: (initialQuery: String) -> Unit = {},
    onToggleArchivedView: (Boolean) -> Unit,
    onToggleOnlyRunning: () -> Unit = {},
    onTogglePinnedCollapsed: () -> Unit = {},
    onToggleGroupingMode: () -> Unit = {},
    onToggleProjectGroupCollapsed: (String) -> Unit = {},
    onSetAllProjectGroupsCollapsed: (Boolean) -> Unit = {},
    onTogglePinSession: (String) -> Unit,
    onRequestRenameSession: (SessionMeta) -> Unit,
    onToggleArchiveSession: (sessionId: String, archived: Boolean) -> Unit,
    onOpenAppUpdate: () -> Unit = {},
    onDismissAppUpdate: () -> Unit = {},
) {
    var showHostSwitcher by remember { mutableStateOf(false) }
    var renamingHost by remember { mutableStateOf<PairedHost?>(null) }
    var showFilterMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val hostName = state.activeHostDisplayName
    val channelText = connectionChannelLabel(state.discoveredBaseUrl, state.tsnetStatus)
    val archivedCount = state.archivedSessionsCount

    val hostHoverSource = remember { MutableInteractionSource() }
    val hostHovered by hostHoverSource.collectIsHoveredAsState()
    val searchInteraction = remember { MutableInteractionSource() }
    val searchFocused by searchInteraction.collectIsFocusedAsState()
    val searchHovered by searchInteraction.collectIsHoveredAsState()

    ModalEscapeEffect(
        enabled = showHostSwitcher,
        onDismiss = { showHostSwitcher = false },
    )
    LaunchedEffect(searchFocused) {
        if (searchFocused && showHostSwitcher) {
            showHostSwitcher = false
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
        // ── Row 1 (Height = 30.dp): Host Switcher + Collapse Sidebar (⌘B) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DesktopControlSize),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LeworkTooltip(
                label = "切换 Lework Hub 主机",
                subtitle = "$hostName · $channelText",
                enabled = !showHostSwitcher,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .height(DesktopControlSize)
                        .clip(RoundedCornerShape(DesktopControlRadius))
                        .background(
                            if (showHostSwitcher || hostHovered) Everforest.Bg2.copy(alpha = 0.75f) else Color.Transparent,
                        )
                        .hoverable(hostHoverSource)
                        .clickable(interactionSource = hostHoverSource, indication = null) {
                            showHostSwitcher = !showHostSwitcher
                        }
                        .padding(horizontal = 7.dp)
                        .testTag("drawer_host_switcher_btn"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.connectionStatus == ConnectionStatus.RECONNECTING) {
                                    Everforest.BoldGold
                                } else {
                                    Everforest.Green
                                },
                            ),
                    )
                    Text(
                        text = hostName,
                        fontSize = 13.5.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.Fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        imageVector = if (showHostSwitcher) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "切换 Lework Hub 主机",
                        tint = Everforest.Grey1,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            LeworkIconActionButton(
                icon = LeworkPanelLeftIcon,
                tooltip = "收起侧边栏",
                shortcut = "⌘B",
                size = DesktopControlSize,
                iconSize = 16.dp,
                testTag = "drawer_btn_collapse_sidebar",
                onClick = {
                    showHostSwitcher = false
                    onCollapseSidebar()
                },
            )
        }

        if (showHostSwitcher) {
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Everforest.Bg1,
                border = BorderStroke(1.dp, Everforest.Bg3),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("desktop_host_switcher_panel"),
            ) {
                Column(
                    modifier = Modifier.padding(7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val pairHover = remember { MutableInteractionSource() }
                    val pairHovered by pairHover.collectIsHoveredAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 5.dp, end = 2.dp, top = 2.dp, bottom = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Lework Hub 主机",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Everforest.CommentGrey,
                            maxLines = 1,
                        )
                        LeworkTooltip(
                            label = "连接其他 Lework Hub 主机",
                            shortcut = "⇧⌘P",
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (pairHovered) Everforest.BgYellow else Everforest.Bg2.copy(alpha = 0.65f),
                                border = BorderStroke(
                                    0.8.dp,
                                    if (pairHovered) Everforest.BoldGold.copy(alpha = 0.55f) else Everforest.Bg3,
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .hoverable(pairHover)
                                    .clickable(interactionSource = pairHover, indication = null) {
                                        showHostSwitcher = false
                                        onPairAnotherHost()
                                    }
                                    .testTag("host_switcher_btn_connect_other"),
                            ) {
                                Text(
                                    text = "＋ 连接其他",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Everforest.BoldGold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Everforest.Bg2)

                    val hostList = if (state.hub != null && state.pairedHosts.none { it.hubId == state.hub.id }) {
                        listOf(PairedHost(hubId = state.hub.id, name = state.hub.name)) + state.pairedHosts
                    } else {
                        state.pairedHosts
                    }
                    for (host in hostList) {
                        val isCurrent = host.hubId == state.hub?.id
                        val hasCustomAlias = !host.customName.isNullOrBlank() && host.customName.trim() != host.name
                        val itemHover = remember { MutableInteractionSource() }
                        val hovered by itemHover.collectIsHoveredAsState()
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = when {
                                isCurrent -> Everforest.BgGreen
                                hovered -> Everforest.Bg2.copy(alpha = 0.7f)
                                else -> Color.Transparent
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .hoverable(itemHover)
                                .clickable(interactionSource = itemHover, indication = null) {
                                    showHostSwitcher = false
                                    if (!isCurrent) onSwitchHost(host.hubId)
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 9.dp, end = 5.dp, top = 5.5.dp, bottom = 5.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
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
                                LeworkIconActionButton(
                                    icon = Icons.Rounded.Edit,
                                    tooltip = "重命名主机",
                                    size = 22.dp,
                                    iconSize = 13.dp,
                                    testTag = "desktop_sidebar_rename_host_${host.hubId}",
                                    onClick = {
                                        showHostSwitcher = false
                                        renamingHost = host
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Row 2 (Uniform 30.dp Height): Strictly Aligned Search Input + 3 Icon Actions ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DesktopControlSize),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(DesktopControlRadius),
                color = Everforest.Bg1,
                border = BorderStroke(
                    1.dp,
                    when {
                        searchFocused -> Everforest.Green.copy(alpha = 0.7f)
                        searchHovered -> Everforest.Bg3
                        else -> Everforest.Bg2
                    },
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(DesktopControlSize)
                    .hoverable(searchInteraction)
                    .testTag("desktop_sidebar_search_box"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "搜索会话",
                        tint = if (searchFocused) Everforest.Green else Everforest.CommentGrey,
                        modifier = Modifier.size(14.dp),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.drawerSearchQuery.isEmpty()) {
                            Text(
                                text = "搜索会话或工作区…",
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = Everforest.Grey0,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = state.drawerSearchQuery,
                            onValueChange = onSearchQueryChange,
                            interactionSource = searchInteraction,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (state.drawerSearchQuery.trim().isNotEmpty()) {
                                        onOpenGlobalSearch(state.drawerSearchQuery.trim())
                                    }
                                },
                            ),
                            textStyle = TextStyle(
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = Everforest.Fg,
                            ),
                            cursorBrush = SolidColor(Everforest.Green),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("drawer_search_input"),
                        )
                    }
                    if (state.drawerSearchQuery.isNotEmpty()) {
                        LeworkTooltip(label = "清空搜索") {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "清空搜索",
                                tint = Everforest.CommentGrey,
                                modifier = Modifier
                                    .size(13.5.dp)
                                    .clickable { onSearchQueryChange("") },
                            )
                        }
                    }
                }
            }

            if (state.engines.size > 1 || state.drawerEngineFilter != null) {
                Box {
                    val activeFilterLabel = state.drawerEngineFilter?.let { shortEngineLabel(it) }
                    LeworkIconActionButton(
                        icon = LeworkFilterSlidersIcon,
                        tooltip = if (activeFilterLabel != null) "按引擎筛选：$activeFilterLabel" else "按编码引擎筛选",
                        active = activeFilterLabel != null,
                        activeTint = Everforest.CodeGreen,
                        showBadgeDot = activeFilterLabel != null,
                        badgeColor = Everforest.CodeGreen,
                        size = DesktopControlSize,
                        iconSize = 15.5.dp,
                        testTag = "drawer_btn_filter",
                        onClick = {
                            showHostSwitcher = false
                            showFilterMenu = true
                        },
                    )
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false },
                        containerColor = Everforest.Bg1,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "全部引擎",
                                    fontSize = 12.5.sp,
                                    color = if (state.drawerEngineFilter == null) Everforest.CodeGreen else Everforest.Fg,
                                    fontWeight = if (state.drawerEngineFilter == null) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            trailingIcon = if (state.drawerEngineFilter == null) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = Everforest.CodeGreen,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                showFilterMenu = false
                                onSelectFilter(null)
                            },
                        )
                        for (eng in state.engines) {
                            val selected = state.drawerEngineFilter == eng.id
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = shortEngineLabel(eng.id),
                                        fontSize = 12.5.sp,
                                        color = if (selected) Everforest.CodeGreen else Everforest.Fg,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                leadingIcon = {
                                    EngineGlyphIcon(engineId = eng.id, size = 15.dp)
                                },
                                trailingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = Everforest.CodeGreen,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    showFilterMenu = false
                                    onSelectFilter(eng.id)
                                },
                            )
                        }
                    }
                }
            }

            LeworkIconActionButton(
                icon = Icons.Rounded.Inventory2,
                tooltip = if (state.drawerShowArchived) {
                    "返回活跃会话列表"
                } else if (archivedCount > 0) {
                    "查看已归档会话 ($archivedCount)"
                } else {
                    "查看已归档会话"
                },
                active = state.drawerShowArchived,
                activeTint = Everforest.BoldGold,
                activeContainerColor = Everforest.BgYellow,
                showBadgeDot = !state.drawerShowArchived && archivedCount > 0,
                badgeColor = Everforest.BoldGold,
                size = DesktopControlSize,
                iconSize = 15.5.dp,
                testTag = "drawer_tab_archived",
                onClick = {
                    showHostSwitcher = false
                    onToggleArchivedView(!state.drawerShowArchived)
                },
            )

            val newSessionDraftPreview = state.draftPreviewFor(null)
            LeworkIconActionButton(
                icon = LeworkSquarePenIcon,
                tooltip = if (newSessionDraftPreview != null && state.activeSessionId != null) {
                    "新建会话（有未发送草稿）"
                } else {
                    "新建会话"
                },
                shortcut = "⌘N",
                idleTint = Everforest.Green,
                hoverTint = Everforest.Green,
                active = state.activeSessionId == null && !state.drawerShowArchived,
                activeTint = Everforest.Green,
                activeContainerColor = Everforest.BgGreen,
                showBadgeDot = newSessionDraftPreview != null && state.activeSessionId != null,
                badgeColor = Everforest.SoftRed,
                size = DesktopControlSize,
                iconSize = 16.dp,
                testTag = "drawer_btn_new_session",
                onClick = {
                    showHostSwitcher = false
                    onNewSession()
                },
            )
        }

        if (state.drawerEngineFilter != null || state.drawerShowArchived) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.drawerEngineFilter != null) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Everforest.Bg2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onSelectFilter(null) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            EngineGlyphIcon(engineId = state.drawerEngineFilter, size = 11.5.dp)
                            Text(
                                text = "筛选：${shortEngineLabel(state.drawerEngineFilter)}",
                                fontSize = 10.5.sp,
                                color = Everforest.CodeGreen,
                            )
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "取消筛选",
                                tint = Everforest.CommentGrey,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
                if (state.drawerShowArchived) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Everforest.BgYellow,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onToggleArchivedView(false) }
                            .testTag("drawer_tab_active"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "已归档视图 · 点击返回",
                                fontSize = 10.5.sp,
                                color = Everforest.BoldGold,
                            )
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "返回活跃列表",
                                tint = Everforest.BoldGold,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Session List (Project-First + Floating Waiting Approval) ──
        val pinnedList = remember(
            state.sessions,
            state.pinnedSessionIds,
            state.drawerSearchQuery,
            state.drawerEngineFilter,
            state.drawerOnlyRunning,
        ) {
            state.pinnedSidebarSessions()
        }
        val waitingList = remember(
            state.sessions,
            state.pinnedSessionIds,
            state.drawerSearchQuery,
            state.drawerEngineFilter,
            state.drawerOnlyRunning,
        ) {
            state.floatingWaitingSessions(excludePinned = true)
        }
        val isGroupedByProject = state.sidebarGroupingMode == SidebarGroupingMode.PROJECT
        val collapsedGroupKeys = state.effectiveCollapsedProjectGroupKeys
        val pinnedCollapsed = state.effectivePinnedSectionCollapsed
        val projectGroups = remember(
            state.sessions,
            state.pinnedSessionIds,
            state.drawerSearchQuery,
            state.drawerEngineFilter,
            state.drawerOnlyRunning,
            state.workspaces,
            state.sessionDrafts,
            state.sessionAttachedImages,
            state.activeSessionId,
        ) {
            state.sidebarProjectGroups(excludePinned = true, excludeFloatingWaiting = true)
        }
        val flatTimeList = remember(
            state.sessions,
            state.pinnedSessionIds,
            state.drawerSearchQuery,
            state.drawerEngineFilter,
            state.drawerOnlyRunning,
        ) {
            state.flatTimeSidebarSessions(excludePinned = true, excludeFloatingWaiting = true)
        }
        val archivedList = state.filteredArchivedSessions
        val archivedGroups = remember(archivedList, state.workspaces, state.sessionDrafts, state.sessionAttachedImages, state.activeSessionId) {
            state.archivedProjectGroups
        }

        // Compact persistent view control bar right above the list (always visible even with many pinned items)
        val activeGroupsForBar = if (state.drawerShowArchived) archivedGroups else projectGroups
        val anyGroupExpanded = activeGroupsForBar.any { it.key !in collapsedGroupKeys }
        DesktopSidebarViewBar(
            isGroupedByProject = isGroupedByProject,
            showCollapseAll = isGroupedByProject &&
                activeGroupsForBar.size > 1 &&
                state.drawerSearchQuery.isBlank() &&
                !state.drawerOnlyRunning,
            anyGroupExpanded = anyGroupExpanded,
            runningCount = state.totalActiveRunningCount,
            onlyRunning = state.drawerOnlyRunning,
            showRunningFilter = !state.drawerShowArchived &&
                (state.totalActiveRunningCount > 0 || state.drawerOnlyRunning),
            onToggleOnlyRunning = onToggleOnlyRunning,
            onToggleGroupingMode = onToggleGroupingMode,
            onToggleCollapseAll = { onSetAllProjectGroupsCollapsed(anyGroupExpanded) },
        )

        Spacer(Modifier.height(6.dp))

        // Keep keyboard-selected session (`⌥↑`/`⌥↓`, `⌘1`..`⌘9`, `⌘[`/`⌘]`) scrolled into view in the sidebar
        LaunchedEffect(
            state.activeSessionId,
            state.drawerShowArchived,
            state.drawerOnlyRunning,
            state.sidebarGroupingMode,
            collapsedGroupKeys,
            pinnedCollapsed,
        ) {
            val activeId = state.activeSessionId ?: return@LaunchedEffect
            val targetKey = when {
                state.drawerShowArchived -> "arch_$activeId"
                pinnedList.any { it.id == activeId } -> "pin_$activeId"
                waitingList.any { it.id == activeId } -> "wait_$activeId"
                else -> activeId
            }
            val visibleInfo = listState.layoutInfo.visibleItemsInfo
            if (visibleInfo.any { it.key == targetKey }) return@LaunchedEffect
            val activeIndex = state.visibleSidebarSessions.indexOfFirst { it.id == activeId }
            if (activeIndex >= 0) {
                listState.scrollToItem(activeIndex.coerceAtLeast(0))
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (state.drawerShowArchived) {
                item(key = "hdr_archived_hint") {
                    Text(
                        text = if (isGroupedByProject && archivedGroups.isNotEmpty()) {
                            "已归档 · ${archivedGroups.size} 个项目（永久保留，点击查看或继续对话）"
                        } else {
                            "已归档会话（永久保留，点击查看或继续对话）"
                        },
                        fontSize = 10.5.sp,
                        color = Everforest.CommentGrey,
                        modifier = Modifier.padding(vertical = 3.dp, horizontal = 4.dp),
                    )
                }
                if (archivedList.isEmpty()) {
                    item(key = "empty_archived") {
                        Text(
                            text = if (state.drawerSearchQuery.isNotBlank()) {
                                "未找到匹配的已归档会话"
                            } else {
                                "暂无归档会话\n悬停会话行点归档图标即可收纳"
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
                            DesktopProjectGroupHeader(
                                group = group,
                                collapsed = collapsed,
                                showNewSessionButton = false,
                                onToggleCollapse = { onToggleProjectGroupCollapsed(group.key) },
                                onNewSessionInProject = {},
                            )
                        }
                        if (!collapsed) {
                            items(group.sessions, key = { "arch_${it.id}" }) { sess ->
                                DesktopSessionRow(
                                    session = sess,
                                    selected = sess.id == state.activeSessionId,
                                    isPinned = false,
                                    allowPin = false,
                                    groupedProjectDisplayName = group.displayName,
                                    draftPreview = state.draftPreviewFor(sess.id),
                                    onClick = { onSelectSession(sess.id) },
                                    onTogglePin = {},
                                    onRename = { onRequestRenameSession(sess) },
                                    onToggleArchive = { onToggleArchiveSession(sess.id, false) },
                                )
                            }
                        }
                    }
                } else {
                    items(archivedList, key = { "arch_${it.id}" }) { sess ->
                        DesktopSessionRow(
                            session = sess,
                            selected = sess.id == state.activeSessionId,
                            isPinned = false,
                            allowPin = false,
                            draftPreview = state.draftPreviewFor(sess.id),
                            onClick = { onSelectSession(sess.id) },
                            onTogglePin = {},
                            onRename = { onRequestRenameSession(sess) },
                            onToggleArchive = { onToggleArchiveSession(sess.id, false) },
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
                    item(key = "new_session_draft_row") {
                        DesktopNewSessionDraftRow(
                            draftPreview = newDraftPreview,
                            selected = state.activeSessionId == null,
                            onClick = onNewSession,
                        )
                    }
                }
                if (pinnedList.isNotEmpty()) {
                    val pinnedRunningCount = pinnedList.count { it.state == SessionState.RUNNING }
                    val pinnedWaitingCount = pinnedList.count { it.state == SessionState.WAITING_PERMISSION }
                    item(key = "hdr_pinned") {
                        DesktopPinnedSectionHeader(
                            count = pinnedList.size,
                            collapsed = pinnedCollapsed,
                            runningCount = pinnedRunningCount,
                            waitingCount = pinnedWaitingCount,
                            onToggleCollapse = onTogglePinnedCollapsed,
                        )
                    }
                    if (!pinnedCollapsed) {
                        items(pinnedList, key = { "pin_${it.id}" }) { sess ->
                            DesktopSessionRow(
                                session = sess,
                                selected = sess.id == state.activeSessionId,
                                isPinned = true,
                                allowPin = true,
                                draftPreview = state.draftPreviewFor(sess.id),
                                onClick = { onSelectSession(sess.id) },
                                onTogglePin = { onTogglePinSession(sess.id) },
                                onRename = { onRequestRenameSession(sess) },
                                onToggleArchive = { onToggleArchiveSession(sess.id, !sess.archived) },
                            )
                        }
                    }
                    item(key = "sp_pinned") { Spacer(Modifier.height(6.dp)) }
                }

                if (waitingList.isNotEmpty()) {
                    item(key = "hdr_waiting") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp, horizontal = 4.dp)
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
                        DesktopSessionRow(
                            session = sess,
                            selected = sess.id == state.activeSessionId,
                            isPinned = false,
                            allowPin = true,
                            draftPreview = state.draftPreviewFor(sess.id),
                            onClick = { onSelectSession(sess.id) },
                            onTogglePin = { onTogglePinSession(sess.id) },
                            onRename = { onRequestRenameSession(sess) },
                            onToggleArchive = { onToggleArchiveSession(sess.id, !sess.archived) },
                        )
                    }
                    item(key = "sp_waiting") { Spacer(Modifier.height(6.dp)) }
                }

                val noMainItems = if (isGroupedByProject) projectGroups.isEmpty() else flatTimeList.isEmpty()
                if (!isGroupedByProject && flatTimeList.isNotEmpty()) {
                    item(key = "hdr_history") {
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                text = "全部会话",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.Grey1,
                            )
                            Text(
                                text = "${flatTimeList.size}",
                                fontSize = 10.sp,
                                color = Everforest.CommentGrey,
                            )
                        }
                    }
                }
                if (noMainItems && pinnedList.isEmpty() && waitingList.isEmpty()) {
                    item(key = "empty_history") {
                        val emptyHistoryText = when {
                            state.drawerOnlyRunning -> "当前没有进行中或待审批的会话"
                            state.drawerSearchQuery.isNotBlank() -> "未找到匹配的会话记录"
                            state.drawerEngineFilter != null -> "暂无 ${shortEngineLabel(state.drawerEngineFilter)} 的历史会话"
                            else -> "还没有历史会话，发一条消息即可开始"
                        }
                        Text(
                            text = emptyHistoryText,
                            fontSize = 12.sp,
                            color = Everforest.Grey0,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                        )
                    }
                } else if (isGroupedByProject) {
                    for (group in projectGroups) {
                        val collapsed = group.key in collapsedGroupKeys
                        item(key = "grp_${group.key}") {
                            DesktopProjectGroupHeader(
                                group = group,
                                collapsed = collapsed,
                                showNewSessionButton = !group.isFallback && group.fullPath.isNotEmpty(),
                                onToggleCollapse = { onToggleProjectGroupCollapsed(group.key) },
                                onNewSessionInProject = { onNewSessionInWorkspace(group.fullPath) },
                            )
                        }
                        if (!collapsed) {
                            items(group.sessions, key = { it.id }) { sess ->
                                DesktopSessionRow(
                                    session = sess,
                                    selected = sess.id == state.activeSessionId,
                                    isPinned = false,
                                    allowPin = true,
                                    groupedProjectDisplayName = group.displayName,
                                    draftPreview = state.draftPreviewFor(sess.id),
                                    onClick = { onSelectSession(sess.id) },
                                    onTogglePin = { onTogglePinSession(sess.id) },
                                    onRename = { onRequestRenameSession(sess) },
                                    onToggleArchive = { onToggleArchiveSession(sess.id, !sess.archived) },
                                )
                            }
                        }
                    }
                } else {
                    items(flatTimeList, key = { it.id }) { sess ->
                        DesktopSessionRow(
                            session = sess,
                            selected = sess.id == state.activeSessionId,
                            isPinned = false,
                            allowPin = true,
                            draftPreview = state.draftPreviewFor(sess.id),
                            onClick = {
                                showHostSwitcher = false
                                onSelectSession(sess.id)
                            },
                            onTogglePin = { onTogglePinSession(sess.id) },
                            onRename = { onRequestRenameSession(sess) },
                            onToggleArchive = { onToggleArchiveSession(sess.id, !sess.archived) },
                        )
                    }
                }
            }
            val sidebarQuery = state.drawerSearchQuery.trim()
            if (sidebarQuery.length >= 2) {
                item(key = "sidebar_fulltext_search_bridge") {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Everforest.Bg1,
                        border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showHostSwitcher = false
                                onOpenGlobalSearch(sidebarQuery)
                            }
                            .testTag("sidebar_btn_fulltext_search"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = Everforest.Green,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "全文搜索「$sidebarQuery」",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Everforest.Fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "⇧⌘F",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Everforest.BoldGold,
                            )
                        }
                    }
                }
            }
        }

        if (state.appUpdate.hasVisibleBanner) {
            SidebarAppUpdateBanner(
                updateState = state.appUpdate,
                onOpenUpdate = onOpenAppUpdate,
                onDismiss = onDismissAppUpdate,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )
        }

        // ── Bottom Footer: Left = [1. 设置, 2. 用量] · Far-Right = [刷新] (Uniform 30.dp Grid) ──
        HorizontalDivider(color = Everforest.Bg2.copy(alpha = 0.75f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LeworkIconActionButton(
                    icon = Icons.Rounded.Settings,
                    tooltip = if (state.appUpdate.hasUpdate && state.appUpdate.latestRelease != null) {
                        "设置（有新版本 ${state.appUpdate.latestRelease.tagName}）"
                    } else {
                        "设置"
                    },
                    shortcut = "⌘,",
                    subtitle = "${state.engines.count { it.status.ready }} 个引擎在线 · $hostName",
                    showBadgeDot = state.appUpdate.hasUpdate,
                    badgeColor = Everforest.Green,
                    size = DesktopControlSize,
                    iconSize = 17.dp,
                    testTag = "drawer_btn_settings",
                    onClick = {
                        showHostSwitcher = false
                        onOpenSettings()
                    },
                )
                LeworkIconActionButton(
                    icon = LeworkChartColumnsIcon,
                    tooltip = "用量与额度",
                    subtitle = "查看各编码引擎 Token 消耗与配额",
                    size = DesktopControlSize,
                    iconSize = 16.5.dp,
                    testTag = "drawer_btn_usage",
                    onClick = {
                        showHostSwitcher = false
                        onOpenUsage()
                    },
                )
            }

            if (state.sessionsRefreshing) {
                LeworkTooltip(label = "正在刷新会话列表…") {
                    Box(
                        modifier = Modifier
                            .size(DesktopControlSize)
                            .testTag("drawer_btn_refresh"),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Everforest.Green,
                            strokeWidth = 1.8.dp,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else {
                LeworkIconActionButton(
                    icon = Icons.Rounded.Refresh,
                    tooltip = "刷新会话列表",
                    shortcut = "⌘R",
                    size = DesktopControlSize,
                    iconSize = 17.dp,
                    testTag = "drawer_btn_refresh",
                    onClick = onRefresh,
                )
            }
        }
        }
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
private fun DesktopNewSessionDraftRow(
    draftPreview: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val rowHoverSource = remember { MutableInteractionSource() }
    val isRowHovered by rowHoverSource.collectIsHoveredAsState()
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
        shape = RoundedCornerShape(9.dp),
        color = when {
            selected -> Everforest.Bg2
            isRowHovered -> Everforest.Bg1
            else -> Everforest.Bg1.copy(alpha = 0.55f)
        },
        border = BorderStroke(1.dp, Everforest.SoftRed.copy(alpha = if (selected) 0.48f else 0.28f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .testTag("session_row_new_draft")
            .clip(RoundedCornerShape(9.dp))
            .hoverable(rowHoverSource)
            .clickable(interactionSource = rowHoverSource, indication = null, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 9.dp, end = 6.dp, top = 6.5.dp, bottom = 6.5.dp),
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
private fun DesktopPinnedSectionHeader(
    count: Int,
    collapsed: Boolean,
    runningCount: Int,
    waitingCount: Int,
    onToggleCollapse: () -> Unit,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (hovered) Everforest.Bg1.copy(alpha = 0.85f) else Color.Transparent)
            .hoverable(hoverSource)
            .clickable(interactionSource = hoverSource, indication = null, onClick = onToggleCollapse)
            .padding(horizontal = 4.dp, vertical = 3.5.dp)
            .testTag("drawer_hdr_pinned"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (collapsed) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (collapsed) "展开已置顶" else "折叠已置顶",
            tint = Everforest.CommentGrey,
            modifier = Modifier.size(13.5.dp),
        )
        Icon(
            imageVector = LeworkPinIcon,
            contentDescription = null,
            tint = Everforest.BoldGold,
            modifier = Modifier.size(11.5.dp),
        )
        Text(
            text = "已置顶",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Everforest.BoldGold,
        )
        Text(
            text = "$count",
            fontSize = 10.sp,
            color = Everforest.CommentGrey,
        )
        Spacer(Modifier.weight(1f))
        SidebarHeaderActivityBadges(
            runningCount = runningCount,
            waitingCount = waitingCount,
        )
    }
}

@Composable
private fun DesktopSidebarViewBar(
    isGroupedByProject: Boolean,
    showCollapseAll: Boolean,
    anyGroupExpanded: Boolean,
    runningCount: Int,
    onlyRunning: Boolean,
    showRunningFilter: Boolean,
    onToggleOnlyRunning: () -> Unit,
    onToggleGroupingMode: () -> Unit,
    onToggleCollapseAll: () -> Unit,
) {
    val toggleHoverSource = remember { MutableInteractionSource() }
    val toggleHovered by toggleHoverSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LeworkTooltip(
            label = if (isGroupedByProject) "切换为按时间平铺" else "切换为按项目目录分组",
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            toggleHovered -> Everforest.Bg2.copy(alpha = 0.85f)
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(toggleHoverSource)
                    .clickable(interactionSource = toggleHoverSource, indication = null) {
                        onToggleGroupingMode()
                    }
                    .padding(horizontal = 6.dp, vertical = 2.5.dp)
                    .testTag("drawer_btn_toggle_grouping"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (isGroupedByProject) Icons.Rounded.FolderCopy else Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = if (isGroupedByProject) Everforest.Aqua else Everforest.CommentGrey,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = if (isGroupedByProject) "按项目" else "按时间",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Everforest.Grey2,
                )
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = Everforest.CommentGrey,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showRunningFilter) {
                SidebarRunningFilterPill(
                    runningCount = runningCount,
                    active = onlyRunning,
                    onClick = onToggleOnlyRunning,
                    modifier = Modifier.height(22.dp),
                )
            }
            if (showCollapseAll) {
                LeworkIconActionButton(
                    icon = if (anyGroupExpanded) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
                    tooltip = if (anyGroupExpanded) "折叠全部项目" else "展开全部项目",
                    idleTint = Everforest.CommentGrey,
                    hoverTint = Everforest.Fg,
                    size = 22.dp,
                    iconSize = 13.5.dp,
                    testTag = "drawer_btn_toggle_collapse_all",
                    onClick = onToggleCollapseAll,
                )
            }
        }
    }
}

@Composable
private fun DesktopProjectGroupHeader(
    group: SessionProjectGroup,
    collapsed: Boolean,
    showNewSessionButton: Boolean,
    onToggleCollapse: () -> Unit,
    onNewSessionInProject: () -> Unit,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (hovered) Everforest.Bg1.copy(alpha = 0.85f) else Color.Transparent)
            .hoverable(hoverSource)
            .clickable(interactionSource = hoverSource, indication = null, onClick = onToggleCollapse)
            .heightIn(min = 28.dp)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag("project_group_header_${group.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (collapsed) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (collapsed) "展开项目" else "折叠项目",
            tint = Everforest.CommentGrey,
            modifier = Modifier.size(14.dp),
        )
        Icon(
            imageVector = if (collapsed) Icons.Rounded.Folder else Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = if (group.isFallback) Everforest.Grey1 else Everforest.Aqua.copy(alpha = 0.88f),
            modifier = Modifier.size(13.5.dp),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            LeworkTooltip(
                label = group.displayName,
                subtitle = group.compactPath.ifEmpty { "未关联特定项目目录" },
            ) {
                Text(
                    text = group.displayName,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (group.isFallback) Everforest.Grey1 else Everforest.HeadingWarm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
        Box(
            modifier = Modifier
                .height(20.dp)
                .widthIn(min = 20.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (showNewSessionButton && hovered) {
                LeworkIconActionButton(
                    icon = Icons.Rounded.Add,
                    tooltip = "在 ${group.displayName} 中新建会话",
                    idleTint = Everforest.Grey1,
                    hoverTint = Everforest.Green,
                    size = 20.dp,
                    iconSize = 13.5.dp,
                    testTag = "project_group_new_${group.key}",
                    onClick = onNewSessionInProject,
                )
            } else {
                Text(
                    text = "${group.sessions.size}",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.CommentGrey,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .testTag("project_group_count_${group.key}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopSessionRow(
    session: SessionMeta,
    selected: Boolean,
    isPinned: Boolean,
    allowPin: Boolean,
    groupedProjectDisplayName: String? = null,
    draftPreview: String? = null,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val rowHoverSource = remember { MutableInteractionSource() }
    val isRowHovered by rowHoverSource.collectIsHoveredAsState()
    val showInlineActions = isRowHovered

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

    val groupedIndentModifier = if (groupedProjectDisplayName != null) {
        Modifier
            .drawBehind {
                val guideX = 9.dp.toPx()
                drawLine(
                    color = Everforest.Bg3.copy(alpha = 0.55f),
                    start = Offset(guideX, 0f),
                    end = Offset(guideX, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(start = 15.dp)
    } else {
        Modifier
    }

    Box(modifier = Modifier.fillMaxWidth().then(groupedIndentModifier)) {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = when {
                selected -> Everforest.Bg2
                isRowHovered -> Everforest.Bg1
                isWaiting -> Everforest.BgYellow.copy(alpha = 0.24f)
                else -> Color.Transparent
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("session_row_${session.id}")
                .clip(RoundedCornerShape(9.dp))
                .hoverable(rowHoverSource)
                .combinedClickable(
                    interactionSource = rowHoverSource,
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            Row(
                modifier = Modifier.padding(
                    start = 8.dp,
                    end = 6.dp,
                    top = 6.dp,
                    bottom = 6.dp,
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

            if (showInlineActions) {
                // Direct Desktop Hover Inline Action Buttons: [重命名] [归档] [置顶 Pin]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    LeworkIconActionButton(
                        icon = Icons.Rounded.Edit,
                        tooltip = "重命名会话",
                        idleTint = Everforest.Grey1,
                        hoverTint = Everforest.Fg,
                        size = 24.dp,
                        iconSize = 13.5.dp,
                        testTag = "session_rename_${session.id}",
                        onClick = onRename,
                    )
                    LeworkIconActionButton(
                        icon = if (session.archived) Icons.Rounded.Unarchive else Icons.Rounded.Inventory2,
                        tooltip = if (session.archived) "移出归档" else "归档会话",
                        idleTint = if (session.archived) Everforest.Green else Everforest.Grey1,
                        hoverTint = if (session.archived) Everforest.Green else Everforest.Fg,
                        size = 24.dp,
                        iconSize = 13.5.dp,
                        testTag = "session_archive_${session.id}",
                        onClick = onToggleArchive,
                    )
                    if (allowPin) {
                        LeworkIconActionButton(
                            icon = LeworkPinIcon,
                            tooltip = if (isPinned) "取消置顶" else "置顶会话",
                            active = isPinned,
                            activeTint = Everforest.BoldGold,
                            activeContainerColor = Everforest.BgYellow,
                            idleTint = Everforest.Grey1,
                            hoverTint = Everforest.BoldGold,
                            size = 24.dp,
                            iconSize = 13.5.dp,
                            testTag = "session_pin_${session.id}",
                            onClick = onTogglePin,
                        )
                    }
                }
            } else {
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
            }
        }
        }
    }
}
