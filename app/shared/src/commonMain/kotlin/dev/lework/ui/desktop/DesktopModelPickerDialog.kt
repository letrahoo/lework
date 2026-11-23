package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.protocol.EngineModelSpec
import dev.lework.protocol.ReasoningEffort
import dev.lework.protocol.SessionMode
import dev.lework.store.ConsoleUiState
import dev.lework.ui.*

private data class DesktopPickerModelEntry(
    val engineId: String,
    val engineName: String,
    val spec: EngineModelSpec,
    val isFavorite: Boolean,
)

private fun compactPickerModelLabel(rawName: String): String {
    val cleaned = rawName.removeSuffix(" (Proxy)").trim()
    return cleaned
        .removePrefix("Claude ")
        .let { if (it.length > 16) it.take(15) + "…" else it }
}

private fun effortSubTag(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.LOW -> "Low"
    ReasoningEffort.MEDIUM -> "Medium"
    ReasoningEffort.HIGH -> "High"
    ReasoningEffort.XHIGH -> "Extra High"
    ReasoningEffort.MAX -> "Max"
}

@Composable
internal fun DesktopModelPickerDialog(
    state: ConsoleUiState,
    onSelectEngineAndModel: (String, EngineModelSpec?, ReasoningEffort?) -> Unit,
    onSelectEffort: (ReasoningEffort) -> Unit,
    onSelectMode: (SessionMode) -> Unit,
    onToggleFavoriteModel: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val favSet = remember(state.favoriteModelKeys) { state.favoriteModelKeys.toSet() }
    var selectedTab by remember {
        mutableStateOf(state.selectedEngineId.ifBlank { state.engines.firstOrNull()?.id ?: "claude" })
    }
    var searchQuery by remember { mutableStateOf("") }
    var expandedMore by remember(selectedTab) { mutableStateOf(false) }
    var showPermissionsMenu by remember { mutableStateOf(false) }
    var previewedEffort by remember { mutableStateOf<ReasoningEffort?>(null) }

    val searchInteraction = remember { MutableInteractionSource() }
    val searchFocused by searchInteraction.collectIsFocusedAsState()

    val allEntries = remember(state.engines, favSet) {
        buildList {
            for (eng in state.engines) {
                if (!eng.status.ready && eng.models.isEmpty()) continue
                val engLabel = eng.displayName.ifBlank { shortEngineLabel(eng.id) }
                for (m in eng.models) {
                    val key = "${eng.id}:${m.id}"
                    add(
                        DesktopPickerModelEntry(
                            engineId = eng.id,
                            engineName = engLabel,
                            spec = m,
                            isFavorite = key in favSet,
                        ),
                    )
                }
            }
        }
    }

    val isSearching = searchQuery.trim().isNotEmpty()
    val tabFilteredEntries = remember(allEntries, selectedTab, searchQuery, favSet) {
        val q = searchQuery.trim().lowercase()
        if (q.isNotEmpty()) {
            allEntries.filter { entry ->
                entry.spec.name.lowercase().contains(q) ||
                    entry.spec.id.lowercase().contains(q) ||
                    entry.engineName.lowercase().contains(q) ||
                    entry.engineId.lowercase().contains(q)
            }
        } else if (selectedTab == "favorites") {
            allEntries.filter { it.isFavorite }
        } else {
            allEntries.filter { it.engineId == selectedTab }
                .sortedByDescending { it.isFavorite }
        }
    }

    val defaultLimit = 6
    val visibleEntries = remember(tabFilteredEntries, expandedMore, isSearching, selectedTab, state.selectedModelSpec) {
        if (isSearching || selectedTab == "favorites" || expandedMore || tabFilteredEntries.size <= defaultLimit) {
            tabFilteredEntries
        } else {
            val top = tabFilteredEntries.take(defaultLimit).toMutableList()
            val activeId = state.selectedModelSpec?.id
            if (activeId != null && top.none { it.spec.id == activeId }) {
                tabFilteredEntries.firstOrNull { it.spec.id == activeId }?.let { activeEntry ->
                    top[top.lastIndex] = activeEntry
                }
            }
            top
        }
    }
    val hiddenMoreCount = (tabFilteredEntries.size - visibleEntries.size).coerceAtLeast(0)

    val jumpNumberKeys = remember {
        listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    }

    // Strict shared top-header height across all 3 columns so horizontal dividers align at y = 42.dp
    val columnTopInset = 6.dp
    val columnHeaderHeight = 36.dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            shadowElevation = 16.dp,
            modifier = Modifier
                .width(612.dp)
                .height(396.dp)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (ev.key == Key.Escape) {
                        onDismiss()
                        return@onPreviewKeyEvent true
                    }
                    if (ev.isMetaPressed || ev.isCtrlPressed) {
                        val idx = jumpNumberKeys.indexOf(ev.key)
                        if (idx >= 0 && idx < visibleEntries.size) {
                            val picked = visibleEntries[idx]
                            onSelectEngineAndModel(picked.engineId, picked.spec, null)
                            selectedTab = picked.engineId
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
                .testTag("desktop_model_picker_dialog"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Upper 3-Column Body: [1. Engine Rail] | [2. Models + Search] | [3. Reasoning] ──
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // Column 1: Left Vertical Engine Icon Rail
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxHeight()
                            .background(Everforest.BgDim.copy(alpha = 0.65f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(columnTopInset))
                        val favSelected = !isSearching && selectedTab == "favorites"
                        val favHover = remember { MutableInteractionSource() }
                        val isFavHovered by favHover.collectIsHoveredAsState()
                        LeworkTooltip(
                            label = "常用模型收藏",
                            subtitle = if (favSet.isEmpty()) "点击模型右侧 ☆ 加入收藏" else "已收藏 ${favSet.size} 个模型",
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(columnHeaderHeight)
                                    .testTag("picker_rail_favorites_slot"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when {
                                                favSelected -> Everforest.Bg2
                                                isFavHovered -> Everforest.Bg2.copy(alpha = 0.7f)
                                                else -> Color.Transparent
                                            },
                                        )
                                        .hoverable(favHover)
                                        .clickable(interactionSource = favHover, indication = null) {
                                            searchQuery = ""
                                            selectedTab = "favorites"
                                        }
                                        .testTag("picker_rail_favorites"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (favSelected || favSet.isNotEmpty()) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                        contentDescription = "常用模型收藏",
                                        tint = if (favSelected || favSet.isNotEmpty()) Everforest.BoldGold else Everforest.Grey1,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                if (favSelected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 2.dp)
                                            .width(3.dp)
                                            .height(18.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(Everforest.BoldGold),
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .height(1.dp)
                                .background(Everforest.Bg2),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (eng in state.engines) {
                                val isSelected = !isSearching && selectedTab == eng.id
                                val isReady = eng.status.ready
                                val engHover = remember { MutableInteractionSource() }
                                val isEngHovered by engHover.collectIsHoveredAsState()
                                val engTitle = eng.displayName.ifBlank { shortEngineLabel(eng.id) }

                                LeworkTooltip(
                                    label = engTitle,
                                    subtitle = if (isReady) {
                                        "就绪 · ${eng.models.size} 个可选模型"
                                    } else {
                                        "当前未就绪或未登录"
                                    },
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    when {
                                                        isSelected -> Everforest.Bg2
                                                        isEngHovered && isReady -> Everforest.Bg2.copy(alpha = 0.7f)
                                                        else -> Color.Transparent
                                                    },
                                                )
                                                .hoverable(engHover, enabled = isReady)
                                                .clickable(
                                                    interactionSource = engHover,
                                                    indication = null,
                                                    enabled = isReady,
                                                ) {
                                                    searchQuery = ""
                                                    selectedTab = eng.id
                                                    if (state.selectedEngineId != eng.id) {
                                                        onSelectEngineAndModel(eng.id, null, null)
                                                    }
                                                }
                                                .testTag("picker_rail_${eng.id}"),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            EngineGlyphIcon(
                                                engineId = eng.id,
                                                size = 18.dp,
                                                tintOverride = if (isReady) engineAccentColor(eng.id) else Everforest.Grey0.copy(alpha = 0.45f),
                                            )
                                        }
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterEnd)
                                                    .padding(end = 2.dp)
                                                    .width(3.dp)
                                                    .height(18.dp)
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(engineAccentColor(eng.id)),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Everforest.Bg2),
                    )

                    // Column 2: Center Search Input + Models List
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(top = columnTopInset),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(columnHeaderHeight)
                                    .testTag("model_picker_search_box"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = "搜索模型",
                                    tint = if (searchFocused) Everforest.Green else Everforest.CommentGrey,
                                    modifier = Modifier.size(15.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "搜索模型…",
                                            fontSize = 13.sp,
                                            lineHeight = 17.sp,
                                            color = Everforest.Grey0,
                                            maxLines = 1,
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        interactionSource = searchInteraction,
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            fontSize = 13.sp,
                                            lineHeight = 17.sp,
                                            color = Everforest.Fg,
                                        ),
                                        cursorBrush = SolidColor(Everforest.Green),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("model_picker_search_input"),
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "清空",
                                        tint = Everforest.CommentGrey,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { searchQuery = "" },
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(if (searchFocused || isSearching) Everforest.Green else Everforest.Bg2),
                            )
                        }

                        if (visibleEntries.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (selectedTab == "favorites" && !isSearching) {
                                        "暂无收藏模型\n将鼠标移至任意模型行右侧点 ☆ 即可收藏"
                                    } else {
                                        "未找到匹配的模型"
                                    },
                                    fontSize = 12.5.sp,
                                    lineHeight = 19.sp,
                                    color = Everforest.CommentGrey,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                itemsIndexed(
                                    items = visibleEntries,
                                    key = { _, entry -> "${entry.engineId}:${entry.spec.providerId}:${entry.spec.id}" },
                                ) { idx, entry ->
                                    val isCurrent =
                                        entry.engineId == state.selectedEngineId &&
                                            entry.spec.id == state.selectedModelSpec?.id
                                    val rowHover = remember { MutableInteractionSource() }
                                    val isHovered by rowHover.collectIsHoveredAsState()
                                    val cleanName = entry.spec.name.removeSuffix(" (Proxy)")

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(35.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                when {
                                                    isHovered -> Everforest.Bg2
                                                    isCurrent -> Everforest.Bg2.copy(alpha = 0.5f)
                                                    else -> Color.Transparent
                                                },
                                            )
                                            .hoverable(rowHover)
                                            .clickable(interactionSource = rowHover, indication = null) {
                                                onSelectEngineAndModel(entry.engineId, entry.spec, null)
                                                if (isSearching) {
                                                    selectedTab = entry.engineId
                                                }
                                            }
                                            .padding(horizontal = 9.dp)
                                            .testTag("picker_model_${entry.engineId}_${entry.spec.id}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        EngineGlyphIcon(engineId = entry.engineId, size = 14.5.dp)

                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Text(
                                                text = cleanName,
                                                fontSize = 13.sp,
                                                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                                                color = Everforest.Fg,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (entry.spec.providerId == "cpa_local") {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Everforest.Bg0,
                                                ) {
                                                    Text(
                                                        text = "代理",
                                                        fontSize = 9.5.sp,
                                                        color = Everforest.Aqua,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                            if (isCurrent) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = "已选中",
                                                    tint = Everforest.Green,
                                                    modifier = Modifier.size(14.5.dp),
                                                )
                                            }
                                        }

                                        if (entry.isFavorite || isHovered) {
                                            Icon(
                                                imageVector = if (entry.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                                contentDescription = if (entry.isFavorite) "取消收藏" else "收藏模型",
                                                tint = if (entry.isFavorite) Everforest.BoldGold else Everforest.Grey1,
                                                modifier = Modifier
                                                    .size(14.5.dp)
                                                    .clickable {
                                                        onToggleFavoriteModel(entry.engineId, entry.spec.id)
                                                    }
                                                    .testTag("picker_fav_${entry.engineId}_${entry.spec.id}"),
                                            )
                                        }

                                        if (idx < 9) {
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = Everforest.Bg0,
                                                border = BorderStroke(0.8.dp, Everforest.Bg3),
                                            ) {
                                                Text(
                                                    text = "⌘${idx + 1}",
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = Everforest.Grey1,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                if (hiddenMoreCount > 0) {
                                    item(key = "show_more_models") {
                                        val moreHover = remember { MutableInteractionSource() }
                                        val moreHovered by moreHover.collectIsHoveredAsState()
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (moreHovered) Everforest.Bg2.copy(alpha = 0.6f) else Color.Transparent)
                                                .hoverable(moreHover)
                                                .clickable(interactionSource = moreHover, indication = null) {
                                                    expandedMore = true
                                                }
                                                .padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "展开其余 $hiddenMoreCount 个模型",
                                                fontSize = 12.sp,
                                                color = if (moreHovered) Everforest.Fg else Everforest.CommentGrey,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Everforest.Bg2),
                    )

                    // Column 3: Right Reasoning Effort Column (with Quiet Bottom Description Preview)
                    Column(
                        modifier = Modifier
                            .width(196.dp)
                            .fillMaxHeight()
                            .background(Everforest.Bg0.copy(alpha = 0.55f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(top = columnTopInset),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(columnHeaderHeight)
                                    .testTag("picker_reasoning_header"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "推理等级",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Everforest.Grey1,
                                )
                                Text(
                                    text = state.selectedModelSpec?.name?.let { compactPickerModelLabel(it) } ?: "默认",
                                    fontSize = 11.5.sp,
                                    color = Everforest.CommentGrey,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Everforest.Bg2),
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (eff in state.availableEfforts) {
                                val selected = eff == state.selectedEffort
                                val effHover = remember { MutableInteractionSource() }
                                val isEffHovered by effHover.collectIsHoveredAsState()

                                LaunchedEffect(isEffHovered) {
                                    if (isEffHovered) {
                                        previewedEffort = eff
                                    } else if (previewedEffort == eff) {
                                        previewedEffort = null
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(
                                            when {
                                                isEffHovered -> Everforest.Bg2
                                                selected -> Everforest.Bg2.copy(alpha = 0.55f)
                                                else -> Color.Transparent
                                            },
                                        )
                                        .hoverable(effHover)
                                        .clickable(interactionSource = effHover, indication = null) {
                                            onSelectEffort(eff)
                                        }
                                        .padding(horizontal = 9.dp)
                                        .testTag("picker_effort_${eff.name.lowercase()}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = effortDisplayLabel(eff),
                                            fontSize = 13.sp,
                                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                            color = if (selected) Everforest.Fg else Everforest.Grey2,
                                        )
                                        Text(
                                            text = effortSubTag(eff),
                                            fontSize = 10.5.sp,
                                            color = Everforest.Grey0,
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "当前等级",
                                            tint = Everforest.Green,
                                            modifier = Modifier.size(14.5.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Quiet non-overlapping description footer for active/hovered reasoning level
                        val describedEffort = previewedEffort ?: state.selectedEffort
                        Text(
                            text = effortDescription(describedEffort),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = Everforest.CommentGrey,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }

                // ── Bottom Footer Bar: Active Engine Pill (Left) + Execution Mode Menu (Right) ──
                HorizontalDivider(color = Everforest.Bg2)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Everforest.BgDim.copy(alpha = 0.65f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = Everforest.Bg1,
                            border = BorderStroke(1.dp, Everforest.Bg2),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                EngineGlyphIcon(engineId = state.selectedEngineId, size = 12.5.dp)
                                Text(
                                    text = shortEngineLabel(state.selectedEngineId),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Everforest.Fg,
                                )
                            }
                        }
                        Text(
                            text = "⌘1-9 速选模型 · Esc 关闭",
                            fontSize = 11.sp,
                            color = Everforest.Grey0,
                        )
                    }

                    Box {
                        val modeHover = remember { MutableInteractionSource() }
                        val isModeHovered by modeHover.collectIsHoveredAsState()
                        val modeColor = when (state.selectedMode) {
                            SessionMode.PLAN -> Everforest.CodeGreen
                            SessionMode.AUTO, SessionMode.ACCEPT_EDITS -> Everforest.BoldGold
                            SessionMode.BYPASS_PERMISSIONS -> Everforest.Red
                            SessionMode.DEFAULT -> Everforest.Fg
                        }

                        LeworkTooltip(
                            label = "执行权限：${modeDisplayLabel(state.selectedMode)}",
                            subtitle = modeDescription(state.selectedMode),
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (isModeHovered || showPermissionsMenu) Everforest.Bg2 else Color.Transparent)
                                    .hoverable(modeHover)
                                    .clickable(interactionSource = modeHover, indication = null) {
                                        showPermissionsMenu = true
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.5.dp)
                                    .testTag("picker_footer_mode_btn"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = modeIcon(state.selectedMode),
                                    contentDescription = null,
                                    tint = modeColor,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = modeDisplayLabel(state.selectedMode),
                                    fontSize = 12.sp,
                                    color = modeColor,
                                    fontWeight = FontWeight.Medium,
                                )
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = null,
                                    tint = Everforest.Grey1,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showPermissionsMenu,
                            onDismissRequest = { showPermissionsMenu = false },
                            containerColor = Everforest.Bg1,
                        ) {
                            for (mode in state.availableModes) {
                                val selected = mode == state.selectedMode
                                DropdownMenuItem(
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = modeDisplayLabel(mode),
                                                fontSize = 13.sp,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = Everforest.Fg,
                                            )
                                            Text(
                                                text = modeDescription(mode),
                                                fontSize = 11.sp,
                                                color = Everforest.Grey1,
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = modeIcon(mode),
                                            contentDescription = null,
                                            tint = if (selected) Everforest.Green else Everforest.Grey1,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    trailingIcon = if (selected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = Everforest.Green,
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        showPermissionsMenu = false
                                        onSelectMode(mode)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
