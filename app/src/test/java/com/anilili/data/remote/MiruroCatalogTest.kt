package com.anilili.data.remote

import com.anilili.data.model.SourceVariant
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Miruro's catalog API, from the shapes it answered on 2026-10-03 (Frieren, AniList 154587), cut
 * down to what each rule reads.
 */
class MiruroCatalogTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val config = MiruroCatalog.parseConfig(
        json,
        """
        {"streaming":{
          "kickassanime":{"label":"hop","visible":true,"parent":null,"relationship":null},
          "anikoto":{"label":"bee","visible":true,"parent":null,"relationship":null},
          "animepahe":{"label":"kiwi","visible":true,"downloadWorker":"https://worker.example/"},
          "allmanga":{"label":"ally","visible":false},
          "bun":{"label":"bun","visible":true,"parent":"anikoto","relationship":"embed"},
          "nun":{"label":"nun","visible":false,"parent":"allmanga","relationship":"embed"}
        },"providerOrder":["anikoto","kickassanime","animepahe","allmanga","bun","nun"]}
        """.trimIndent(),
    )

    private val play = json.parseToJsonElement(
        """
        {"episode_number":1,"tracks":[
          {"track":"sub","providers":[
            {"provider":"animepahe","subtitles":[],"downloads":[{"url":"https://pahe.example/AbC123"}],
             "servers":[{"server":"animepahe","headers":{"Referer":"https://kwik.cx/"},
               "streams":[{"url":"https://vault.example/a.m3u8","format":"hls","quality":"1080p","embed":{"url":"https://kwik.cx/e/x"}}]}]}]},
          {"track":"ssub","providers":[
            {"provider":"anikoto","subtitles":[{"file":"https://subs.example/en.jpeg","label":"English","language":"en","format":"vtt"}],
             "downloads":[],
             "servers":[
               {"server":"Vidstream-1","headers":{"Referer":"https://megaplay.buzz/"},"embed":{"url":"https://megaplay.buzz/e/1"},
                "streams":[{"url":"https://fetch.example/master.m3u8","format":"hls","quality":null}]},
               {"server":"Vidstream-2","headers":{"Referer":"https://megaplay.buzz/"},"streams":[]}]}]},
          {"track":"dub","providers":[
            {"provider":"kickassanime","subtitles":[],"downloads":[],
             "servers":[{"server":"Vid","headers":{"Referer":"https://krussdomi.com/","Origin":"https://krussdomi.com"},
               "streams":[{"url":"https://hls.example/dub.m3u8","format":"hls","resolution":"1920x1080"}]}]}]}
        ]}
        """.trimIndent(),
    ) as JsonObject

    @Test
    fun `config keeps the site's order and labels`() {
        assertEquals(listOf("bee", "hop", "kiwi", "ally", "bun", "nun"), config.map { it.label })
        val bun = config.single { it.label == "bun" }
        assertTrue(bun.isEmbed)
        assertEquals("anikoto", bun.parent)
    }

    @Test
    fun `a lookup gives the title id and its audio counts`() {
        val title = MiruroCatalog.parseTitle(
            json,
            """{"data":[{"id":"o2Eqmv4w0JYgQJWQFi9CDDdH-PgffNpm","episode_counts":{"raw":28,"sub":28,"dub":12}}]}""",
        )
        assertEquals("o2Eqmv4w0JYgQJWQFi9CDDdH-PgffNpm", title?.id)
        assertEquals(12, title?.dubCount)
        assertNull(MiruroCatalog.parseTitle(json, """{"data":[]}"""))
    }

    @Test
    fun `episodes carry titles, filler and the plain skip window over the mixed one`() {
        val (episodes, next) = MiruroCatalog.parseEpisodes(
            json,
            """
            {"data":[{"episode_number":1,"canon_type":"manga_canon","title":"The Journey's End",
              "thumbnail_url":"https://img/1.jpg","aired_on":"2023-09-29",
              "skip_times":[{"kind":"op","start_seconds":3.2,"end_seconds":93.2},
                            {"kind":"mixed_op","start_seconds":58.7,"end_seconds":148.7},
                            {"kind":"ed","start_seconds":1417.1,"end_seconds":1507.1}]},
             {"episode_number":2,"canon_type":"filler","title":"x","skip_times":[]}],
             "next_cursor":null,"has_more":false}
            """.trimIndent(),
        )
        assertNull(next)
        assertEquals(3.2, episodes[0].skip?.introStart)
        assertEquals(1417.1, episodes[0].skip?.outroStart)
        assertEquals("filler", episodes[1].canonType)
    }

    @Test
    fun `specials in the unfiltered list are not offered as episodes`() {
        // Shape from 2026-10-12, when `kind=regular` started answering 400 and the list came back
        // unfiltered: Frieren's 28 episodes followed by 25 specials numbered from 20001.
        val (episodes, _) = MiruroCatalog.parseEpisodes(
            json,
            """
            {"data":[{"episode_number":27,"kind":"regular","title":"a","skip_times":[]},
             {"episode_number":28,"kind":"regular","title":"b","skip_times":[]},
             {"episode_number":20001,"kind":"special","title":"Magic Episode 1","skip_times":[]},
             {"episode_number":20025,"kind":"special","title":"Completed Screening","skip_times":[]}],
             "next_cursor":null,"has_more":false}
            """.trimIndent(),
        )
        assertEquals(listOf(27.0, 28.0), episodes.map { it.number })
    }

    @Test
    fun `episode ids round-trip and an old pipe id is told apart`() {
        val id = MiruroCatalog.episodeId("o2Eqmv4w0JYgQJWQFi9CDDdH-PgffNpm", 12.0)
        assertEquals("o2Eqmv4w0JYgQJWQFi9CDDdH-PgffNpm" to "12", MiruroCatalog.parseEpisodeId(id))
        assertNull(MiruroCatalog.parseEpisodeId("YW5pbWVwYWhlOmFiYzox"))
    }

    /** A provider is offered in the audio the sampled episode carries it in, and nowhere else. */
    @Test
    fun `providers follow the sample and embeds follow their parent`() {
        val title = MiruroCatalog.Title("t", subCount = 2, dubCount = 1)
        val episodes = listOf(1.0, 2.0).map { MiruroCatalog.Episode(it, null, null, null, null, null) }
        val providers = MiruroCatalog.providers(config, title, episodes, play).associateBy { it.name }

        assertEquals(setOf("bee", "hop", "kiwi", "bun"), providers.keys)
        assertEquals(2, providers.getValue("bee").sub.size)
        assertTrue(providers.getValue("bee").dub.isEmpty())
        assertTrue(providers.getValue("hop").sub.isEmpty())
        assertEquals(1, providers.getValue("hop").dub.size)
        // bun is offered because bee is, with bee's episodes.
        assertEquals(providers.getValue("bee").sub, providers.getValue("bun").sub)
    }

    @Test
    fun `without a sample every visible provider is offered`() {
        val title = MiruroCatalog.Title("t", subCount = null, dubCount = null)
        val episodes = listOf(MiruroCatalog.Episode(1.0, null, null, null, null, null))
        val names = MiruroCatalog.providers(config, title, episodes, sample = null).map { it.name }
        assertEquals(listOf("bee", "hop", "kiwi", "bun"), names)
    }

    @Test
    fun `a native server plays its stream urls with the referer and keeps the rest as headers`() {
        val result = MiruroCatalog.sources(config, "hop", SourceVariant.DUB, play, skip = null)
        val stream = result.streams.single()
        assertEquals("https://hls.example/dub.m3u8", stream.url)
        assertEquals("https://krussdomi.com/", stream.referer)
        assertEquals(mapOf("Origin" to "https://krussdomi.com"), stream.headers)
        assertEquals(1920, stream.width)
        assertTrue(stream.isActive)
    }

    @Test
    fun `a generic sub settles for the selectable track`() {
        val result = MiruroCatalog.sources(config, "bee", SourceVariant.GENERIC_SUB, play, skip = null)
        assertEquals(listOf("https://fetch.example/master.m3u8"), result.streams.map { it.url })
        // Vault hosts serve WebVTT under image names; the response's format says what it is.
        assertEquals("text/vtt", result.subtitles.single().mimeType)
        assertEquals("https://megaplay.buzz/", result.subtitles.single().referer)
    }

    @Test
    fun `an embed child plays its parent's embed pages`() {
        val result = MiruroCatalog.sources(config, "bun", SourceVariant.SOFT_SUB, play, skip = null)
        assertEquals(listOf("https://megaplay.buzz/e/1"), result.streams.map { it.url })
        assertTrue(result.streams.all { it.isEmbed })
    }

    @Test
    fun `a native provider whose servers name no stream offers their embed pages`() {
        // 181444 ep 1 on 2026-10-22: kiwi's three sub servers were embeds alone.
        val embedOnly = json.parseToJsonElement(
            """
            {"tracks":[{"track":"sub","providers":[{"provider":"animepahe","subtitles":[],"downloads":[],"servers":[
              {"server":"animepahe","headers":{"Referer":"https://kwik.cx/"},"embed":{"url":"https://kwik.cx/e/a"},"streams":[]},
              {"server":"animepahe","headers":{"Referer":"https://kwik.cx/"},"embed":{"url":"https://kwik.cx/e/b"},"streams":[]}]}]}]}
            """.trimIndent(),
        ) as JsonObject
        val result = MiruroCatalog.sources(config, "kiwi", SourceVariant.HARD_SUB, embedOnly, skip = null)
        assertEquals(listOf("https://kwik.cx/e/a", "https://kwik.cx/e/b"), result.streams.map { it.url })
        assertTrue(result.streams.all { it.isEmbed })
        assertEquals("https://kwik.cx/", result.streams.first().referer)
        assertEquals(listOf(true, false), result.streams.map { it.isActive })
        // A server with a stream still plays only its streams.
        val direct = MiruroCatalog.sources(config, "bee", SourceVariant.SOFT_SUB, play, skip = null)
        assertEquals(listOf("https://fetch.example/master.m3u8"), direct.streams.map { it.url })
    }

    @Test
    fun `a hard sub asks for the burned-in track only`() {
        assertTrue(MiruroCatalog.sources(config, "bee", SourceVariant.HARD_SUB, play, skip = null).streams.isEmpty())
    }

    @Test
    fun `a download page moves onto the provider's worker`() {
        val result = MiruroCatalog.sources(config, "kiwi", SourceVariant.HARD_SUB, play, skip = null)
        assertEquals("https://worker.example/AbC123", result.download)
        assertEquals("https://vault.example/a.m3u8", result.streams.single().url)
    }

    @Test
    fun `catalog bodies decode with the site's key`() {
        val text = """{"data":[]}"""
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
        val key = "miruro/catalog".toByteArray()
        val masked = ByteArray(gz.size) { (gz[it].toInt() xor key[it % key.size].toInt()).toByte() }
        assertEquals(text, MiruroCatalogApi.decodeCatalogBody(masked))
    }
}
