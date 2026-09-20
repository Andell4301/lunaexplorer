package com.lunaexplorer.app.storage.shizuku

import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.app.storage.LocalStorageProvider
import com.lunaexplorer.app.storage.PathProbe
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageProvider
import com.lunaexplorer.core.StorageProviderContract
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Every operation here lands under Android/data, so all of it crosses to the helper and back. */
@RunWith(RobolectricTestRunner::class)
class AssistedLocalProviderContractTest : StorageProviderContract() {
    @get:Rule val temporary = TemporaryFolder()

    private val roots by lazy { listOf(LocalRoot("primary", "Internal storage", temporary.root, RootKind.INTERNAL, followLinks = true)) }
    private val direct by lazy { LocalStorageProvider(roots, probe = PathProbe.OF_FILESYSTEM) }
    private val helper by lazy { HelperFiles("local", FileHelperService()).also { runBlocking { it.setRoots(roots) } } }

    override val provider: StorageProvider by lazy {
        File(temporary.root, "Android/data").mkdirs()
        AssistedLocalProvider(direct, "com.lunaexplorer.app") { helper }
    }
    private var counter = 0

    override suspend fun freshRoot(): NodeRef {
        val data = provider.child(provider.child(direct.root("primary"), "Android")!!.ref, "data")!!.ref
        return provider.create(data, "root${counter++}", directory = true).ref
    }
}
