package dev.lework.ui

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.lework.platform.isDesktopPlatform
import dev.lework.protocol.ReasoningEffort
import dev.lework.protocol.SessionMode
import dev.lework.protocol.SlashCommandInfo
import dev.lework.protocol.SlashCommandSource
import dev.lework.store.ConsoleUiState

internal enum class SlashCommandCategory(val label: String) {
    WORKFLOW("协作"),
    ENGINE("命令"),
    LOCAL("快捷"),
    SKILL("技能"),
}

internal data class ResolvedSlashCommand(
    val name: String,
    val description: String,
    val inputHint: String? = null,
    val category: SlashCommandCategory,
    val isLocal: Boolean = false,
    val executeImmediatelyOnSelect: Boolean = false,
)

internal sealed interface LocalSlashAction {
    data object NewSession : LocalSlashAction
    data object OpenModelPicker : LocalSlashAction
    data object OpenDiff : LocalSlashAction
    data object OpenFiles : LocalSlashAction
    data object OpenArtifacts : LocalSlashAction
    data object OpenUsage : LocalSlashAction
    data class OpenSearch(val query: String) : LocalSlashAction
    data class RenameSession(val newTitle: String?) : LocalSlashAction
    data class SwitchMode(val mode: SessionMode?) : LocalSlashAction
    data class SwitchEffort(val effort: ReasoningEffort?) : LocalSlashAction
    data object ShowHelp : LocalSlashAction
}

private val SLASH_COMMAND_TOKEN_RE = Regex("^/([A-Za-z0-9._-]*)$")

private val FALLBACK_AGY_COMMANDS = listOf(
    SlashCommandInfo("plan", "先梳理方案与步骤，确认后再修改代码", "任务目标", SlashCommandSource.BUILTIN),
    SlashCommandInfo("goal", "围绕目标持续推进，直到全部达成", "目标描述", SlashCommandSource.BUILTIN),
    SlashCommandInfo("compact", "压缩并总结当前对话上下文，释放窗口", null, SlashCommandSource.BUILTIN),
    SlashCommandInfo("boost", "深度分析与多角度验证复杂任务", "任务描述", SlashCommandSource.BUILTIN),
    SlashCommandInfo("grill-me", "通过问答对齐关键设计决策", "主题", SlashCommandSource.BUILTIN),
    SlashCommandInfo("swarm", "调度多个智能体并行分工协作", "任务分工", SlashCommandSource.WORKFLOW),
    SlashCommandInfo("teamwork", "启动多智能体团队协同推进大型任务", "项目目标", SlashCommandSource.WORKFLOW),
    SlashCommandInfo("run-workflow", "以脚本编排多步骤智能体流水线", "工作流目标", SlashCommandSource.WORKFLOW),
    SlashCommandInfo("schedule", "设置定时或周期执行的提醒与任务", "时间与内容", SlashCommandSource.BUILTIN),
    SlashCommandInfo("browser", "打开浏览器执行网页检索与页面交互", "网页任务", SlashCommandSource.BUILTIN),
    SlashCommandInfo("learn", "将当前经验沉淀为可复用的持久化规则", "沉淀内容", SlashCommandSource.BUILTIN),
)

private val FALLBACK_CLAUDE_COMMANDS = listOf(
    SlashCommandInfo("compact", "压缩并总结当前对话上下文", null, SlashCommandSource.BUILTIN),
    SlashCommandInfo("plan", "先给出实施计划再动手修改", "需求说明", SlashCommandSource.BUILTIN),
    SlashCommandInfo("review", "对当前改动进行代码审查", "审查范围", SlashCommandSource.BUILTIN),
    SlashCommandInfo("init", "分析仓库结构并初始化项目规则", null, SlashCommandSource.BUILTIN),
)

private val FALLBACK_CODEX_COMMANDS: List<SlashCommandInfo> = emptyList()

/**
 * Extracts the active slash-command filter query when the user is typing a leading `/command` token.
 * Automatically dismisses (`null`) as soon as a space or newline is typed so parameter entry is never obstructed.
 */
