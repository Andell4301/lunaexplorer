package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.Bookmark
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.BrowserTab
import com.lunaexplorer.app.model.FolderView
import com.lunaexplorer.app.model.Location
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.smb.SmbAccount
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Invalid sessions are reset by the ViewModel.
@Serializable
data class SessionDocument(
    val tabs: List<BrowserTab> = emptyList(),
    val activeTabId: String = "",
    val bookmarks: List<Bookmark> = emptyList(),
    val homeBookmarks: List<Bookmark> = emptyList(),
    val recent: List<Location> = emptyList(),
    val preferences: Preferences = Preferences(),
    val folderViews: Map<String, FolderView> = emptyMap(),
    val smbAccounts: List<SmbAccount> = emptyList(),
    val b2Accounts: List<B2Account> = emptyList(),
    val transferAccounts: List<TransferAccount> = emptyList(),
    val vaultLocked: Boolean = false,
)

object SessionCodec {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

    fun encode(state: BrowserState): String = json.encodeToString(SessionDocument(
        tabs = state.tabs,
        activeTabId = state.activeTabId,
        bookmarks = state.bookmarks,
        homeBookmarks = state.homeBookmarks,
        recent = state.recent.take(30),
        preferences = state.preferences,
        folderViews = state.folderViews,
        smbAccounts = state.smbAccounts,
        b2Accounts = state.b2Accounts,
        transferAccounts = state.transferAccounts,
        vaultLocked = state.vaultLocked,
    ))

    fun decode(raw: String): BrowserState {
        val document = json.decodeFromString<SessionDocument>(raw)
        val tabs = document.tabs.filter { it.history.isNotEmpty() }
            .map { it.copy(index = it.index.coerceIn(it.history.indices)) }
            .take(12)
        return BrowserState(
            tabs = tabs,
            activeTabId = document.activeTabId.takeIf { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id.orEmpty(),
            bookmarks = document.bookmarks,
            homeBookmarks = document.homeBookmarks,
            recent = document.recent.take(30),
            preferences = document.preferences,
            folderViews = document.folderViews,
            smbAccounts = document.smbAccounts,
            b2Accounts = document.b2Accounts,
            transferAccounts = document.transferAccounts,
            vaultLocked = document.vaultLocked,
        )
    }
}
