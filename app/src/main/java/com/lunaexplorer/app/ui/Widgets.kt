@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.lunaexplorer.app.model.on
import com.lunaexplorer.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun rememberThumbnail(entry: Entry, sizePx: Int, folders: Boolean = false): ImageBitmap? {
    val loader = LocalThumbnails.current
    val limits = LocalNetworkThumbnails.current
    val network = remember(loader, entry.ref) { loader?.networkOf(entry.ref) }
    val limit = network?.let { limits.on(it).bytes } ?: Long.MAX_VALUE
    // Folder previews are only loaded when the caller asks; they need a tile-sized slot to be legible.
    val supported = loader != null && (folders || !entry.directory) && loader.supports(entry, limit)
    // Not keyed by sizePx: a UI zoom step changes it, and the old bitmap stays up until the new one is ready.
    var bitmap by remember(entry.ref, entry.modified, entry.size, supported) {
        mutableStateOf(if (supported) loader?.cached(entry, sizePx)?.asImageBitmap() else null)
    }
    var shownPx by remember(entry.ref, entry.modified, entry.size, supported) { mutableIntStateOf(sizePx) }
    LaunchedEffect(entry.ref, entry.modified, entry.size, sizePx, supported, limit) {
        if (!supported || (bitmap != null && shownPx == sizePx)) return@LaunchedEffect
        loader?.load(entry, sizePx, limit)?.let { bitmap = it.asImageBitmap(); shownPx = sizePx }
    }
    return bitmap
}

/** LRU of rendered app icons. Misses are cached as null so each name is looked up once. */
private val appIcons = object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap?>) = size > 300
}

private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

private fun looksLikePackage(name: String): Boolean = name.length in 3..255 && PACKAGE_NAME.matches(name)

internal fun cachedAppIcon(packageName: String): ImageBitmap? = synchronized(appIcons) { appIcons[packageName] }

internal fun appIcon(context: Context, packageName: String): ImageBitmap? {
    synchronized(appIcons) { if (appIcons.containsKey(packageName)) return appIcons[packageName] }
    val rendered = runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val pixels = 128
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, pixels, pixels)
        drawable.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()
    synchronized(appIcons) { appIcons[packageName] = rendered }
    return rendered
}

@Composable
internal fun rememberAppIcon(entry: Entry): ImageBitmap? {
    val wanted = entry.directory && looksLikePackage(entry.name)
    val context = LocalContext.current
    var icon by remember(entry.name, wanted) {
        mutableStateOf(if (wanted) cachedAppIcon(entry.name) else null)
    }
    LaunchedEffect(entry.name, wanted) {
        if (wanted && icon == null) icon = withContext(Dispatchers.IO) { appIcon(context, entry.name) }
    }
    return icon
}

@Composable
internal fun ToolIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(description) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(21.dp))
        }
    }
}

internal enum class FileKind(val icon: ImageVector, val hue: Hue, val labelled: Boolean = false) {
    FOLDER(Icons.Outlined.Folder, Hue.FOLDER),
    IMAGE(Icons.Outlined.Image, Hue.IMAGE),
    VIDEO(Icons.Outlined.Movie, Hue.VIDEO),
    AUDIO(Icons.Outlined.AudioFile, Hue.AUDIO),
    PDF(Icons.Outlined.PictureAsPdf, Hue.PDF),
    WORD(Icons.AutoMirrored.Outlined.Article, Hue.WORD),
    SHEET(Icons.Outlined.TableChart, Hue.SHEET),
    SLIDES(Icons.Outlined.Slideshow, Hue.SLIDES),
    EBOOK(Icons.AutoMirrored.Outlined.MenuBook, Hue.EBOOK),
    CODE(Icons.Outlined.Code, Hue.CODE, labelled = true),
    DATA(Icons.Outlined.DataObject, Hue.DATA, labelled = true),
    SHELL(Icons.Outlined.Terminal, Hue.SHELL),
    DATABASE(Icons.Outlined.Storage, Hue.DATABASE),
    FONT(Icons.Outlined.FontDownload, Hue.FONT),
    SUBTITLE(Icons.Outlined.Subtitles, Hue.VIDEO),
    CERTIFICATE(Icons.Outlined.Key, Hue.CERTIFICATE),
    VAULT(Icons.Outlined.Lock, Hue.VAULT),
    DISK(Icons.Outlined.Album, Hue.ARCHIVE),
    PACKAGE(Icons.Outlined.Android, Hue.PACKAGE),
    LINK(Icons.Outlined.Link, Hue.PLAIN),
    ARCHIVE(Icons.Outlined.FolderZip, Hue.ARCHIVE),
    TEXT(Icons.Outlined.Description, Hue.DOCUMENT),
    OTHER(Icons.AutoMirrored.Outlined.InsertDriveFile, Hue.PLAIN),
}