internal fun extractSlashCommandQuery(text: String): String? {
    val trimmedStart = text.trimStart()
    if (!trimmedStart.startsWith('/')) return null
    if (trimmedStart.any { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) return null
    val match = SLASH_COMMAND_TOKEN_RE.matchEntire(trimmedStart) ?: return null
    return match.groupValues[1]
}

private fun SlashCommandInfo.toResolved(): ResolvedSlashCommand {
    val cat = when (source) {
        SlashCommandSource.WORKFLOW -> SlashCommandCategory.WORKFLOW
        SlashCommandSource.SKILL -> SlashCommandCategory.SKILL
        SlashCommandSource.LOCAL -> SlashCommandCategory.LOCAL
        SlashCommandSource.BUILTIN, null -> SlashCommandCategory.ENGINE
    }
    return ResolvedSlashCommand(
        name = name.trim().removePrefix("/"),
        description = description.trim(),
        inputHint = inputHint?.trim()?.takeIf { it.isNotEmpty() },
        category = cat,
        isLocal = source == SlashCommandSource.LOCAL,
        executeImmediatelyOnSelect = false,
    )
}

internal fun buildAvailableSlashCommands(
    state: ConsoleUiState,
    includeDesktopOnly: Boolean = isDesktopPlatform(),
): List<ResolvedSlashCommand> {
    val activeSess = state.activeSession?.takeIf { it.id == state.activeSessionId }
    val engineId = activeSess?.engineId ?: state.selectedEngineId
    val remoteCommands = activeSess?.slashCommands?.takeIf { it.isNotEmpty() }
        ?: state.selectedEngine?.slashCommands?.takeIf { it.isNotEmpty() }
        ?: when (engineId) {
            "agy" -> FALLBACK_AGY_COMMANDS
            "claude" -> FALLBACK_CLAUDE_COMMANDS
            "codex" -> FALLBACK_CODEX_COMMANDS
            else -> emptyList()
        }

    val byName = linkedMapOf<String, ResolvedSlashCommand>()
    for (cmd in remoteCommands) {
        val resolved = cmd.toResolved()
        if (resolved.name.isNotEmpty()) {
            byName[resolved.name.lowercase()] = resolved
        }
    }

    val localCommands = buildList {
        add(
            ResolvedSlashCommand(
                name = "new",
                description = "开启空白新会话（当前草稿自动保留）",
                category = SlashCommandCategory.LOCAL,
                isLocal = true,
                executeImmediatelyOnSelect = true,
            ),
        )
        add(
            ResolvedSlashCommand(
                name = "model",
                description = "切换引擎、模型与推理等级",
                category = SlashCommandCategory.LOCAL,
                isLocal = true,
                executeImmediatelyOnSelect = true,
            ),
        )
        val modeNames = state.availableModes.joinToString(" / ") { modeDisplayLabel(it) }
        add(
            ResolvedSlashCommand(
                name = "mode",
                description = "切换执行模式（$modeNames）",
                category = SlashCommandCategory.LOCAL,
                isLocal = true,
                executeImmediatelyOnSelect = true,
            ),
        )
        if (state.availableEfforts.size > 1) {
            val effortNames = state.availableEfforts.joinToString(" / ") { effortDisplayLabel(it) }
            add(
                ResolvedSlashCommand(
                    name = "effort",
                    description = "切换推理深度（$effortNames）",
                    category = SlashCommandCategory.LOCAL,
                    isLocal = true,
                    executeImmediatelyOnSelect = true,
                ),
            )
        }
        add(
            ResolvedSlashCommand(
                name = "diff",
                description = "查看代码变更与检查点快照",
                category = SlashCommandCategory.LOCAL,
                isLocal = true,
                executeImmediatelyOnSelect = true,
            ),
        )
        add(
            ResolvedSlashCommand(
                name = "files",
                description = "浏览当前工作区文件目录",
                category = SlashCommandCategory.LOCAL,
                isLocal = true,
                executeImmediatelyOnSelect = true,
            ),
        )
        if (activeSess != null) {
            add(
                ResolvedSlashCommand(
                    name = "artifacts",
                    description = "查看当前会话的执行方案与产物",
                    category = SlashCommandCategory.LOCAL,
                    isLocal = true,
                    executeImmediatelyOnSelect = true,
                ),
            )
            add(
                ResolvedSlashCommand(
                    name = "rename",
                    description = "重命名当前会话",
                    category = SlashCommandCategory.LOCAL,
                    isLocal = true,
                    executeImmediatelyOnSelect = true,
                ),
            )
        }
        add(
            ResolvedSlashCommand(
                name = "usage",
                description = "查看用量与上下文窗口统计",
                category = SlashCommandCategory.LOCAL,
                isLocal = true,
                executeImmediatelyOnSelect = true,
            ),
        )
        if (includeDesktopOnly) {
            add(
                ResolvedSlashCommand(
                    name = "search",
                    description = "全局搜索历史对话内容",
                    inputHint = "关键词",
                    category = SlashCommandCategory.LOCAL,
                    isLocal = true,
                    executeImmediatelyOnSelect = true,
                ),
            )
        }
        add(
            ResolvedSlashCommand(
                name = "help",
                description = "查看当前引擎可用命令与快捷说明",
                category = SlashCommandCategory.LOCAL,
                isLocal = true,
                executeImmediatelyOnSelect = true,
            ),
        )
    }

    // Local shortcuts that `parseLocalSlashAction` intercepts always keep authoritative local metadata
    // so clicking in the palette and pressing Enter behave identically.
    for (local in localCommands) {
        val key = local.name.lowercase()
        byName[key] = local
    }

    val orderOf = { cat: SlashCommandCategory ->
        when (cat) {
            SlashCommandCategory.ENGINE -> 0
            SlashCommandCategory.WORKFLOW -> 1
            SlashCommandCategory.LOCAL -> 2
            SlashCommandCategory.SKILL -> 3
        }
    }
    return byName.values.sortedWith(compareBy<ResolvedSlashCommand> { orderOf(it.category) })
}

internal fun filterSlashCommands(
    commands: List<ResolvedSlashCommand>,
    query: String,
): List<ResolvedSlashCommand> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return commands
    val prefixMatches = mutableListOf<ResolvedSlashCommand>()
    val containsMatches = mutableListOf<ResolvedSlashCommand>()
    val descMatches = mutableListOf<ResolvedSlashCommand>()
    for (cmd in commands) {
        val nameLower = cmd.name.lowercase()
        when {
            nameLower.startsWith(q) -> prefixMatches.add(cmd)
            nameLower.contains(q) -> containsMatches.add(cmd)
            cmd.description.lowercase().contains(q) -> descMatches.add(cmd)
        }
    }
    return prefixMatches + containsMatches + descMatches
}

