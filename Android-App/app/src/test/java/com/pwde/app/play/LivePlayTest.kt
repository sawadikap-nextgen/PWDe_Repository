package com.pwde.app.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PWDe has exactly one overlay — the accessibility service's views — so its behaviour is a pure
 * function of what is actually running: a live session over the real game, or one of PWDe's own
 * screens holding a claim. Nothing here may imply a second overlay being built.
 */
class LivePlayTest {
    @Test
    fun nothingRunningMeansNoOverlay() {
        assertEquals(OverlayMode.OFF, LivePlayState().overlayMode(inApp = false, pwdeInForeground = false))
    }

    @Test
    fun aClaimedScreenInFrontTurnsTheOverlayInApp() {
        assertEquals(OverlayMode.IN_APP, LivePlayState().overlayMode(inApp = true, pwdeInForeground = true))
    }

    /**
     * A composed NavHost survives being backgrounded, so a claim alone is not enough: with PWDe not in
     * front the overlay must come down rather than be left drawn over whatever app the user switched to.
     */
    @Test
    fun aClaimDoesNotShowTheOverlayWhilePwdeIsInTheBackground() {
        assertEquals(OverlayMode.OFF, LivePlayState().overlayMode(inApp = true, pwdeInForeground = false))
    }

    @Test
    fun aRunningSessionTurnsTheOverlayToTheGame() {
        assertEquals(OverlayMode.GAME, LivePlayState(active = true).overlayMode(inApp = false, pwdeInForeground = false))
    }

    /** A session must never end up half in-app while it is pressing the game's buttons. */
    @Test
    fun aRunningSessionBeatsAnInAppClaim() {
        assertEquals(OverlayMode.GAME, LivePlayState(active = true).overlayMode(inApp = true, pwdeInForeground = true))
    }

    /** Claims are a reference count: two screens asking must not cancel each other out. */
    @Test
    fun twoScreensCanHoldTheOverlayAtOnce() {
        val livePlay = LivePlay()
        livePlay.acquireOverlay("nav-host")
        livePlay.acquireOverlay("gabai-step")
        assertEquals(setOf("nav-host", "gabai-step"), livePlay.overlayHolders.value)
        livePlay.releaseOverlay("gabai-step")
        assertEquals(setOf("nav-host"), livePlay.overlayHolders.value)
        livePlay.releaseOverlay("nav-host")
        assertTrue(livePlay.overlayHolders.value.isEmpty())
    }

    /**
     * A session starting or ending must not drop a screen's claim. The session resets the whole
     * [LivePlayState], so the claims deliberately live in their own flow — and a screen showing PWDe
     * is still using the overlay after a game session ends.
     */
    @Test
    fun aSessionLifecycleLeavesAScreensClaimAlone() {
        val livePlay = LivePlay()
        livePlay.acquireOverlay("nav-host")
        livePlay.update { LivePlayState(active = true) }
        livePlay.end()
        assertEquals(setOf("nav-host"), livePlay.overlayHolders.value)
        assertEquals(
            OverlayMode.IN_APP,
            livePlay.state.value.overlayMode(inApp = livePlay.overlayHolders.value.isNotEmpty(), pwdeInForeground = true),
        )
    }

    @Test
    fun releasingANameThatNeverClaimedIsHarmless() {
        val livePlay = LivePlay()
        livePlay.releaseOverlay("nobody")
        assertFalse(livePlay.overlayHolders.value.isNotEmpty())
    }
}
