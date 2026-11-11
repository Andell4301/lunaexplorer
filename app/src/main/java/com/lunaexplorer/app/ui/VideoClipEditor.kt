package com.lunaexplorer.app.ui

import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lunaexplorer.core.Entry
import com.lunaexplorer.app.model.Overlay
import com.lunaexplorer.core.validateName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal fun clipTime(milliseconds: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
    milliseconds / 3_600_000, milliseconds / 60_000 % 60, milliseconds / 1000 % 60, milliseconds % 1000)

internal fun parseClipTime(text: String): Long? {
    if (!Regex("[0-9]+:[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,3})?").matches(text)) return null
    val parts = text.split(':')
    val hours = parts[0].toLongOrNull() ?: return null
    if (hours > Long.MAX_VALUE / 3_600_000 - 1) return null
    val seconds = parts[2].substringBefore('.').toLong()
    val fraction = parts[2].substringAfter('.', "").padEnd(3, '0').toLong()
    return hours * 3_600_000 + parts[1].toLong() * 60_000 + seconds * 1000 + fraction
}

private data class ClipSaveRequest(val name: String, val start: Long, val end: Long, val burn: Boolean)
private data class QueuedVideoClip(val id: String, val name: String, val destination: String)

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun VideoClipScreen(entry: Entry, player: MediaPlayback, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(view) { (view.parent as? DialogWindowProvider)?.window }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val browserState by viewModel.state.collectAsStateWithLifecycle()
    val selection = remember(player) { player.clipSelection() }
    var pending by remember { mutableStateOf<ClipSaveRequest?>(null) }
    var savingTo by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var exportProgress by remember { mutableStateOf<VideoClipProgress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var frameJob by remember { mutableStateOf<Job?>(null) }
    var queued by remember { mutableStateOf<QueuedVideoClip?>(null) }
    val busy = job != null

    DisposableEffect(window) {
        val previous = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { previous?.let { window?.setSoftInputMode(it) } }
    }
    DisposableEffect(player) {
        val repeating = player.repeat
        player.repeat = false
        player.setClipEditing(true)
        onDispose {
            player.pause()
            player.repeat = repeating
            player.setSubtitlesVisible(true)
            player.setClipEditing(false)
        }
    }
    val close = { job?.cancel(); frameJob?.cancel(); player.pause(); onDismiss() }
    BackHandler(onBack = close)

    fun save(folder: Entry, destination: String, clip: ClipSaveRequest) {
        player.pause()
        frameJob?.cancel()
        failure = null
        exportProgress = null
        savingTo = destination
        val request = scope.launch(start = CoroutineStart.LAZY) {
            var owned: File? = null
            try {
                val output = viewModel.files.createClipFile()
                owned = output
                exportVideoClip(context, player.source,
                    VideoClipRequest(clip.start, clip.end, clip.burn, selection), output) { exportProgress = it }
                exportProgress = null
                val operationId = viewModel.files.saveClip(output, folder.ref, clip.name)
                owned = null
                queued = QueuedVideoClip(operationId, clip.name, destination)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                failure = error.message ?: "This clip could not be saved"
            } finally {
                try { withContext(NonCancellable + Dispatchers.IO) { owned?.delete() } }
                finally { job = null }
            }
        }
        job = request
        request.start()
    }

    Column(Modifier.fillMaxSize()) {
        ViewerBar("Cut video", close)
        val saved = queued
        if (saved != null) {
            VideoClipSaveStatus(saved.id, saved.name, saved.destination,
                browserState.operations.firstOrNull { it.id == saved.id }, onDismiss = onDismiss,
                onOperations = { viewModel.showOverlay(Overlay.Queue) })
        } else {
            VideoClipEditor(
                fileName = entry.name.substringBeforeLast('.', entry.name) + "-clip.mp4",
                duration = player.duration,
                initialStart = player.position.coerceAtMost((player.duration - 1).coerceAtLeast(0)),
                position = player.framePosition,
                hasSubtitles = selection.subtitle != null,
                busy = busy,
                progress = exportProgress?.percent,
                exportProgress = exportProgress,
                onCancel = { job?.cancel() },
                playing = player.playing,
                failure = failure ?: player.failure,
                onPreview = { start, end -> frameJob?.cancel(); player.playRange(start, end) },
                onSeek = { frameJob?.cancel(); player.pause(); player.seek(it) },
                onPause = player::pause,
                onPlay = { frameJob?.cancel(); player.play() },
                onStepFrame = { forward ->
                    if (frameJob == null && !busy) {
                        player.pause()
                        failure = null
                        val request = scope.launch(start = CoroutineStart.LAZY) {
                            try { player.stepFrame(forward) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { ensureActive(); failure = error.message ?: "This frame could not be read" }
                            finally { frameJob = null }
                        }
                        frameJob = request
                        request.start()
                    }
                },
                stepping = frameJob != null,
                buffering = player.buffering,
                readPosition = { player.refreshPosition(); player.framePosition },
                savingTo = savingTo,
                onSave = { name, start, end, burn ->
                    player.pause()
                    focus.clearFocus()
                    keyboard?.hide()
                    pending = ClipSaveRequest(name, start, end, burn)
                },
                preview = { burn ->
                    SideEffect { player.setSubtitlesVisible(burn) }
                    if (!busy) AndroidView(factory = { PlayerView(it).also(player::attach) },
                        onRelease = player::detach,
                        modifier = Modifier.fillMaxSize().testTag("clipPreview"))
                },
            )
        }
    }
    val choosing = pending
    if (choosing != null) FolderDestinationChooser(viewModel, browserState.preferences.showHidden,
        onDismiss = { pending = null }, onChoose = { folder, path ->
            pending = null
            save(folder, path, choosing)
        })
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun VideoClipEditor(
    fileName: String,
    duration: Long,
    initialStart: Long,
    position: Long,
    hasSubtitles: Boolean,
    busy: Boolean,
    progress: Int?,
    playing: Boolean,
    failure: String?,
    onPreview: (Long, Long) -> Unit,
    onSeek: (Long) -> Unit,
    onPause: () -> Unit,
    onSave: (String, Long, Long, Boolean) -> Unit,
    onPlay: () -> Unit = {},
    onStepFrame: (Boolean) -> Unit = {},
    stepping: Boolean = false,
    buffering: Boolean = false,
    readPosition: () -> Long = { position },
    modifier: Modifier = Modifier,
    exportProgress: VideoClipProgress? = null,
    onCancel: () -> Unit = {},
    savingTo: String? = null,
    preview: @Composable (Boolean) -> Unit = {},
) {
    var name by rememberSaveable { mutableStateOf(fileName) }
    var startText by rememberSaveable { mutableStateOf(clipTime(initialStart)) }
    var endText by rememberSaveable { mutableStateOf(clipTime(duration)) }
    var burn by rememberSaveable { mutableStateOf(false) }
    var editingText by remember { mutableStateOf(false) }
    val validName = remember(name) { runCatching { validateName(name) }.isSuccess }
    val start = parseClipTime(startText)
    val end = parseClipTime(endText)
    val valid = start != null && end != null && start >= 0 && start < end && end <= duration
    val rangeStart = (start ?: 0).coerceIn(0, duration).toFloat()
    val rangeEnd = (end ?: duration).coerceIn(0, duration).toFloat().coerceAtLeast(rangeStart)
    var panel by rememberSaveable { mutableStateOf(0) }
    val cutScroll = rememberScrollState()
    val exportScroll = rememberScrollState()
    val editable = !busy && !stepping
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // The keyboard changes available height without changing the window's orientation.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val stopEditing = { focus.clearFocus(); keyboard?.hide(); Unit }

    BoxWithConstraints(modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        val compactEditing = editingText && (imeBottom > 0 || maxHeight < if (landscape) 200.dp else 350.dp)
        val viewportHeight = maxHeight
        val playbackPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(paneModifier.clipToBounds().padding(horizontal = 8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                    preview(burn)
                    if (buffering && !busy) CircularProgressIndicator()
                }
                Slider(value = position.coerceIn(0, duration).toFloat(), onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = editable,
                    modifier = Modifier.fillMaxWidth().height(40.dp).testTag("clipPlayhead")
                        .semantics { contentDescription = "Video position" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSeek((readPosition() - 10_000L).coerceIn(0, duration)) }, enabled = editable) {
                        Icon(Icons.Outlined.Replay10, "Back 10 seconds")
                    }
                    IconButton(onClick = { onStepFrame(false) }, enabled = editable) {
                        Icon(Icons.Outlined.SkipPrevious, "Previous frame")
                    }
                    IconButton(onClick = { if (playing) onPause() else onPlay() }, enabled = editable) {
                        Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            if (playing) "Pause" else "Play")
                    }
                    IconButton(onClick = { onStepFrame(true) }, enabled = editable) {
                        Icon(Icons.Outlined.SkipNext, "Next frame")
                    }
                    IconButton(onClick = { onSeek((readPosition() + 10_000L).coerceIn(0, duration)) }, enabled = editable) {
                        Icon(Icons.Outlined.Forward10, "Forward 10 seconds")
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { startText = clipTime(readPosition().coerceIn(0, duration)) }, enabled = editable && !buffering) {
                        Text("Set start")
                    }
                    Text(clipTime(position), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, maxLines = 1)
                    TextButton(onClick = { endText = clipTime(readPosition().coerceIn(0, duration)) }, enabled = editable && !buffering) {
                        Text("Set end")
                    }
                }
            }
        }
        val optionsPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(paneModifier) {
                if (!compactEditing) PrimaryTabRow(selectedTabIndex = panel) {
                    Tab(selected = panel == 0, onClick = { stopEditing(); panel = 0 }, text = { Text("Cut") })
                    Tab(selected = panel == 1, onClick = { stopEditing(); panel = 1 }, text = { Text("Export") })
                }
                Column(Modifier.weight(1f).fillMaxWidth()
                    .fastVerticalScroll(if (panel == 0) cutScroll else exportScroll).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (panel == 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClipTextField(startText, { startText = it }, "Start", editable,
                                start == null || start >= (end ?: duration), viewportHeight, imeBottom,
                                onFocus = { editingText = it; if (it) onPause() }, onDone = stopEditing,
                                modifier = Modifier.weight(1f))
                            ClipTextField(endText, { endText = it }, "End", editable,
                                end == null || end > duration || end <= (start ?: 0), viewportHeight, imeBottom,
                                onFocus = { editingText = it; if (it) onPause() }, onDone = stopEditing,
                                modifier = Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { stopEditing(); start?.let(onSeek) }, enabled = editable && start != null && start in 0..duration) {
                                Text("Go to start")
                            }
                            TextButton(onClick = { stopEditing(); end?.let(onSeek) }, enabled = editable && end != null && end in 0..duration) {
                                Text("Go to end")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RangeSlider(value = rangeStart..rangeEnd,
                                valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = editable,
                                onValueChange = {
                                    stopEditing()
                                    onSeek(if (it.start != rangeStart) it.start.toLong() else it.endInclusive.toLong())
                                    startText = clipTime(it.start.toLong()); endText = clipTime(it.endInclusive.toLong())
                                }, modifier = Modifier.weight(1f).testTag("clipRange"))
                            OutlinedButton(onClick = {
                                stopEditing()
                                if (valid) onPreview(requireNotNull(start), requireNotNull(end))
                            }, enabled = editable && valid) { Text("Play clip") }
                        }

                    } else {
                        ClipTextField(name, { name = it }, "Name", editable, !validName, viewportHeight, imeBottom,
                            onFocus = { editingText = it; if (it) onPause() }, onDone = stopEditing,
                            modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ClipSubtitleChoice("No subtitles", !burn, editable, Modifier.weight(1f)) { burn = false }
                            ClipSubtitleChoice("Burn in subtitles", burn, editable && hasSubtitles, Modifier.weight(1f)) { burn = true }
                        }
                        Text(if (burn) "May reduce quality." else "Lossless · timing varies by player",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                if (busy || stepping) {
                    if (progress == null || stepping) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (stepping) "Reading frames…" else when (exportProgress?.phase) {
                                VideoClipPhase.READING_SUBTITLES -> "Reading subtitles…"
                                VideoClipPhase.RENDERING -> "Rendering clip…"
                                null -> "Saving clip…"
                            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (!stepping && progress != null) Text("$progress%", style = MaterialTheme.typography.bodySmall)
                            if (busy && exportProgress != null) TextButton(onClick = onCancel) { Text("Cancel") }
                        }
                        if (busy) savingTo?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.StartEllipsis) }
                    }
                }
                if (!compactEditing) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End) {
                        Button(onClick = {
                            stopEditing()
                            if (valid) onSave(name, requireNotNull(start), requireNotNull(end), burn)
                        }, enabled = editable && valid && validName,
                            contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Save clip", maxLines = 1) }
                    }
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                playbackPane(Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                optionsPane(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                playbackPane(Modifier.height(if (compactEditing) 0.dp else viewportHeight / 2).fillMaxWidth())
                optionsPane(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ClipTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    invalid: Boolean,
    viewportHeight: Dp,
    imeBottom: Int,
    onFocus: (Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, viewportHeight, imeBottom) {
        if (focused) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true,
        enabled = enabled, isError = invalid,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.bringIntoViewRequester(requester).onFocusChanged {
            focused = it.isFocused
            onFocus(it.isFocused)
        })
}

@Composable
private fun ClipSubtitleChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp), color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
    }
}
