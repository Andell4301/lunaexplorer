package com.lunaexplorer.app.model

import com.lunaexplorer.app.storage.ViewerAdvertising
import kotlinx.serialization.Serializable

enum class SortOrder(val label: String) {
    NATURAL("Natural"),
    NAME("Name"),
    SIZE("Size"),
    MODIFIED("Modified"),
    TYPE("Type"),
    EXTENSION("Extension"),
}

enum class ViewMode(val label: String, val grid: Boolean, val cell: Int) {
    GRID_SMALL("Small grid", true, 84),
    GRID_MEDIUM("Grid", true, 112),
    GRID_LARGE("Large grid", true, 160),
    /** Tile width comes from [Preferences.gridCell]. */
    GRID_CUSTOM("Custom grid", true, 112),
    GALLERY("Gallery", true, 112),
    LIST("List", false, 0),
    COMPACT("Compact", false, 0),
    DETAILED("Detailed", false, 0),
}
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class VideoExitBehavior(val label: String) {
    PICTURE_IN_PICTURE("Picture in picture"),
    BACKGROUND("Background play"),
    OFF("Off"),
}

/** Capped below 100 so a hidden item can never vanish entirely. */
val HIDDEN_FADE_PERCENT = 0..90

enum class SyntaxScheme(val label: String) {
    DARCULA("Darcula"), GITHUB("GitHub"), ONE("One"),
    SOLARIZED("Solarized"), GRUVBOX("Gruvbox"),
    DRACULA("Dracula"), CATPPUCCIN("Catppuccin"), TOKYO_NIGHT("Tokyo Night"), MONOKAI("Monokai"),
}

@Serializable
data class FolderView(val view: ViewMode, val sort: SortOrder, val descending: Boolean)

/** Cap on saved per-folder views; bounds session size and cold-start parsing. */
const val FOLDER_VIEW_LIMIT = 240

enum class Accent(val label: String) {
    SYSTEM("Match system"),
    TEAL("Teal"),
    INDIGO("Indigo"),
    VIOLET("Violet"),
    ROSE("Rose"),
    AMBER("Amber"),
    GRAPHITE("Graphite"),
    CUSTOM("Custom"),
}

// The path identifies the root so renaming it preserves external grants; a blank name uses the folder name.
@Serializable
data class ServedFolder(val path: String, val name: String = "")

@Serializable
data class Preferences(
    val view: ViewMode = ViewMode.GRID_SMALL,
    val sort: SortOrder = SortOrder.NATURAL,
    val descending: Boolean = false,
    val sections: Boolean = false,
    val showHidden: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val thumbnails: Boolean = true,
    val networkThumbnails: Map<String, NetworkThumbnails> = emptyMap(),
    val showDeviceRoot: Boolean = false,
    val shizuku: Boolean = false,
    val showAppData: Boolean = false,
    val accent: Accent = Accent.SYSTEM,
    val accentSeed: Int = 0xFF2F6F66.toInt(),
    val confirmDelete: Boolean = true,
    val recycleBin: Boolean = true,
    /** Applies even when [confirmDelete] is off. */
    val warnLargeDelete: Boolean = false,
    val largeDeleteGb: Int = 10,
    /** 0.7 to 1.6; applied as a density multiplier. */
    val uiScale: Float = 1f,
    val colorfulIcons: Boolean = true,
    /** How far hidden items fade when shown, in percent; 0 leaves them as they are. */
    val hiddenIconFade: Int = 65,
    val hiddenTextFade: Int = 30,
    val syntaxScheme: SyntaxScheme = SyntaxScheme.DARCULA,
    val introSeen: Boolean = false,
    /** Extra extensions per media category, keyed by MediaCategory name. */
    val categoryExtras: Map<String, Set<String>> = emptyMap(),
    val ignoreDotFiles: Boolean = false,
    val includeNomedia: Boolean = false,
    /** Launch screen; reaching it clears the screen back stack. */
    val startScreen: Screen = Screen.HOME,
    /** Launch folder. Blank means [startScreen] is used. */
    val startPath: String = "",
    /** Tile width in dp for [ViewMode.GRID_CUSTOM]. */
    val gridCell: Int = 0,
    val dropAction: DropAction = DropAction.ASK,
    val versionedDelete: VersionedDelete = VersionedDelete.ASK,
    val subtitleAppearance: SubtitleAppearance = SubtitleAppearance(),
    val playerDoubleTap: Boolean = true,
    val playerSwipeSeek: Boolean = true,
    val playerVolumeGesture: Boolean = true,
    val playerBrightnessGesture: Boolean = true,
    val videoExitBehavior: VideoExitBehavior = VideoExitBehavior.PICTURE_IN_PICTURE,
    val audioBackground: Boolean = true,
    val renameIncludesExtension: Boolean = false,
    /** [ViewerAdvertising] kind keys. */
    val openWithLuna: Set<String> = ViewerAdvertising.allKeys,
    val documentsProvider: Boolean = false,
    val servedFolders: List<ServedFolder> = emptyList(),
) {
    fun thumbnailsOn(provider: String): NetworkThumbnails = networkThumbnails.on(provider)

    fun inFolder(own: FolderView?): Preferences =
        if (own == null) this else copy(view = own.view, sort = own.sort, descending = own.descending)
}

/** Not persisted. Null falls back to the value in [Preferences]. */
data class SessionChoices(
    val confirmDelete: Boolean? = null,
    val recycleBin: Boolean? = null,
    val dropAction: DropAction? = null,
    val versionedDelete: VersionedDelete? = null,
)

enum class DropAction(val label: String) { ASK("Ask every time"), COPY("Always copy"), MOVE("Always move") }

enum class VersionedDelete(val label: String) { ASK("Ask every time"), HIDE("Hide, keeping old versions"), PURGE("Delete every version") }
