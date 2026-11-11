package com.anilili.data.remote

import com.anilili.data.model.StreamItem
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of an iframe provider's streams the resolver reads as the embed's page. */
class MiruroEmbedPageTest {

    private val resolver = MiruroEmbedResolver(OkHttpClient(), Json)

    private fun stream(url: String, type: String = "") =
        StreamItem(url, type, quality = null, audio = null, referer = null, isActive = true, width = null, height = null)

    /** Cog's shape: four MP4 qualities first, the page last. The page is the one to read. */
    @Test
    fun `the page is found behind the files that lead the list`() {
        val page = stream("https://www.animegg.org/embed/133564", type = "embed")
        val streams = listOf(
            stream("https://www.animegg.org/play/1/video.mp4?for=1"),
            stream("https://www.animegg.org/play/2/video.mp4?for=1"),
            page,
        )
        assertEquals(page, resolver.embedPage(streams))
    }

    @Test
    fun `an untyped page is still found when nothing is typed as an embed`() {
        val page = stream("https://otakuvid.online/embed/abc")
        assertEquals(page, resolver.embedPage(listOf(stream("https://h.example/a.mp4"), page)))
    }

    @Test
    fun `a list of files and manifests has no page to read`() {
        assertNull(
            resolver.embedPage(
                listOf(stream("https://h.example/a.mp4"), stream("https://h.example/master.m3u8")),
            ),
        )
    }
}
