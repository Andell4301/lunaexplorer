package com.lunaexplorer.app.storage

import android.net.Uri
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 31])
class FormatDetailsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `PNG metadata works through both paths and streams on older Android`() {
        // A two-pixel PNG with an eXIf chunk: camera make "Luna", orientation 6 (90 degrees).
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAK2VYSWZJSSoACAAAAAIADwECAAUAAAAmAAAA" +
                "EgEDAAEAAAAGAAAAAAAAAEx1bmEADdIWogAAAA9JREFUeJxj+M/AwPCfAQAH/wH/AX+JpwAAAABJRU5ErkJggg==",
        )
        val file = temporary.newFile("photo.png").apply { writeBytes(png) }
        val entry = Entry(NodeRef("local", "workspace:/photo.png"), file.name,
            directory = false, mimeType = "image/png")
        val reader = FormatDetails(RuntimeEnvironment.getApplication())
        val byPath = reader.read(entry, file.path, null).toMap()
        val byStream = reader.read(entry, null, Uri.fromFile(file)).toMap()

        assertEquals("Luna", byPath["Camera"])
        assertEquals("Rotated 90°", byPath["Orientation"])
        assertEquals(byPath, byStream)
    }
}
