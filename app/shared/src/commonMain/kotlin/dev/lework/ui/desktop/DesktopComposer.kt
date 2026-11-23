package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.canPickImages
import dev.lework.protocol.SessionMode
import dev.lework.protocol.SessionState
import dev.lework.store.ConsoleUiState
import dev.lework.ui.*

@Composable
internal fun DesktopComposer(
    state: ConsoleUiState,
    text: String,
    onTextChange: (String) -> Unit,
    onOpenWorkspaceDialog: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onCycleMode: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onClearQueuedPrompts: () -> Unit = {},
    onPickImage: () -> Unit,
    onPasteClipboardImages: () -> Boolean = { false },
    onRemoveImage: (Int) -> Unit,
    onSelectMentionFile: (String) -> Unit,
    onDismissMention: () -> Unit,
) {
    val activeSess = state.activeSession?.takeIf { it.id == state.activeSessionId }
    val isSessionRunning = activeSess != null &&
        (activeSess.state == SessionState.RUNNING || activeSess.state == SessionState.WAITING_PERMISSION)
    val queuedCount = activeSess?.queuedPrompts?.size ?: 0
    val imageSupported = remember { canPickImages() } && (state.selectedEngine?.capabilities?.images != false)
    var lightboxIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.mentionQuery != null && state.workspaceFileSuggestions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Everforest.Bg1,
                border = BorderStroke(1.dp, Everforest.Bg3),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("composer_mention_popup"),
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
                            text = "选择要引用的工作区文件（Enter 选中首项）",
                            fontSize = 11.sp,
                            color = Everforest.CommentGrey,
                        )
                        Text(
                            text = "关闭",
                            fontSize = 11.sp,
                            color = Everforest.Grey1,
                            modifier = Modifier.clickable { onDismissMention() },
                        )
                    }
                    state.workspaceFileSuggestions.take(6).forEach { relPath ->
                        val itemHover = remember { MutableInteractionSource() }
                        val hovered by itemHover.collectIsHoveredAsState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (hovered) Everforest.Bg2 else Color.Transparent)
                                .hoverable(itemHover)
                                .clickable(interactionSource = itemHover, indication = null) {
                                    onSelectMentionFile(relPath)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                                tint = Everforest.Green,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = relPath,
                                fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Everforest.Fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (queuedCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = Everforest.BoldGold,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "已加入待发队列 ($queuedCount 条)，当前轮次结束后自动继续",
                            fontSize = 11.5.sp,
                            color = Everforest.BoldGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "取消排队",
                            fontSize = 11.sp,
                            color = Everforest.SoftRed,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onClearQueuedPrompts() }
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                .testTag("composer_clear_queued_btn"),
                        )
                    }
                }

                if (state.composerAttachedImages.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        state.composerAttachedImages.forEachIndexed { idx, img ->
                            val bmp = rememberBase64ImageBitmap(img.thumbnailBase64 ?: img.dataBase64)
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .testTag("composer_thumb_$idx"),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Everforest.Bg2,
                                    border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { lightboxIndex = idx },
                                ) {
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = img.fileName ?: "图片 ${idx + 1}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Image,
                                                contentDescription = null,
                                                tint = Everforest.Green,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = Everforest.BgDim.copy(alpha = 0.88f),
                                    border = BorderStroke(0.8.dp, Everforest.Bg3),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .clickable { onRemoveImage(idx) }
                                        .testTag("composer_remove_img_$idx"),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "移除图片",
                                            tint = Everforest.Fg,
                                            modifier = Modifier.size(11.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val hasDraft = text.isNotBlank() || state.composerAttachedImages.isNotEmpty()
                val canSubmitNow =
                    hasDraft && (!isSessionRunning || activeSess?.state == SessionState.RUNNING)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp, max = 180.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = "发消息给 ${shortEngineLabel(state.selectedEngineId)}（Enter 发送 · Shift+Enter 换行 · ⌘V 粘贴图片 · @ 引用文件）…",
                            color = Everforest.CommentGrey,
                            fontSize = 14.5.sp,
                            lineHeight = 20.sp,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = TextStyle(
                            color = Everforest.Fg,
                            fontSize = 14.5.sp,
                            lineHeight = 20.sp,
                        ),
                        cursorBrush = SolidColor(Everforest.CodeGreen),
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val isPasteShortcut =
                                    keyEvent.key == Key.V &&
                                        (keyEvent.isMetaPressed || keyEvent.isCtrlPressed) &&
                                        !keyEvent.isShiftPressed &&
                                        !keyEvent.isAltPressed
                                if (isPasteShortcut) {
                                    return@onPreviewKeyEvent onPasteClipboardImages()
                                }
                                val isEnter = keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter
                                if (!isEnter) return@onPreviewKeyEvent false
                                if (keyEvent.isMetaPressed || keyEvent.isCtrlPressed) {
                                    if (canSubmitNow) onSend()
                                    return@onPreviewKeyEvent true
                                }
                                if (!keyEvent.isShiftPressed) {
                                    if (state.mentionQuery != null && state.workspaceFileSuggestions.isNotEmpty()) {
                                        onSelectMentionFile(state.workspaceFileSuggestions.first())
                                    } else if (canSubmitNow) {
                                        onSend()
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                false
                            }
                            .testTag("composer_input"),
                    )
                }

                // Bottom Controls Bar (Iconified Attachments + Workspace + Unified Model/Reasoning Trigger + Mode)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (imageSupported) {
                            val remainingSlots = (4 - state.composerAttachedImages.size).coerceAtLeast(0)
                            if (state.composerAttachedImages.isEmpty()) {
                                LeworkIconActionButton(
                                    icon = Icons.Rounded.AddPhotoAlternate,
                                    tooltip = "添加图片",
                                    subtitle = "支持多选或 ⌘V 粘贴 PNG / JPG / WebP（最多 4 张）",
                                    idleTint = Everforest.Green,
                                    hoverTint = Everforest.Green,
                                    size = 28.dp,
                                    iconSize = 16.5.dp,
                                    testTag = "composer_add_image_btn",
                                    onClick = onPickImage,
                                )
                            } else {
                                LeworkTooltip(
                                    label = if (remainingSlots > 0) "继续添加图片" else "已达图片上限 (4/4)",
                                    subtitle = if (remainingSlots > 0) "点击选择或直接 ⌘V 粘贴（还可添加 $remainingSlots 张）" else null,
                                ) {
                                    DesktopComposerPill(
                                        icon = Icons.Rounded.AddPhotoAlternate,
                                        label = "${state.composerAttachedImages.size}/4",
                                        tint = if (remainingSlots > 0) Everforest.Green else Everforest.CommentGrey,
                                        labelColor = if (remainingSlots > 0) Everforest.Fg else Everforest.CommentGrey,
                                        testTag = "composer_add_image_btn",
                                        onClick = { if (remainingSlots > 0) onPickImage() },
                                    )
                                }
                            }
                        }

                        LeworkIconActionButton(
                            icon = Icons.Rounded.AlternateEmail,
                            tooltip = "引用工作区文件",
                            shortcut = "@",
                            subtitle = "插入 @ 搜索并引用项目内文件",
                            size = 28.dp,
                            iconSize = 16.dp,
                            testTag = "composer_mention_btn",
                            onClick = {
                                val next = if (text.isEmpty() || text.last().isWhitespace()) "$text@" else "$text @"
                                onTextChange(next)
                            },
                        )

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .width(1.dp)
                                .height(14.dp)
                                .background(Everforest.Bg3),
                        )

                        val isWorktreeActive =
                            activeSess?.worktree != null || (activeSess == null && state.draftUseWorktree)
                        LeworkTooltip(
                            label = if (isWorktreeActive) "独立分支工作区" else "当前工作区目录",
                            subtitle = "${state.selectedWorkspacePath.ifBlank { "默认工作区" }}（点击切换目录或开启独立分支）",
                        ) {
                            DesktopComposerPill(
                                icon = if (isWorktreeActive) Icons.Rounded.AccountTree else Icons.Rounded.Folder,
                                label = state.workspaceDisplayName,
                                tint = if (isWorktreeActive) Everforest.Aqua else Everforest.CommentGrey,
                                labelColor = if (isWorktreeActive) Everforest.Aqua else Everforest.Fg,
                                testTag = "composer_workspace_chip",
                                onClick = onOpenWorkspaceDialog,
                            )
                        }

                        // Unified Engine + Model + Reasoning Level Trigger (⌘K)
                        val modelHover = remember { MutableInteractionSource() }
                        val isModelHovered by modelHover.collectIsHoveredAsState()
                        val displayModelName = state.selectedModelSpec?.name
                            ?.removeSuffix(" (Proxy)")
                            ?.let { if (it.length > 22) it.take(21) + "…" else it }
                            ?: shortEngineLabel(state.selectedEngineId)

                        LeworkTooltip(
                            label = "切换引擎、模型与推理等级",
                            shortcut = "⌘K",
                            subtitle = "${shortEngineLabel(state.selectedEngineId)} · $displayModelName · ${effortDisplayLabel(state.selectedEffort)}",
                        ) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (isModelHovered) Everforest.Bg3.copy(alpha = 0.78f) else Everforest.Bg2,
                                modifier = Modifier
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .hoverable(modelHover)
                                    .clickable(interactionSource = modelHover, indication = null) {
                                        onOpenModelPicker()
                                    }
                                    .testTag("composer_model_picker_btn"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    EngineGlyphIcon(engineId = state.selectedEngineId, size = 12.5.dp)
                                    Text(
                                        text = displayModelName,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Everforest.Fg,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = "· ${effortDisplayLabel(state.selectedEffort)}",
                                        fontSize = 11.sp,
                                        color = Everforest.Grey1,
                                        maxLines = 1,
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Everforest.Grey1,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                            }
                        }

                        val modeColor = when (state.selectedMode) {
                            SessionMode.PLAN -> Everforest.CodeGreen
                            SessionMode.AUTO, SessionMode.ACCEPT_EDITS -> Everforest.BoldGold
                            SessionMode.BYPASS_PERMISSIONS -> Everforest.Red
                            SessionMode.DEFAULT -> Everforest.Fg
                        }
                        LeworkIconActionButton(
                            icon = modeIcon(state.selectedMode),
                            tooltip = "执行模式：${modeDisplayLabel(state.selectedMode)}",
                            subtitle = "${modeDescription(state.selectedMode)}（点击切换）",
                            idleTint = modeColor,
                            hoverTint = modeColor,
                            size = 28.dp,
                            iconSize = 16.dp,
                            testTag = "composer_mode_btn",
                            onClick = onCycleMode,
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    val canQueueWhileRunning =
                        isSessionRunning && hasDraft && activeSess?.state == SessionState.RUNNING

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isSessionRunning) {
                            LeworkTooltip(label = "停止当前生成") {
                                Surface(
                                    shape = CircleShape,
                                    color = Everforest.Red,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable { onCancel() }
                                        .testTag("composer_stop_btn"),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.Stop,
                                            contentDescription = "停止",
                                            tint = Everforest.BgDim,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (!isSessionRunning || canQueueWhileRunning) {
                            LeworkTooltip(
                                label = if (canQueueWhileRunning) "加入待发队列" else "发送消息",
                                shortcut = if (hasDraft) "Enter" else null,
                                subtitle = if (!hasDraft) "输入内容或添加图片后发送" else null,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (hasDraft) Everforest.Green else Everforest.Bg2,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = hasDraft) { onSend() }
                                        .testTag("composer_send_btn"),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.ArrowUpward,
                                            contentDescription = if (canQueueWhileRunning) "排队发送" else "发送",
                                            tint = if (hasDraft) Everforest.BgDim else Everforest.Grey0,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (lightboxIndex != null && state.composerAttachedImages.isNotEmpty()) {
            ImageLightboxDialog(
                imagesBase64 = state.composerAttachedImages.map { it.dataBase64 },
                initialIndex = lightboxIndex ?: 0,
                onDismiss = { lightboxIndex = null },
            )
        }
    }
}

@Composable
private fun DesktopComposerPill(
    icon: ImageVector,
    label: String,
    tint: Color,
    labelColor: Color,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (isHovered) Everforest.Bg3.copy(alpha = 0.78f) else Everforest.Bg2,
        modifier = Modifier
            .height(28.dp)
            .let { mod -> if (testTag != null) mod.testTag(testTag) else mod }
            .clip(RoundedCornerShape(999.dp))
            .hoverable(hoverSource)
            .clickable(interactionSource = hoverSource, indication = null) { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.5.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            Text(
                text = label,
                fontSize = 11.5.sp,
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
