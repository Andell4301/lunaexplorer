package com.lunaexplorer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
internal fun ToolSelection(viewModel: BrowserViewModel?, selected: Boolean, onClear: () -> Unit) {
    val owner = remember { Any() }
    val clear = rememberUpdatedState(onClear)
    DisposableEffect(viewModel, owner, selected) {
        if (selected) viewModel?.registerToolSelection(owner) { clear.value() }
        onDispose { viewModel?.registerToolSelection(owner, null) }
    }
    if (viewModel == null) BackHandler(enabled = selected) { clear.value() }
}
