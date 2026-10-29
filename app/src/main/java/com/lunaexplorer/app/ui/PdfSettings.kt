@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.PdfDirection
import com.lunaexplorer.app.model.PdfPageLayout
import com.lunaexplorer.app.model.Preferences

@Composable
internal fun PdfSettings(
    preferences: Preferences,
    onChange: (Preferences) -> Unit,
    fullScreen: Boolean = preferences.pdfFullScreen,
    onFullScreenChange: ((Boolean) -> Unit)? = null,
) {
    if (onFullScreenChange == null) {
        SwitchRow("Full screen", preferences.pdfFullScreen) {
            onChange(preferences.copy(pdfFullScreen = it))
        }
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 40.dp)
                .combinedClickable(onClick = { onFullScreenChange(!fullScreen) }, role = Role.Checkbox),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = fullScreen, onCheckedChange = onFullScreenChange)
                Text("Full screen", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { onChange(preferences.copy(pdfFullScreen = fullScreen)) },
                enabled = fullScreen != preferences.pdfFullScreen,
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Remember") }
        }
    }
    SwitchRow("Tap to turn pages", preferences.pdfTapToTurn) {
        onChange(preferences.copy(pdfTapToTurn = it))
    }
    SwitchRow("Swipe to turn pages", preferences.pdfSwipeToTurn) {
        onChange(preferences.copy(pdfSwipeToTurn = it))
    }
    Spacer(Modifier.height(16.dp))
    Text("Direction", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    PdfDirection.entries.forEach { direction ->
        ChoiceRow(direction.label, preferences.pdfDirection == direction) {
            onChange(preferences.copy(pdfDirection = direction))
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("Layout", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    PdfPageLayout.entries.forEach { layout ->
        ChoiceRow(layout.label, preferences.pdfPageLayout == layout) {
            onChange(preferences.copy(pdfPageLayout = layout))
        }
    }
}
