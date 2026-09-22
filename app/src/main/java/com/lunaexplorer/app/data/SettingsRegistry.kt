package com.lunaexplorer.app.data

import com.lunaexplorer.app.model.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

// Import writers must validate values that the UI would otherwise constrain.
object SettingsRegistry {

    val pages: List<String> = listOf(
        "Appearance", "Where Luna opens", "Files and folders", "Media categories",
        "Video player", "Audio player", "Subtitles",
        "Deleting", "Storage access", "Open with Luna", "Document provider", "Network",
        "Bookmarks", "Default apps", "Debug log",
    )

    val units: List<TransferUnit> = buildList {
        add(choice("appearance.theme", "Appearance", "Theme", ThemeMode.entries,
            { it.theme }, { p, v -> p.copy(theme = v) }))
        add(choice("appearance.accent", "Appearance", "Accent", Accent.entries,
            { it.accent }, { p, v -> p.copy(accent = v) }))
        add(int("appearance.accentSeed", "Appearance", "Custom accent colour", Int.MIN_VALUE..Int.MAX_VALUE,
            { it.accentSeed }, { p, v -> p.copy(accentSeed = v) }))
        add(unit("appearance.uiScale", "Appearance", "Interface size",
            read = { JsonPrimitive(it.preferences.uiScale) },
            write = { value, source ->
                val scale = (value.json as? JsonPrimitive)?.floatOrNull
                if (scale == null || scale !in 0.7f..1.6f) refuse("A size from 70% to 160%")
                else applied(source, source.preferences.copy(uiScale = scale))
            }))
        add(flag("appearance.colorfulIcons", "Appearance", "Colorful icons",
            { it.colorfulIcons }, { p, v -> p.copy(colorfulIcons = v) }))
        add(int("appearance.hiddenIconFade", "Appearance", "Hidden item icon fade", HIDDEN_FADE_PERCENT,
            { it.hiddenIconFade }, { p, v -> p.copy(hiddenIconFade = v) }))
        add(int("appearance.hiddenTextFade", "Appearance", "Hidden item text fade", HIDDEN_FADE_PERCENT,
            { it.hiddenTextFade }, { p, v -> p.copy(hiddenTextFade = v) }))
        add(choice("appearance.syntaxScheme", "Appearance", "Code colors", SyntaxScheme.entries,
            { it.syntaxScheme }, { p, v -> p.copy(syntaxScheme = v) }))

        add(choice("start.screen", "Where Luna opens", "Screen", Screen.entries,
            { it.startScreen }, { p, v -> p.copy(startScreen = v) }, claims = setOf("startScreen")))
        add(text("start.path", "Where Luna opens", "Folder",
            { it.startPath }, { p, v -> p.copy(startPath = v) }, claims = setOf("startPath")))

        add(choice("files.view", "Files and folders", "Default layout", ViewMode.entries,
            { it.view }, { p, v -> p.copy(view = v) }))
        add(choice("files.sort", "Files and folders", "Sort by", SortOrder.entries,
            { it.sort }, { p, v -> p.copy(sort = v) }))
        add(flag("files.descending", "Files and folders", "Descending order",
            { it.descending }, { p, v -> p.copy(descending = v) }))
        add(flag("files.sections", "Files and folders", "Sections",
            { it.sections }, { p, v -> p.copy(sections = v) }))
        add(flag("files.showHidden", "Files and folders", "Show hidden files",
            { it.showHidden }, { p, v -> p.copy(showHidden = v) }))
        add(flag("files.thumbnails", "Files and folders", "Thumbnails",
            { it.thumbnails }, { p, v -> p.copy(thumbnails = v) }))
        add(int("files.gridCell", "Files and folders", "Custom tile size", 0..512,
            { it.gridCell }, { p, v -> p.copy(gridCell = v) }))
        add(choice("files.dropAction", "Files and folders", "Drag and drop", DropAction.entries,
            { it.dropAction }, { p, v -> p.copy(dropAction = v) }))
        add(flag("files.renameIncludesExtension", "Files and folders", "Rename includes extension",
            { it.renameIncludesExtension }, { p, v -> p.copy(renameIncludesExtension = v) }))
        add(folderViews())

        add(flag("categories.ignoreDotFiles", "Media categories", "Ignore dot files",
            { it.ignoreDotFiles }, { p, v -> p.copy(ignoreDotFiles = v) }))
        add(flag("categories.includeNomedia", "Media categories", "Include .nomedia folders",
            { it.includeNomedia }, { p, v -> p.copy(includeNomedia = v) }))
        add(categoryExtras())

        add(flag("subtitles.enabled", "Subtitles", "Override subtitle styling",
            { it.subtitleAppearance.enabled }, { p, v -> p.subtitles { it.copy(enabled = v) } }))
        add(int("subtitles.textSizePercent", "Subtitles", "Text size", 50..200,
            { it.subtitleAppearance.textSizePercent }, { p, v -> p.subtitles { it.copy(textSizePercent = v) } }))
        add(choice("subtitles.font", "Subtitles", "Font", SubtitleFont.entries,
            { it.subtitleAppearance.font }, { p, v -> p.subtitles { it.copy(font = v) } }))
        add(flag("subtitles.bold", "Subtitles", "Bold",
            { it.subtitleAppearance.bold }, { p, v -> p.subtitles { it.copy(bold = v) } }))
        add(int("subtitles.textColor", "Subtitles", "Text colour", Int.MIN_VALUE..Int.MAX_VALUE,
            { it.subtitleAppearance.textColor }, { p, v -> p.subtitles { it.copy(textColor = v) } }))
        add(int("subtitles.backgroundColor", "Subtitles", "Background colour", Int.MIN_VALUE..Int.MAX_VALUE,
            { it.subtitleAppearance.backgroundColor }, { p, v -> p.subtitles { it.copy(backgroundColor = v) } }))
        add(int("subtitles.backgroundOpacityPercent", "Subtitles", "Background opacity", 0..100,
            { it.subtitleAppearance.backgroundOpacityPercent },
            { p, v -> p.subtitles { it.copy(backgroundOpacityPercent = v) } }))
        add(choice("subtitles.edge", "Subtitles", "Edge", SubtitleEdge.entries,
            { it.subtitleAppearance.edge }, { p, v -> p.subtitles { it.copy(edge = v) } }))
        add(int("subtitles.edgeColor", "Subtitles", "Edge colour", Int.MIN_VALUE..Int.MAX_VALUE,
            { it.subtitleAppearance.edgeColor }, { p, v -> p.subtitles { it.copy(edgeColor = v) } }))
        add(int("subtitles.outlineThicknessPercent", "Subtitles", "Outline thickness", 50..300,
            { it.subtitleAppearance.outlineThicknessPercent },
            { p, v -> p.subtitles { it.copy(outlineThicknessPercent = v) } }))

        add(flag("player.doubleTap", "Video player", "Double tap controls",
            { it.playerDoubleTap }, { p, v -> p.copy(playerDoubleTap = v) },
            claims = setOf("playerDoubleTap")))
        add(flag("player.swipeSeek", "Video player", "Swipe to seek",
            { it.playerSwipeSeek }, { p, v -> p.copy(playerSwipeSeek = v) },
            claims = setOf("playerSwipeSeek")))
        add(flag("player.volumeGesture", "Video player", "Volume gesture",
            { it.playerVolumeGesture }, { p, v -> p.copy(playerVolumeGesture = v) },
            claims = setOf("playerVolumeGesture")))
        add(flag("player.brightnessGesture", "Video player", "Brightness gesture",
            { it.playerBrightnessGesture }, { p, v -> p.copy(playerBrightnessGesture = v) },
            claims = setOf("playerBrightnessGesture")))
        add(choice("video.exitBehavior", "Video player", "Default exit behavior", VideoExitBehavior.entries,
            { it.videoExitBehavior }, { p, v -> p.copy(videoExitBehavior = v) },
            claims = setOf("videoExitBehavior")))
        add(flag("audio.background", "Audio player", "Background play",
            { it.audioBackground }, { p, v -> p.copy(audioBackground = v) },
            claims = setOf("audioBackground")))
        add(choice("subtitles.position", "Subtitles", "Position", SubtitlePosition.entries,
            { it.subtitleAppearance.position }, { p, v -> p.subtitles { it.copy(position = v) } }))
        add(int("subtitles.verticalMarginPercent", "Subtitles", "Vertical margin", 0..30,
            { it.subtitleAppearance.verticalMarginPercent },
            { p, v -> p.subtitles { it.copy(verticalMarginPercent = v) } }))

        add(flag("deleting.confirmDelete", "Deleting", "Ask before deleting",
            { it.confirmDelete }, { p, v -> p.copy(confirmDelete = v) }))
        add(flag("deleting.recycleBin", "Deleting", "Delete to the recycle bin",
            { it.recycleBin }, { p, v -> p.copy(recycleBin = v) }))
        add(flag("deleting.warnLargeDelete", "Deleting", "Large deletion warning",
            { it.warnLargeDelete }, { p, v -> p.copy(warnLargeDelete = v) }))
        add(int("deleting.largeDeleteGb", "Deleting", "Warn at", 1..Int.MAX_VALUE,
            { it.largeDeleteGb }, { p, v -> p.copy(largeDeleteGb = v) }))

        add(flag("access.showDeviceRoot", "Storage access", "Show Root",
            { it.showDeviceRoot }, { p, v -> p.copy(showDeviceRoot = v) }))
        add(flag("access.shizuku", "Storage access", "Use Shizuku for Android/data and Android/obb",
            { it.shizuku }, { p, v -> p.copy(shizuku = v) }))
        add(flag("access.showAppData", "Storage access", "Show Luna's own data",
            { it.showAppData }, { p, v -> p.copy(showAppData = v) }))

        add(openWithLuna())

        add(flag("documents.enabled", "Document provider", "Serve folders to other apps",
            { it.documentsProvider }, { p, v -> p.copy(documentsProvider = v) },
            claims = setOf("documentsProvider")))
        add(servedFolders())

        add(smbAccounts())
        add(smbPasswords())
        add(networkThumbnails("network.smbThumbnails", "SMB thumbnails", provider = "smb"))
        add(b2Accounts())
        add(transferAccounts())
        add(transferCredentials())
        add(networkThumbnails("network.ftpThumbnails", "FTP thumbnails", provider = "ftp"))
        add(networkThumbnails("network.sftpThumbnails", "SFTP thumbnails", provider = "sftp"))
        add(b2Keys())
        add(networkThumbnails("network.b2Thumbnails", "B2 thumbnails", provider = "b2"))
        add(choice("network.b2Deleting", "Network", "Deleting on B2", VersionedDelete.entries,
            { it.versionedDelete }, { p, v -> p.copy(versionedDelete = v) }, claims = setOf("versionedDelete")))
        add(unit("network.vaultLocked", "Network", "Keep the vault behind authentication",
            read = { JsonPrimitive(it.vaultLocked) },
            write = { value, source ->
                val on = (value.json as? JsonPrimitive)?.booleanOrNull
                // Only the flag is set here. The import applies it with VaultAccess.setLocked, which
                // re-encrypts the vault and refuses without a screen lock.
                if (on == null) refuse("true or false") else applied(source.copy(vaultLocked = on))
            }))

        add(bookmarks("bookmarks.sidebar", "Sidebar bookmarks", BookmarkList.SIDEBAR))
        add(bookmarks("bookmarks.home", "Home bookmarks", BookmarkList.HOME))

        add(openDefaults())

        add(unit("logs.recording", "Debug log", "Record debug log",
            read = { JsonPrimitive(it.recordingLog) },
            write = { value, source ->
                val on = (value.json as? JsonPrimitive)?.booleanOrNull
                if (on == null) refuse("true or false") else applied(source.copy(recordingLog = on))
            }))
    }

