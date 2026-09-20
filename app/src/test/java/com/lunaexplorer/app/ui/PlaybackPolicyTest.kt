package com.lunaexplorer.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {

    @Test fun `stopping pauses when background play is off`() {
        assertTrue(pauseOnStop(published = false, pip = PipPhase.NONE, interactive = true, closing = false))
    }

    @Test fun `stopping carries on when background play is on`() {
        assertFalse(pauseOnStop(published = true, pip = PipPhase.NONE, interactive = true, closing = false))
    }

    @Test fun `an activity on its way out pauses`() {
        assertTrue(pauseOnStop(published = true, pip = PipPhase.NONE, interactive = true, closing = true))
    }

    @Test fun `closing the picture in picture window pauses even with background play on`() {
        assertTrue(pauseOnStop(published = true, pip = PipPhase.ACTIVE, interactive = true, closing = false))
    }

    @Test fun `a stop while leaving picture in picture is the window being closed`() {
        assertTrue(pauseOnStop(published = true, pip = PipPhase.LEAVING, interactive = true, closing = false))
        assertTrue(pauseOnStop(published = true, pip = PipPhase.LEAVING, interactive = false, closing = false))
    }

    @Test fun `the screen going off in picture in picture follows background play`() {
        assertFalse(pauseOnStop(published = true, pip = PipPhase.ACTIVE, interactive = false, closing = false))
        assertTrue(pauseOnStop(published = false, pip = PipPhase.ACTIVE, interactive = false, closing = false))
    }

    @Test fun `leaving picture in picture while stopped is a close`() {
        val exit = PipExit()
        assertFalse(exit.modeChanged(inPip = true, started = true, resumed = true))
        assertEquals(PipPhase.ACTIVE, exit.phase)
        assertTrue(exit.modeChanged(inPip = false, started = false, resumed = false))
        assertEquals(PipPhase.LEAVING, exit.phase)
    }

    @Test fun `resuming after leaving is an expansion`() {
        val exit = PipExit()
        exit.modeChanged(inPip = true, started = true, resumed = true)
        assertFalse(exit.modeChanged(inPip = false, started = true, resumed = false))
        assertEquals(PipPhase.LEAVING, exit.phase)
        exit.resumed()
        assertEquals(PipPhase.NONE, exit.phase)
    }

    @Test fun `leaving while already resumed needs no second event`() {
        val exit = PipExit()
        exit.modeChanged(inPip = true, started = true, resumed = true)
        assertFalse(exit.modeChanged(inPip = false, started = true, resumed = true))
        assertEquals(PipPhase.NONE, exit.phase)
    }

    @Test fun `auto enter needs an armed video, playback and no launch in progress`() {
        assertTrue(autoEnterPip(armed = true, playing = true, launching = false))
        assertFalse(autoEnterPip(armed = false, playing = true, launching = false))
        assertFalse(autoEnterPip(armed = true, playing = false, launching = false))
        assertFalse(autoEnterPip(armed = true, playing = true, launching = true))
        assertFalse(autoEnterPip(armed = true, playing = true, launching = false, autoEnter = false))
    }

    @Test fun `aspect follows the video and stays inside what Android accepts`() {
        assertEquals(16 to 9, pipAspect(0, 0))
        assertEquals(16 to 9, pipAspect(1920, 0))
        assertEquals(1920 to 1080, pipAspect(1920, 1080))
        assertEquals(119 to 50, pipAspect(4000, 500))
        assertEquals(50 to 119, pipAspect(500, 4000))
    }

    @Test fun `window actions fit the slots Android gives`() {
        assertEquals(listOf(PipAction.BACK, PipAction.TOGGLE, PipAction.FORWARD), pipActions(seekable = true, slots = 3))
        assertEquals(listOf(PipAction.TOGGLE), pipActions(seekable = false, slots = 3))
        assertEquals(listOf(PipAction.TOGGLE), pipActions(seekable = true, slots = 2))
        assertEquals("A window that takes none is given none", emptyList<PipAction>(), pipActions(seekable = true, slots = 0))
    }

    @Test fun `a pause off screen keeps the service, and coming back stops it`() {
        assertEquals(ServiceAction.START, serviceAction(published = true, onScreen = false, playWhenReady = true, failed = false))
        assertEquals("The notification must outlive a pause, with a Play that works",
            ServiceAction.LEAVE, serviceAction(published = true, onScreen = false, playWhenReady = false, failed = false))
        assertEquals("Nor does a play on a player that is still failed take it down",
            ServiceAction.LEAVE, serviceAction(published = true, onScreen = false, playWhenReady = true, failed = true))
        assertEquals(ServiceAction.STOP, serviceAction(published = true, onScreen = true, playWhenReady = false, failed = false))
        assertEquals("Back on screen, whatever the player is doing",
            ServiceAction.STOP, serviceAction(published = true, onScreen = true, playWhenReady = true, failed = false))
    }

    @Test fun `the service is wanted only off screen with a session and playback asked for`() {
        assertTrue(serviceWanted(published = true, onScreen = false, playWhenReady = true, failed = false))
        assertFalse(serviceWanted(published = false, onScreen = false, playWhenReady = true, failed = false))
        assertFalse(serviceWanted(published = true, onScreen = true, playWhenReady = true, failed = false))
        assertFalse(serviceWanted(published = true, onScreen = false, playWhenReady = false, failed = false))
        assertFalse(serviceWanted(published = true, onScreen = false, playWhenReady = true, failed = true))
    }

    @Test fun `the picture is decoded on screen and not while carrying on unseen`() {
        assertTrue(decodePicture(onScreen = true, carryingOn = true, decoding = false))
        assertFalse(decodePicture(onScreen = false, carryingOn = true, decoding = true))
        // Off screen and about to be paused: leave the tracks alone rather than reselect twice.
        assertTrue(decodePicture(onScreen = false, carryingOn = false, decoding = true))
        assertFalse(decodePicture(onScreen = false, carryingOn = false, decoding = false))
    }
}
