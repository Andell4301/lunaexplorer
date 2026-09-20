package com.lunaexplorer.app.ui

import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.lunaexplorer.app.LunaApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class, sdk = [35])
class BrowserViewModelHarnessTest {
    @Test fun `teardown finishes cancelled writes before closing its database`() {
        val harness = BrowserViewModelHarness()
        val release = CompletableDeferred<Unit>()
        lateinit var job: Job
        lateinit var database: SQLiteDatabase
        var finished = false
        val body = object : Statement() {
            override fun evaluate() {
                database = harness.graph.database.writableDatabase
                val scope = harness.viewModel.viewModelScope
                job = scope.coroutineContext.job
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            release.await()
                            harness.graph.database.saveSession(harness.state)
                            finished = true
                        }
                    }
                }
                Handler(Looper.getMainLooper()).post { release.complete(Unit) }
            }
        }
        try {
            harness.apply(body, Description.createTestDescription(javaClass, "teardown")).evaluate()
            assertTrue("Pending cancellation cleanup must finish before teardown returns", finished)
            assertTrue(job.isCompleted)
            assertFalse("The harness owns its database connection", database.isOpen)
        } finally {
            release.complete(Unit)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!job.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            database.close()
        }
    }
}