    private val byId: Map<String, TransferUnit> = units.associateBy { it.id }
    fun unit(id: String): TransferUnit? = byId[id]

    /** Model fields deliberately not transferred, with the reason. Read by SettingsRegistryCoverageTest. */
    val excluded: Map<String, String> = mapOf(
        "introSeen" to "First-run bookkeeping, not a setting. A reset keeps it too.",
        "subtitleAppearance" to "Carried field by field under Subtitles.",
        "tabs" to "Open tabs are where this device is, not how it is set up.",
        "activeTabId" to "Belongs to tabs.",
        "recent" to "A history, and it rebuilds itself.",
        "preferences" to "The session's wrapper around the fields themselves.",
    )
}

private fun unit(
    id: String,
    page: String,
    label: String,
    sensitive: Boolean = false,
    read: (TransferSource) -> JsonElement?,
    write: (TransferValue, TransferSource) -> TransferWrite,
    items: ((JsonElement, TransferSource) -> List<TransferItem>)? = null,
    claims: Set<String> = emptySet(),
) = TransferUnit(id, page, label, sensitive, read, write, items, claims)

private fun refuse(why: String): TransferWrite = TransferWrite.Refused(why)
private fun applied(source: TransferSource): TransferWrite = TransferWrite.Applied(source)
private fun applied(source: TransferSource, preferences: Preferences): TransferWrite =
    TransferWrite.Applied(source.copy(preferences = preferences))

