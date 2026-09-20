package com.lunaexplorer.app.ui

import android.net.Uri
import android.media.AudioManager
import android.os.PowerManager
import android.provider.OpenableColumns
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.ScreenLockRotation
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lunaexplorer.app.storage.formatDuration
import com.lunaexplorer.app.model.VideoExitBehavior
import com.lunaexplorer.core.Entry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.abs
import java.io.File
import java.util.UUID

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun RichMediaViewer(entry: Entry, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val browserState by viewModel.state.collectAsStateWithLifecycle()
    val subtitleAppearance = browserState.preferences.subtitleAppearance
    val video = entry.mimeType.startsWith("video/")
    val activity = LocalActivity.current
    val pip = LocalPictureInPicture.current
    val pipOn = video && pip?.supported == true
    val inPip = pip?.active == true
    var manualBackground by rememberSaveable(entry.ref) { mutableStateOf(false) }
    val videoExit = browserState.preferences.videoExitBehavior
    val backgroundOn = if (video) manualBackground || videoExit == VideoExitBehavior.BACKGROUND
        else browserState.preferences.audioBackground
    val autoPip = videoExit == VideoExitBehavior.PICTURE_IN_PICTURE && !manualBackground
    var playback by remember(entry.ref) { mutableStateOf<MediaPlayback?>(null) }
    var failure by remember(entry.ref) { mutableStateOf<String?>(null) }
    var trackDialog by remember(entry.ref) { mutableStateOf(false) }
    var speedMenu by remember(entry.ref) { mutableStateOf(false) }
    var appearanceDialog by remember(entry.ref) { mutableStateOf(false) }
    var gestureDialog by remember(entry.ref) { mutableStateOf(false) }
    var overflowOpen by remember(entry.ref) { mutableStateOf(false) }
    var subtitleBusy by remember(entry.ref) { mutableStateOf(false) }
    var trackMessage by remember(entry.ref) { mutableStateOf<String?>(null) }
    var dragging by remember(entry.ref) { mutableStateOf(false) }
    var seekPosition by remember(entry.ref) { mutableLongStateOf(0L) }
    val nearby = remember(entry.ref) {
        viewModel.siblingsOf(entry) { it.ref == entry.ref || isSubtitleFile(it.name) }
            .filter { it.ref != entry.ref && isSubtitleFile(it.name) }
    }

    LaunchedEffect(entry.ref) {
        var session: MediaPlayback? = null
        val directory = File(context.cacheDir, "subtitles/${UUID.randomUUID()}")
        try {
            val source = withContext(Dispatchers.IO) {
                viewModel.files.playbackSource(entry)
            }
            val opened = MediaPlayback(context, source, directory, entry.name)
            session = opened
            viewModel.takePlayback(entry.ref)?.let { opened.startAt(it) }
            playback = opened
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = error.message ?: "This file could not be played"
        } catch (error: LinkageError) {
            failure = "The media player is unavailable on this device"
        } finally {
            playback = null
            val open = session
            if (open != null) {
                // A recreation only: this ViewModel survives one, and the same viewer comes back.
                if (activity?.isChangingConfigurations == true) {
                    open.refreshPosition()
                    viewModel.rememberPlayback(PlaybackResume(entry.ref, open.position, open.playing))
                }
                open.close()
            } else withContext(NonCancellable + Dispatchers.IO) {
                directory.deleteRecursively()
            }
        }
    }

    val player = playback
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    LaunchedEffect(player, subtitleAppearance) { player?.setSubtitleAppearance(subtitleAppearance) }
    SideEffect { player?.setBackground(backgroundOn) }
    // Off screen the poll would wake the main thread all the way through background play.
    LaunchedEffect(player) {
        if (player != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { player.refreshPosition(); delay(250) }
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> manualBackground = false
                Lifecycle.Event.ON_START -> player?.onScreen(true)
                Lifecycle.Event.ON_STOP -> {
                    val closing = activity?.isFinishing == true || activity?.isChangingConfigurations == true
                    if (pauseOnStop(player?.background == true, pip?.phase ?: PipPhase.NONE,
                            power?.isInteractive != false, closing)) player?.pause()
                    player?.onScreen(false)
                }
                else -> Unit
            }
        }
        player?.onScreen(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(pip) { onDispose { pip?.arm(null) } }
    LaunchedEffect(pip, pipOn, autoPip, player, player?.playing, player?.seekable,
        player?.videoWidth, player?.videoHeight, player?.pictureBounds) {
        if (pip == null) return@LaunchedEffect
        if (!pipOn || player == null) { pip.arm(null); return@LaunchedEffect }
        pip.arm(
            PipRequest(pipAspect(player.videoWidth, player.videoHeight), player.pictureBounds,
                player.playing, player.seekable, autoEnter = autoPip),
            onAction = { action ->
                when (action) {
                    // Plain seeks: nothing is composed in the window to add them up on.
                    PipAction.BACK -> player.seek(player.position - SKIP_STEP)
                    PipAction.FORWARD -> player.seek(player.position + SKIP_STEP)
                    PipAction.TOGGLE -> if (player.playing) player.pause() else player.play()
                }
            },
            onClosed = { player.pause() },
        )
    }

    fun loadSubtitle(name: String, read: suspend (File) -> File) {
        val current = playback ?: return
        if (subtitleBusy) return
        subtitleBusy = true
        trackMessage = null
        scope.launch {
            var cached: File? = null
            try {
                withContext(Dispatchers.IO) { cached = read(current.subtitleDirectory) }
                if (current !== playback) return@launch
                check(current.addSubtitle(requireNotNull(cached), name)) { "This subtitle file could not be loaded" }
                cached = null // Owned by the player now; removed with the subtitle directory on close.
                trackMessage = "Loaded $name"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                trackMessage = error.message ?: "This subtitle file could not be loaded"
            } finally {
                cached?.delete()
                subtitleBusy = false
            }
        }
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            loadSubtitle("subtitle file") { directory ->
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment.orEmpty()
                context.contentResolver.openInputStream(uri)?.use { copySubtitle(it, name, directory) }
                    ?: error("This subtitle file could not be read")
            }
        }
    }

    var chromeShown by remember { mutableStateOf(true) }
    // Counts the skips, so that tapping a skip button over and over does not run the controls out from under the finger.
    var skips by remember { mutableIntStateOf(0) }
    LaunchedEffect(video, chromeShown, player?.playing, speedMenu, overflowOpen, gestureDialog, trackDialog, skips) {
        // Hiding the chrome would remove an open menu's anchor.
        if (video && chromeShown && !speedMenu && !overflowOpen && !gestureDialog && !trackDialog && player?.playing == true) {
            delay(CHROME_LINGER)
            chromeShown = false
        }
    }
    val view = LocalView.current
    // The viewer is a Dialog with its own window; the system bars are controlled through that
    // window, not the activity's.
    val window = remember(view) {
        (view.parent as? DialogWindowProvider)?.window ?: (view.context as? Activity)?.window
    }
    val bars = remember(window, view) { window?.let { WindowCompat.getInsetsController(it, view) } }
    SideEffect {
        if (inPip) return@SideEffect
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (video && !chromeShown) bars?.hide(WindowInsetsCompat.Type.systemBars())
        else bars?.show(WindowInsetsCompat.Type.systemBars())
    }
    // The dim layer behind this opaque window would otherwise flash while a PiP window shrinks.
    DisposableEffect(window, video) {
        if (video) window?.setDimAmount(0f)
        onDispose {}
    }
    // Restored in a separate effect so that toggling chromeShown does not re-show the bars in between.
    DisposableEffect(bars, window) {
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            window?.let { it.attributes = it.attributes.apply { screenBrightness = BRIGHTNESS_OVERRIDE_NONE } }
        }
    }

    var heldOrientation by rememberSaveable { mutableStateOf<Int?>(null) }
    val rotationLocked = heldOrientation != null
    DisposableEffect(activity, video, heldOrientation) {
        if (video) {
            activity?.requestedOrientation = heldOrientation ?: PlayerRotation.FREE
        }
        onDispose {}
    }
    // Restore UNSPECIFIED rather than the value read on entry: a requested orientation survives
    // activity recreation, so that value could be a previous viewer's request.
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    var locked by rememberSaveable(entry.ref) { mutableStateOf(false) }
    var lockOffered by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<PlayerHint?>(null) }
    var seekFrom by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    // Accumulated as a float fraction; rounding each touch event to whole milliseconds loses slow scrubs.
    var seekTravel by remember { mutableFloatStateOf(0f) }
    var gesturing by remember { mutableStateOf(false) }
    LaunchedEffect(lockOffered) { if (lockOffered) { delay(CHROME_LINGER); lockOffered = false } }
    LaunchedEffect(hint, gesturing) {
        if (hint != null && !gesturing) { delay(HINT_LINGER); hint = null }
    }
    BackHandler(enabled = locked) { lockOffered = true }

    var restoreBrightness by remember { mutableFloatStateOf(BRIGHTNESS_OVERRIDE_NONE) }
    LaunchedEffect(inPip) {
        if (inPip) {
            trackDialog = false
            speedMenu = false
            appearanceDialog = false
            gestureDialog = false
            hint = null
            restoreBrightness = window?.attributes?.screenBrightness ?: BRIGHTNESS_OVERRIDE_NONE
            // A visible unobscured window holds the whole screen at its own brightness.
            window?.let { it.attributes = it.attributes.apply { screenBrightness = BRIGHTNESS_OVERRIDE_NONE } }
        } else {
            window?.let { it.attributes = it.attributes.apply { screenBrightness = restoreBrightness } }
            chromeShown = true
            if (locked) lockOffered = true
        }
    }

    fun skip(step: Long) {
        val playing = player ?: return
        playing.seek(playing.position + step)
        // Skips made while the last one's hint still shows add up on it: +10s, +20s, +30s.
        val total = skipTotal(hint?.skipped ?: 0L, step)
        val once = abs(total) == SKIP_STEP
        hint = PlayerHint(
            if (total < 0) { if (once) Icons.Outlined.Replay10 else Icons.Outlined.FastRewind }
            else { if (once) Icons.Outlined.Forward10 else Icons.Outlined.FastForward },
            "${if (total < 0) "-" else "+"}${abs(total) / 1000}s", skipped = total)
        skips++
    }

    val gestures = browserState.preferences
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val volumeSteps = remember(audio) { audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0 }
    val volumeFixed = remember(audio) { audio?.isVolumeFixed == true }

    fun brightnessNow(): Float {
        val set = window?.attributes?.screenBrightness ?: -1f
        if (set >= 0f) return set
        // SCREEN_BRIGHTNESS is not 0..255 on every device and is meaningless under automatic
        // brightness, so it is clamped and used only as a starting point.
        return runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(.5f).coerceIn(0f, 1f)
    }

    fun setBrightness(level: Float) {
        val holder = window ?: return
        // Per-window brightness needs no WRITE_SETTINGS permission.
        holder.attributes = holder.attributes.apply { screenBrightness = level.coerceIn(.01f, 1f) }
    }

    fun turn() {
        // Read from the activity, not LocalConfiguration: Compose sees a rotation late, so two quick
        // presses would both read the old orientation.
        val nowLandscape = activity?.resources?.configuration?.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        heldOrientation = PlayerRotation.turnedFrom(nowLandscape)
    }

    fun holdRotation(hold: Boolean) { heldOrientation = if (hold) PlayerRotation.HELD else null }

    @Composable
    fun Surface(modifier: Modifier) {
        Box(modifier.background(if (video) Color.Black else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center) {
            if (player != null && video) {
                AndroidView(
                    factory = { PlayerView(it) },
                    modifier = Modifier.fillMaxSize().testTag("playerSurface"),
                    update = { view ->
                        player.attach(view)
                        view.keepScreenOn = player.playing
                        if (!player.started && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.play()
                    },
                    onRelease = { view -> view.keepScreenOn = false; player.detach(view) },
                )
            } else if (player != null) {
                LaunchedEffect(player) {
                    if (!player.started && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.play()
                }
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            }
            val error = failure ?: player?.failure
            when {
                error != null -> Text(error, Modifier.padding(24.dp), color = if (video) Color.White else MaterialTheme.colorScheme.error)
                player == null || (player.buffering && player.playing) -> CircularProgressIndicator(Modifier.size(32.dp))
            }
        }
    }

    @Composable
    fun Controls(modifier: Modifier) {
        Column(modifier.padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
            val duration = player?.duration ?: 0L
            val position = if (dragging) seekPosition else player?.position ?: 0L
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(position), Modifier.width(58.dp), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                    onValueChange = { dragging = true; seekPosition = it.toLong() },
                    onValueChangeFinished = { player?.seek(seekPosition); dragging = false },
                    valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                    enabled = player?.seekable == true && duration > 0,
                    modifier = Modifier.weight(1f),
                )
                Text(formatDuration(duration), Modifier.width(58.dp), style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End)
            }
            CentredControls {
                IconButton(onClick = { skip(-SKIP_STEP) }, enabled = player?.seekable == true) {
                    Icon(Icons.Outlined.Replay10, "Back 10 seconds")
                }
                FilledTonalIconButton(onClick = { if (player?.playing == true) player.pause() else player?.play() },
                    enabled = player != null && player.failure == null) {
                    Icon(if (player?.playing == true) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (player?.playing == true) "Pause" else "Play")
                }
                IconButton(onClick = { skip(SKIP_STEP) }, enabled = player?.seekable == true) {
                    Icon(Icons.Outlined.Forward10, "Forward 10 seconds")
                }
            }
            CentredControls {
                Toggled(player?.repeat == true, { player?.let { it.repeat = !it.repeat } },
                    Icons.Outlined.Repeat, if (player?.repeat == true) "Stop repeating" else "Repeat",
                    enabled = player != null)
                Box {
                    Toggled(player != null && player.speed != 1f, { speedMenu = true },
                        Icons.Outlined.Speed, "Playback speed", enabled = player != null)
                    FastDropdownMenu(speedMenu, onDismissRequest = { speedMenu = false }) {
                        SPEEDS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("$option×") },
                                trailingIcon = {
                                    if (player?.speed == option) Icon(Icons.Outlined.Check, null)
                                },
                                onClick = { player?.changeSpeed(option); speedMenu = false },
                            )
                        }
                    }
                }
                IconButton(onClick = { trackDialog = true }, enabled = player != null) {
                    Icon(if (video) Icons.Outlined.Subtitles else Icons.Outlined.Audiotrack,
                        if (video) "Audio and subtitles" else "Audio track")
                }
                if (video) {
                    IconButton(onClick = { locked = true; chromeShown = false }) {
                        Icon(Icons.Outlined.LockOpen, "Lock the picture")
                    }
                    IconButton(onClick = { turn() }) {
                        Icon(Icons.Outlined.ScreenRotation, "Turn the picture")
                    }
                    Toggled(rotationLocked, { holdRotation(!rotationLocked) },
                        Icons.Outlined.ScreenLockRotation,
                        if (rotationLocked) "Let it turn again" else "Keep this way up")
                }
            }
            if (!video) AudioPlayerSettings(browserState.preferences, viewModel::setPreferences)
            Spacer(Modifier.height(8.dp))
        }
    }

    if (video) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // The picture only in a PiP window: the chrome's dialogs are windows of their own and
            // would draw inside it. Surface keeps its place so the PlayerView is not rebuilt.
            Surface(Modifier.fillMaxSize())
            if (!inPip) {
                Box(
                    Modifier.fillMaxSize().playerGestures(
                        key = player,
                        locked = locked,
                        doubleTap = gestures.playerDoubleTap,
                        seek = gestures.playerSwipeSeek && player?.seekable == true,
                        volume = gestures.playerVolumeGesture && volumeSteps > 0 && !volumeFixed,
                        brightness = gestures.playerBrightnessGesture,
                        onLockedTouch = { lockOffered = true },
                        onTap = { chromeShown = !chromeShown },
                        onDoubleTap = { zone ->
                            val playing = player
                            if (playing != null) when (zone) {
                                TapZone.BACK -> skip(-SKIP_STEP)
                                TapZone.FORWARD -> skip(SKIP_STEP)
                                TapZone.MIDDLE -> if (playing.playing) playing.pause() else playing.play()
                            }
                        },
                        onSeekStart = {
                            dragging = true
                            gesturing = true
                            seekTravel = 0f
                            seekFrom = player?.position ?: 0L
                            seekPosition = seekFrom
                        },
                        onSeek = { fraction ->
                            val span = (player?.duration ?: 0L).coerceAtMost(SEEK_SWEEP)
                            seekTravel += fraction
                            seekPosition = (seekFrom + (seekTravel * span).toLong())
                                .coerceIn(0L, player?.duration ?: 0L)
                            val delta = seekPosition - seekFrom
                            hint = PlayerHint(
                                if (delta < 0) Icons.Outlined.FastRewind else Icons.Outlined.FastForward,
                                "${if (delta < 0) "-" else "+"}${formatDuration(abs(delta))}" +
                                    "   ${formatDuration(seekPosition)}",
                            )
                        },
                        onSeekEnd = {
                            player?.seek(seekPosition)
                            dragging = false
                            gesturing = false
                            hint = null
                        },
                        onVolumeStart = {
                            gesturing = true
                            level = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) / volumeSteps.toFloat()
                        },
                        onVolume = { fraction ->
                            level = (level + fraction).coerceIn(0f, 1f)
                            val step = (level * volumeSteps).roundToInt()
                            // flags = 0 keeps the system volume panel from showing.
                            runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0) }
                            // Show the level the stream actually took, not the one requested.
                            val applied = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: step
                            hint = PlayerHint(Icons.AutoMirrored.Outlined.VolumeUp, "${applied * 100 / volumeSteps}%")
                        },
                        onBrightnessStart = {
                            gesturing = true
                            level = brightnessNow()
                        },
                        onBrightness = { fraction ->
                            level = (level + fraction).coerceIn(0f, 1f)
                            setBrightness(level)
                            hint = PlayerHint(Icons.Outlined.BrightnessMedium, "${(level * 100).roundToInt()}%")
                        },
                        onAdjustEnd = { gesturing = false },
                    ),
                )
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    AnimatedVisibility(!locked && chromeShown, Modifier.align(Alignment.TopCenter),
                        enter = fadeIn(), exit = fadeOut()) {
                        Box(Modifier.fillMaxWidth().background(SCRIM)) {
                            ViewerBar(entry.name, onDismiss, entry = entry, viewModel = viewModel,
                                onOverlayVisibilityChange = { overflowOpen = it },
                                menuItems = { dismissMenu ->
                                    DropdownMenuItem(text = { Text("Gestures") },
                                        leadingIcon = { Icon(Icons.Outlined.TouchApp, null) },
                                        onClick = { dismissMenu(); gestureDialog = true })
                                    HorizontalDivider()
                                }, actions = {
                                if (pipOn) IconButton(enabled = player != null, onClick = {
                                    // inPip only flips on the callback, so the shrink would show chrome.
                                    chromeShown = false
                                    if (pip?.enter() != true) {
                                        chromeShown = true
                                        hint = PlayerHint(Icons.Outlined.PictureInPictureAlt, "Unavailable")
                                    }
                                }) { Icon(Icons.Outlined.PictureInPictureAlt, "Picture in picture") }
                                IconButton(enabled = player != null && pip != null, onClick = {
                                    val current = player ?: return@IconButton
                                    val previous = manualBackground
                                    manualBackground = true
                                    // Publish before moving the task: ON_STOP may arrive before recomposition.
                                    current.setBackground(true)
                                    if (pip?.moveToBackground() != true) {
                                        manualBackground = previous
                                        current.setBackground(backgroundOn)
                                        hint = PlayerHint(Icons.Outlined.Headphones, "Unavailable")
                                    }
                                }) { Icon(Icons.Outlined.Headphones, "Background play") }
                            })
                        }
                    }
                    AnimatedVisibility(!locked && chromeShown, Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn(), exit = fadeOut()) {
                        Box(Modifier.fillMaxWidth().background(SCRIM)) { Controls(Modifier.fillMaxWidth()) }
                    }
                    AnimatedVisibility(locked && lockOffered, Modifier.align(Alignment.CenterStart),
                        enter = fadeIn(), exit = fadeOut()) {
                        Box(Modifier.padding(24.dp).windowInsetsPadding(WindowInsets.safeDrawing)) {
                            FilledTonalIconButton(onClick = { locked = false; chromeShown = true }) {
                                Icon(Icons.Outlined.Lock, "Unlock the picture")
                            }
                        }
                    }
                    AnimatedVisibility(hint != null, Modifier.align(Alignment.Center),
                        enter = fadeIn(), exit = fadeOut()) {
                        val shown = hint
                        if (shown != null) {
                            Row(Modifier.background(SCRIM, RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(shown.icon, null)
                                Text(shown.text, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            ViewerBar(entry.name, onDismiss, entry = entry, viewModel = viewModel)
            Surface(Modifier.weight(1f).fillMaxWidth())
            Controls(Modifier)
        }
    }

    if (gestureDialog && !inPip) {
        AlertDialog(
            onDismissRequest = { gestureDialog = false },
            title = { Text("Gestures") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).fastVerticalScroll(rememberScrollState())) {
                    PlayerGestureSettings(browserState.preferences, viewModel::setPreferences)
                }
            },
            confirmButton = { TextButton(onClick = { gestureDialog = false }) { Text("Done") } },
        )
    }

    if (player != null && trackDialog && !inPip) {
        AlertDialog(
            onDismissRequest = { trackDialog = false },
            title = { Text(if (video) "Audio and subtitles" else "Audio track") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).fastVerticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (video) Text("Audio", style = MaterialTheme.typography.titleSmall)
                    if (player.audioTracks.isEmpty()) Text("No audio tracks were found")
                    else TrackChoice("Off", player.audioOff) { player.selectAudio(-1) }
                    player.audioTracks.forEach { track ->
                        TrackChoice(track.label, !player.audioOff && player.audioTrack == track.id, track.supported) {
                            if (!player.selectAudio(track.id)) trackMessage = "This audio track could not be selected"
                        }
                    }
                    if (video) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Subtitles", style = MaterialTheme.typography.titleSmall)
                        TrackChoice("Off", player.subtitleTrack == -1) { player.selectSubtitle(-1) }
                        player.subtitleTracks.forEach { track ->
                            TrackChoice(track.label, player.subtitleTrack == track.id, track.supported) {
                                if (!player.selectSubtitle(track.id)) trackMessage = "This subtitle track could not be selected"
                            }
                        }
                        if (player.subtitleTracks.isEmpty()) Text("No embedded subtitles were found",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { trackDialog = false; appearanceDialog = true }) { Text("Appearance") }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Subtitle timing", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { player.setSubtitleDelay(player.subtitleDelayMs - 250) }) { Text("−0.25 s") }
                            Text(String.format(LocalConfiguration.current.locales[0],
                                "%+.2f s", player.subtitleDelayMs / 1000.0),
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { player.setSubtitleDelay(player.subtitleDelayMs + 250) }) { Text("+0.25 s") }
                        }
                        Text("Negative shows subtitles earlier; positive shows them later.", style = MaterialTheme.typography.bodySmall)
                        if (player.subtitleDelayMs != 0L) TextButton(onClick = { player.setSubtitleDelay(0L) }) { Text("Reset timing") }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        OutlinedButton(onClick = { subtitlePicker.launch(arrayOf("*/*")) }, enabled = !subtitleBusy) {
                            Text("Choose subtitle file")
                        }
                        Text("SRT, WebVTT, TTML and ASS/SSA", style = MaterialTheme.typography.bodySmall)
                        Text("ASS/SSA supports basic styling and positioning. Advanced effects and attached fonts are not supported.",
                            style = MaterialTheme.typography.bodySmall)
                        if (nearby.isNotEmpty()) {
                            Text("Subtitle files in this folder", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                            nearby.forEach { subtitle ->
                                TextButton(onClick = {
                                    loadSubtitle(subtitle.name) { directory ->
                                        viewModel.files.readBytes(subtitle, MAX_SUBTITLE_BYTES).getOrThrow().inputStream()
                                            .use { copySubtitle(it, subtitle.name, directory) }
                                    }
                                }, enabled = !subtitleBusy) { Text(subtitle.name) }
                            }
                        }
                        if (subtitleBusy) Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Loading subtitles…", Modifier.padding(start = 8.dp))
                        }
                    }
                    trackMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { trackDialog = false }) { Text("Done") } },
        )
    }
    if (appearanceDialog && !inPip) {
        SubtitleAppearanceDialog(subtitleAppearance,
            onChange = { viewModel.setPreferences(viewModel.state.value.preferences.copy(subtitleAppearance = it)) },
            onDismiss = { appearanceDialog = false; trackDialog = true })
    }
}

@Composable
private fun TrackChoice(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

internal object PlayerRotation {
    /** SENSOR, not USER: a video rotates with the device even when system auto-rotate is off. */
    const val FREE = ActivityInfo.SCREEN_ORIENTATION_SENSOR

    const val HELD = ActivityInfo.SCREEN_ORIENTATION_LOCKED

    /** SENSOR_* rather than USER_*: works with system auto-rotate off and still flips end for end. */
    fun turnedFrom(landscape: Boolean): Int =
        if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

@Composable
private fun Toggled(
    on: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
) {
    if (on) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
    } else {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
    }
}

@Composable
internal fun CentredControls(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** [skipped] is the signed total of a run of skips, 0 for any other hint. */
private class PlayerHint(val icon: ImageVector, val text: String, val skipped: Long = 0L)

internal val SCRIM = Color.Black.copy(alpha = 0.55f)

private const val CHROME_LINGER = 3_000L
private const val HINT_LINGER = 900L
private const val SKIP_STEP = 10_000L

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/** Milliseconds covered by a seek drag across the full width; shorter media uses its own duration. */
private const val SEEK_SWEEP = 120_000L
