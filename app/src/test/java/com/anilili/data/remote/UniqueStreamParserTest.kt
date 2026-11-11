package com.anilili.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UniqueStreamParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesSearchCandidates() {
        val payload = """
            {
              "series": [
                {"content_id":"I35iIyC2","title":"Solo Leveling","type":"show","subbed":true,"dubbed":true},
                {"content_id":"Ab3kL9xQ","title":"Solo Leveling Specials","type":"show","subbed":true,"dubbed":false}
              ],
              "movies": [
                {"content_id":"Mv1Mv1Mv","title":"Solo Leveling: ReAwakening","type":"movie"}
              ]
            }
        """.trimIndent()

        val candidates = UniqueStreamParser.parseSearch(json.parseToJsonElement(payload))

        // Only series are candidates; movies have no seasons and cannot resolve episodes.
        assertEquals(2, candidates.size)
        assertEquals("I35iIyC2", candidates[0].contentId)
        assertEquals("Solo Leveling", candidates[0].title)
        assertEquals(true, candidates[0].subbed)
        assertEquals(true, candidates[0].dubbed)
        assertEquals(false, candidates[1].dubbed)
    }

    @Test
    fun selectsSeasonByMalIdBeforeTitleRanking() {
        val payload = """
            {
              "audio_locales": ["de-DE","en-US","es-419","ja-JP"],
              "seasons": [
                {"content_id":"0DsN4Kb7","title":"Solo Leveling","season_number":1,"episode_count":13,"mal_id":"52299"},
                {"content_id":"Xk2Pq9Lm","title":"Solo Leveling Season 2 -Arise from the Shadow-","season_number":2,"episode_count":13,"mal_id":"58567"},
                {"content_id":"NoMal001","title":"Solo Leveling OVAs","season_number":null,"episode_count":2,"mal_id":null}
              ]
            }
        """.trimIndent()

        val seasons = UniqueStreamParser.parseSeasons(json.parseToJsonElement(payload))

        assertEquals(3, seasons.size)
        assertEquals("52299", seasons[0].malId)
        assertEquals(13, seasons[0].episodeCount)
        assertNull(seasons[2].malId)

        // Season 2's own title scores badly against the plain "Solo Leveling" query, but its
        // MAL id must still win over the better-scoring Season 1 — and it resolves alone.
        val s2 = UniqueStreamParser.selectSeasons(seasons, "58567", listOf("Solo Leveling"))
        assertEquals(listOf("Xk2Pq9Lm"), s2.map { it.contentId })

        val s1 = UniqueStreamParser.selectSeasons(seasons, "52299", listOf("Solo Leveling"))
        assertEquals(listOf("0DsN4Kb7"), s1.map { it.contentId })
    }

    @Test
    fun fallsBackToTitleScoreWhenMalIdIsAbsent() {
        val seasons = listOf(
            UniqueStreamParser.Season("a", "One Punch Man", 1, 12, null),
            UniqueStreamParser.Season("b", "One Punch Man Season 2", 2, 12, null),
        )

        // Parallel seasons restart their numbering, so exactly one of them may be taken.
        val picked = UniqueStreamParser.selectSeasons(seasons, null, listOf("One Punch Man Season 2"))
        assertEquals(listOf("b"), picked.map { it.contentId })

        val base = UniqueStreamParser.selectSeasons(seasons, null, listOf("One Punch Man"))
        assertEquals(listOf("a"), base.map { it.contentId })

        assertEquals(emptyList<String>(), UniqueStreamParser.selectSeasons(emptyList(), "52299", listOf("x")).map { it.contentId })
    }

    @Test
    fun readsSagaSplitSeriesAsOneCatalog() {
        // One Piece's real shape: saga titles, no MAL ids, absolute episode numbering.
        val sagas = listOf(
            UniqueStreamParser.Season("dHTiu8rG", "East Blue (1-61)", 1, 61, null),
            UniqueStreamParser.Season("HxyVVAYQ", "Alabasta (62-135)", 2, 74, null),
            UniqueStreamParser.Season("ryPJvHBp", "Skypiea (136-206)", 3, 71, null),
            UniqueStreamParser.Season("vISRWU6Y", "Summit War (385-516)", 17, 132, null),
        )

        // No saga title resembles "One Piece", so all of them are read together instead of one
        // arbitrary saga winning and hiding episode 1.
        val selected = UniqueStreamParser.selectSeasons(sagas, "21", listOf("One Piece"))
        assertEquals(sagas.map { it.contentId }, selected.map { it.contentId })

        // Announced counts reproduce the ranges the site prints in its own titles.
        assertEquals("dHTiu8rG", UniqueStreamParser.seasonForEpisode(sagas, 1)!!.contentId)
        assertEquals("dHTiu8rG", UniqueStreamParser.seasonForEpisode(sagas, 61)!!.contentId)
        assertEquals("HxyVVAYQ", UniqueStreamParser.seasonForEpisode(sagas, 62)!!.contentId)
        assertEquals("ryPJvHBp", UniqueStreamParser.seasonForEpisode(sagas, 206)!!.contentId)
        assertNull(UniqueStreamParser.seasonForEpisode(sagas, 400))

        // One missing count slides every later range onto the wrong episodes, so none is offered.
        val gapped = sagas.mapIndexed { index, s -> if (index == 1) s.copy(episodeCount = null) else s }
        assertNull(UniqueStreamParser.seasonForEpisode(gapped, 150))
        assertNull(UniqueStreamParser.seasonForEpisode(listOf(sagas[0]), 1))
    }

    @Test
    fun parsesEpisodePagesIntoSubAndDubSets() {
        val payload = """
            [
              {"title":"I'm Used to It","episode":"1","is_clip":false,"content_id":"sczeR0vi","episode_number":1.0,"duration_ms":1420046,"audio_locales":["ja-JP","en-US","ar-SA"]},
              {"title":"If I Had One More Chance","episode":"2","is_clip":false,"content_id":"ep2abcd1","episode_number":2.0,"duration_ms":1420000,"audio_locales":["ja-JP","ar-SA"]},
              {"title":"Recap","episode":"7.5","is_clip":false,"content_id":"ep75recp","episode_number":7.5,"duration_ms":1400000,"audio_locales":["ja-JP","en-US"]},
              {"title":"Season Trailer","episode":"","is_clip":true,"content_id":"clip0001","episode_number":3.0,"duration_ms":90000,"audio_locales":["ja-JP","en-US"]}
            ]
        """.trimIndent()

        val episodes = UniqueStreamParser.parseEpisodes(json.parseToJsonElement(payload))

        // The 7.5 recap and the is_clip trailer are dropped; only whole-number episodes remain.
        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals("sczeR0vi", episodes[0].contentId)

        val sub = episodes.filter { "ja-JP" in it.audioLocales }.map { it.number }.toSet()
        val dub = episodes.filter { "en-US" in it.audioLocales }.map { it.number }.toSet()
        assertEquals(setOf(1, 2), sub)
        assertEquals(setOf(1), dub)
    }

    @Test
    fun mapsMediaResponseToPlaylistAndSubtitles() {
        val payload = """
            {
              "title":"I'm Used to It","content_id":"sczeR0vi","media_id":"m123",
              "dash":{"playlist":"https://get.mediacache.cc/episode/x/dash.mpd"},
              "hls":{
                "locale":"ja-JP",
                "playlist":"https://get.mediacache.cc/episode/sczeR0vi/master.m3u8?sign=abc&expires=999",
                "subtitles":[
                  {"language":"en-US","url":"https://get.mediacache.cc/episode/sczeR0vi/subs/en-US.vtt","mimeType":"text/vtt"},
                  {"language":"ar-SA","url":"https://get.mediacache.cc/episode/sczeR0vi/subs/ar-SA.vtt","mimeType":"text/vtt"}
                ],
                "hard_subs":[{"locale":"ar-SA","playlist":"https://get.mediacache.cc/episode/sczeR0vi/hard/ar-SA/master.m3u8?sign=def"}]
              }
            }
        """.trimIndent()

        val media = UniqueStreamParser.parseMedia(json.parseToJsonElement(payload))!!

        assertEquals("m123", media.mediaId)
        assertEquals(
            "https://get.mediacache.cc/episode/sczeR0vi/master.m3u8?sign=abc&expires=999",
            media.playlist,
        )
        // No en-US hard-sub entry here, so there is no fallback playlist.
        assertNull(media.hardSubEn)
        assertEquals(2, media.subtitles.size)
        assertEquals("en-US", media.subtitles[0].label)
        assertEquals("en", media.subtitles[0].language)
        assertEquals("text/vtt", media.subtitles[0].mimeType)
    }

    @Test
    fun subStreamsTakeTheBurntInEncodeWhenThereAreNoSoftSubtitles() {
        // The shape every probed title returns: a clean Japanese playlist, zero soft subtitles,
        // and burnt-in locale encodes. Playing the clean one would show no subtitles at all.
        val noSoftSubs = UniqueStreamParser.parseMedia(
            json.parseToJsonElement(
                """{"media_id":"m1","hls":{"locale":"ja-JP","playlist":"https://cdn/x/master.m3u8","subtitles":[],
                   "hard_subs":[{"locale":"ar-SA","playlist":"https://cdn/x/hard/ar-SA/master.m3u8"},
                                {"locale":"en-US","playlist":"https://cdn/x/hard/en-US/master.m3u8"}]}}""",
            ),
        )!!

        val sub = UniqueStreamParser.choosePlayback(noSoftSubs, "sub")!!
        assertEquals("https://cdn/x/hard/en-US/master.m3u8", sub.url)
        assertEquals(true, sub.burntIn)
        assertEquals(emptyList<com.anilili.data.model.SubtitleItem>(), sub.subtitles)

        // A dub needs no subtitles and keeps the clean encode.
        val dub = UniqueStreamParser.choosePlayback(noSoftSubs, "dub")!!
        assertEquals("https://cdn/x/master.m3u8", dub.url)
        assertEquals(false, dub.burntIn)
    }

    @Test
    fun subStreamsPreferSoftSubtitlesOverBurntInWhenBothExist() {
        val withSoftSubs = UniqueStreamParser.parseMedia(
            json.parseToJsonElement(
                """{"media_id":"m1","hls":{"locale":"ja-JP","playlist":"https://cdn/x/master.m3u8",
                   "subtitles":[{"language":"en-US","url":"https://cdn/x/subs/en-US.vtt","mimeType":"text/vtt"}],
                   "hard_subs":[{"locale":"en-US","playlist":"https://cdn/x/hard/en-US/master.m3u8"}]}}""",
            ),
        )!!

        val sub = UniqueStreamParser.choosePlayback(withSoftSubs, "sub")!!
        assertEquals("https://cdn/x/master.m3u8", sub.url)
        assertEquals(false, sub.burntIn)
        assertEquals(1, sub.subtitles.size)

        // Nothing playable at all stays null rather than inventing a URL.
        val empty = UniqueStreamParser.parseMedia(
            json.parseToJsonElement("""{"hls":{"locale":"ja-JP","playlist":null,"subtitles":[],"hard_subs":[]}}"""),
        )!!
        assertNull(UniqueStreamParser.choosePlayback(empty, "sub"))
        assertNull(UniqueStreamParser.choosePlayback(empty, "dub"))
    }

    @Test
    fun usesEnUsHardSubOnlyWhenCleanPlaylistIsMissing() {
        val withClean = UniqueStreamParser.parseMedia(
            json.parseToJsonElement(
                """{"hls":{"locale":"en-US","playlist":"https://cdn/x/master.m3u8","subtitles":null,"hard_subs":[{"locale":"en-US","playlist":"https://cdn/x/hard/en-US/master.m3u8"}]}}""",
            ),
        )!!
        assertEquals("https://cdn/x/master.m3u8", withClean.playlist)
        assertEquals("https://cdn/x/hard/en-US/master.m3u8", withClean.hardSubEn)
        assertEquals(emptyList<com.anilili.data.model.SubtitleItem>(), withClean.subtitles)

        val hardOnly = UniqueStreamParser.parseMedia(
            json.parseToJsonElement(
                """{"hls":{"locale":"en-US","playlist":null,"subtitles":null,"hard_subs":[{"locale":"ar-SA","playlist":"https://cdn/x/hard/ar-SA/master.m3u8"},{"locale":"en-US","playlist":"https://cdn/x/hard/en-US/master.m3u8"}]}}""",
            ),
        )!!
        assertNull(hardOnly.playlist)
        assertEquals("https://cdn/x/hard/en-US/master.m3u8", hardOnly.hardSubEn)
    }

    @Test
    fun detectsNotReadyErrorShape() {
        val payload = """
            {"title":"I'm Used to It","content_id":"sczeR0vi","hls_not_ready":true,"dash":null}
        """.trimIndent()

        assertNull(UniqueStreamParser.parseMedia(json.parseToJsonElement(payload)))
        assertNull(UniqueStreamParser.parseMedia(json.parseToJsonElement("""{"title":"x"}""")))
    }
}
