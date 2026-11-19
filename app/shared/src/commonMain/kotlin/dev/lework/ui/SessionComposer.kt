package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.appVersionLabel
import dev.lework.platform.epochMillis
import dev.lework.platform.loadSavedRelayUrls
import dev.lework.protocol.*
import dev.lework.store.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClaudeStyleComposer(
    state: ConsoleUiState,
    text: String,
    onTextChange: (String) -> Unit,
    onOpenWorkspaceSheet: () -> Unit,
    onCycleMode: () -> Unit,
    onCycleEffort: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val activeSess = state.activeSession?.takeIf { it.id == state.activeSessionId }
    val isHubRunning = activeSess != null &&
        activeSess.source == SessionSource.HUB &&
        (activeSess.state == SessionState.RUNNING || activeSess.state == SessionState.WAITING_PERMISSION)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg3),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 28.dp, max = 120.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "发消息给 ${shortEngineLabel(state.selectedEngineId)}…",
                        color = Everforest.CommentGrey,
                        fontSize = 15.sp,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = TextStyle(
                        color = Everforest.Fg,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    ),
                    cursorBrush = SolidColor(Everforest.CodeGreen),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerMetaChip(
                        icon = Icons.Rounded.Folder,
                        label = state.workspaceDisplayName,
                        tint = Everforest.CommentGrey,
                        labelColor = Everforest.Fg,
                        onClick = onOpenWorkspaceSheet,
                    )
                    val modeColor = when (state.selectedMode) {
                        SessionMode.PLAN -> Everforest.CodeGreen
                        SessionMode.AUTO, SessionMode.ACCEPT_EDITS -> Everforest.BoldGold
                        SessionMode.BYPASS_PERMISSIONS -> Everforest.Red
                        SessionMode.DEFAULT -> Everforest.Fg
                    }
                    ComposerMetaChip(
                        icon = null,
                        label = modeDisplayLabel(state.selectedMode),
                        tint = modeColor,
                        labelColor = modeColor,
                        onClick = onCycleMode,
                    )
                    ComposerMetaChip(
                        icon = Icons.Rounded.Bolt,
                        label = effortDisplayLabel(state.selectedEffort),
                        tint = Everforest.CommentGrey,
                        labelColor = Everforest.Grey2,
                        onClick = onCycleEffort,
                    )
                }

                Spacer(Modifier.width(8.dp))

                if (isHubRunning) {
                    Surface(
                        shape = CircleShape,
                        color = Everforest.Red,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { onCancel() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = "停止",
                                tint = Everforest.BgDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    val enabled = text.isNotBlank()
                    Surface(
                        shape = CircleShape,
                        color = if (enabled) Everforest.Green else Everforest.Bg2,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable(enabled = enabled) { onSend() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = "发送",
                                tint = if (enabled) Everforest.BgDim else Everforest.Grey0,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerMetaChip(
    icon: ImageVector?,
    label: String,
    tint: Color,
    labelColor: Color = Everforest.Fg,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Everforest.Bg2,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            }
            Text(
                text = label,
                fontSize = 11.sp,
                color = labelColor,
                fontWeight = if (labelColor != Everforest.Fg && labelColor != Everforest.Grey2) {
                    FontWeight.Medium
                } else {
                    FontWeight.Normal
                },
                maxLines = 1,
            )
        }
    }
}

