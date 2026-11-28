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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.MAX_ATTACHED_FILES
import dev.lework.platform.canCaptureCameraImage
import dev.lework.platform.canPickFiles
import dev.lework.platform.canPickImages
import dev.lework.protocol.*
import dev.lework.store.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClaudeStyleComposer(
    state: ConsoleUiState,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onOpenWorkspaceSheet: () -> Unit,
    onCycleMode: () -> Unit,
    onCycleEffort: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onClearQueuedPrompts: () -> Unit = {},
    onPickImage: () -> Unit = {},
    onPickFiles: () -> Unit = {},
    onCaptureCamera: () -> Unit = {},
    onPasteClipboardImages: (notifyWhenEmpty: Boolean) -> Boolean = { false },
    onRemoveImage: (Int) -> Unit = {},
    onRemoveFile: (Int) -> Unit = {},
    onSelectMentionFile: (String) -> Unit = {},
    onInsertAtMention: () -> Unit = {},
    onDismissMention: () -> Unit = {},
    onSelectSlashCommand: (ResolvedSlashCommand) -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val text = value.text
    val activeSess = state.activeSession?.takeIf { it.id == state.activeSessionId }
    val isSessionRunning = activeSess != null &&
        (activeSess.state == SessionState.RUNNING || activeSess.state == SessionState.WAITING_PERMISSION)
    val queuedCount = activeSess?.queuedPrompts?.size ?: 0
    val imageSupported = remember { canPickImages() } && (state.selectedEngine?.capabilities?.images != false)
    val fileSupported = remember { canPickFiles() }
    val cameraSupported = remember { canCaptureCameraImage() }
    var showImageSourceMenu by remember { mutableStateOf(false) }
    var lightboxIndex by remember { mutableStateOf<Int?>(null) }

    var browseSlashManually by remember(state.activeDraftKey) { mutableStateOf(false) }
    var dismissedSlashForText by remember(state.activeDraftKey) { mutableStateOf<String?>(null) }
    val typedSlashQuery = remember(text) { extractSlashCommandQuery(text) }
    val effectiveSlashQuery = typedSlashQuery ?: if (browseSlashManually) "" else null
    val availableSlashCommands = remember(
        state.selectedEngineId,
        activeSess?.id,
        activeSess?.slashCommands,
        state.selectedEngine?.slashCommands,
        state.availableEfforts,
    ) {
        buildAvailableSlashCommands(state, includeDesktopOnly = false)
    }
    val filteredSlashCommands = remember(availableSlashCommands, effectiveSlashQuery) {
        effectiveSlashQuery?.let { filterSlashCommands(availableSlashCommands, it) }.orEmpty()
    }
    val slashPopupVisible =
        effectiveSlashQuery != null && dismissedSlashForText != text && filteredSlashCommands.isNotEmpty()
    val mentionPopupVisible = !slashPopupVisible && state.mentionQuery != null && state.workspaceFileSuggestions.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (slashPopupVisible) {
            SlashCommandPopup(
                commands = filteredSlashCommands,
                selectedIndex = 0,
                isDesktop = false,
                onSelect = { cmd ->
                    browseSlashManually = false
                    dismissedSlashForText = null
                    onSelectSlashCommand(cmd)
                    if (!cmd.executeImmediatelyOnSelect) {
                        runCatching { focusRequester.requestFocus() }
                        keyboardController?.show()
                    }
                },
                onDismiss = {
                    browseSlashManually = false
                    dismissedSlashForText = text
                },
            )
        } else if (mentionPopupVisible) {
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
                                .clickable {
                                    onSelectMentionFile(relPath)
                                    runCatching { focusRequester.requestFocus() }
                                    keyboardController?.show()
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                                .testTag("mention_item_$relPath"),
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
                if (state.composerHasAttachments) {
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
                        state.composerAttachedFiles.forEachIndexed { idx, file ->
                            ComposerAttachedFileChip(
                                file = file,
                                index = idx,
                                onRemove = { onRemoveFile(idx) },
                            )
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
                            text = mobileComposerPlaceholder(
                                engineId = state.selectedEngineId,
                                hasAttachedImages = state.composerAttachedImages.isNotEmpty(),
                                hasAttachedFiles = state.composerAttachedFiles.isNotEmpty(),
                                canQueueFollowUp = isSessionRunning && activeSess?.state == SessionState.RUNNING,
                            ),
                            color = Everforest.CommentGrey,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("composer_placeholder"),
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { next ->
                            if (next.text != text) {
                                browseSlashManually = false
                                dismissedSlashForText = null
                            }
                            onValueChange(next)
                        },
                        textStyle = TextStyle(
                            color = Everforest.Fg,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                        ),
                        cursorBrush = SolidColor(Everforest.CodeGreen),
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val isPasteShortcut =
                                    keyEvent.key == Key.V &&
                                        (keyEvent.isMetaPressed || keyEvent.isCtrlPressed) &&
                                        !keyEvent.isShiftPressed &&
                                        !keyEvent.isAltPressed
                                if (isPasteShortcut) {
                                    return@onPreviewKeyEvent onPasteClipboardImages(false)
                                }
                                val isEnter = keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter
                                if (isEnter && value.composition == null && (keyEvent.isShiftPressed || keyEvent.isAltPressed)) {
                                    onValueChange(dev.lework.ui.desktop.insertNewlineAtSelection(value))
                                    return@onPreviewKeyEvent true
                                }
                                false
                            }
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
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (imageSupported || fileSupported) {
                            val imgCount = state.composerAttachedImages.size
                            val fileCount = state.composerAttachedFiles.size
                            val remainingImageSlots = if (imageSupported) (4 - imgCount).coerceAtLeast(0) else 0
                            val remainingFileSlots = if (fileSupported) (MAX_ATTACHED_FILES - fileCount).coerceAtLeast(0) else 0
                            val hasRemainingSlots = remainingImageSlots > 0 || remainingFileSlots > 0
                            val attachDescription = when {
                                imageSupported && fileSupported -> "添加图片或文件"
                                fileSupported -> "添加文件"
                                else -> "添加图片"
                            }
                            val attachIcon = when {
                                fileCount > 0 && imgCount == 0 -> Icons.Rounded.AttachFile
                                imgCount > 0 && fileCount == 0 -> Icons.Rounded.AddPhotoAlternate
                                fileSupported -> Icons.Rounded.AttachFile
                                else -> Icons.Rounded.AddPhotoAlternate
                            }
                            Box {
                                if (!state.composerHasAttachments) {
                                    ComposerIconButton(
                                        icon = attachIcon,
                                        contentDescription = attachDescription,
                                        tint = Everforest.Green,
                                        active = showImageSourceMenu,
                                        activeTint = Everforest.Green,
                                        iconSize = 16.dp,
                                        testTag = "composer_add_image_btn",
                                        onClick = { showImageSourceMenu = true },
                                    )
                                } else {
                                    val badgeLabel = when {
                                        imgCount > 0 && fileCount == 0 -> "$imgCount/4"
                                        fileCount > 0 && imgCount == 0 -> "$fileCount/$MAX_ATTACHED_FILES"
                                        else -> "${imgCount + fileCount}"
                                    }
                                    ComposerMetaChip(
                                        icon = attachIcon,
                                        label = badgeLabel,
                                        tint = if (hasRemainingSlots) Everforest.Green else Everforest.CommentGrey,
                                        labelColor = if (hasRemainingSlots) Everforest.Fg else Everforest.CommentGrey,
                                        iconContentDescription = attachDescription,
                                        testTag = "composer_add_image_btn",
                                        onClick = {
                                            if (!hasRemainingSlots) return@ComposerMetaChip
                                            showImageSourceMenu = true
                                        },
                                    )
                                }
                                DropdownMenu(
                                    expanded = showImageSourceMenu,
                                    onDismissRequest = { showImageSourceMenu = false },
                                    modifier = Modifier.background(Everforest.Bg1),
                                ) {
                                    if (fileSupported && remainingFileSlots > 0) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "选择文件（最多 $remainingFileSlots 个）",
                                                    color = Everforest.Fg,
                                                    fontSize = 13.5.sp,
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                                                    contentDescription = null,
                                                    tint = Everforest.Aqua,
                                                    modifier = Modifier.size(17.dp),
                                                )
                                            },
                                            onClick = {
                                                showImageSourceMenu = false
                                                onPickFiles()
                                            },
                                            modifier = Modifier.testTag("composer_pick_files"),
                                        )
                                    }
                                    if (imageSupported && remainingImageSlots > 0) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "从相册选择（最多 $remainingImageSlots 张）",
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
                                        if (cameraSupported) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text("直接拍照识别", color = Everforest.Fg, fontSize = 13.5.sp)
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Rounded.PhotoCamera,
                                                        contentDescription = null,
                                                        tint = Everforest.BoldGold,
                                                        modifier = Modifier.size(17.dp),
                                                    )
                                                },
                                                onClick = {
                                                    showImageSourceMenu = false
                                                    onCaptureCamera()
                                                },
                                                modifier = Modifier.testTag("composer_pick_camera"),
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Text("粘贴剪贴板图片", color = Everforest.Fg, fontSize = 13.5.sp)
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.ContentPaste,
                                                    contentDescription = null,
                                                    tint = Everforest.Grey2,
                                                    modifier = Modifier.size(17.dp),
                                                )
                                            },
                                            onClick = {
                                                showImageSourceMenu = false
                                                onPasteClipboardImages(true)
                                            },
                                            modifier = Modifier.testTag("composer_paste_clipboard_image"),
                                        )
                                    }
                                }
                            }
                        }
                        ComposerIconButton(
                            icon = Icons.Rounded.Terminal,
                            contentDescription = if (slashPopupVisible) "收起命令面板" else "命令",
                            tint = Everforest.CommentGrey,
                            active = slashPopupVisible,
                            activeTint = Everforest.CodeGreen,
                            iconSize = 15.dp,
                            testTag = "composer_slash_btn",
                            onClick = {
                                if (slashPopupVisible) {
                                    browseSlashManually = false
                                    dismissedSlashForText = text
                                } else {
                                    if (mentionPopupVisible) onDismissMention()
                                    dismissedSlashForText = null
                                    browseSlashManually = true
                                }
                            },
                        )
                        ComposerIconButton(
                            icon = Icons.Rounded.AlternateEmail,
                            contentDescription = if (mentionPopupVisible) "收起文件引用" else "引用文件",
                            tint = Everforest.CommentGrey,
                            active = mentionPopupVisible,
                            activeTint = Everforest.Green,
                            iconSize = 15.5.dp,
                            testTag = "composer_mention_btn",
                            onClick = {
                                if (mentionPopupVisible) {
                                    onDismissMention()
                                } else {
                                    browseSlashManually = false
                                    onInsertAtMention()
                                    runCatching { focusRequester.requestFocus() }
                                    keyboardController?.show()
                                }
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
                        if (state.availableEfforts.size > 1) {
                            ComposerMetaChip(
                                icon = Icons.Rounded.Bolt,
                                label = effortDisplayLabel(state.selectedEffort),
                                tint = Everforest.CommentGrey,
                                labelColor = Everforest.Grey2,
                                testTag = "composer_effort_chip",
                                onClick = onCycleEffort,
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    val hasDraft = text.isNotBlank() || state.composerHasAttachments
                    val canQueueWhileRunning =
                        isSessionRunning && hasDraft && activeSess?.state == SessionState.RUNNING
                    val isCancelling =
                        state.cancellingSessionId != null && state.cancellingSessionId == state.activeSessionId

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isSessionRunning) {
                            Surface(
                                shape = CircleShape,
                                color = if (isCancelling) Everforest.Red.copy(alpha = 0.65f) else Everforest.Red,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = !isCancelling) { onCancel() }
                                    .testTag("composer_stop_btn"),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isCancelling) {
                                        CircularProgressIndicator(
                                            color = Everforest.BgDim,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Stop,
                                            contentDescription = "停止",
                                            tint = Everforest.BgDim,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
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

internal fun mobileComposerPlaceholder(
    engineId: String,
    hasAttachedImages: Boolean,
    canQueueFollowUp: Boolean,
    hasAttachedFiles: Boolean = false,
): String = when {
    hasAttachedImages && hasAttachedFiles -> "添加附件说明，或直接发送…"
    hasAttachedFiles -> "添加文件说明，或直接发送…"
    hasAttachedImages -> "添加图片说明，或直接发送…"
    canQueueFollowUp -> "补充下一条指令，结束后自动发送…"
    else -> "发消息给 ${shortEngineLabel(engineId)}…"
}

internal fun formatAttachmentFileSize(sizeBytes: Int?): String? {
    val bytes = sizeBytes?.takeIf { it >= 0 } ?: return null
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
        else -> {
            val wholeMb = bytes / (1024 * 1024)
            val tenthMb = ((bytes % (1024 * 1024)) * 10) / (1024 * 1024)
            "$wholeMb.$tenthMb MB"
        }
    }
}

internal fun attachmentFileIcon(fileName: String, mimeType: String? = null): ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase().trim()
    val lowerMime = mimeType?.lowercase().orEmpty()
    return when {
        ext == "pdf" || lowerMime == "application/pdf" -> Icons.Rounded.PictureAsPdf
        ext in setOf("zip", "tar", "gz", "tgz", "7z", "rar") -> Icons.Rounded.FolderZip
        ext in setOf("csv", "tsv", "xls", "xlsx") -> Icons.Rounded.TableChart
        ext in setOf(
            "kt", "kts", "java", "ts", "tsx", "js", "jsx", "mjs", "cjs", "py", "go", "rs",
            "swift", "c", "h", "cpp", "hpp", "json", "jsonl", "yaml", "yml", "toml", "xml",
            "html", "htm", "css", "scss", "sql", "sh", "bash", "zsh", "gradle",
        ) -> Icons.Rounded.Code
        ext in setOf("md", "markdown", "txt", "log", "doc", "docx") -> Icons.Rounded.Description
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

@Composable
internal fun ComposerAttachedFileChip(
    file: PromptFileAttachment,
    index: Int,
    onRemove: () -> Unit,
) {
    val ext = file.fileName.substringAfterLast('.', "").uppercase().take(8)
    val sizeLabel = formatAttachmentFileSize(file.sizeBytes)
    val subtitle = listOfNotNull(ext.takeIf { it.isNotEmpty() && it != file.fileName.uppercase() }, sizeLabel)
        .joinToString(" · ")
        .ifEmpty { "文件" }

    Surface(
        shape = RoundedCornerShape(11.dp),
        color = Everforest.Bg2,
        border = BorderStroke(1.dp, Everforest.Aqua.copy(alpha = 0.42f)),
        modifier = Modifier
            .height(50.dp)
            .widthIn(min = 124.dp, max = 196.dp)
            .testTag("composer_file_chip_$index"),
    ) {
        Row(
            modifier = Modifier.padding(start = 9.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Everforest.Bg1),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = attachmentFileIcon(file.fileName, file.mimeType),
                    contentDescription = null,
                    tint = Everforest.Aqua,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = file.fileName,
                    fontSize = 11.5.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Everforest.Fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    fontSize = 9.5.sp,
                    lineHeight = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Everforest.CommentGrey,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = CircleShape,
                color = Everforest.BgDim.copy(alpha = 0.85f),
                border = BorderStroke(0.8.dp, Everforest.Bg3),
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable { onRemove() }
                    .testTag("composer_remove_file_$index"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "移除文件",
                        tint = Everforest.Fg,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun TimelineAttachedFileChip(
    file: MessageFileSummary,
    messageId: String,
    index: Int,
    onClick: (() -> Unit)? = null,
) {
    val sizeLabel = formatAttachmentFileSize(file.sizeBytes)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg3),
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(10.dp))
            .let { mod -> if (onClick != null) mod.clickable(onClick = onClick) else mod }
            .testTag("user_bubble_file_${messageId}_$index"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = attachmentFileIcon(file.fileName, file.mimeType),
                contentDescription = null,
                tint = Everforest.Aqua,
                modifier = Modifier.size(15.dp),
            )
            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = file.fileName,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Everforest.Fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sizeLabel != null) {
                    Text(
                        text = sizeLabel,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CommentGrey,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    active: Boolean = false,
    activeTint: Color = tint,
    iconSize: Dp = 16.dp,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (active) activeTint.copy(alpha = 0.16f) else Everforest.Bg2,
        border = if (active) BorderStroke(1.dp, activeTint.copy(alpha = 0.45f)) else null,
        modifier = Modifier
            .size(30.dp)
            .let { mod -> if (testTag != null) mod.testTag(testTag) else mod }
            .clip(CircleShape)
            .clickable { onClick() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) activeTint else tint,
                modifier = Modifier.size(iconSize),
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
    iconContentDescription: String? = null,
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
                Icon(icon, contentDescription = iconContentDescription, tint = tint, modifier = Modifier.size(13.dp))
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
