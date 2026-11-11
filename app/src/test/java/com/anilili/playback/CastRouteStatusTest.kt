package com.anilili.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastRouteStatusTest {

    /**
     * The state a refused cast actually leaves behind, measured from the report that produced this
     * code: the receiver was handed the episode, went BUFFERING, and dropped back to IDLE 896ms
     * later with the queue still wanting to play. Nothing in the app was watching for it.
     */
    @Test
    fun `a receiver idle with the queue still wanting to play has given up`() {
        assertTrue(
            shouldGiveUpOnCastLoad(
                isRemoteRoute = true,
                playbackState = Player.STATE_IDLE,
                playWhenReady = true,
            ),
        )
    }

    /**
     * The one that would do damage. A receiver still fetching reports BUFFERING, the player
     * screen's twenty-second stall failover already covers it, and ending a session the viewer
     * chose because it was merely slow is worse than the bug this replaces.
     */
    @Test
    fun `a receiver still buffering is not given up on`() {
        assertFalse(
            shouldGiveUpOnCastLoad(
                isRemoteRoute = true,
                playbackState = Player.STATE_BUFFERING,
                playWhenReady = true,
            ),
        )
    }

    /** Paused on the television is a choice, and IDLE while paused is an idle queue, not a fault. */
    @Test
    fun `a receiver that was not asked to play is not given up on`() {
        assertFalse(
            shouldGiveUpOnCastLoad(
                isRemoteRoute = true,
                playbackState = Player.STATE_IDLE,
                playWhenReady = false,
            ),
        )
    }

    /** The local player sits in exactly this state between episodes, and must never be touched. */
    @Test
    fun `the local route is never given up on`() {
        assertFalse(
            shouldGiveUpOnCastLoad(
                isRemoteRoute = false,
                playbackState = Player.STATE_IDLE,
                playWhenReady = true,
            ),
        )
    }
}
