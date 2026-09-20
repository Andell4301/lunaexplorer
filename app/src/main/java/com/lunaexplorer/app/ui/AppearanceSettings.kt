@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.lunaexplorer.app.model.Accent
import com.lunaexplorer.app.model.HIDDEN_FADE_PERCENT
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.model.SyntaxScheme
import com.lunaexplorer.app.model.ThemeMode
import kotlin.math.round

@Composable
internal fun AppearanceSettings(preferences: Preferences, onChange: (Preferences) -> Unit) {
    ThemeMode.entries.forEach { mode ->
        ChoiceRow(
            when (mode) { ThemeMode.SYSTEM -> "Follow system"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" },
            mode == preferences.theme,
        ) { onChange(preferences.copy(theme = mode)) }
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Accent.entries.forEach { accent ->
            FilterChip(
                selected = preferences.accent == accent,
                onClick = { onChange(preferences.copy(accent = accent)) },
                label = { Text(accent.label) },
            )
        }
    }
    if (preferences.accent == Accent.CUSTOM) {
        Spacer(Modifier.height(10.dp))
        ColourPicker(preferences.accentSeed) { onChange(preferences.copy(accentSeed = it)) }
    }

    Spacer(Modifier.height(10.dp))
    SwitchRow("Colorful icons", preferences.colorfulIcons) { onChange(preferences.copy(colorfulIcons = it)) }

    SectionHeading("Interface size")
    PercentSetting("Size", "Interface size", round(preferences.uiScale * 100).toInt(), SIZE_PERCENT) {
        onChange(preferences.copy(uiScale = it / 100f))
    }
    SizePreview(preferences.uiScale)
    Text("Pinch with two fingers anywhere to change this. The preview shows the new size.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    SectionHeading("Hidden items")
    PercentSetting("Icon fade", "Hidden item icon fade", preferences.hiddenIconFade, HIDDEN_FADE_PERCENT) {
        onChange(preferences.copy(hiddenIconFade = it))
    }
    Spacer(Modifier.height(8.dp))
    PercentSetting("Text fade", "Hidden item text fade", preferences.hiddenTextFade, HIDDEN_FADE_PERCENT) {
        onChange(preferences.copy(hiddenTextFade = it))
    }
    HiddenPreview(preferences)

    SectionHeading("Code colors")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SyntaxScheme.entries.forEach { scheme ->
            FilterChip(
                selected = preferences.syntaxScheme == scheme,
                onClick = { onChange(preferences.copy(syntaxScheme = scheme)) },
                label = { Text(scheme.label) },
            )
        }
    }
    SyntaxPreview(preferences.syntaxScheme)
    Spacer(Modifier.height(32.dp))
}

/** Colored by hand, not tokenized: the sample must show every token class of the chosen scheme at once. */
@Composable
private fun SyntaxPreview(scheme: SyntaxScheme) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val sample = remember(scheme, dark) {
        val theme = scheme.theme(dark)
        buildAnnotatedString {
            fun put(text: String, rgb: Int? = null) {
                if (rgb == null) append(text) else withStyle(SpanStyle(color = Color(rgb or 0xFF000000.toInt()))) { append(text) }
            }
            put("// Phase of the moon\n", theme.comment)
            put("@Composable\n", theme.metadata)
            put("fun ", theme.keyword); put("phase"); put("(", theme.mark); put("day"); put(": ", theme.punctuation)
            put("Int"); put(")", theme.mark); put(": ", theme.punctuation); put("String "); put("=\n", theme.mark)
            put("    if ", theme.keyword); put("(", theme.mark); put("day "); put("< ", theme.mark); put("15", theme.literal)
            put(") ", theme.mark); put("\"waxing\"", theme.string); put(" else ", theme.keyword)
            put("\"waning\"", theme.string)
        }
    }
    Surface(
        Modifier.padding(top = 10.dp).fillMaxWidth().testTag("syntaxPreview"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(sample, Modifier.padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

private val SIZE_PERCENT = 70..160
private const val PERCENT_STEP = 5

@Composable
private fun PercentSetting(label: String, description: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        var typed by remember { mutableStateOf(value.toString()) }
        LaunchedEffect(value) { typed = value.toString() }
        OutlinedTextField(
            value = typed,
            onValueChange = { text ->
                val digits = text.filter { it.isDigit() }.take(3)
                typed = digits
                // Below the range may be an unfinished number. Above it cannot be, and must not leave
                // the prefix that was typed on the way there as the setting.
                digits.toIntOrNull()?.takeIf { it >= range.first }?.let { onChange(it.coerceAtMost(range.last)) }
            },
            label = { Text(label) },
            suffix = { Text("%") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(124.dp)
                .onFocusChanged { if (!it.isFocused) typed = value.toString() }
                .semantics { contentDescription = description },
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(round(it / PERCENT_STEP).toInt() * PERCENT_STEP) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / PERCENT_STEP - 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HiddenPreview(preferences: Preferences) {
    Surface(
        Modifier.padding(top = 10.dp).fillMaxWidth().testTag("hiddenPreview"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            KindIcon(Icons.Outlined.Folder, Hue.FOLDER, null, plainSize = 26.dp, badgeSize = 36.dp,
                plainTint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.alpha(1f - preferences.hiddenIconFade / 100f))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.alpha(1f - preferences.hiddenTextFade / 100f)) {
                Text(".config", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text("Folder  ·  12 items", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

// Only the preview takes the new density: rescaling the settings window would restart the
// slider's pointer input mid-drag.
@Composable
private fun SizePreview(scale: Float) {
    val base = LocalDensity.current
    Surface(
        Modifier.padding(top = 10.dp).fillMaxWidth().height(88.dp).testTag("sizePreview"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.fillMaxSize().clipToBounds().padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart) {
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindIcon(Icons.Outlined.Folder, Hue.FOLDER, null, plainSize = 26.dp, badgeSize = 36.dp,
                        plainTint = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("sizePreviewIcon"))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Downloads", style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Folder  ·  128 items", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColourPicker(seed: Int, onPick: (Int) -> Unit) {
    val hsl = remember(seed) { FloatArray(3).also { ColorUtils.colorToHSL(seed, it) } }
    var hue by remember(seed) { mutableFloatStateOf(hsl[0]) }
    var saturation by remember(seed) { mutableFloatStateOf(hsl[1]) }
    var lightness by remember(seed) { mutableFloatStateOf(hsl[2]) }
    var hex by remember(seed) { mutableStateOf("#%06X".format(seed and 0xFFFFFF)) }

    fun emit() {
        val colour = ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))
        hex = "#%06X".format(colour and 0xFFFFFF)
        onPick(colour)
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(seed)))
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = hex,
                onValueChange = { typed ->
                    hex = typed
                    val cleaned = typed.trim().removePrefix("#")
                    if (cleaned.length == 6) {
                        cleaned.toIntOrNull(16)?.let { value ->
                            val colour = value or (0xFF shl 24)
                            ColorUtils.colorToHSL(colour, hsl)
                            hue = hsl[0]; saturation = hsl[1]; lightness = hsl[2]
                            onPick(colour)
                        }
                    }
                },
                label = { Text("Hex") }, singleLine = true, modifier = Modifier.weight(1f),
            )
        }
        Text("Hue", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = hue, onValueChange = { hue = it; emit() }, valueRange = 0f..360f)
        Text("Saturation", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = saturation, onValueChange = { saturation = it; emit() }, valueRange = 0f..1f)
        Text("Lightness", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = lightness, onValueChange = { lightness = it; emit() }, valueRange = 0.15f..0.85f)
        Text("Luna keeps the hue and picks readable shades of it for text, surfaces and highlights.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
