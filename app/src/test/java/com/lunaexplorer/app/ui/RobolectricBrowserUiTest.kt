package com.lunaexplorer.app.ui

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import com.lunaexplorer.app.model.Overlay
import android.os.Handler
import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.lunaexplorer.app.LunaApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.robolectric.shadows.ShadowLog
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Declares no tests, so subclasses do not re-run the smoke suite. */
abstract class RobolectricBrowserUiTest : BrowserUiTest() {
    private val runtime = RobolectricBrowserRuntime()
    override fun prepareRuntime(application: LunaApplication) = runtime.prepare(application)
    override fun releaseRuntime() = runtime.release()
    override fun runtimeDiagnostics() = runtime.diagnostics()

    protected fun openSettingsPage(title: String) {
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BrowserViewModel::class.java] }
        compose.runOnUiThread { viewModel.showOverlay(Overlay.Settings) }
        val page = hasText(title) and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodes(page).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(page).performScrollTo().performClick()
        compose.waitForIdle()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class RobolectricBrowserRuntime {
    private lateinit var application: LunaApplication
    private val executor = TrackedExecutor()
    fun prepare(application: LunaApplication) {
        this.application = application
        // Dispatchers.Main caches its Handler; each Robolectric test gets a fresh main looper.
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher())
        WorkManagerTestInitHelper.initializeTestWorkManager(application,
            Configuration.Builder().setExecutor(executor).setTaskExecutor(executor).build())
    }
    fun release() {
        try {
            val workManager = WorkManagerImpl.getInstance(application)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            // Workers outlive WorkManager's scope, and their completion schedules another database write.
            while (!executor.idle || workManager.processor.hasWork() || !executor.idle) {
                check(System.nanoTime() < deadline) { "WorkManager did not become idle before test teardown" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
        finally { Dispatchers.resetMain() }
    }
    fun diagnostics(): String {
        val work = runCatching { WorkManager.getInstance(application).getWorkInfosByTag("luna-file-operations").get(2, TimeUnit.SECONDS) }.getOrElse { it }
        val logs = ShadowLog.getLogs().takeLast(20).joinToString("\n") { "${it.tag}: ${it.msg} ${it.throwable ?: ""}" }
        return "WorkManager: $work\n$logs"
    }

    private class TrackedExecutor : Executor {
        private val active = AtomicInteger()
        private val delegate = SynchronousExecutor()
        val idle: Boolean get() = active.get() == 0

        override fun execute(command: Runnable) {
            active.incrementAndGet()
            try { delegate.execute(command) }
            finally { active.decrementAndGet() }
        }
    }
}
