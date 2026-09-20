package com.lunaexplorer.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lunaexplorer.core.AndroidBinaryXml
import com.lunaexplorer.core.Capability
import com.lunaexplorer.core.Entry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Above either limit one text field cannot lay the file out without stalling the main thread. */
internal const val MAX_EDITABLE_CHARS = 256 * 1024
internal const val MAX_EDITABLE_LINE_CHARS = 20_000

internal fun tooLargeToEdit(text: String): Boolean {
    if (text.length > MAX_EDITABLE_CHARS) return true
    var lineLength = 0
    for (char in text) {
        if (char == '\n' || char == '\r') lineLength = 0
        else if (++lineLength > MAX_EDITABLE_LINE_CHARS) return true
    }
    return false
}

/** The key of a decoded manifest's session; a file's own session is keyed by its ref. */
internal data class ManifestOf(val source: Any)

/** [derived] text is not the file's bytes (a decoded manifest or compiled XML): it is shown as rows and never saved. */
internal class EditorContent(val text: String, val writable: Boolean, val derived: Boolean)

/** One open draft or read-only text. A file's session is held by the ViewModel so it survives Activity recreation. */
internal class TextEditorSession private constructor(
    val key: Any,
    private val label: String?,
    entry: Entry?,
    code: Boolean,
    language: CodeLanguage,
    scope: CoroutineScope,
    private val unreadable: String,
    load: suspend () -> Result<EditorContent>,
    private val write: ((Entry, String, (Result<Entry>) -> Unit) -> Unit)?,
) {
    var file by mutableStateOf(entry)
        private set
    val title: String get() = file?.name ?: label.orEmpty()
    private var draft by mutableStateOf<String?>(null)
    var text: String?
        get() = draft
        set(value) {
            draft = value
            find.sourceChanged()
        }
    /** Read-only rows for derived text or a file too large to edit; [text] then stays null and nothing can be saved. */
    var document by mutableStateOf<TextDocument?>(null)
        private set
    private var original by mutableStateOf("")
    var failure by mutableStateOf<String?>(null)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    private var writable by mutableStateOf(false)
    val editable: Boolean get() = writable && document == null
    val tooLarge: Boolean get() = writable && document != null
    var saving by mutableStateOf(false)
        private set
    var closeReady by mutableStateOf(false)
        private set
    var confirmExit by mutableStateOf(false)
    var wrap by mutableStateOf(!code)
    var language by mutableStateOf(language)
    val changed: Boolean get() = draft != null && draft != original
    val find = TextFinder(scope) { draft ?: document?.text }

    // Drafts stay in ViewModel memory: up to 8 MiB is too large for a saved-state Bundle.
    private val loading = scope.launch {
        val content = load().getOrElse {
            failure = it.message ?: unreadable
            return@launch
        }
        // Measure off the main thread; an oversized file becomes lazy rows, never a draft.
        val rows = withContext(Dispatchers.Default) {
            if (content.derived || tooLargeToEdit(content.text)) TextDocument.prepare(content.text) { ensureActive() } else null
        }
        writable = content.writable
        if (rows != null) {
            document = rows
        } else {
            original = content.text
            draft = original
        }
        find.sourceLoaded()
    }

    fun save(closeAfterSave: Boolean) {
        if (saving || !editable) return
        val target = file ?: return
        val write = write ?: return
        val content = draft ?: return
        saving = true
        saveError = null
        write(target, content) { result ->
            saving = false
            result.fold(
                onSuccess = {
                    file = it
                    original = content
                    confirmExit = false
                    closeReady = closeAfterSave
                },
                onFailure = { saveError = it.message ?: "This file could not be saved" },
            )
        }
    }

    fun close() {
        loading.cancel()
        find.cancel()
    }

    companion object {
        fun forFile(entry: Entry, files: BrowserFiles, scope: CoroutineScope, code: Boolean) = TextEditorSession(
            entry.ref, null, entry, code, CodeLanguage.fromFilename(entry.name), scope, "This file could not be read",
            load = {
                val writable = Capability.WRITE in files.currentCapabilities(entry)
                files.readBytes(entry, limit = 8L * 1024 * 1024).map { bytes ->
                    withContext(Dispatchers.Default) {
                        if (!AndroidBinaryXml.looksCompiled(bytes)) {
                            return@withContext EditorContent(String(bytes, Charsets.UTF_8), writable, derived = false)
                        }
                        val xml = try {
                            AndroidBinaryXml.toText(AndroidBinaryXml.decode(bytes))
                        } catch (error: Exception) {
                            ensureActive()
                            null
                        }
                        // Compiled XML that does not decode is shown as text but stays read-only: saving that text would not restore its bytes.
                        if (xml != null) EditorContent(xml, writable = false, derived = true)
                        else EditorContent(String(bytes, Charsets.UTF_8), writable = false, derived = false)
                    }
                }
            },
            write = files::writeText,
        )

        fun forText(key: Any, title: String, scope: CoroutineScope, unreadable: String, load: suspend () -> String) = TextEditorSession(
            key, title, null, code = true, CodeLanguage.XML, scope, unreadable,
            load = {
                try {
                    Result.success(EditorContent(load(), writable = false, derived = true))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            },
            write = null,
        )
    }
}
