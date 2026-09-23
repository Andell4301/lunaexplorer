@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import android.os.Build
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.model.Screen
import com.lunaexplorer.app.model.ServedFolder
import com.lunaexplorer.app.model.SubtitleAppearance
import com.lunaexplorer.app.model.VideoExitBehavior
import com.lunaexplorer.app.storage.LunaDocumentsProvider
import com.lunaexplorer.app.storage.ViewerAdvertising
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun StorageAccessSettings(state: BrowserState, viewModel: BrowserViewModel, actions: LunaActions) {
    val preferences = state.preferences
    val onChange = viewModel::setPreferences
    Text(
        if (state.fullAccess) "Luna can read and write all shared storage."
        else "Luna can only reach locations you add one at a time.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.fullAccess) FilledTonalButton(onClick = actions.requestFullAccess) { Text("Turn on full access") }
        OutlinedButton(onClick = { actions.grantFolder(state.directoryPath) }) { Text("Add a location") }
    }
    Spacer(Modifier.height(10.dp))
    SwitchRow("Show Root", preferences.showDeviceRoot,
        "Browse the parts of the filesystem Android leaves readable") {
        onChange(preferences.copy(showDeviceRoot = it))
    }
    // Before Android 11 those folders are open to Luna as they are.
    if (Build.VERSION.SDK_INT >= 30) {
        SwitchRow("Use Shizuku for Android/data and Android/obb", preferences.shizuku,
            if (preferences.shizuku) helperStatus(state.helper, viewModel.helperUid) else null) {
            onChange(preferences.copy(shizuku = it))
        }
        if (preferences.shizuku) helperAction(state.helper, viewModel, LocalContext.current)?.let { (label, act) ->
            OutlinedButton(onClick = act) { Text(label) }
            Spacer(Modifier.height(6.dp))
        }
    }
    SwitchRow("Show Luna's own data", viewModel.bookmarks.appDataOn(state),
        "Add a bookmark to Luna's app data folder. No other apps can access this " +
            "folder, but it is deleted if you uninstall Luna") {
        viewModel.setAppDataBookmark(it)
    }
}

