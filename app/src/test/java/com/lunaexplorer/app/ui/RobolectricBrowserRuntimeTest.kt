package com.lunaexplorer.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import com.lunaexplorer.app.LunaApplication
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class, sdk = [35])
class RobolectricBrowserRuntimeTest {
    @Test fun `runtime waits for workers and their completion callbacks before closing the database`() {
        val application = RuntimeEnvironment.getApplication() as LunaApplication
        val runtime = RobolectricBrowserRuntime()
        runtime.prepare(application)
        val workManager = WorkManagerImpl.getInstance(application)
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        RuntimeCleanupWorker.work = {
            started.complete(Unit)
            released.await()
            finished.complete(Unit)
        }
        var closed = false
        try {
            workManager.enqueue(OneTimeWorkRequestBuilder<RuntimeCleanupWorker>().build()).result.get(5, TimeUnit.SECONDS)
            assertTrue("The worker must be running before teardown", await { started.isCompleted })
            Handler(Looper.getMainLooper()).post { released.complete(Unit) }

            runtime.release()
            closed = true

            assertTrue("Teardown must await the worker's suspended work", finished.isCompleted)
            assertFalse(workManager.processor.hasWork())
        } finally {
            released.complete(Unit)
            assertTrue("The worker did not finish", await { finished.isCompleted && !workManager.processor.hasWork() })
            if (!closed) runtime.release()
            RuntimeCleanupWorker.work = {}
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

class RuntimeCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        work()
        return Result.success()
    }

    companion object {
        var work: suspend () -> Unit = {}
    }
}
