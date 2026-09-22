package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.*
import com.lunaexplorer.app.storage.MediaCategory
import com.lunaexplorer.app.storage.ViewerAdvertising
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.core.StorageException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal fun interface PathCheck {
    fun exists(path: String): Boolean
}

// Missing paths flag preview rows without blocking import; compare paths exactly as typed.
internal var transferPathCheck: PathCheck = PathCheck { true }

private val codec = TransferCodec.json

internal fun storedProcedures() = TransferUnit(
    id = "procedures.saved", page = "Stored procedures", label = "Stored procedures",
    claims = setOf("procedures"),
    read = { source ->
        source.procedures.takeIf { it.isNotEmpty() }?.let {
            codec.encodeToJsonElement(ListSerializer(StoredProcedure.serializer()), it)
        }
    },
    items = { json, _ ->
        decode(json, StoredProcedure.serializer()).map { procedure ->
            TransferItem(procedure.id, procedure.name,
                detail = if (procedure.schedule?.enabled == true) "Schedule disabled on import" else "",
                probe = "")
        }
    },
    write = { value, source ->
        try {
            val incoming = codec.decodeFromJsonElement(ListSerializer(StoredProcedure.serializer()), value.json)
                .filter { value.keeps(it.id) }
                .onEach { it.validate() }
                .map { it.copy(schedule = it.schedule?.copy(enabled = false)) }
            require(incoming.map { it.id }.distinct().size == incoming.size) { "Duplicate procedure IDs" }
            val merged = source.procedures.map { held -> incoming.firstOrNull { it.id == held.id } ?: held } +
                incoming.filterNot { arriving -> source.procedures.any { it.id == arriving.id } }
            TransferWrite.Applied(source.copy(procedures = merged))
        } catch (error: IllegalArgumentException) {
            TransferWrite.Refused(error.message ?: "Invalid stored procedures")
        } catch (error: StorageException) {
            TransferWrite.Refused(error.message ?: "Invalid stored procedures")
        }
    },
)

internal fun bookmarks(id: String, label: String, list: BookmarkList) = TransferUnit(
    id = id, page = "Bookmarks", label = label,
    claims = setOf(if (list == BookmarkList.SIDEBAR) "bookmarks" else "homeBookmarks"),
    read = { source ->
        val saved = if (list == BookmarkList.SIDEBAR) source.bookmarks else source.homeBookmarks
        // Null rather than an empty array, so a preview reports incoming bookmarks as NEW, not REPLACES.
        saved.takeIf { it.isNotEmpty() }
            ?.let { list -> JsonArray(list.map { codec.encodeToJsonElement(Bookmark.serializer(), it) }) }
    },
    items = { json, _ ->
        decode(json, Bookmark.serializer()).map { bookmark ->
            val path = (bookmark.destination as? Destination.Place)?.path
            TransferItem(
                key = bookmark.id,
                label = bookmark.title,
                detail = path ?: (bookmark.destination as? Destination.Tool)?.screen?.title.orEmpty(),
                missing = path != null && !transferPathCheck.exists(path),
            )
        }
    },
    write = { value, source ->
        val incoming = decode(value.json, Bookmark.serializer()).filter { value.keeps(it.id) }
        val existing = if (list == BookmarkList.SIDEBAR) source.bookmarks else source.homeBookmarks
        // Merge by id so importing the same file twice does not duplicate entries.
        val merged = existing.map { held -> incoming.firstOrNull { it.id == held.id } ?: held } +
            incoming.filterNot { arriving -> existing.any { it.id == arriving.id } }
        TransferWrite.Applied(
            if (list == BookmarkList.SIDEBAR) source.copy(bookmarks = merged)
            else source.copy(homeBookmarks = merged),
        )
    },
)

