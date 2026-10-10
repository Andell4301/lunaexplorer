package com.lunaexplorer.app.terminal

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

interface TerminalConnection : Closeable {
    val input: InputStream
    val error: InputStream? get() = null
    val output: OutputStream
    val exitStatus: Int?
    fun resize(columns: Int, rows: Int)
}
