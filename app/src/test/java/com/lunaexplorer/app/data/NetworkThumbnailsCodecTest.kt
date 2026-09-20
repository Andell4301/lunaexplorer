package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.BrowserState
import com.lunaexplorer.app.model.NetworkThumbnails
import com.lunaexplorer.app.model.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkThumbnailsCodecTest {
    @Test fun `finite limits reject overflow and the largest valid limit survives saving`() {
        assertNull(NetworkThumbnails.forMegabytes(0))
        assertNull(NetworkThumbnails.forMegabytes(-1))
        assertNull(NetworkThumbnails.forMegabytes(NetworkThumbnails.MAX_MEGABYTES + 1))
        assertNull(NetworkThumbnails.forMegabytes(Long.MAX_VALUE))
        val limit = requireNotNull(NetworkThumbnails.forMegabytes(NetworkThumbnails.MAX_MEGABYTES))
        assertEquals(Long.MAX_VALUE - ((1L shl 20) - 1), limit.bytes)
        val state = BrowserState(preferences = Preferences(networkThumbnails = mapOf("smb" to limit)))
        assertEquals(limit, SessionCodec.decode(SessionCodec.encode(state)).preferences.thumbnailsOn("smb"))
    }
}
