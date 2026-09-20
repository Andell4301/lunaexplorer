package com.lunaexplorer.app.ui

import com.lunaexplorer.app.LunaApplication
import com.lunaexplorer.app.storage.smb.FakeSmbConnector
import com.lunaexplorer.app.storage.smb.SmbAccount
import com.lunaexplorer.core.Entry
import com.lunaexplorer.core.NodeRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = LunaApplication::class)
class ArchiveOpeningTest {
    private val connector = FakeSmbConnector()
    private val account = SmbAccount(id = "nas", name = "NAS", host = "nas.local", guest = true)

    @get:Rule val harness = BrowserViewModelHarness().withSmb(connector).withSession {
        it.copy(smbAccounts = listOf(account))
    }

    private val viewModel get() = harness.viewModel
    private val state get() = harness.state

    private fun photos(): Entry {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("holiday/one.jpg")); zip.write(ByteArray(2048)); zip.closeEntry()
        }
        connector.share.put("photos.zip", out.toByteArray())
        return runBlocking { harness.graph.smb.stat(NodeRef("smb", "nas:media:photos.zip")) }
    }

    private fun gated(): Pair<CountDownLatch, CountDownLatch> {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        connector.share.readEntered = entered
        connector.share.readGate = gate
        return entered to gate
    }

    @Test fun `an archive being opened is shown until it lands inside`() {
        assertTrue(harness.awaitUntil { state.ready })
        val archive = photos()
        val (entered, gate) = gated()

        viewModel.browseArchive(archive)
        assertTrue("A read is under way", entered.await(5, TimeUnit.SECONDS))
        harness.idle()

        assertEquals("Shown while it reads", "photos.zip", state.openingArchive?.name)
        gate.countDown()
        assertTrue("Lands inside once the directory is read", harness.awaitUntil { state.location?.ref?.provider == "archive" })
        assertNull(state.openingArchive)
    }

    @Test fun `back gives up on an archive still opening, and the browser stays where it was`() {
        assertTrue(harness.awaitUntil { state.ready })
        val archive = photos()
        val where = state.location?.ref
        val message = state.message
        val (entered, gate) = gated()

        viewModel.browseArchive(archive)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        viewModel.back()
        assertNull("Given up on at once", state.openingArchive)

        gate.countDown()
        assertTrue("The read given up on closes what it opened", harness.awaitUntil { connector.share.readersOpen == 0 })
        harness.idle()
        assertEquals(where, state.location?.ref)
        assertNull(state.openingArchive)
        assertEquals("Giving up is not reported as a failure", message, state.message)
    }

    @Test fun `an archive opening in one tab is given up on when another tab is opened`() {
        assertTrue(harness.awaitUntil { state.ready })
        val archive = photos()
        val (entered, gate) = gated()

        viewModel.browseArchive(archive)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        viewModel.newTab()
        assertNull("Given up on as the other tab opens", state.openingArchive)

        gate.countDown()
        assertTrue(harness.awaitUntil { connector.share.readersOpen == 0 })
        harness.idle()
        assertTrue("Nothing landed in either tab", state.tabs.none { it.location?.ref?.provider == "archive" })
    }
}
