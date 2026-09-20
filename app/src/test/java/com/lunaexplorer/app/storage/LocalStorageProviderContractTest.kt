package com.lunaexplorer.app.storage

import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.StorageProviderContract
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LocalStorageProviderContractTest : StorageProviderContract() {
    @get:Rule val temporary = TemporaryFolder()

    override val provider: LocalStorageProvider by lazy {
        LocalStorageProvider(listOf(LocalRoot("workspace", "workspace", temporary.root)), probe = PathProbe.OF_FILESYSTEM)
    }
    private var counter = 0

    override suspend fun freshRoot(): NodeRef =
        provider.create(provider.root("workspace"), "root${counter++}", directory = true).ref

    @Test fun `a reference built from a path equals the one a listing gives`() = runBlocking {
        val root = freshRoot()
        val created = provider.create(root, "walked.txt", directory = false)
        val listed = provider.list(root).toList().flatten().single { it.name == "walked.txt" }.ref
        val walked = provider.referenceTo(requireNotNull(provider.absolutePath(listed)))
        assertEquals(listed, walked)
        assertEquals(created.ref, walked)
    }
}
