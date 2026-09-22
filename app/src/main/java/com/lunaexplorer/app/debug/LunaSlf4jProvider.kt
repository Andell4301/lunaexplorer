package com.lunaexplorer.app.debug

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.helpers.NOPLogger
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap

/** SLF4J loads this class by reflection through the `slf4j.provider` property, so R8 must keep its constructor. */
class LunaSlf4jProvider : SLF4JServiceProvider {
    private val loggers = ConcurrentHashMap<String, Logger>()
    private val factory = ILoggerFactory { name ->
        // SSHJ logs raw malformed packets and untrusted server messages, which may contain credentials.
        if (name.startsWith("net.schmizz.") || name.startsWith("com.hierynomus.sshj.")) NOPLogger.NOP_LOGGER
        else loggers.getOrPut(name) { BridgedLogger(name) }
    }
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = factory
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion(): String = "2.0.99"
    override fun initialize() = Unit
}

private class BridgedLogger(private val loggerName: String) : LegacyAbstractLogger() {
    private val tag = loggerName.substringAfterLast('.')
    private val chatty = CHATTY.any { loggerName.startsWith(it) }

    private val recording: Boolean get() = DebugLog.installed?.enabled == true

    override fun getName(): String = loggerName
    override fun isTraceEnabled(): Boolean = false
    override fun isDebugEnabled(): Boolean = recording && !chatty
    override fun isInfoEnabled(): Boolean = recording
    override fun isWarnEnabled(): Boolean = recording
    override fun isErrorEnabled(): Boolean = recording
    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val log = DebugLog.installed ?: return
        if (!log.enabled || level == Level.TRACE || (level == Level.DEBUG && chatty)) return
        @Suppress("UNCHECKED_CAST")
        val message = MessageFormatter.basicArrayFormat(messagePattern, arguments as Array<Any?>?) ?: ""
        val mapped = when (level) {
            Level.ERROR -> DebugLog.Level.ERROR
            Level.WARN -> DebugLog.Level.WARN
            Level.INFO -> DebugLog.Level.INFO
            else -> DebugLog.Level.DEBUG
        }
        log.log(mapped, tag, message, throwable)
    }
}

/** Logger name prefixes whose DEBUG output is per-packet or includes authentication details. */
private val CHATTY = listOf(
    "com.hierynomus.smbj.transport.",
    "com.hierynomus.protocol.commons.concurrent.",
    "com.hierynomus.protocol.commons.buffer.",
    "com.hierynomus.smbj.connection.Connection",
    "com.hierynomus.smbj.connection.packet.",
    "com.hierynomus.smbj.connection.PacketEncryptor",
    "com.hierynomus.smbj.connection.PacketSignatory",
    "com.hierynomus.smbj.share.SMB2Writer",
    "com.hierynomus.smbj.auth.NtlmAuthenticator",
    "com.hierynomus.asn1.",
)
