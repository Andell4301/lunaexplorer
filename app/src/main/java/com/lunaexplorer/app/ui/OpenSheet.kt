@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.storage.OpenCandidate
import com.lunaexplorer.core.ArchiveEngine
import com.lunaexplorer.core.BROWSABLE_ARCHIVE_EXTENSIONS
import com.lunaexplorer.core.Entry
import com.lunaexplorer.app.model.*

@Composable
fun OpenSheet(
    entry: Entry,
    viewModel: BrowserViewModel,
    onInternal: (ViewerKind) -> Unit,
    onExternal: (OpenCandidate, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var declared by rememberSaveable(entry.ref) { mutableStateOf(entry.mimeType) }
    var always by rememberSaveable(entry.ref) { mutableStateOf(false) }
    var typePicker by rememberSaveable(entry.ref) { mutableStateOf(false) }
    var allViewers by rememberSaveable(entry.ref) { mutableStateOf(false) }
    var allApps by rememberSaveable(entry.ref) { mutableStateOf(false) }
    var candidates by remember(entry.ref) { mutableStateOf<List<OpenCandidate>?>(null) }
    var icons by remember(entry.ref) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }

    LaunchedEffect(entry.ref, declared) {
        candidates = null
        val found = viewModel.openWith.candidates(entry, declared)
        candidates = found
        icons = viewModel.openWith.icons(found).mapValues { it.value.asImageBitmap() }
    }

    fun saveDefault(target: String, label: String) {
        if (always) viewModel.openWith.remember(entry, target, declared, label)
    }

    OpenChooserSheet(onDismiss) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Column(Modifier.fastVerticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                ScrollingText(entry.name, Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.titleMedium)
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(declared, style = MaterialTheme.typography.bodySmall,
                        color = if (declared == entry.mimeType) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { typePicker = !typePicker }) {
                        Text(if (typePicker) "Done" else "Open as")
                    }
                }

                if (typePicker) {
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (declared != entry.mimeType) {
                            FilterChip(selected = false, onClick = { declared = entry.mimeType },
                                label = { Text("Actual") })
                        }
                        OPEN_AS_TYPES.forEach { (type, label) ->
                            FilterChip(selected = declared == type, onClick = { declared = type },
                                label = { Text(label) })
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                val all = internalTargets(entry, LocalArchives.current)
                val suited = all.filter { (kind, _) -> suits(entry, kind, declared) }
                // The hex viewer suits every file, so it alone does not make a file one Luna has a viewer for.
                val specific = suited.any { (kind, _) -> kind != ViewerKind.HEX }
                val shown = if (allViewers || !specific) all else suited
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("IN LUNA", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (shown.size < all.size || allViewers) {
                        TextButton(onClick = { allViewers = !allViewers }) {
                            Text(if (allViewers) "Fewer" else "All viewers")
                        }
                    }
                }
                shown.forEach { (kind, target) ->
                    OpenRow(
                        icon = target.icon,
                        title = target.title,
                        detail = target.detail,
                        highlight = viewerFor(entry) == kind,
                    ) {
                        saveDefault(target.key, target.title)
                        onInternal(kind)
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("APPS", Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)

                val apps = candidates
                when {
                    apps == null -> Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking", style = MaterialTheme.typography.bodySmall)
                    }
                    apps.isEmpty() -> Text(
                        "No installed app handles $declared.",
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        val shownApps = if (allApps || apps.size <= 4) apps else apps.take(3)
                        shownApps.forEach { candidate ->
                            OpenRow(
                                bitmap = icons[candidate.component],
                                icon = Icons.Outlined.Android,
                                title = candidate.label,
                                detail = candidate.packageName,
                                highlight = false,
                            ) {
                                saveDefault(candidate.component, candidate.label)
                                onExternal(candidate, declared)
                            }
                        }
                        if (apps.size > 4) {
                            TextButton(onClick = { allApps = !allApps }, modifier = Modifier.padding(horizontal = 12.dp)) {
                                Text(if (allApps) "Fewer" else "All ${apps.size} apps")
                            }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { always = !always }
                        .padding(horizontal = 20.dp, vertical = 4.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = always, onCheckedChange = { always = it })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Always for ${defaultScope(entry)}", style = MaterialTheme.typography.bodyMedium)
                        Text("Reset in Settings", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun defaultScope(entry: Entry): String {
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    return if (extension.isNotEmpty()) ".$extension files" else entry.mimeType
}

internal data class InternalTarget(val key: String, val title: String, val detail: String, val icon: ImageVector)

/** Without an [archives] engine the archive browser is offered by file extension alone. */
internal fun internalTargets(entry: Entry, archives: ArchiveEngine? = null): List<Pair<ViewerKind, InternalTarget>> = buildList {
    add(ViewerKind.TEXT to InternalTarget("luna:text", "Text editor", "Edit as text", Icons.Outlined.Description))
    add(ViewerKind.CODE to InternalTarget("luna:code", "Code editor", "Edit with syntax highlighting", Icons.Outlined.Code))
    add(ViewerKind.IMAGE to InternalTarget("luna:image", "Image viewer", "Show as a picture", Icons.Outlined.ImageIcon))
    add(ViewerKind.MEDIA to InternalTarget("luna:media", "Player", "Play as audio or video", Icons.Outlined.PlayArrow))
    add(ViewerKind.PDF to InternalTarget("luna:pdf", "PDF viewer", "Read PDF pages", Icons.Outlined.PictureAsPdf))
    add(ViewerKind.DOCUMENT to InternalTarget("luna:document", "Book and document viewer", "Read EPUB, Word (.docx) and PowerPoint (.pptx)", Icons.AutoMirrored.Outlined.MenuBook))
    if (archives?.canBrowse(entry) ?: hasBrowsableExtension(entry)) {
        add(ViewerKind.NONE to InternalTarget("luna:archive", "Archive browser", "Look inside", Icons.Outlined.FolderZip))
    }
    if (looksLikeDatabase(entry)) {
        add(ViewerKind.DATABASE to InternalTarget("luna:sqlite", "Database", "Tables and rows", Icons.Outlined.Storage))
    }
    add(ViewerKind.HEX to InternalTarget("luna:hex", "Hex viewer", "Show as bytes", Icons.Outlined.Memory))
}

private fun hasBrowsableExtension(entry: Entry): Boolean =
    entry.name.substringAfterLast('.', "").lowercase() in BROWSABLE_ARCHIVE_EXTENSIONS

@Composable
private fun OpenRow(
    icon: ImageVector,
    title: String,
    detail: String,
    highlight: Boolean,
    bitmap: ImageBitmap? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.size(28.dp))
        } else {
            Icon(icon, null, Modifier.size(24.dp),
                tint = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (highlight) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun suits(entry: Entry, kind: ViewerKind, declared: String): Boolean {
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    return when (kind) {
        ViewerKind.IMAGE -> declared.startsWith("image/")
        ViewerKind.MEDIA -> declared.startsWith("audio/") || declared.startsWith("video/")
        ViewerKind.PDF -> declared == "application/pdf" || extension == "pdf"
        ViewerKind.DOCUMENT -> extension in setOf("epub", "docx", "pptx") || declared in setOf(
            "application/epub+zip", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        ViewerKind.TEXT, ViewerKind.CODE -> isTextual(entry.name, declared)
        // NONE is the archive browser row, which internalTargets adds only for archives.
        ViewerKind.NONE -> true
        ViewerKind.DATABASE -> true
        ViewerKind.HEX -> true
    }
}

/** By extension only; the SQLite header decides when the file is opened. */
internal fun looksLikeDatabase(entry: Entry): Boolean =
    entry.name.substringAfterLast('.', "").lowercase() in setOf("db", "sqlite", "sqlite3", "db3")
