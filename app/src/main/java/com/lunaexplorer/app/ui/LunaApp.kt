@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lunaexplorer.app.storage.OpenCandidate
import com.lunaexplorer.app.storage.ThumbnailLoader
import com.lunaexplorer.core.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.lunaexplorer.app.model.*
import kotlin.math.round

data class LunaActions(
    val grantFolder: (String?) -> Unit,
    val open: (Entry, Boolean) -> Unit,
    val openWith: (Entry, OpenCandidate, String) -> Unit,
    val shareReport: (String) -> Unit,
    val requestFullAccess: () -> Unit,
    val openSystemBrowser: (String?) -> Unit,
    val unlockVault: ((Boolean) -> Unit) -> Unit = { it(false) },
    /** The ViewModel already holds what to export: the save dialog can outlive the composition. */
    val saveSettings: (String) -> Unit = { },
    val openSettings: () -> Unit = { },
)

internal val LocalPinching = compositionLocalOf { false }

internal val LocalThumbnails = staticCompositionLocalOf<ThumbnailLoader?> { null }
internal val LocalNetworkThumbnails = compositionLocalOf<Map<String, NetworkThumbnails>> { emptyMap() }

@Composable
fun LunaApp(viewModel: BrowserViewModel, actions: LunaActions) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val windowDensity = LocalDensity.current
    var pinching by remember { mutableStateOf(false) }
    var zoomShown by remember { mutableStateOf(false) }
    LaunchedEffect(pinching) { if (!pinching && zoomShown) { delay(ZOOM_LABEL_LINGER); zoomShown = false } }
    Box(Modifier.fillMaxSize().uiZoom(state.preferences.uiScale, onPinching = { pinching = it },
        onZooming = { zoomShown = true }) { scale ->
        viewModel.setPreferences(state.preferences.copy(uiScale = scale))
    }) {
        LunaTheme(state.preferences.theme, state.preferences.accent, state.preferences.accentSeed,
            uiScale = state.preferences.uiScale) {
            CompositionLocalProvider(
                LocalThumbnails provides viewModel.thumbnails.takeIf { state.preferences.thumbnails },
                LocalNetworkThumbnails provides state.preferences.networkThumbnails,
                LocalArchives provides viewModel.archives,
                LocalPinching provides pinching,
                LocalColorfulIcons provides state.preferences.colorfulIcons,
                LocalHiddenFade provides HiddenFade(
                    icon = 1f - state.preferences.hiddenIconFade / 100f,
                    text = 1f - state.preferences.hiddenTextFade / 100f,
                ),
                LocalSyntaxScheme provides state.preferences.syntaxScheme,
            ) {
                LunaScaffold(state, viewModel, actions, windowDensity)
            }
            // At the window's density, so the label does not resize with the zoom it reports.
            CompositionLocalProvider(LocalDensity provides windowDensity) {
                AnimatedVisibility(zoomShown, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp),
                    enter = fadeIn(), exit = fadeOut()) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 4.dp) {
                        Text("${(state.preferences.uiScale * 100).roundToInt()}%",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

private const val ZOOM_LABEL_LINGER = 700L

@Composable
private fun LunaScaffold(
    state: BrowserState,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    windowDensity: Density,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    var filtering by rememberSaveable { mutableStateOf(false) }
    val show: (Overlay) -> Unit = viewModel::showOverlay
    // open/share unlocks an encrypted archive member first.
    val sharing = remember(actions, viewModel) {
        actions.copy(open = { entry, share -> viewModel.requireArchivePassword(entry) { actions.open(entry, share) } })
    }

    // Launched in the screen scope: a LaunchedEffect keyed on the tapped file would be cancelled
    // mid-lookup when the key is consumed.
    fun beginOpen(entry: Entry) {
        scope.launch {
            val remembered = runCatching { viewModel.openWith.defaultFor(entry) }.getOrNull()
            if (remembered == null) { show(Overlay.Opening(entry)); return@launch }
            val kind = internalTargets(entry, viewModel.archives).firstOrNull { it.second.key == remembered.target }?.first
            when {
                kind == ViewerKind.NONE -> viewModel.browseArchive(entry)
                kind != null -> viewModel.requireArchivePassword(entry) { show(Overlay.Viewer(entry, kind)) }
                else -> {
                    val parts = remembered.target.split('/', limit = 2)
                    if (parts.size == 2) {
                        viewModel.requireArchivePassword(entry) {
                            actions.openWith(entry, OpenCandidate(parts[0], parts[1], remembered.label, true),
                                remembered.mime.ifEmpty { entry.mimeType })
                        }
                    } else {
                        show(Overlay.Opening(entry))
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.shareRequests.collect { entry -> actions.open(entry, true) }
    }

    // The settings window covers this one and shows messages itself.
    val covered = state.overlay == Overlay.Settings
    LaunchedEffect(state.message, covered) {
        if (covered) return@LaunchedEffect
        state.message?.let {
            snackbars.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long)
            viewModel.dismissMessage()
        }
    }
    BackHandler(enabled = viewModel.canGoBack() || state.selected.isNotEmpty() || state.openingArchive != null ||
        (state.screen == Screen.BROWSER && state.searchActive)) {
        viewModel.back()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // maxWidth is in zoomed dp; scale it back to physical dp for the tablet breakpoint.
            val wide = maxWidth * state.preferences.uiScale >= 840.dp
            val locationsScroll = rememberLazyListState()
            LaunchedEffect(drawerState.currentValue) {
                if (drawerState.currentValue == DrawerValue.Closed) locationsScroll.scrollToItem(0)
            }
            val locations: @Composable () -> Unit = {
                LocationsPanel(state, viewModel, actions, locationsScroll,
                    onSettings = { show(Overlay.Settings) },
                    onQueue = { show(Overlay.Queue) },
                    onRecycleBin = { viewModel.showScreen(Screen.RECYCLE_BIN) },
                    onAddBookmark = { show(Overlay.AddBookmark(bookmarkProposal(state))) },
                    onRenameBookmark = { bookmark, list -> show(Overlay.EditBookmark(bookmark, list)) },
                    onStorage = { viewModel.showScreen(Screen.STORAGE) },
                    onApps = { viewModel.showScreen(Screen.APPS) },
                    onNavigated = { scope.launch { drawerState.close() } })
            }
            val browser: @Composable () -> Unit = {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbars) },
                    topBar = {
                        Column {
                            PathBar(state, viewModel, wide,
                                onMenu = { scope.launch { drawerState.open() } },
                                onSearch = { show(Overlay.Search) },
                                onView = { show(Overlay.ViewOptions) },
                                onFilter = { filtering = true },
                                onPinPath = { show(Overlay.PinPath) },
                                onAddBookmarkPath = { show(Overlay.AddBookmark(bookmarkProposal(state))) },
                                onCreate = { show(Overlay.Create) },
                                onNewFile = { show(Overlay.NewFile) },
                                onProperties = {
                                    show(Overlay.Properties(state.selectedEntries.ifEmpty { listOfNotNull(state.directory) }))
                                },
                                onGoToPath = { show(Overlay.GoTo) },
                                onSystemBrowser = { actions.openSystemBrowser(state.directoryPath) })
                            AnimatedVisibility(
                                visible = state.tabs.size > 1,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) { TabsBar(state, viewModel) }
                            AnimatedVisibility(
                                visible = state.searchActive && state.screen == Screen.BROWSER,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) { SearchStrip(state, viewModel) }
                            // Holds the last value so the strip keeps its content during the exit animation.
                            val shownOpening = remember { arrayOfNulls<ArchiveOpening>(1) }
                            state.openingArchive?.let { shownOpening[0] = it }
                            AnimatedVisibility(
                                visible = state.openingArchive != null,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) { shownOpening[0]?.let { ArchiveOpeningStrip(it, viewModel::cancelArchiveOpening) } }
                            AnimatedVisibility(
                                visible = filtering && state.screen == Screen.BROWSER,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) { FilterStrip(state, viewModel) { filtering = false } }
                            val shownOperation = remember { arrayOfNulls<Pair<QueueItem, Int>>(1) }
                            state.leadOperation?.let { shownOperation[0] = it to state.activeOperations }
                            AnimatedVisibility(
                                visible = state.leadOperation != null,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                shownOperation[0]?.let { (operation, active) ->
                                    OperationsStrip(operation, active, onQueue = { show(Overlay.Queue) })
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(2.dp)) {
                                // The archive strip shows determinate progress for a sequential read.
                                val walking = state.openingArchive?.let { it.fromStart && (it.total ?: 0L) > 0L } == true
                                if (state.loading || state.searching || !state.ready || (state.openingArchive != null && !walking)) {
                                    LinearProgressIndicator(Modifier.fillMaxSize())
                                }
                            }
                        }
                    },
                    bottomBar = {
                        Column(Modifier.navigationBarsPadding()) {
                            AnimatedVisibility(
                                visible = state.selected.isNotEmpty() && state.screen == Screen.BROWSER,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                SelectionBar(state, viewModel, sharing,
                                    onRename = {
                                        show(if (state.selected.size > 1) Overlay.BatchRename else Overlay.Rename)
                                    },
                                    onDelete = viewModel::requestDelete,
                                    onProperties = { show(Overlay.Properties(state.selectedEntries)) },
                                    onOpenAs = { show(Overlay.Opening(it)) },
                                    onArchive = { show(Overlay.Archive) },
                                    onExtract = { show(Overlay.Extract) })
                            }
                            AnimatedVisibility(
                                visible = state.clipboard != null && state.screen == Screen.BROWSER,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                state.clipboard?.let { ClipboardBar(it, state, viewModel) }
                            }
                        }
                    },
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        when (state.screen) {
                            Screen.RECYCLE_BIN -> RecycleBinScreen(viewModel)
                            Screen.APPS -> AppsScreen(viewModel)
                            Screen.STORAGE -> StorageScreen(state.roots, viewModel, actions,
                                fullAccess = state.fullAccess, browsing = state.directoryPath)
                            Screen.HOME -> HomeScreen(state, viewModel,
                                onRenameBookmark = { bookmark, list -> show(Overlay.EditBookmark(bookmark, list)) },
                                onAddBookmark = { show(Overlay.AddBookmark(bookmarkProposal(state), BookmarkList.HOME)) })
                            Screen.BROWSER -> BrowserContent(state, viewModel, actions,
                                onOpen = { beginOpen(it) },
                                onPackage = { entry ->
                                    viewModel.requireArchivePassword(entry) { show(Overlay.Package(entry)) }
                                })
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Surface(Modifier.width(264.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) { locations() }
                    VerticalDivider()
                    Box(Modifier.weight(1f)) { browser() }
                }
            } else {
                ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
                    ModalDrawerSheet(Modifier.width(300.dp)) { locations() }
                }, content = browser)
            }
        }
    }

    val dismiss = viewModel::dismissOverlay
    // Dialogs use the unscaled window density: Compose recreates a dialog window when its density
    // changes, which would interrupt a UI-size slider mid-drag.
    CompositionLocalProvider(LocalDensity provides windowDensity) {
    when (val overlay = state.overlay) {
        null -> Unit
        is Overlay.EditBookmark -> EditBookmarkDialog(overlay.bookmark, onDismiss = dismiss) { title, path ->
            viewModel.bookmarks.edit(overlay.bookmark, title, path, overlay.list)
            dismiss()
        }
        is Overlay.AddBookmark -> AddBookmarkDialog(overlay.proposal, overlay.list, viewModel,
            state.preferences.showHidden, onDismiss = dismiss) { title, destination, list ->
            when (destination) {
                is Destination.Tool -> viewModel.bookmarks.addScreen(destination.screen, title, list)
                is Destination.Place ->
                    if (destination.path == null && destination.location != null) {
                        viewModel.bookmarks.addLocation(title, destination.location, destination.file, list)
                    } else viewModel.bookmarks.add(title, destination.path.orEmpty(), list)
            }
            dismiss()
        }
        is Overlay.Drop -> DropDialog(overlay.entries.size, overlay.title, onDismiss = dismiss) { move, keep ->
            if (keep) viewModel.rememberForSession(dropAction = if (move) DropAction.MOVE else DropAction.COPY)
            viewModel.dropInto(overlay.entries, overlay.destination, move)
            dismiss()
        }
        Overlay.Create -> NameDialog("New folder", "Folder name", "Create", onDismiss = dismiss) {
            viewModel.operations.createFolder(it); dismiss()
        }
        Overlay.BatchRename -> BatchRenameDialog(
            entries = state.selectedEntries,
            existing = viewModel.namesInFolder(),
            includeExtension = state.preferences.renameIncludesExtension,
            onRemember = { viewModel.setPreferences(viewModel.state.value.preferences.copy(renameIncludesExtension = it)) },
            onDismiss = dismiss,
        ) { steps, includeExtension -> viewModel.batchRename(state.selectedEntries, steps, includeExtension); dismiss() }
        Overlay.Rename -> state.selectedEntries.singleOrNull()?.let { entry ->
            RenameDialog(entry, includeExtension = state.preferences.renameIncludesExtension,
                onRemember = { viewModel.setPreferences(viewModel.state.value.preferences.copy(renameIncludesExtension = it)) },
                onDismiss = dismiss) {
                viewModel.operations.renameSelection(it); dismiss()
            }
        }
        Overlay.Delete -> DeleteDialog(state, viewModel, dismiss)
        Overlay.NewFile -> NameDialog("New file", "File name", "Create",
            presets = DOT_FILES, onDismiss = dismiss) {
            viewModel.files.createTextFile(it); dismiss()
        }
        Overlay.Archive -> ArchiveDialog(state.selectedEntries, onDismiss = dismiss) { name, options ->
            viewModel.operations.createArchive(state.selectedEntries, name, options); dismiss()
        }
        Overlay.Extract -> if (viewModel.insideArchive()) {
            LaunchedEffect(Unit) {
                viewModel.operations.copySelection(move = false)
                viewModel.showMessage("Open the folder to extract into, then Paste here")
                dismiss()
            }
        } else state.selectedEntries.singleOrNull()?.let { archive ->
            ExtractDialog(archive, onDismiss = dismiss) { into, password, here ->
                if (here) viewModel.operations.extractArchive(archive, into, password)
                else {
                    viewModel.operations.carryExtract(archive, ExtractPlan(into, password))
                    viewModel.showMessage("Open the folder to extract into, then Extract here")
                }
                dismiss()
            }
        }
        Overlay.PinPath -> PinPathDialog(state.directoryPath.orEmpty(), onDismiss = dismiss) { path, label ->
            viewModel.pinPath(path, label); dismiss()
        }
        Overlay.GoTo -> GoToPathDialog(state.directoryPath.orEmpty(), onDismiss = dismiss) {
            viewModel.goTo(it); dismiss()
        }
        Overlay.Search -> SearchDialog(viewModel.searchScope(), viewModel.searchesText(), onDismiss = dismiss) { query, type, min, max, after, regex, matchCase, text ->
            viewModel.search(query, type, min, max, after, regex, matchCase, text); dismiss()
        }
        Overlay.ViewOptions -> ViewOptionsSheet(
            preferences = state.preferences.inFolder(state.folderViews[state.folderKey]),
            scopable = state.folderKey != null,
            startScoped = viewModel.folderHasOwnView(),
            onChange = viewModel::setViewOptions,
            onDismiss = dismiss,
        )
        Overlay.Settings -> SettingsScreen(state, viewModel, actions,
            onRecycleBin = { viewModel.showScreen(Screen.RECYCLE_BIN) }, onDismiss = dismiss)
        Overlay.Queue -> QueueDialog(state.operations,
            includeExtension = state.preferences.renameIncludesExtension,
            onRemember = {
                viewModel.setPreferences(viewModel.state.value.preferences.copy(renameIncludesExtension = it))
            },
            onCancel = viewModel.operations::cancelOperation,
            onConflict = viewModel.operations::resolveConflict,
            onInspect = { id -> viewModel.inspectOperation(id); dismiss() },
            onReport = actions.shareReport, onDismiss = dismiss)
        is Overlay.Overwrite -> OverwriteWarning(
            onDismiss = { show(Overlay.Queue) },
            onConfirm = { viewModel.operations.confirmOverwrite(overlay) },
        )
        is Overlay.Properties -> PropertiesScreen(overlay.entries, viewModel, dismiss)
        is Overlay.Viewer -> ViewerScreen(overlay.entry, overlay.kind, viewModel, dismiss)
        is Overlay.Package -> PackageSheet(overlay.entry, viewModel,
            onInstall = { show(Overlay.Install(overlay.entry)) },
            onBrowse = { dismiss(); viewModel.browseArchive(overlay.entry) },
            onManifest = { show(Overlay.Manifest(overlay.entry)) },
            onProperties = { show(Overlay.Properties(listOf(overlay.entry))) },
            onOpenWith = { show(Overlay.Opening(overlay.entry)) },
            onDismiss = dismiss)
        is Overlay.Install -> InstallDialog(overlay.entry, viewModel, dismiss)
        is Overlay.Manifest -> ManifestScreen(overlay.entry, viewModel, dismiss)
        is Overlay.Opening -> OpenSheet(
            overlay.entry, viewModel,
            onInternal = { kind ->
                if (kind == ViewerKind.NONE) { dismiss(); viewModel.browseArchive(overlay.entry) }
                else viewModel.requireArchivePassword(overlay.entry) { show(Overlay.Viewer(overlay.entry, kind)) }
            },
            onExternal = { candidate, mime ->
                dismiss()
                viewModel.requireArchivePassword(overlay.entry) { actions.openWith(overlay.entry, candidate, mime) }
            },
            onDismiss = dismiss,
        )
        is Overlay.ArchivePassword -> ArchivePasswordDialog(overlay.title, overlay.error, overlay.verifying,
            onUnlock = viewModel::unlockArchive, onDismiss = viewModel::dismissArchivePassword)
    }
    }

    if (state.ready && !state.preferences.introSeen && !state.fullAccess) {
        AlertDialog(
            onDismissRequest = { viewModel.setPreferences(state.preferences.copy(introSeen = true)) },
            icon = { Icon(Icons.Outlined.FolderOpen, null) },
            title = { Text("Give Luna access to your files") },
            text = {
                Text(
                    "Android calls this all-files access. Without it Luna can only reach folders " +
                        "you add one at a time, and the device-wide views stay mostly empty.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setPreferences(state.preferences.copy(introSeen = true))
                    actions.requestFullAccess()
                }) { Text("Grant access") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.setPreferences(state.preferences.copy(introSeen = true))
                }) { Text("Not now") }
            },
        )
    }
}

/** Media-scanner marker files. Android defines .nomedia; the rest are gallery and player app conventions. */
private val DOT_FILES = listOf(
    ".nomedia", ".noimage", ".novideo", ".nomusic",
    ".nothumbnail", ".nopreview", ".nocache", ".nosearch",
)

/**
 * Two-finger UI zoom, consumed on the Initial pass so lists underneath never see it. Must sit above the
 * scaled theme: pointerInput restarts when its density changes, which would cancel the pinch in progress.
 */
private fun Modifier.uiZoom(
    current: Float,
    onPinching: (Boolean) -> Unit,
    onZooming: () -> Unit,
    onScale: (Float) -> Unit,
): Modifier = composed {
    val scale = rememberUpdatedState(current)
    val publish = rememberUpdatedState(onScale)
    val pinched = rememberUpdatedState(onPinching)
    val zooming = rememberUpdatedState(onZooming)
    pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var live = scale.value
        var pinching = false
        var twoDown = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointers = event.changes.count { it.pressed }
                if (pointers < 2) {
                    if (pointers == 0) break else continue
                }
                // Reported on the second finger, before any zoom is measurable, so pull-to-refresh is off in time.
                if (!twoDown) { twoDown = true; pinched.value(true) }
                event.changes.forEach { it.consume() }
                val zoom = event.calculateZoom()
                if (zoom == 1f && !pinching) continue
                // Reported even when the scale is pinned at a limit and publishes nothing.
                if (!pinching) { pinching = true; zooming.value() }
                live = (live * zoom).coerceIn(0.7f, 1.6f)
                // 0.05 steps: each change remeasures the whole tree.
                val stepped = round(live * 20f) / 20f
                if (stepped != scale.value) publish.value(stepped)
            }
        } finally {
            if (twoDown) pinched.value(false)
        }
    }
    }
}
