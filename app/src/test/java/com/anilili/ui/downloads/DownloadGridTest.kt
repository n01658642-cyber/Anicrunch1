package com.anilili.ui.downloads

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How wide a saved-anime card comes out on the Downloads page.
 *
 * The page is the only poster grid in the app with no compact mode to expand out of, so whatever
 * this returns is the only size a viewer ever sees. The numbers below are the ones My List opens on,
 * because "the same as My List" is the whole point of measuring rather than counting.
 */
class DownloadGridTest {
    @Test
    fun `a phone fits two cards at the width My List draws them`() {
        // 360dp screen, 16dp of page padding each side, 128dp posters.
        assertEquals(2, downloadPosterColumns(available = 328.dp, cardWidth = 128.dp))
    }

    @Test
    fun `a tablet fits five`() {
        // 800dp screen, 24dp of page padding each side, 140dp posters.
        assertEquals(5, downloadPosterColumns(available = 752.dp, cardWidth = 140.dp))
    }

    @Test
    fun `the gutter counts against every card but the last`() {
        // Two 100dp cards and one 12dp gutter is exactly 212dp; a point less and only one fits.
        assertEquals(2, downloadPosterColumns(available = 212.dp, cardWidth = 100.dp))
        assertEquals(1, downloadPosterColumns(available = 211.dp, cardWidth = 100.dp))
    }

    @Test
    fun `a card wider than the page still gets a column`() {
        // The largest interface size on the narrowest phone. One card that overflows a little is a
        // page; zero columns is a crash in `chunked` and an empty screen if it were not.
        assertEquals(1, downloadPosterColumns(available = 90.dp, cardWidth = 128.dp))
    }
}
