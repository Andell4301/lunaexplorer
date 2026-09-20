@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lunaexplorer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lunaexplorer.core.ArchiveEngine
import com.lunaexplorer.core.ArchiveFormat
import com.lunaexplorer.core.archiveFormatOf
import com.lunaexplorer.core.ArchiveOptions
import com.lunaexplorer.core.CompressionPreset
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.ZipEncryption

val LocalArchives = staticCompositionLocalOf<ArchiveEngine?> { null }

@Composable
fun ArchiveDialog(entries: List<Entry>, onDismiss: () -> Unit, onCreate: (String, ArchiveOptions) -> Unit) {
    val suggested = remember(entries) {
        entries.singleOrNull()?.name?.substringBeforeLast('.')?.ifBlank { null } ?: "archive"
    }
    var name by rememberSaveable(suggested) { mutableStateOf(suggested) }
    var format by rememberSaveable { mutableStateOf(ArchiveFormat.ZIP) }
    var level by rememberSaveable { mutableIntStateOf(CompressionPreset.NORMAL.level) }
    var comment by rememberSaveable { mutableStateOf("") }
    var encryption by rememberSaveable { mutableStateOf(ZipEncryption.NONE) }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }
    val fileName = "${name.trim()}.${format.extension}"
    val singleFileOnly = (format == ArchiveFormat.GZIP || format == ArchiveFormat.XZ) &&
        entries.count { !it.directory } != entries.size
    val encrypting = format == ArchiveFormat.ZIP && encryption != ZipEncryption.NONE
    val passwordsMatch = !encrypting || (password.isNotEmpty() && password == confirm)
    val valid = name.isNotBlank() && !singleFileOnly && passwordsMatch

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to archive") },
        text = {
            Column(Modifier.fastVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true,
                    supportingText = { Text(fileName) }, modifier = Modifier.fillMaxWidth())

                Text("FORMAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveFormat.entries.filter { it.creatable }.forEach { option ->
                        FilterChip(selected = format == option, onClick = { format = option },
                            label = { Text(option.label) })
                    }
                }
                if (singleFileOnly) {
                    Text("${format.label} holds a single file.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (format.compressible) {
                    Text("COMPRESSION  ·  $level", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompressionPreset.entries.forEach { option ->
                            FilterChip(selected = level == option.level, onClick = { level = option.level },
                                label = { Text(option.label) })
                        }
                    }
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { level = it.toInt() },
                        valueRange = 0f..9f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (format == ArchiveFormat.ZIP) {
                    OutlinedTextField(comment, { comment = it }, label = { Text("Comment (optional)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                if (format == ArchiveFormat.ZIP) {
                    Text("ENCRYPTION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZipEncryption.entries.forEach { option ->
                            FilterChip(selected = encryption == option, onClick = { encryption = option },
                                label = { Text(option.label) })
                        }
                    }
                    if (encrypting) {
                        OutlinedTextField(password, { password = it }, label = { Text("Password") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                            })
                        OutlinedTextField(confirm, { confirm = it }, label = { Text("Confirm password") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            isError = confirm.isNotEmpty() && confirm != password,
                            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation())
                    }
                }

                Text("${entries.size} item${if (entries.size == 1) "" else "s"} will be added.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(fileName, ArchiveOptions(format, level, comment.trim(),
                        if (encrypting) encryption else ZipEncryption.NONE, if (encrypting) password else ""))
                },
                enabled = valid,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * [onExtract] receives the new folder name (null extracts loose), the password ("" if none) and whether to
 * extract into the current folder; false means the archive is carried and extracted at a folder chosen later.
 */
@Composable
fun ExtractDialog(archive: Entry, onDismiss: () -> Unit, onExtract: (String?, String, Boolean) -> Unit) {
    val suggested = remember(archive) {
        archive.name.removeSuffix(".gz").removeSuffix(".xz").removeSuffix(".bz2").removeSuffix(".tar")
            .substringBeforeLast('.').ifBlank { "extracted" }
    }
    var intoFolder by rememberSaveable { mutableStateOf(true) }
    var folder by rememberSaveable(suggested) { mutableStateOf(suggested) }
    var password by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }
    val engine = LocalArchives.current
    val encryptable = remember(archive, engine) {
        (engine?.formatOf(archive.name) ?: archiveFormatOf(archive.name) ?: ArchiveFormat.ZIP) in ENCRYPTABLE
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extract ${archive.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Into a new folder", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = intoFolder, onCheckedChange = { intoFolder = it })
                }
                if (intoFolder) {
                    OutlinedTextField(folder, { folder = it }, label = { Text("Folder name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Replaces colliding names.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (encryptable) {
                    OutlinedTextField(password, { password = it }, label = { Text("Password") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                        })
                }
            }
        },
        confirmButton = {
            val name = { if (intoFolder) folder.trim() else null }
            val secret = { if (encryptable) password else "" }
            val ready = !intoFolder || folder.isNotBlank()
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onExtract(name(), secret(), false) }, enabled = ready) {
                    Text("Choose folder")
                }
                TextButton(onClick = { onExtract(name(), secret(), true) }, enabled = ready) {
                    Text("Extract here")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val ENCRYPTABLE = setOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR)

internal val OPEN_AS_TYPES = listOf(
    "text/plain" to "Text",
    "text/html" to "Web page",
    "application/json" to "JSON",
    "application/xml" to "XML",
    "image/*" to "Image",
    "image/jpeg" to "JPEG image",
    "image/png" to "PNG image",
    "audio/*" to "Audio",
    "video/*" to "Video",
    "application/pdf" to "PDF",
    "application/zip" to "Zip archive",
    "application/vnd.android.package-archive" to "App installer",
    "application/epub+zip" to "EPUB book",
    "application/octet-stream" to "Raw data",
    "*/*" to "Anything",
)
