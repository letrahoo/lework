package dev.lework.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.epochMillis
import dev.lework.protocol.PlanEntry
import dev.lework.protocol.SessionMeta
import kotlinx.coroutines.delay

private val AGY_BRAILLE_SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
private val LEADING_SPINNER_RE = Regex("^[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏⣷⣯⣟⡿⢿⣻⣽⣾·✻✽✢✳✶]\\s*")
private val STEP_COUNTER_PREFIX_RE = Regex("^\\d+/\\d+\\s*(?:steps?|步)\\s*[·•-]?\\s*", RegexOption.IGNORE_CASE)
private val TRAILING_ELAPSED_RE = Regex("\\s+(?:\\d+m\\s+)?\\d+(?:\\.\\d+)?s$")

internal fun cleanLiveStatusForSteps(rawLive: String?): String {
    val normalized = rawLive?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (normalized.isEmpty()) return ""
    return normalized
        .replace(LEADING_SPINNER_RE, "")
        .replace(STEP_COUNTER_PREFIX_RE, "")
        .replace(TRAILING_ELAPSED_RE, "")
        .trim()
}

internal fun formatStepElapsedSeconds(sec: Long): String {
    if (sec < 60L) return "${sec}s"
    val mins = sec / 60L
    val rem = sec % 60L
    return "${mins}m ${rem}s"
}

private fun formatStepTokens(tokens: Int): String {
    if (tokens < 1000) return tokens.toString()
    val tenths = (tokens * 10) / 1000
    val whole = tenths / 10
    val frac = tenths % 10
    return "$whole.${frac}k"
}

/**
 * AGY-terminal-inspired live Execution Steps tree anchored at the bottom of the conversation stream.
 * - While running: displays animated braille spinner + `done/total 步 · activity · elapsed`, followed
 *   by tree-indented step rows (`└ ● active / ✓ done / ○ pending / ⊘ skipped / ✕ failed`).
 * - Once all steps finish cleanly: auto-collapses to a quiet 1-line summary pill unless plan approval
 *   actions are visible or the user toggles expansion.
 */
