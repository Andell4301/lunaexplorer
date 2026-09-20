package com.lunaexplorer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.lunaexplorer.app.storage.MediaCategory
import com.lunaexplorer.core.RootKind
import com.lunaexplorer.core.StorageRoot

internal val LocalColorfulIcons = compositionLocalOf { false }

internal data class HiddenFade(val icon: Float = 0.35f, val text: Float = 0.7f)

internal val LocalHiddenFade = compositionLocalOf { HiddenFade() }

/**
 * Badge fills. The glyph on a fixed fill is white, so each needs at least 3:1 against white and has
 * to read on both the light and the dark surface. [ACCENT] and [FOLDER] follow the theme instead;
 * [FOLDER] is only a wash of the accent, because folders outnumber everything else in a listing.
 */
internal enum class Hue(val fill: Color?) {
    ACCENT(null),
    FOLDER(null),
    IMAGE(Color(0xFF8E24AA)),
    VIDEO(Color(0xFFE53935)),
    AUDIO(Color(0xFF00897B)),
    PDF(Color(0xFFC2185B)),
    DOCUMENT(Color(0xFF1E88E5)),
    ARCHIVE(Color(0xFF8D6E63)),
    PACKAGE(Color(0xFF558B2F)),
    WORD(Color(0xFF1565C0)),
    SHEET(Color(0xFF2E7D32)),
    SLIDES(Color(0xFFD84315)),
    EBOOK(Color(0xFF00838F)),
    CODE(Color(0xFF3949AB)),
    DATA(Color(0xFF546E7A)),
    SHELL(Color(0xFF37474F)),
    DATABASE(Color(0xFF4527A0)),
    FONT(Color(0xFF827717)),
    CERTIFICATE(Color(0xFFE65100)),
    VAULT(Color(0xFF388E3C)),
    PLAIN(Color(0xFF607D8B)),
    INTERNAL(Color(0xFF0097A7)),
    REMOVABLE(Color(0xFFEF6C00)),
    SYSTEM(Color(0xFF546E7A)),
    APP_DATA(Color(0xFF6D4C41)),
    GRANTED(Color(0xFF0288D1)),
    NETWORK(Color(0xFF5C6BC0)),
    ANALYSIS(Color(0xFFF4511E)),
}

internal fun categoryHue(category: MediaCategory): Hue = when (category) {
    MediaCategory.IMAGES -> Hue.IMAGE
    MediaCategory.VIDEO -> Hue.VIDEO
    MediaCategory.AUDIO -> Hue.AUDIO
    MediaCategory.DOCUMENTS -> Hue.DOCUMENT
    MediaCategory.ARCHIVES -> Hue.ARCHIVE
    MediaCategory.PACKAGES -> Hue.PACKAGE
}

internal fun rootHue(kind: RootKind): Hue = when (kind) {
    RootKind.INTERNAL -> Hue.INTERNAL
    RootKind.SD_CARD, RootKind.USB -> Hue.REMOVABLE
    RootKind.SYSTEM -> Hue.SYSTEM
    RootKind.APP -> Hue.APP_DATA
    RootKind.FOLDER -> Hue.GRANTED
    RootKind.NETWORK -> Hue.NETWORK
}

internal fun rootHue(root: StorageRoot): Hue = rootHue(root.kind)

@Composable
internal fun hueColor(hue: Hue): Color = hue.fill ?: MaterialTheme.colorScheme.primary

private val BADGE_SHAPE = RoundedCornerShape(percent = 30)
private const val GLYPH_RATIO = 0.58f
private const val FOLDER_WASH = 0.14f
private val LABEL_RATIO = floatArrayOf(0.44f, 0.40f, 0.32f, 0.26f)

@Composable
internal fun KindIcon(
    icon: ImageVector,
    hue: Hue,
    description: String?,
    plainSize: Dp,
    badgeSize: Dp,
    plainTint: Color,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    label: String? = null,
    fill: Color? = null,
) {
    if (!LocalColorfulIcons.current) {
        Icon(icon, description, modifier.size(plainSize), tint = plainTint)
        return
    }
    val tonal = hue == Hue.FOLDER
    val paint = fill ?: hueColor(hue).let { if (tonal) it.copy(alpha = FOLDER_WASH) else it }
    val glyph = when {
        fill != null -> if (fill.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White
        tonal -> MaterialTheme.colorScheme.primary
        hue.fill == null -> MaterialTheme.colorScheme.onPrimary
        else -> Color.White
    }
    Box(modifier.size(badgeSize).background(paint.copy(alpha = paint.alpha * if (dimmed) .4f else 1f), BADGE_SHAPE)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center) {
        if (label == null) {
            Icon(icon, null, Modifier.size(badgeSize * GLYPH_RATIO), tint = glyph)
        } else {
            // Sized from the badge, not in sp: the label must fit whatever the system font scale is.
            val size = with(LocalDensity.current) { (badgeSize * LABEL_RATIO[label.length - 1]).toSp() }
            Text(label, color = glyph, fontSize = size, lineHeight = size, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                modifier = Modifier.clearAndSetSemantics {})
        }
    }
}
