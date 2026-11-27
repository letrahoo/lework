package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.epochMillis
import dev.lework.platform.localDateTimeParts
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

private const val SIDEBAR_MIN_VALID_EPOCH_SEC = 946_684_800L // 2000-01-01T00:00:00Z
private const val SIDEBAR_MIN_VALID_EPOCH_MS = 946_684_800_000L
private const val SPINNER_FRAME_MS = 32L
private const val SPINNER_DEG_PER_FRAME = 13.5f // ~850ms per full revolution
private const val PULSE_STEP_RAD = 0.145f // ~1.4s smooth pulse cycle

@Stable
internal class SidebarAnimationPhase(
    val spinnerAngleDeg: State<Float>,
    val waitingPulseAlpha: State<Float>,
    val nowMs: State<Long>,
)

private val DefaultSidebarAnimationPhase = SidebarAnimationPhase(
    spinnerAngleDeg = mutableFloatStateOf(0f),
    waitingPulseAlpha = mutableFloatStateOf(1f),
    nowMs = mutableLongStateOf(0L),
)

internal val LocalSidebarAnimationPhase = compositionLocalOf { DefaultSidebarAnimationPhase }

/**
 * Provides a single shared coroutine ticker for all spinning loading rings, waiting-approval pulse indicators,
 * and live elapsed-duration counters inside a sidebar.
 *
 * Uses a coroutine `delay()` loop (matching `ExecutionStepsCard` and `SubagentViews`) rather than
 * `rememberInfiniteTransition` so headless Compose UI tests (`waitForIdle()`) never hang on infinite frame clocks,
 * and reads state inside `Canvas` draw scopes to avoid recomposing sibling rows.
 */
@Composable
internal fun ProvideSidebarAnimationPhase(
    hasRunning: Boolean,
    hasWaiting: Boolean,
    content: @Composable () -> Unit,
) {
    val spinnerAngle = remember { mutableFloatStateOf(0f) }
    val pulseAlpha = remember { mutableFloatStateOf(1f) }
    val liveNowMs = remember { mutableLongStateOf(epochMillis()) }

    val animateLoop = hasRunning || hasWaiting
    LaunchedEffect(animateLoop) {
        if (!animateLoop) {
            pulseAlpha.floatValue = 1f
            return@LaunchedEffect
        }
        var pulsePhase = 0f
        while (true) {
            delay(SPINNER_FRAME_MS)
            spinnerAngle.floatValue = (spinnerAngle.floatValue + SPINNER_DEG_PER_FRAME) % 360f
            pulsePhase = (pulsePhase + PULSE_STEP_RAD) % (2f * PI.toFloat())
            pulseAlpha.floatValue = 0.62f + 0.38f * ((sin(pulsePhase) + 1f) * 0.5f)
        }
    }

    LaunchedEffect(hasRunning) {
        liveNowMs.longValue = epochMillis()
        if (!hasRunning) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            liveNowMs.longValue = epochMillis()
        }
    }

    val phase = remember {
        SidebarAnimationPhase(
            spinnerAngleDeg = spinnerAngle,
            waitingPulseAlpha = pulseAlpha,
            nowMs = liveNowMs,
        )
    }
    CompositionLocalProvider(LocalSidebarAnimationPhase provides phase, content = content)
}

/**
 * Smooth rotating arc ring (`Everforest.CodeGreen` by default) with a faint track,
 * crisp even at `10.dp`–`13.dp` sidebar dimensions.
 */
@Composable
internal fun SidebarLoadingArcSpinner(
    size: Dp = 13.dp,
    strokeWidth: Dp = 1.85.dp,
    color: Color = Everforest.CodeGreen,
    trackColor: Color = color.copy(alpha = 0.22f),
    modifier: Modifier = Modifier,
) {
    val angleState = LocalSidebarAnimationPhase.current.spinnerAngleDeg
    Canvas(modifier = modifier.size(size)) {
        val strokePx = strokeWidth.toPx()
        val inset = strokePx * 0.5f
        val diameter = (minOf(this.size.width, this.size.height) - strokePx).coerceAtLeast(1f)
        val topLeft = Offset(inset, inset)
        val arcSize = Size(diameter, diameter)
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = color,
            startAngle = angleState.value - 90f,
            sweepAngle = 118f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
    }
}

