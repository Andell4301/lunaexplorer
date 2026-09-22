package com.lunaexplorer.app.model

import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.shizuku.HelperState
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.core.*
import kotlinx.serialization.Serializable

/** [intoFolder] is null to extract without a containing folder. */
data class ExtractPlan(val intoFolder: String?, val password: String = "")

/** With [extract] set, the entry is an archive to unpack at the paste target, not a file to copy. */
data class Clipboard(val entries: List<Entry>, val move: Boolean, val extract: ExtractPlan? = null)

@Serializable
data class ConflictRow(val ref: NodeRef? = null, val directory: Boolean = false, val name: String = "")

data class QueueItem(
    val id: String, val title: String, val status: String,
    val detail: String = "", val bytes: Long = 0, val currentName: String = "",
    val results: List<String> = emptyList(), val artifacts: List<NodeRef> = emptyList(),
    /** Items awaiting a decision, in retry order. A typed name renames the first. */
    val conflicts: List<ConflictRow> = emptyList(),
) {
    val conflictFolders: Boolean get() = conflicts.any { it.directory }
    val conflictFiles: Boolean get() = conflicts.any { !it.directory }
    val conflictNames: List<String> get() = conflicts.map { it.name }
}

data class DeleteCheck(
    val entries: List<Entry>,
    val totals: FolderTotals? = null,
    val measuring: Boolean = false,
    /** Selected top-level Android folders, by absolute path. */
    val androidPaths: List<String> = emptyList(),
    /** False until the Android-folder lookup has finished. */
    val guardKnown: Boolean = false,
    /** Large-deletion warning threshold in bytes, or null while the warning is off. */
    val threshold: Long? = null,
    /** Every entry is on storage that keeps old versions, so the delete is a hide or a purge and the bin does not apply. */
    val versioned: Boolean = false,
) {
    val bytes: Long get() = entries.sumOf { if (it.directory) 0L else it.size ?: 0L } + (totals?.bytes ?: 0L)
    val files: Long get() = entries.count { !it.directory } + (totals?.files ?: 0L)
    val large: Boolean get() = threshold?.let { bytes >= it } == true
    val guarded: Boolean get() = androidPaths.isNotEmpty() || large
}

data class BrowserState(
    val ready: Boolean = false,
    val tabs: List<BrowserTab> = emptyList(), val activeTabId: String = "",
    val roots: List<StorageRoot> = emptyList(), val bookmarks: List<Bookmark> = emptyList(),
    val homeBookmarks: List<Bookmark> = emptyList(),
    val recent: List<Location> = emptyList(), val preferences: Preferences = Preferences(),
    /** Keyed by path, least recently set first. The oldest entries are evicted at the limit. */
    val folderViews: Map<String, FolderView> = emptyMap(),
    val filter: String = "",
    val extensions: Set<String> = emptySet(),
    val entries: List<Entry> = emptyList(), val selected: Set<NodeRef> = emptySet(),
    val sections: List<SectionRun> = emptyList(),
    /** A tool screen holds a selection. Back clears it before leaving the tool. */
    val toolSelectionActive: Boolean = false,
    val view: View? = null,
    val overlay: Overlay? = null,
    val loading: Boolean = false, val error: String? = null,
    /** True only for user-requested refreshes, not initial folder loads. */
    val refreshing: Boolean = false,
    val errorReason: StorageError? = null,
    val fullAccess: Boolean = false,
    val helper: HelperState = HelperState.OFF,
    val clipboard: Clipboard? = null, val message: String? = null,
    val session: SessionChoices = SessionChoices(),
    val trashCount: Int = 0,
    val screen: Screen = Screen.HOME,
    /** Oldest first. */
    val backStack: List<Screen> = emptyList(),
    val smbAccounts: List<SmbAccount> = emptyList(),
    val b2Accounts: List<B2Account> = emptyList(),
    val transferAccounts: List<TransferAccount> = emptyList(),
    /** Outside [Preferences] because it identifies the vault's encryption key and must survive a preference reset. */
    val vaultLocked: Boolean = false,
    val searching: Boolean = false,
    val openingArchive: ArchiveOpening? = null,
    val deleteCheck: DeleteCheck? = null,
    val operations: List<QueueItem> = emptyList(),
) {
    val tab: BrowserTab? get() = tabs.find { it.id == activeTabId }
    val inBrowserHistory: Boolean get() = screen == Screen.BROWSER && tab?.let { it.index > it.floor } == true
    val location: Location? get() = tab?.location
    val searchActive: Boolean get() = view is View.Search || view is View.Category
    val searchSummary: String get() = when (val v = view) { is View.Search -> v.summary; is View.Category -> v.summary; else -> "" }
    val searchNote: String get() = when (val v = view) { is View.Search -> v.note; is View.Category -> v.note; else -> "" }
    val directory: Entry? get() = (view as? View.Folder)?.directory
    val directoryPath: String? get() = (view as? View.Folder)?.path
    val folderKey: String? get() = (view as? View.Folder)?.folderKey
    val listingRef: NodeRef? get() = (view as? View.Folder)?.listingRef
    // Visible rows can still belong to the previous folder while the next listing loads.
    val dropHere: Entry? get() = (view as? View.Folder)
        ?.takeIf { screen == Screen.BROWSER && it.listingRef == it.ref && error == null }?.directory
        ?.takeIf { Capability.CREATE in it.capabilities }
    /** The screen is checked because a folder stays loaded behind the other screens. */
    val canPasteHere: Boolean get() = screen == Screen.BROWSER && !searchActive &&
        Capability.CREATE in directory?.capabilities.orEmpty()
    val selectedEntries: List<Entry> get() = entries.filter { it.ref in selected }
    val rowSections: List<SectionRun> get() =
        sections.takeIf { it.isEmpty() || it.last().end == entries.size } ?: emptyList()
    val confirmDelete: Boolean get() = session.confirmDelete ?: preferences.confirmDelete
    fun showing(target: Screen, stack: List<Screen> = backStackFor(target)): BrowserState = copy(
        screen = target, backStack = stack,
        tabs = tabs.map { if (it.id == activeTabId) it.copy(screen = target, backStack = stack) else it })

    fun backStackFor(target: Screen): List<Screen> = when {
        target == screen -> backStack
        target == preferences.startScreen -> emptyList()
        target in backStack -> backStack.take(backStack.indexOf(target))
        else -> backStack + screen
    }
    val recycleBin: Boolean get() = session.recycleBin ?: preferences.recycleBin
    val dropAction: DropAction get() = session.dropAction ?: preferences.dropAction
    val versionedDelete: VersionedDelete get() = session.versionedDelete ?: preferences.versionedDelete
    val activeOperations: Int get() = operations.count { it.status.uppercase() in ACTIVE_OPERATIONS }
    val leadOperation: QueueItem? get() = operations.firstOrNull { it.status.uppercase() in WAITING_OPERATIONS }
        ?: operations.firstOrNull { it.status.uppercase() in ACTIVE_OPERATIONS }
}

private val ACTIVE_OPERATIONS = setOf("QUEUED", "RUNNING", "CONFLICT", "INTERRUPTED")
private val WAITING_OPERATIONS = setOf("CONFLICT", "INTERRUPTED")
