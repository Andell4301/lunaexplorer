package com.lunaexplorer.app.storage

import android.content.Intent
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShortcutsTest {
    private val shortcuts = Shortcuts(ApplicationProvider.getApplicationContext())

    private val awkward = listOf(
        "/storage/emulated/0/Music",
        "/storage/emulated/0/somepath\u200e",          // left-to-right mark
        "/storage/emulated/0/a b c",
        "/storage/emulated/0/trailing space ",
        "/storage/emulated/0/100% done",
        "/storage/emulated/0/tag#1",                    // URI fragment separator
        "/storage/emulated/0/what?",
        "/storage/emulated/0/a&b=c",
        "/storage/emulated/0/日本語",
        "/storage/emulated/0/song 🎵",
        "/storage/emulated/0/semi;colon",
        "/",
    )

    @Test fun `a path survives being parcelled, as a launcher would pass it`() {
        awkward.forEach { path ->
            val parcel = Parcel.obtain()
            try {
                intentFor(path).writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val restored = Intent.CREATOR.createFromParcel(parcel)
                assertEquals("Parcelling changed $path", path, Shortcuts.folderFrom(restored))
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test fun `a path survives being written out as a string, as the system persists it`() {
        awkward.forEach { path ->
            val text = intentFor(path).toUri(Intent.URI_INTENT_SCHEME)
            val restored = Intent.parseUri(text, Intent.URI_INTENT_SCHEME)
            assertEquals("String persistence changed $path", path, Shortcuts.folderFrom(restored))
        }
    }

    @Test fun `an intent that is not a shortcut is not mistaken for one`() {
        assertNull(Shortcuts.folderFrom(null))
        assertNull(Shortcuts.folderFrom(Intent(Intent.ACTION_VIEW)))
        assertNull(Shortcuts.folderFrom(Intent(Shortcuts.ACTION_OPEN_FOLDER)))
    }

    private fun intentFor(path: String): Intent = shortcuts.openFolderIntent(path)
}
