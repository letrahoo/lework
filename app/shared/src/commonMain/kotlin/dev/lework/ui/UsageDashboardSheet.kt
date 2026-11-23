package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import dev.lework.platform.epochMillis
import dev.lework.protocol.*
import dev.lework.store.ConsoleUiState
import kotlin.math.roundToInt

/**
 * Sleek Claude-CLI-inspired status strip shown above the composer when viewing an active session.
 * Surfaces context window occupancy %, cache hit %, and cumulative session cost at a glance.
 */
@Composable
internal fun SessionContextStatusStrip(
    sessionUsage: SessionUsageResult?,
    fallbackInputTokens: Int,
    fallbackOutputTokens: Int,
    onClick: () -> Unit,
) {
    val hasRichUsage = sessionUsage != null && (sessionUsage.supported || sessionUsage.totals.requests > 0)
    val fallbackTotal = fallbackInputTokens.toLong() + fallbackOutputTokens.toLong()
    if (!hasRichUsage && fallbackTotal <= 0L) return

    val pct = sessionUsage?.contextPercent?.coerceIn(0.0, 100.0) ?: 0.0
    val barColor = when {
        pct >= 88.0 -> Everforest.Red
        pct >= 70.0 -> Everforest.BoldGold
        else -> Everforest.Green
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .testTag("session_context_strip"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (sessionUsage != null && sessionUsage.supported && sessionUsage.contextWindow > 0) {
                    // Mini context window progress bar
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Everforest.Bg3),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((pct.toFloat() / 100f).coerceIn(0.03f, 1f))
                                .clip(RoundedCornerShape(999.dp))
                                .background(barColor),
                        )
                    }
                    Text(
                        text = buildString {
                            append("上下文 ${formatOneDecimal(pct)}%")
                            append(" (${formatTokenCompact(sessionUsage.contextTokens)}/${formatTokenCompact(sessionUsage.contextWindow)})")
                            sessionUsage.cacheHitPercent?.takeIf { it > 0.5 }?.let { ch ->
                                append(" · 缓存 ${ch.roundToInt()}%")
                            }
                            sessionUsage.totals.estimatedCostUsd?.takeIf { it > 0.0005 }?.let { cost ->
                                append(" · ${formatUsd(cost)}")
                            }
                        },
                        fontSize = 11.sp,
                        color = if (pct >= 85.0) barColor else Everforest.Grey2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (sessionUsage != null && !sessionUsage.supported) {
                    Icon(
                        imageVector = Icons.Rounded.QueryStats,
                        contentDescription = null,
                        tint = Everforest.CommentGrey,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "已对话 ${sessionUsage.totals.requests} 轮 · 查看用量与额度",
                        fontSize = 11.sp,
                        color = Everforest.CommentGrey,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.QueryStats,
                        contentDescription = null,
                        tint = Everforest.Green,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "本会话累计 ${formatTokenCompact(fallbackTotal)} Token · 查看详情",
                        fontSize = 11.sp,
                        color = Everforest.Grey2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "展开用量看板",
                tint = Everforest.Grey0,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
internal fun UsageDashboardSheet(
    state: ConsoleUiState,
    onSelectDays: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    val summary = state.usageSummary
    val quota = state.usageQuota
    val sessionUsage = state.activeSessionUsage
    val proxy = state.proxyUsage
    val nowMs = remember(quota, summary) { epochMillis() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 420.dp, max = 640.dp)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header + Day range pills + Refresh
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "用量与额度",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Everforest.Fg,
                )
                Text(
                    text = "上下文容量 · 5小时/周窗口 · 多引擎账单估算",
                    fontSize = 11.5.sp,
                    color = Everforest.CommentGrey,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.usageLoading) {
                    CircularProgressIndicator(
                        color = Everforest.Green,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新用量",
                        tint = Everforest.Grey2,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (d in listOf(7, 14, 30)) {
                SidebarFilterPill(
                    label = "近 $d 天",
                    selected = state.usageSelectedDays == d,
                    onClick = { onSelectDays(d) },
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Active Session Context Card
            if (sessionUsage != null) {
                SessionContextCard(sessionUsage)
            }

            // 2. Subscription Quota Windows (5h / Weekly)
            if (quota != null && quota.windows.isNotEmpty()) {
                SectionHeader("订阅窗口与重置倒计时")
                for (w in quota.windows) {
                    QuotaWindowCard(window = w, nowMs = nowMs)
                }
            }

            // 3. Global Summary KPIs + Daily Bars + Engine/Model Breakdown
            if (summary != null) {
                SectionHeader("近 ${summary.days} 天全局消耗")
                SummaryKpiGrid(summary.totals)

                val recentDays = summary.daily.filter { it.totalTokens > 0 || it.requests > 0 }.takeLast(10)
                if (recentDays.isNotEmpty()) {
                    DailyUsageBarsCard(recentDays)
                }

                if (summary.byEngine.isNotEmpty()) {
                    EngineBreakdownCard(summary.byEngine)
                }

                if (summary.byModel.isNotEmpty()) {
                    ModelRankingCard(summary.byModel.take(8))
                }
            } else if (!state.usageLoading) {
                Text(
                    text = "正在等待本机日志汇总数据…",
                    fontSize = 12.5.sp,
                    color = Everforest.CommentGrey,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            // 4. Local API Proxy
            if (proxy != null) {
                SectionHeader("本机模型代理")
                ProxyStatsCard(proxy)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Everforest.BoldFg,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun SessionContextCard(u: SessionUsageResult) {
    val pct = u.contextPercent.coerceIn(0.0, 100.0)
    val accent = when {
        pct >= 88.0 -> Everforest.Red
        pct >= 70.0 -> Everforest.BoldGold
        else -> Everforest.Green
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Everforest.Bg2,
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "当前会话上下文",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.BoldFg,
                    )
                    u.model?.takeIf { it.isNotBlank() }?.let { m ->
                        Text(
                            text = "· $m",
                            fontSize = 11.sp,
                            color = Everforest.CommentGrey,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (u.supported && u.contextWindow > 0) {
                    Text(
                        text = "${formatOneDecimal(pct)}%",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                }
            }

            if (u.supported && u.contextWindow > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Everforest.Bg0),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((pct.toFloat() / 100f).coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(999.dp))
                            .background(accent),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "已占用 ${formatTokenCompact(u.contextTokens)} / 容量 ${formatTokenCompact(u.contextWindow)}",
                        fontSize = 11.sp,
                        color = Everforest.Grey2,
                    )
                    Text(
                        text = buildString {
                            u.cacheHitPercent?.let { append("缓存命中 ${it.roundToInt()}%") }
                            u.totals.estimatedCostUsd?.let { c ->
                                if (isNotEmpty()) append(" · ")
                                append("约 ${formatUsd(c)}")
                            }
                        },
                        fontSize = 11.sp,
                        color = Everforest.CommentGrey,
                    )
                }
                Text(
                    text = "累计 ${formatTokenCompact(u.totals.totalTokens)} tokens (${u.totals.requests} 轮) · 最近一轮 ↑${formatTokenCompact(u.lastInputTokens)} ↓${formatTokenCompact(u.lastOutputTokens)}",
                    fontSize = 10.5.sp,
                    color = Everforest.CommentGrey,
                )
            } else {
                Text(
                    text = u.note ?: "已记录 ${u.totals.requests} 轮交互",
                    fontSize = 11.5.sp,
                    color = Everforest.CommentGrey,
                )
            }
        }
    }
}

@Composable
private fun QuotaWindowCard(window: QuotaWindowInfo, nowMs: Long) {
    val pct = window.usedPercent?.coerceIn(0.0, 100.0)
    val barColor = when {
        (pct ?: 0.0) >= 85.0 -> Everforest.Red
        (pct ?: 0.0) >= 65.0 -> Everforest.BoldGold
        else -> engineAccentColor(window.engineId)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EngineGlyphIcon(engineId = window.engineId, size = 14.dp)
                    Text(
                        text = window.label,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Fg,
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Everforest.Bg2,
                    ) {
                        Text(
                            text = if (window.source == "reported") "官方额度" else "窗口统计",
                            fontSize = 9.5.sp,
                            color = Everforest.CommentGrey,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    text = formatResetCountdown(window.resetsAt, window.active, nowMs),
                    fontSize = 11.sp,
                    color = Everforest.CommentGrey,
                )
            }

            if (pct != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Everforest.Bg2),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((pct.toFloat() / 100f).coerceIn(0.01f, 1f))
                            .clip(RoundedCornerShape(999.dp))
                            .background(barColor),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = buildString {
                        if (pct != null) {
                            append("已用 ${formatOneDecimal(pct)}%")
                            if (window.usedTokens > 0) append(" · ")
                        }
                        if (window.usedTokens > 0 || pct == null) {
                            append("${formatTokenCompact(window.usedTokens)} tokens")
                        }
                        window.estimatedCostUsd?.takeIf { it > 0.001 }?.let { c ->
                            append(" (${formatUsd(c)})")
                        }
                    },
                    fontSize = 11.sp,
                    color = Everforest.Grey2,
                )
                val burn = window.burnRateTokensPerMin
                if (burn != null && burn > 1.0 && window.active) {
                    Text(
                        text = "速率 ${formatTokenCompact(burn.toLong())}/分",
                        fontSize = 10.5.sp,
                        color = Everforest.CommentGrey,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryKpiGrid(totals: UsageTotals) {
    val promptSide = totals.inputTokens + totals.cacheReadTokens + totals.cacheWriteTokens
    val cachePct = if (promptSide > 0) ((totals.cacheReadTokens.toDouble() / promptSide) * 100.0).roundToInt() else 0

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KpiTile(
                title = "累计 Token",
                value = formatTokenCompact(totals.totalTokens),
                sub = "输入 ${formatTokenCompact(totals.inputTokens)} · 输出 ${formatTokenCompact(totals.outputTokens)}",
                modifier = Modifier.weight(1f),
            )
            KpiTile(
                title = "预估账单",
                value = formatUsd(totals.estimatedCostUsd),
                sub = "按公开 API 单价折算",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KpiTile(
                title = "缓存命中",
                value = "$cachePct%",
                sub = "读取 ${formatTokenCompact(totals.cacheReadTokens)}",
                modifier = Modifier.weight(1f),
            )
            KpiTile(
                title = "请求与交互",
                value = "${totals.requests}",
                sub = "缓存写入 ${formatTokenCompact(totals.cacheWriteTokens)}",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KpiTile(
    title: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, fontSize = 11.sp, color = Everforest.CommentGrey)
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Everforest.BoldFg,
                fontFamily = FontFamily.Monospace,
            )
            Text(sub, fontSize = 10.sp, color = Everforest.Grey1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DailyUsageBarsCard(days: List<DailyUsageEntry>) {
    val maxTokens = (days.maxOfOrNull { it.totalTokens } ?: 1L).coerceAtLeast(1L)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("按日消耗趋势", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Everforest.Fg)
            for (d in days.asReversed()) {
                val ratio = (d.totalTokens.toFloat() / maxTokens.toFloat()).coerceIn(0.03f, 1f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = d.date.takeLast(5),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CommentGrey,
                        modifier = Modifier.width(42.dp),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(7.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Everforest.Bg2),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(ratio)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Everforest.Green.copy(alpha = 0.85f)),
                        )
                    }
                    Text(
                        text = buildString {
                            append(formatTokenCompact(d.totalTokens))
                            d.estimatedCostUsd?.takeIf { it >= 0.01 }?.let { c ->
                                append(" · ${formatUsd(c)}")
                            }
                        },
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.Grey2,
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineBreakdownCard(engines: List<EngineUsageEntry>) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("按引擎汇总", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Everforest.Fg)
            for (e in engines) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        EngineGlyphIcon(engineId = e.engineId, size = 15.dp)
                        Column {
                            Text(
                                text = shortEngineLabel(e.engineId),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Everforest.Fg,
                            )
                            if (!e.note.isNullOrBlank()) {
                                Text(
                                    text = e.note,
                                    fontSize = 10.sp,
                                    color = Everforest.CommentGrey,
                                )
                            }
                        }
                    }
                    Text(
                        text = if (e.supported || e.totalTokens > 0) {
                            buildString {
                                append("${formatTokenCompact(e.totalTokens)} · ${e.requests} 次")
                                e.estimatedCostUsd?.takeIf { it >= 0.01 }?.let { append(" · ${formatUsd(it)}") }
                            }
                        } else {
                            "${e.requests} 轮交互"
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.Grey2,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRankingCard(models: List<ModelUsageEntry>) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("常用模型排行", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Everforest.Fg)
            for (m in models) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = m.model,
                            fontSize = 11.5.sp,
                            color = Everforest.Fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${shortEngineLabel(m.engineId)} · ${m.requests} 次请求",
                            fontSize = 10.sp,
                            color = Everforest.CommentGrey,
                        )
                    }
                    Text(
                        text = buildString {
                            append(formatTokenCompact(m.totalTokens))
                            m.estimatedCostUsd?.takeIf { it >= 0.01 }?.let { append(" · ${formatUsd(it)}") }
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.Grey2,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyStatsCard(proxy: ProxyUsageResult) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (proxy.reachable) Everforest.Green else Everforest.Grey0),
                    )
                    Text(
                        text = if (proxy.reachable) "在线 (${proxy.exposedModels} 个模型)" else "未启动或未连接",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.Fg,
                    )
                }
                Text(
                    text = proxy.endpoint.removePrefix("http://").removePrefix("https://"),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.CommentGrey,
                )
            }

            if (proxy.managementEnabled && proxy.totalRequests != null) {
                Text(
                    text = "累计请求 ${proxy.totalRequests} 次 · 成功 ${proxy.successCount ?: 0} · 失败 ${proxy.failureCount ?: 0} · 代理总计 ${formatTokenCompact(proxy.totalTokens ?: 0)} tokens",
                    fontSize = 11.sp,
                    color = Everforest.Grey2,
                )
                for (pm in proxy.byModel.take(5)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(pm.model, fontSize = 10.5.sp, color = Everforest.CommentGrey, maxLines = 1)
                        Text(
                            "${pm.requests} 次 · ${formatTokenCompact(pm.totalTokens)}",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Everforest.Grey2,
                        )
                    }
                }
            } else if (!proxy.note.isNullOrBlank()) {
                Text(proxy.note, fontSize = 11.sp, color = Everforest.CommentGrey)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Compact Human-Friendly Formatting Helpers
// ---------------------------------------------------------------------------

internal fun formatTokenCompact(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> "${formatOneDecimal(tokens / 1_000_000_000.0)}B"
    tokens >= 1_000_000L -> "${formatOneDecimal(tokens / 1_000_000.0)}M"
    tokens >= 1_000L -> "${formatOneDecimal(tokens / 1_000.0)}k"
    else -> tokens.toString()
}

internal fun formatOneDecimal(v: Double): String {
    val tenths = (v * 10.0).roundToInt()
    val whole = tenths / 10
    val frac = kotlin.math.abs(tenths % 10)
    return if (frac == 0) "$whole" else "$whole.$frac"
}

internal fun formatUsd(usd: Double?): String {
    if (usd == null) return "—"
    if (usd < 0.005) return "<$0.01"
    val cents = (usd * 100.0).roundToInt()
    val dollars = cents / 100
    val rem = (cents % 100).toString().padStart(2, '0')
    return "$$dollars.$rem"
}

private fun formatResetCountdown(resetsAt: Long?, active: Boolean, nowMs: Long): String {
    if (resetsAt == null || resetsAt <= nowMs) {
        return if (active) "即将重置" else "当前无活跃计费窗口"
    }
    val diffMin = ((resetsAt - nowMs) / 60_000L).coerceAtLeast(1L)
    val days = diffMin / (60 * 24)
    val hours = (diffMin % (60 * 24)) / 60
    val mins = diffMin % 60
    return when {
        days > 0 -> "${days}天 ${hours}小时后重置"
        hours > 0 -> "${hours}小时 ${mins}分后重置"
        else -> "${mins}分后重置"
    }
}