/**
 * Pulsing warm-gold approval ring with centered exclamation glyph to distinguish
 * human-blocked `WAITING_PERMISSION` sessions from healthy spinning `RUNNING` tasks.
 */
@Composable
internal fun SidebarWaitingPulseIndicator(
    size: Dp = 13.5.dp,
    modifier: Modifier = Modifier,
) {
    val pulseState = LocalSidebarAnimationPhase.current.waitingPulseAlpha
    Canvas(modifier = modifier.size(size)) {
        val alpha = pulseState.value
        val minDim = minOf(this.size.width, this.size.height)
        val strokePx = 1.6.dp.toPx()
        val radius = (minDim - strokePx) * 0.5f
        val center = Offset(this.size.width * 0.5f, this.size.height * 0.5f)
        drawCircle(
            color = Everforest.BoldGold.copy(alpha = 0.16f * alpha),
            radius = radius,
            center = center,
        )
        drawCircle(
            color = Everforest.BoldGold.copy(alpha = alpha),
            radius = radius,
            center = center,
            style = Stroke(width = strokePx),
        )
        val barWidth = 1.55.dp.toPx()
        drawLine(
            color = Everforest.BoldGold.copy(alpha = alpha),
            start = Offset(center.x, center.y - radius * 0.44f),
            end = Offset(center.x, center.y + radius * 0.08f),
            strokeWidth = barWidth,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = Everforest.BoldGold.copy(alpha = alpha),
            radius = barWidth * 0.55f,
            center = Offset(center.x, center.y + radius * 0.44f),
        )
    }
}

/**
 * Fixed `16.dp` leading indicator slot for both Desktop and Mobile session rows:
 * - `RUNNING`: smooth spinning `CodeGreen` loading arc ring
 * - `WAITING_PERMISSION`: pulsing warm-gold approval badge
 * - `ERROR`: soft-red error outline icon
 * - `IDLE` / `CLOSED`: dimmed low-glare engine vector glyph (eliminating color-dot ambiguity)
 */
@Composable
internal fun SidebarSessionLeadingIndicator(
    session: SessionMeta,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (session.state) {
            SessionState.RUNNING -> {
                SidebarLoadingArcSpinner(
                    size = 13.dp,
                    strokeWidth = 1.85.dp,
                    color = Everforest.CodeGreen,
                    modifier = Modifier.testTag("session_status_loading_${session.id}"),
                )
            }
            SessionState.WAITING_PERMISSION -> {
                SidebarWaitingPulseIndicator(
                    size = 13.5.dp,
                    modifier = Modifier.testTag("session_status_waiting_${session.id}"),
                )
            }
            SessionState.ERROR -> {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = "执行中断",
                    tint = Everforest.SoftRed,
                    modifier = Modifier
                        .size(13.5.dp)
                        .testTag("session_status_error_${session.id}"),
                )
            }
            SessionState.IDLE, SessionState.CLOSED -> {
                EngineGlyphIcon(
                    engineId = session.engineId,
                    size = 13.dp,
                    modifier = Modifier
                        .alpha(if (session.archived) 0.45f else 0.86f)
                        .testTag("session_status_engine_${session.id}"),
                )
            }
        }
    }
}

/**
 * Compact header status chips (`[! N]` and `[↻ N]`) shown on collapsible `已置顶` and Project group headers
 * so active background tasks remain immediately visible even when a group is collapsed.
 */
@Composable
internal fun SidebarHeaderActivityBadges(
    runningCount: Int,
    waitingCount: Int,
    modifier: Modifier = Modifier,
) {
    if (runningCount <= 0 && waitingCount <= 0) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (waitingCount > 0) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Everforest.BgYellow,
                border = BorderStroke(0.8.dp, Everforest.Yellow.copy(alpha = 0.5f)),
            ) {
                Text(
                    text = "! $waitingCount",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.BoldGold,
                    modifier = Modifier.padding(horizontal = 5.5.dp, vertical = 1.dp),
                )
            }
        }
        if (runningCount > 0) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Everforest.Bg1.copy(alpha = 0.92f),
                border = BorderStroke(0.8.dp, Everforest.CodeGreen.copy(alpha = 0.34f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 5.5.dp, vertical = 1.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.5.dp),
                ) {
                    SidebarLoadingArcSpinner(
                        size = 9.5.dp,
                        strokeWidth = 1.5.dp,
                        color = Everforest.CodeGreen,
                    )
                    Text(
                        text = "$runningCount",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CodeGreen,
                    )
                }
            }
        }
    }
}

