package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.platform.epochMillis
import dev.lework.platform.localDateTimeParts
import dev.lework.protocol.SearchMatchRole
import dev.lework.protocol.SearchRoleFilter
import dev.lework.protocol.SessionSearchHit
import dev.lework.protocol.SessionSearchMatch
import dev.lework.store.ConsoleUiState
import dev.lework.ui.EngineGlyphIcon
import dev.lework.ui.Everforest
import dev.lework.ui.engineAccentColor
import dev.lework.ui.highlightPlainTextForSearch
import dev.lework.ui.shortEngineLabel

private data class FlattenedSearchRow(
    val hit: SessionSearchHit,
    val match: SessionSearchMatch,
    val isFirstInSession: Boolean,
)

@Composable
internal fun DesktopSearchDialog(
    state: ConsoleUiState,
    openNonce: Int = 0,
    onSearch: (
        query: String,
        engineFilter: String?,
        roleFilter: SearchRoleFilter,
        scopeCurrentWorkspace: Boolean,
        includeArchived: Boolean,
        immediate: Boolean,
    ) -> Unit,
    onJumpToHit: (sessionId: String, seq: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val nowMs = remember(state.searchHits) { epochMillis() }
    var queryFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.searchQuery,
                selection = TextRange(0, state.searchQuery.length),
            ),
        )
    }
    var lastSyncedExternalQuery by remember { mutableStateOf(state.searchQuery) }
    if (state.searchQuery != lastSyncedExternalQuery) {
        lastSyncedExternalQuery = state.searchQuery
        if (queryFieldValue.text != state.searchQuery) {
            queryFieldValue = TextFieldValue(
                text = state.searchQuery,
                selection = TextRange(0, state.searchQuery.length),
            )
        }
    }

    val flatRows = remember(state.searchHits) {
        buildList {
            for (hit in state.searchHits) {
                if (hit.matches.isEmpty()) continue
                hit.matches.forEachIndexed { idx, m ->
                    add(FlattenedSearchRow(hit = hit, match = m, isFirstInSession = idx == 0))
                }
            }
        }
    }
    var selectedFlatIdx by remember(flatRows) { mutableIntStateOf(0) }

    LaunchedEffect(openNonce) {
        withFrameNanos {}
        runCatching { focusRequester.requestFocus() }
        if (queryFieldValue.text.isNotEmpty()) {
            withFrameNanos {}
            queryFieldValue = queryFieldValue.copy(
                selection = TextRange(0, queryFieldValue.text.length),
            )
        }
    }

    LaunchedEffect(selectedFlatIdx, flatRows.size) {
        if (selectedFlatIdx in flatRows.indices) {
            listState.animateScrollToItem(selectedFlatIdx)
        }
    }

    val engineOptions = remember(state.engines, state.sessions) {
        val fromStatus = state.engines.filterNot { it.id == "mock" }.map { it.id }
        val fromSessions = state.sessions.map { it.engineId }.filterNot { it == "mock" }
        (fromStatus + fromSessions).distinct()
    }

    val activeWorkspacePath = state.activeSession?.cwd?.takeIf { it.isNotBlank() }
        ?: state.selectedWorkspacePath.takeIf { it.isNotBlank() }

    dev.lework.ui.ModalEscapeEffect(onDismiss = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            shadowElevation = 20.dp,
            modifier = Modifier
                .widthIn(min = 560.dp, max = 760.dp)
                .fillMaxWidth(0.84f)
                .heightIn(min = 380.dp, max = 600.dp)
                .testTag("desktop_global_search_dialog")
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        Key.DirectionDown -> {
                            if (flatRows.isNotEmpty()) {
                                selectedFlatIdx = (selectedFlatIdx + 1).coerceAtMost(flatRows.lastIndex)
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (flatRows.isNotEmpty()) {
                                selectedFlatIdx = (selectedFlatIdx - 1).coerceAtLeast(0)
                            }
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            val chosen = flatRows.getOrNull(selectedFlatIdx)
                            if (chosen != null) {
                                onDismiss()
                                onJumpToHit(chosen.hit.sessionId, chosen.match.seq)
                            } else if (state.searchQuery.isNotBlank()) {
                                onSearch(
                                    state.searchQuery,
                                    state.searchEngineFilter,
                                    state.searchRoleFilter,
                                    state.searchScopeCurrentWorkspace,
                                    state.searchIncludeArchived,
                                    true,
                                )
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Top Search Input Row ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "全文搜索",
                        tint = Everforest.Green,
                        modifier = Modifier.size(19.dp),
                    )
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.searchQuery.isEmpty()) {
                            Text(
                                text = "搜索所有历史对话内容、提问或工作区（空格分隔多词）…",
                                fontSize = 13.5.sp,
                                color = Everforest.Grey0,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = queryFieldValue,
                            onValueChange = { next ->
                                val textChanged = next.text != queryFieldValue.text
                                queryFieldValue = next
                                if (textChanged) {
                                    onSearch(
                                        next.text,
                                        state.searchEngineFilter,
                                        state.searchRoleFilter,
                                        state.searchScopeCurrentWorkspace,
                                        state.searchIncludeArchived,
                                        false,
                                    )
                                }
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = Everforest.Fg,
                                fontWeight = FontWeight.Normal,
                            ),
                            cursorBrush = SolidColor(Everforest.Green),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .testTag("desktop_global_search_input"),
                        )
                    }
                    if (state.searchLoading || state.searchIndexing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(
                                color = Everforest.BoldGold,
                                strokeWidth = 1.8.dp,
                                modifier = Modifier.size(13.dp),
                            )
                            if (state.searchIndexing && state.searchTotalSessions > 0) {
                                Text(
                                    text = "索引 ${state.searchIndexedSessions}/${state.searchTotalSessions}",
                                    fontSize = 11.sp,
                                    color = Everforest.BoldGold,
                                )
                            }
                        }
                    } else if (state.searchTotalSessions > 0 && state.searchQuery.isNotBlank()) {
                        Text(
                            text = "命中 ${state.searchHits.size} 个会话",
                            fontSize = 11.sp,
                            color = Everforest.CommentGrey,
                        )
                    }
                    if (state.searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清空",
                            tint = Everforest.CommentGrey,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    queryFieldValue = TextFieldValue("")
                                    onSearch(
                                        "",
                                        state.searchEngineFilter,
                                        state.searchRoleFilter,
                                        state.searchScopeCurrentWorkspace,
                                        state.searchIncludeArchived,
                                        true,
                                    )
                                },
                        )
                    }
                }

                HorizontalDivider(color = Everforest.Bg2)

                // ── Filter Chips Row (Role · Engine · Scope) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Everforest.Bg0.copy(alpha = 0.45f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SearchFilterChip(
                        label = "全部消息",
                        selected = state.searchRoleFilter == SearchRoleFilter.ALL,
                        testTag = "search_filter_role_all",
                        onClick = {
                            onSearch(
                                state.searchQuery,
                                state.searchEngineFilter,
                                SearchRoleFilter.ALL,
                                state.searchScopeCurrentWorkspace,
                                state.searchIncludeArchived,
                                true,
                            )
                        },
                    )
                    SearchFilterChip(
                        label = "仅我的提问",
                        selected = state.searchRoleFilter == SearchRoleFilter.USER,
                        accentColor = Everforest.Aqua,
                        testTag = "search_filter_role_user",
                        onClick = {
                            onSearch(
                                state.searchQuery,
                                state.searchEngineFilter,
                                SearchRoleFilter.USER,
                                state.searchScopeCurrentWorkspace,
                                state.searchIncludeArchived,
                                true,
                            )
                        },
                    )
                    SearchFilterChip(
                        label = "仅 AI 回复",
                        selected = state.searchRoleFilter == SearchRoleFilter.ASSISTANT,
                        accentColor = Everforest.Green,
                        testTag = "search_filter_role_assistant",
                        onClick = {
                            onSearch(
                                state.searchQuery,
                                state.searchEngineFilter,
                                SearchRoleFilter.ASSISTANT,
                                state.searchScopeCurrentWorkspace,
                                state.searchIncludeArchived,
                                true,
                            )
                        },
                    )

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(14.dp)
                            .background(Everforest.Bg3),
                    )

                    SearchFilterChip(
                        label = "全部引擎",
                        selected = state.searchEngineFilter == null,
                        onClick = {
                            onSearch(
                                state.searchQuery,
                                null,
                                state.searchRoleFilter,
                                state.searchScopeCurrentWorkspace,
                                state.searchIncludeArchived,
                                true,
                            )
                        },
                    )
                    for (engId in engineOptions.take(5)) {
                        SearchFilterChip(
                            label = shortEngineLabel(engId),
                            selected = state.searchEngineFilter == engId,
                            accentColor = engineAccentColor(engId),
                            testTag = "search_filter_engine_$engId",
                            onClick = {
                                val nextEng = if (state.searchEngineFilter == engId) null else engId
                                onSearch(
                                    state.searchQuery,
                                    nextEng,
                                    state.searchRoleFilter,
                                    state.searchScopeCurrentWorkspace,
                                    state.searchIncludeArchived,
                                    true,
                                )
                            },
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    if (activeWorkspacePath != null) {
                        SearchFilterChip(
                            label = "当前工作区",
                            selected = state.searchScopeCurrentWorkspace,
                            accentColor = Everforest.BoldGold,
                            testTag = "search_filter_current_ws",
                            onClick = {
                                onSearch(
                                    state.searchQuery,
                                    state.searchEngineFilter,
                                    state.searchRoleFilter,
                                    !state.searchScopeCurrentWorkspace,
                                    state.searchIncludeArchived,
                                    true,
                                )
                            },
                        )
                    }
                    SearchFilterChip(
                        label = "含已归档",
                        selected = state.searchIncludeArchived,
                        accentColor = Everforest.BoldGold,
                        testTag = "search_filter_archived",
                        onClick = {
                            onSearch(
                                state.searchQuery,
                                state.searchEngineFilter,
                                state.searchRoleFilter,
                                state.searchScopeCurrentWorkspace,
                                !state.searchIncludeArchived,
                                true,
                            )
                        },
                    )
                }

                HorizontalDivider(color = Everforest.Bg2)

                // ── Results List ──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when {
                        !state.searchError.isNullOrBlank() -> {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = state.searchError.orEmpty(),
                                    fontSize = 12.5.sp,
                                    color = Everforest.Red,
                                )
                            }
                        }
                        state.searchQuery.isBlank() -> {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = Everforest.CommentGrey,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "跨引擎全文检索历史对话",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Everforest.Fg,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "支持任意关键词或空格分隔多词，命中后点击直接定位到原消息",
                                    fontSize = 12.sp,
                                    color = Everforest.Grey0,
                                )
                            }
                        }
                        flatRows.isEmpty() && !state.searchLoading -> {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = "未找到包含「${state.searchQuery.trim()}」的历史消息",
                                    fontSize = 13.sp,
                                    color = Everforest.Grey2,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "可尝试减少关键词、切换「全部消息」或勾选「含已归档」",
                                    fontSize = 11.5.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .testTag("desktop_global_search_results"),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                itemsIndexed(
                                    items = flatRows,
                                    key = { idx, row -> "${row.hit.sessionId}_${row.match.seq}_$idx" },
                                ) { idx, row ->
                                    val selected = idx == selectedFlatIdx
                                    val hoverSource = remember { MutableInteractionSource() }
                                    val hovered by hoverSource.collectIsHoveredAsState()

                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        if (row.isFirstInSession) {
                                            if (idx > 0) {
                                                Spacer(Modifier.height(6.dp))
                                            }
                                            SearchSessionGroupHeader(
                                                hit = row.hit,
                                                query = state.searchQuery,
                                                nowMs = nowMs,
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(9.dp),
                                            color = when {
                                                selected -> Everforest.BgGreen.copy(alpha = 0.85f)
                                                hovered -> Everforest.Bg2.copy(alpha = 0.72f)
                                                else -> Everforest.Bg0.copy(alpha = 0.65f)
                                            },
                                            border = BorderStroke(
                                                1.dp,
                                                if (selected) Everforest.Green.copy(alpha = 0.65f) else Everforest.Bg2,
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 10.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                                .hoverable(hoverSource)
                                                .clickable {
                                                    onDismiss()
                                                    onJumpToHit(row.hit.sessionId, row.match.seq)
                                                }
                                                .testTag("search_hit_${row.hit.sessionId}_${row.match.seq}"),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                val isUser = row.match.role == SearchMatchRole.USER
                                                Surface(
                                                    shape = RoundedCornerShape(5.dp),
                                                    color = if (isUser) {
                                                        Everforest.Aqua.copy(alpha = 0.16f)
                                                    } else {
                                                        Everforest.Green.copy(alpha = 0.14f)
                                                    },
                                                ) {
                                                    Text(
                                                        text = if (isUser) "我" else "AI",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (isUser) Everforest.Aqua else Everforest.Green,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                                    )
                                                }
                                                Text(
                                                    text = buildHighlightedSearchText(
                                                        text = row.match.snippet,
                                                        query = state.searchQuery,
                                                    ),
                                                    fontSize = 12.sp,
                                                    lineHeight = 17.sp,
                                                    color = Everforest.Fg,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                if (row.match.ts > 0L) {
                                                    Text(
                                                        text = formatSearchShortTime(row.match.ts, nowMs),
                                                        fontSize = 10.5.sp,
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
                }

                HorizontalDivider(color = Everforest.Bg2)

                // ── Footer Keyboard Hints ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Everforest.BgDim.copy(alpha = 0.7f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "↑↓ 选择命中 · Enter 跳转并高亮原消息 · Esc 关闭",
                        fontSize = 11.sp,
                        color = Everforest.CommentGrey,
                    )
                    Text(
                        text = "⇧⌘F 全局搜索 · ⌘F 会话内查找",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.Grey0,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSessionGroupHeader(
    hit: SessionSearchHit,
    query: String,
    nowMs: Long,
) {
    val wsShort = hit.cwd.trimEnd('/').substringAfterLast('/').ifBlank { hit.cwd }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        EngineGlyphIcon(engineId = hit.engineId, size = 15.dp)
        Text(
            text = buildHighlightedSearchText(hit.title.ifBlank { "未命名会话" }, query),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Everforest.Fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (hit.archived == true) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Everforest.Bg2,
            ) {
                Text(
                    text = "已归档",
                    fontSize = 9.5.sp,
                    color = Everforest.CommentGrey,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = Everforest.CommentGrey,
                modifier = Modifier.size(11.dp),
            )
            Text(
                text = buildHighlightedSearchText(wsShort, query),
                fontSize = 11.sp,
                color = Everforest.Grey0,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        if (hit.matchCount > 1) {
            Text(
                text = "${hit.matchCount} 处匹配",
                fontSize = 10.5.sp,
                color = Everforest.BoldGold,
            )
        }
        Text(
            text = formatSearchShortTime(hit.updatedAt, nowMs),
            fontSize = 10.5.sp,
            color = Everforest.CommentGrey,
        )
    }
}

@Composable
private fun SearchFilterChip(
    label: String,
    selected: Boolean,
    accentColor: Color = Everforest.Green,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) accentColor.copy(alpha = 0.16f) else Everforest.Bg2.copy(alpha = 0.65f),
        border = BorderStroke(
            1.dp,
            if (selected) accentColor.copy(alpha = 0.65f) else Everforest.Bg3.copy(alpha = 0.7f),
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .let { if (testTag != null) it.testTag(testTag) else it },
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accentColor else Everforest.Grey2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

internal fun buildHighlightedSearchText(
    text: String,
    query: String,
): AnnotatedString = highlightPlainTextForSearch(
    text = text,
    query = query,
    primary = true,
)

private fun formatSearchShortTime(tsMs: Long, nowMs: Long): String {
    if (tsMs <= 0L) return ""
    val p = localDateTimeParts(tsMs)
    val nowP = localDateTimeParts(nowMs)
    val hh = p.hour.toString().padStart(2, '0')
    val mm = p.minute.toString().padStart(2, '0')
    return if (p.year == nowP.year && p.month == nowP.month && p.day == nowP.day) {
        "$hh:$mm"
    } else if (p.year == nowP.year) {
        "${p.month.toString().padStart(2, '0')}-${p.day.toString().padStart(2, '0')} $hh:$mm"
    } else {
        "${p.year}-${p.month.toString().padStart(2, '0')}-${p.day.toString().padStart(2, '0')}"
    }
}
