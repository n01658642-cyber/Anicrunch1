package com.anilili.ui.adaptive

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A card's focus highlight rounds the card by clipping it, so any corner radius it carries is also
 * a bite taken out of whatever the card draws there. Poster cards caption their artwork on the
 * bottom edge, and the bite was landing on the caption's first letter — "TV" arrived as "'V" on
 * every poster in the catalogue. These shapes exist to keep that edge square; the risk is that a
 * later tidy-up "restores" an evenly rounded corner without knowing what it costs.
 */
class CaptionedCardShapeTest {
    private val density = Density(2f)
    private val card = Size(400f, 700f)

    @Test
    fun `captioned cards round the artwork and leave the caption's edge square`() {
        listOf(
            "phone" to CaptionedCardShape,
            "television" to TvSmallCaptionedCardShape,
        ).forEach { (name, shape) ->
            assertEquals("$name bottom start", 0f, shape.bottomStart.toPx(card, density), 0f)
            assertEquals("$name bottom end", 0f, shape.bottomEnd.toPx(card, density), 0f)
            assertTrue("$name top start", shape.topStart.toPx(card, density) > 0f)
            assertTrue("$name top end", shape.topEnd.toPx(card, density) > 0f)
        }
    }

    @Test
    fun `a television keeps the larger radius it reads from across a room`() {
        assertTrue(
            TvSmallCaptionedCardShape.topStart.toPx(card, density) >
                CaptionedCardShape.topStart.toPx(card, density),
        )
    }
}
