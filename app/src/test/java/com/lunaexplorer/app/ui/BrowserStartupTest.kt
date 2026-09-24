package com.lunaexplorer.app.ui

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.storage.MemoryVaultKeys
import com.lunaexplorer.app.storage.PathProbe
import com.lunaexplorer.app.storage.Secrets
import com.lunaexplorer.app.storage.VaultKeys
import com.lunaexplorer.app.storage.b2.B2Account
import com.lunaexplorer.app.storage.b2.FakeB2Backend
import com.lunaexplorer.app.storage.b2.FakeB2Connector
import com.lunaexplorer.app.storage.shizuku.FakeShizuku
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class, sdk = [35])
class BrowserStartupTest {
    @Test fun `startup is not ready until the saved network credentials can be used`() {
        val application = RuntimeEnvironment.getApplication() as LunaApplication
        val runtime = RobolectricBrowserRuntime()
        runtime.prepare(application)
        val decrypting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val memoryKeys = MemoryVaultKeys()
        val keys = object : VaultKeys by memoryKeys {
            override fun cipher(mode: Int, locked: Boolean, iv: ByteArray?): Cipher {
                if (mode == Cipher.DECRYPT_MODE) {
                    decrypting.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Vault decryption was not released" }
                }
                return memoryKeys.cipher(mode, locked, iv)
            }
        }
        val account = B2Account(id = "cloud", name = "Cloud", keyId = "key-id", bucket = "media")
        val connector = FakeB2Connector(FakeB2Backend("media").apply { put("media", "remote.txt", "content") })
        val graph = AppGraph(application, PathProbe.OF_FILESYSTEM, b2Connector = connector,
            vaultKeys = keys, shizukuGateway = FakeShizuku())
        graph.vault.save(Secrets(b2Keys = mapOf(account.id to "saved-key")), locked = false)
        graph.vault.close()
        runBlocking { graph.database.saveSession(BrowserState(b2Accounts = listOf(account))) }
        val viewModels = ViewModelStore()
        val viewModel = BrowserViewModel(application, graph)
        viewModels.put("browser", viewModel)
        try {
            assertTrue("Startup must reach vault decryption", await { decrypting.count == 0L })
            assertFalse("The browser cannot navigate network storage until startup opens the vault", viewModel.state.value.ready)

            release.countDown()
            assertTrue("Startup must finish after decryption", await { viewModel.state.value.ready })
            viewModel.navigateRoot(viewModel.state.value.roots.single { it.ref.provider == "b2" })
            assertTrue("The first network navigation must load using the restored key", await {
                val state = viewModel.state.value
                !state.loading && state.entries.any { it.name == "remote.txt" }
            })
            assertEquals(listOf("saved-key"), connector.keysSeen)
        } finally {
            release.countDown()
            val job = viewModel.viewModelScope.coroutineContext.job
            viewModels.clear()
            try { assertTrue("Startup did not finish cancellation", await { job.isCompleted }) }
            finally {
                graph.b2.disconnect(account.id)
                try { graph.procedures.close() }
                finally {
                    try { graph.database.close() }
                    finally {
                        try { graph.debugLog.close() }
                        finally { runtime.release() }
                    }
                }
            }
        }
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return condition()
    }
}
