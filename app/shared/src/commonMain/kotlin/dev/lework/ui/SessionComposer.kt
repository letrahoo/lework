package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.canCaptureCameraImage
import dev.lework.platform.canPickImages
import dev.lework.protocol.*
import dev.lework.store.*

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
    onPickImage: () -> Unit = {},
    onCaptureCamera: () -> Unit = {},
    onRemoveImage: (Int) -> Unit = {},
    onSelectMentionFile: (String) -> Unit = {},
    onDismissMention: () -> Unit = {},
) {
    val activeSess = state.activeSession?.takeIf { it.id == state.activeSessionId }
    val isSessionRunning = activeSess != null &&
        (activeSess.state == SessionState.RUNNING || activeSess.state == SessionState.WAITING_PERMISSION)
    val queuedCount = activeSess?.queuedPrompts?.size ?: 0
    val imageSupported = remember { canPickImages() } && (state.selectedEngine?.capabilities?.images != false)
    val cameraSupported = remember { canCaptureCameraImage() }
    var showImageSourceMenu by remember { mutableStateOf(false) }
    var lightboxIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.mentionQuery != null && state.workspaceFileSuggestions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
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
                            text = "选择要引用的工作区文件",
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectMentionFile(relPath) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
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
            shape = RoundedCornerShape(22.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (queuedCount > 0) {
                    Row(
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
                                    .size(62.dp)
                                    .testTag("composer_thumb_$idx"),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Everforest.Bg2,
                                    border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
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
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(4.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Image,
                                                contentDescription = null,
                                                tint = Everforest.Green,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Text(
                                                text = img.fileName?.ifBlank { null } ?: "图${idx + 1}",
                                                fontSize = 9.5.sp,
                                                color = Everforest.Grey2,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
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

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp, max = 120.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = "发消息给 ${shortEngineLabel(state.selectedEngineId)}（输入 @ 可引用文件）…",
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("composer_input"),
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
                        if (imageSupported) {
                            val remainingSlots = (4 - state.composerAttachedImages.size).coerceAtLeast(0)
                            Box {
                                ComposerMetaChip(
                                    icon = Icons.Rounded.AddPhotoAlternate,
                                    label = if (state.composerAttachedImages.isEmpty()) {
                                        "添加图片"
                                    } else {
                                        "图片 ${state.composerAttachedImages.size}/4"
                                    },
                                    tint = if (remainingSlots > 0) Everforest.Green else Everforest.CommentGrey,
                                    labelColor = if (remainingSlots > 0) Everforest.Fg else Everforest.CommentGrey,
                                    testTag = "composer_add_image_btn",
                                    onClick = {
                                        if (remainingSlots <= 0) return@ComposerMetaChip
                                        if (cameraSupported) {
                                            showImageSourceMenu = true
                                        } else {
                                            onPickImage()
                                        }
                                    },
                                )
                                DropdownMenu(
                                    expanded = showImageSourceMenu,
                                    onDismissRequest = { showImageSourceMenu = false },
                                    modifier = Modifier.background(Everforest.Bg1),
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text("直接拍照识别", color = Everforest.Fg, fontSize = 13.5.sp)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.PhotoCamera,
                                                contentDescription = null,
                                                tint = Everforest.Aqua,
                                                modifier = Modifier.size(17.dp),
                                            )
                                        },
                                        onClick = {
                                            showImageSourceMenu = false
                                            onCaptureCamera()
                                        },
                                        modifier = Modifier.testTag("composer_pick_camera"),
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "从相册选择（最多 $remainingSlots 张）",
                                                color = Everforest.Fg,
                                                fontSize = 13.5.sp,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.PhotoLibrary,
                                                contentDescription = null,
                                                tint = Everforest.Green,
                                                modifier = Modifier.size(17.dp),
                                            )
                                        },
                                        onClick = {
                                            showImageSourceMenu = false
                                            onPickImage()
                                        },
                                        modifier = Modifier.testTag("composer_pick_library"),
                                    )
                                }
                            }
                        }
                        ComposerMetaChip(
                            icon = Icons.Rounded.AlternateEmail,
                            label = "引用文件",
                            tint = Everforest.CommentGrey,
                            labelColor = Everforest.Fg,
                            onClick = {
                                val next = if (text.isEmpty() || text.last().isWhitespace()) "$text@" else "$text @"
                                onTextChange(next)
                            },
                        )
                        val isWorktreeActive =
                            activeSess?.worktree != null || (activeSess == null && state.draftUseWorktree)
                        ComposerMetaChip(
                            icon = if (isWorktreeActive) Icons.Rounded.AccountTree else Icons.Rounded.Folder,
                            label = state.workspaceDisplayName,
                            tint = if (isWorktreeActive) Everforest.Aqua else Everforest.CommentGrey,
                            labelColor = if (isWorktreeActive) Everforest.Aqua else Everforest.Fg,
                            testTag = "composer_workspace_chip",
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

                    val hasDraft = text.isNotBlank() || state.composerAttachedImages.isNotEmpty()
                    val canQueueWhileRunning =
                        isSessionRunning && hasDraft && activeSess?.state == SessionState.RUNNING

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isSessionRunning) {
                            Surface(
                                shape = CircleShape,
                                color = Everforest.Red,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .clickable { onCancel() }
                                    .testTag("composer_stop_btn"),
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
                        }

                        if (!isSessionRunning || canQueueWhileRunning) {
                            Surface(
                                shape = CircleShape,
                                color = if (hasDraft) Everforest.Green else Everforest.Bg2,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = hasDraft) { onSend() }
                                    .testTag("composer_send_btn"),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowUpward,
                                        contentDescription = if (canQueueWhileRunning) "排队发送" else "发送",
                                        tint = if (hasDraft) Everforest.BgDim else Everforest.Grey0,
                                        modifier = Modifier.size(18.dp),
                                    )
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
private fun ComposerMetaChip(
    icon: ImageVector?,
    label: String,
    tint: Color,
    labelColor: Color = Everforest.Fg,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Everforest.Bg2,
        modifier = Modifier
            .let { mod -> if (testTag != null) mod.testTag(testTag) else mod }
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
