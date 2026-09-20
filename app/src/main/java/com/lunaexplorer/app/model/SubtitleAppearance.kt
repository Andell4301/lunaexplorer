package com.lunaexplorer.app.model

import kotlinx.serialization.Serializable

/** While [enabled] is false the subtitle file's own formatting is used. */
@Serializable
data class SubtitleAppearance(
    val enabled: Boolean = false,
    val textSizePercent: Int = 100,
    val font: SubtitleFont = SubtitleFont.DEFAULT,
    val bold: Boolean = false,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val backgroundColor: Int = 0xFF000000.toInt(),
    val backgroundOpacityPercent: Int = 100,
    val edge: SubtitleEdge = SubtitleEdge.NONE,
    val edgeColor: Int = 0xFF000000.toInt(),
    /** Percent of the outline the player draws by default; 100 is that default. */
    val outlineThicknessPercent: Int = 100,
    val position: SubtitlePosition = SubtitlePosition.FROM_FILE,
    val verticalMarginPercent: Int = 8,
)

enum class SubtitleFont(val label: String) {
    DEFAULT("Default"), SANS_SERIF("Sans serif"), SERIF("Serif"), MONOSPACE("Monospace"),
}

enum class SubtitleEdge(val label: String) {
    NONE("None"), OUTLINE("Outline"), SHADOW("Shadow"),
}

enum class SubtitlePosition(val label: String) {
    FROM_FILE("From file"), BOTTOM("Bottom"), TOP("Top"),
}
