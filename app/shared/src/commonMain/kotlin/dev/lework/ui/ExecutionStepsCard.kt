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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
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
import dev.lework.protocol.SessionState
import kotlinx.coroutines.delay

private val AGY_BRAILLE_SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
private val LEADING_SPINNER_RE = Regex("^[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏⣷⣯⣟⡿⢿⣻⣽⣾·✻✽✢✳✶]\\s*")
private val STEP_COUNTER_PREFIX_RE = Regex("^\\d+/\\d+\\s*(?:steps?|步)\\s*[·•-]?\\s*", RegexOption.IGNORE_CASE)
private val TRAILING_ELAPSED_RE = Regex("\\s+(?:\\d+m\\s+)?\\d+(?:\\.\\d+)?s$")

internal enum class ExecutionStepsLifecycle {
    /** Session is actively executing right now (live braille spinner, gold pulse, elapsed timer). */
    RUNNING,

    /** Session turn is paused awaiting user permission approval (active, NOT stopped/failed). */
    WAITING_PERMISSION,

    /** Inactive session with an actionable plan waiting for user review/approval. */
    PLAN_APPROVAL_PENDING,

    /** All steps finished cleanly (`completed` / `skipped`) and session is no longer active. */
    COMPLETED,

    /** One or more steps explicitly failed, or session ended in `ERROR` before completing all steps. */
    FAILED,

    /** Inactive (`IDLE` / `CLOSED`) session whose `in_progress` step was cut off mid-flight. */
    STOPPED,

    /** Inactive (`IDLE` / `CLOSED`) session with remaining `pending` steps and no active/failed rows. */
    INCOMPLETE,
}

internal fun resolveExecutionStepsLifecycle(
    entries: List<PlanEntry>,
    sessionState: SessionState?,
    isSessionRunning: Boolean,
    isWaitingPermission: Boolean,
    hasActionButtons: Boolean,
): ExecutionStepsLifecycle {
    val effectiveWaitingPermission = isWaitingPermission || sessionState == SessionState.WAITING_PERMISSION
    if (effectiveWaitingPermission) return ExecutionStepsLifecycle.WAITING_PERMISSION
    if (isSessionRunning) return ExecutionStepsLifecycle.RUNNING

    val failedCount = entries.count { it.status == "failed" }
    val allFinished = entries.isNotEmpty() && entries.all { it.status == "completed" || it.status == "skipped" }
    val hasInProgress = entries.any { it.status == "in_progress" }

    return when {
        failedCount > 0 || (sessionState == SessionState.ERROR && !allFinished) ->
            ExecutionStepsLifecycle.FAILED
        hasInProgress ->
            ExecutionStepsLifecycle.STOPPED
        allFinished ->
            ExecutionStepsLifecycle.COMPLETED
        hasActionButtons ->
            ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING
        else ->
            ExecutionStepsLifecycle.INCOMPLETE
    }
}

internal fun defaultExpandedForLifecycle(
    lifecycle: ExecutionStepsLifecycle,
    hasApprovePlanAction: Boolean,
): Boolean = when (lifecycle) {
    ExecutionStepsLifecycle.RUNNING,
    ExecutionStepsLifecycle.WAITING_PERMISSION,
    ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING -> true
    ExecutionStepsLifecycle.COMPLETED -> hasApprovePlanAction
    ExecutionStepsLifecycle.FAILED,
    ExecutionStepsLifecycle.STOPPED,
    ExecutionStepsLifecycle.INCOMPLETE -> false
}

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
 * - While running (`RUNNING`): displays animated braille spinner + `done/total 步 · activity · elapsed`,
 *   expanded by default with pulsing gold active dot.
 * - While awaiting permission (`WAITING_PERMISSION`): displays calm orange pause cue (`等待权限确认`),
 *   expanded by default so the user sees which step triggered the approval banner.
 * - While awaiting plan approval (`PLAN_APPROVAL_PENDING`): expanded by default alongside action buttons.
 * - Once inactive (`COMPLETED` / `FAILED` / `STOPPED` / `INCOMPLETE`): auto-collapses to a quiet 1-line
 *   semantic summary pill with non-active styling for unfinished rows, expandable on tap.
 */
