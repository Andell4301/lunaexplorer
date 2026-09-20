package com.lunaexplorer.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class ManifestPermissionsTest {
    @Test fun `installed manifest requests network, usage access and package deletion`() {
        val context = ApplicationProvider.getApplicationContext<LunaApplication>()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        val required = setOf(Manifest.permission.INTERNET, Manifest.permission.PACKAGE_USAGE_STATS,
            Manifest.permission.REQUEST_DELETE_PACKAGES)

        assertTrue("Missing permissions: ${required - requested}", requested.containsAll(required))
    }
}
