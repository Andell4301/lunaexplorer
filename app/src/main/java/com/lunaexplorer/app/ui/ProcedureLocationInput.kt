package com.lunaexplorer.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.core.ProcedureLocation

internal data class ProcedurePlaceInput(
    val text: String = "",
    val location: ProcedureLocation? = null,
    val children: String = "",
)

internal fun Procedures.input(location: ProcedureLocation, pickedLabel: String? = null): ProcedurePlaceInput {
    val path = shownPath(location)
    return if (path != null) ProcedurePlaceInput(text = path, location = location) else ProcedurePlaceInput(
        text = pickedLabel ?: "${location.ref.provider}: Selected item",
        location = ProcedureLocation(location.ref),
        children = location.children.joinToString("/"),
    )
}

internal suspend fun Procedures.resolve(input: ProcedurePlaceInput, allowMissing: Boolean = false): ProcedureLocation {
    val base = input.location ?: resolve(input.text, allowMissing)
    val selected = base.copy(children = base.children + if (input.children.isEmpty()) emptyList() else input.children.split('/'))
    return resolve(selected, allowMissing)
}

@Composable
internal fun ProcedurePlaceField(
    label: String,
    input: ProcedurePlaceInput,
    procedures: Procedures,
    onChange: (ProcedurePlaceInput) -> Unit,
    onBrowse: () -> Unit,
) {
    val saved = input.location
    val opaque = saved != null && procedures.shownPath(saved) == null
    var selectedLabel by remember(saved, procedures) { mutableStateOf<String?>(null) }
    LaunchedEffect(saved, procedures) {
        if (saved != null && opaque) {
            selectedLabel = procedures.label(saved)
        }
    }
    if (!opaque) {
        OutlinedTextField(input.text, { onChange(ProcedurePlaceInput(text = it)) }, label = { Text(label) },
            trailingIcon = { ProcedureBrowseButton(label, onBrowse) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("procedure-place-$label"))
        return
    }
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (focused) 2.dp else 1.dp,
            if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selectedLabel ?: input.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                ToolIcon(Icons.Outlined.Clear, "Clear $label selection", onClick = { onChange(ProcedurePlaceInput()) })
                ProcedureBrowseButton(label, onBrowse)
            }
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("/", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = input.children,
                    onValueChange = { onChange(input.copy(children = it)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
                        .testTag("procedure-place-$label").semantics { contentDescription = label },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.children.isEmpty()) Text("Relative path (optional)",
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            field()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProcedureBrowseButton(label: String, onBrowse: () -> Unit) {
    TextButton(onClick = onBrowse, modifier = Modifier.semantics { contentDescription = "Browse $label" }) {
        Text("Browse")
    }
}
