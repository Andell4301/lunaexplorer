@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.core.*
import kotlinx.coroutines.flow.first
import com.lunaexplorer.app.model.*

@Composable
internal fun PathBar(
    state: BrowserState,
    viewModel: BrowserViewModel,
    wide: Boolean,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    onView: () -> Unit,
    onFilter: () -> Unit,
    onPinPath: () -> Unit,
    onAddBookmarkPath: () -> Unit,
    onCreate: () -> Unit,
    onNewFile: () -> Unit,
    onProperties: () -> Unit,
    onGoToPath: () -> Unit,
    onSystemBrowser: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    var pathMenu by remember { mutableStateOf(false) }
    val tab = state.tab
    val browsing = state.screen == Screen.BROWSER
    val folder = browsing && state.view is View.Folder
    val crumbs = state.location?.crumbs.orEmpty()
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs) {
        // maxValue is Int.MAX_VALUE until the row has been measured.
        snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
        scroll.animateScrollTo(scroll.maxValue)
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!wide) {
                BadgedBox(badge = { if (state.activeOperations > 0) Badge { Text(state.activeOperations.toString()) } }) {
                    ToolIcon(Icons.Outlined.Menu, "Locations", onClick = onMenu)
                }
            }
            ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back",
                enabled = onBack != null || viewModel.canGoBack(),
                onClick = onBack ?: { viewModel.back() })

            val tool = state.screen.takeIf { it != Screen.BROWSER }
            val category = (state.view as? View.Category)?.category
            Row(Modifier.weight(1f).fastHorizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                when {
                    tool != null -> Text(tool.title, Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleMedium)
                    category != null -> Text(category.label, Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleMedium)
                    crumbs.isEmpty() -> Text("Luna", Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleMedium)
                }
                if (tool == null && category == null) crumbs.forEachIndexed { index, crumb ->
                    if (index > 0) {
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val last = index == crumbs.lastIndex
                    Box(
                        Modifier.heightIn(min = 44.dp).combinedClickable(
                            onClick = { viewModel.breadcrumb(index) },
                            onLongClick = { pathMenu = true },
                            onLongClickLabel = "Path actions",
                            role = Role.Button,
                        ).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(crumb.name, maxLines = 1,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (state.screen == Screen.BROWSER && state.entries.isNotEmpty() && !state.loading) {
                Text(state.entries.size.toString(), Modifier.padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box {
                // Empty anchor at the end of the bar, so the path menu does not open over the crumbs.
                FastDropdownMenu(expanded = pathMenu, onDismissRequest = { pathMenu = false }) {
                    val copy = rememberCopyText()
                    val shown = state.directoryPath ?: crumbs.joinToString("/") { it.name }
                    DropdownMenuItem(text = { Text("Copy path") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { pathMenu = false; copy(shown) })
                    DropdownMenuItem(text = { Text("Go to path") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { pathMenu = false; onGoToPath() })
                    DropdownMenuItem(text = { Text("Bookmark a path…") },
                        leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null) },
                        onClick = { pathMenu = false; onAddBookmarkPath() })
                    if (viewModel.canPinShortcut()) {
                        DropdownMenuItem(text = { Text("Pin a path to home screen") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = { pathMenu = false; onPinPath() })
                    }
                    DropdownMenuItem(text = { Text("Properties") },
                        leadingIcon = { Icon(Icons.Outlined.Info, null) },
                        enabled = state.directory != null,
                        onClick = { pathMenu = false; onProperties() })
                }
            }
            val searchable = when (state.screen) {
                Screen.BROWSER -> Capability.LIST in state.directory?.capabilities.orEmpty()
                Screen.HOME -> state.roots.any { !it.hidden }
                else -> false
            }
            ToolIcon(Icons.Outlined.Search,
                if (state.screen == Screen.HOME) "Search storage" else "Search this folder",
                enabled = searchable, onClick = onSearch)
            Box {
                ToolIcon(Icons.Outlined.MoreVert, if (browsing) "Folder actions" else "Page actions", onClick = { menu = true })
                FastDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (folder && viewModel.canPinShortcut()) {
                        DropdownMenuItem(text = { Text("Pin to home screen") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = { menu = false; viewModel.pinCurrentFolder() })
                    }
                    if (browsing) {
                        DropdownMenuItem(text = { Text(if (folder) "Filter this folder" else "Filter files") },
                            leadingIcon = { Icon(Icons.Outlined.FilterAlt, null) },
                            onClick = { menu = false; onFilter() })
                        DropdownMenuItem(text = { Text("View and sort") },
                            leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                            onClick = { menu = false; onView() })
                    }
                    if (folder && Capability.CREATE in state.directory?.capabilities.orEmpty() && !state.searchActive) {
                        DropdownMenuItem(text = { Text("New folder") },
                            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                            onClick = { menu = false; onCreate() })
                        DropdownMenuItem(text = { Text("New file") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null) },
                            onClick = { menu = false; onNewFile() })
                    }
                    if (browsing) {
                        DropdownMenuItem(text = { Text("Select all") },
                            leadingIcon = { Icon(Icons.Outlined.SelectAll, null) },
                            enabled = state.entries.isNotEmpty(), onClick = { menu = false; viewModel.selectAll() })
                    }
                    if (folder) {
                        DropdownMenuItem(
                            text = { Text(if (state.preferences.showHidden) "Hide hidden files" else "Show hidden files") },
                            leadingIcon = {
                                Icon(if (state.preferences.showHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
                            },
                            onClick = {
                                menu = false
                                viewModel.setPreferences(state.preferences.copy(showHidden = !state.preferences.showHidden))
                            })
                    }
                    if (browsing) HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Bookmark") },
                        leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null) },
                        enabled = state.location != null || state.screen != Screen.BROWSER,
                        onClick = { menu = false; onAddBookmarkPath() })
                    DropdownMenuItem(text = { Text("New tab") }, leadingIcon = { Icon(Icons.Outlined.Add, null) },
                        enabled = state.location != null, onClick = { menu = false; viewModel.newTab() })
                    if (browsing) {
                        DropdownMenuItem(text = { Text("Forward") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) },
                            enabled = tab != null && tab.index < tab.history.lastIndex,
                            onClick = { menu = false; viewModel.forward() })
                        DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                            onClick = { menu = false; viewModel.refresh() })
                    }
                    if (folder) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Properties") }, leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            enabled = state.directory != null, onClick = { menu = false; onProperties() })
                        DropdownMenuItem(text = { Text("Open in system files app") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                            onClick = { menu = false; onSystemBrowser() })
                    }
                }
            }
        }
    }
}

@Composable
internal fun TabsBar(state: BrowserState, viewModel: BrowserViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fastHorizontalScroll(rememberScrollState())) {
                state.tabs.forEach { tab ->
                    val active = tab.id == state.activeTabId
                    val title = tab.screen.title.ifEmpty { tab.location.title }
                    val droppable = tab.screen == Screen.BROWSER
                    var hovered by remember(tab.id) { mutableStateOf(false) }
                    val target = rememberDropTarget(onHover = { hovered = it }) { items ->
                        viewModel.dropOnto(items, tab.location.ref, tab.location.title)
                    }
                    SpringLoad(hovered && !active) { viewModel.selectTab(tab.id) }
                    Surface(onClick = { viewModel.selectTab(tab.id) },
                        color = when {
                            hovered -> MaterialTheme.colorScheme.tertiaryContainer
                            active -> MaterialTheme.colorScheme.surface
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        modifier = Modifier.widthIn(min = 92.dp, max = 190.dp).semantics { selected = active }
                            .then(if (droppable) Modifier.lunaDropTarget(target) else Modifier)) {
                        Column {
                            Row(Modifier.height(48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f, fill = false), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                ToolIcon(Icons.Outlined.Close, "Close $title",
                                    onClick = { viewModel.closeTab(tab.id) })
                            }
                            Box(Modifier.height(2.dp).fillMaxWidth()
                                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow))
                        }
                    }
                }
            }
            ToolIcon(Icons.Outlined.Add, "New tab", onClick = viewModel::newTab)
        }
    }
}
