package com.anilili.playback

import com.anilili.data.settings.PlaybackBufferSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBufferPolicyTest {
    private fun automatic(isTv: Boolean, isLowRamDevice: Boolean, memoryClassMb: Int) =
        playbackBufferPolicy(PlaybackBufferSize.AUTOMATIC, isTv, isLowRamDevice, memoryClassMb)

    @Test
    fun `tv and constrained phones receive a bounded buffer`() {
        assertEquals(30_000, automatic(true, false, 512).maximumMs)
        assertEquals(30_000, automatic(false, true, 512).maximumMs)
        assertEquals(30_000, automatic(false, false, 256).maximumMs)
    }

    @Test
    fun `larger phones retain Media3 defaults`() {
        assertEquals(50_000, automatic(false, false, 384).maximumMs)
    }

    /** Automatic is the shipped behaviour and must stay exactly it, byte ceiling included. */
    @Test
    fun `automatic leaves the byte ceiling to Media3`() {
        assertNull(automatic(true, false, 256).targetBufferBytes)
        assertNull(automatic(false, false, 384).targetBufferBytes)
    }

    @Test
    fun `a chosen size overrides the device policy on a television`() {
        val policy = playbackBufferPolicy(PlaybackBufferSize.VERY_LARGE, true, true, 192)
        assertEquals(240_000, policy.maximumMs)
        // A stall is followed by a long enough pause to be worth having buffered.
        assertEquals(10_000, policy.rebufferMs)
    }

    @Test
    fun `every chosen size is a load control Media3 will accept`() {
        PlaybackBufferSize.entries.forEach { size ->
            val policy = playbackBufferPolicy(
                size,
                isTv = false,
                isLowRamDevice = false,
                memoryClassMb = 256,
            )
            assertTrue(
                "$size start buffer exceeds its minimum",
                policy.playbackMs <= policy.minimumMs && policy.rebufferMs <= policy.minimumMs,
            )
            assertTrue("$size maximum is below its minimum", policy.minimumMs <= policy.maximumMs)
        }
    }

    /**
     * The gap between minimum and maximum is how much playback happens between refills, and each
     * refill of a progressive file is a fresh range request into the middle of it. Twenty seconds
     * of that stalled a television repeatedly; sixty does not.
     */
    @Test
    fun `a progressive file on a constrained device loads further ahead`() {
        val policy = playbackBufferPolicy(
            PlaybackBufferSize.AUTOMATIC,
            isTv = true,
            isLowRamDevice = false,
            memoryClassMb = 256,
            isProgressive = true,
        )
        assertEquals(90_000, policy.maximumMs)
        assertEquals(60_000, policy.maximumMs - policy.minimumMs)
        // The wait for a picture, and the wait after a stall, are deliberately unchanged.
        assertEquals(automatic(true, false, 256).playbackMs, policy.playbackMs)
        assertEquals(automatic(true, false, 256).rebufferMs, policy.rebufferMs)
    }

    /** Ninety seconds of a fat encode is hundreds of megabytes, so this one policy names a ceiling. */
    @Test
    fun `the widened progressive buffer is bounded in bytes`() {
        val policy = playbackBufferPolicy(
            PlaybackBufferSize.AUTOMATIC,
            isTv = true,
            isLowRamDevice = false,
            memoryClassMb = 256,
            isProgressive = true,
        )
        assertEquals(playbackBufferBytes(256), policy.targetBufferBytes)
    }

    /** A phone large enough for Media3's own defaults was never the device that stalled. */
    @Test
    fun `a roomy device is left alone whatever the stream is`() {
        assertEquals(
            automatic(false, false, 384),
            playbackBufferPolicy(
                PlaybackBufferSize.AUTOMATIC,
                isTv = false,
                isLowRamDevice = false,
                memoryClassMb = 384,
                isProgressive = true,
            ),
        )
    }

    /** An explicit choice is the viewer's, and it outranks anything inferred from the stream. */
    @Test
    fun `a chosen size ignores the stream shape`() {
        PlaybackBufferSize.entries.filter { it != PlaybackBufferSize.AUTOMATIC }.forEach { size ->
            assertEquals(
                "$size changed for a progressive stream",
                playbackBufferPolicy(size, isTv = true, isLowRamDevice = false, memoryClassMb = 256),
                playbackBufferPolicy(
                    size,
                    isTv = true,
                    isLowRamDevice = false,
                    memoryClassMb = 256,
                    isProgressive = true,
                ),
            )
        }
    }

    @Test
    fun `a chosen size is capped by the heap it would occupy`() {
        assertEquals(48 * 1024 * 1024, playbackBufferBytes(192))
        assertEquals(64 * 1024 * 1024, playbackBufferBytes(256))
        // Neither a device that reports nothing useful nor a very large heap escapes the bounds.
        assertEquals(16 * 1024 * 1024, playbackBufferBytes(0))
        assertEquals(192 * 1024 * 1024, playbackBufferBytes(4_096))
    }
}
