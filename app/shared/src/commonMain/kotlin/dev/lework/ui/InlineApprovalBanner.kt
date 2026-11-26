package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.PermissionOption
import dev.lework.protocol.PermissionOptionKind

private val OPTION_INDEX_PREFIX_RE = Regex("^(\\d+\\.\\s+)(.*)$")
private val HEADER_REASON_PREFIX_RE = Regex("^(Reason:|原因[:：])\\s*(.*)$", RegexOption.IGNORE_CASE)
private val QUOTED_COMMAND_SNIPPET_RE = Regex("('[^']{2,}'|`[^`]{2,}`)")

private enum class ApprovalOptionTone {
    PRIMARY,
    SECONDARY,
    REJECT,
}

internal fun shouldUseVerticalApprovalOptions(options: List<PermissionOption>): Boolean {
    if (options.size > 3) return true
    val totalChars = options.sumOf { it.name.trim().length }
    return options.any { it.name.trim().length > 22 } || totalChars > 44
}

internal fun formatApprovalHeaderTitle(rawTitle: String): AnnotatedString {
    val match = HEADER_REASON_PREFIX_RE.matchEntire(rawTitle.trim()) ?: return AnnotatedString(rawTitle)
    val prefix = match.groupValues[1]
    val rest = match.groupValues[2]
    return buildAnnotatedString {
        pushStyle(SpanStyle(color = Everforest.BoldGold, fontWeight = FontWeight.SemiBold))
        append(prefix)
        pop()
        if (rest.isNotEmpty()) {
            append(" ")
            append(rest)
        }
    }
}

