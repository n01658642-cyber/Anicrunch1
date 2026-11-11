package com.anilili.data

import com.anilili.data.model.EpisodeItem
import com.anilili.ui.detail.markDubbedEpisodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DubEpisodesTest {
    @Test
    fun `the published file round-trips through the overlay format`() {
        val parsed = parseDubEpisodes("""{"v":1,"generated":"2026-09-25","titles":{"21":1155,"154587":28}}""")
        assertEquals(mapOf(21 to 1155, 154587 to 28), parsed)
        assertEquals(parsed, parseDubEpisodes(dubEpisodesJson(parsed)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive episode refuses the whole file`() {
        parseDubEpisodes("""{"titles":{"21":0}}""")
    }

    @Test
    fun `episodes up to the latest dub are marked, the rest left alone`() {
        val rows = (1..4).map { EpisodeItem(pipeId = "p-$it", number = it.toDouble(), title = null, image = null, filler = false) }
        assertEquals(listOf(true, true, false, false), markDubbedEpisodes(rows, 2).map { it.dubbed })
        assertSame(rows, markDubbedEpisodes(rows, null))
    }
}
