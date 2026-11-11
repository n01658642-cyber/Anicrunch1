package com.anilili.ui.watch

import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPlayerPreferencePolicyTest {

    @Test
    fun `a direct stream can be handed to an external player`() {
        assertTrue(hasExternalPlayerStream("bonk", sources(stream("video"))))
    }

    @Test
    fun `an embed page is skipped because it is not a video URL`() {
        assertFalse(hasExternalPlayerStream("bonk", sources(stream("embed"))))
    }

    @Test
    fun `a provider with both kinds still qualifies through its direct stream`() {
        assertTrue(
            hasExternalPlayerStream(
                "kiwi",
                sources(stream("embed"), stream("hls")),
            ),
        )
    }

    private fun sources(vararg streams: StreamItem) =
        SourcesResult(streams.toList(), emptyList(), null, null)

    private fun stream(type: String) = StreamItem(
        url = if (type == "embed") "https://player.example/embed" else "https://cdn.example/video.m3u8",
        type = type,
        quality = "auto",
        audio = null,
        referer = null,
        isActive = true,
        width = null,
        height = null,
    )
}
