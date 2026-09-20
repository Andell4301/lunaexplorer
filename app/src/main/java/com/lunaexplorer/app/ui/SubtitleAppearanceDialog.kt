package com.lunaexplorer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.model.SubtitleEdge
import com.lunaexplorer.app.model.SubtitleFont
import com.lunaexplorer.app.model.SubtitlePosition
import kotlin.math.roundToInt

@Composable
internal fun SubtitleAppearanceDialog(
    appearance: SubtitleAppearance,
    onChange: (SubtitleAppearance) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitle appearance") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Preview", style = MaterialTheme.typography.labelMedium)
                SubtitleAppearancePreview(appearance, Modifier.fillMaxWidth().weight(.3f))
                Column(Modifier.fillMaxWidth().weight(.7f).fastVerticalScroll(rememberScrollState()).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubtitleAppearanceControls(appearance, onChange)
                }
            }
        },
        dismissButton = { TextButton(onClick = { onChange(SubtitleAppearance()) }) { Text("Reset appearance") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun SubtitleAppearancePreview(appearance: SubtitleAppearance, modifier: Modifier = Modifier) {
    val sample = remember { listOf(Cue.Builder().setText("A little closer\nto the stars.").build()) }
    AndroidView(
        factory = { LunaSubtitleView(it, previewTextSizeSp = 18f).apply { setCues(sample) } },
        update = { it.appearance = appearance },
        modifier = modifier
            .background(Brush.verticalGradient(listOf(Color(0xFF26384B), Color(0xFF576A70), Color(0xFF1B2532))))
            .semantics { contentDescription = "Subtitle appearance preview" },
    )
}

@Composable
internal fun SubtitleAppearanceControls(appearance: SubtitleAppearance, onChange: (SubtitleAppearance) -> Unit) {
    AppearanceSwitch("Custom appearance", appearance.enabled) { onChange(appearance.copy(enabled = it)) }
    Text(if (appearance.enabled) "Your settings override text subtitles’ fonts, colors and sizes. Picture subtitles keep their appearance."
        else "Using the subtitle file’s styling and the device’s caption defaults. Turn on Custom appearance to use your own settings.",
        style = MaterialTheme.typography.bodySmall)
    Text("Changes apply immediately and are saved for all videos.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    AppearanceSlider("Text size", appearance.textSizePercent, 50..200, 5, appearance.enabled) {
        onChange(appearance.copy(textSizePercent = it))
    }
    AppearanceChoices("Font", SubtitleFont.entries, appearance.font, appearance.enabled, { it.label }) {
        onChange(appearance.copy(font = it))
    }
    AppearanceSwitch("Bold text", appearance.bold, appearance.enabled) { onChange(appearance.copy(bold = it)) }
    AppearanceColor("Text color", appearance.textColor, appearance.enabled) { onChange(appearance.copy(textColor = it)) }
    HorizontalDivider()
    AppearanceChoices("Text edge", SubtitleEdge.entries, appearance.edge, appearance.enabled, { it.label }) {
        onChange(appearance.copy(edge = it))
    }
    if (appearance.edge != SubtitleEdge.NONE) {
        AppearanceColor("Edge color", appearance.edgeColor, appearance.enabled) { onChange(appearance.copy(edgeColor = it)) }
        if (appearance.edge == SubtitleEdge.OUTLINE) {
            AppearanceSlider("Outline thickness", appearance.outlineThicknessPercent,
                OUTLINE_THICKNESS, 10, appearance.enabled) {
                onChange(appearance.copy(outlineThicknessPercent = it))
            }
        }
    }
    AppearanceColor("Background color", appearance.backgroundColor, appearance.enabled) {
        onChange(appearance.copy(backgroundColor = it))
    }
    AppearanceSlider("Background opacity", appearance.backgroundOpacityPercent, 0..100, 10, appearance.enabled) {
        onChange(appearance.copy(backgroundOpacityPercent = it))
    }
    HorizontalDivider()
    AppearanceChoices("Position", SubtitlePosition.entries, appearance.position, appearance.enabled, { it.label }) {
        onChange(appearance.copy(position = it))
    }
    AppearanceSlider("Vertical margin", appearance.verticalMarginPercent, 0..30, 1, appearance.enabled) {
        onChange(appearance.copy(verticalMarginPercent = it))
    }
    Text(if (appearance.position == SubtitlePosition.FROM_FILE)
        "Keeps positions set by the subtitle file. The margin moves subtitles that have no position."
        else "Places text subtitles at the selected edge. The margin moves them away from that edge.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AppearanceSwitch(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked = value, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun AppearanceSlider(label: String, value: Int, range: IntRange, step: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value%", style = MaterialTheme.typography.titleSmall)
        Slider(value = value.coerceIn(range).toFloat(), onValueChange = { onChange((it / step).roundToInt() * step) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first) / step - 1,
            enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun <T> AppearanceChoices(label: String, options: List<T>, selected: T, enabled: Boolean, name: (T) -> String, onChange: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().fastHorizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, enabled = enabled, onClick = { onChange(option) }, label = { Text(name(option)) })
            }
        }
    }
}

private val subtitleColors = listOf(
    "White" to 0xFFFFFFFF.toInt(), "Yellow" to 0xFFFFFF00.toInt(), "Black" to 0xFF000000.toInt(),
    "Cyan" to 0xFF00FFFF.toInt(), "Green" to 0xFF00FF00.toInt(), "Pink" to 0xFFFF80AB.toInt(),
)

private val subtitleColorHex = Regex("#?[0-9a-fA-F]{6}")

private fun parseSubtitleColorHex(value: String): Int? =
    value.takeIf { subtitleColorHex.matches(it) }?.removePrefix("#")?.toInt(16)?.or(0xFF000000.toInt())

@Composable
private fun AppearanceColor(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var hex by remember(value) { mutableStateOf("#%06X".format(value and 0xFFFFFF)) }
    val parsed = parseSubtitleColorHex(hex)
    val showCustom = custom || subtitleColors.none { it.second == value }
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().fastHorizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            subtitleColors.forEach { (name, color) ->
                FilterChip(selected = value == color, enabled = enabled, onClick = { onChange(color) },
                    modifier = Modifier.semantics { contentDescription = "$label: $name" },
                    leadingIcon = {
                        Spacer(Modifier.size(16.dp).background(Color(color), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    }, label = { Text(name) })
            }
            FilterChip(selected = showCustom, enabled = enabled,
                onClick = { custom = !custom }, modifier = Modifier.semantics { contentDescription = "$label: Custom" },
                label = { Text("Custom") })
        }
        if (showCustom) {
            OutlinedTextField(value = hex, onValueChange = { typed ->
                hex = typed
                parseSubtitleColorHex(typed)?.let(onChange)
            }, label = { Text("$label hex") }, supportingText = { Text("#RRGGBB") },
                isError = parsed == null, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}