private fun Preferences.subtitles(change: (SubtitleAppearance) -> SubtitleAppearance): Preferences =
    copy(subtitleAppearance = change(subtitleAppearance))

private fun flag(
    id: String, page: String, label: String,
    get: (Preferences) -> Boolean, set: (Preferences, Boolean) -> Preferences,
    claims: Set<String> = emptySet(),
) = unit(id, page, label, claims = claims,
    read = { JsonPrimitive(get(it.preferences)) },
    write = { value, source ->
        val on = (value.json as? JsonPrimitive)?.booleanOrNull
        if (on == null) refuse("true or false") else applied(source, set(source.preferences, on))
    })

private fun int(
    id: String, page: String, label: String, range: IntRange,
    get: (Preferences) -> Int, set: (Preferences, Int) -> Preferences,
    claims: Set<String> = emptySet(),
) = unit(id, page, label, claims = claims,
    read = { JsonPrimitive(get(it.preferences)) },
    write = { value, source ->
        val number = (value.json as? JsonPrimitive)?.intOrNull
        if (number == null || number !in range) refuse(describe(range))
        else applied(source, set(source.preferences, number))
    })

private fun text(
    id: String, page: String, label: String,
    get: (Preferences) -> String, set: (Preferences, String) -> Preferences,
    claims: Set<String> = emptySet(),
) = unit(id, page, label, claims = claims,
    read = { JsonPrimitive(get(it.preferences)) },
    write = { value, source ->
        val content = (value.json as? JsonPrimitive)?.contentOrNull
        if (content == null) refuse("Text") else applied(source, set(source.preferences, content))
    })

