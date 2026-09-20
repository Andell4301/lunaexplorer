package com.lunaexplorer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Two fixed heights rather than an anchored draggable sheet, so a sheet drag never competes with
 * the content's scrolling.
 */
@Composable
internal fun OpenChooserSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val expandOnSwipe = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!expanded && source == NestedScrollSource.UserInput && available.y < 0) {
                    expanded = true
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val cap = maxHeight * if (expanded) .88f else .5f
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = onDismiss,
            ))
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                    .heightIn(max = cap)
                    .semantics {
                        paneTitle = "Open file"
                        stateDescription = if (expanded) "Expanded" else "Compact"
                        dismiss { latestDismiss(); true }
                    }
                    .pointerInput(Unit) { detectTapGestures { /* Taps on the sheet must not reach the dismiss scrim. */ } }
                    .nestedScroll(expandOnSwipe),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    Box(
                        Modifier.fillMaxWidth().height(44.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "Resize open menu"
                                if (expanded) collapse { expanded = false; true }
                                else expand { expanded = true; true }
                            }
                            .clickable { expanded = !expanded }
                            .pointerInput(expanded) {
                                var distance = 0f
                                detectVerticalDragGestures(
                                    onDragStart = { distance = 0f },
                                    onDragEnd = {
                                        when {
                                            distance < -24.dp.toPx() -> expanded = true
                                            distance > 24.dp.toPx() -> if (expanded) expanded = false else latestDismiss()
                                        }
                                    },
                                    onVerticalDrag = { change, amount -> change.consume(); distance += amount },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(width = 32.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f), RoundedCornerShape(2.dp)))
                    }
                    content()
                }
            }
        }
    }
}
