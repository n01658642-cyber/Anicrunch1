package com.anilili.ui.watch

import androidx.media3.common.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ratio the subtitle canvas is sized by, so a wrong one is caught here rather than as captions
 * shrinking towards nothing on a television.
 */
class ContentAspectRatioTest {
    @Test
    fun widescreenPictureReportsItsShape() {
        assertEquals(16f / 9f, VideoSize(1920, 1080).contentAspectRatio(), 0.0001f)
    }

    @Test
    fun anamorphicPixelsAreFoldedIn() {
        assertEquals(
            16f / 9f,
            VideoSize(1440, 1080, /* pixelWidthHeightRatio= */ 4f / 3f).contentAspectRatio(),
            0.0001f,
        )
    }

    /**
     * Zero is media3's "shape not known", and it is what releases a stale ratio when the item is
     * cleared — so an unknown size must never come back as a number the frame would measure to.
     */
    @Test
    fun unknownSizeIsNotAShape() {
        assertEquals(0f, VideoSize.UNKNOWN.contentAspectRatio(), 0f)
        assertEquals(0f, VideoSize(1920, 0).contentAspectRatio(), 0f)
        assertEquals(0f, VideoSize(0, 1080).contentAspectRatio(), 0f)
    }
}