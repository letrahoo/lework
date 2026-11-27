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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.PairedHost
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleUiState
import dev.lework.ui.EngineGlyphIcon
import dev.lework.ui.Everforest
import dev.lework.ui.ModalEscapeEffect
import dev.lework.ui.RenameHostDialog
import dev.lework.ui.SidebarAppUpdateBanner
import dev.lework.ui.connectionChannelLabel
import dev.lework.ui.engineAccentColor
import dev.lework.ui.shortEngineLabel

@Composable
internal fun DesktopSidebar(
    state: ConsoleUiState,
    onNewSession: () -> Unit,
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
                label = "切换主机连接",
                subtitle = "$hostName · $channelText",
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
                        contentDescription = "切换主机",
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
                onClick = onCollapseSidebar,
            )
        }

        if (showHostSwitcher) {
            ModalEscapeEffect(onDismiss = { showHostSwitcher = false })
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Everforest.Bg1,
                border = BorderStroke(1.dp, Everforest.Bg3),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
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
                    val pairHover = remember { MutableInteractionSource() }
                    val pairHovered by pairHover.collectIsHoveredAsState()
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = if (pairHovered) Everforest.Bg2.copy(alpha = 0.7f) else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .hoverable(pairHover)
                            .clickable(interactionSource = pairHover, indication = null) {
                                showHostSwitcher = false
                                onPairAnotherHost()
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddLink,
                                contentDescription = null,
                                tint = Everforest.BoldGold,
                                modifier = Modifier.size(14.5.dp),
                            )
                            Text(
                                text = "连接其他主机",
                                fontSize = 12.sp,
                                color = Everforest.BoldGold,
                            )
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
                    onClick = { showFilterMenu = true },
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
                onClick = { onToggleArchivedView(!state.drawerShowArchived) },
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
                onClick = onNewSession,
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

        Spacer(Modifier.height(10.dp))

        // ── Session List ──
        val pinnedOrder = state.pinnedSessionIds
        val pinnedSet = remember(pinnedOrder) { pinnedOrder.toSet() }
        val allVisibleActive = state.filteredRunningSessions + state.filteredHistorySessions
        val pinnedList = remember(allVisibleActive, pinnedOrder) {
            val byId = allVisibleActive.associateBy { it.id }
            pinnedOrder.mapNotNull { byId[it] }
        }
        val runningList = state.filteredRunningSessions.filterNot { it.id in pinnedSet }
        val historyList = state.filteredHistorySessions.filterNot { it.id in pinnedSet }

        // Keep keyboard-selected session (`⌥↑`/`⌥↓`, `⌘1`..`⌘9`, `⌘[`/`⌘]`) scrolled into view in the sidebar
        LaunchedEffect(state.activeSessionId, state.drawerShowArchived) {
            val activeId = state.activeSessionId ?: return@LaunchedEffect
            val targetKey = when {
                state.drawerShowArchived -> "arch_$activeId"
                activeId in pinnedSet -> "pin_$activeId"
                runningList.any { it.id == activeId } -> "run_$activeId"
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
                        text = "已归档会话（永久保留，点击查看或继续对话）",
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
                                "暂无归档会话\n悬停会话行点归档图标即可收纳"
                            },
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Everforest.Grey0,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 14.dp),
                        )
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
                    item(key = "hdr_pinned") {
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
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
                        }
                    }
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
                    item(key = "sp_pinned") { Spacer(Modifier.height(8.dp)) }
                }

                if (runningList.isNotEmpty()) {
                    item(key = "hdr_running") {
                        Text(
                            text = "进行中",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Everforest.Orange,
                            modifier = Modifier.padding(vertical = 3.dp, horizontal = 4.dp),
                        )
                    }
                    items(runningList, key = { "run_${it.id}" }) { sess ->
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
                    item(key = "sp_running") { Spacer(Modifier.height(8.dp)) }
                }

                item(key = "hdr_history") {
                    Text(
                        text = "历史会话",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.Grey1,
                        modifier = Modifier.padding(vertical = 3.dp, horizontal = 4.dp),
                    )
                }

                if (historyList.isEmpty() && pinnedList.isEmpty()) {
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
                            .clickable { onOpenGlobalSearch(sidebarQuery) }
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
                    onClick = onOpenSettings,
                )
                LeworkIconActionButton(
                    icon = LeworkChartColumnsIcon,
                    tooltip = "用量与额度",
                    subtitle = "查看各编码引擎 Token 消耗与配额",
                    size = DesktopControlSize,
                    iconSize = 16.5.dp,
                    testTag = "drawer_btn_usage",
                    onClick = onOpenUsage,
                )
            }

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopSessionRow(
    session: SessionMeta,
    selected: Boolean,
    isPinned: Boolean,
    allowPin: Boolean,
    draftPreview: String? = null,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val rowHoverSource = remember { MutableInteractionSource() }
    val isRowHovered by rowHoverSource.collectIsHoveredAsState()
    val showInlineActions = isRowHovered

    val wsList = session.workspaces?.filter { it.isNotBlank() }?.distinct().orEmpty()
    val primaryPath = session.worktree?.baseRepoPath ?: wsList.firstOrNull() ?: session.cwd
    val wsShort = primaryPath.trimEnd('/').substringAfterLast('/').ifBlank { "~" }
    val branchSuffix = session.worktree?.let { wt ->
        val shortBranch = wt.branch.removePrefix("lework/")
        if (wt.cleanedUp == true) " · ⎇ $shortBranch (已清理目录)" else " · ⎇ $shortBranch"
    }.orEmpty()
    val wsBadge = (if (wsList.size > 1 && session.worktree == null) "$wsShort +${wsList.size - 1}" else wsShort) + branchSuffix
    val isWaiting = session.state == SessionState.WAITING_PERMISSION
    val isRunning = session.state == SessionState.RUNNING

    Surface(
        shape = RoundedCornerShape(9.dp),
        color = when {
            selected -> Everforest.Bg2
            isRowHovered -> Everforest.Bg1
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
            modifier = Modifier.padding(start = 9.dp, end = 6.dp, top = 6.5.dp, bottom = 6.5.dp),
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
                            else -> engineAccentColor(session.engineId).copy(alpha = 0.78f)
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
                val hasLiveStatus = isRunning && !session.liveStatusText.isNullOrBlank()
                val visibleDraftPreview = draftPreview?.takeUnless { selected }
                if (visibleDraftPreview != null && !hasLiveStatus) {
                    val draftSubtitle = remember(visibleDraftPreview) {
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Everforest.SoftRed, fontWeight = FontWeight.SemiBold)) {
                                append("[草稿] ")
                            }
                            withStyle(SpanStyle(color = Everforest.Grey2)) {
                                append(visibleDraftPreview)
                            }
                        }
                    }
                    Text(
                        text = draftSubtitle,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("session_draft_preview_${session.id}"),
                    )
                } else {
                    val subtitle = if (hasLiveStatus) {
                        session.liveStatusText.orEmpty()
                    } else {
                        "${shortEngineLabel(session.engineId)} · $wsBadge"
                    }
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = if (hasLiveStatus) {
                            Everforest.BoldGold
                        } else {
                            Everforest.Grey0
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