// Invalid byte counts would make the saved session undecodable.
private fun networkThumbnails(id: String, label: String, provider: String) = unit(id, "Network", label,
    claims = setOf("networkThumbnails"),
    read = {
        val limit = it.preferences.thumbnailsOn(provider)
        JsonPrimitive(if (limit == NetworkThumbnails.ANY) "unlimited" else limit.megabytes.toString())
    },
    write = { value, source ->
        val text = (value.json as? JsonPrimitive)?.contentOrNull
        val limit = when {
            text == "unlimited" -> NetworkThumbnails.ANY
            text == "0" -> NetworkThumbnails.OFF
            else -> text?.toLongOrNull()?.let(NetworkThumbnails::forMegabytes)
        }
        if (limit == null) refuse("Whole megabytes from 1 to ${NetworkThumbnails.MAX_MEGABYTES}, 0, or \"unlimited\"")
        else applied(source, source.preferences.copy(networkThumbnails = source.preferences.networkThumbnails + (provider to limit)))
    })

private fun <E : Enum<E>> choice(
    id: String, page: String, label: String, values: List<E>,
    get: (Preferences) -> E, set: (Preferences, E) -> Preferences,
    claims: Set<String> = emptySet(),
) = unit(id, page, label, claims = claims,
    read = { JsonPrimitive(get(it.preferences).name) },
    write = { value, source ->
        val chosen = values.firstOrNull { it.name == (value.json as? JsonPrimitive)?.contentOrNull }
        if (chosen == null) refuse("One of ${values.joinToString(", ") { it.name }}")
        else applied(source, set(source.preferences, chosen))
    })

private fun describe(range: IntRange): String = when {
    range.first == Int.MIN_VALUE -> "A whole number"
    range.last == Int.MAX_VALUE -> "A whole number from ${range.first}"
    else -> "A whole number from ${range.first} to ${range.last}"
}
