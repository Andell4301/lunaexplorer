package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.QueueItem
import java.util.Locale

@Composable
internal fun VideoClipSaveStatus(
    operationId: String,
    fileName: String,
    destination: String,
    operation: QueueItem?,
    onDismiss: () -> Unit,
    onOperations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previous by remember(operationId) { mutableStateOf<QueueItem?>(null) }
    val current = operation?.takeIf { it.id == operationId }
    SideEffect { if (current != null) previous = current }
    val item = current ?: previous
    val status = item?.status?.uppercase(Locale.ROOT) ?: "QUEUED"
    val waiting = status == "QUEUED" || status == "RUNNING"
    val failed = status in setOf("FAILED", "PARTIAL", "INTERRUPTED")
    val title = when (status) {
        "QUEUED" -> "Clip queued"
        "RUNNING" -> "Saving clip…"
        "SUCCEEDED" -> "Clip saved"
        "CONFLICT" -> "Name conflict"
        "CANCELLED" -> "Clip cancelled"
        "INTERRUPTED" -> "Saving interrupted"
        "PARTIAL" -> "Clip needs attention"
        "RESOLVED" -> "Continue in Operations"
        "FAILED" -> "Could not save clip"
        else -> "Clip status unavailable"
    }
    val detail = when (status) {
        "SUCCEEDED", "RUNNING" -> null
        "FAILED", "PARTIAL" -> item?.results?.lastOrNull { it.isNotBlank() }
            ?: item?.detail?.takeIf { it.isNotBlank() }
        else -> item?.detail?.takeIf { it.isNotBlank() }
    }
    Column(modifier.fillMaxSize().fastVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(fileName, style = MaterialTheme.typography.titleMedium)
        Text(destination, style = MaterialTheme.typography.bodyMedium)
        if (waiting) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("clipSavingProgress"))
        if (item != null && item.bytes > 0) Text("${formatBytes(item.bytes)} transferred",
            Modifier.testTag("clipSaveBytes"), style = MaterialTheme.typography.bodySmall)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOperations) { Text("Operations") }
            Button(onClick = onDismiss) { Text(if (waiting) "Close" else "Done") }
        }
    }
}
