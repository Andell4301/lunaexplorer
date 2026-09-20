package com.lunaexplorer.app.ui

import androidx.media3.common.Format

/** MergingMediaPeriod prefixes a child index to both group IDs and format IDs. */
internal fun unmergedMediaTrackId(id: String?): String? = id?.replace(Regex("^(?:[0-9]+:)+"), "")

internal data class MediaTrackIdentity(
    val id: String?, val label: String?, val language: String?, val mimeType: String?, val roles: Int,
)

internal fun mediaTrackIdentity(format: Format): MediaTrackIdentity = MediaTrackIdentity(
    unmergedMediaTrackId(format.id), format.label, format.language, format.sampleMimeType, format.roleFlags,
)
