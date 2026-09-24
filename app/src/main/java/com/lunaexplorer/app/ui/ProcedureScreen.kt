package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.BrowserState

internal class ProcedureScreenBack {
    var available by mutableStateOf(false)
        private set
    // Editor callbacks change on every composition; only availability triggers recomposition.
    var action: (() -> Unit)? = null
        set(value) {
            field = value
            available = value != null
        }
}

@Composable
internal fun ProcedureScreen(
    state: BrowserState,
    procedures: Procedures,
    actions: LunaActions,
    back: ProcedureScreenBack,
) {
    Column(Modifier.fillMaxSize().testTag("procedure-screen").fastVerticalScroll(rememberScrollState()).padding(20.dp)) {
        ProcedureSettings(state, procedures, actions) { back.action = it }
    }
}
