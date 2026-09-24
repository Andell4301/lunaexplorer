package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.Bookmark
import com.lunaexplorer.app.model.FolderView
import com.lunaexplorer.app.model.Preferences
import com.lunaexplorer.app.model.StoredProcedure
import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.smb.SmbAccount
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class TransferDocument(
    /** Format stamp. 0 (absent) means the file is not a Luna export. */
    val luna: Int = 0,
    /** Version name of the app that wrote the file. Display only. */
    val app: String = "",
    val written: Long = 0,
    val values: Map<String, JsonElement> = emptyMap(),
) {
    val readable: Boolean get() = luna == TRANSFER_FORMAT
}

/** Bump only for a change that readers of the current format cannot parse. */
const val TRANSFER_FORMAT = 1

data class TransferSource(
    val preferences: Preferences = Preferences(),
    val bookmarks: List<Bookmark> = emptyList(),
    val homeBookmarks: List<Bookmark> = emptyList(),
    val folderViews: Map<String, FolderView> = emptyMap(),
    val smbAccounts: List<SmbAccount> = emptyList(),
    val b2Accounts: List<B2Account> = emptyList(),
    val transferAccounts: List<TransferAccount> = emptyList(),
    val procedures: List<StoredProcedure> = emptyList(),
    val vaultLocked: Boolean = false,
    val openDefaults: List<OpenDefault> = emptyList(),
    val recordingLog: Boolean = false,
    /** SMB passwords by account id. Empty unless the vault was opened for an export. */
    val passwords: Map<String, String> = emptyMap(),
    /** B2 application keys by account id, carried under the same condition. */
    val b2Keys: Map<String, String> = emptyMap(),
    val transferCredentials: Map<String, TransferCredentials> = emptyMap(),
)

sealed interface TransferWrite {
    data class Applied(val source: TransferSource) : TransferWrite
    data class Refused(val why: String) : TransferWrite
}

/** [chosen] is null when every item is kept. */
data class TransferValue(val json: JsonElement, val chosen: Set<String>? = null) {
    fun keeps(key: String): Boolean = chosen == null || key in chosen
}

/** One row inside a list-valued setting. [missing] marks a destination this device does not have. */
data class TransferItem(
    val key: String,
    val label: String,
    val detail: String = "",
    val missing: Boolean = false,
    /** What the path check is asked about, when that differs from [detail]. */
    val probe: String = detail,
)

class TransferUnit(
    val id: String,
    val page: String,
    val label: String,
    /** Needs authentication before an export may carry it. */
    val sensitive: Boolean = false,
    /** Null when this device has nothing to write for it. */
    val read: (TransferSource) -> JsonElement?,
    val write: (TransferValue, TransferSource) -> TransferWrite,
    val items: ((JsonElement, TransferSource) -> List<TransferItem>)? = null,
    // Model fields covered by this unit when they differ from the last segment of its id.
    claims: Set<String> = emptySet(),
    /** For a sensitive unit, whether the vault may hold something for it: [read] is null while the vault is closed. */
    val mayHold: (TransferSource) -> Boolean = { false },
) {
    val claims: Set<String> = claims.ifEmpty { setOf(id.substringAfterLast('.')) }
}

object TransferCodec {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(document: TransferDocument): String = json.encodeToString(document)

    /** Null for anything that is not a Luna export, including unparseable input. Never throws. */
    fun decode(raw: String): TransferDocument? = runCatching {
        json.decodeFromString<TransferDocument>(raw)
    }.getOrNull()?.takeIf { it.luna > 0 }

    fun export(source: TransferSource, ids: Set<String>, app: String, written: Long): TransferDocument {
        val values = SettingsRegistry.units
            .filter { it.id in ids }
            .mapNotNull { unit -> unit.read(source)?.let { unit.id to it } }
        return TransferDocument(luna = TRANSFER_FORMAT, app = app, written = written,
            values = values.toMap())
    }
}

fun TransferDocument.unknownIds(): List<String> =
    values.keys.filter { SettingsRegistry.unit(it) == null }.sorted()

internal fun JsonElement.primitiveField(name: String): JsonPrimitive? = (this as? JsonObject)?.get(name) as? JsonPrimitive