internal fun parseLocalSlashAction(
    rawText: String,
    state: ConsoleUiState,
): LocalSlashAction? {
    val trimmed = rawText.trim()
    if (!trimmed.startsWith('/')) return null
    val firstWs = trimmed.indexOfFirst { it.isWhitespace() }
    val name = (if (firstWs < 0) trimmed.substring(1) else trimmed.substring(1, firstWs)).lowercase()
    val arg = if (firstWs < 0) "" else trimmed.substring(firstWs + 1).trim()
    val hasActiveSession = state.activeSession?.takeIf { it.id == state.activeSessionId } != null

    return when (name) {
        "new", "clear" -> LocalSlashAction.NewSession
        "model" -> LocalSlashAction.OpenModelPicker
        "diff" -> LocalSlashAction.OpenDiff
        "files" -> LocalSlashAction.OpenFiles
        "artifacts" -> if (hasActiveSession) LocalSlashAction.OpenArtifacts else null
        "usage" -> LocalSlashAction.OpenUsage
        "search" -> LocalSlashAction.OpenSearch(arg)
        "rename" -> if (hasActiveSession) LocalSlashAction.RenameSession(arg.takeIf { it.isNotEmpty() }) else null
        "mode" -> LocalSlashAction.SwitchMode(parseModeArg(arg))
        "effort" -> LocalSlashAction.SwitchEffort(parseEffortArg(arg))
        "help" -> LocalSlashAction.ShowHelp
        else -> null
    }
}

private fun parseModeArg(raw: String): SessionMode? = when (raw.lowercase()) {
    "auto", "自动", "自动模式" -> SessionMode.AUTO
    "plan", "规划", "仅规划" -> SessionMode.PLAN
    "acceptedits", "accept-edits", "accept_edits", "edit", "自动编辑", "自动改码" -> SessionMode.ACCEPT_EDITS
    "default", "ask", "默认", "手动", "逐步确认" -> SessionMode.DEFAULT
    "bypass", "bypasspermissions", "yolo", "跳过审批", "免审批" -> SessionMode.BYPASS_PERMISSIONS
    else -> null
}

private fun parseEffortArg(raw: String): ReasoningEffort? = when (raw.lowercase()) {
    "low", "低", "轻量" -> ReasoningEffort.LOW
    "medium", "med", "中", "标准" -> ReasoningEffort.MEDIUM
    "high", "高", "深度" -> ReasoningEffort.HIGH
    "xhigh", "极高", "超高" -> ReasoningEffort.XHIGH
    "max", "最高", "极限" -> ReasoningEffort.MAX
    else -> null
}

internal fun buildSlashHelpNotice(state: ConsoleUiState): String {
    val engineName = shortEngineLabel(state.selectedEngineId)
    val commands = buildAvailableSlashCommands(state)
    val engineTop = commands
        .filter { !it.isLocal && it.category != SlashCommandCategory.SKILL }
        .take(5)
        .joinToString(" ") { "/${it.name}" }
    val localSummary = commands
        .filter { it.isLocal && it.name != "help" }
        .joinToString(" ") { "/${it.name}" }
    val skillCount = commands.count { it.category == SlashCommandCategory.SKILL }
    return buildString {
        append("$engineName 常用命令：")
        if (engineTop.isNotEmpty()) append(engineTop) else append("输入 / 查看全部")
        if (skillCount > 0) append("（含 $skillCount 项已安装技能）")
        if (localSummary.isNotEmpty()) {
            append(" · 快捷操作：$localSummary")
        }
    }
}

