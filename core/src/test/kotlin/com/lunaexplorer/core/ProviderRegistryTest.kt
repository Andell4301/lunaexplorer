package com.lunaexplorer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProviderRegistryTest {
    @Test fun `cancelled root discovery propagates instead of returning an empty root list`() {
        val storage = MemoryStorageProvider("test")
        val cancelled = CancellationException("Stopped")
        val provider = object : StorageProvider by storage {
            override suspend fun roots(): List<StorageRoot> = throw cancelled
        }

        try {
            runBlocking { ProviderRegistry(listOf(provider)).roots() }
            fail("Cancellation was swallowed")
        } catch (failure: CancellationException) {
            assertEquals(cancelled.message, failure.message)
        }
    }

    @Test fun `unavailable providers do not hide other roots`() = runBlocking {
        val first = MemoryStorageProvider("first")
        val unavailable = object : StorageProvider by MemoryStorageProvider("offline") {
            override suspend fun roots(): List<StorageRoot> = throw StorageException(StorageError.OFFLINE, "Offline")
        }
        val last = MemoryStorageProvider("last")

        assertEquals(listOf(first.root, last.root), ProviderRegistry(listOf(first, unavailable, last)).roots().map { it.ref })
    }
}