internal fun formatApprovalOptionLabel(
    rawName: String,
    indexAccentColor: Color,
    codeAccentColor: Color,
    highlightCodeSnippets: Boolean = true,
): AnnotatedString = buildAnnotatedString {
    val prefixMatch = OPTION_INDEX_PREFIX_RE.matchEntire(rawName)
    val bodyStart: Int
    if (prefixMatch != null) {
        val prefix = prefixMatch.groupValues[1]
        pushStyle(
            SpanStyle(
                color = indexAccentColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        append(prefix)
        pop()
        bodyStart = prefix.length
    } else {
        bodyStart = 0
    }

    val body = rawName.substring(bodyStart)
    if (!highlightCodeSnippets || (!body.contains('\'') && !body.contains('`'))) {
        append(body)
        return@buildAnnotatedString
    }

    var cursor = 0
    for (m in QUOTED_COMMAND_SNIPPET_RE.findAll(body)) {
        val start = m.range.first
        val end = m.range.last + 1
        if (start > cursor) {
            append(body.substring(cursor, start))
        }
        pushStyle(
            SpanStyle(
                color = codeAccentColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
            ),
        )
        append(body.substring(start, end))
        pop()
        cursor = end
    }
    if (cursor < body.length) {
        append(body.substring(cursor))
    }
}

private fun resolveApprovalOptionTone(
    option: PermissionOption,
    isFirstAllowOption: Boolean,
): ApprovalOptionTone = when (option.kind) {
    PermissionOptionKind.REJECT_ONCE,
    PermissionOptionKind.REJECT_ALWAYS,
    -> ApprovalOptionTone.REJECT
    PermissionOptionKind.ALLOW_ONCE -> if (isFirstAllowOption) {
        ApprovalOptionTone.PRIMARY
    } else {
        ApprovalOptionTone.SECONDARY
    }
    PermissionOptionKind.ALLOW_ALWAYS -> ApprovalOptionTone.SECONDARY
}

private fun approvalOptionBadgeText(kind: PermissionOptionKind): String = when (kind) {
    PermissionOptionKind.ALLOW_ONCE -> "本次"
    PermissionOptionKind.ALLOW_ALWAYS -> "始终"
    PermissionOptionKind.REJECT_ONCE,
    PermissionOptionKind.REJECT_ALWAYS,
    -> "拒绝"
}

private fun approvalOptionIcon(tone: ApprovalOptionTone): ImageVector = when (tone) {
    ApprovalOptionTone.PRIMARY -> Icons.Rounded.Check
    ApprovalOptionTone.SECONDARY -> Icons.Rounded.DoneAll
    ApprovalOptionTone.REJECT -> Icons.Rounded.Close
}

@Composable
internal fun InlineApprovalBanner(
    permission: AgentEvent.PermissionRequested,
    isSubmitting: Boolean = false,
    errorText: String? = null,
    onRefresh: (() -> Unit)? = null,
    onDecision: (requestId: String, optionId: String?, sessionId: String) -> Unit,
) {
    val lines = remember(permission.title) { permission.title.lines().dropWhile { it.isBlank() } }
    val headerTitle = lines.firstOrNull()?.trim() ?: "需要执行审批"
    val formattedHeaderTitle = remember(headerTitle) { formatApprovalHeaderTitle(headerTitle) }
    val cmdPreview = remember(lines) {
        lines.drop(1)
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }
    val hasBodyContent = !cmdPreview.isNullOrBlank() || !errorText.isNullOrBlank() || permission.options.isNotEmpty()
    val useVerticalOptions = remember(permission.options) {
        shouldUseVerticalApprovalOptions(permission.options)
    }
    val firstAllowOptionId = remember(permission.options) {
        permission.options.firstOrNull {
            it.kind == PermissionOptionKind.ALLOW_ONCE
        }?.id
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("inline_approval_banner"),
        shape = RoundedCornerShape(12.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.BoldGold.copy(alpha = 0.36f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ApprovalBannerHeader(
                formattedHeaderTitle = formattedHeaderTitle,
                isSubmitting = isSubmitting,
                showRefreshAction = onRefresh != null && permission.options.isEmpty(),
                showEscCancelAction = permission.options.isNotEmpty() && permission.requestId.startsWith("tty_perm_"),
                onRefresh = onRefresh,
                onEscCancel = { onDecision(permission.requestId, null, permission.sessionId) },
            )

            if (hasBodyContent) {
                HorizontalDivider(thickness = 1.dp, color = Everforest.Bg2)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!cmdPreview.isNullOrBlank()) {
                        ApprovalCommandPreview(
                            cmdPreview = cmdPreview,
                            isFallbackPlainText = permission.options.isEmpty(),
                        )
                    }

                    if (!errorText.isNullOrBlank()) {
                        ApprovalErrorNotice(errorText = errorText)
                    }

                    if (permission.options.isNotEmpty()) {
                        if (useVerticalOptions) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("inline_approval_vertical_options"),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (opt in permission.options) {
                                    val tone = resolveApprovalOptionTone(
                                        option = opt,
                                        isFirstAllowOption = opt.id == firstAllowOptionId,
                                    )
                                    ApprovalVerticalOptionRow(
                                        option = opt,
                                        tone = tone,
                                        enabled = !isSubmitting,
                                        onClick = { onDecision(permission.requestId, opt.id, permission.sessionId) },
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("inline_approval_compact_options"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                for (opt in permission.options) {
                                    val tone = resolveApprovalOptionTone(
                                        option = opt,
                                        isFirstAllowOption = opt.id == firstAllowOptionId,
                                    )
                                    ApprovalCompactOptionButton(
                                        option = opt,
                                        tone = tone,
                                        enabled = !isSubmitting,
                                        onClick = { onDecision(permission.requestId, opt.id, permission.sessionId) },
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

@Composable
private fun ApprovalBannerHeader(
    formattedHeaderTitle: AnnotatedString,
    isSubmitting: Boolean,
    showRefreshAction: Boolean,
    showEscCancelAction: Boolean,
    onRefresh: (() -> Unit)?,
    onEscCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Everforest.BoldGold.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Everforest.BoldGold.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    color = Everforest.BoldGold,
                    strokeWidth = 1.8.dp,
                    modifier = Modifier.size(12.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = Everforest.BoldGold,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        if (isSubmitting) {
            Text(
                text = "正在向主机终端发送确认…",
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Everforest.BoldGold,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = formattedHeaderTitle,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Everforest.BoldFg,
                modifier = Modifier.weight(1f),
            )
        }

        when {
            showRefreshAction && onRefresh != null -> {
                ApprovalHeaderGhostAction(
                    label = "同步选项",
                    icon = Icons.Rounded.Refresh,
                    accentColor = Everforest.CodeGreen,
                    enabled = !isSubmitting,
                    onClick = onRefresh,
                )
            }
            showEscCancelAction -> {
                ApprovalHeaderGhostAction(
                    label = "取消 (Esc)",
                    icon = null,
                    accentColor = Everforest.Grey2,
                    enabled = !isSubmitting,
                    onClick = onEscCancel,
                )
            }
            else -> {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = Everforest.BoldGold.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = if (isSubmitting) "处理中" else "待确认",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Everforest.BoldGold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalHeaderGhostAction(
    label: String,
    icon: ImageVector?,
    accentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isHovered && enabled) Everforest.Bg2 else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (isHovered && enabled) Everforest.Bg4 else Everforest.Bg3.copy(alpha = 0.7f),
        ),
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .hoverable(hoverSource)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = accentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ApprovalCommandPreview(
    cmdPreview: String,
    isFallbackPlainText: Boolean,
) {
    if (isFallbackPlainText) {
        SelectionContainer {
            Text(
                text = cmdPreview,
                fontSize = 12.sp,
                lineHeight = 17.5.sp,
                color = Everforest.Fg,
            )
        }
        return
    }
    val highlightedCmd = remember(cmdPreview) { highlightEverforestCode(cmdPreview, "zsh") }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = highlightedCmd,
                fontSize = 11.5.sp,
                lineHeight = 16.5.sp,
                fontFamily = FontFamily.Monospace,
                color = Everforest.Fg,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun ApprovalErrorNotice(errorText: String) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = Everforest.Red.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, Everforest.Red.copy(alpha = 0.30f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = errorText,
                fontSize = 11.5.sp,
                lineHeight = 16.5.sp,
                color = Everforest.SoftRed,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ApprovalVerticalOptionRow(
    option: PermissionOption,
    tone: ApprovalOptionTone,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()

    val containerColor = when (tone) {
        ApprovalOptionTone.PRIMARY -> Everforest.CodeGreen.copy(alpha = if (isHovered) 0.21f else 0.13f)
        ApprovalOptionTone.SECONDARY -> if (isHovered) Everforest.Bg2 else Everforest.Bg0.copy(alpha = 0.72f)
        ApprovalOptionTone.REJECT -> Everforest.Red.copy(alpha = if (isHovered) 0.15f else 0.06f)
    }
    val borderColor = when (tone) {
        ApprovalOptionTone.PRIMARY -> Everforest.CodeGreen.copy(alpha = if (isHovered) 0.72f else 0.45f)
        ApprovalOptionTone.SECONDARY -> if (isHovered) Everforest.BoldGold.copy(alpha = 0.50f) else Everforest.Bg3
        ApprovalOptionTone.REJECT -> Everforest.Red.copy(alpha = if (isHovered) 0.55f else 0.28f)
    }
    val iconTint = when (tone) {
        ApprovalOptionTone.PRIMARY -> Everforest.CodeGreen
        ApprovalOptionTone.SECONDARY -> Everforest.BoldGold
        ApprovalOptionTone.REJECT -> Everforest.SoftRed
    }
    val textColor = when (tone) {
        ApprovalOptionTone.PRIMARY -> Everforest.BoldFg
        ApprovalOptionTone.SECONDARY -> Everforest.Fg
        ApprovalOptionTone.REJECT -> Everforest.SoftRed
    }
    val badgeBg = when (tone) {
        ApprovalOptionTone.PRIMARY -> Everforest.CodeGreen.copy(alpha = 0.16f)
        ApprovalOptionTone.SECONDARY -> Everforest.BoldGold.copy(alpha = 0.12f)
        ApprovalOptionTone.REJECT -> Everforest.Red.copy(alpha = 0.12f)
    }
    val formattedLabel = remember(option.name, tone) {
        formatApprovalOptionLabel(
            rawName = option.name,
            indexAccentColor = iconTint,
            codeAccentColor = Everforest.InlineCode,
            highlightCodeSnippets = true,
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .hoverable(hoverSource)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("approval_option_${option.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = approvalOptionIcon(tone),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(12.dp),
                )
            }

            Text(
                text = formattedLabel,
                fontSize = 12.sp,
                lineHeight = 17.5.sp,
                fontWeight = if (tone == ApprovalOptionTone.PRIMARY) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
                modifier = Modifier.weight(1f),
            )

            Surface(
                shape = RoundedCornerShape(5.dp),
                color = badgeBg,
                modifier = Modifier.padding(top = 1.dp),
            ) {
                Text(
                    text = approvalOptionBadgeText(option.kind),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = iconTint,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ApprovalCompactOptionButton(
    option: PermissionOption,
    tone: ApprovalOptionTone,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()

    val containerColor = when (tone) {
        ApprovalOptionTone.PRIMARY -> if (isHovered) Everforest.CodeGreen else Everforest.Green
        ApprovalOptionTone.SECONDARY -> if (isHovered) Everforest.Bg3 else Everforest.Bg2
        ApprovalOptionTone.REJECT -> Everforest.Red.copy(alpha = if (isHovered) 0.16f else 0.08f)
    }
    val borderColor = when (tone) {
        ApprovalOptionTone.PRIMARY -> Color.Transparent
        ApprovalOptionTone.SECONDARY -> if (isHovered) Everforest.BoldGold.copy(alpha = 0.45f) else Everforest.Bg4
        ApprovalOptionTone.REJECT -> Everforest.Red.copy(alpha = if (isHovered) 0.62f else 0.38f)
    }
    val contentColor = when (tone) {
        ApprovalOptionTone.PRIMARY -> Everforest.BgDim
        ApprovalOptionTone.SECONDARY -> Everforest.BoldFg
        ApprovalOptionTone.REJECT -> Everforest.SoftRed
    }
    val iconTint = when (tone) {
        ApprovalOptionTone.PRIMARY -> Everforest.BgDim
        ApprovalOptionTone.SECONDARY -> Everforest.BoldGold
        ApprovalOptionTone.REJECT -> Everforest.SoftRed
    }
    val formattedLabel = remember(option.name, tone) {
        formatApprovalOptionLabel(
            rawName = option.name,
            indexAccentColor = contentColor.copy(alpha = if (tone == ApprovalOptionTone.PRIMARY) 0.85f else 0.9f),
            codeAccentColor = if (tone == ApprovalOptionTone.PRIMARY) Everforest.BgDim else Everforest.InlineCode,
            highlightCodeSnippets = false,
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = if (borderColor == Color.Transparent) null else BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.55f)
            .hoverable(hoverSource)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("approval_option_${option.id}"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = approvalOptionIcon(tone),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = formattedLabel,
                fontSize = 12.sp,
                fontWeight = if (tone == ApprovalOptionTone.PRIMARY) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
