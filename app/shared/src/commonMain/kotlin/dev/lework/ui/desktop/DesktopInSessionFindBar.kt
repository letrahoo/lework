package dev.lework.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.store.ConsoleUiState
import dev.lework.ui.Everforest

@Composable
internal fun DesktopInSessionFindBar(
    state: ConsoleUiState,
    openNonce: Int = 0,
    onQueryChange: (String) -> Unit,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var findFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.inSessionFindQuery,
                selection = TextRange(0, state.inSessionFindQuery.length),
            ),
        )
    }
    var lastSyncedExternalQuery by remember { mutableStateOf(state.inSessionFindQuery) }
    if (state.inSessionFindQuery != lastSyncedExternalQuery) {
        lastSyncedExternalQuery = state.inSessionFindQuery
        if (findFieldValue.text != state.inSessionFindQuery) {
            findFieldValue = TextFieldValue(
                text = state.inSessionFindQuery,
                selection = TextRange(0, state.inSessionFindQuery.length),
            )
        }
    }
    LaunchedEffect(state.inSessionFindOpen, openNonce) {
        if (state.inSessionFindOpen) {
            withFrameNanos {}
            runCatching { focusRequester.requestFocus() }
            if (findFieldValue.text.isNotEmpty()) {
                withFrameNanos {}
                findFieldValue = findFieldValue.copy(
                    selection = TextRange(0, findFieldValue.text.length),
                )
            }
        }
    }

    val total = state.inSessionFindMatches.size
    val activeHumanIdx = if (total > 0) (state.inSessionFindActiveIndex.coerceIn(0, total - 1) + 1) else 0

    Surface(
        shape = RoundedCornerShape(11.dp),
        color = Everforest.Bg1,
        border = BorderStroke(1.dp, Everforest.Bg3),
        shadowElevation = 10.dp,
        modifier = modifier
            .widthIn(min = 320.dp, max = 420.dp)
            .testTag("desktop_in_session_find_bar")
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Escape -> {
                        onClose()
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        if (ev.isShiftPressed) onPrevMatch() else onNextMatch()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "会话内查找",
                tint = Everforest.Green,
                modifier = Modifier.size(15.dp),
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.inSessionFindQuery.isEmpty()) {
                    Text(
                        text = "在当前会话中查找…",
                        fontSize = 12.sp,
                        color = Everforest.Grey0,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = findFieldValue,
                    onValueChange = { next ->
                        val textChanged = next.text != findFieldValue.text
                        findFieldValue = next
                        if (textChanged) {
                            onQueryChange(next.text)
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 12.5.sp,
                        color = Everforest.Fg,
                    ),
                    cursorBrush = SolidColor(Everforest.Green),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("desktop_in_session_find_input"),
                )
            }

            if (state.inSessionFindQuery.isNotBlank()) {
                if (total > 0 || !state.inSessionFindLoading) {
                    Text(
                        text = if (total > 0) "$activeHumanIdx / $total" else "无匹配",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (total > 0) Everforest.BoldGold else Everforest.CommentGrey,
                        modifier = Modifier.testTag("desktop_in_session_find_counter"),
                    )
                }
                if (state.inSessionFindLoading) {
                    CircularProgressIndicator(
                        color = Everforest.BoldGold,
                        strokeWidth = 1.6.dp,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }

            LeworkIconActionButton(
                icon = Icons.Rounded.KeyboardArrowUp,
                tooltip = "上一处匹配",
                shortcut = "⇧↵",
                size = 24.dp,
                iconSize = 16.dp,
                enabled = total > 0,
                testTag = "desktop_in_session_find_prev",
                onClick = onPrevMatch,
            )
            LeworkIconActionButton(
                icon = Icons.Rounded.KeyboardArrowDown,
                tooltip = "下一处匹配",
                shortcut = "↵",
                size = 24.dp,
                iconSize = 16.dp,
                enabled = total > 0,
                testTag = "desktop_in_session_find_next",
                onClick = onNextMatch,
            )
            LeworkIconActionButton(
                icon = Icons.Rounded.Close,
                tooltip = "关闭查找",
                shortcut = "Esc",
                size = 24.dp,
                iconSize = 14.dp,
                testTag = "desktop_in_session_find_close",
                onClick = onClose,
            )
        }
    }
}