/**
 * One-click `↻ N 进行中` quick-filter pill rendered in the sidebar view control bar.
 */
@Composable
internal fun SidebarRunningFilterPill(
    runningCount: Int,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (active) Everforest.CodeGreen else Everforest.BgGreen.copy(alpha = 0.65f)
    val contentColor = if (active) Everforest.BgDim else Everforest.CodeGreen
    val borderColor = if (active) Everforest.CodeGreen else Everforest.CodeGreen.copy(alpha = 0.38f)

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .testTag("drawer_btn_filter_running"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.5.dp),
        ) {
            if (runningCount > 0) {
                SidebarLoadingArcSpinner(
                    size = 9.5.dp,
                    strokeWidth = 1.55.dp,
                    color = contentColor,
                    trackColor = contentColor.copy(alpha = 0.26f),
                )
            }
            Text(
                text = if (runningCount > 0) "$runningCount 进行中" else "仅看进行中",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
    }
}

private fun normalizeEpochMillisOrNull(raw: Long?): Long? {
    if (raw == null || raw <= 0L) return null
    return when {
        raw in SIDEBAR_MIN_VALID_EPOCH_SEC..9_999_999_999L -> raw * 1000L
        raw >= SIDEBAR_MIN_VALID_EPOCH_MS -> raw
        else -> null
    }
}

private fun padTwoDigits(v: Int): String = if (v < 10) "0$v" else v.toString()

internal fun formatSidebarElapsedDuration(
    startEpochMs: Long?,
    nowMs: Long = epochMillis(),
): String? {
    val startMs = normalizeEpochMillisOrNull(startEpochMs) ?: return null
    val effectiveNow = normalizeEpochMillisOrNull(nowMs) ?: epochMillis()
    val sec = ((effectiveNow - startMs) / 1000L).coerceAtLeast(1L)
    return when {
        sec < 60L -> "${sec}s"
        sec < 3600L -> "${sec / 60L}m ${padTwoDigits((sec % 60L).toInt())}s"
        else -> "${sec / 3600L}h ${padTwoDigits(((sec % 3600L) / 60L).toInt())}m"
    }
}

