package com.lunaexplorer.app.storage.smb

import com.hierynomus.mssmb2.SMB2PacketHeader
import com.hierynomus.protocol.transport.PacketFactory
import com.hierynomus.protocol.transport.PacketHandlers
import com.hierynomus.protocol.transport.PacketReceiver
import com.hierynomus.smb.SMBPacket
import com.hierynomus.smb.SMBPacketData
import com.lunaexplorer.core.StorageError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SmbjTimeoutTest {
    private val connector = SmbjConnector()
    private val options = SmbOptions(timeoutSeconds = 1)

    @Test(timeout = 10_000) fun `idle transport survives the request deadline and still receives packets`() {
        val config = connector.configuration(options)
        val failure = AtomicReference<Throwable>()
        val failed = CountDownLatch(1)
        val received = CountDownLatch(1)
        val payload = AtomicReference<ByteArray>()
        val handlers = PacketHandlers<SMBPacketData<*>, SMBPacket<*, *>>(
            null, // No sender: this test only receives.
            object : PacketReceiver<SMBPacketData<*>> {
                override fun handle(packet: SMBPacketData<*>) { received.countDown() }
                override fun handleError(error: Throwable) { failure.set(error); failed.countDown() }
            },
            object : PacketFactory<SMBPacketData<*>> {
                override fun canHandle(data: ByteArray) = true
                override fun read(data: ByteArray): SMBPacketData<*> {
                    payload.set(data)
                    return object : SMBPacketData<SMB2PacketHeader>(SMB2PacketHeader()) {}
                }
            },
        )
        val transport = config.transportLayerFactory.createTransportLayer(handlers, config)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 3_000
            try {
                transport.connect(InetSocketAddress(listener.inetAddress, listener.localPort))
                listener.accept().use { peer ->
                    assertFalse("An idle connection must not hit the one-second request deadline",
                        failed.await(1_500, TimeUnit.MILLISECONDS))
                    peer.getOutputStream().apply { write(byteArrayOf(0, 0, 0, 1, 42)); flush() }
                    assertTrue("The packet reader must still be running", received.await(3, TimeUnit.SECONDS))
                    assertNull(failure.get())
                    assertArrayEquals(byteArrayOf(42), payload.get())
                    assertTrue(transport.isConnected)
                    transport.disconnect()
                }
            } finally { transport.disconnect() }
        }
    }

    @Test(timeout = 10_000) fun `a server that accepts TCP but never answers SMB still times out`() {
        // The socket read timeout is disabled, so the request deadline is the only one left.
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                listener.soTimeout = 3_000
                val result = executor.submit<SmbFailure?> {
                    try {
                        connector.connect(SmbAccount(name = "Silent server", host = requireNotNull(listener.inetAddress.hostAddress),
                            port = listener.localPort, options = options)).close()
                        null
                    } catch (error: SmbFailure) { error }
                }
                listener.accept().use { peer ->
                    peer.soTimeout = 3_000
                    assertTrue("SMB negotiation was actually sent", peer.getInputStream().read() >= 0)
                    val error = result.get(4, TimeUnit.SECONDS) ?: run { fail("Expected an SMB request timeout"); return }
                    assertEquals(error.stackTraceToString(), StorageError.TIMEOUT, error.reason)
                }
            }
        } finally { executor.shutdownNow() }
    }
}
