package com.lunaexplorer.app.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.ViewerAdvertising
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class, shadows = [HeldComponentWrites::class])
class OpenWithLunaTest {
    @get:Rule val harness = BrowserViewModelHarness().withSession {
        it.copy(preferences = it.preferences.copy(openWithLuna = setOf("text")))
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state
    private val packageManager get() = harness.application.packageManager

    private fun component(key: String) =
        ViewerAdvertising.componentFor(harness.application, ViewerAdvertising.kinds.first { it.key == key })

    private fun stateOf(key: String) = packageManager.getComponentEnabledSetting(component(key))

    @Test fun `a restored kind is advertised at start and a switch flips only its own alias`() {
        assertTrue(harness.awaitUntil { state.ready })
        assertTrue("The kind kept from last time is on without a switch being touched",
            harness.awaitUntil { stateOf("text") == PackageManager.COMPONENT_ENABLED_STATE_ENABLED })
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, stateOf("images"))

        viewModel.setPreferences(state.preferences.copy(openWithLuna = setOf("text", "images")))
        assertTrue(harness.awaitUntil { stateOf("images") == PackageManager.COMPONENT_ENABLED_STATE_ENABLED })
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, stateOf("text"))

        viewModel.setPreferences(state.preferences.copy(openWithLuna = setOf("images")))
        assertTrue(harness.awaitUntil { stateOf("text") == PackageManager.COMPONENT_ENABLED_STATE_DISABLED })
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, stateOf("images"))
        assertEquals("Flipping an alias must not restart Luna", PackageManager.DONT_KILL_APP,
            shadowOf(packageManager).getComponentEnabledSettingFlags(component("text")) and PackageManager.DONT_KILL_APP)
    }

    @Test fun `reset turns every kind back on`() {
        assertTrue(harness.awaitUntil { state.ready })
        assertTrue(harness.awaitUntil { stateOf("text") == PackageManager.COMPONENT_ENABLED_STATE_ENABLED })
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, stateOf("images"))

        viewModel.resetSettings()
        assertTrue(harness.awaitUntil {
            ViewerAdvertising.kinds.all { stateOf(it.key) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
        })
        assertEquals(ViewerAdvertising.allKeys, state.preferences.openWithLuna)
    }

    @Test fun `a second flip during the first write still ends at the last preference`() {
        assertTrue(harness.awaitUntil { state.ready })
        assertTrue(harness.awaitUntil { stateOf("images") == PackageManager.COMPONENT_ENABLED_STATE_DISABLED })
        HeldComponentWrites.holdNextWrite()

        viewModel.setPreferences(state.preferences.copy(openWithLuna = setOf("text", "images")))
        assertTrue("Switching images on must reach the package manager",
            HeldComponentWrites.reached.await(10, TimeUnit.SECONDS))
        viewModel.setPreferences(state.preferences.copy(openWithLuna = setOf("text")))
        // Long enough for an unserialized worker to read the state and skip its own write.
        Thread.sleep(300)
        HeldComponentWrites.letTheWriteFinish()

        assertTrue("Images ends off, as the preference saved last asks",
            harness.awaitUntil { stateOf("images") == PackageManager.COMPONENT_ENABLED_STATE_DISABLED })
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, stateOf("text"))
        assertEquals(setOf("text"), state.preferences.openWithLuna)
    }
}

/** Blocks one setComponentEnabledSetting call until released, so a later write can race it. */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class HeldComponentWrites : ShadowApplicationPackageManager() {
    @Implementation
    override fun setComponentEnabledSetting(componentName: ComponentName, newState: Int, flags: Int) {
        if (holding.compareAndSet(true, false)) {
            reached.countDown()
            resume.await(10, TimeUnit.SECONDS)
        }
        super.setComponentEnabledSetting(componentName, newState, flags)
    }

    companion object {
        private val holding = AtomicBoolean(false)
        var reached = CountDownLatch(1); private set
        private var resume = CountDownLatch(1)

        fun holdNextWrite() {
            reached = CountDownLatch(1)
            resume = CountDownLatch(1)
            holding.set(true)
        }

        fun letTheWriteFinish() = resume.countDown()
    }
}
