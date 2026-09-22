package com.lunaexplorer.app.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import org.slf4j.spi.SLF4JServiceProvider
import java.io.IOException
import java.net.SocketTimeoutException

class LunaSlf4jProviderTest {
    @get:Rule val temporary = TemporaryFolder()
    private var previous: DebugLog? = null
    private lateinit var log: DebugLog
    private val logger by lazy {
        LunaSlf4jProvider().apply { initialize() }.loggerFactory.getLogger("com.hierynomus.smbj.session.Session")
    }

    @Before fun install() {
        previous = DebugLog.installed
        log = DebugLog(temporary.root)
        DebugLog.installed = log
    }

    @After fun restore() { log.close(); DebugLog.installed = previous }

    @Test fun `nothing is formatted or kept while the log is not recording`() {
        assertFalse(logger.isDebugEnabled)
        logger.info("Successfully connected to: {}", "nas.local")

        assertEquals("", log.snapshot().text)
    }

    @Test fun `a library line arrives formatted, under the name of the class that wrote it`() {
        log.setRecording(true)
        logger.debug("Returning cached Share {} for {}", "DiskShare[media]", "media")

        assertTrue(log.snapshot().text.contains(" D Session ["))
        assertTrue(log.snapshot().text.contains("Returning cached Share DiskShare[media] for media"))
    }

    @Test fun `an exception passed last is kept as the error, and trace never is`() {
        log.setRecording(true)
        logger.warn("{} close failed for {}", "File", "films/one.mkv", IOException("Connection reset"))
        logger.info("PacketReader error, got exception.", SocketTimeoutException("Read timed out"))
        logger.trace("every byte")

        val text = log.snapshot().text
        assertTrue(text.contains(" W Session [") && text.contains("File close failed for films/one.mkv"))
        assertTrue(text.contains("java.io.IOException: Connection reset"))
        assertTrue(text.contains("java.net.SocketTimeoutException: Read timed out"))
        assertFalse(text.contains("every byte"))
    }

    @Test fun `the loggers that write a line per packet are kept to info and above`() {
        log.setRecording(true)
        val loggers = LunaSlf4jProvider().loggerFactory
        val reader = loggers.getLogger("com.hierynomus.smbj.transport.tcp.direct.DirectTcpPacketReader")
        val promise = loggers.getLogger("com.hierynomus.protocol.commons.concurrent.Promise")

        assertFalse(reader.isDebugEnabled)
        reader.debug("Received packet {}", "SMB2_READ with message id << 7 >>")
        promise.debug("Setting << {} >> to `{}`", "7", "SMB2_READ")
        reader.info("PacketReader error, got exception.", SocketTimeoutException("Read timed out"))

        val text = log.snapshot().text
        assertFalse(text, text.contains("Received packet"))
        assertFalse(text, text.contains("Setting <<"))
        assertTrue(text, text.contains("I DirectTcpPacketReader [") && text.contains("PacketReader error, got exception."))
    }

    @Test fun `SLF4J finds the provider by the name the app gives it`() {
        val before = System.getProperty("slf4j.provider")
        try {
            System.setProperty("slf4j.provider", LunaSlf4jProvider::class.java.name)
            // Called by reflection because LoggerFactory caches the first provider bound in this process.
            val find = LoggerFactory::class.java.getDeclaredMethod("loadExplicitlySpecified", ClassLoader::class.java)
            find.isAccessible = true
            val found = find.invoke(null, LoggerFactory::class.java.classLoader) as SLF4JServiceProvider?

            assertTrue("Found ${found?.javaClass}", found is LunaSlf4jProvider)
        } finally {
            if (before == null) System.clearProperty("slf4j.provider") else System.setProperty("slf4j.provider", before)
        }
    }

    @Test fun `SSH packet and key parser errors cannot write credentials to the log`() {
        log.setRecording(true)
        val loggers = LunaSlf4jProvider().loggerFactory
        loggers.getLogger("net.schmizz.sshj.transport.Decoder").error("Malformed packet: {}", "password secret")
        loggers.getLogger("com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile")
            .warn("Cannot parse private key", IOException("private key secret"))
        logger.info("A storage operation failed")

        val text = log.snapshot().text
        assertFalse(text.contains("password secret"))
        assertFalse(text.contains("private key secret"))
        assertTrue(text.contains("A storage operation failed"))
    }
}
