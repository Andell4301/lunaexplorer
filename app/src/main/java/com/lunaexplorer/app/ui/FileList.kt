@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import com.lunaexplorer.app.storage.shizuku.HelperState
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.storage.SystemNames
import com.lunaexplorer.core.*
import com.lunaexplorer.app.model.*

@Composable
internal fun BrowserContent(
    state: BrowserState,
    viewModel: BrowserViewModel,
    actions: LunaActions,
    onOpen: (Entry) -> Unit,
    onPackage: (Entry) -> Unit,
) {
    val tabScrollState = rememberSaveableStateHolder()
    val archives = LocalArchives.current
    val listingLocation = (state.view as? View.Folder)?.listingLocation
    val openEntry: (Entry) -> Unit = remember(viewModel, archives, state.selected.isEmpty(), listingLocation) { { entry ->
        when {
            state.selected.isNotEmpty() -> viewModel.toggleSelection(entry.ref)
            entry.directory -> viewModel.openFolder(entry, listingLocation)
            viewModel.packages.isPackage(entry) -> onPackage(entry)
            archives != null && opensAsArchive(entry, archives) -> viewModel.browseArchive(entry, location = listingLocation)
            else -> onOpen(entry)
        }
    } }
    val density = LocalDensity.current
    val rowThumb = remember(density) { with(density) { 40.dp.roundToPx() } }
    val tileThumb = remember(density) { with(density) { 108.dp.roundToPx() } }

    val positions = rememberSaveable { HashMap<List<String?>, IntArray>() }
    val listingKey = listOf(state.activeTabId, state.listingRef?.provider, state.listingRef?.key)
    val position = positions[listingKey]
    val listState = key(listingKey) {
        rememberLazyListState(position?.get(0) ?: 0, position?.get(1) ?: 0)
    }
    val gridState = key(listingKey) {
        rememberLazyGridState(position?.get(2) ?: 0, position?.get(3) ?: 0)
    }
    DisposableEffect(listingKey, listState, gridState) {
        onDispose {
            positions[listingKey] = intArrayOf(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
                gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        }
    }
    val laidOut = state.preferences.inFolder(state.folderViews[state.folderKey])

    // Compose registers drop targets only at drag start, so rows composed mid-drag (after a tab or folder
    // change) never receive it. This area-wide target hit-tests the rows itself and also covers empty folders.
    var area by remember { mutableStateOf(Rect.Zero) }
    val areaHover = remember { mutableStateOf<Entry?>(null) }
    fun rowAt(position: Offset): Entry? {
        if (!area.contains(position)) return null
        val local = position - area.topLeft
        val index = if (laidOut.view.grid) {
            gridState.layoutInfo.visibleItemsInfo.firstOrNull {
                local.x >= it.offset.x && local.x < it.offset.x + it.size.width &&
                    local.y >= it.offset.y && local.y < it.offset.y + it.size.height
            }?.index
        } else {
            listState.layoutInfo.visibleItemsInfo.firstOrNull {
                local.y >= it.offset && local.y < it.offset + it.size
            }?.index
        }
        // Section headers occupy item indices too; over one, the drop falls through to the current folder.
        val row = index?.let { Sections.entryIndexAt(state.rowSections, it) } ?: return null
        return state.entries.getOrNull(row)
    }
    fun targetAt(position: Offset): Entry? {
        val row = rowAt(position)
        return when {
            row == null -> state.dropHere
            row.directory -> row
            else -> null
        }
    }
    val areaTarget = rememberAreaDropTarget(
        onHover = { position -> areaHover.value = position?.let { targetAt(it) } },
        onDrop = { items, position ->
            val into = targetAt(position)
            if (into != null) viewModel.dropOnto(items, into.ref, into.name)
            into != null
        },
    )
    val hereHovered = areaHover.value != null && areaHover.value?.ref == state.dropHere?.ref

    // Background stays present (transparent when idle) so the modifier chain doesn't change mid-drag.
    Box(Modifier.fillMaxSize().lunaDropTarget(areaTarget)
        .background(if (hereHovered) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent)) {
        when {
            !state.ready -> EmptyState(Icons.Outlined.NightlightRound, "Opening", null)
            state.location == null -> EmptyState(Icons.Outlined.FolderOpen, "No storage available",
                "Turn on full storage access, or add a location through Android's picker.",
                primary = "Turn on full access" to { actions.requestFullAccess() },
                secondary = "Add a location" to { actions.grantFolder(null) })
            // Every way in is offered together: a helper that is switched on may still not be there.
            state.entries.isEmpty() && !state.loading && !state.searching && !state.searchActive &&
                shutOut(state, viewModel) -> ClosedFolderState(state, viewModel, actions)
            state.entries.isEmpty() && !state.loading && obbWithoutAccess(state, viewModel) -> EmptyState(
                Icons.Outlined.FolderOff, "Android/obb needs the install permission", OBB_PERMISSION,
                primary = "Allow installing apps" to { viewModel.packages.requestInstallPermission() },
                secondary = "Open in system files app" to { actions.openSystemBrowser(state.directoryPath) },
                tertiary = "Add this folder" to { actions.grantFolder(state.directoryPath) })
            state.entries.isEmpty() && !state.loading && closedPathReason(state) != null -> EmptyState(
                Icons.Outlined.FolderOff, "Closed to apps", closedPathReason(state),
                primary = if (state.directoryPath?.trimEnd('/') == "/data/media") {
                    "Go to /storage/emulated" to { viewModel.goTo("/storage/emulated") }
                } else "Open in system files app" to { actions.openSystemBrowser(state.directoryPath) })
            state.error != null && state.entries.isEmpty() && state.errorReason == StorageError.AUTH &&
                state.vaultLocked && !viewModel.vault.isOpen -> EmptyState(
                Icons.Outlined.Lock, "Locked", state.error,
                primary = "Unlock" to {
                    actions.unlockVault { proved -> if (proved && viewModel.vault.open()) viewModel.reopen() }
                },
                secondary = "Try again" to { viewModel.refresh() })
            state.error != null && state.entries.isEmpty() -> EmptyState(
                Icons.Outlined.FolderOff, "Can't open this folder", state.error,
                primary = if (state.errorReason == StorageError.PERMISSION) {
                    "Open in system files app" to { actions.openSystemBrowser(state.directoryPath) }
                } else "Try again" to { viewModel.refresh() },
                secondary = alternateMethod(state.directoryPath, viewModel),
                tertiary = if (state.errorReason == StorageError.PERMISSION) {
                    "Add this folder" to { actions.grantFolder(state.directoryPath) }
                } else null)
            state.entries.isEmpty() && !state.loading && !state.searching ->
                if (state.searchActive) EmptyState(Icons.Outlined.SearchOff, "Nothing found", null,
                    primary = "Close search" to { viewModel.closeSearch() })
                else EmptyState(Icons.Outlined.FolderOpen, "Empty folder", null)
            else -> Column(Modifier.fillMaxSize()) {
                state.error?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = viewModel::refresh) { Text("Retry") }
                        }
                    }
                }
                Box(Modifier.onGloballyPositioned { area = it.boundsInRoot() }) {
                tabScrollState.SaveableStateProvider(state.activeTabId) {
                    Box {
                        val refreshState = rememberPullToRefreshState()
                        val pinching = LocalPinching.current
                        LaunchedEffect(pinching) { if (pinching) refreshState.snapTo(0f) }
                        Box(Modifier.fillMaxSize().pullToRefresh(
                            isRefreshing = state.refreshing,
                            state = refreshState,
                            enabled = !pinching,
                            onRefresh = viewModel::pullToRefresh,
                        )) {
                        val mode = laidOut.view
                        val selecting = state.selected.isNotEmpty()
                        if (mode.grid) {
                            val chosen = if (mode == ViewMode.GRID_CUSTOM) laidOut.gridCell else 0
                            val cell = (if (chosen > 0) chosen else mode.cell).dp
                            FastLazyVerticalGrid(columns = GridCells.Adaptive(cell), state = gridState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val rows = state.entries
                                val runs = state.rowSections
                                val tile: @Composable (Entry) -> Unit = { entry ->
                                    FileTile(entry, entry.ref in state.selected, selecting, tileThumb,
                                        gallery = mode == ViewMode.GALLERY,
                                        hoveredByArea = areaHover.value?.ref == entry.ref,
                                        onClick = { openEntry(entry) }, onSelect = { viewModel.toggleSelection(entry.ref) },
                                        onDrag = { viewModel.beginDrag(entry) },
                                        onDrop = if (entry.directory) ({ items: List<Entry> ->
                                            viewModel.dropOnto(items, entry.ref, entry.name)
                                        }) else null)
                                }
                                if (runs.isEmpty()) {
                                    gridItems(rows, key = ::entryKeyOf, contentType = { it.directory }) { entry -> tile(entry) }
                                } else runs.forEach { section ->
                                    // A grid stickyHeader spans the full line on its own.
                                    stickyHeader(key = sectionKeyOf(section), contentType = "section") { _ ->
                                        SectionHeaderRow(section.label)
                                    }
                                    items(section.count,
                                        key = { i -> entryKeyOf(rows[section.start + i]) },
                                        contentType = { i -> rows[section.start + i].directory }) { i ->
                                        tile(rows[section.start + i])
                                    }
                                }
                            }
                        } else {
                            FastLazyColumn(Modifier.fillMaxSize(), state = listState,
                                contentPadding = PaddingValues(bottom = 12.dp)) {
                                val rows = state.entries
                                val runs = state.rowSections
                                val row: @Composable (Entry) -> Unit = { entry ->
                                    FileRow(entry, entry.ref in state.selected, selecting, rowThumb,
                                        mode = mode,
                                        hoveredByArea = areaHover.value?.ref == entry.ref,
                                        onClick = { openEntry(entry) }, onSelect = { viewModel.toggleSelection(entry.ref) },
                                        onDrag = { viewModel.beginDrag(entry) },
                                        onDrop = if (entry.directory) ({ items: List<Entry> ->
                                            viewModel.dropOnto(items, entry.ref, entry.name)
                                        }) else null)
                                }
                                if (runs.isEmpty()) {
                                    items(rows, key = ::entryKeyOf, contentType = { it.directory }) { entry -> row(entry) }
                                } else runs.forEach { section ->
                                    stickyHeader(key = sectionKeyOf(section), contentType = "section") { _ ->
                                        SectionHeaderRow(section.label)
                                    }
                                    items(section.count,
                                        key = { i -> entryKeyOf(rows[section.start + i]) },
                                        contentType = { i -> rows[section.start + i].directory }) { i ->
                                        row(rows[section.start + i])
                                    }
                                }
                            }
                        }
                        PullToRefreshDefaults.Indicator(
                            state = refreshState,
                            isRefreshing = state.refreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                        }
                    }
                }
                }
            }
        }
    }
}

