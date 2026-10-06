package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.playback.PlaybackSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

@Composable
internal fun EmbeddedSubtitleViewer(source: PlaybackSource, track: MediaTrack, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var metadata by remember(source, track) { mutableStateOf(false) }
    val progress = remember(source, track) { MutableStateFlow<Float?>(null) }
    val fraction by progress.collectAsState()
    val loaded = remember(source, track) {
        scope.async(start = CoroutineStart.LAZY) {
            try {
                Result.success(readEmbeddedSubtitleDocument(source, requireNotNull(track.readableSubtitle)) { progress.value = it })
            } catch (error: Exception) {
                ensureActive()
                Result.failure(error)
            }
        }
    }
    DisposableEffect(loaded) { onDispose { loaded.cancel() } }
    val session = remember(loaded, metadata) {
        val showMetadata = metadata
        TextEditorSession.forText(track to metadata, track.label, scope, "This subtitle track could not be read",
            language = CodeLanguage.PLAIN_TEXT) {
            val document = loaded.await().getOrThrow()
            if (showMetadata) document.metadata else document.text
        }
    }
    DisposableEffect(session) { onDispose { session.close() } }
    TextEditorScreen(session, code = false, onDismiss, onScheme = {}, copySubject = "subtitle text", controls = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = metadata, onClick = { metadata = !metadata }, label = { Text("Metadata") })
            if (session.document == null && session.failure == null) fraction?.let { Text("${(it * 100).roundToInt()}%") }
        }
        if (session.document == null && session.failure == null) fraction?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        }
    })
}
