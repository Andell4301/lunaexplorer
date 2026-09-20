package com.lunaexplorer.app.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.PathAddressable
import com.lunaexplorer.core.ProviderRegistry
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface ContentAddressable {
    fun contentUri(ref: NodeRef): Uri?
}

fun storageUriOrNull(context: Context, registry: ProviderRegistry, ref: NodeRef): Uri? {
    val provider = registry.provider(ref)
    (provider as? PathAddressable)?.pathOf(ref)?.let { path ->
        return FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
    }
    return (provider as? ContentAddressable)?.contentUri(ref) ?: StreamProvider.uriFor(context, ref)
}

/** The caller must add FLAG_GRANT_READ_URI_PERMISSION and ClipData to its send/view intent. */
suspend fun storageUri(context: Context, registry: ProviderRegistry, ref: NodeRef): Uri = withContext(Dispatchers.IO) {
    storageUriOrNull(context, registry, ref)
        ?: throw StorageException(StorageError.UNSUPPORTED, "This provider does not yet support Android sharing")
}