internal fun executeLocalSlashAction(
    action: LocalSlashAction,
    state: ConsoleUiState,
    onNewSession: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenDiff: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenArtifacts: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenSearch: (String) -> Unit,
    onRenameSession: (String?) -> Unit,
    onSelectMode: (SessionMode) -> Unit,
    onSelectEffort: (ReasoningEffort) -> Unit,
    onShowNotice: (String) -> Unit,
) {
    when (action) {
        LocalSlashAction.NewSession -> onNewSession()
        LocalSlashAction.OpenModelPicker -> onOpenModelPicker()
        LocalSlashAction.OpenDiff -> onOpenDiff()
        LocalSlashAction.OpenFiles -> onOpenFiles()
        LocalSlashAction.OpenArtifacts -> onOpenArtifacts()
        LocalSlashAction.OpenUsage -> onOpenUsage()
        is LocalSlashAction.OpenSearch -> onOpenSearch(action.query)
        is LocalSlashAction.RenameSession -> onRenameSession(action.newTitle)
        is LocalSlashAction.SwitchMode -> {
            val targetMode = action.mode ?: run {
                val modes = state.availableModes
                val idx = modes.indexOf(state.selectedMode)
                modes.getOrElse((idx + 1) % modes.size) { SessionMode.AUTO }
            }
            onSelectMode(targetMode)
            onShowNotice("已切换执行模式为「${modeDisplayLabel(targetMode)}」")
        }
        is LocalSlashAction.SwitchEffort -> {
            val supported = state.availableEfforts
            val targetEffort = action.effort?.takeIf { it in supported } ?: run {
                val idx = supported.indexOf(state.selectedEffort)
                supported.getOrElse((idx + 1) % supported.size) { ReasoningEffort.MEDIUM }
            }
            onSelectEffort(targetEffort)
            onShowNotice("已切换推理深度为「${effortDisplayLabel(targetEffort)}」")
        }
        LocalSlashAction.ShowHelp -> onShowNotice(buildSlashHelpNotice(state))
    }
}

@Composable
internal fun SlashCommandPopup(
    commands: List<ResolvedSlashCommand>,
    selectedIndex: Int,
    isDesktop: Boolean,
    onSelect: (ResolvedSlashCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    if (commands.isEmpty()) return
    val safeSelected = selectedIndex.coerceIn(0, (commands.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState()
    LaunchedEffect(safeSelected, commands.size) {
        if (isDesktop && commands.isNotEmpty()) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == safeSelected }
            if (!isVisible) {
                listState.scrollToItem(safeSelected)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(if (isDesktop) 12.dp else 14.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("composer_slash_popup"),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isDesktop) {
                        "命令与技能 · ${commands.size} 项（↑↓ 选择 · ↩ 确认 · Esc 关闭）"
                    } else {
                        "选择命令或技能（${commands.size} 项）"
                    },
                    fontSize = 11.sp,
                    color = Everforest.CommentGrey,
                )
                Text(
                    text = "关闭",
                    fontSize = 11.sp,
                    color = Everforest.Grey1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .testTag("composer_slash_dismiss_btn"),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 264.dp),
            ) {
                itemsIndexed(commands, key = { _, cmd -> cmd.name }) { idx, cmd ->
                    val highlighted = isDesktop && idx == safeSelected
                    SlashCommandRow(
                        command = cmd,
                        highlighted = highlighted,
                        onClick = { onSelect(cmd) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SlashCommandRow(
    command: ResolvedSlashCommand,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val badgeColor = when (command.category) {
        SlashCommandCategory.WORKFLOW -> Everforest.Aqua
        SlashCommandCategory.ENGINE -> Everforest.Green
        SlashCommandCategory.SKILL -> Everforest.BoldGold
        SlashCommandCategory.LOCAL -> Everforest.Grey2
    }
    val rowBg = when {
        highlighted -> Everforest.Bg2
        hovered -> Everforest.Bg2.copy(alpha = 0.65f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .hoverable(hoverSource)
            .clickable(interactionSource = hoverSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("slash_cmd_${command.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "/${command.name}",
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) Everforest.CodeGreen else Everforest.Fg,
            maxLines = 1,
        )

        if (!command.inputHint.isNullOrBlank()) {
            Text(
                text = command.inputHint,
                fontSize = 11.sp,
                color = Everforest.Grey0,
                maxLines = 1,
            )
        }

        Text(
            text = command.description,
            fontSize = 12.sp,
            color = Everforest.CommentGrey,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Surface(
            shape = RoundedCornerShape(4.dp),
            color = badgeColor.copy(alpha = 0.13f),
        ) {
            Text(
                text = command.category.label,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                color = badgeColor,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
            )
        }
    }
}