private val KIND_BY_EXTENSION: Map<String, FileKind> = buildMap {
    fun all(kind: FileKind, vararg extensions: String) = extensions.forEach { put(it, kind) }
    all(FileKind.PDF, "pdf")
    all(FileKind.WORD, "doc", "docx", "docm", "odt", "rtf")
    all(FileKind.SHEET, "xls", "xlsx", "xlsm", "ods", "csv", "tsv")
    all(FileKind.SLIDES, "ppt", "pptx", "pptm", "pps", "ppsx", "odp")
    all(FileKind.EBOOK, "epub", "mobi", "azw", "azw3", "fb2", "djvu")
    all(FileKind.CODE, "py", "kt", "kts", "java", "rs", "go", "c", "h", "cpp", "cc", "cxx", "hpp", "hxx", "cs",
        "js", "mjs", "cjs", "ts", "jsx", "tsx", "rb", "php", "swift", "dart", "lua", "pl", "r", "m", "mm", "scala",
        "groovy", "gradle", "sql", "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "asm", "s", "zig",
        "nim", "ex", "exs", "erl", "hs", "clj", "lisp", "el", "vim")
    all(FileKind.DATA, "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "env", "xml", "plist")
    all(FileKind.SHELL, "sh", "bash", "zsh", "fish", "ksh", "bat", "cmd", "ps1")
    all(FileKind.DATABASE, "db", "sqlite", "sqlite3", "db3")
    all(FileKind.FONT, "ttf", "otf", "ttc", "woff", "woff2")
    all(FileKind.SUBTITLE, "srt", "vtt", "ass", "ssa", "sub")
    all(FileKind.CERTIFICATE, "pem", "crt", "cer", "der", "p12", "pfx", "jks", "keystore")
    all(FileKind.VAULT, "kdbx", "kdb")
    all(FileKind.DISK, "iso", "img", "dmg")
    all(FileKind.PACKAGE, "apk", "xapk", "apks", "apkm")
    all(FileKind.ARCHIVE, "zip", "7z", "rar", "tar", "gz", "tgz", "xz", "bz2", "zst", "jar")
}

/** Media types win over the extension, as they do when opening: a .ts that is MPEG video is a video. */
internal fun fileKind(entry: Entry): FileKind = when {
    entry.directory -> FileKind.FOLDER
    entry.mimeType.startsWith("image/") -> FileKind.IMAGE
    entry.mimeType.startsWith("video/") -> FileKind.VIDEO
    entry.mimeType.startsWith("audio/") -> FileKind.AUDIO
    entry.mimeType == "inode/symlink" -> FileKind.LINK
    else -> KIND_BY_EXTENSION[entry.name.substringAfterLast('.', "").lowercase()] ?: when {
        entry.mimeType == "application/pdf" -> FileKind.PDF
        entry.mimeType == "application/vnd.android.package-archive" -> FileKind.PACKAGE
        entry.mimeType.contains("zip") -> FileKind.ARCHIVE
        entry.mimeType.startsWith("text/") -> FileKind.TEXT
        else -> FileKind.OTHER
    }
}

/** Badge fills for languages with a colour people already associate with them; the rest use [Hue.CODE]. */
private val LANGUAGE_FILL: Map<String, Color> = buildMap {
    fun all(fill: Long, vararg extensions: String) = extensions.forEach { put(it, Color(fill)) }
    all(0xFF3572A5, "py")
    all(0xFFF0DB4F, "js", "mjs", "cjs", "jsx")
    all(0xFF3178C6, "ts", "tsx")
    all(0xFF7F52FF, "kt", "kts")
    all(0xFFB07219, "java")
    all(0xFFB7410E, "rs")
    all(0xFF0090B8, "go")
    all(0xFF546E7A, "c", "h")
    all(0xFFD81B60, "cpp", "cc", "cxx", "hpp", "hxx")
    all(0xFF178600, "cs")
    all(0xFFA31515, "rb")
    all(0xFF4F5D95, "php")
    all(0xFFF05138, "swift")
    all(0xFF00897B, "dart")
    all(0xFF3D3DC8, "lua")
    all(0xFFE34C26, "html", "htm")
    all(0xFF663399, "css", "scss", "sass", "less")
    all(0xFFC77700, "sql")
    all(0xFF2E8B57, "vue")
}

internal fun badgeFill(entry: Entry, kind: FileKind): Color? =
    if (kind == FileKind.CODE) LANGUAGE_FILL[entry.name.substringAfterLast('.', "").lowercase()] else null

internal fun badgeLabel(entry: Entry, kind: FileKind): String? =
    entry.name.substringAfterLast('.', "").takeIf { kind.labelled && it.length in 1..4 }?.uppercase()

@Composable
internal fun fileColor(entry: Entry) = when {
    entry.directory -> MaterialTheme.colorScheme.primary
    entry.capabilities.isEmpty() -> MaterialTheme.colorScheme.outline
    entry.mimeType.startsWith("image/") || entry.mimeType.startsWith("video/") -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "Unknown size"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.getDefault(), if (value >= 10) "%.0f %s" else "%.1f %s", value, units[index])
}

internal fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

/** Copies plain text. LocalClipboard's setter suspends; this keeps click handlers plain. */
@Composable
internal fun rememberCopyText(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        fun(text: String) { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text))) } }
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).combinedClickable(onClick = { onChange(!checked) }, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchRow(title, checked, null, onChange)

@Composable
internal fun SortDirectionRow(descending: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(role = Role.Button) { onChange(!descending) },
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
            null, Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(if (descending) "Descending" else "Ascending", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun SectionHeading(text: String) {
    Spacer(Modifier.height(22.dp))
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
}

@Composable
internal fun SheetAction(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val ink = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val quiet = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Row(Modifier.fillMaxWidth().combinedClickable(enabled = enabled, onClick = onClick, role = Role.Button)
        .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = quiet)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = ink)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = quiet)
            }
        }
    }
}

// Register Back inside the sheet window after Material so it dismisses instead of collapsing.
@Composable
internal fun BottomSheet(
    onDismiss: () -> Unit,
    expanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = expanded)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BackHandler {
            // Dismiss only if the hide animation ran to completion.
            scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
        }
        content()
    }
}
