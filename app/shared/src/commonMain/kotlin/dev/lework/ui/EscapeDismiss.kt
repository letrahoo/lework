package dev.lework.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * LIFO stack of currently open modal dialogs / overlays so pressing `Escape` anywhere in the
 * desktop window deterministically dismisses the topmost overlay even when no child text field
 * holds keyboard focus (e.g. Settings, Workspace, Usage, Subagent, Image Lightbox, Mermaid).
 */
class ModalEscapeDispatcher {
    private data class Entry(
        val id: Long,
        val onDismiss: () -> Unit,
    )

    private var nextId: Long = 0L
    private val entries = ArrayDeque<Entry>()

    fun push(onDismiss: () -> Unit): Long {
        val id = ++nextId
        entries.addLast(Entry(id = id, onDismiss = onDismiss))
        return id
    }

    fun remove(id: Long) {
        val idx = entries.indexOfLast { it.id == id }
        if (idx >= 0) {
            entries.removeAt(idx)
        }
    }

    fun dispatchEscape(): Boolean {
        val top = entries.lastOrNull() ?: return false
        top.onDismiss()
        return true
    }

    fun hasActiveModals(): Boolean = entries.isNotEmpty()
}

val LocalModalEscapeDispatcher = staticCompositionLocalOf<ModalEscapeDispatcher?> { null }

/**
 * Registers [onDismiss] onto the enclosing [LocalModalEscapeDispatcher] while this composable
 * is active, guaranteeing LIFO `Escape` dismissal for nested dialogs as well.
 */
@Composable
fun ModalEscapeEffect(
    enabled: Boolean = true,
    onDismiss: () -> Unit,
) {
    val dispatcher = LocalModalEscapeDispatcher.current ?: return
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    if (enabled) {
        DisposableEffect(dispatcher) {
            val id = dispatcher.push { latestOnDismiss() }
            onDispose {
                dispatcher.remove(id)
            }
        }
    }
}

/**
 * Makes a dialog container focusable so `Escape` key events work reliably even when the dialog
 * has no auto-focused text field or is hosted outside a Desktop `Window` (e.g. headless UI tests).
 */
fun Modifier.dialogEscapeDismiss(
    onDismiss: () -> Unit,
    autoRequestFocus: Boolean = true,
): Modifier = composed {
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val focusRequester = remember { FocusRequester() }
    if (autoRequestFocus) {
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    this
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                latestOnDismiss()
                true
            } else {
                false
            }
        }
}
