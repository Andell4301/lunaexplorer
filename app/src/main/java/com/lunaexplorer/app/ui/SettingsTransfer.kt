package com.lunaexplorer.app.ui

import android.app.Application
import android.net.Uri
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.data.*
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.storage.Secrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** An import writes through the ordinary setters, so it has the same side effects as changing each setting by hand. */
class SettingsTransfer(
    private val application: Application,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<BrowserState>,
    private val resolver: PathResolver,
    private val vault: VaultAccess,
    private val accounts: SmbAccounts,
    private val b2: B2Accounts,
    private val servers: TransferAccounts,
    private val persist: () -> Unit,
    private val message: (String) -> Unit,
    private val setPreferences: (Preferences) -> Unit,
    private val refreshAccess: () -> Unit,
) {
    private val _preview = MutableStateFlow<TransferPreview?>(null)
    val preview: StateFlow<TransferPreview?> = _preview

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    fun canCarryPasswords(): Boolean = graph.vault.canLock() || graph.vault.secrets.value != null

    /** [withPasswords] only has an effect while the vault is already open; this never opens it. */
    fun snapshot(withPasswords: Boolean = false): TransferSource {
        val current = state.value
        return TransferSource(
            preferences = current.preferences,
            bookmarks = current.bookmarks,
            homeBookmarks = current.homeBookmarks,
            folderViews = current.folderViews,
            smbAccounts = current.smbAccounts,
            b2Accounts = current.b2Accounts,
            transferAccounts = current.transferAccounts,
            procedures = graph.procedures.procedures.value,
            vaultLocked = current.vaultLocked,
            openDefaults = _openDefaults.value,
            recordingLog = graph.debugLog.enabled,
            // A secret can outlive its account when that was removed with the vault shut; it does not travel.
            passwords = if (withPasswords) graph.vault.secrets.value?.smbPasswords.orEmpty()
                .filterKeys { id -> current.smbAccounts.any { it.id == id } } else emptyMap(),
            transferCredentials = if (withPasswords) graph.vault.secrets.value?.transferCredentials.orEmpty()
                .filterKeys { id -> current.transferAccounts.any { it.id == id } } else emptyMap(),
            b2Keys = if (withPasswords) graph.vault.secrets.value?.b2Keys.orEmpty()
                .filterKeys { id -> current.b2Accounts.any { it.id == id } } else emptyMap(),
        )
    }

    /** Loaded asynchronously because the read shares the database lock. A flow, so the chooser redraws on arrival. */
    private val _openDefaults = MutableStateFlow<List<OpenDefault>>(emptyList())
    val openDefaults: StateFlow<List<OpenDefault>> = _openDefaults

    fun loadOpenDefaults() { scope.launch { refreshOpenDefaults(); graph.procedures.refresh() } }

    private suspend fun refreshOpenDefaults() {
        _openDefaults.value = runCatching { graph.database.openDefaults() }.getOrDefault(emptyList())
    }

    /** Whether [unlockForExport] opened a vault that was shut, and so has it to shut again. */
    private var openedForExport = false

    /** Opens the vault so an export can carry passwords. The caller must have authenticated the user first. */
    fun unlockForExport(): Boolean {
        val wasOpen = vault.isOpen
        val opened = vault.open()
        openedForExport = opened && !wasOpen
        _unlocked.value = opened
        if (!opened) message("The stored passwords could not be read")
        return opened
    }

    /** Closes the vault again. Called on every way out of an export, including a cancelled picker. */
    private fun forgetUnlock() {
        if (openedForExport) vault.close()
        openedForExport = false
        _unlocked.value = false
    }

    // The file picker can outlive the Activity and deliver its result to a new composition.
    private val _pendingExport = MutableStateFlow<PendingExport?>(null)

    private data class PendingExport(val ids: Set<String>, val withPasswords: Boolean)

    fun prepareExport(ids: Set<String>, withPasswords: Boolean) {
        _pendingExport.value = PendingExport(ids, withPasswords)
    }

    fun onExportTarget(target: Uri, onDone: (Boolean) -> Unit = {}) {
        val pending = _pendingExport.value
        if (pending == null) {
            // The picker has already created the file, so report that nothing was written to it.
            message("That export had already been put down")
            forgetUnlock()
            onDone(false)
            return
        }
        _pendingExport.value = null
        export(target, pending.ids, pending.withPasswords, onDone)
    }

    fun cancelExport() { _pendingExport.value = null; forgetUnlock() }

    /** Count of finished exports. The screen watches this because it may have been rebuilt since it started one. */
    private val _exported = MutableStateFlow(0)
    val exported: StateFlow<Int> = _exported

    fun export(target: Uri, ids: Set<String>, withPasswords: Boolean, onDone: (Boolean) -> Unit) {
        scope.launch {
            val source = snapshot(withPasswords)
            val document = TransferCodec.export(source, ids, app = versionName(),
                written = System.currentTimeMillis())
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    application.contentResolver.openOutputStream(target, "wt")?.use { stream ->
                        stream.write(TransferCodec.encode(document).toByteArray())
                    } ?: error("No file to write to")
                }.isSuccess
            }
            message(if (written) "Exported ${ids.size} ${if (ids.size == 1) "setting" else "settings"}"
            else "The settings could not be written")
            if (written) _exported.value++
            forgetUnlock()
            onDone(written)
        }
    }

    fun openForImport(source: Uri, onDone: (Boolean) -> Unit) {
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    application.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            val document = raw?.let(TransferCodec::decode)
            if (document == null) {
                message("That file is not a Luna settings export")
                onDone(false); return@launch
            }
            if (!document.readable) {
                message("That export was written by a newer Luna")
                onDone(false); return@launch
            }
            refreshOpenDefaults()
            graph.procedures.refresh()
            val here = snapshot()
            // transferPathCheck is global: point it at real storage only while the preview is built.
            val known = withContext(Dispatchers.IO) { reachablePaths(document) }
            transferPathCheck = PathCheck { path -> path in known }
            val read = try { SettingsPreviewer.of(document, here) } finally {
                transferPathCheck = PathCheck { true }
            }
            _preview.value = read
            onDone(true)
        }
    }

    /** Paths in [document] that this device can reach. One batch, so the preview does not hit storage per row. */
    private suspend fun reachablePaths(document: TransferDocument): Set<String> {
        val wanted = mutableSetOf<String>()
        val here = snapshot()
        document.values.forEach { (id, value) ->
            val unit = SettingsRegistry.unit(id) ?: return@forEach
            unit.items?.invoke(value, here)?.forEach { item ->
                if (item.probe.isNotBlank()) wanted += item.probe
            }
        }
        val installed by lazy {
            runCatching {
                application.packageManager.getInstalledPackages(0).mapTo(HashSet()) { it.packageName }
            }.getOrDefault(emptySet())
        }
        return wanted.filterTo(HashSet()) { path ->
            when {
                path.startsWith(APP_TARGET) -> {
                    val target = path.removePrefix(APP_TARGET)
                    // A component name carries the package before the slash.
                    installed.any { it == target.substringBefore('/') }
                }
                // Paths are used exactly as written.
                path.startsWith("/") -> runCatching { File(path).exists() }.getOrDefault(false)
                else -> resolver.locate(path) != null
            }
        }
    }

    fun dropImport() { _preview.value = null }

    fun needsAuthentication(chosen: Set<String>, items: Map<String, Set<String>>): Boolean {
        val read = _preview.value ?: return false
        val before = snapshot()
        val after = SettingsPreviewer.apply(read, before, chosen, items).source
        val changesLock = after.vaultLocked != before.vaultLocked && (!after.vaultLocked || vault.canLock())
        val writesSecrets = after.passwords != before.passwords || after.b2Keys != before.b2Keys ||
            after.transferCredentials != before.transferCredentials
        return changesLock || (writesSecrets && !vault.usable())
    }

    fun import(chosen: Set<String>, items: Map<String, Set<String>>, onDone: () -> Unit) {
        val read = _preview.value ?: return
        scope.launch {
            val before = snapshot()
            val result = SettingsPreviewer.apply(read, before, chosen, items)
            val after = result.source

            // Lists and accounts first, so the preference write below publishes settled state. vaultLocked is
            // not copied: it identifies the vault's key and may only change through setLocked, which re-encrypts.
            state.update {
                it.copy(
                    bookmarks = after.bookmarks,
                    homeBookmarks = after.homeBookmarks,
                    folderViews = after.folderViews,
                    smbAccounts = after.smbAccounts,
                    b2Accounts = after.b2Accounts,
                    transferAccounts = after.transferAccounts,
                )
            }
            accounts.apply()
            b2.apply()
            servers.apply()
            // Another key or bucket sees other files, so listings saved under the old one go.
            after.b2Accounts.forEach { arrived ->
                val held = before.b2Accounts.firstOrNull { it.id == arrived.id }
                if (held != null && (held.keyId != arrived.keyId || held.bucket != arrived.bucket)) b2.clearCache(arrived.id)
            }

            if (after.openDefaults != before.openDefaults) {
                after.openDefaults.forEach {
                    runCatching { graph.database.rememberOpenDefault(it.key, it.target, it.mime, it.label) }
                }
            }
            val refusals = mutableListOf<Pair<String, String>>()
            if (after.procedures != before.procedures) {
                try {
                    graph.procedures.replace(after.procedures)
                    graph.procedureScheduler.synchronize()
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    refusals += "Stored procedures" to "they could not be written"
                }
            }
            // A secret whose account no longer exists is dropped.
            if (after.passwords != before.passwords) {
                val known = state.value.smbAccounts.mapTo(HashSet()) { it.id }
                writeSecrets(after.passwords, known, "server") {
                    it.copy(smbPasswords = (it.smbPasswords + after.passwords).filterKeys { id -> id in known })
                }?.let { refusals += "Stored passwords" to it }
            }
            if (after.b2Keys != before.b2Keys) {
                val known = state.value.b2Accounts.mapTo(HashSet()) { it.id }
                writeSecrets(after.b2Keys, known, "account") {
                    it.copy(b2Keys = (it.b2Keys + after.b2Keys).filterKeys { id -> id in known })
                }?.let { refusals += "Stored B2 keys" to it }
            }
            if (after.transferCredentials != before.transferCredentials) {
                val known = state.value.transferAccounts.mapTo(HashSet()) { it.id }
                writeSecrets(after.transferCredentials, known, "server") {
                    it.copy(transferCredentials = (it.transferCredentials + after.transferCredentials).filterKeys { id -> id in known })
                }?.let { refusals += "Stored FTP/SFTP credentials" to it }
            }
            // Passwords first, then the lock: relocking rewrites the file the passwords just went into.
            if (after.vaultLocked != before.vaultLocked && !vault.setLocked(after.vaultLocked)) {
                refusals += "Keep the vault behind authentication" to "the vault could not be rewritten"
            }
            if (after.recordingLog != before.recordingLog) graph.debugLog.setRecording(after.recordingLog)

            withContext(Dispatchers.IO) {
                (before.transferAccounts + after.transferAccounts).map { it.id }.distinct().forEach {
                    graph.ftp.disconnect(it)
                    graph.sftp.disconnect(it)
                }
            }

            // Preferences last, through setPreferences for its side effects.
            setPreferences(after.preferences)
            persist()
            refreshAccess()

            _preview.value = null
            message(summary(result, refusals))
            onDone()
        }
    }

    private fun <T> writeSecrets(incoming: Map<String, T>, known: Set<String>, owner: String, merge: (Secrets) -> Secrets): String? {
        if (!vault.open()) return "the vault is locked"
        val secrets = graph.vault.secrets.value ?: return "the vault could not be read"
        val orphaned = incoming.keys.count { it !in known }
        return runCatching {
            graph.vault.save(merge(secrets), state.value.vaultLocked)
            if (orphaned > 0) "$orphaned had no $owner here to belong to" else null
        }.getOrElse { "they could not be written" }
    }

    private fun summary(result: TransferResult, refusals: List<Pair<String, String>>): String {
        val applied = result.applied.size
        val head = "Imported $applied ${if (applied == 1) "setting" else "settings"}"
        val unreadable = "${result.refused.size} could not be read".takeIf { result.refused.isNotEmpty() }
        val failed = refusals.joinToString("; ") { (what, why) -> "$what: $why" }.ifBlank { null }
        return listOfNotNull(head, unreadable, failed).joinToString("  ·  ")
    }

    private fun versionName(): String = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
