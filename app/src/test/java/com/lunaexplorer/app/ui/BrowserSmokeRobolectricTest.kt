package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LunaApplication::class)
class BrowserSmokeRobolectricTest : BrowserSmokeSuite() {
    private val runtime = RobolectricBrowserRuntime()
    override fun prepareRuntime(application: LunaApplication) = runtime.prepare(application)
    override fun releaseRuntime() = runtime.release()
    override fun runtimeDiagnostics() = runtime.diagnostics()
}
