package com.anilili.ui.watch

import androidx.media3.common.TrackSelectionParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQualitySelectionTest {
    @Test
    fun `default quality is an adaptive height ceiling`() {
        val original = TrackSelectionParameters.Builder()
            .setMaxVideoSize(1280, 720)
            .setViewportSize(720, 1600, true)
            .build()

        val capped = adaptiveVideoHeightCeiling(original, 1080)

        assertEquals(Int.MAX_VALUE, capped.maxVideoWidth)
        assertEquals(1080, capped.maxVideoHeight)
        assertEquals(720, capped.viewportWidth)
        assertEquals(1600, capped.viewportHeight)
        assertTrue(capped.overrides.isEmpty())
    }
}