internal fun folderViews() = TransferUnit(
    id = "files.folderViews", page = "Files and folders", label = "Per-folder display settings",
    claims = setOf("folderViews"),
    read = { source ->
        source.folderViews.takeIf { it.isNotEmpty() }?.let { views ->
        JsonArray(views.map { (key, own) ->
            JsonObject(mapOf(
                "folder" to JsonPrimitive(key),
                "view" to JsonPrimitive(own.view.name),
                "sort" to JsonPrimitive(own.sort.name),
                "descending" to JsonPrimitive(own.descending),
            ))
        }) }
    },
    items = { json, _ ->
        readViews(json).map { (key, _) ->
            TransferItem(key, key.trimEnd('/').substringAfterLast('/').ifEmpty { key }, key,
                missing = !transferPathCheck.exists(key))
        }
    },
    write = { value, source ->
        val incoming = readViews(value.json).filter { value.keeps(it.first) }.toMap()
        // Rewritten keys are removed before being re-added so they move to the end; the cap then
        // evicts the least recently set, as setViewOptions does.
        val merged = ((source.folderViews - incoming.keys) + incoming).entries
            .toList().takeLast(FOLDER_VIEW_LIMIT).associate { it.key to it.value }
        TransferWrite.Applied(source.copy(folderViews = merged))
    },
)

private fun readViews(json: JsonElement): List<Pair<String, FolderView>> =
    (json as? JsonArray).orEmpty().mapNotNull { row ->
        val folder = row.primitiveField("folder")?.contentOrNull ?: return@mapNotNull null
        val view = ViewMode.entries.firstOrNull { it.name == row.primitiveField("view")?.contentOrNull }
            ?: return@mapNotNull null
        val sort = SortOrder.entries.firstOrNull { it.name == row.primitiveField("sort")?.contentOrNull }
            ?: return@mapNotNull null
        val descending = row.primitiveField("descending")?.booleanOrNull ?: false
        folder to FolderView(view, sort, descending)
    }

