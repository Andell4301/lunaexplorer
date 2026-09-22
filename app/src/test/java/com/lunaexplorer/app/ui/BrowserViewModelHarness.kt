package com.lunaexplorer.app.ui

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.lunaexplorer.app.AppGraph
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.LocalRoot
import com.lunaexplorer.app.storage.PathProbe
import com.lunaexplorer.app.storage.MemoryVaultKeys
import com.lunaexplorer.app.storage.b2.B2Connector
import com.lunaexplorer.app.storage.b2.B2SdkConnector
import com.lunaexplorer.app.storage.shizuku.FakeShizuku
import com.lunaexplorer.app.storage.smb.SmbConnector
import com.lunaexplorer.app.storage.smb.SmbjConnector
import com.lunaexplorer.app.storage.transfer.FtpConnector
import com.lunaexplorer.app.storage.transfer.SshjConnector
import com.lunaexplorer.app.storage.transfer.TransferConnector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.job
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import com.lunaexplorer.app.model.*

/** JUnit rule for Robolectric tests whose @Config names [LunaApplication]. */
class BrowserViewModelHarness : ExternalResource() {
    private val temporary = TemporaryFolder()
    private val viewModels = ViewModelStore()
    private val runtime = RobolectricBrowserRuntime()
    lateinit var application: LunaApplication; private set
    lateinit var graph: AppGraph; private set
    lateinit var viewModel: BrowserViewModel; private set
    lateinit var directory: File; private set

    private var prepare: (File) -> Unit = {}
    private var session: (BrowserState) -> BrowserState = { it }
    private var smb: SmbConnector = SmbjConnector()
    private var b2: B2Connector = B2SdkConnector()
    private var ftp: TransferConnector = FtpConnector()
    private var sftp: TransferConnector = SshjConnector()
    /** Absent, as on a device without it, until a test starts it. */
    val shizuku = FakeShizuku()
    /** The keystore does not exist off a device. */
    val vaultKeys = MemoryVaultKeys()

    /** Populates the start folder before the ViewModel restores its session. */
    fun startingWith(block: (File) -> Unit): BrowserViewModelHarness { prepare = block; return this }

    fun withSmb(connector: SmbConnector): BrowserViewModelHarness { smb = connector; return this }

    fun withB2(connector: B2Connector): BrowserViewModelHarness { b2 = connector; return this }

    fun withFtp(connector: TransferConnector): BrowserViewModelHarness { ftp = connector; return this }

    fun withSftp(connector: TransferConnector): BrowserViewModelHarness { sftp = connector; return this }

    /** Configures the persisted session before ViewModel restoration. */
    fun withSession(block: (BrowserState) -> BrowserState): BrowserViewModelHarness { session = block; return this }

    override fun before() {
        temporary.create()
        application = RuntimeEnvironment.getApplication() as LunaApplication
        runtime.prepare(application)

        graph = AppGraph(application, PathProbe.OF_FILESYSTEM, smb, b2, vaultKeys, shizuku, ftp, sftp)
        directory = File(temporary.root, "browsed").apply { mkdirs() }
        prepare(directory)
        graph.additionalRoots = listOf(LocalRoot("harness", "Harness", directory))
        runBlocking {
            val root = graph.local.root("harness")
            val tab = BrowserTab(history = listOf(Location(listOf(Crumb(root, "Harness")))))
            graph.database.saveSession(session(BrowserState(tabs = listOf(tab), activeTabId = tab.id)))
        }
        viewModel = BrowserViewModel(application, graph)
        viewModels.put("browser", viewModel)
        idle()
    }

    override fun after() {
        val job = viewModel.viewModelScope.coroutineContext.job
        try {
            viewModels.clear()
            // Cancelled IO can post cleanup back to Main before the database can close.
            check(awaitUntil(rounds = 2_000) { job.isCompleted }) { "Browser ViewModel did not finish cancellation" }
        } finally {
            try { graph.database.close() }
            finally {
                try { graph.debugLog.close() }
                finally {
                    try { runtime.release() }
                    finally { temporary.delete() }
                }
            }
        }
    }

    fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    fun awaitUntil(rounds: Int = 200, condition: () -> Boolean): Boolean {
        repeat(rounds) { if (condition()) return true; idle(); Thread.sleep(5) }
        return condition()
    }

    val state: BrowserState get() = viewModel.state.value
}
