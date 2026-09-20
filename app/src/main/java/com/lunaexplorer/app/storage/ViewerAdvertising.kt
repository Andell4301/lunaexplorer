package com.lunaexplorer.app.storage

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Each kind is an activity-alias on MainActivity, enabled or disabled from settings. */
object ViewerAdvertising {
    /** [key] is what `Preferences.openWithLuna` stores; [alias] is the manifest alias name. */
    class Kind(val key: String, val label: String, internal val alias: String)

    val kinds: List<Kind> = listOf(
        Kind("images", "Images", "ImagesAlias"),
        Kind("media", "Audio and video", "MediaAlias"),
        Kind("text", "Text and code", "TextAlias"),
        Kind("documents", "Documents", "DocumentsAlias"),
        Kind("archives", "Archives", "ArchivesAlias"),
        Kind("databases", "Databases", "DatabasesAlias"),
    )

    val allKeys: Set<String> = kinds.mapTo(LinkedHashSet()) { it.key }

    // Alias class names use the manifest namespace, not the applicationId.
    fun componentFor(context: Context, kind: Kind): ComponentName =
        ComponentName(context.packageName, "com.lunaexplorer.app.open.${kind.alias}")

    fun apply(context: Context, enabled: Set<String>) {
        val packageManager = context.packageManager
        for (kind in kinds) {
            val component = componentFor(context, kind)
            val wanted = if (kind.key in enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            // setComponentEnabledSetting broadcasts a package change, so skip no-op calls.
            if (packageManager.getComponentEnabledSetting(component) == wanted) continue
            packageManager.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
        }
    }

    data class ViewRequest(val uri: Uri, val type: String?)

    fun viewRequestFrom(intent: Intent?): ViewRequest? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return null
        return ViewRequest(uri, intent.type)
    }
}
