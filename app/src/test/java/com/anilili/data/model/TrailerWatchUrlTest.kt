package com.anilili.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerWatchUrlTest {
    @Test
    fun youtubeAndDailymotionBuildTheirWatchPages() {
        assertEquals("https://www.youtube.com/watch?v=abc123", Trailer(id = "abc123", site = "youtube").watchUrl)
        assertEquals("https://www.dailymotion.com/video/x7z", Trailer(id = "x7z", site = "dailymotion").watchUrl)
    }

    @Test
    fun unknownSiteOrMissingIdHasNoUrl() {
        assertNull(Trailer(id = "abc", site = "vimeo").watchUrl)
        assertNull(Trailer(id = " ", site = "youtube").watchUrl)
        assertNull(Trailer(thumbnail = "https://i.ytimg.com/vi/x/hqdefault.jpg").watchUrl)
    }
}
