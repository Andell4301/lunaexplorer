package com.lunaexplorer.app.ui

import java.io.File
import java.util.Base64
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

internal object TestArchives {
    private val TAR_MEMBERS = listOf("docs/readme.txt" to "top level text", "notes.txt" to "second file")

    fun writeTar(file: File) {
        TarArchiveOutputStream(file.outputStream()).use { tar ->
            for ((path, text) in TAR_MEMBERS) {
                val bytes = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }

    /**
     * 7z 26.02: `7z a -pSecret1 -mhe=on -mx=1`, so headers are encrypted as well as content.
     * Holds docs/nested/notes.txt and readme.txt.
     */
    val HEADER_ENCRYPTED_7Z: ByteArray get() = Base64.getDecoder().decode(
        """
        N3q8ryccAAQLxhE0cAEAAAAAAAA/AAAAAAAAAOc733VI3IRfphTll/Xcdg3lzVVZ33IOF7l5EaJNXxylBD4XUtn7pPWDnpIg
        JStDtS0bXhxWxkRdWy00np6SQvnw9AAI518UNsDJXRB+6QImGTGz2DR/wP/Er02oy1hLQJ++uZZId0IQFmHZ0mCJGfv8vzCC
        BSSvKgPHBrJlAoNXRDIA19dcaVLm/xWpFOSUYUJL1dieXFohqEqHw1b1qKr0yOAfHacP4d2XvaA6ZPgUH/V7bUus2fwAP+dP
        AmL20Gy2AFoly91Er6yUJNzYw9tkqu5sttzQ+Cs30G9Lcn8d//P49vtGSKO/+a9843vUp2oRc6cbONauSkTGxwlg7eXx0Pks
        ggZ/EYenEB1wrkQtiIc1r3R1vFmQA/FybbkfCzGvRsK499OJnD68VWSxY09soKcXxCxf4XIVo5Z2loNScbGeARq1Ks6aJKqt
        5O2NB59rq+CtBgoQPvoC/H0uyvl8hzgpQwer6CwtQJ+e9ZqRInU/ERcGgLABCYDAAAcLAQACJAbxBwESUw8bCPvO2l4l1+rC
        BzHkIOy/IwMBAQVdABAAAAEADIC5gSoKAenrUOwAAA==
        """.filterNot { it.isWhitespace() },
    )
    const val HEADER_ENCRYPTED_7Z_PASSWORD = "Secret1"
}
