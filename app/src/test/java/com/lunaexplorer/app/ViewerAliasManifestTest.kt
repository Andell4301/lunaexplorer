package com.lunaexplorer.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lunaexplorer.app.storage.ViewerAdvertising
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class ViewerAliasManifestTest {
    @Test fun `every kind has an exported alias that starts off`() {
        val context = ApplicationProvider.getApplicationContext<LunaApplication>()
        for (kind in ViewerAdvertising.kinds) {
            val info = context.packageManager.getActivityInfo(
                ViewerAdvertising.componentFor(context, kind), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertFalse("${kind.key} must stay off until its switch is on", info.enabled)
            assertTrue("${kind.key} must be reachable by other apps once on", info.exported)
        }
    }

    // Without singleTask an ACTION_VIEW starts a second MainActivity in the sender's task, and its
    // ViewModel writes the saved session as well.
    @Test fun `the aliases hand over to a single activity that still launches normally`() {
        val context = ApplicationProvider.getApplicationContext<LunaApplication>()
        val main = ComponentName(context, MainActivity::class.java)
        assertEquals("A hand-off must reach the running instance through onNewIntent",
            ActivityInfo.LAUNCH_SINGLE_TASK, context.packageManager.getActivityInfo(main, 0).launchMode)
        for (kind in ViewerAdvertising.kinds) {
            val info = context.packageManager.getActivityInfo(
                ViewerAdvertising.componentFor(context, kind), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals("${kind.key} must target the one activity", main.className, info.targetActivity)
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        assertTrue("The launcher entry must still start Luna",
            context.packageManager.queryIntentActivities(launcher, 0).any { it.activityInfo.name == main.className })
    }

    // A lone gzip, xz or bzip2 file cannot be browsed, and extracting it needs a destination folder,
    // so the archive alias does not claim those types.
    @Test fun `the archive alias claims only what an incoming file can be opened with`() {
        val context = ApplicationProvider.getApplicationContext<LunaApplication>()
        fun offeredByLuna(type: String): Boolean = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://other.app.share/docs/1"), type),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        ).any { it.activityInfo.packageName == context.packageName }

        for (type in listOf("application/zip", "application/java-archive", "application/x-tar",
            "application/x-7z-compressed", "application/vnd.rar", "application/vnd.android.package-archive")) {
            assertTrue("$type browses in place or opens the package sheet", offeredByLuna(type))
        }
        for (type in listOf("application/gzip", "application/x-xz", "application/x-bzip2")) {
            assertFalse("$type is a dead end for a file handed over", offeredByLuna(type))
        }
    }

    @Test fun `turning the device is handled without relaunching`() {
        val context = ApplicationProvider.getApplicationContext<LunaApplication>()
        val main = ComponentName(context, MainActivity::class.java)
        val handled = context.packageManager.getActivityInfo(main, 0).configChanges

        // Otherwise rotation recreates the activity and a playing video starts again from the beginning.
        listOf(
            "orientation" to ActivityInfo.CONFIG_ORIENTATION,
            "screen size" to ActivityInfo.CONFIG_SCREEN_SIZE,
            "smallest screen size" to ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
            "screen layout" to ActivityInfo.CONFIG_SCREEN_LAYOUT,
        ).forEach { (what, bit) ->
            assertTrue("A change of $what must be handled in place", handled and bit != 0)
        }
    }
}