@Composable
internal fun StartSettings(preferences: Preferences, directoryPath: String?, onChange: (Preferences) -> Unit) {
    val custom = preferences.startPath.isNotBlank()
    Text("Where Luna opens, and where back stops rather than going further.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    START_SCREENS.forEach { screen ->
        ChoiceRow(startLabel(screen), !custom && preferences.startScreen == screen) {
            onChange(preferences.copy(startScreen = screen, startPath = ""))
        }
    }
    ChoiceRow("Custom folder", custom) {
        onChange(preferences.copy(startScreen = Screen.BROWSER,
            startPath = preferences.startPath.ifBlank { directoryPath.orEmpty() }))
    }
    if (custom) {
        OutlinedTextField(
            value = preferences.startPath,
            onValueChange = { onChange(preferences.copy(startPath = it)) },
            label = { Text("Folder") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Start folder" },
        )
    }
}

private val START_SCREENS = listOf(Screen.HOME, Screen.BROWSER, Screen.STORAGE, Screen.APPS, Screen.RECYCLE_BIN,
    Screen.PROCEDURES)

@Composable
internal fun DocumentProviderSettings(preferences: Preferences, viewModel: BrowserViewModel, onChange: (Preferences) -> Unit) {
    // Editors are inline: a text field in an AlertDialog inside the settings Dialog never goes idle.
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    SwitchRow("Serve folders to other apps", preferences.documentsProvider) {
        onChange(preferences.copy(documentsProvider = it))
    }
    preferences.servedFolders.forEach { served ->
        ServedFolderRow(served, onOpen = {
            adding = false
            renaming = if (renaming == served.path) null else served.path
        }) {
            if (renaming == served.path) renaming = null
            onChange(preferences.copy(servedFolders = preferences.servedFolders - served))
        }
        if (renaming == served.path) {
            // Roots are identified by path, so a rename keeps the grants other apps already hold.
            NameServedFolder(served, onCancel = { renaming = null }) { renamed ->
                renaming = null
                onChange(preferences.copy(servedFolders = preferences.servedFolders.map {
                    if (it.path == served.path) it.copy(name = renamed) else it
                }))
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    if (!adding) {
        OutlinedButton(onClick = { adding = true; renaming = null }) { Text("Add a folder") }
    } else {
        AddServedFolder(viewModel, preferences.showHidden, onCancel = { adding = false }) { folder, name ->
            adding = false
            // No trimming: the path is stored exactly as typed.
            if (folder.isNotBlank() && preferences.servedFolders.none { it.path == folder }) {
                onChange(preferences.copy(servedFolders = preferences.servedFolders + ServedFolder(folder, name)))
            }
        }
    }
}

@Composable
private fun ServedFolderRow(served: ServedFolder, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .combinedClickable(onClick = onOpen, role = Role.Button),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(served.name.ifBlank { LunaDocumentsProvider.titleOf(served.path) },
                style = MaterialTheme.typography.bodyLarge)
            Text(served.path, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ToolIcon(Icons.Outlined.Close, "Remove ${served.path}", onClick = onRemove)
    }
}

@Composable
private fun NameServedFolder(served: ServedFolder, onCancel: () -> Unit, onDone: (String) -> Unit) {
    var name by rememberSaveable(served.path) { mutableStateOf(served.name) }
    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text("Name") },
        placeholder = { Text(LunaDocumentsProvider.titleOf(served.path)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name for ${served.path}" },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onDone(name) }) { Text("Save") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun AddServedFolder(viewModel: BrowserViewModel, showHidden: Boolean, onCancel: () -> Unit, onAdd: (String, String) -> Unit) {
    var folder by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val common by produceState(emptyList<Pair<String, String>>()) { value = viewModel.files.commonFolders() }
    OutlinedTextField(
        value = folder, onValueChange = { folder = it },
        label = { Text("Folder") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Served folder" },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // A full window of its own, and without a text field: those never go idle in a dialog nested in this one.
        AssistChip(onClick = { choosing = true }, label = { Text("Browse") },
            leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)) })
        common.forEach { (title, path) -> SuggestionChip(onClick = { folder = path }, label = { Text(title) }) }
    }
    if (choosing) FolderChooser(viewModel, showHidden, onDismiss = { choosing = false }) { chosen -> folder = chosen; choosing = false }
    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text("Name") },
        placeholder = { Text(LunaDocumentsProvider.titleOf(folder)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Served folder name" },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onAdd(folder, name) }, enabled = folder.isNotBlank()) { Text("Add") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

internal fun startLabel(screen: Screen): String = screen.title.ifEmpty { "Files" }

@Composable
internal fun DeletingSettings(state: BrowserState, viewModel: BrowserViewModel, onRecycleBin: () -> Unit) {
    val preferences = state.preferences
    val onChange = viewModel::setPreferences
    SwitchRow("Delete to the recycle bin", preferences.recycleBin,
        "Moves deleted items to a recycle bin on the same storage so you can restore them") {
        onChange(preferences.copy(recycleBin = it))
        viewModel.rememberForSession(recycleBin = it)
    }
    SwitchRow("Ask before deleting", preferences.confirmDelete) {
        onChange(preferences.copy(confirmDelete = it))
        viewModel.rememberForSession(confirmDelete = it)
    }
    SwitchRow("Large deletion warning", preferences.warnLargeDelete) {
        onChange(preferences.copy(warnLargeDelete = it))
    }
    if (preferences.warnLargeDelete) {
        Spacer(Modifier.height(10.dp))
        LargeDeleteField(preferences.largeDeleteGb) { onChange(preferences.copy(largeDeleteGb = it)) }
    }
    if (state.trashCount > 0) {
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onRecycleBin) { Text("Open recycle bin (${state.trashCount})") }
    }
}

@Composable
private fun LargeDeleteField(saved: Int, onSave: (Int) -> Unit) {
    var typed by rememberSaveable { mutableStateOf(saved.toString()) }
    LaunchedEffect(saved) { typed = saved.toString() }
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it >= 1 }
    OutlinedTextField(
        value = typed,
        onValueChange = { text ->
            typed = text
            parse(text)?.takeIf { it != saved }?.let(onSave)
        },
        label = { Text("Warn at") },
        suffix = { Text("GB") },
        singleLine = true,
        isError = parse(typed) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun OpenWithLunaSettings(preferences: Preferences, onChange: (Preferences) -> Unit) {
    ViewerAdvertising.kinds.forEach { kind ->
        SwitchRow(kind.label, kind.key in preferences.openWithLuna) { on ->
            onChange(preferences.copy(openWithLuna = if (on) preferences.openWithLuna + kind.key else preferences.openWithLuna - kind.key))
        }
    }
}

@Composable
internal fun DebugLogSettings(recordingLog: Boolean, viewModel: BrowserViewModel, onViewLog: () -> Unit) {
    SwitchRow("Record debug log", recordingLog) { viewModel.setDebugLogging(it) }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = onViewLog) { Text("View log") }
}

@Composable
internal fun AboutSettings(onReset: () -> Unit) {
    SectionHeading("Reset")
    OutlinedButton(onClick = onReset) { Text("Reset all settings") }
    Spacer(Modifier.height(6.dp))
    Text("Everything here, every folder's own view and sort, and the bookmarks these switches " +
        "own. Your files, your own bookmarks and the access you have granted stay as they are.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    SectionHeading("About")
    BuildFacts()
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun BuildFacts() {
    val context = LocalContext.current
    val facts = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(info.lastUpdateTime))
            Triple(info.versionName ?: "?", code, stamp)
        }.getOrNull()
    }
    Text("Luna Explorer ${facts?.first ?: "?"}", style = MaterialTheme.typography.bodyMedium)
    facts?.let { (_, code, stamp) ->
        Text("Build $code  ·  installed $stamp", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PlayerGestureSettings(preferences: Preferences, onChange: (Preferences) -> Unit) {
    SwitchRow("Double tap controls", preferences.playerDoubleTap) {
        onChange(preferences.copy(playerDoubleTap = it))
    }
    SwitchRow("Swipe to seek", preferences.playerSwipeSeek) {
        onChange(preferences.copy(playerSwipeSeek = it))
    }
    SwitchRow("Volume gesture", preferences.playerVolumeGesture) {
        onChange(preferences.copy(playerVolumeGesture = it))
    }
    SwitchRow("Brightness gesture", preferences.playerBrightnessGesture) {
        onChange(preferences.copy(playerBrightnessGesture = it))
    }
}

@Composable
internal fun AudioPlayerSettings(preferences: Preferences, onChange: (Preferences) -> Unit) {
    SwitchRow("Background play", preferences.audioBackground) {
        onChange(preferences.copy(audioBackground = it))
    }
}

@Composable
internal fun PlayerSettings(preferences: Preferences, onChange: (Preferences) -> Unit) {
    Text("Default exit behavior", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    VideoExitBehavior.entries.forEach { behavior ->
        ChoiceRow(behavior.label, preferences.videoExitBehavior == behavior) {
            onChange(preferences.copy(videoExitBehavior = behavior))
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("Gestures", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    PlayerGestureSettings(preferences, onChange)
    Spacer(Modifier.height(16.dp))
    Text("Subtitle appearance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
    val appearance = preferences.subtitleAppearance
    val onAppearance = { changed: SubtitleAppearance -> onChange(preferences.copy(subtitleAppearance = changed)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SubtitleAppearancePreview(appearance, Modifier.fillMaxWidth().height(120.dp))
        SubtitleAppearanceControls(appearance, onAppearance)
        TextButton(onClick = { onAppearance(SubtitleAppearance()) }) { Text("Reset appearance") }
    }
    Spacer(Modifier.height(32.dp))
}
