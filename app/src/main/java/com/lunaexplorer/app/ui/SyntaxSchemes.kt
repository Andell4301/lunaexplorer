package com.lunaexplorer.app.ui

import androidx.compose.runtime.compositionLocalOf
import com.lunaexplorer.app.model.SyntaxScheme
import dev.snipme.highlights.model.SyntaxTheme

internal val LocalSyntaxScheme = compositionLocalOf { SyntaxScheme.DARCULA }

/**
 * Each scheme's own hues, with lightness moved until every token reads on Luna's surfaces, which are
 * not the backgrounds these palettes were drawn for: 4.5:1 for code, 3:1 for comments.
 */
internal fun SyntaxScheme.theme(dark: Boolean): SyntaxTheme = when (this) {
    SyntaxScheme.DARCULA -> if (dark) tokens(0xCC7832, 0x6B895A, 0x6897BB, 0x909090, 0xBBB529, 0xCC7832, mark = 0xEDEDED)
        else tokens(0xA36028, 0x5E784F, 0x457599, 0x8D8D8D, 0x76721A, 0xA36028, mark = 0x121212)
    SyntaxScheme.GITHUB -> if (dark) tokens(0xFF7B72, 0xA5D6FF, 0x79C0FF, 0x8B949E, 0xD2A8FF, 0xC9D1D9)
        else tokens(0xCF222E, 0x0A3069, 0x0550AE, 0x6E7781, 0x8250DF, 0x24292F)
    SyntaxScheme.ONE -> if (dark) tokens(0xC678DD, 0x98C379, 0xD19A66, 0x5E6573, 0x61AFEF, 0xABB2BF)
        else tokens(0xA626A4, 0x3E7D3D, 0x956601, 0x8C8D95, 0x2867F0, 0x383A42)
    SyntaxScheme.SOLARIZED -> if (dark) tokens(0x859900, 0x2AA198, 0xD84B8F, 0x586E75, 0xDD5218, 0x839496)
        else tokens(0x687800, 0x217D76, 0xCD2D7A, 0x809090, 0xC44815, 0x5F747C)
    SyntaxScheme.GRUVBOX -> if (dark) tokens(0xFB4934, 0xB8BB26, 0xD3869B, 0x928374, 0x8EC07C, 0xEBDBB2)
        else tokens(0x9D0006, 0x77720E, 0x8F3F71, 0x928374, 0x427B58, 0x3C3836)
    SyntaxScheme.DRACULA -> if (dark) tokens(0xFF79C6, 0xF1FA8C, 0xBD93F9, 0x6272A4, 0x50FA7B, 0xF8F8F2)
        else tokens(0xA3144D, 0x846E15, 0x644AC9, 0x6C664B, 0x14710A, 0x1F1F1F)
    SyntaxScheme.CATPPUCCIN -> if (dark) tokens(0xCBA6F7, 0xA6E3A1, 0xFAB387, 0x6C7086, 0x89B4FA, 0x9399B2)
        else tokens(0x8839EF, 0x338022, 0xC34801, 0x888DA0, 0x1E66F5, 0x6B6E82)
    SyntaxScheme.TOKYO_NIGHT -> if (dark) tokens(0xBB9AF7, 0x9ECE6A, 0xFF9E64, 0x59628E, 0x7AA2F7, 0x89DDFF)
        else tokens(0x8E44F0, 0x587539, 0xAE5B00, 0x848CB5, 0x176BDE, 0x006A83)
    SyntaxScheme.MONOKAI -> if (dark) tokens(0xF92672, 0xE6DB74, 0xAE81FF, 0x75715E, 0xA6E22E, 0xF8F8F2)
        else tokens(0xDE0654, 0x7B7116, 0x8541FF, 0x75715E, 0x577A11, 0x272822)
}

private fun tokens(
    keyword: Int, string: Int, literal: Int, comment: Int, metadata: Int, punctuation: Int, mark: Int = punctuation,
) = SyntaxTheme(
    key = "luna", code = mark, keyword = keyword, string = string, literal = literal, comment = comment,
    metadata = metadata, multilineComment = comment, punctuation = punctuation, mark = mark,
)
