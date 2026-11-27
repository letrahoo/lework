package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lework.platform.canInstallDesktopDmgUpdate
import dev.lework.platform.isDesktopPlatform
import dev.lework.platform.platformName
import dev.lework.store.AppUpdateState
import dev.lework.store.extractReleaseHighlightSummary
import dev.lework.store.formatReleasePublishedDate

@Composable
internal fun SidebarAppUpdateBanner(
    updateState: AppUpdateState,
    onOpenUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val release = updateState.latestRelease ?: return
    if (!updateState.hasVisibleBanner) return
    val summary = remember(release.releaseNotes) { extractReleaseHighlightSummary(release.releaseNotes) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.48f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpenUpdate() }
            .testTag("sidebar_update_banner"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdateAlt,
                contentDescription = null,
                tint = Everforest.Green,
                modifier = Modifier.size(16.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "发现新版本 ${release.tagName}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.Green,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = updateState.installStatusText
                        ?: summary
                        ?: "当前 ${updateState.currentVersion} · 点击查看并更新",
                    fontSize = 10.5.sp,
                    color = Everforest.Grey2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(22.dp)
                    .testTag("sidebar_update_dismiss_btn"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "暂不提示此版本",
                    tint = Everforest.CommentGrey,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
internal fun DesktopAppUpdateSection(
    updateState: AppUpdateState,
    onCheckForUpdates: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
) {
    val latest = updateState.latestRelease
    val badgeBg = when {
        updateState.hasUpdate -> Everforest.BgGreen
        updateState.checking -> Everforest.Bg2
        else -> Everforest.Bg1
    }
    val badgeBorder = when {
        updateState.hasUpdate -> Everforest.Green.copy(alpha = 0.45f)
        updateState.checking -> Everforest.BoldGold.copy(alpha = 0.45f)
        else -> Everforest.Bg3
    }
    val badgeColor = when {
        updateState.hasUpdate -> Everforest.Green
        updateState.checking -> Everforest.BoldGold
        else -> Everforest.Grey1
    }
    val badgeText = when {
        updateState.checking -> "检查中…"
        updateState.hasUpdate && latest != null -> "有新版本 ${latest.tagName}"
        updateState.lastCheckedAtMs > 0L && updateState.checkError == null -> "已是最新"
        else -> updateState.currentVersion
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Everforest.Bg0,
        border = BorderStroke(
            1.dp,
            if (updateState.hasUpdate) Everforest.Green.copy(alpha = 0.45f) else Everforest.Bg2,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_app_update_section"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdateAlt,
                        contentDescription = null,
                        tint = if (updateState.hasUpdate) Everforest.Green else Everforest.BoldGold,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "软件更新",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Everforest.Fg,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeBorder),
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            val detailText = when {
                updateState.checking -> "正在检查 GitHub Releases 最新版本…"
                updateState.hasUpdate && latest != null -> {
                    val date = formatReleasePublishedDate(latest.publishedAt)?.let { " · 发布于 $it" }.orEmpty()
                    "当前版本 ${updateState.currentVersion}，可升级至 ${latest.tagName}$date"
                }
                updateState.lastCheckedAtMs > 0L && updateState.checkError == null ->
                    "当前版本 ${updateState.currentVersion} 已是最新正式版本"
                else -> "当前版本 ${updateState.currentVersion} · 自动检查 GitHub Releases 新版本"
            }
            Text(
                text = detailText,
                fontSize = 11.5.sp,
                color = Everforest.Grey2,
                lineHeight = 16.5.sp,
            )

            updateState.checkError?.let { err ->
                Text(
                    text = err,
                    fontSize = 11.sp,
                    color = Everforest.Red,
                    lineHeight = 15.5.sp,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (updateState.hasUpdate && latest != null) {
                    Button(
                        onClick = onOpenUpdateDialog,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Everforest.Green,
                            contentColor = Everforest.BgDim,
                        ),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("settings_btn_open_update_dialog"),
                    ) {
                        Text(
                            text = "查看更新并安装",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    enabled = !updateState.checking && !updateState.installing,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, Everforest.Bg3),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("settings_btn_check_update"),
                ) {
                    if (updateState.checking) {
                        CircularProgressIndicator(
                            color = Everforest.BoldGold,
                            strokeWidth = 1.6.dp,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = Everforest.Grey2,
                            modifier = Modifier.size(13.5.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = if (updateState.checking) "检查中…" else "检查更新",
                        fontSize = 12.sp,
                        color = Everforest.Fg,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AppUpdateDialog(
    updateState: AppUpdateState,
    onCheckAgain: () -> Unit,
    onStartUpdate: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onDismissVersion: () -> Unit,
    onClose: () -> Unit,
) {
    val release = updateState.latestRelease
    val isDesktop = remember { isDesktopPlatform() }
    val canDirectDmgInstall = remember { canInstallDesktopDmgUpdate() }
    val isAndroid = remember { platformName().contains("android", ignoreCase = true) }

    ModalEscapeEffect(onDismiss = onClose)

    Dialog(
        onDismissRequest = { if (!updateState.installing) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Everforest.Bg1,
            border = BorderStroke(1.dp, Everforest.Bg3),
            shadowElevation = 16.dp,
            modifier = Modifier
                .widthIn(min = 320.dp, max = if (isDesktop) 560.dp else 440.dp)
                .fillMaxWidth(if (isDesktop) 0.92f else 0.94f)
                .heightIn(max = 640.dp)
                .dialogEscapeDismiss(onDismiss = { if (!updateState.installing) onClose() })
                .testTag("app_update_dialog"),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = if (updateState.hasUpdate) {
                                Icons.Rounded.SystemUpdateAlt
                            } else {
                                Icons.Rounded.CheckCircleOutline
                            },
                            contentDescription = null,
                            tint = Everforest.Green,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = when {
                                    updateState.checking && release == null -> "正在检查更新…"
                                    updateState.hasUpdate && release != null -> "发现新版本 ${release.tagName}"
                                    else -> "软件更新"
                                },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.Fg,
                            )
                            val subtitle = when {
                                updateState.hasUpdate && release != null -> {
                                    val date = formatReleasePublishedDate(release.publishedAt)
                                        ?.let { " · 发布于 $it" }
                                        .orEmpty()
                                    "${updateState.currentVersion} → ${release.tagName}$date"
                                }
                                updateState.checking -> "正在连接 GitHub Releases…"
                                else -> "当前版本 ${updateState.currentVersion}"
                            }
                            Text(
                                text = subtitle,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Everforest.CommentGrey,
                            )
                        }
                    }
                    IconButton(
                        onClick = { if (!updateState.installing) onClose() },
                        enabled = !updateState.installing,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("app_update_close_btn"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = Everforest.Grey1,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                HorizontalDivider(color = Everforest.Bg2)

                if (updateState.checking && release == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = Everforest.Green,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "正在检查最新正式版本…",
                            fontSize = 13.sp,
                            color = Everforest.Grey2,
                        )
                    }
                } else if (updateState.checkError != null && !updateState.hasUpdate) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Everforest.BgRed,
                        border = BorderStroke(1.dp, Everforest.Red.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = updateState.checkError,
                            fontSize = 12.5.sp,
                            color = Everforest.Red,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else if (!updateState.hasUpdate) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Everforest.Bg0,
                        border = BorderStroke(1.dp, Everforest.Bg2),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "当前已是最新版本",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Everforest.Green,
                            )
                            Text(
                                text = "已安装版本 ${updateState.currentVersion}${release?.let { "，最新正式版为 ${it.tagName}" }.orEmpty()}。",
                                fontSize = 12.sp,
                                color = Everforest.Grey2,
                            )
                        }
                    }
                } else if (release != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Everforest.Bg0,
                        border = BorderStroke(1.dp, Everforest.Bg2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(min = 120.dp, max = 340.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                        ) {
                            val notes = release.releaseNotes.ifBlank {
                                "新版本 **${release.tagName}** 已发布，点击下方按钮即可获取最新安装包。"
                            }
                            EverforestMarkdownContent(markdown = notes)
                        }
                    }

                    if (updateState.installing || !updateState.installStatusText.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Everforest.Bg0,
                            border = BorderStroke(1.dp, Everforest.Green.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("app_update_progress_box"),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = updateState.installStatusText ?: "正在准备更新…",
                                    fontSize = 12.sp,
                                    color = Everforest.Fg,
                                )
                                if (updateState.installing) {
                                    val progress = updateState.installProgressFraction
                                    if (progress != null) {
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            color = Everforest.Green,
                                            trackColor = Everforest.Bg2,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            color = Everforest.Green,
                                            trackColor = Everforest.Bg2,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    updateState.installError?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Everforest.BgRed,
                            border = BorderStroke(1.dp, Everforest.Red.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("app_update_error_box"),
                        ) {
                            Text(
                                text = err,
                                fontSize = 12.sp,
                                color = Everforest.Red,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(color = Everforest.Bg2)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (updateState.hasUpdate && release != null) {
                            TextButton(
                                onClick = onDismissVersion,
                                enabled = !updateState.installing,
                                modifier = Modifier.testTag("app_update_dismiss_version_btn"),
                            ) {
                                Text("暂不提示此版本", fontSize = 12.sp, color = Everforest.Grey1)
                            }
                        } else {
                            TextButton(
                                onClick = onCheckAgain,
                                enabled = !updateState.checking,
                            ) {
                                Text("重新检查", fontSize = 12.sp, color = Everforest.Grey1)
                            }
                        }
                        TextButton(
                            onClick = onOpenReleasePage,
                            modifier = Modifier.testTag("app_update_open_release_page_btn"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = Everforest.Grey2,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("GitHub 发布页", fontSize = 12.sp, color = Everforest.Grey2)
                        }
                    }

                    if (updateState.hasUpdate && release != null) {
                        val actionLabel = when {
                            updateState.installing -> "正在更新…"
                            isDesktop && canDirectDmgInstall && release.macDmgAsset != null -> "下载并安装更新"
                            isAndroid && release.androidApkAsset != null -> "下载新版 APK"
                            else -> "前往下载更新"
                        }
                        Button(
                            onClick = onStartUpdate,
                            enabled = !updateState.installing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Everforest.Green,
                                contentColor = Everforest.BgDim,
                            ),
                            modifier = Modifier.testTag("app_update_primary_action_btn"),
                        ) {
                            if (updateState.installing) {
                                CircularProgressIndicator(
                                    color = Everforest.BgDim,
                                    strokeWidth = 1.8.dp,
                                    modifier = Modifier.size(13.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = actionLabel,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        Button(
                            onClick = onClose,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Everforest.Bg2,
                                contentColor = Everforest.Fg,
                            ),
                        ) {
                            Text("关闭", fontSize = 12.5.sp)
                        }
                    }
                }
            }
        }
    }
}
