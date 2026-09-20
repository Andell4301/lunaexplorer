@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lunaexplorer.app.model.ViewerKind
import com.lunaexplorer.core.ArchiveEngine
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry

fun viewerFor(entry: Entry): ViewerKind = when {
    entry.directory || Capability.READ !in entry.capabilities -> ViewerKind.NONE
    looksLikeDatabase(entry) -> ViewerKind.DATABASE
    documentExtension(entry) == "pdf" -> ViewerKind.PDF
    documentExtension(entry) != null -> ViewerKind.DOCUMENT
    entry.mimeType.startsWith("image/") -> ViewerKind.IMAGE
    entry.mimeType.startsWith("audio/") || entry.mimeType.startsWith("video/") -> ViewerKind.MEDIA
    isTextual(entry.name, entry.mimeType) -> ViewerKind.TEXT
    else -> ViewerKind.NONE
}

/** A file with a viewer of its own opens in it: an EPUB is a zip, but it is a book first. */
internal fun opensAsArchive(entry: Entry, archives: ArchiveEngine): Boolean =
    viewerFor(entry) == ViewerKind.NONE && archives.canBrowse(entry)

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "log", "json", "xml", "yml", "yaml", "toml", "ini", "cfg", "conf",
    "properties", "csv", "tsv", "sql", "sh", "bash", "zsh", "fish", "bat", "ps1", "kt", "kts", "java",
    "c", "h", "cpp", "hpp", "cc", "cs", "m", "py", "rb", "php", "pl", "lua", "go", "rs", "swift",
    "js", "ts", "jsx", "tsx", "css", "scss", "html", "htm", "gradle", "pro", "gitignore", "env",
    "lst", "diff", "patch", "srt", "vtt", "m3u", "m3u8",
)

private val TEXT_MIME_TYPES = setOf("application/json", "application/xml", "application/x-sh", "application/javascript")

/** [mimeType] may be one the user picked under "Open as" rather than the file's own. */
internal fun isTextual(name: String, mimeType: String): Boolean =
    mimeType.startsWith("text/") || mimeType in TEXT_MIME_TYPES ||
        name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

@Composable
fun ViewerScreen(entry: Entry, kind: ViewerKind, viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    // Editors own their dialog so Android Back and toolbar Close share the unsaved-changes guard.
    if (kind == ViewerKind.TEXT || kind == ViewerKind.CODE) {
        TextEditor(entry, viewModel, onDismiss, code = kind == ViewerKind.CODE)
        return
    }
    // decorFitsSystemWindows = false for every kind: a fitted dialog window leaves the browser showing
    // around a full-screen video, and Compose applies these window flags only when the dialog is created.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (kind) {
                ViewerKind.IMAGE -> ImageGallery(entry, viewModel, onDismiss)
                ViewerKind.MEDIA -> RichMediaViewer(entry, viewModel, onDismiss)
                ViewerKind.DATABASE -> SqliteScreen(entry, viewModel, onDismiss)
                ViewerKind.PDF, ViewerKind.DOCUMENT -> DocumentViewer(entry, viewModel, onDismiss, asPdf = kind == ViewerKind.PDF)
                ViewerKind.HEX -> HexViewer(entry, viewModel, onDismiss)
                ViewerKind.TEXT, ViewerKind.CODE, ViewerKind.NONE -> Unit
            }
        }
    }
}

@Composable
internal fun ViewerBar(
    title: String,
    onDismiss: () -> Unit,
    entry: Entry? = null,
    viewModel: BrowserViewModel? = null,
    subtitle: String? = null,
    fileActionsEnabled: Boolean = true,
    closeOnRename: Boolean = false,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onOverlayVisibilityChange: (Boolean) -> Unit = {},
    menuItems: @Composable (dismissMenu: () -> Unit) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
        .height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close") }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            ScrollingText(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 1)
            }
        }
        actions()
        if (entry != null && viewModel != null) {
            ViewerOverflow(entry, viewModel, onDismiss, fileActionsEnabled, closeOnRename, onOverlayVisibilityChange, menuItems)
        }
    }
}

@Composable
private fun ViewerOverflow(
    entry: Entry,
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    enabled: Boolean,
    closeOnRename: Boolean,
    onOverlayVisibilityChange: (Boolean) -> Unit,
    menuItems: @Composable (dismissMenu: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var properties by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val overlayOpen = menu || properties || renaming || confirmDelete
    DisposableEffect(overlayOpen) {
        onOverlayVisibilityChange(overlayOpen)
        onDispose { onOverlayVisibilityChange(false) }
    }

    Box {
        IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, "More") }
        FastDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            menuItems { menu = false }
            DropdownMenuItem(text = { Text("File info") },
                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                onClick = { menu = false; properties = true })
            DropdownMenuItem(text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { menu = false; renaming = true })
            DropdownMenuItem(text = { Text("Share") },
                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                onClick = { menu = false; viewModel.shareFromViewer(entry) })
            DropdownMenuItem(text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                onClick = { menu = false; confirmDelete = true })
        }
    }

    if (properties) PropertiesScreen(listOf(entry), viewModel) { properties = false }
    if (renaming) {
        val state by viewModel.state.collectAsState()
        RenameDialog(entry, includeExtension = state.preferences.renameIncludesExtension,
            onRemember = { viewModel.setPreferences(viewModel.state.value.preferences.copy(renameIncludesExtension = it)) },
            onDismiss = { renaming = false }) { name ->
            viewModel.operations.renameEntry(entry, name)
            renaming = false
            if (closeOnRename) onDismiss()
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${entry.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.operations.deleteEntry(entry)
                    onDismiss()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