@Composable
internal fun ExecutionStepsCard(
    sessionKey: String,
    activeSession: SessionMeta?,
    entries: List<PlanEntry>,
    isSessionRunning: Boolean,
    outputTokens: Int = 0,
    onOpenPlanArtifact: (() -> Unit)? = null,
    onApprovePlan: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return

    val total = entries.size
    val doneCount = entries.count { it.status == "completed" || it.status == "skipped" }
    val failedCount = entries.count { it.status == "failed" }
    val inProgressEntry = entries.firstOrNull { it.status == "in_progress" }
    val allFinished = entries.all { it.status == "completed" || it.status == "skipped" }
    val hasActionButtons = onOpenPlanArtifact != null || onApprovePlan != null
    val defaultExpanded = isSessionRunning || !allFinished || hasActionButtons

    var userExpandedOverride by remember(sessionKey) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(sessionKey, isSessionRunning, allFinished) {
        userExpandedOverride = null
    }
    val expanded = userExpandedOverride ?: defaultExpanded

    var tick by remember(sessionKey) { mutableIntStateOf(0) }
    var nowMs by remember(sessionKey) { mutableLongStateOf(epochMillis()) }
    LaunchedEffect(sessionKey, isSessionRunning) {
        if (!isSessionRunning) return@LaunchedEffect
        while (true) {
            delay(180L)
            tick++
            nowMs = epochMillis()
        }
    }

    val pulseBright = (tick / 3) % 2 == 0
    val borderColor = when {
        isSessionRunning -> Everforest.BoldGold.copy(alpha = 0.42f)
        failedCount > 0 -> Everforest.Red.copy(alpha = 0.42f)
        allFinished -> Everforest.Bg3
        else -> Everforest.Aqua.copy(alpha = 0.36f)
    }

    val activityLabel = remember(activeSession?.liveStatusText, inProgressEntry?.content) {
        val cleanedLive = cleanLiveStatusForSteps(activeSession?.liveStatusText)
        when {
            cleanedLive.isNotEmpty() -> cleanedLive
            !inProgressEntry?.content.isNullOrBlank() -> inProgressEntry?.content.orEmpty()
            else -> "执行中…"
        }
    }
    val startMs = activeSession?.turnStartedAt ?: activeSession?.updatedAt ?: nowMs
    val elapsedSec = ((nowMs - startMs) / 1000L).coerceAtLeast(0L)
    val progressFraction = (doneCount.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .testTag("execution_steps_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Header row: Spinner/Icon + progress counter + live activity + elapsed + actions + toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { userExpandedOverride = !expanded }
                    .padding(vertical = 1.dp)
                    .testTag("execution_steps_header"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isSessionRunning) {
                    val glyph = AGY_BRAILLE_SPINNER_FRAMES[tick % AGY_BRAILLE_SPINNER_FRAMES.size]
                    Text(
                        text = glyph,
                        fontSize = 13.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Everforest.BoldGold,
                    )
                } else {
                    val (headerIcon, headerTint) = when {
                        failedCount > 0 -> Icons.Rounded.ErrorOutline to Everforest.SoftRed
                        allFinished -> Icons.Rounded.CheckCircle to Everforest.Green
                        else -> Icons.Rounded.Checklist to Everforest.Aqua
                    }
                    Icon(
                        imageVector = headerIcon,
                        contentDescription = null,
                        tint = headerTint,
                        modifier = Modifier.size(15.dp),
                    )
                }

                Text(
                    text = buildAnnotatedString {
                        val countColor = when {
                            isSessionRunning -> Everforest.BoldGold
                            failedCount > 0 -> Everforest.SoftRed
                            allFinished -> Everforest.Green
                            else -> Everforest.Aqua
                        }
                        withStyle(
                            SpanStyle(
                                color = countColor,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            ),
                        ) {
                            append("$doneCount/$total 步")
                        }
                        if (isSessionRunning) {
                            withStyle(SpanStyle(color = Everforest.Bg4)) { append(" · ") }
                            withStyle(SpanStyle(color = Everforest.SoftSand, fontWeight = FontWeight.Medium)) {
                                append(activityLabel)
                            }
                            withStyle(SpanStyle(color = Everforest.CommentGrey, fontFamily = FontFamily.Monospace)) {
                                append(" ${formatStepElapsedSeconds(elapsedSec)}")
                                if (outputTokens > 0) {
                                    append(" · ↓ ${formatStepTokens(outputTokens)}")
                                }
                            }
                        } else if (failedCount > 0) {
                            withStyle(SpanStyle(color = Everforest.SoftRed)) {
                                append(" · 含 $failedCount 项失败")
                            }
                        } else if (allFinished) {
                            withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                                append(" · 执行步骤已完成")
                            }
                        } else {
                            withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                                append(" · 执行步骤")
                            }
                        }
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (onOpenPlanArtifact != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Everforest.Bg2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenPlanArtifact() },
                    ) {
                        Text(
                            text = "查看方案",
                            fontSize = 10.5.sp,
                            color = Everforest.BoldGold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                        )
                    }
                }

                if (onApprovePlan != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Everforest.BgGreen,
                        border = BorderStroke(0.8.dp, Everforest.Green.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onApprovePlan() }
                            .testTag("plan_card_approve_btn"),
                    ) {
                        Text(
                            text = "确认执行",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Everforest.Green,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                        )
                    }
                }

                Icon(
                    imageVector = if (expanded) {
                        Icons.Rounded.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight
                    },
                    contentDescription = if (expanded) "收起执行步骤" else "展开执行步骤",
                    tint = Everforest.CommentGrey,
                    modifier = Modifier.size(16.dp),
                )
            }

            // Expanded tree checklist (matches AGY terminal `└ ● / ○ / ✓` hierarchy)
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    // Subtle Everforest progress hairline
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Everforest.Bg2),
                    ) {
                        if (progressFraction > 0f || isSessionRunning) {
                            val visualFraction = if (progressFraction > 0f) {
                                progressFraction
                            } else {
                                (1f / (total * 2f)).coerceIn(0.08f, 0.25f)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(visualFraction)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        when {
                                            failedCount > 0 -> Everforest.SoftRed
                                            allFinished -> Everforest.Green
                                            else -> Everforest.BoldGold
                                        },
                                    ),
                            )
                        }
                    }

                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 1.dp),
                        ) {
                            entries.forEachIndexed { index, entry ->
                                if (index > 0) {
                                    MarkdownSelectionSeparator("\n", Modifier.height(5.dp).width(1.dp))
                                }
                                ExecutionStepRow(
                                    index = index,
                                    entry = entry,
                                    isSessionRunning = isSessionRunning,
                                    pulseBright = pulseBright,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionStepRow(
    index: Int,
    entry: PlanEntry,
    isSessionRunning: Boolean,
    pulseBright: Boolean,
) {
    val isInProgress = entry.status == "in_progress"
    val isCompleted = entry.status == "completed"
    val isSkipped = entry.status == "skipped"
    val isFailed = entry.status == "failed"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("execution_step_row_$index"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Tree elbow connector on first step, aligned indent on following steps
        Box(
            modifier = Modifier.width(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (index == 0) {
                Text(
                    text = "└",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.Bg4,
                )
            }
        }

        // Semantic status dot / icon
        Box(
            modifier = Modifier.size(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isCompleted -> {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "已完成",
                        tint = Everforest.Green,
                        modifier = Modifier.size(13.dp),
                    )
                }
                isInProgress -> {
                    val dotAlpha = if (isSessionRunning && !pulseBright) 0.45f else 1f
                    Box(
                        modifier = Modifier
                            .size(7.5.dp)
                            .alpha(dotAlpha)
                            .clip(CircleShape)
                            .background(Everforest.BoldGold),
                    )
                }
                isFailed -> {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "失败",
                        tint = Everforest.SoftRed,
                        modifier = Modifier.size(13.dp),
                    )
                }
                isSkipped -> {
                    Text(
                        text = "–",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CommentGrey,
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .border(1.2.dp, Everforest.Grey0, CircleShape),
                    )
                }
            }
        }

        Text(
            text = entry.content,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            fontWeight = if (isInProgress) FontWeight.Medium else FontWeight.Normal,
            textDecoration = if (isSkipped) TextDecoration.LineThrough else TextDecoration.None,
            color = when {
                isInProgress -> Everforest.Fg
                isFailed -> Everforest.SoftRed
                isSkipped -> Everforest.CommentGrey
                isCompleted -> Everforest.Grey2
                else -> Everforest.Grey2
            },
            modifier = Modifier.weight(1f),
        )
    }
}
