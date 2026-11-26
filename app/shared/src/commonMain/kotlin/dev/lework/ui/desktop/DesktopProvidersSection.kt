package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.protocol.ProviderAdapterKind
import dev.lework.protocol.ProviderConfigInput
import dev.lework.protocol.ProviderInfo
import dev.lework.ui.ModalEscapeEffect
import dev.lework.ui.dialogEscapeDismiss
import dev.lework.protocol.WireApi
import dev.lework.store.ConsoleUiState
import dev.lework.ui.Everforest
import dev.lework.ui.shortEngineLabel

@Composable
internal fun DesktopProvidersSection(
    state: ConsoleUiState,
    onRefreshProviders: (providerId: String?) -> Unit,
    onProbeDraft: (ProviderConfigInput) -> Unit,
    onUpsertProvider: (ProviderConfigInput, onSuccess: () -> Unit) -> Unit,
    onRemoveProvider: (providerId: String) -> Unit,
    onClearFeedback: () -> Unit,
) {
    var editingProvider by remember { mutableStateOf<ProviderInfo?>(null) }
    var filteringProvider by remember { mutableStateOf<ProviderInfo?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val externalProviders = state.externalProviders

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.Bg0,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_providers_section"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "模型服务（当前主机）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.Fg,
                    )
                    Text(
                        text = "仅作用于「${state.activeHostDisplayName}」，每台开发机独立保存；AGY 保持仅官方登录",
                        fontSize = 11.sp,
                        color = Everforest.CommentGrey,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LeworkIconActionButton(
                        icon = Icons.Rounded.Refresh,
                        tooltip = "刷新所有模型服务",
                        size = 24.dp,
                        iconSize = 14.dp,
                        testTag = "settings_btn_refresh_providers",
                        onClick = { onRefreshProviders(null) },
                    )
                    Text(
                        text = "＋ 添加服务",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Aqua,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onClearFeedback()
                                editingProvider = null
                                showCreateDialog = true
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("settings_btn_add_provider"),
                    )
                }
            }

            state.providerActionError?.let { err ->
                Text(
                    text = err,
                    fontSize = 11.sp,
                    color = Everforest.SoftRed,
                    modifier = Modifier.testTag("settings_providers_error"),
                )
            }
            state.providerActionNotice?.let { notice ->
                Text(
                    text = notice,
                    fontSize = 11.sp,
                    color = Everforest.Green,
                    modifier = Modifier.testTag("settings_providers_notice"),
                )
            }

            if (externalProviders.isEmpty()) {
                Text(
                    text = "当前主机尚未配置外部模型服务，可点击右上「＋ 添加服务」接入本机 CPA、OpenRouter、LiteLLM 或 Ollama。",
                    fontSize = 11.5.sp,
                    color = Everforest.Grey1,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (provider in externalProviders) {
                        val isProbing = state.providerProbingId == provider.id
                        val statusColor = when {
                            !provider.enabled -> Everforest.Grey0
                            provider.healthy -> Everforest.Green
                            provider.authStatus == "required" -> Everforest.BoldGold
                            else -> Everforest.SoftRed
                        }
                        val statusText = when {
                            isProbing -> "正在探测…"
                            !provider.enabled -> "已停用"
                            provider.healthy -> buildString {
                                val total = provider.models.size
                                val curated = provider.selectedModels.count { it in provider.models }
                                if (provider.selectedModels.isNotEmpty()) {
                                    append("在线 · 已选 $curated/$total 个模型")
                                } else {
                                    append("在线 · $total 个模型")
                                }
                                provider.latencyMs?.let { append(" · ${it}ms") }
                            }
                            provider.authStatus == "required" -> "需填写访问密钥"
                            else -> provider.error ?: "未连接"
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Everforest.Bg1,
                            border = BorderStroke(
                                1.dp,
                                if (provider.enabled && provider.healthy) {
                                    Everforest.Aqua.copy(alpha = 0.28f)
                                } else {
                                    Everforest.Bg2
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_provider_card_${provider.id}"),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(statusColor),
                                        )
                                        Text(
                                            text = provider.name,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (provider.enabled) Everforest.Fg else Everforest.Grey1,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Everforest.Bg0,
                                        ) {
                                            Text(
                                                text = if (provider.adapter == ProviderAdapterKind.CPA) "CPA 插件" else "标准兼容",
                                                fontSize = 9.5.sp,
                                                color = Everforest.Aqua,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                        Text(
                                            text = statusText,
                                            fontSize = 11.sp,
                                            color = statusColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (provider.enabled && provider.models.isNotEmpty()) {
                                            val hasFilter = provider.selectedModels.isNotEmpty()
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = if (hasFilter) Everforest.BgGreen else Everforest.Bg0,
                                                border = BorderStroke(
                                                    0.8.dp,
                                                    if (hasFilter) Everforest.Green.copy(alpha = 0.45f) else Everforest.Bg3,
                                                ),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .clickable(enabled = !state.providersBusy) {
                                                        onClearFeedback()
                                                        filteringProvider = provider
                                                    }
                                                    .testTag("settings_provider_filter_${provider.id}"),
                                            ) {
                                                Text(
                                                    text = if (hasFilter) "筛选 (${provider.selectedModels.size})" else "筛选模型",
                                                    fontSize = 10.5.sp,
                                                    color = if (hasFilter) Everforest.Green else Everforest.Aqua,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (provider.enabled) "停用" else "启用",
                                            fontSize = 11.sp,
                                            color = if (provider.enabled) Everforest.Grey1 else Everforest.Green,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(5.dp))
                                                .clickable(enabled = !state.providersBusy) {
                                                    onUpsertProvider(
                                                        ProviderConfigInput(
                                                            id = provider.id,
                                                            name = provider.name,
                                                            adapter = provider.adapter,
                                                            baseUrl = provider.baseUrl ?: "http://127.0.0.1:8317",
                                                            wireApis = provider.wireApis.takeIf { it.isNotEmpty() },
                                                            enabledEngines = provider.enabledEngines,
                                                            enabled = !provider.enabled,
                                                        ),
                                                        {},
                                                    )
                                                }
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                                .testTag("settings_provider_toggle_${provider.id}"),
                                        )
                                        LeworkIconActionButton(
                                            icon = Icons.Rounded.Refresh,
                                            tooltip = "刷新并测通此服务",
                                            size = 24.dp,
                                            iconSize = 13.5.dp,
                                            testTag = "settings_provider_refresh_${provider.id}",
                                            onClick = { onRefreshProviders(provider.id) },
                                        )
                                        LeworkIconActionButton(
                                            icon = Icons.Rounded.Edit,
                                            tooltip = "编辑服务配置",
                                            size = 24.dp,
                                            iconSize = 13.5.dp,
                                            testTag = "settings_provider_edit_${provider.id}",
                                            onClick = {
                                                onClearFeedback()
                                                editingProvider = provider
                                                showCreateDialog = false
                                            },
                                        )
                                        LeworkIconActionButton(
                                            icon = Icons.Rounded.DeleteOutline,
                                            tooltip = "从当前主机移除",
                                            size = 24.dp,
                                            iconSize = 13.5.dp,
                                            testTag = "settings_provider_remove_${provider.id}",
                                            onClick = { onRemoveProvider(provider.id) },
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = provider.baseUrl.orEmpty(),
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Everforest.Grey0,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (provider.enabledEngines.isNotEmpty()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            for (engId in provider.enabledEngines) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Everforest.Bg0,
                                                ) {
                                                    Text(
                                                        text = shortEngineLabel(engId),
                                                        fontSize = 9.5.sp,
                                                        color = Everforest.Grey1,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
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
            }
        }
    }

    if (showCreateDialog || editingProvider != null) {
        DesktopProviderEditorDialog(
            initial = editingProvider,
            hostDisplayName = state.activeHostDisplayName,
            busy = state.providersBusy,
            probing = state.providerProbingId != null,
            probeResult = state.providerDraftProbeResult,
            errorText = state.providerActionError,
            onProbe = onProbeDraft,
            onSave = { input ->
                onUpsertProvider(input) {
                    showCreateDialog = false
                    editingProvider = null
                }
            },
            onDismiss = {
                onClearFeedback()
                showCreateDialog = false
                editingProvider = null
            },
        )
    }

    val activeFilterTarget = filteringProvider?.let { target ->
        externalProviders.firstOrNull { it.id == target.id } ?: target
    }
    if (activeFilterTarget != null) {
        DesktopProviderModelFilterDialog(
            provider = activeFilterTarget,
            busy = state.providersBusy,
            onSave = { selectedList ->
                onUpsertProvider(
                    ProviderConfigInput(
                        id = activeFilterTarget.id,
                        name = activeFilterTarget.name,
                        adapter = activeFilterTarget.adapter,
                        baseUrl = activeFilterTarget.baseUrl ?: "http://127.0.0.1:8317",
                        wireApis = activeFilterTarget.wireApis.takeIf { it.isNotEmpty() },
                        enabledEngines = activeFilterTarget.enabledEngines,
                        enabled = activeFilterTarget.enabled,
                        selectedModels = selectedList,
                    ),
                ) {
                    filteringProvider = null
                }
            },
            onDismiss = { filteringProvider = null },
        )
    }
}

private fun modelFamilyLabel(modelId: String): String {
    val lower = modelId.lowercase()
    return when {
        lower.startsWith("claude") -> "Claude"
        lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3") ||
            lower.startsWith("o4") || lower.startsWith("codex") -> "GPT / Codex"
        lower.startsWith("gemini") -> "Gemini"
        lower.startsWith("kimi") || lower.startsWith("moonshot") -> "Kimi"
        lower.startsWith("deepseek") -> "DeepSeek"
        lower.startsWith("glm") -> "GLM"
        lower.startsWith("qwen") -> "Qwen"
        else -> "其他"
    }
}

@Composable
private fun DesktopProviderModelFilterDialog(
    provider: ProviderInfo,
    busy: Boolean,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val allModels = remember(provider.models) { provider.models.distinct() }
    var selectedSet by remember(provider.id, provider.selectedModels, allModels) {
        val initial = provider.selectedModels.filter { it in allModels }.toSet()
        mutableStateOf(if (initial.isEmpty()) allModels.toSet() else initial)
    }
    var searchQuery by remember(provider.id) { mutableStateOf("") }
    var selectedFamily by remember(provider.id) { mutableStateOf<String?>(null) }

    val families = remember(allModels) {
        val counts = linkedMapOf<String, Int>()
        for (m in allModels) {
            val fam = modelFamilyLabel(m)
            counts[fam] = (counts[fam] ?: 0) + 1
        }
        counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    val filteredModels = remember(allModels, searchQuery, selectedFamily) {
        val q = searchQuery.trim().lowercase()
        allModels.filter { id ->
            val famOk = selectedFamily == null || modelFamilyLabel(id) == selectedFamily
            val qOk = q.isEmpty() || id.lowercase().contains(q)
            famOk && qOk
        }
    }

    ModalEscapeEffect(onDismiss = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            shadowElevation = 18.dp,
            modifier = Modifier
                .width(500.dp)
                .heightIn(max = 620.dp)
                .dialogEscapeDismiss(onDismiss = onDismiss)
                .testTag("provider_model_filter_dialog"),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "筛选「${provider.name}」展示的模型",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Everforest.Fg,
                        )
                        Text(
                            text = "仅把勾选的模型合并到 ⌘K 引擎列表中（已选 ${selectedSet.size} / 共 ${allModels.size} 个）",
                            fontSize = 11.5.sp,
                            color = Everforest.CommentGrey,
                        )
                    }
                    LeworkIconActionButton(
                        icon = Icons.Rounded.Close,
                        tooltip = "关闭",
                        shortcut = "Esc",
                        onClick = onDismiss,
                    )
                }

                // Search bar
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Everforest.Bg0,
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
                            contentDescription = null,
                            tint = Everforest.CommentGrey,
                            modifier = Modifier.size(14.5.dp),
                        )
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                Text("搜索模型关键词，例如 claude / gpt-6.2 / kimi…", fontSize = 12.sp, color = Everforest.Grey0)
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 12.sp, color = Everforest.Fg),
                                cursorBrush = SolidColor(Everforest.Green),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("provider_model_filter_search"),
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "清空",
                                tint = Everforest.Grey1,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { searchQuery = "" },
                            )
                        }
                    }
                }

                // Family filter chips
                if (families.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ProviderChoiceChip(
                            label = "全部 (${allModels.size})",
                            selected = selectedFamily == null,
                            onClick = { selectedFamily = null },
                        )
                        for ((fam, count) in families) {
                            ProviderChoiceChip(
                                label = "$fam ($count)",
                                selected = selectedFamily == fam,
                                onClick = { selectedFamily = if (selectedFamily == fam) null else fam },
                            )
                        }
                    }
                }

                // Quick bulk actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "全选当前 (${filteredModels.size})",
                            fontSize = 11.5.sp,
                            color = Everforest.Green,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .clickable { selectedSet = selectedSet + filteredModels }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        Text(
                            text = "取消当前",
                            fontSize = 11.5.sp,
                            color = Everforest.Grey1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .clickable { selectedSet = selectedSet - filteredModels.toSet() }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .testTag("provider_filter_btn_clear_visible"),
                        )
                    }
                    Text(
                        text = "恢复全部展示",
                        fontSize = 11.5.sp,
                        color = Everforest.Aqua,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { selectedSet = allModels.toSet() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("provider_filter_btn_reset_all"),
                    )
                }

                HorizontalDivider(color = Everforest.Bg2)

                // Model checklist
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 140.dp, max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (filteredModels.isEmpty()) {
                        Text(
                            text = "没有匹配的模型",
                            fontSize = 12.sp,
                            color = Everforest.Grey1,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    } else {
                        for (modelId in filteredModels) {
                            val checked = modelId in selectedSet
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (checked) Everforest.Bg2.copy(alpha = 0.65f) else Everforest.Bg0.copy(alpha = 0.45f))
                                    .clickable {
                                        selectedSet = if (checked) selectedSet - modelId else selectedSet + modelId
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                                    .testTag("provider_filter_item_$modelId"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (checked) Everforest.Green else Everforest.Bg0,
                                    border = BorderStroke(1.dp, if (checked) Everforest.Green else Everforest.Bg3),
                                    modifier = Modifier.size(16.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (checked) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = Everforest.Bg0,
                                                modifier = Modifier.size(12.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = modelId,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (checked) Everforest.Fg else Everforest.Grey1,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = modelFamilyLabel(modelId),
                                    fontSize = 10.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Everforest.Bg2)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selectedSet.size == allModels.size) {
                            "当前为全量展示"
                        } else if (selectedSet.isEmpty()) {
                            "至少勾选 1 个模型，或点「恢复全部展示」"
                        } else {
                            "保存后仅展示已勾选的 ${selectedSet.size} 个模型"
                        },
                        fontSize = 11.sp,
                        color = if (selectedSet.isEmpty()) Everforest.BoldGold else Everforest.CommentGrey,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("取消", color = Everforest.Grey1, fontSize = 12.sp)
                        }
                        val canSave = !busy && selectedSet.isNotEmpty()
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (canSave) Everforest.Green else Everforest.Bg2,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = canSave) {
                                    // All models checked => save empty list (meaning "no allowlist filter")
                                    val payload = if (selectedSet.size >= allModels.size) {
                                        emptyList()
                                    } else {
                                        allModels.filter { it in selectedSet }
                                    }
                                    onSave(payload)
                                }
                                .testTag("provider_filter_btn_save"),
                        ) {
                            Text(
                                text = if (busy) "保存中…" else "保存筛选",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (canSave) Everforest.Bg0 else Everforest.Grey0,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopProviderEditorDialog(
    initial: ProviderInfo?,
    hostDisplayName: String,
    busy: Boolean,
    probing: Boolean,
    probeResult: ProviderInfo?,
    errorText: String?,
    onProbe: (ProviderConfigInput) -> Unit,
    onSave: (ProviderConfigInput) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEditing = initial != null
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var adapter by remember(initial?.id) {
        mutableStateOf(initial?.adapter ?: ProviderAdapterKind.GENERIC)
    }
    var baseUrl by remember(initial?.id) { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember(initial?.id) { mutableStateOf("") }
    var clearApiKey by remember(initial?.id) { mutableStateOf(false) }
    var showApiKey by remember(initial?.id) { mutableStateOf(false) }
    var apiKeyEnv by remember(initial?.id) { mutableStateOf(initial?.apiKeyEnv ?: "") }
    var manualModelsText by remember(initial?.id) {
        mutableStateOf(initial?.manualModels?.joinToString(", ").orEmpty())
    }
    var selectedWireApis by remember(initial?.id) {
        mutableStateOf(
            initial?.wireApis?.takeIf { it.isNotEmpty() }?.toSet()
                ?: setOf(WireApi.ANTHROPIC_MESSAGES, WireApi.OPENAI_RESPONSES, WireApi.OPENAI_CHAT),
        )
    }
    var selectedEngines by remember(initial?.id) {
        mutableStateOf(
            initial?.enabledEngines?.takeIf { it.isNotEmpty() }?.toSet()
                ?: setOf("claude", "codex", "pi"),
        )
    }
    var localValidationError by remember { mutableStateOf<String?>(null) }

    fun buildInput(): ProviderConfigInput? {
        val cleanName = name.trim()
        val cleanUrl = baseUrl.trim()
        if (cleanName.isEmpty()) {
            localValidationError = "请填写模型服务简称（例如 CPA、OpenRouter）"
            return null
        }
        if (cleanUrl.isEmpty() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) {
            localValidationError = "服务地址需以 http:// 或 https:// 开头"
            return null
        }
        if (selectedWireApis.isEmpty()) {
            localValidationError = "请至少选择一种兼容协议"
            return null
        }
        localValidationError = null
        val manualList = manualModelsText
            .split(',', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return ProviderConfigInput(
            id = initial?.id,
            name = cleanName,
            adapter = adapter,
            baseUrl = cleanUrl,
            apiKey = apiKey.trim().takeIf { it.isNotEmpty() },
            clearApiKey = if (clearApiKey) true else null,
            apiKeyEnv = apiKeyEnv.trim(),
            wireApis = selectedWireApis.toList(),
            enabledEngines = selectedEngines.toList(),
            enabled = initial?.enabled ?: true,
            manualModels = manualList,
        )
    }

    ModalEscapeEffect(onDismiss = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            shadowElevation = 18.dp,
            modifier = Modifier
                .width(500.dp)
                .heightIn(max = 680.dp)
                .dialogEscapeDismiss(onDismiss = onDismiss)
                .testTag("provider_editor_dialog"),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (isEditing) "编辑模型服务" else "添加模型服务",
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Everforest.Fg,
                        )
                        Text(
                            text = "配置将保存在主机「$hostDisplayName」本地，不跨主机共享",
                            fontSize = 11.5.sp,
                            color = Everforest.CommentGrey,
                        )
                    }
                    LeworkIconActionButton(
                        icon = Icons.Rounded.Close,
                        tooltip = "关闭",
                        shortcut = "Esc",
                        onClick = onDismiss,
                    )
                }

                HorizontalDivider(color = Everforest.Bg2)

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!isEditing) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("快捷模板", fontSize = 11.5.sp, color = Everforest.Grey1)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ProviderPresetChip("本机 CPA") {
                                    name = "CPA"
                                    adapter = ProviderAdapterKind.CPA
                                    baseUrl = "http://127.0.0.1:8317"
                                    selectedWireApis = setOf(
                                        WireApi.ANTHROPIC_MESSAGES,
                                        WireApi.OPENAI_RESPONSES,
                                        WireApi.OPENAI_CHAT,
                                    )
                                    selectedEngines = setOf("claude", "codex", "pi")
                                }
                                ProviderPresetChip("OpenRouter") {
                                    name = "OpenRouter"
                                    adapter = ProviderAdapterKind.GENERIC
                                    baseUrl = "https://openrouter.ai/api/v1"
                                    selectedWireApis = setOf(
                                        WireApi.ANTHROPIC_MESSAGES,
                                        WireApi.OPENAI_CHAT,
                                    )
                                    selectedEngines = setOf("claude", "codex", "pi")
                                }
                                ProviderPresetChip("LiteLLM / New-API") {
                                    name = "LiteLLM"
                                    adapter = ProviderAdapterKind.GENERIC
                                    baseUrl = "http://127.0.0.1:4000"
                                    selectedWireApis = setOf(
                                        WireApi.ANTHROPIC_MESSAGES,
                                        WireApi.OPENAI_RESPONSES,
                                        WireApi.OPENAI_CHAT,
                                    )
                                    selectedEngines = setOf("claude", "codex", "pi")
                                }
                                ProviderPresetChip("Ollama") {
                                    name = "Ollama"
                                    adapter = ProviderAdapterKind.GENERIC
                                    baseUrl = "http://127.0.0.1:11434"
                                    selectedWireApis = setOf(WireApi.OPENAI_CHAT)
                                    selectedEngines = setOf("codex", "pi")
                                }
                            }
                        }
                    }

                    ProviderTextField(
                        label = "服务简称（用于模型来源徽标）",
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "例如 CPA / OpenRouter / 公司网关",
                        testTag = "provider_field_name",
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("接入适配器", fontSize = 11.5.sp, color = Everforest.Grey1)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderChoiceChip(
                                label = "标准兼容服务",
                                selected = adapter == ProviderAdapterKind.GENERIC,
                                onClick = { adapter = ProviderAdapterKind.GENERIC },
                            )
                            ProviderChoiceChip(
                                label = "CPA 插件（自动读本机密钥）",
                                selected = adapter == ProviderAdapterKind.CPA,
                                onClick = { adapter = ProviderAdapterKind.CPA },
                            )
                        }
                    }

                    ProviderTextField(
                        label = "服务地址（Base URL）",
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        placeholder = "http://127.0.0.1:8317 或 https://openrouter.ai/api/v1",
                        monospace = true,
                        testTag = "provider_field_url",
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (isEditing && initial?.hasApiKey == true && !clearApiKey) {
                                    "访问密钥（主机已保存，留空保持不变）"
                                } else {
                                    "访问密钥（可选）"
                                },
                                fontSize = 11.5.sp,
                                color = Everforest.Grey1,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (apiKey.isNotEmpty()) {
                                    Text(
                                        text = if (showApiKey) "隐藏" else "显示",
                                        fontSize = 11.sp,
                                        color = Everforest.Aqua,
                                        modifier = Modifier.clickable { showApiKey = !showApiKey },
                                    )
                                }
                                if (isEditing && initial?.hasApiKey == true && !clearApiKey) {
                                    Text(
                                        text = "清除已存密钥",
                                        fontSize = 11.sp,
                                        color = Everforest.SoftRed,
                                        modifier = Modifier.clickable {
                                            clearApiKey = true
                                            apiKey = ""
                                        },
                                    )
                                }
                            }
                        }
                        ProviderTextField(
                            label = null,
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                if (it.isNotEmpty()) clearApiKey = false
                            },
                            placeholder = if (adapter == ProviderAdapterKind.CPA) {
                                "留空则自动读取 cpa-config.yaml / LEWORK_CPA_KEY"
                            } else {
                                "sk-..."
                            },
                            monospace = true,
                            masked = !showApiKey,
                            testTag = "provider_field_apikey",
                        )
                    }

                    ProviderTextField(
                        label = "或读取主机环境变量名（可选）",
                        value = apiKeyEnv,
                        onValueChange = { apiKeyEnv = it },
                        placeholder = "例如 OPENROUTER_API_KEY",
                        monospace = true,
                        testTag = "provider_field_env",
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("支持的协议接口", fontSize = 11.5.sp, color = Everforest.Grey1)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                WireApi.ANTHROPIC_MESSAGES to "Anthropic",
                                WireApi.OPENAI_RESPONSES to "OpenAI Responses",
                                WireApi.OPENAI_CHAT to "OpenAI Chat",
                            ).forEach { (wire, label) ->
                                val active = wire in selectedWireApis
                                ProviderChoiceChip(
                                    label = label,
                                    selected = active,
                                    onClick = {
                                        selectedWireApis = if (active && selectedWireApis.size > 1) {
                                            selectedWireApis - wire
                                        } else {
                                            selectedWireApis + wire
                                        }
                                    },
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("适用引擎（可跨厂商调用该服务暴露的对话模型）", fontSize = 11.5.sp, color = Everforest.Grey1)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("claude", "codex", "pi", "dsh").forEach { engId ->
                                val active = engId in selectedEngines
                                ProviderChoiceChip(
                                    label = shortEngineLabel(engId),
                                    selected = active,
                                    onClick = {
                                        selectedEngines = if (active) selectedEngines - engId else selectedEngines + engId
                                    },
                                )
                            }
                        }
                    }

                    ProviderTextField(
                        label = "手动补充模型列表（可选，逗号分隔）",
                        value = manualModelsText,
                        onValueChange = { manualModelsText = it },
                        placeholder = "当网关未提供 /v1/models 时可手动填写，例如 gpt-5.4, claude-opus-4-6",
                        monospace = true,
                        testTag = "provider_field_manual_models",
                    )

                    if (probeResult != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Everforest.Bg0,
                            border = BorderStroke(
                                1.dp,
                                if (probeResult.healthy) Everforest.Green.copy(alpha = 0.45f)
                                else Everforest.SoftRed.copy(alpha = 0.45f),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("provider_probe_result"),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = if (probeResult.healthy) {
                                        "连接成功 · 发现 ${probeResult.models.size} 个对话模型" +
                                            (probeResult.latencyMs?.let { " · ${it}ms" } ?: "")
                                    } else {
                                        "连接未通过：${probeResult.error ?: "请检查地址与访问密钥"}"
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (probeResult.healthy) Everforest.Green else Everforest.SoftRed,
                                )
                                if (probeResult.models.isNotEmpty()) {
                                    Text(
                                        text = probeResult.models.take(6).joinToString(" · ") +
                                            if (probeResult.models.size > 6) " …等 ${probeResult.models.size} 个" else "",
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Everforest.Grey1,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    val shownError = localValidationError ?: errorText
                    if (!shownError.isNullOrBlank()) {
                        Text(
                            text = shownError,
                            fontSize = 11.5.sp,
                            color = Everforest.SoftRed,
                        )
                    }
                }

                HorizontalDivider(color = Everforest.Bg2)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Everforest.Bg0,
                        border = BorderStroke(1.dp, Everforest.Bg3),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !probing && !busy) {
                                buildInput()?.let(onProbe)
                            }
                            .testTag("provider_btn_test"),
                    ) {
                        Text(
                            text = if (probing) "正在测试…" else "测试连接",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Everforest.Aqua,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("取消", color = Everforest.Grey1, fontSize = 12.sp)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Everforest.Green,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !busy) {
                                    buildInput()?.let(onSave)
                                }
                                .testTag("provider_btn_save"),
                        ) {
                            Text(
                                text = if (busy) "保存中…" else "保存并刷新",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.Bg0,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderPresetChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Everforest.Bg0,
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = Everforest.Aqua,
                modifier = Modifier.size(12.dp),
            )
            Text(text = label, fontSize = 11.sp, color = Everforest.Fg)
        }
    }
}

@Composable
private fun ProviderChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (selected) Everforest.BgGreen else Everforest.Bg0,
        border = BorderStroke(1.dp, if (selected) Everforest.Green else Everforest.Bg3),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = if (selected) Everforest.Green else Everforest.Grey1,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun ProviderTextField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    monospace: Boolean = false,
    masked: Boolean = false,
    testTag: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!label.isNullOrBlank()) {
            Text(text = label, fontSize = 11.5.sp, color = Everforest.Grey1)
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Everforest.Bg0,
            border = BorderStroke(1.dp, Everforest.Bg3),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 12.sp,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                        color = Everforest.Grey0,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    textStyle = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                        color = Everforest.Fg,
                    ),
                    cursorBrush = SolidColor(Everforest.Green),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(testTag),
                )
            }
        }
    }
}
