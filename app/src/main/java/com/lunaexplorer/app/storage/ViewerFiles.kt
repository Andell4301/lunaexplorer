package com.lunaexplorer.app.storage

import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.StorageError
import com.lunaexplorer.core.StorageException
import com.lunaexplorer.core.StorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

internal object ViewerFiles {
    suspend fun stage(cacheDir: File, provider: StorageProvider, entry: Entry, limit: Long, tooLarge: String = TOO_LARGE): Result<File> {
        var staged: File? = null
        return try {
            Result.success(withContext(Dispatchers.IO) {
                require(limit > 0)
                if ((entry.size ?: 0) > limit) throw StorageException(StorageError.UNSUPPORTED, tooLarge)
                val directory = File(cacheDir, "viewers")
                check(directory.isDirectory || directory.mkdirs()) { "The document cache could not be created" }
                // A viewer deletes its own copy; one here for a day was left by a process that died.
                val stale = System.currentTimeMillis() - STALE_MILLIS
                directory.listFiles()?.forEach { if (it.lastModified() < stale) it.delete() }
                val file = File.createTempFile("document-", ".${entry.name.substringAfterLast('.', "bin")}", directory)
                staged = file
                provider.openRead(entry.ref).use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) throw StorageException(StorageError.IO, "The document stopped returning bytes")
                            total += read
                            if (total > limit) throw StorageException(StorageError.UNSUPPORTED, tooLarge)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                file
            })
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { staged?.delete() }
            if (error is CancellationException) throw error
            Result.failure(error)
        }
    }

    const val TOO_LARGE = "This document is too large for the built-in viewer. Open it with another app."
    private const val STALE_MILLIS = 24 * 60 * 60 * 1000L
}