internal fun categoryExtras() = TransferUnit(
    id = "categories.extras", page = "Media categories", label = "Additional extensions",
    claims = setOf("categoryExtras"),
    read = { source ->
        source.preferences.categoryExtras.takeIf { it.isNotEmpty() }?.let { extras ->
            JsonObject(extras.mapValues { (_, extensions) ->
                JsonArray(extensions.sorted().map { JsonPrimitive(it) })
            })
        }
    },
    items = { json, _ ->
        (json as? JsonObject).orEmpty().map { (category, extensions) ->
            val names = (extensions as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val label = MediaCategory.entries.firstOrNull { it.name == category }?.label ?: category
            TransferItem(category, label, names.joinToString(", ") { ".$it" })
        }
    },
    write = { value, source ->
        val incoming = (value.json as? JsonObject).orEmpty()
            .filterKeys { value.keeps(it) }
            // An unknown category name would be stored and never read.
            .filterKeys { key -> MediaCategory.entries.any { it.name == key } }
            .mapValues { (_, extensions) ->
                (extensions as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trimStart('.')?.lowercase()?.ifBlank { null } }
                    .toSet()
            }
        TransferWrite.Applied(source.copy(
            preferences = source.preferences.copy(
                categoryExtras = source.preferences.categoryExtras + incoming)))
    },
)

internal fun openWithLuna() = TransferUnit(
    id = "openWith.kinds", page = "Open with Luna", label = "Kinds Luna offers to open",
    claims = setOf("openWithLuna"),
    read = { source -> JsonArray(source.preferences.openWithLuna.sorted().map { JsonPrimitive(it) }) },
    items = { json, _ ->
        (json as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map { key ->
            TransferItem(key, ViewerAdvertising.kinds.firstOrNull { it.key == key }?.label ?: key)
        }
    },
    write = { value, source ->
        val offered = (value.json as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { it in ViewerAdvertising.allKeys }
        val taken = offered.filter { value.keeps(it) }.toSet()
        // Kinds the file does not list were off at its source, so they end up off here. Kinds it
        // lists but the user unticked keep their current state.
        val untouched = offered.toSet() - taken
        val kept = source.preferences.openWithLuna.filter { it in untouched }
        TransferWrite.Applied(source.copy(
            preferences = source.preferences.copy(openWithLuna = taken + kept)))
    },
)

internal fun servedFolders() = TransferUnit(
    id = "documents.folders", page = "Document provider", label = "Folders served to other apps",
    claims = setOf("servedFolders"),
    read = { source ->
        source.preferences.servedFolders.takeIf { it.isNotEmpty() }?.let { folders ->
            JsonArray(folders.map {
                JsonObject(mapOf("path" to JsonPrimitive(it.path), "name" to JsonPrimitive(it.name)))
            })
        }
    },
    items = { json, _ ->
        readServed(json).map {
            TransferItem(it.path, it.name.ifBlank { it.path.trimEnd('/').substringAfterLast('/') },
                it.path, missing = !transferPathCheck.exists(it.path))
        }
    },
    write = { value, source ->
        val incoming = readServed(value.json).filter { value.keeps(it.path) }
        val existing = source.preferences.servedFolders
        // Match on path: grants held by other apps are keyed by it.
        val merged = existing.map { held -> incoming.firstOrNull { it.path == held.path } ?: held } +
            incoming.filterNot { arriving -> existing.any { it.path == arriving.path } }
        TransferWrite.Applied(source.copy(
            preferences = source.preferences.copy(servedFolders = merged)))
    },
)

private fun readServed(json: JsonElement): List<ServedFolder> =
    (json as? JsonArray).orEmpty().mapNotNull { row ->
        val path = row.primitiveField("path")?.contentOrNull?.ifBlank { null } ?: return@mapNotNull null
        ServedFolder(path, row.primitiveField("name")?.contentOrNull.orEmpty())
    }

internal fun smbAccounts() = TransferUnit(
    id = "network.smb", page = "Network", label = "SMB servers",
    claims = setOf("smbAccounts"),
    read = { source ->
        source.smbAccounts.takeIf { it.isNotEmpty() }
            ?.let { saved -> JsonArray(saved.map { codec.encodeToJsonElement(SmbAccount.serializer(), it) }) }
    },
    items = { json, _ ->
        decode(json, SmbAccount.serializer()).map {
            TransferItem(it.id, it.name.ifBlank { it.host }, it.address)
        }
    },
    write = { value, source ->
        val chosen = decode(value.json, SmbAccount.serializer()).filter { value.keeps(it.id) }
        val incoming = chosen.mapNotNull(::sane)
        val merged = source.smbAccounts.map { held -> incoming.firstOrNull { it.id == held.id } ?: held } +
            incoming.filterNot { arriving -> source.smbAccounts.any { it.id == arriving.id } }
        // Refuse only when servers were chosen and none is usable; choosing none is not an error.
        if (incoming.isEmpty() && chosen.isNotEmpty()) {
            TransferWrite.Refused("A server needs a host, a port from 1 to 65535 and a timeout from 1 to 600")
        } else {
            TransferWrite.Applied(source.copy(smbAccounts = merged))
        }
    },
)

/** SmbAccount does not validate itself; these are the account editor's limits. */
private fun sane(account: SmbAccount): SmbAccount? = account.takeIf {
    it.host.isNotBlank() && it.port in 1..65535 && it.options.timeoutSeconds in 1..600
}

internal fun smbPasswords() = TransferUnit(
    id = "network.smbPasswords", page = "Network", label = "Stored passwords", sensitive = true,
    mayHold = { it.smbAccounts.isNotEmpty() },
    read = { source ->
        source.passwords.takeIf { it.isNotEmpty() }
            ?.let { held -> JsonObject(held.mapValues { (_, password) -> JsonPrimitive(password) }) }
    },
    items = { json, source ->
        (json as? JsonObject).orEmpty().map { (accountId, _) ->
            val account = source.smbAccounts.firstOrNull { it.id == accountId }
            TransferItem(accountId, account?.name?.ifBlank { account.host } ?: "Unknown server",
                account?.address.orEmpty(), missing = account == null)
        }
    },
    write = { value, source ->
        readSecrets(value)?.let { incoming ->
            TransferWrite.Applied(source.copy(passwords = source.passwords + incoming))
        } ?: TransferWrite.Refused("Expected stored credentials as text")
    },
)

internal fun b2Accounts() = TransferUnit(
    id = "network.b2", page = "Network", label = "B2 accounts",
    claims = setOf("b2Accounts"),
    read = { source ->
        source.b2Accounts.takeIf { it.isNotEmpty() }
            ?.let { saved -> JsonArray(saved.map { codec.encodeToJsonElement(B2Account.serializer(), it) }) }
    },
    items = { json, _ ->
        decode(json, B2Account.serializer()).map { TransferItem(it.id, it.name.ifBlank { it.keyId }, it.address) }
    },
    write = { value, source ->
        val chosen = decode(value.json, B2Account.serializer()).filter { value.keeps(it.id) }
        val incoming = chosen.mapNotNull(::sane)
        val merged = source.b2Accounts.map { held -> incoming.firstOrNull { it.id == held.id } ?: held } +
            incoming.filterNot { arriving -> source.b2Accounts.any { it.id == arriving.id } }
        if (incoming.isEmpty() && chosen.isNotEmpty()) {
            TransferWrite.Refused("An account needs a key ID, a timeout from 1 to 600 and a part size from 5 to 5000")
        } else {
            TransferWrite.Applied(source.copy(b2Accounts = merged))
        }
    },
)

/** B2Account does not validate itself; these are the account editor's limits. */
private fun sane(account: B2Account): B2Account? = account.takeIf {
    it.keyId.isNotBlank() && it.options.timeoutSeconds in 1..600 && it.options.partMegabytes in 5..5000
}

internal fun b2Keys() = TransferUnit(
    id = "network.b2Keys", page = "Network", label = "Stored B2 keys", sensitive = true,
    mayHold = { it.b2Accounts.isNotEmpty() },
    read = { source ->
        source.b2Keys.takeIf { it.isNotEmpty() }
            ?.let { held -> JsonObject(held.mapValues { (_, key) -> JsonPrimitive(key) }) }
    },
    items = { json, source ->
        (json as? JsonObject).orEmpty().map { (accountId, _) ->
            val account = source.b2Accounts.firstOrNull { it.id == accountId }
            TransferItem(accountId, account?.name?.ifBlank { account.keyId } ?: "Unknown account",
                account?.address.orEmpty(), missing = account == null)
        }
    },
    write = { value, source ->
        readSecrets(value)?.let { incoming ->
            TransferWrite.Applied(source.copy(b2Keys = source.b2Keys + incoming))
        } ?: TransferWrite.Refused("Expected stored credentials as text")
    },
)

private fun readSecrets(value: TransferValue): Map<String, String>? {
    val objectValue = value.json as? JsonObject ?: return null
    return objectValue.filterKeys(value::keeps).mapValues { (_, secret) ->
        (secret as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    }
}

internal fun openDefaults() = TransferUnit(
    id = "defaults.apps", page = "Default apps", label = "What opens each kind of file",
    read = { source ->
        source.openDefaults.takeIf { it.isNotEmpty() }?.let { defaults ->
        JsonArray(defaults.map {
            JsonObject(mapOf(
                "key" to JsonPrimitive(it.key), "target" to JsonPrimitive(it.target),
                "mime" to JsonPrimitive(it.mime), "label" to JsonPrimitive(it.label),
            ))
        }) }
    },
    items = { json, _ ->
        readDefaults(json).map {
            TransferItem(it.key, it.key.substringAfter(':'), it.label.ifBlank { it.target },
                missing = !transferPathCheck.exists(APP_TARGET + it.target),
                probe = APP_TARGET + it.target)
        }
    },
    write = { value, source ->
        val incoming = readDefaults(value.json).filter { value.keeps(it.key) }
        val merged = source.openDefaults.map { held -> incoming.firstOrNull { it.key == held.key } ?: held } +
            incoming.filterNot { arriving -> source.openDefaults.any { it.key == arriving.key } }
        TransferWrite.Applied(source.copy(openDefaults = merged))
    },
)

/** Prefix for asking the path check about an installed app, by component, instead of a folder. */
internal const val APP_TARGET = "app:"

private fun readDefaults(json: JsonElement): List<OpenDefault> =
    (json as? JsonArray).orEmpty().mapNotNull { row ->
        val key = row.primitiveField("key")?.contentOrNull?.ifBlank { null } ?: return@mapNotNull null
        val target = row.primitiveField("target")?.contentOrNull?.ifBlank { null } ?: return@mapNotNull null
        OpenDefault(key, target, row.primitiveField("mime")?.contentOrNull.orEmpty(),
            row.primitiveField("label")?.contentOrNull.orEmpty())
    }

private fun <T> decode(json: JsonElement, serializer: KSerializer<T>): List<T> =
    (json as? JsonArray).orEmpty().mapNotNull { runCatching { codec.decodeFromJsonElement(serializer, it) }.getOrNull() }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
