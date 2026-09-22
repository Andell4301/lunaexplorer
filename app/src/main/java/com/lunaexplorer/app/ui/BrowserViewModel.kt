package com.lunaexplorer.app.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.debug.DebugLog
import com.lunaexplorer.app.model.*
import com.lunaexplorer.app.storage.InstalledApp
import com.lunaexplorer.app.storage.LunaDocumentsProvider
import com.lunaexplorer.app.storage.MediaCategory
import com.lunaexplorer.app.storage.ThumbnailLoader
import com.lunaexplorer.app.storage.ViewerAdvertising
import com.lunaexplorer.app.storage.shizuku.HelperState
import com.lunaexplorer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class BrowserViewModel(application: Application, private val graph: AppGraph) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(BrowserState())
    val state = _state.asStateFlow()
    private val saves = Channel<BrowserState>(Channel.CONFLATED)
    private var browseJob: Job? = null
    private var generation = 0L
    private var allEntries = emptyList<Entry>()
    private val resolver = PathResolver(graph.providers) { _state.value.roots }
    val bookmarks = Bookmarks(_state, viewModelScope, resolver, graph.providers, ::persist, ::showMessage) {
        runCatching { application.dataDir.absolutePath }.getOrNull()
    }
    val vault = VaultAccess(_state, graph, ::persist, ::showMessage)
    val smb = SmbAccounts(_state, graph, vault, ::persist) { refreshAccess() }
    val b2 = B2Accounts(_state, graph, vault, viewModelScope, ::persist) { refreshAccess() }
    val servers = TransferAccounts(_state, graph, vault, viewModelScope, ::persist) { refreshAccess() }
    val openWith = OpenWith(graph, viewModelScope)
    val packages = Packages(application, graph, viewModelScope, resolver, state, ::showMessage) { loadDirectory(force = true) }
    val files = BrowserFiles(application, graph, viewModelScope, _state, resolver,
        // Drop the cached listing first, or the reload would reuse it within the freshness window.
        onChanged = { preserve -> forgetCurrentListing(); loadDirectory(preserveSelection = preserve) }, ::showMessage)
    private var activeEditor: TextEditorSession? = null

    internal fun editorSession(entry: Entry, code: Boolean): TextEditorSession =
        sessionFor(entry.ref) { TextEditorSession.forFile(entry, files, viewModelScope, code) }

    internal fun manifestSession(entry: Entry): TextEditorSession {
        val key = ManifestOf(entry.ref)
        return sessionFor(key) {
            TextEditorSession.forText(key, "${entry.name} · manifest", viewModelScope, "This manifest could not be read") {
                packages.manifestOf(entry)
            }
        }
    }

    private fun sessionFor(key: Any, create: () -> TextEditorSession): TextEditorSession {
        activeEditor?.takeIf { it.key == key }?.let { return it }
        releaseEditor()
        return create().also { activeEditor = it }
    }

    private fun releaseEditor() {
        activeEditor?.close()
        activeEditor = null
    }

    val operations = BrowserOperations(graph, viewModelScope, _state, resolver, ::showMessage)
    val transfer = SettingsTransfer(application, graph, viewModelScope, _state, resolver, vault, smb, b2, servers, ::persist,
        ::showMessage, ::setPreferences) { refreshAccess() }
    val archives: ArchiveEngine get() = graph.archives
    val tools = StorageTools(application, graph, viewModelScope, resolver, { _state.value.roots },
        delete = { operations.deleteEntries(it, _state.value.recycleBin) }, message = ::showMessage)

    private var resortToken = 0L

    private val _shareRequests = MutableSharedFlow<Entry>(extraBufferCapacity = 4)
    val shareRequests = _shareRequests.asSharedFlow()

    private var pendingReveal: NodeRef? = null
    private var dragOrigin: NodeRef? = null

    /** A tab's search or category query. Its job is separate from [browseJob], so it runs on under a folder or another tab. */
    private class FlatSession(val tabId: String, val view: View, val presorted: Boolean) {
        var raw: List<Entry> = emptyList()
        var visible: List<Entry> = emptyList()
        var sections: List<SectionRun> = emptyList()
        var summary: String = (view as? View.Search)?.summary ?: (view as? View.Category)?.summary.orEmpty()
        var running: Boolean = true
        var job: Job? = null
        var showing: Boolean = true
        var parkedAt: Int? = null
        var error: String? = null
        var publishes = 0L
    }
    /** By tab id. Only the front tab's session publishes. */
    private val flats = HashMap<String, FlatSession>()
    private val flat: FlatSession? get() = flats[_state.value.activeTabId]

    /** Rows the sessions behind the front tab may hold between them. A test lowers it. */
    internal var backgroundRows = 400_000

    private fun liveIsFlat(session: FlatSession): Boolean = session.showing && _state.value.let { state ->
        state.activeTabId == session.tabId && flats[session.tabId] === session && when (val live = state.view) {
            is View.Search -> (session.view as? View.Search)
                ?.let { it.root == live.root && it.filter == live.filter } == true
            is View.Category -> (session.view as? View.Category)?.category == live.category
            else -> false
        }
    }

    private fun endFlat(tabId: String) {
        flats.remove(tabId)?.let { it.job?.cancel(); it.running = false; it.showing = false }
    }

    private fun leaveFront() {
        // What comes next may be a saved listing, which never clears the refresh indicator.
        _state.update { if (it.refreshing) it.copy(refreshing = false) else it }
        val session = flat ?: return
        // resort() writes only the state, so the rows on screen are the ones to come back to.
        if (liveIsFlat(session)) { session.visible = _state.value.entries; session.sections = _state.value.sections }
        session.showing = false
    }

    private fun parkFlatView() {
        val tab = _state.value.tab ?: return
        val session = flat?.takeIf(::liveIsFlat) ?: return
        leaveFront()
        session.parkedAt = tab.index
    }

    private fun showFlat(session: FlatSession) {
        browseJob?.cancel(); generation++
        session.showing = true
        allEntries = session.raw
        // Rows that arrived while away are not sorted yet; an empty list that is not busy reads as nothing found.
        val unsorted = session.visible.isEmpty() && session.raw.isNotEmpty()
        _state.update {
            // Extension chips are cleared because publishFlat ignores them for a presorted session.
            it.copy(view = withSummary(session.view, session.summary), entries = session.visible,
                sections = session.sections, selected = emptySet(), extensions = emptySet(), loading = false,
                refreshing = false, searching = session.running || unsorted, error = session.error, errorReason = null)
        }
        viewModelScope.launch { publishFlat(session) }
    }

    private fun showFrontTab() {
        val session = flat
        when {
            session == null || session.parkedAt != null -> loadDirectory()
            session.showing -> Unit
            else -> showFlat(session)
        }
    }

    private fun trimBackground() {
        while (true) {
            val behind = flats.values.filter { it.tabId != _state.value.activeTabId }
            if (behind.sumOf { it.raw.size } <= backgroundRows) return
            val largest = behind.filter { !it.running }.maxByOrNull { it.raw.size } ?: return
            endFlat(largest.tabId)
        }
    }

    private fun startFlat(view: View, presorted: Boolean = false, work: suspend CoroutineScope.(FlatSession) -> Unit) {
        browseJob?.cancel()
        val tabId = _state.value.activeTabId
        endFlat(tabId)
        generation++
        allEntries = emptyList()
        val opened = _state.value.let {
            // A re-query of the view already showing keeps the screen it was first opened from.
            val requeried = when (view) {
                is View.Category -> (it.view as? View.Category)?.category == view.category
                is View.Search -> (it.view as? View.Search)?.root == view.root
                else -> false
            }
            val origin = it.screen.takeIf { screen -> screen != Screen.BROWSER }
                ?: it.view?.openedFrom.takeIf { requeried }
            when (view) {
                is View.Category -> view.copy(from = origin)
                is View.Search -> view.copy(from = origin)
                else -> view
            }
        }
        val session = FlatSession(tabId, opened, presorted)
        flats[tabId] = session
        _state.update {
            it.copy(view = opened, searching = true, loading = false, entries = emptyList(),
                sections = emptyList(), selected = emptySet(), error = null, errorReason = null)
                .showing(Screen.BROWSER)
        }
        session.job = viewModelScope.launch {
            try { work(session) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                session.running = false
                session.error = readableError(error)
                if (liveIsFlat(session)) _state.update { it.copy(searching = false, error = session.error) }
            }
        }
    }

    private suspend fun deliver(session: FlatSession, raw: List<Entry>, summary: String, running: Boolean) {
        session.raw = raw
        session.summary = summary
        session.running = running
        // A session off screen only collects; showFlat sorts once when it is shown again.
        if (liveIsFlat(session)) publishFlat(session) else trimBackground()
    }

    private suspend fun publishFlat(session: FlatSession) {
        val preferences = _state.value.preferences
        val raw = session.raw
        val mine = ++session.publishes
        val (visible, runs) = withContext(Dispatchers.Default) {
            val ordered = if (session.presorted) raw
            else sortEntries(raw, preferences, _state.value.filter, _state.value.extensions)
            ordered to sectionsFor(ordered, preferences, session.view)
        }
        // A newer publish has these rows or more; one whose tab was left sorted by the other tab's options.
        if (mine != session.publishes || !liveIsFlat(session)) return
        session.visible = visible
        session.sections = runs
        allEntries = raw
        _state.update {
            it.copy(entries = visible, sections = runs, searching = session.running,
                view = withSummary(it.view, session.summary))
        }
    }

    private fun withSummary(view: View?, summary: String): View? = when (view) {
        is View.Search -> view.copy(summary = summary)
        is View.Category -> view.copy(summary = summary)
        else -> view
    }

    private companion object {
        const val BROWSE = "Browse"
        const val LISTING_FRESH_NANOS = 15_000_000_000L

        const val DELETE_REVEAL_MILLIS = 400L

        val PENDING_STATUSES = setOf("QUEUED", "RUNNING")
        val ATTENTION_STATUSES = setOf("CONFLICT", "PARTIAL", "FAILED", "INTERRUPTED")
        /** Providers whose PERMISSION errors get Luna's wording; for the rest the server's message is kept. */
        val PLATFORM_PROVIDERS = setOf("local", "saf")
    }

    private val listings = object : LinkedHashMap<NodeRef, CachedListing>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NodeRef, CachedListing>?): Boolean {
            if (size > 8) return true
            var rows = 0
            values.forEach { rows += it.entries.size }
            return rows > 400_000 && size > 1
        }
    }

    private data class CachedListing(val directory: Entry, val path: String?, val entries: List<Entry>, val readAt: Long, val closedToApps: Boolean)

    private fun cacheListing(ref: NodeRef, directory: Entry, path: String?, entries: List<Entry>, closedToApps: Boolean) {
        synchronized(listings) { listings[ref] = CachedListing(directory, path, entries, System.nanoTime(), closedToApps) }
    }

    private fun cachedListing(ref: NodeRef): CachedListing? = synchronized(listings) { listings[ref] }

    private fun forgetListings(ref: NodeRef? = null) {
        synchronized(listings) { if (ref == null) listings.clear() else listings.remove(ref) }
    }

    private fun forgetCurrentListing() { _state.value.location?.ref?.let { forgetListings(it) } }

    val thumbnails: ThumbnailLoader get() = graph.thumbnails

    val debugLog: DebugLog get() = graph.debugLog

    fun setDebugLogging(on: Boolean) = graph.debugLog.setRecording(on, DebugLog.about(getApplication()))

    @Suppress("DEPRECATION")
    fun defaultLogFolder(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    fun defaultLogName(): String =
        "luna-log-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.txt"

    suspend fun saveDebugLog(folder: String, name: String): Result<String> {
        val file = name.trim()
        // Platform exceptions often carry only a path as their message; map them to readable ones.
        suspend fun <T> step(block: suspend () -> T): T = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = (error as? StorageException)?.reason ?: when {
                error is java.nio.file.NoSuchFileException -> StorageError.NOT_FOUND
                error is java.nio.file.AccessDeniedException -> StorageError.PERMISSION
                error.message.orEmpty().contains("No space left", ignoreCase = true) -> StorageError.NO_SPACE
                else -> StorageError.IO
            }
            throw StorageException(reason, when (reason) {
                StorageError.NOT_FOUND -> "No such folder"
                StorageError.PERMISSION -> "No write access to that folder"
                StorageError.NO_SPACE -> "Not enough space"
                StorageError.CONFLICT -> "$file is already there"
                else -> "Could not save: ${error.message}"
            }, error)
        }
        val saved = try {
            withContext(Dispatchers.IO) {
                validateName(file)
                val parent = graph.local.referenceTo(folder.trim())
                    ?: throw StorageException(StorageError.NOT_FOUND, "Luna cannot reach that folder")
                val provider = graph.providers.provider(parent)
                if (!step { provider.stat(parent) }.directory) throw StorageException(StorageError.UNSUPPORTED, "That is not a folder")
                if (step { provider.child(parent, file) } != null) throw StorageException(StorageError.CONFLICT, "$file is already there")
                val created = step { provider.create(parent, file, directory = false, mimeType = "text/plain") }
                val text = graph.debugLog.snapshot().text
                try {
                    step { provider.openWrite(created.ref).use { it.write(text.toByteArray()) } }
                } catch (failed: Throwable) {
                    withContext(NonCancellable) { runCatching { provider.delete(created.ref) } }
                    throw failed
                }
                parent to ((provider as? PathAddressable)?.shownPathOf(created.ref) ?: file)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.failure(error)
        }
        forgetListings(saved.first)
        if (_state.value.location?.ref == saved.first) loadDirectory(preserveSelection = true, force = true)
        return Result.success(saved.second)
    }

    val trash: StateFlow<List<TrashedItem>> get() = graph.database.trash

    /** Volume discovery reads disk and crosses a binder, so it runs on [Dispatchers.IO]. */
    private suspend fun loadRoots(preferences: Preferences): List<StorageRoot> = withContext(Dispatchers.IO) {
        graph.appDataVisible = preferences.showAppData
        graph.refreshRoots(preferences.showDeviceRoot)
        graph.providers.roots()
    }

    /** 0 as root, 2000 as the adb shell; null until the helper is up. */
    val helperUid: Int? get() = graph.shizuku.uid

    fun useShizuku() { if (Build.VERSION.SDK_INT >= 30) setPreferences(_state.value.preferences.copy(shizuku = true)) }

    fun allowShizuku() = graph.shizuku.requestPermission()

    fun retryShizuku() = graph.shizuku.retry()

    fun refreshAccess() {
        // Allowing Luna inside Shizuku's own app sends no word, so coming back is when to look.
        graph.shizuku.evaluate()
        viewModelScope.launch {
            // Session restore enumerates roots and opens tabs itself.
            if (!_state.value.ready) return@launch
            val roots = try {
                loadRoots(_state.value.preferences)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return@launch }
            val previous = _state.value.roots.map { it.ref }
            val hadAccess = _state.value.fullAccess
            val fullAccess = graph.hasFullAccess()
            // Newly granted access can widen listings, so drop the cache and force the reload.
            val opened = fullAccess && !hadAccess
            if (opened) forgetListings()
            _state.update { it.copy(roots = roots, fullAccess = fullAccess) }
            when {
                _state.value.tabs.isEmpty() -> roots.firstOrNull { !it.hidden }?.let { navigateRoot(it) }
                _state.value.searchActive -> if (opened) reload()
                else -> loadDirectory(preserveSelection = true, force = opened)
            }
            if (previous != roots.map { it.ref } && !opened) {
                if (_state.value.searchActive) closeSearch(toOrigin = false)
                val front = _state.value.activeTabId
                flats.values.filter { it.parkedAt == null && it.tabId != front }.forEach { endFlat(it.tabId) }
            }
        }
    }

    private var archiveJob: Job? = null
    /** Bumped by every opening and every cancel; a result from an older opening is ignored. */
    @Volatile private var archiveOpenings = 0L

    private class PasswordPrompt(val attempt: (String) -> Unit, val giveUp: () -> Unit)
    private var passwordPrompt: PasswordPrompt? = null
    private var unlockJob: Job? = null

    /** When opening fails for lack of a [password], [Overlay.ArchivePassword] asks for one and retries. */
    fun browseArchive(
        entry: Entry,
        password: String? = null,
        location: Location? = (_state.value.view as? View.Folder)?.listingLocation,
    ) {
        // A nested archive is read through its parent archive, so the parent's password comes first.
        if (password == null && entry.ref.provider == graph.insideArchives.id && graph.insideArchives.needsPassword(entry.ref)) {
            requireArchivePassword(entry) { browseArchive(entry, location = location) }
            return
        }
        cancelArchiveOpening()
        fun origin() = _state.value.let { Triple(it.activeTabId, it.location?.ref, it.screen) }
        val from = origin()
        val opening = ++archiveOpenings
        val started = System.nanoTime()
        val where = "${entry.ref.provider}:${entry.ref.key}"
        DebugLog.i(BROWSE) { "Opening archive $where (${entry.size ?: "unknown"} bytes)" }
        _state.update { it.copy(openingArchive = ArchiveOpening(entry.name)) }
        archiveJob = viewModelScope.launch {
            try {
                // A changed version makes the provider re-read an archive it has opened before.
                val version = "${entry.version}|${entry.size}|${entry.modified}"
                val root = graph.insideArchives.open(entry.ref, entry.name, version, password) { reading ->
                    _state.update { current ->
                        if (opening != archiveOpenings || current.openingArchive == null) current
                        else current.copy(openingArchive = ArchiveOpening(entry.name, reading.bytesRead, reading.total, reading.fromStart))
                    }
                }
                if (opening != archiveOpenings) return@launch
                DebugLog.i(BROWSE) { "Read the archive $where in ${DebugLog.millisSince(started)} ms" }
                archiveJob = null
                _state.update { it.copy(openingArchive = null) }
                if (password != null) settlePasswordPrompt(entry, error = null, dismiss = true)
                // Ignore the result if the tab, folder or screen changed during loading.
                if (origin() != from) return@launch
                navigate(
                    if (location == null) Location(listOf(Crumb(root, entry.name)))
                    else Location(location.crumbs + Crumb(root, entry.name)),
                )
            } catch (cancelled: CancellationException) {
                DebugLog.i(BROWSE) { "Gave up on the archive $where after ${DebugLog.millisSince(started)} ms" }
                // Cancelling a retry leaves the password dialog open for another attempt.
                if (password != null) settlePasswordPrompt(entry, error = null)
                throw cancelled
            } catch (error: Exception) {
                DebugLog.w(BROWSE, error) { "Could not open the archive $where after ${DebugLog.millisSince(started)} ms: ${error.message}" }
                if (opening == archiveOpenings) {
                    archiveJob = null
                    _state.update { it.copy(openingArchive = null) }
                    when {
                        (error as? StorageException)?.reason != StorageError.AUTH -> {
                            settlePasswordPrompt(entry, error = null, dismiss = true)
                            showMessage(error.message ?: "This archive could not be opened")
                        }
                        password == null -> askArchivePassword(entry,
                            attempt = { typed -> browseArchive(entry, typed, location) },
                            giveUp = { cancelArchiveOpening() })
                        else -> settlePasswordPrompt(entry, error.message ?: "Wrong password")
                    }
                }
            }
        }
    }

    fun cancelArchiveOpening() {
        archiveOpenings++
        archiveJob?.cancel()
        archiveJob = null
        _state.update { if (it.openingArchive == null) it else it.copy(openingArchive = null) }
    }

    /** Runs [then] once [entry]'s archive is readable: at once, or after [Overlay.ArchivePassword] unlocks it. */
    fun requireArchivePassword(entry: Entry, then: () -> Unit) {
        if (entry.ref.provider != graph.insideArchives.id || !graph.insideArchives.needsPassword(entry.ref)) {
            then()
            return
        }
        askArchivePassword(entry,
            attempt = { password ->
                unlockJob?.cancel()
                unlockJob = viewModelScope.launch {
                    try {
                        withContext(Dispatchers.IO) { graph.insideArchives.unlock(entry.ref, password) }
                        settlePasswordPrompt(entry, error = null, dismiss = true)
                        then()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        settlePasswordPrompt(entry, error.message ?: "Wrong password")
                    }
                }
            },
            giveUp = { unlockJob?.cancel(); unlockJob = null })
    }

    fun requireArchivePassword(entries: List<Entry>, then: () -> Unit) {
        val locked = entries.firstOrNull {
            it.ref.provider == graph.insideArchives.id && graph.insideArchives.needsPassword(it.ref)
        }
        if (locked == null) then() else requireArchivePassword(locked, then)
    }

    fun unlockArchive(password: String) {
        val prompt = passwordPrompt ?: return
        val showing = _state.value.overlay as? Overlay.ArchivePassword ?: return
        if (showing.verifying || password.isEmpty()) return
        _state.update { it.copy(overlay = showing.copy(error = null, verifying = true)) }
        prompt.attempt(password)
    }

    fun dismissArchivePassword() {
        val prompt = passwordPrompt
        passwordPrompt = null
        if (_state.value.overlay is Overlay.ArchivePassword) showOverlay(null)
        prompt?.giveUp()
    }

    private fun askArchivePassword(entry: Entry, attempt: (String) -> Unit, giveUp: () -> Unit) {
        unlockJob?.cancel()
        unlockJob = null
        passwordPrompt = PasswordPrompt(attempt, giveUp)
        showOverlay(Overlay.ArchivePassword(entry, entry.name))
    }

    /** If the password dialog for [entry] is already gone, [error] goes to the snackbar instead. */
    private fun settlePasswordPrompt(entry: Entry, error: String?, dismiss: Boolean = false) {
        val showing = (_state.value.overlay as? Overlay.ArchivePassword)?.entry?.ref == entry.ref
        when {
            !showing -> error?.let(::showMessage)
            dismiss -> { passwordPrompt = null; showOverlay(null) }
            else -> _state.update { current ->
                val dialog = current.overlay as? Overlay.ArchivePassword
                if (dialog?.entry?.ref == entry.ref) current.copy(overlay = dialog.copy(error = error, verifying = false)) else current
            }
        }
    }

    private fun persist() { saves.trySend(_state.value) }
    private fun rootLocation(root: StorageRoot) = Location(listOf(Crumb(root.ref, root.title)))
    fun showMessage(message: String) { _state.update { it.copy(message = message) } }
    fun dismissMessage() { _state.update { it.copy(message = null) } }
    fun navigateRoot(root: StorageRoot) = navigate(rootLocation(root))

    fun navigate(location: Location) {
        cancelArchiveOpening()
        val state = _state.value
        val current = state.tab ?: BrowserTab(history = listOf(location))
        val entering = state.screen != Screen.BROWSER
        val moved = if (location == current.location) current else current.navigate(location)
        // Entering from another screen sets a new history floor so Back returns to that screen.
        val updated = if (entering) moved.copy(floor = moved.index) else moved
        // Results opened with no tab belong to none, so the first tab ends them.
        if (state.tab == null) endFlat(state.activeTabId)
        flat?.let { session ->
            val parkedAt = session.parkedAt ?: return@let
            // The history trim shifts every index. Covered results that Back can no longer reach end.
            val at = if (moved === current) parkedAt else parkedAt - (current.index + 1 - moved.index)
            if (at < updated.floor || at >= updated.index) endFlat(session.tabId) else session.parkedAt = at
        }
        _state.update { it.copy(tabs = if (it.tabs.isEmpty()) listOf(updated) else it.tabs.map { tab -> if (tab.id == current.id) updated else tab },
            activeTabId = current.id,
            recent = (listOf(location) + it.recent.filter { old -> old.ref != location.ref }).take(30))
            // Push the source screen even if BROWSER is already in the back stack: opening a result is a
            // forward step.
            .showing(Screen.BROWSER, if (entering) it.backStack + it.screen else it.backStack) }
        loadDirectory(); persist()
    }

    fun openFolder(entry: Entry, location: Location? = (_state.value.view as? View.Folder)?.listingLocation) {
        if (!entry.directory) return
        if (_state.value.location == null) return
        parkFlatView()
        // Retained rows still belong to their displayed location while another folder loads.
        navigate(Location(location?.crumbs.orEmpty() + Crumb(entry.ref, entry.name)))
    }

    private fun openStartFolder(path: String) {
        viewModelScope.launch {
            val located = resolver.locate(path) ?: return@launch
            navigate(located.location)
            _state.update { state ->
                val tab = state.tab ?: return@update state
                state.copy(tabs = state.tabs.map { if (it.id == tab.id) it.copy(floor = it.index) else it })
            }
        }
    }

    private fun showUnreachable(what: String) = showMessage("No storage Luna can reach contains $what")

    fun goTo(path: String) {
        viewModelScope.launch {
            val located = resolver.locate(path)
            if (located == null) showUnreachable(path) else navigate(located.location)
        }
    }

    fun exploreAppFiles(app: InstalledApp) {
        val path = app.sourceDir?.let { File(it).parent } ?: run {
            showMessage("This app has no installation folder"); return
        }
        viewModelScope.launch {
            var located = resolver.locate(path)
            if (located == null && !_state.value.preferences.showDeviceRoot) {
                val preferences = _state.value.preferences.copy(showDeviceRoot = true)
                _state.update { it.copy(preferences = preferences) }
                persist()
                val roots = loadRoots(preferences)
                _state.update { it.copy(roots = roots) }
                located = resolver.locate(path)
            }
            if (located == null) showUnreachable(path) else navigate(located.location)
        }
    }

    private suspend fun revealPath(path: String): Boolean {
        val parent = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val located = resolver.locate(parent)?.location ?: return false
        pendingReveal = resolver.refFor(path) ?: return false
        navigate(located)
        return true
    }

    fun openBookmark(bookmark: Bookmark) {
        val place = when (val destination = bookmark.destination) {
            is Destination.Tool -> { showScreen(destination.screen); return }
            is Destination.Place -> destination
        }
        viewModelScope.launch {
            if (place.file) {
                val path = place.path
                if (path != null && revealPath(path)) return@launch
                // Without a path, the saved breadcrumbs minus the last one give the parent folder.
                val location = place.location
                if (path == null && location != null && location.crumbs.size > 1) {
                    pendingReveal = location.ref
                    navigate(Location(location.crumbs.dropLast(1)))
                    return@launch
                }
            }
            // A saved location can go stale when roots change; fall back to resolving the path again.
            val direct = place.location?.takeIf { place.path == null || resolver.resolves(it) }
            val located = direct ?: place.path?.let { path -> resolver.locate(path)?.location }
            if (located == null) {
                showMessage("Luna cannot reach ${place.path ?: bookmark.title}")
                return@launch
            }
            navigate(located)
        }
    }

    fun breadcrumb(index: Int) { _state.value.location?.let { if (index in it.crumbs.indices) navigate(Location(it.crumbs.take(index + 1))) } }

    private val toolSelections = linkedMapOf<Any, () -> Unit>()

    /** Tool cards register a clear action so Back clears their selection first; null unregisters. */
    internal fun registerToolSelection(owner: Any, clear: (() -> Unit)?) {
        if (clear == null) toolSelections.remove(owner) else toolSelections[owner] = clear
        _state.update { it.copy(toolSelectionActive = toolSelections.isNotEmpty()) }
    }

    fun back() {
        val state = _state.value
        if (state.openingArchive != null) { cancelArchiveOpening(); return }
        if (toolSelections.isNotEmpty()) {
            val clear = toolSelections.values.toList()
            toolSelections.clear()
            _state.update { it.copy(toolSelectionActive = false) }
            clear.forEach { it() }
            return
        }
        if (state.selected.isNotEmpty()) { clearSelection(); return }
        if (state.screen == Screen.BROWSER && state.searchActive) { closeSearch(); return }
        if (state.inBrowserHistory) { changeHistory(-1); return }
        val previous = state.backStack.lastOrNull() ?: return
        releaseEditor()
        _state.update {
            it.showing(previous, it.backStack.dropLast(1)).copy(selected = emptySet(), overlay = null)
        }
    }

    fun canGoBack(): Boolean = _state.value.let {
        it.toolSelectionActive || it.selected.isNotEmpty() || it.openingArchive != null ||
            (it.screen == Screen.BROWSER && it.searchActive) || it.inBrowserHistory || it.backStack.isNotEmpty()
    }

    fun forward() = changeHistory(1)

    private fun changeHistory(delta: Int) {
        val tab = _state.value.tab ?: return
        val index = tab.index + delta
        if (index !in tab.history.indices) return
        cancelArchiveOpening()
        _state.update { it.copy(tabs = it.tabs.map { t -> if (t.id == tab.id) t.copy(index = index) else t }) }
        // Back reached the history entry whose results were parked; restore them instead of loading.
        val resume = flat?.takeIf { it.parkedAt == index }
        if (resume != null) {
            resume.parkedAt = null
            showFlat(resume)
            persist()
            return
        }
        loadDirectory(); persist()
    }

    fun newTab() {
        if (_state.value.tabs.size >= 12) { showMessage("Up to 12 tabs are supported"); return }
        val location = _state.value.location ?: return
        cancelArchiveOpening()
        leaveFront()
        val tab = BrowserTab(history = listOf(location))
        _state.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id,
            screen = tab.screen, backStack = tab.backStack) }
        trimBackground()
        loadDirectory(); persist()
    }

    fun selectTab(id: String) {
        val incoming = _state.value.tabs.firstOrNull { it.id == id } ?: return
        if (id != _state.value.activeTabId) { cancelArchiveOpening(); leaveFront() }
        _state.update { it.copy(activeTabId = id, screen = incoming.screen, backStack = incoming.backStack) }
        trimBackground()
        showFrontTab(); persist()
    }

    fun closeTab(id: String) {
        val tabs = _state.value.tabs
        if (tabs.size <= 1) { showMessage("Keep at least one tab open"); return }
        val index = tabs.indexOfFirst { it.id == id }
        val remaining = tabs.filter { it.id != id }
        if (id == _state.value.activeTabId) { cancelArchiveOpening(); leaveFront() }
        if (index >= 0) endFlat(id)
        _state.update {
            if (it.activeTabId != id) it.copy(tabs = remaining) else {
                val next = remaining[(index - 1).coerceAtLeast(0)]
                it.copy(tabs = remaining, activeTabId = next.id,
                    screen = next.screen, backStack = next.backStack)
            }
        }
        showFrontTab(); persist()
    }

    /** The user asking for the folder as it is now: a provider that keeps saved listings forgets this one first. */
    fun refresh() { reload(fresh = true) }

    /** Opens what is on screen again as if for the first time, so a saved listing still serves. For after an unlock. */
    fun reopen() = reload()

    private fun reload(fresh: Boolean = false) {
        when (val view = _state.value.view) {
            is View.Search -> if (fresh) searchAfresh(view) else runSearch(view.root, view.filter.withHiddenSetting())
            is View.Category -> showCategory(view.category)
            else -> loadDirectory(preserveSelection = true, force = true, fresh = fresh)
        }
    }

    /** A search walks saved listings too, so everything saved under its root is forgotten first. */
    private fun searchAfresh(view: View.Search) {
        val session = flat ?: return
        viewModelScope.launch {
            try { (graph.providers.provider(view.root) as? CachedListings)?.forget(view.root, below = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { DebugLog.w(BROWSE, error) { "Could not forget saved listings: ${error.message}" } }
            if (liveIsFlat(session)) runSearch(view.root, view.filter.withHiddenSetting())
        }
    }

    /** Results that sat behind another tab missed a change of the setting, so a rerun reads it again. */
    private fun SearchFilter.withHiddenSetting() = copy(includeHidden = _state.value.preferences.showHidden)

    private fun folderView(state: BrowserState, ref: NodeRef): View.Folder =
        (state.view as? View.Folder)?.takeIf { it.ref == ref }
            ?: View.Folder(ref, listingRef = state.listingRef,
                listingLocation = (state.view as? View.Folder)?.listingLocation)

    fun pullToRefresh() {
        _state.update { it.copy(refreshing = true) }
        forgetCurrentListing()
        refresh()
    }

    private fun sameListing(before: List<Entry>, after: List<Entry>): Boolean {
        if (before.size != after.size) return false
        for (index in before.indices) {
            val a = before[index]
            val b = after[index]
            if (a.ref != b.ref || a.name != b.name || a.size != b.size || a.modified != b.modified) return false
        }
        return true
    }

    private fun loadDirectory(preserveSelection: Boolean = false, force: Boolean = false, fresh: Boolean = false) {
        // A query whose results are parked for Back keeps running; any other is unreachable now.
        flat?.let { session -> if (session.parkedAt == null) endFlat(session.tabId) else session.showing = false }
        browseJob?.cancel()
        val token = ++generation
        val location = _state.value.location ?: return
        val ref = location.ref
        allEntries = emptyList()

        // Show cached rows immediately and re-read unless the cache is still fresh.
        val cached = if (force) null else cachedListing(ref)
        if (cached != null) {
            allEntries = cached.entries
            viewModelScope.launch {
                val preferences = _state.value.preferences
                val (sorted, runs) = withContext(Dispatchers.Default) {
                    val ordered = sortEntries(cached.entries, preferences, _state.value.filter, _state.value.extensions)
                    ordered to sectionsFor(ordered, preferences, _state.value.view)
                }
                val reveal = pendingReveal?.takeIf { want -> sorted.any { it.ref == want } }
                if (reveal != null) pendingReveal = null
                if (token == generation) {
                    _state.update {
                        it.copy(view = View.Folder(ref, cached.directory, cached.path,
                                folderKey = cached.path ?: (it.view as? View.Folder)?.folderKey, listingRef = ref,
                                listingLocation = location, closedToApps = cached.closedToApps),
                            entries = sorted, sections = runs, loading = false, error = null, errorReason = null,
                            selected = reveal?.let { want -> setOf(want) }
                                ?: if (preserveSelection) it.selected else emptySet(),
                            searching = false, extensions = emptySet())
                    }
                }
            }
            if (System.nanoTime() - cached.readAt < LISTING_FRESH_NANOS) {
                DebugLog.d(BROWSE) { "Showed ${ref.provider}:${ref.key} from a listing read moments ago" }
                return
            }
        }
        // The previous rows stay until new ones arrive, which avoids an empty frame.
        if (cached == null) {
            _state.update { it.copy(loading = true, error = null, errorReason = null,
                selected = if (preserveSelection) it.selected else emptySet(),
                view = View.Folder(ref, listingRef = it.listingRef,
                    listingLocation = (it.view as? View.Folder)?.listingLocation),
                searching = false, extensions = emptySet()) }
        } else {
            _state.update { it.copy(loading = true) }
        }
        browseJob = viewModelScope.launch {
            val started = System.nanoTime()
            val where = "${ref.provider}:${ref.key}"
            DebugLog.d(BROWSE) { "Opening $where" + if (cached != null) " (the remembered listing is shown meanwhile)" else "" }
            try {
                val provider = graph.providers.provider(ref)
                if (fresh) (provider as? CachedListings)?.forget(ref)
                val folder = withContext(Dispatchers.IO) { provider.stat(ref) }
                if (!folder.directory) throw StorageException(StorageError.NOT_FOUND, "This location is no longer a folder")
                DebugLog.d(BROWSE) { "Found $where in ${DebugLog.millisSince(started)} ms; listing it" }
                val path = withContext(Dispatchers.IO) { (provider as? PathAddressable)?.shownPathOf(ref) }
                val closed = withContext(Dispatchers.IO) { graph.closedToApps(ref) }
                // Folder overrides are keyed by path, or by ref where the provider declares STABLE_KEYS.
                val key = path ?: ref.takeIf { Feature.STABLE_KEYS in provider.features }?.let { "${it.provider}:${it.key}" }
                if (token == generation) _state.update { it.copy(view = folderView(it, ref).copy(directory = folder, path = path, folderKey = key, closedToApps = closed)) }
                var published = false
                val accumulated = ArrayList<Entry>(1024)
                var lastPublished = 0L
                var lastPublishedSize = 0
                provider.list(ref).collect { batch ->
                    ensureActive()
                    accumulated.addAll(batch)
                    // Every publish re-sorts the whole list, so wait for both 150 ms and 50% growth. Cached
                    // rows are replaced only by the complete listing.
                    if (cached == null && System.nanoTime() - lastPublished > 150_000_000L &&
                        accumulated.size >= lastPublishedSize + (lastPublishedSize / 2).coerceAtLeast(256)) {
                        publishEntries(accumulated, location, token)
                        published = true
                        lastPublished = System.nanoTime()
                        lastPublishedSize = accumulated.size
                    }
                }
                val unchanged = cached != null && sameListing(cached.entries, accumulated)
                if (!unchanged || !published) publishEntries(accumulated, location, token)
                cacheListing(ref, folder, path, accumulated.toList(), closed)
                DebugLog.i(BROWSE) { "Listed $where: ${accumulated.size} items in ${DebugLog.millisSince(started)} ms" }
                if (token == generation) _state.update { it.copy(loading = false, refreshing = false) }
            } catch (cancelled: CancellationException) {
                DebugLog.d(BROWSE) { "Stopped opening $where after ${DebugLog.millisSince(started)} ms" }
                throw cancelled
            } catch (error: Exception) {
                DebugLog.w(BROWSE, error) {
                    "Could not open $where after ${DebugLog.millisSince(started)} ms: " +
                        "${(error as? StorageException)?.reason ?: error.javaClass.simpleName} · ${error.message}"
                }
                forgetListings(ref)
                // A folder that would not open still has a place, which the empty states go by.
                val (shown, closed) = withContext(Dispatchers.IO) {
                    runCatching { (graph.providers.provider(ref) as? PathAddressable)?.shownPathOf(ref) }.getOrNull() to
                        runCatching { graph.closedToApps(ref) }.getOrDefault(false)
                }
                if (token == generation) _state.update {
                    it.copy(loading = false, refreshing = false, entries = emptyList(), sections = emptyList(),
                        view = folderView(it, ref).let { view -> view.copy(listingRef = ref, listingLocation = location,
                            path = view.path ?: shown, closedToApps = closed) },
                        error = readableError(error, network = ref.provider !in PLATFORM_PROVIDERS),
                        errorReason = (error as? StorageException)?.reason)
                }
            }
        }
    }

    private suspend fun publishEntries(entries: List<Entry>, location: Location, token: Long) {
        if (token != generation) return
        allEntries = entries.toList()
        val preferences = _state.value.preferences
        val (visible, runs) = withContext(Dispatchers.Default) {
            val ordered = sortEntries(allEntries, preferences, _state.value.filter, _state.value.extensions)
            ordered to sectionsFor(ordered, preferences, _state.value.view)
        }
        val reveal = pendingReveal?.takeIf { want -> visible.any { it.ref == want } }
        if (reveal != null) pendingReveal = null
        if (token == generation && _state.value.preferences == preferences) _state.update {
            it.copy(entries = visible, sections = runs,
                view = (it.view as? View.Folder)?.copy(listingRef = location.ref, listingLocation = location) ?: it.view,
                selected = reveal?.let { ref -> setOf(ref) } ?: it.selected.keptIn(visible))
        }
    }

    private fun Set<NodeRef>.keptIn(entries: List<Entry>): Set<NodeRef> =
        if (isEmpty()) this else intersect(entries.mapTo(HashSet(entries.size * 2)) { it.ref })

    private fun sortEntries(entries: List<Entry>, preferences: Preferences, filterText: String, extensions: Set<String>): List<Entry> {
        val p = preferences.inFolder(_state.value.folderViews[_state.value.folderKey])
        val comparator = when (p.sort) {
            SortOrder.NAME -> compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortOrder.SIZE -> compareBy<Entry> { it.size ?: -1L }
            SortOrder.MODIFIED -> compareBy<Entry> { it.modified ?: -1L }
            SortOrder.TYPE -> compareBy<Entry> { it.mimeType }
            SortOrder.EXTENSION -> compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) {
                it.name.substringAfterLast('.', "")
            }
            SortOrder.NATURAL -> NaturalOrder
        }.thenBy { it.name }.let { if (p.descending) it.reversed() else it }
        val filter = filterText.trim()
        return entries.asSequence()
            .filter { p.showHidden || !it.hidden }
            .filter { filter.isEmpty() || it.name.contains(filter, ignoreCase = true) }
            .filter { extensions.isEmpty() || it.name.substringAfterLast('.', "").lowercase() in extensions }
            .sortedWith(compareByDescending<Entry> { it.directory }.then(comparator)).toList()
    }

    fun toggleExtension(extension: String) {
        val current = _state.value.extensions
        setExtensions(if (extension in current) current - extension else current + extension)
    }

    fun clearExtensions() { if (_state.value.extensions.isNotEmpty()) setExtensions(emptySet()) }

    fun setExtensions(extensions: Set<String>) {
        if (_state.value.extensions == extensions) return
        _state.update { it.copy(extensions = extensions) }
        resort()
    }

    fun setFilter(text: String) {
        if (_state.value.filter == text) return
        _state.update { it.copy(filter = text) }
        resort()
    }

    /** A newer resort or any navigation makes a running one discard its result. */
    private fun resort() {
        val token = generation
        val mine = ++resortToken
        viewModelScope.launch {
            val state = _state.value
            val (sorted, runs) = withContext(Dispatchers.Default) {
                val ordered = sortEntries(allEntries, state.preferences, state.filter, state.extensions)
                ordered to sectionsFor(ordered, state.preferences, state.view)
            }
            if (token == generation && mine == resortToken) _state.update {
                it.copy(entries = sorted, sections = runs, selected = it.selected.keptIn(sorted))
            }
        }
    }

    /** Category results keep the media index's DATE_MODIFIED DESC order, so they are always sectioned by date. */
    private fun sectionsFor(entries: List<Entry>, preferences: Preferences, view: View?): List<SectionRun> {
        val own = preferences.inFolder(_state.value.folderViews[_state.value.folderKey])
        if (!own.sections || entries.isEmpty()) return emptyList()
        return sectionsOf(entries, if (view is View.Category) SortOrder.MODIFIED else own.sort)
    }

    private fun Preferences.sortKey() = listOf(sort, descending, showHidden, sections)

    fun setViewOptions(updated: Preferences, thisFolderOnly: Boolean) {
        val key = _state.value.folderKey
        val base = _state.value.preferences
        if (thisFolderOnly && key != null) {
            val own = FolderView(updated.view, updated.sort, updated.descending)
            _state.update { state ->
                // Reinsert the override last so eviction removes the least recently set entry.
                val kept = (state.folderViews - key) + (key to own)
                state.copy(folderViews = if (kept.size <= FOLDER_VIEW_LIMIT) kept
                    else kept.entries.drop(kept.size - FOLDER_VIEW_LIMIT).associate { it.key to it.value })
            }
            // These options stay global even when layout and ordering are saved for one folder.
            setPreferences(base.copy(showHidden = updated.showHidden, thumbnails = updated.thumbnails,
                gridCell = updated.gridCell, sections = updated.sections))
        } else {
            if (key != null) _state.update { it.copy(folderViews = it.folderViews - key) }
            setPreferences(updated)
        }
        resort()
    }

    fun setFolderView(key: String, own: FolderView) = changeFolderViews(key) { it + (key to own) }

    fun forgetFolderView(key: String) = changeFolderViews(key) { it - key }

    fun forgetFolderViews() = changeFolderViews(null) { emptyMap() }

    private fun changeFolderViews(key: String?, change: (Map<String, FolderView>) -> Map<String, FolderView>) {
        _state.update { it.copy(folderViews = change(it.folderViews)) }
        persist()
        if (key == null || key == _state.value.folderKey) resort()
    }

    fun folderHasOwnView(): Boolean = _state.value.folderKey?.let { it in _state.value.folderViews } ?: false

    private var documentsProviderApplied: Boolean? = null

    // Serialize component read-compare-set calls so an older write cannot overwrite the latest preference.
    private val componentWrites = Channel<() -> Unit>(Channel.UNLIMITED).also { writes ->
        viewModelScope.launch(Dispatchers.IO) { for (write in writes) runCatching { write() } }
    }

    private fun applyToGraph(preferences: Preferences) {
        graph.media.ignoreDotFiles = preferences.ignoreDotFiles
        graph.media.includeNomedia = preferences.includeNomedia
        graph.media.categoryExtras = preferences.categoryExtras
        graph.shizuku.setEnabled(preferences.shizuku && Build.VERSION.SDK_INT >= 30)
        val enabled = preferences.documentsProvider
        if (documentsProviderApplied != enabled) {
            documentsProviderApplied = enabled
            componentWrites.trySend { LunaDocumentsProvider.setEnabled(getApplication(), enabled) }
        }
    }

    private fun advertiseViewers(kinds: Set<String>) {
        componentWrites.trySend {
            try { ViewerAdvertising.apply(getApplication(), kinds) }
            catch (error: Exception) { DebugLog.w(BROWSE, error) { "Could not update Open with Luna: ${error.message}" } }
        }
    }

    /** A file: uri inside Luna's roots opens as that file; anything else is read through the incoming provider. */
    fun openExternal(uri: Uri, declaredType: String?) {
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) {
                    val ref = uri.path?.takeIf { uri.scheme == "file" }?.let { graph.local.referenceTo(it) }
                        ?: graph.incoming.referenceTo(uri, declaredType)
                    graph.providers.provider(ref).stat(ref)
                }
                val declared = declaredType?.takeIf { it.contains('/') && !it.endsWith("/*") }
                val typed = if (declared != null && entry.mimeType == "application/octet-stream") entry.copy(mimeType = declared) else entry
                when {
                    packages.isPackage(typed) -> showOverlay(Overlay.Package(typed))
                    opensAsArchive(typed, graph.archives) -> browseArchive(typed)
                    else -> when (val kind = viewerFor(typed)) {
                        ViewerKind.NONE -> showOverlay(Overlay.Opening(typed))
                        else -> showOverlay(Overlay.Viewer(typed, kind))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(error.message ?: "Luna could not open this file")
            }
        }
    }

    fun setPreferences(preferences: Preferences) {
        val previous = _state.value.preferences
        _state.update { it.copy(preferences = preferences) }; persist()
        // Turning it on is the moment to ask Shizuku; afterwards the Storage access page has the button.
        if (!previous.shizuku && preferences.shizuku && Build.VERSION.SDK_INT >= 30) graph.shizuku.setEnabled(true, ask = true)
        // Update the index before it builds the next query.
        applyToGraph(preferences)
        if (previous.openWithLuna != preferences.openWithLuna) advertiseViewers(preferences.openWithLuna)
        if (previous.showDeviceRoot != preferences.showDeviceRoot ||
            previous.showAppData != preferences.showAppData) { refreshAccess(); return }
        // Only a category in view: startFlat brings the browser to the front, which a category parked
        // behind another screen must not do from inside Settings.
        if (_state.value.view is View.Category && _state.value.screen == Screen.BROWSER &&
            (previous.ignoreDotFiles != preferences.ignoreDotFiles || previous.includeNomedia != preferences.includeNomedia)) {
            reload(); return
        }
        if (previous.showHidden != preferences.showHidden && _state.value.searchActive) {
            _state.update { s -> s.copy(view = (s.view as? View.Search)?.let { it.copy(filter = it.filter.copy(includeHidden = preferences.showHidden)) } ?: s.view) }
            reload()
        } else if (previous.sortKey() != preferences.sortKey()) {
            resort()
        }
    }

    fun toggleSelection(ref: NodeRef) { _state.update { it.copy(selected = if (ref in it.selected) it.selected - ref else it.selected + ref) } }
    fun selectAll() = select { true }
    fun selectAllFiles() = select { !it.directory }
    fun selectAllFolders() = select { it.directory }
    private fun select(match: (Entry) -> Boolean) {
        _state.update { it.copy(selected = it.entries.filter(match).map { e -> e.ref }.toSet()) }
    }

    fun invertSelection() { _state.update { it.copy(selected = it.entries.map { e -> e.ref }.toSet() - it.selected) } }
    fun clearSelection() { _state.update { it.copy(selected = emptySet()) } }

    private var deleteJob: Job? = null

    /**
     * Asks first when confirmation is on, the selection holds a volume's Android folder, or its size reaches
     * the large-deletion warning. Otherwise deletes once folders are measured, showing the dialog only if
     * measuring takes a while.
     */
    fun requestDelete() {
        val state = _state.value
        val entries = state.selectedEntries
        if (entries.isEmpty()) return
        deleteJob?.cancel()
        val folders = entries.filter { it.directory }
        val check = DeleteCheck(entries, measuring = folders.isNotEmpty(),
            // Null turns the size warning off; the Android-folder guard still applies.
            threshold = state.preferences.takeIf { it.warnLargeDelete }
                ?.let { DeleteGuard.thresholdBytes(it.largeDeleteGb) },
            versioned = operations.keepsVersions(entries))
        _state.update { it.copy(deleteCheck = check) }
        if (state.confirmDelete || check.large || (check.versioned && state.versionedDelete == VersionedDelete.ASK)) askDelete()
        deleteJob = viewModelScope.launch {
            // The guard canonicalises paths, which touches the filesystem.
            val androidPaths = try {
                withContext(Dispatchers.IO) {
                    DeleteGuard.androidFolders(entries, resolver::shownPathOf, DeleteGuard.volumePaths(state.roots, resolver::shownPathOf))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.w(BROWSE, error) { "Could not check the selection for Android folders: ${error.message}" }
                emptyList()
            }
            // Set guardKnown even when the lookup failed: the dialog refuses to confirm until it is set.
            updateDeleteCheck { it.copy(androidPaths = androidPaths, guardKnown = true) }
            if (androidPaths.isNotEmpty()) askDelete()
            if (folders.isEmpty()) { finishDeleteCheck(); return@launch }
            val reveal = launch { delay(DELETE_REVEAL_MILLIS); askDelete() }
            try {
                files.measure(folders.map { it.ref }).collect { totals ->
                    updateDeleteCheck { it.copy(totals = totals) }
                    if (_state.value.deleteCheck?.large == true) askDelete()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.w(BROWSE, error) { "Could not measure the selection before deleting: ${error.message}" }
            } finally {
                reveal.cancel()
            }
            finishDeleteCheck()
        }
    }

    private fun updateDeleteCheck(change: (DeleteCheck) -> DeleteCheck) {
        _state.update { state -> state.deleteCheck?.let { state.copy(deleteCheck = change(it)) } ?: state }
    }

    private fun askDelete() { if (_state.value.overlay != Overlay.Delete) showOverlay(Overlay.Delete) }

    private fun finishDeleteCheck() {
        updateDeleteCheck { it.copy(measuring = false) }
        val check = _state.value.deleteCheck ?: return
        if (_state.value.overlay == Overlay.Delete) return
        if (check.guarded) askDelete()
        else deleteChecked(_state.value.recycleBin, keepVersions = _state.value.versionedDelete == VersionedDelete.HIDE)
    }

    /** [keepVersions] only matters where old versions are kept, and there [toBin] does not. */
    fun deleteChecked(toBin: Boolean, keepVersions: Boolean = false) {
        val entries = _state.value.deleteCheck?.entries ?: _state.value.selectedEntries
        cancelDeleteCheck()
        operations.deleteEntries(entries, toBin, keepVersions)
    }

    /** Read at the tap, not at composition: a guard can arrive between the last frame and the press. */
    fun guardsDeletion(): Boolean = _state.value.deleteCheck?.guarded == true

    fun cancelDeleteCheck() {
        deleteJob?.cancel()
        deleteJob = null
        _state.update { if (it.deleteCheck == null) it else it.copy(deleteCheck = null) }
    }

    fun shareFromViewer(entry: Entry) {
        _state.update { it.copy(selected = setOf(entry.ref)) }
        _shareRequests.tryEmit(entry)
    }

    fun siblingsOf(entry: Entry, predicate: (Entry) -> Boolean): List<Entry> {
        val shown = _state.value.entries.filter { !it.directory && predicate(it) }
        return if (shown.any { it.ref == entry.ref }) shown else listOf(entry)
    }

    private var playbackResume: PlaybackResume? = null

    // The ViewModel retains playback position across Activity recreation while saved instance state is unavailable.
    internal fun rememberPlayback(resume: PlaybackResume) { playbackResume = resume }

    internal fun takePlayback(ref: NodeRef): PlaybackResume? {
        val saved = playbackResume ?: return null
        playbackResume = null
        return saved.takeIf { it.ref == ref }
    }

    fun rememberForSession(
        confirmDelete: Boolean? = null,
        recycleBin: Boolean? = null,
        dropAction: DropAction? = null,
        versionedDelete: VersionedDelete? = null,
    ) {
        _state.update {
            it.copy(session = it.session.copy(
                confirmDelete = confirmDelete ?: it.session.confirmDelete,
                recycleBin = recycleBin ?: it.session.recycleBin,
                dropAction = dropAction ?: it.session.dropAction,
                versionedDelete = versionedDelete ?: it.session.versionedDelete,
            ))
        }
    }

    fun beginDrag(entry: Entry): List<Entry> {
        dragOrigin = _state.value.listingRef
        if (entry.ref !in _state.value.selected) {
            _state.update { it.copy(selected = it.selected + entry.ref) }
        }
        return _state.value.selectedEntries
    }

    fun dropOnto(entries: List<Entry>, destination: NodeRef, title: String) {
        val sources = entries.filter { it.ref != destination }
        if (sources.isEmpty()) { showMessage("A folder cannot be dropped into itself"); return }
        // A drop back into the source folder would only collide with itself.
        if (destination == dragOrigin) { showMessage("Already in $title"); return }
        when (_state.value.dropAction) {
            DropAction.ASK -> showOverlay(Overlay.Drop(sources, destination, title))
            DropAction.COPY -> dropInto(sources, destination, move = false)
            DropAction.MOVE -> dropInto(sources, destination, move = true)
        }
    }

    fun dropInto(entries: List<Entry>, destination: NodeRef, move: Boolean) {
        if (entries.isEmpty()) return
        operations.submit(OperationRequest(
            type = if (move) OperationType.MOVE else OperationType.COPY,
            sources = entries.map { it.ref }, destination = destination,
            conflictPolicy = ConflictPolicy.ASK,
        ), "${if (move) "Move" else "Copy"} ${entries.size} item(s)")
    }

    fun inspectOperation(id: String) {
        viewModelScope.launch {
            val destination = graph.database.request(id)?.destination
            if (destination == null) { showMessage("Browse the original folder to inspect what remains"); return@launch }
            try {
                val entry = withContext(Dispatchers.IO) { graph.providers.provider(destination).stat(destination) }
                setPreferences(_state.value.preferences.copy(showHidden = true))
                val previous = (_state.value.tabs.flatMap { it.history } + _state.value.recent).find { it.ref == destination }
                navigate(previous ?: Location(listOf(Crumb(destination, entry.name))))
                showMessage("Hidden files are visible; review any .luna- items before deleting them")
            } catch (error: Exception) { showMessage(readableError(error)) }
        }
    }

    fun search(
        query: String,
        type: String,
        minBytes: Long?,
        maxBytes: Long?,
        modifiedAfter: Long?,
        regex: Boolean = false,
        caseSensitive: Boolean = false,
        text: String = "",
    ) {
        if (minBytes != null && minBytes < 0 || maxBytes != null && maxBytes < 0 || minBytes != null && maxBytes != null && minBytes > maxBytes) {
            showMessage("Use a nonnegative size range with minimum at or below maximum"); return
        }
        val root = searchRoot() ?: run { showMessage("There is no storage to search yet"); return }
        val filter = runCatching {
            SearchFilter(
                query = query,
                type = when (type) { "FILE" -> SearchType.FILES; "FOLDER" -> SearchType.FOLDERS; else -> SearchType.ANY },
                minSize = minBytes, maxSize = maxBytes, modifiedAfter = modifiedAfter,
                includeHidden = _state.value.preferences.showHidden,
                regex = regex, caseSensitive = caseSensitive, text = text,
            )
        }.getOrElse { showMessage(it.message ?: "That search could not be built"); return }
        runSearch(root, filter)
    }

    /** Whether a search from here can look inside files: not across a network, where each would have to come down it. */
    fun searchesText(): Boolean = searchRoot()?.let { root ->
        runCatching { Feature.NETWORK !in graph.providers.provider(root).features }.getOrDefault(false)
    } ?: false

    private fun searchRoot(): NodeRef? = _state.value.let { state ->
        if (state.screen == Screen.BROWSER) state.location?.ref else state.defaultSearchRoot()?.ref
    }

    fun searchScope(): String = _state.value.let { state ->
        if (state.screen == Screen.BROWSER) state.location?.title ?: "this folder"
        else state.defaultSearchRoot()?.title ?: "storage"
    }

    private fun BrowserState.defaultSearchRoot(): StorageRoot? =
        roots.firstOrNull { it.kind == RootKind.INTERNAL } ?: roots.firstOrNull { !it.hidden }

    private fun runSearch(root: NodeRef, filter: SearchFilter) {
        startFlat(View.Search(root, filter)) { session ->
            val results = mutableListOf<Entry>()
            var issues = 0
            graph.search.search(root, filter).collect { event ->
                ensureActive()
                when (event) {
                    is SearchEvent.Batch -> {
                        results.addAll(event.entries)
                        deliver(session, results.toList(), "${results.size} found", running = true)
                    }
                    is SearchEvent.Issue -> issues++
                    is SearchEvent.Complete -> {
                        // Only the session on screen may clear the refresh indicator.
                        if (liveIsFlat(session)) _state.update { it.copy(refreshing = false) }
                        deliver(session, results.toList(),
                            "${event.matches} found" +
                                (if (event.truncated) " · limit reached" else "") +
                                (if (issues > 0) " · $issues skipped" else ""),
                            running = false)
                    }
                }
            }
        }
    }

    fun showCategory(category: MediaCategory) {
        val view = View.Category(category, "Reading ${category.label.lowercase()}", graph.media.coverageNote())
        startFlat(view, presorted = true) { session ->
            val found = ArrayList<Entry>(4096)
            var lastPublished = 0
            graph.media.query(category).collect { batch ->
                ensureActive()
                found.addAll(batch.entries)
                val summary = buildString {
                    append("${category.label}: ${"%,d".format(found.size)}")
                    if (batch.skipped > 0) append(" · ${batch.skipped} unreachable")
                    if (!batch.complete) append(" · reading")
                }
                // Publish on completion or after 25% growth, to limit recompositions.
                if (batch.complete || found.size >= lastPublished * 5 / 4 + 256) {
                    deliver(session, found.toList(), summary, running = !batch.complete)
                    lastPublished = found.size
                } else {
                    session.summary = summary
                }
            }
        }
    }

    /** Reruns the query: a re-sort cannot drop files the old query admitted. */
    fun removeCategoryExtra(category: MediaCategory, extension: String) {
        val preferences = _state.value.preferences
        val extras = preferences.categoryExtras[category.name].orEmpty()
        if (extension !in extras) return
        // setPreferences updates the index's extras, so it has to come before the query.
        setPreferences(preferences.copy(
            categoryExtras = preferences.categoryExtras + (category.name to (extras - extension))))
        setExtensions(_state.value.extensions - extension)
        showCategory(category)
        showMessage("Removed $extension from ${category.label}")
    }

    /** Every name in the listing, filtered-out rows included, for batch-rename collision checks. */
    fun namesInFolder(): Set<String> = allEntries.mapTo(HashSet()) { it.name }

    /**
     * [includeExtension] must match the preview the user saw. Names that collide with another source go
     * through a temporary name so swaps work; a failed entry does not stop the rest.
     */
    fun batchRename(entries: List<Entry>, steps: List<RenameStep>, includeExtension: Boolean) {
        viewModelScope.launch {
            val previews = BatchRename.preview(entries, steps, namesInFolder(), keepExtension = !includeExtension)
            val plan = BatchRename.plan(previews)
            if (plan.isEmpty()) { showMessage("Nothing to rename"); return@launch }

            var renamed = 0
            val failures = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                // Rename inside one StorageRun so DeferredWrites providers (archives) apply everything in a
                // single rewrite instead of one per rename.
                val batched = graph.providers.all.filterIsInstance<DeferredWrites>()
                val run = StorageRun()
                var settled = false
                try {
                    withContext(run) {
                        val staged = mutableListOf<Pair<NodeRef, String>>()
                        for (action in plan) {
                            val provider = graph.providers.provider(action.entry.ref)
                            try {
                                if (action.viaTemporary) {
                                    val temporary = ".luna-rename-${UUID.randomUUID()}"
                                    val moved = provider.rename(action.entry.ref, temporary)
                                    staged += moved.ref to action.target
                                } else {
                                    provider.rename(action.entry.ref, action.target)
                                    renamed++
                                }
                            } catch (error: Exception) {
                                failures += "${action.entry.name}: ${error.message ?: "failed"}"
                            }
                        }
                        for ((ref, target) in staged) {
                            try {
                                graph.providers.provider(ref).rename(ref, target)
                                renamed++
                            } catch (error: Exception) {
                                failures += "$target: ${error.message ?: "failed"}"
                            }
                        }
                    }
                    for (writer in batched) {
                        try {
                            writer.flush(run)
                        } catch (error: Exception) {
                            // The flush is one rewrite, so its failure means no rename landed.
                            failures += error.message ?: "Could not save the new names"
                            renamed = 0
                        }
                    }
                    settled = true
                } finally {
                    if (!settled) {
                        withContext(NonCancellable) {
                            batched.forEach { runCatching { it.discardPending(run) } }
                        }
                    }
                }
            }
            forgetListings()
            loadDirectory(force = true)
            showMessage(
                when {
                    failures.isEmpty() -> "Renamed $renamed items"
                    renamed == 0 -> "Nothing renamed. ${failures.first()}"
                    else -> "Renamed $renamed, ${failures.size} failed. ${failures.first()}"
                },
            )
        }
    }

    fun currentFolderPath(): String = _state.value.directoryPath.orEmpty()

    fun insideArchive(): Boolean = _state.value.location?.ref?.provider == graph.insideArchives.id

    /** Checks the clipboard rather than the current folder: by paste time the user has left the archive. */
    fun carryingArchiveMembers(): Boolean =
        _state.value.clipboard?.entries?.any { it.ref.provider == graph.insideArchives.id } == true

    fun runInBackground(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    private val revealed = mutableSetOf<String>()
    /** Set when an outcome arrived under another overlay; the queue opens when that overlay closes. */
    private var revealQueue = false

    private fun revealOperation(item: QueueItem) {
        revealed.retainAll(_state.value.operations.mapTo(mutableSetOf()) { it.id })
        if (!revealed.add(item.id)) return
        when (_state.value.overlay) {
            Overlay.Queue -> Unit
            null -> showOverlay(Overlay.Queue)
            else -> {
                revealQueue = true
                showMessage("${item.title}: ${item.status.lowercase().replace('_', ' ')}")
            }
        }
    }

    fun showOverlay(overlay: Overlay?) {
        val target = if (overlay == null && revealQueue) Overlay.Queue else overlay
        if (target == Overlay.Queue) revealQueue = false
        val kept = when (target) {
            is Overlay.Viewer -> target.entry.ref.takeIf { target.kind == ViewerKind.TEXT || target.kind == ViewerKind.CODE }
            is Overlay.Manifest -> ManifestOf(target.entry.ref)
            else -> null
        }
        if (kept == null || kept != activeEditor?.key) releaseEditor()
        // Any other overlay abandons the pending delete check. Both steps of a guarded deletion are
        // Overlay.Delete, so the second step keeps it.
        if (target !is Overlay.Delete) cancelDeleteCheck()
        _state.update { it.copy(overlay = target) }
    }

    fun dismissOverlay() = showOverlay(null)

    fun showScreen(screen: Screen) {
        if (screen != _state.value.screen) {
            cancelArchiveOpening()
            releaseEditor()
            // The overlay is cleared below, so a pending check must not reopen its dialog over the new screen.
            cancelDeleteCheck()
            revealQueue = false
        }
        _state.update {
            if (screen == it.screen) it
            else it.showing(screen).copy(selected = emptySet(), overlay = null)
        }
    }

    fun setAppDataBookmark(on: Boolean) {
        setPreferences(_state.value.preferences.copy(showAppData = on))
        bookmarks.setAppData(on)
    }

    fun resetSettings() {
        val previous = _state.value.preferences
        _state.update {
            it.copy(
                folderViews = emptyMap(),
                session = SessionChoices(),
                bookmarks = it.bookmarks.filterNot { mark -> bookmarks.isSwitchBookmark(mark) },
                homeBookmarks = it.homeBookmarks.filterNot { mark -> bookmarks.isSwitchBookmark(mark) },
            )
        }
        // Through setPreferences so roots, the index and ordering follow.
        setPreferences(Preferences(introSeen = previous.introSeen))
        graph.debugLog.setRecording(false)
        showMessage("Settings are back to their defaults")
    }

    fun canPinShortcut() = graph.shortcuts.canPin()

    fun pinCurrentFolder() {
        val path = _state.value.directoryPath
        if (path.isNullOrBlank()) { showMessage("This location has no path to pin"); return }
        pinPath(path, _state.value.location?.title.orEmpty())
    }

    fun pinPath(path: String, label: String) {
        if (path.isBlank()) { showMessage("Enter a path to pin"); return }
        val name = label.ifBlank { path.trimEnd('/').substringAfterLast('/').ifBlank { path } }
        if (graph.shortcuts.pinFolder(path, name)) showMessage("Ask your launcher to place the shortcut")
        else showMessage("This launcher does not accept pinned shortcuts")
    }

    private fun publishShortcuts(recentLocations: List<Location>) {
        val recent = recentLocations.mapNotNull { location ->
            val ref = location.crumbs.lastOrNull()?.ref ?: return@mapNotNull null
            val path = resolver.shownPathOf(ref) ?: return@mapNotNull null
            path to location.title
        }
        if (recent.isNotEmpty()) graph.shortcuts.publishRecent(recent)
    }

    fun reveal(path: String) {
        viewModelScope.launch {
            if (!revealPath(path)) showUnreachable(path)
        }
    }

    fun revealEntry(entry: Entry) {
        val tabId = _state.value.activeTabId
        viewModelScope.launch {
            val located = withContext(Dispatchers.IO) { resolver.crumbsTo(entry.ref) }
            // Parking and navigating act on the front tab, which has to be the one that asked.
            if (_state.value.activeTabId != tabId) return@launch
            if (located == null) showUnreachable(entry.name)
            else {
                // Select through pendingReveal once the listing arrives; loading clears the selection.
                parkFlatView()
                pendingReveal = entry.ref
                navigate(located)
            }
        }
    }

    fun cancelSearch() {
        val summary = "${_state.value.entries.size} found · stopped"
        flat?.let { it.job?.cancel(); it.running = false; if (liveIsFlat(it)) it.summary = summary }
        browseJob?.cancel(); generation++
        _state.update { it.copy(searching = false, view = withSummary(it.view, summary)) }
    }

    /**
     * [toOrigin] returns to the screen the results were opened from. Pass false when a storage change, not
     * the user, closes the results.
     */
    fun closeSearch(toOrigin: Boolean = true) {
        val origin = _state.value.view?.openedFrom
        endFlat(_state.value.activeTabId)
        loadDirectory()
        if (toOrigin && origin != null) {
            _state.update { it.showing(origin).copy(selected = emptySet(), overlay = null) }
        }
    }

    fun grantFolder(uri: Uri, flags: Int) {
        viewModelScope.launch {
            try {
                graph.saf.takeGrant(uri, flags)
                val roots = loadRoots(_state.value.preferences)
                _state.update { it.copy(roots = roots) }
                val root = graph.saf.rootForTree(uri)
                navigateRoot(roots.find { it.ref == root.ref } ?: root)
            } catch (error: Exception) { showMessage("Folder access could not be saved: ${error.message}") }
        }
    }

    fun forgetRoot(root: StorageRoot) {
        viewModelScope.launch {
            try {
                graph.providers.provider(root.ref).forgetRoot(root)
                _state.update { it.copy(roots = it.roots - root) }
                reload()
            } catch (error: Exception) { showMessage(readableError(error)) }
        }
    }

    private fun readableError(error: Exception, network: Boolean = false): String = when ((error as? StorageException)?.reason) {
        StorageError.PERMISSION -> if (network) error.message ?: "The server refused"
            else "Android does not allow Luna to read this folder."
        StorageError.AUTH -> error.message ?: "This storage needs you to sign in again."
        StorageError.DISCONNECTED -> "This storage is disconnected. Reconnect it, then refresh."
        StorageError.OFFLINE -> "This storage cannot be reached. Check the connection, then refresh."
        StorageError.TIMEOUT -> "This storage did not answer in time. Try again."
        StorageError.RATE_LIMITED -> "This storage asked Luna to slow down. Try again shortly."
        StorageError.NOT_FOUND -> "This location no longer exists."
        else -> error.message ?: "This folder could not be read."
    }

    // Startup can resume immediately, so all fields must be initialized before launching it.
    init {
        viewModelScope.launch {
            var published: List<Location>? = null
            var served: List<ServedFolder>? = null
            for (snapshot in saves) {
                try { graph.database.saveSession(snapshot) }
                catch (error: Exception) { showMessage("Could not save this session: ${error.message}") }
                if (snapshot.recent != published) {
                    published = snapshot.recent
                    withContext(Dispatchers.IO) { runCatching { publishShortcuts(snapshot.recent) } }
                }
                // Pickers read the served folders from the saved session, so tell them after the save.
                if (snapshot.preferences.servedFolders != served) {
                    served = snapshot.preferences.servedFolders
                    withContext(Dispatchers.IO) { runCatching { LunaDocumentsProvider.rootsChanged(getApplication()) } }
                }
            }
        }
        viewModelScope.launch {
            var sessionError: String? = null
            val restored = try { graph.database.loadSession() } catch (e: Exception) {
                sessionError = "Saved navigation could not be restored: ${e.message}"; null
            }
            val initial = restored ?: BrowserState()
            // Accounts must be in place before their roots are enumerated.
            graph.smbAccounts = initial.smbAccounts
            graph.b2Accounts = initial.b2Accounts
            graph.transferAccounts = initial.transferAccounts
            val roots = try { loadRoots(initial.preferences) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                sessionError = "Storage could not be loaded: ${error.message}"
                emptyList()
            }
            val tabs = initial.tabs.ifEmpty {
                roots.firstOrNull()?.let { listOf(BrowserTab(history = listOf(rootLocation(it)))) } ?: emptyList()
            }
            _state.value = initial.copy(ready = false, roots = roots, tabs = tabs,
                activeTabId = initial.activeTabId.takeIf { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id.orEmpty(),
                fullAccess = graph.hasFullAccess(), message = sessionError)
                .showing(if (initial.preferences.startPath.isNotBlank()) Screen.BROWSER
                    else initial.preferences.startScreen, emptyList())
            // Apply restored preferences before the index builds a query.
            applyToGraph(initial.preferences)
            advertiseViewers(initial.preferences.openWithLuna)
            smb.apply()
            b2.apply()
            servers.apply()
            if (!initial.vaultLocked) withContext(Dispatchers.IO) { vault.open() }
            // Catch failures here so the refresh and the collectors below still run.
            try {
                graph.database.refreshQueue()
                graph.database.refreshTrash()
                graph.queue.reconnect()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showMessage("Background work could not be restored: ${error.message}") }
            reload()
            initial.preferences.startPath.takeIf { it.isNotBlank() }?.let { openStartFolder(it) }
            _state.update { it.copy(ready = true) }
            graph.providers.all.forEach { provider -> launch { provider.notices.collect { showMessage(it) } } }
            launch { graph.database.trash.collect { items -> _state.update { it.copy(trashCount = items.size) } } }
            launch {
                graph.shizuku.state.collect { helper ->
                    val before = _state.value.helper
                    _state.update { it.copy(helper = helper) }
                    // What Android/data and Android/obb show depends on who is asked.
                    if ((before == HelperState.READY) != (helper == HelperState.READY)) {
                        forgetListings()
                        graph.thumbnails.forgetFailures()
                        if (_state.value.view is View.Folder) loadDirectory(preserveSelection = true, force = true)
                    }
                }
            }
            var queueObserved = false
            graph.database.queue.collect { operations ->
                val old = _state.value.operations.associate { it.id to it.status }
                val finished = operations.filter {
                    it.status !in PENDING_STATUSES && old[it.id] != it.status && (old.containsKey(it.id) || queueObserved)
                }
                _state.update { it.copy(operations = operations) }
                finished.firstOrNull { it.status in ATTENTION_STATUSES }?.let(::revealOperation)
                if (finished.isNotEmpty()) {
                    // The operation may have changed folders that are not on screen.
                    forgetListings()
                    if (!_state.value.searchActive) loadDirectory(force = true)
                }
                queueObserved = true
            }
        }
    }
}