internal fun formatSidebarRelativeTime(
    epochMs: Long,
    nowMs: Long = epochMillis(),
): String {
    val normMs = normalizeEpochMillisOrNull(epochMs) ?: return ""
    val effectiveNow = normalizeEpochMillisOrNull(nowMs) ?: epochMillis()
    val diffSec = ((effectiveNow - normMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "刚刚"
        diffSec < 3600L -> "${diffSec / 60L}分钟前"
        diffSec < 86_400L -> "${diffSec / 3600L}小时前"
        diffSec < 172_800L -> "昨天"
        diffSec < 2_592_000L -> "${diffSec / 86_400L}天前"
        else -> {
            val msg = localDateTimeParts(normMs)
            val now = localDateTimeParts(effectiveNow)
            if (msg.year == now.year) {
                "${msg.month}月${msg.day}日"
            } else {
                "${msg.year}-${padTwoDigits(msg.month)}-${padTwoDigits(msg.day)}"
            }
        }
    }
}

internal fun buildSidebarSessionSubtitle(
    session: SessionMeta,
    selected: Boolean,
    groupedProjectDisplayName: String?,
    draftPreview: String?,
    nowMs: Long,
): AnnotatedString {
    val wsList = session.workspaces?.filter { it.isNotBlank() }?.distinct().orEmpty()
    val primaryPath = session.worktree?.baseRepoPath ?: wsList.firstOrNull() ?: session.cwd
    val wsShort = primaryPath.trimEnd('/').substringAfterLast('/').ifBlank { "~" }
    val multiSuffix = if (wsList.size > 1 && session.worktree == null) " +${wsList.size - 1}" else ""
    val shortBranch = session.worktree?.branch?.removePrefix("lework/")
    val branchLabel = session.worktree?.let { wt ->
        if (wt.cleanedUp == true) "⎇ $shortBranch (已清理目录)" else "⎇ $shortBranch"
    }
    val inProjectGroup = groupedProjectDisplayName != null
    val isWaiting = session.state == SessionState.WAITING_PERMISSION
    val isRunning = session.state == SessionState.RUNNING
    val liveText = session.liveStatusText?.trim().orEmpty()
    val visibleDraft = draftPreview?.takeUnless { selected }

    return buildAnnotatedString {
        when {
            isWaiting -> {
                withStyle(SpanStyle(color = Everforest.BoldGold)) {
                    append(liveText.ifEmpty { "等待权限确认…" })
                }
                if (!inProjectGroup) {
                    withStyle(SpanStyle(color = Everforest.Grey0)) {
                        append(" · $wsShort$multiSuffix")
                    }
                }
            }
            isRunning && liveText.isNotEmpty() -> {
                withStyle(SpanStyle(color = Everforest.SoftSand)) {
                    append(liveText)
                }
            }
            isRunning -> {
                withStyle(SpanStyle(color = Everforest.CodeGreen, fontWeight = FontWeight.Medium)) {
                    append("运行中")
                }
                val elapsed = formatSidebarElapsedDuration(session.turnStartedAt ?: session.updatedAt, nowMs)
                if (elapsed != null) {
                    withStyle(SpanStyle(color = Everforest.Grey0)) { append(" · ") }
                    withStyle(SpanStyle(color = Everforest.Grey2, fontFamily = FontFamily.Monospace)) {
                        append(elapsed)
                    }
                }
                if (!inProjectGroup) {
                    withStyle(SpanStyle(color = Everforest.Grey0)) {
                        append(" · $wsShort$multiSuffix")
                    }
                } else if (branchLabel != null) {
                    withStyle(SpanStyle(color = Everforest.Grey0)) { append(" · ") }
                    withStyle(SpanStyle(color = Everforest.InlineCode)) { append(branchLabel) }
                }
            }
            visibleDraft != null -> {
                withStyle(SpanStyle(color = Everforest.SoftRed, fontWeight = FontWeight.SemiBold)) {
                    append("[草稿] ")
                }
                withStyle(SpanStyle(color = Everforest.Grey2)) {
                    append(visibleDraft)
                }
            }
            session.state == SessionState.ERROR -> {
                withStyle(SpanStyle(color = Everforest.SoftRed)) {
                    append("执行中断")
                }
                val rel = formatSidebarRelativeTime(session.updatedAt, nowMs)
                val ctx = if (!inProjectGroup) "$wsShort$multiSuffix" else rel
                if (ctx.isNotEmpty()) {
                    withStyle(SpanStyle(color = Everforest.Grey0)) {
                        append(" · $ctx")
                    }
                }
            }
            inProjectGroup -> {
                var hasSegment = false
                if (branchLabel != null) {
                    withStyle(SpanStyle(color = Everforest.InlineCode)) { append(branchLabel) }
                    hasSegment = true
                }
                if (wsList.size > 1 && session.worktree == null) {
                    if (hasSegment) withStyle(SpanStyle(color = Everforest.Grey0)) { append(" · ") }
                    withStyle(SpanStyle(color = Everforest.Grey0)) { append("+${wsList.size - 1} 目录") }
                    hasSegment = true
                }
                val rel = formatSidebarRelativeTime(session.updatedAt, nowMs)
                if (rel.isNotEmpty()) {
                    if (hasSegment) withStyle(SpanStyle(color = Everforest.Grey0)) { append(" · ") }
                    withStyle(SpanStyle(color = Everforest.Grey0)) { append(rel) }
                    hasSegment = true
                }
                if (!hasSegment) {
                    withStyle(SpanStyle(color = Everforest.Grey0)) {
                        append(shortEngineLabel(session.engineId))
                    }
                }
            }
            else -> {
                withStyle(SpanStyle(color = Everforest.Grey0)) {
                    append("$wsShort$multiSuffix")
                }
                if (branchLabel != null) {
                    withStyle(SpanStyle(color = Everforest.Grey0)) { append(" · ") }
                    withStyle(SpanStyle(color = Everforest.InlineCode)) { append(branchLabel) }
                }
                val rel = formatSidebarRelativeTime(session.updatedAt, nowMs)
                if (rel.isNotEmpty()) {
                    withStyle(SpanStyle(color = Everforest.Grey0)) {
                        append(" · $rel")
                    }
                }
            }
        }
    }
}
