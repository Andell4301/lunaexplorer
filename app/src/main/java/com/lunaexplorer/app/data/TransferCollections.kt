package com.lunaexplorer.app.data

import com.lunaexplorer.app.storage.transfer.TransferAccount
import com.lunaexplorer.app.storage.transfer.TransferCredentials
import com.lunaexplorer.app.storage.transfer.validationError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val codec = TransferCodec.json

internal fun transferAccounts() = TransferUnit(
    id = "network.transfer", page = "Network", label = "FTP/SFTP servers",
    claims = setOf("transferAccounts"),
    read = { source -> source.transferAccounts.takeIf { it.isNotEmpty() }?.let { saved ->
        JsonArray(saved.map { codec.encodeToJsonElement(TransferAccount.serializer(), it) })
    } },
    items = { json, _ -> readAccounts(json).orEmpty().map { TransferItem(it.id, it.name.ifEmpty { it.host }, it.host) } },
    write = { value, source ->
        val incoming = readAccounts(value.json)?.filter { value.keeps(it.id) }
        val invalid = incoming?.firstNotNullOfOrNull { it.validationError() }
        when {
            incoming == null -> TransferWrite.Refused("Invalid FTP/SFTP servers")
            invalid != null -> TransferWrite.Refused(invalid)
            incoming.distinctBy { it.id }.size != incoming.size -> TransferWrite.Refused("Duplicate server ID")
            else -> {
                val merged = source.transferAccounts.map { held -> incoming.firstOrNull { it.id == held.id } ?: held } +
                    incoming.filterNot { arriving -> source.transferAccounts.any { it.id == arriving.id } }
                TransferWrite.Applied(source.copy(transferAccounts = merged))
            }
        }
    },
)

internal fun transferCredentials() = TransferUnit(
    id = "network.transferCredentials", page = "Network", label = "Stored FTP/SFTP credentials", sensitive = true,
    mayHold = { it.transferAccounts.isNotEmpty() },
    read = { source -> source.transferCredentials.takeIf { it.isNotEmpty() }?.let { saved ->
        JsonObject(saved.mapValues { codec.encodeToJsonElement(TransferCredentials.serializer(), it.value) })
    } },
    items = { json, source -> (json as? JsonObject).orEmpty().map { (id, _) ->
        val account = source.transferAccounts.firstOrNull { it.id == id }
        TransferItem(id, account?.name?.ifEmpty { account.host } ?: "Unknown server",
            account?.host.orEmpty(), missing = account == null)
    } },
    write = { value, source ->
        val incoming = readCredentials(value)
        if (incoming == null) TransferWrite.Refused("Invalid FTP/SFTP credentials")
        else TransferWrite.Applied(source.copy(transferCredentials = source.transferCredentials + incoming))
    },
)

private fun readAccounts(json: JsonElement): List<TransferAccount>? {
    val values = json as? JsonArray ?: return null
    return values.map {
        runCatching { codec.decodeFromJsonElement(TransferAccount.serializer(), it) }.getOrNull() ?: return null
    }
}

private fun readCredentials(value: TransferValue): Map<String, TransferCredentials>? {
    val values = value.json as? JsonObject ?: return null
    return values.filterKeys(value::keeps).mapValues { (_, secret) ->
        runCatching { codec.decodeFromJsonElement(TransferCredentials.serializer(), secret) }.getOrNull() ?: return null
    }
}
