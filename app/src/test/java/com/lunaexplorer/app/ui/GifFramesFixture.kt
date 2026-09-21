package com.lunaexplorer.app.ui

internal object GifFramesFixture {
    val twoFrames = byteArrayOf(
        71, 73, 70, 56, 57, 97, 2, 0, 2, 0, -16, 0, 0, -1, 0, 0, 0, 0, -1, 33, -1, 11, 78, 69, 84,
        83, 67, 65, 80, 69, 50, 46, 48, 3, 1, 0, 0, 0, 33, -7, 4, 0, 50, 0, 0, 0, 44, 0, 0, 0, 0, 2,
        0, 2, 0, 0, 2, 3, 4, -128, 2, 0, 33, -7, 4, 0, 50, 0, 0, 0, 44, 0, 0, 0, 0, 2, 0, 2, 0, 0,
        2, 3, 76, -110, 2, 0, 59,
    )

    fun frames(count: Int): ByteArray {
        val head = twoFrames.copyOfRange(0, 38)
        val frame = twoFrames.copyOfRange(38, 62)
        return (0 until count).fold(head) { bytes, _ -> bytes + frame } + byteArrayOf(59)
    }
}