/** Opaque so rows do not show through a sticky header as they scroll under it. */
@Composable
private fun SectionHeaderRow(label: String) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(label, Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun entryKeyOf(entry: Entry) = "${entry.ref.provider}:${entry.ref.key}"
/**
 * "luna-section" is not a provider id, so this never equals an entry key. Keyed by start index because
 * labels can repeat and duplicate lazy keys crash Compose.
 */
private fun sectionKeyOf(section: SectionRun) = "luna-section:${section.start}"

@Composable
private fun FileRow(
    entry: Entry,
    selected: Boolean,
    selecting: Boolean,
    thumbPx: Int,
    mode: ViewMode,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onDrag: () -> List<Entry>,
    onDrop: ((List<Entry>) -> Unit)?,
    hoveredByArea: Boolean,
) {
    val compact = mode == ViewMode.COMPACT
    val detailed = mode == ViewMode.DETAILED
    val thumbnail = if (compact) null else rememberThumbnail(entry, thumbPx)
    val appIcon = rememberAppIcon(entry)
    val kind = fileKind(entry)
    val hiddenFade = LocalHiddenFade.current
    val fade = if (entry.hidden) hiddenFade.icon else 1f
    val textFade = if (entry.hidden) hiddenFade.text else 1f
    val glyph = rememberVectorPainter(kind.icon)
    val tint = if (LocalColorfulIcons.current) hueColor(kind.hue) else fileColor(entry)
    // Read at pickup so a thumbnail that loaded after the press is used.
    val visual = rememberUpdatedState(remember(thumbnail, appIcon, glyph, tint) { dragVisual(thumbnail, appIcon, glyph, tint) })
    var ownHover by remember(entry.ref) { mutableStateOf(false) }
    val hovered = ownHover || hoveredByArea
    val target = rememberDropTarget(onHover = { ownHover = it }) { onDrop?.invoke(it) }
    val view = LocalView.current
    val scale = LocalDensity.current.density
    val direction = LocalLayoutDirection.current
    val colours = dragColours()
    Row(Modifier.fillMaxWidth()
        .background(when {
            hovered -> MaterialTheme.colorScheme.tertiaryContainer
            selected -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        })
        .then(if (onDrop != null) Modifier.lunaDropTarget(target) else Modifier)
        // No onLongClick: it would compete with the drag source's long press and prevent pickup.
        .combinedClickable(onClick = onClick, role = Role.Button)
        .lunaDragSource(view, entry.ref, scale, direction, colours) { DragPickup(onDrag(), visual.value) }
        .semantics {
            this.selected = selected
            // Accessibility long-click; the pointer long press belongs to the drag source.
            onLongClick(label = "Select ${entry.name}") { onSelect(); true }
        }
        .heightIn(min = if (compact) 40.dp else if (detailed) 66.dp else 58.dp)
        .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 32.dp else 44.dp)
            .combinedClickable(onClick = onSelect, role = Role.Checkbox), contentAlignment = Alignment.Center) {
            when {
                selected -> Icon(Icons.Outlined.CheckCircle, "Deselect ${entry.name}", tint = MaterialTheme.colorScheme.primary)
                selecting -> Icon(Icons.Outlined.RadioButtonUnchecked, "Select ${entry.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                thumbnail != null -> Image(thumbnail, "Select ${entry.name}",
                    Modifier.size(38.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop, alpha = fade)
                appIcon != null -> Image(appIcon, "Select ${entry.name}",
                    Modifier.size(if (compact) 24.dp else 30.dp), alpha = fade)
                else -> KindIcon(kind.icon, kind.hue, "Select ${entry.name}", plainSize = 26.dp,
                    badgeSize = if (compact) 26.dp else 36.dp, plainTint = tint, dimmed = entry.capabilities.isEmpty(),
                    label = badgeLabel(entry, kind), fill = badgeFill(entry, kind), modifier = Modifier.alpha(fade))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = if (compact) 3.dp else 8.dp).alpha(textFade)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry.directory) FontWeight.Medium else FontWeight.Normal)
            if (!compact) {
                Text(rowDetail(entry), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (detailed) {
                Text(detailedLine(entry), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (entry.directory && !selecting) {
            Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 8.dp).size(16.dp).alpha(textFade),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(Modifier.padding(start = if (compact) 40.dp else 58.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
}

private fun detailedLine(entry: Entry): String = buildString {
    append(entry.mimeType)
    entry.size?.let { append("  ·  ").append(it.toString()).append(" bytes") }
}

@Composable
private fun FileTile(
    entry: Entry,
    selected: Boolean,
    selecting: Boolean,
    thumbPx: Int,
    gallery: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onDrag: () -> List<Entry>,
    onDrop: ((List<Entry>) -> Unit)?,
    hoveredByArea: Boolean,
) {
    val thumbnail = rememberThumbnail(entry, thumbPx, folders = true)
    val appIcon = rememberAppIcon(entry)
    val kind = fileKind(entry)
    val hiddenFade = LocalHiddenFade.current
    val fade = if (entry.hidden) hiddenFade.icon else 1f
    val textFade = if (entry.hidden) hiddenFade.text else 1f
    val glyph = rememberVectorPainter(kind.icon)
    val tint = if (LocalColorfulIcons.current) hueColor(kind.hue) else fileColor(entry)
    val visual = rememberUpdatedState(remember(thumbnail, appIcon, glyph, tint) { dragVisual(thumbnail, appIcon, glyph, tint) })
    var ownHover by remember(entry.ref) { mutableStateOf(false) }
    val hovered = ownHover || hoveredByArea
    val target = rememberDropTarget(onHover = { ownHover = it }) { onDrop?.invoke(it) }
    val view = LocalView.current
    val scale = LocalDensity.current.density
    val direction = LocalLayoutDirection.current
    val colours = dragColours()
    Column(Modifier.fillMaxWidth()
        .background(when {
            hovered -> MaterialTheme.colorScheme.tertiaryContainer
            selected -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }, RoundedCornerShape(10.dp))
        .then(if (onDrop != null) Modifier.lunaDropTarget(target) else Modifier)
        .combinedClickable(onClick = onClick, role = Role.Button)
        .lunaDragSource(view, entry.ref, scale, direction, colours) { DragPickup(onDrag(), visual.value) }
        .semantics {
            this.selected = selected
            onLongClick(label = "Select ${entry.name}") { onSelect(); true }
        }
        .padding(if (gallery) 2.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            when {
                thumbnail != null -> Image(thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = fade)
                appIcon != null -> Image(appIcon, null, Modifier.size(46.dp), alpha = fade)
                else -> KindIcon(kind.icon, kind.hue, null, plainSize = 38.dp, badgeSize = 56.dp, plainTint = tint,
                    dimmed = entry.capabilities.isEmpty(), label = badgeLabel(entry, kind), fill = badgeFill(entry, kind),
                    modifier = Modifier.alpha(fade))
            }
            if (selecting) {
                Box(Modifier.align(Alignment.TopEnd).size(40.dp).combinedClickable(onClick = onSelect, role = Role.Checkbox),
                    contentAlignment = Alignment.Center) {
                    Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        if (selected) "Deselect ${entry.name}" else "Select ${entry.name}",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (!gallery) {
            Spacer(Modifier.height(4.dp))
            Text(entry.name, Modifier.fillMaxWidth().alpha(textFade), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                minLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (entry.directory) "Folder" else formatBytes(entry.size), Modifier.fillMaxWidth().alpha(textFade),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private fun rowDetail(entry: Entry): String = listOfNotNull(
    if (entry.directory) "Folder" else formatBytes(entry.size),
    entry.modified?.let(::formatDate),
    if (!entry.directory && Capability.READ !in entry.capabilities) "No access" else null,
).joinToString("  ·  ")

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String?,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    tertiary: Pair<String, () -> Unit>? = null,
    more: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Box(Modifier.fillMaxSize().fastVerticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 400.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (text != null) {
                Spacer(Modifier.height(6.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (primary != null) {
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = primary.second) { Text(primary.first) }
            }
            if (secondary != null) TextButton(onClick = secondary.second) { Text(secondary.first) }
            if (tertiary != null) TextButton(onClick = tertiary.second) { Text(tertiary.first) }
            more.forEach { (label, act) -> TextButton(onClick = act) { Text(label) } }
        }
    }
}

private fun obbWithoutAccess(state: BrowserState, viewModel: BrowserViewModel): Boolean {
    val path = state.directoryPath ?: return false
    // With the helper answering, an empty obb folder is just empty.
    return state.helper != HelperState.READY && path.trimEnd('/').endsWith("/Android/obb") && !viewModel.packages.canInstall()
}

/**
 * The folder on screen is one Android closes to file managers, and nothing else lets Luna in: not
 * the helper, and for Android/obb not the install permission either.
 */
private fun shutOut(state: BrowserState, viewModel: BrowserViewModel): Boolean {
    val folder = state.view as? View.Folder ?: return false
    if (!folder.closedToApps || state.helper == HelperState.READY) return false
    return !("/Android/obb" in folder.path.orEmpty() && viewModel.packages.canInstall())
}

/**
 * A folder Android closes to file managers, with every way in that applies. Which one works depends
 * on the device and what is installed, so switching the helper on takes none of the others away.
 */
@Composable
private fun ClosedFolderState(state: BrowserState, viewModel: BrowserViewModel, actions: LunaActions) {
    val path = state.directoryPath?.trimEnd('/').orEmpty()
    val obb = "/Android/obb" in path
    val waiting = state.preferences.shizuku && state.helper != HelperState.OFF
    val context = LocalContext.current
    val ways = buildList {
        if (waiting) helperAction(state.helper, viewModel, context)?.let(::add)
        if (obb) add("Allow installing apps" to { viewModel.packages.requestInstallPermission() })
        add("Open in system files app" to { actions.openSystemBrowser(state.directoryPath) })
        alternateMethod(path, viewModel)?.let(::add)
        add("Add this folder" to { actions.grantFolder(state.directoryPath) })
        if (!state.preferences.shizuku) add("Use Shizuku" to { viewModel.useShizuku() })
    }
    val (title, text) = when {
        waiting -> "Can't open this folder" to helperStatus(state.helper, viewModel.helperUid)
        obb -> "Android/obb needs the install permission" to OBB_PERMISSION
        state.error != null -> "Can't open this folder" to state.error
        // Some Android versions answer an app with an empty folder here, not a refusal.
        else -> "Empty folder" to "Android shows apps nothing in here."
    }
    EmptyState(if (state.error == null && !waiting && !obb) Icons.Outlined.FolderOpen else Icons.Outlined.FolderOff,
        title, text, primary = ways.first(), more = ways.drop(1))
}

private fun alternateMethod(path: String?, viewModel: BrowserViewModel): Pair<String, () -> Unit>? {
    val here = path?.trimEnd('/') ?: return null
    if (CLOSED_BY_ANDROID.none { here.endsWith(it) }) return null
    return "Try alternate method" to { viewModel.goTo(here + "\u200E") }
}

private val CLOSED_BY_ANDROID = listOf("/Android/data", "/Android/obb")

private const val OBB_PERMISSION = "Android grants this folder to apps allowed to install packages. It is not a folder " +
    "permission, and it applies after Luna restarts."

internal fun helperStatus(helper: HelperState, uid: Int?): String = when (helper) {
    HelperState.OFF -> "Off"
    HelperState.NOT_INSTALLED -> "Shizuku is not installed"
    HelperState.NOT_RUNNING -> "Shizuku is not running"
    HelperState.NEEDS_PERMISSION -> "Shizuku has not allowed Luna"
    HelperState.REFUSED -> "Shizuku was told not to allow Luna"
    HelperState.CONNECTING -> "Starting"
    HelperState.READY -> if (uid == 0) "Connected as root" else "Connected over adb"
    HelperState.FAILED -> "The helper would not start"
}

internal fun helperAction(helper: HelperState, viewModel: BrowserViewModel, context: Context): Pair<String, () -> Unit>? = when (helper) {
    HelperState.NEEDS_PERMISSION -> "Allow" to { viewModel.allowShizuku() }
    HelperState.FAILED -> "Try again" to { viewModel.retryShizuku() }
    // Looked up at the tap, not while drawing.
    HelperState.NOT_RUNNING, HelperState.REFUSED -> "Open Shizuku" to {
        runCatching { context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER)?.let(context::startActivity) }
        Unit
    }
    else -> null
}

private const val SHIZUKU_MANAGER = "moe.shizuku.privileged.api"

private fun closedPathReason(state: BrowserState): String? =
    state.directoryPath?.trimEnd('/')?.let { SystemNames.CLOSED_PATHS[it] }
