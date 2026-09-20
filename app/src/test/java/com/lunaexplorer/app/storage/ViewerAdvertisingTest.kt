package com.lunaexplorer.app.storage

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewerAdvertisingTest {
    private val uri: Uri = Uri.parse("content://other.app.files/docs/notes.txt")

    @Test fun `a view intent yields its data URI and declared type`() {
        val request = ViewerAdvertising.viewRequestFrom(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain"))
        assertEquals(ViewerAdvertising.ViewRequest(uri, "text/plain"), request)
    }

    @Test fun `clip data stands in for missing intent data`() {
        val intent = Intent(Intent.ACTION_VIEW).setType("text/plain").apply { clipData = ClipData.newRawUri("notes", uri) }
        assertEquals(ViewerAdvertising.ViewRequest(uri, "text/plain"), ViewerAdvertising.viewRequestFrom(intent))
    }

    @Test fun `other launches and empty views are not file requests`() {
        assertNull(ViewerAdvertising.viewRequestFrom(null))
        assertNull(ViewerAdvertising.viewRequestFrom(Intent(Intent.ACTION_MAIN)))
        assertNull(ViewerAdvertising.viewRequestFrom(Intent(Intent.ACTION_SEND).setDataAndType(uri, "text/plain")))
        assertNull(ViewerAdvertising.viewRequestFrom(Intent(Intent.ACTION_VIEW).setType("text/plain")))
    }
}
