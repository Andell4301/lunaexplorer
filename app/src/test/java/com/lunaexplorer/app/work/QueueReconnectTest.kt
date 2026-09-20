package com.lunaexplorer.app.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lunaexplorer.app.data.LunaDatabase
import com.lunaexplorer.core.NodeRef
import com.lunaexplorer.core.OperationRequest
import com.lunaexplorer.core.OperationType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QueueReconnectTest {
    @Test fun reopeningActivityPreservesLiveWorkButRecoversAbandonedWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "reconnect-${UUID.randomUUID()}.db"
        val database = LunaDatabase(context, name)
        try {
            val request = OperationRequest(type = OperationType.DELETE, sources = listOf(NodeRef("test", "source")))
            database.enqueue(request, "Delete test item")
            database.claimNext()
            val queue = OperationQueue(context, database)
            queue.execution.lock() // A currently executing worker owns this lock.
            try {
                queue.reconnect()
                assertEquals("RUNNING", database.queue.value.single().status)
            } finally { queue.execution.unlock() }
            // No worker owns execution anymore; no queued job exists to trigger WorkManager.
            queue.reconnect()
            assertEquals("INTERRUPTED", database.queue.value.single().status)
            assertNull(database.claimNext())
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
