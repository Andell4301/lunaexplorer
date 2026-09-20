package com.lunaexplorer.app.model

import kotlinx.serialization.Serializable

/** Limits by storage provider id. A provider not named reads [NetworkThumbnails.MB_25]. */
fun Map<String, NetworkThumbnails>.on(provider: String): NetworkThumbnails = this[provider] ?: NetworkThumbnails.MB_25

/** How much network file data one thumbnail may read. Finite budgets are whole megabytes. */
@Serializable
data class NetworkThumbnails(val bytes: Long) {
    init {
        require(bytes == Long.MAX_VALUE || (bytes >= 0 && bytes % MEGABYTE == 0L))
    }

    val megabytes: Long get() = bytes / MEGABYTE
    val label: String get() = when (this) {
        OFF -> "Off"
        ANY -> "Unlimited"
        else -> "$megabytes MB"
    }

    companion object {
        private const val MEGABYTE = 1L shl 20
        const val MAX_MEGABYTES = Long.MAX_VALUE / MEGABYTE
        val OFF = NetworkThumbnails(0)
        val MB_10 = NetworkThumbnails(10 * MEGABYTE)
        val MB_25 = NetworkThumbnails(25 * MEGABYTE)
        val ANY = NetworkThumbnails(Long.MAX_VALUE)

        /** Validate before multiplication to prevent overflow. */
        fun forMegabytes(value: Long): NetworkThumbnails? =
            value.takeIf { it in 1..MAX_MEGABYTES }?.let { NetworkThumbnails(it * MEGABYTE) }
    }
}
