package com.lunaexplorer.app.storage.transfer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TransferConnectionsTest {
    @Test fun `idle sessions close without another provider operation`() = runBlocking {
        val connector = FakeTransferConnector()
        val closed = CountDownLatch(1)
        connector.sessionClosed = closed
        val account = TransferAccount(id = "test", name = "Server", host = "test.local")
        val connections = TransferConnections(connector, { TransferCredentials(password = "secret") }, true, idleMillis = 20)
        connections.acquire(account, currentCoroutineContext()).close()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        connections.acquire(account, currentCoroutineContext()).use { assertTrue(it.stat("")!!.directory) }
        assertEquals(2, connector.sessionsOpened.get())
        connections.disconnect(account.id)
        assertEquals(2, connector.sessionsClosed.get())
    }
}
