package com.lunaexplorer.app.terminal

internal fun shellInputQuote(value: String): String {
    if (value.none { it.code < 32 || it.code == 127 }) return shellQuote(value)
    // Terminal input interprets control bytes before the shell can parse ordinary quotes.
    return "$'" + buildString {
        value.forEach { character ->
            when {
                character == '\\' || character == '\'' -> { append('\\'); append(character) }
                character.code < 32 || character.code == 127 -> {
                    append('\\'); append(character.code.toString(8).padStart(3, '0'))
                }
                else -> append(character)
            }
        }
    } + "'"
}
