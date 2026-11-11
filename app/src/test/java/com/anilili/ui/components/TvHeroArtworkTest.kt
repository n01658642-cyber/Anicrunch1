package com.anilili.ui.components

import com.anilili.data.model.CoverImage
import com.anilili.data.model.Media
import com.anilili.data.model.Trailer
import org.junit.Assert.assertEquals
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHeroArtworkTest {
    @Test
    fun bannerWinsOverTrailerArtwork() {
        val media = Media(
            id = 1,
            bannerImage = "banner",
            trailer = Trailer(thumbnail = "trailer"),
        )

        assertEquals("banner", media.wideHeroArtwork())
    }

    @Test
    fun trailerThumbnailIsNotWideHeroArtwork() {
        // It was treated as one until a featured title with no banner rendered as an
        // unrecognisable close-up: YouTube's hqdefault is a 480x360 frame, so a television hero
        // magnifies it about fourfold and discards three quarters of its height. Returning null
        // sends the title to the poster treatment, which keeps the artwork whole.
        val media = Media(id = 1, trailer = Trailer(thumbnail = "https://i.ytimg.com/vi/x/hqdefault.jpg"))

        assertNull(media.wideHeroArtwork())
    }

    @Test
    fun coverOnlyArtworkUsesPosterTreatment() {
        val media = Media(id = 1, coverImage = CoverImage(extraLarge = "cover"))

        assertNull(media.wideHeroArtwork())
    }

    private val slide = Size(1920f, 570f)

    @Test
    fun backdropIsShownWholeAgainstTheRightEdge() {
        // A 16:9 backdrop at the slide's height: nothing cropped, ending at the right edge and
        // starting about where the text ends (~43% across), with its fade inside the picture.
        val g = heroPictureGeometry(Size(1280f, 720f), slide)

        assertEquals(570f / 720f, g.scale, 1e-4f)
        assertEquals(1920f, g.left + 1280f * g.scale, 0.5f)
        assertEquals(0f, g.top, 0f)
        assertTrue(g.left / slide.width in .45f..0.5f)
        assertEquals(g.left, g.fadeStart, 0f)
    }

    @Test
    fun bannerCoversTheSlideWithItsMiddleOnTheClearArea() {
        // AniList banners are 1900x400: at the slide's height they are wider than the slide, so
        // they cover it, centred on the clear area (67.5% across) and never short of the right.
        val g = heroPictureGeometry(Size(1900f, 400f), slide)
        val width = 1900f * g.scale

        assertEquals(570f / 400f, g.scale, 1e-4f)
        assertTrue(g.left <= 0f)
        assertTrue(g.left + width >= slide.width)
        assertEquals(slide.width * .675f, g.left + width / 2f, 0.5f)
        // The fade then starts where the clear area does, not at the picture's off-screen edge.
        assertEquals(slide.width * .35f, g.fadeStart, 0.5f)
    }

    @Test
    fun squarePictureIsHeldToHalfTheSlideAndLosesItsBottom() {
        val g = heroPictureGeometry(Size(1000f, 1000f), slide)

        assertEquals(slide.width * .5f / 1000f, g.scale, 1e-4f)
        assertEquals(slide.width * .5f, g.left, 0.5f)
        assertTrue(1000f * g.scale > slide.height)
    }
}