@Composable
internal fun ExecutionStepsCard(
    sessionKey: String,
    activeSession: SessionMeta?,
    entries: List<PlanEntry>,
    isSessionRunning: Boolean,
    isWaitingPermission: Boolean = false,
    outputTokens: Int = 0,
    onOpenPlanArtifact: (() -> Unit)? = null,
    onApprovePlan: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return

    val matchingSession = activeSession?.takeIf { it.id == sessionKey }
    val total = entries.size
    val doneCount = entries.count { it.status == "completed" || it.status == "skipped" }
    val failedCount = entries.count { it.status == "failed" }
    val inProgressEntry = entries.firstOrNull { it.status == "in_progress" }
    val allFinished = entries.all { it.status == "completed" || it.status == "skipped" }
    val hasActionButtons = onOpenPlanArtifact != null || onApprovePlan != null

    val lifecycle = resolveExecutionStepsLifecycle(
        entries = entries,
        sessionState = matchingSession?.state,
        isSessionRunning = isSessionRunning,
        isWaitingPermission = isWaitingPermission,
        hasActionButtons = hasActionButtons,
    )
    val isLiveRunning = lifecycle == ExecutionStepsLifecycle.RUNNING
    val defaultExpanded = defaultExpandedForLifecycle(
        lifecycle = lifecycle,
        hasApprovePlanAction = onApprovePlan != null,
    )

    // Keyed directly on (sessionKey, lifecycle, defaultExpanded) so user manual toggles survive
    // live ticks/progress updates while genuine lifecycle transitions reset immediately with zero flicker.
    var userExpandedOverride by remember(sessionKey, lifecycle, defaultExpanded) {
        mutableStateOf<Boolean?>(null)
    }
    val expanded = userExpandedOverride ?: defaultExpanded

    var tick by remember(sessionKey) { mutableIntStateOf(0) }
    var nowMs by remember(sessionKey) { mutableLongStateOf(epochMillis()) }
    LaunchedEffect(sessionKey, isLiveRunning) {
        if (!isLiveRunning) return@LaunchedEffect
        nowMs = epochMillis()
        while (true) {
            delay(180L)
            tick++
            nowMs = epochMillis()
        }
    }

    val pulseBright = isLiveRunning && (tick / 3) % 2 == 0
    val borderColor = when (lifecycle) {
        ExecutionStepsLifecycle.RUNNING -> Everforest.BoldGold.copy(alpha = 0.42f)
        ExecutionStepsLifecycle.WAITING_PERMISSION -> Everforest.Orange.copy(alpha = 0.42f)
        ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING -> Everforest.Aqua.copy(alpha = 0.36f)
        ExecutionStepsLifecycle.FAILED -> Everforest.Red.copy(alpha = 0.42f)
        ExecutionStepsLifecycle.COMPLETED,
        ExecutionStepsLifecycle.STOPPED,
        ExecutionStepsLifecycle.INCOMPLETE -> Everforest.Bg3
    }

    val activityLabel = remember(matchingSession?.liveStatusText, inProgressEntry?.content) {
        val cleanedLive = cleanLiveStatusForSteps(matchingSession?.liveStatusText)
        when {
            cleanedLive.isNotEmpty() -> cleanedLive
            !inProgressEntry?.content.isNullOrBlank() -> inProgressEntry?.content.orEmpty()
            else -> "执行中…"
        }
    }
    val startMs = matchingSession?.turnStartedAt ?: matchingSession?.updatedAt ?: nowMs
    val elapsedSec = ((nowMs - startMs) / 1000L).coerceAtLeast(0L)
    val progressFraction = (doneCount.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    val isActiveTurn = lifecycle == ExecutionStepsLifecycle.RUNNING ||
        lifecycle == ExecutionStepsLifecycle.WAITING_PERMISSION

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
            // Header row: Spinner/Icon + progress counter + semantic lifecycle label + actions + toggle
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
                if (isLiveRunning) {
                    val glyph = AGY_BRAILLE_SPINNER_FRAMES[tick % AGY_BRAILLE_SPINNER_FRAMES.size]
                    Text(
                        text = glyph,
                        fontSize = 13.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Everforest.BoldGold,
                    )
                } else {
                    val (headerIcon, headerTint) = when (lifecycle) {
                        ExecutionStepsLifecycle.WAITING_PERMISSION ->
                            Icons.Rounded.PauseCircleOutline to Everforest.Orange
                        ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING ->
                            Icons.Rounded.Checklist to Everforest.Aqua
                        ExecutionStepsLifecycle.COMPLETED ->
                            Icons.Rounded.CheckCircle to Everforest.Green
                        ExecutionStepsLifecycle.FAILED ->
                            Icons.Rounded.ErrorOutline to Everforest.SoftRed
                        ExecutionStepsLifecycle.STOPPED ->
                            Icons.Rounded.StopCircle to Everforest.CommentGrey
                        ExecutionStepsLifecycle.INCOMPLETE ->
                            Icons.Rounded.Checklist to Everforest.CommentGrey
                        ExecutionStepsLifecycle.RUNNING ->
                            Icons.Rounded.Checklist to Everforest.BoldGold
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
                        val countColor = when (lifecycle) {
                            ExecutionStepsLifecycle.RUNNING -> Everforest.BoldGold
                            ExecutionStepsLifecycle.WAITING_PERMISSION -> Everforest.Orange
                            ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING -> Everforest.Aqua
                            ExecutionStepsLifecycle.COMPLETED -> Everforest.Green
                            ExecutionStepsLifecycle.FAILED -> Everforest.SoftRed
                            ExecutionStepsLifecycle.STOPPED,
                            ExecutionStepsLifecycle.INCOMPLETE -> Everforest.Grey2
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
                        when (lifecycle) {
                            ExecutionStepsLifecycle.RUNNING -> {
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
                            }
                            ExecutionStepsLifecycle.WAITING_PERMISSION -> {
                                withStyle(SpanStyle(color = Everforest.Orange, fontWeight = FontWeight.Medium)) {
                                    append(" · 等待权限确认")
                                }
                            }
                            ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING -> {
                                withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                                    append(if (onApprovePlan != null) " · 待确认方案" else " · 执行方案")
                                }
                            }
                            ExecutionStepsLifecycle.COMPLETED -> {
                                withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                                    append(" · 执行步骤已完成")
                                }
                            }
                            ExecutionStepsLifecycle.FAILED -> {
                                withStyle(SpanStyle(color = Everforest.SoftRed)) {
                                    if (failedCount > 0) {
                                        append(" · 含 $failedCount 项失败")
                                    } else {
                                        append(" · 执行异常中断")
                                    }
                                }
                            }
                            ExecutionStepsLifecycle.STOPPED -> {
                                withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                                    append(" · 已中断")
                                }
                            }
                            ExecutionStepsLifecycle.INCOMPLETE -> {
                                withStyle(SpanStyle(color = Everforest.CommentGrey)) {
                                    append(" · 未完成")
                                }
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
                    // Subtle Everforest progress hairline (only fakes minimum bar while actively running/waiting)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Everforest.Bg2),
                    ) {
                        if (progressFraction > 0f || isActiveTurn) {
                            val visualFraction = if (progressFraction > 0f) {
                                progressFraction
                            } else {
                                (1f / (total * 2f)).coerceIn(0.08f, 0.25f)
                            }
                            val progressColor = when (lifecycle) {
                                ExecutionStepsLifecycle.RUNNING -> when {
                                    failedCount > 0 -> Everforest.SoftRed
                                    allFinished -> Everforest.Green
                                    else -> Everforest.BoldGold
                                }
                                ExecutionStepsLifecycle.WAITING_PERMISSION -> Everforest.Orange
                                ExecutionStepsLifecycle.PLAN_APPROVAL_PENDING -> Everforest.Aqua
                                ExecutionStepsLifecycle.COMPLETED -> Everforest.Green
                                ExecutionStepsLifecycle.FAILED -> Everforest.SoftRed
                                ExecutionStepsLifecycle.STOPPED,
                                ExecutionStepsLifecycle.INCOMPLETE -> Everforest.Grey0
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(visualFraction)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(progressColor),
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
                                    lifecycle = lifecycle,
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
    lifecycle: ExecutionStepsLifecycle,
    pulseBright: Boolean,
) {
    val isInProgress = entry.status == "in_progress"
    val isCompleted = entry.status == "completed"
    val isSkipped = entry.status == "skipped"
    val isFailed = entry.status == "failed"

    val isLiveRunningStep = isInProgress && lifecycle == ExecutionStepsLifecycle.RUNNING
    val isWaitingPermissionStep = isInProgress && lifecycle == ExecutionStepsLifecycle.WAITING_PERMISSION
    val isStoppedInProgressStep = isInProgress && !isLiveRunningStep && !isWaitingPermissionStep
    val isInactiveLifecycle = lifecycle == ExecutionStepsLifecycle.STOPPED ||
        lifecycle == ExecutionStepsLifecycle.FAILED ||
        lifecycle == ExecutionStepsLifecycle.INCOMPLETE

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
                        modifier = Modifier
                            .size(13.dp)
                            .testTag("execution_step_status_completed_$index"),
                    )
                }
                isLiveRunningStep -> {
                    val dotAlpha = if (!pulseBright) 0.45f else 1f
                    Box(
                        modifier = Modifier
                            .size(7.5.dp)
                            .alpha(dotAlpha)
                            .clip(CircleShape)
                            .background(Everforest.BoldGold)
                            .testTag("execution_step_status_running_$index"),
                    )
                }
                isWaitingPermissionStep -> {
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = "等待权限确认",
                        tint = Everforest.Orange,
                        modifier = Modifier
                            .size(13.dp)
                            .testTag("execution_step_status_waiting_$index"),
                    )
                }
                isStoppedInProgressStep -> {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = "已中断",
                        tint = Everforest.CommentGrey,
                        modifier = Modifier
                            .size(12.dp)
                            .testTag("execution_step_status_stopped_$index"),
                    )
                }
                isFailed -> {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "失败",
                        tint = Everforest.SoftRed,
                        modifier = Modifier
                            .size(13.dp)
                            .testTag("execution_step_status_failed_$index"),
                    )
                }
                isSkipped -> {
                    Text(
                        text = "–",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CommentGrey,
                        modifier = Modifier.testTag("execution_step_status_skipped_$index"),
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .border(1.2.dp, Everforest.Grey0, CircleShape)
                            .testTag("execution_step_status_pending_$index"),
                    )
                }
            }
        }

        Text(
            text = entry.content,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            fontWeight = if (isLiveRunningStep || isWaitingPermissionStep) FontWeight.Medium else FontWeight.Normal,
            textDecoration = if (isSkipped) TextDecoration.LineThrough else TextDecoration.None,
            color = when {
                isLiveRunningStep || isWaitingPermissionStep -> Everforest.Fg
                isStoppedInProgressStep -> Everforest.CommentGrey
                isFailed -> Everforest.SoftRed
                isSkipped -> Everforest.CommentGrey
                isCompleted -> Everforest.Grey2
                isInactiveLifecycle -> Everforest.CommentGrey
                else -> Everforest.Grey2
            },
            modifier = Modifier.weight(1f),
        )
    }
}
