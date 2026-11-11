package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * UniqueStream (anime.uniquestream.net) — a Crunchyroll mirror behind an open FastAPI JSON
 * backend, so there is no HTML scraping anywhere: `/search` finds the series, `/series/{id}`
 * lists its seasons (each carrying a MAL id), `/season/{id}/episodes` pages the episode list,
 * and `/episode/{id}/media/hls/{locale}` hands out a signed HLS master playlist (ja-JP = sub,
 * en-US = dub) plus optional soft subtitles. The playlist URLs play directly, with no Referer
 * or extra headers.
 *
 * Season matching goes by MAL id first: every season in the series payload carries the same
 * `mal_id` AniList stores on the media, which identifies the exact entry (Solo Leveling S1 vs
 * S2). Failing that a season titled like the show itself wins, and failing *that* the series is
 * one AniList entry split into sagas (One Piece's "East Blue (1-61)", "Alabasta (62-135)", …,
 * none of which carries a MAL id) whose episodes are numbered absolutely, so they are paged as
 * one catalog under a single shared page budget.
 *
 * Episode numbers come back as floats and can be fractional (7.5 recaps); only whole numbers
 * are kept, because the app's episode sets are Int-based. Clips are dropped entirely.
 */
internal class UniqueStreamProvider(private val client: OkHttpClient, private val json: Json) {
    private data class Catalog(
        val seasons: List<UniqueStreamParser.Season>,
        val episodes: List<UniqueStreamParser.Episode>,
    )

    private val catalogs = boundedMap<Int, Catalog>(64)

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val episodes = catalog(media).episodes
        if (episodes.isEmpty()) error("UniqueStream returned no episode catalog")
        return EpisodeAvailability(
            sub = episodes.filter { SUB_LOCALE in it.audioLocales }.map { it.number }.toSet(),
            dub = episodes.filter { DUB_LOCALE in it.audioLocales }.map { it.number }.toSet(),
        )
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val catalog = catalog(media)
        val entry = catalog.episodes.firstOrNull { it.number == episode }
            ?: sagaEpisode(catalog, episode)
            ?: error("UniqueStream episode $episode is not in the catalog")
        val locale = if (audio == "dub") DUB_LOCALE else SUB_LOCALE
        val root = json.parseToJsonElement(get("$BASE/episode/${entry.contentId}/media/hls/$locale"))
        val stream = UniqueStreamParser.parseMedia(root)
            ?: error("UniqueStream episode $episode has no ${audio.uppercase()} stream ready")
        val playback = UniqueStreamParser.choosePlayback(stream, audio)
            ?: error("UniqueStream episode $episode has no playable HLS stream")
        return SourcesResult(
            streams = listOf(
                StreamItem(
                    url = playback.url,
                    type = "hls",
                    quality = "auto",
                    audio = audio,
                    referer = null,
                    isActive = true,
                    width = null,
                    height = null,
                    // The playlist's AES-128 key.bin is served base64-wrapped and gated on a
                    // custom header, but the plaintext key is simply the media id as hex bytes;
                    // UniqueStreamKeyDataSource synthesizes it locally from this marker.
                    playlistKey = stream.mediaId?.let { "uniquestream:$it" },
                ),
            ),
            subtitles = playback.subtitles,
            skip = null,
            download = null,
        )
    }

    /**
     * A saga past the paging budget: the catalog stops at [MAX_EPISODE_PAGES], so rather than
     * page everything in between, jump to the season whose announced range covers this episode.
     */
    private fun sagaEpisode(catalog: Catalog, episode: Int): UniqueStreamParser.Episode? {
        val season = UniqueStreamParser.seasonForEpisode(catalog.seasons, episode) ?: return null
        return runCatching { pages(season, SAGA_JUMP_PAGES).first }
            .getOrDefault(emptyList())
            .firstOrNull { it.number == episode }
    }

    private fun catalog(media: Media): Catalog {
        catalogs[media.id]?.let { return it }
        val seasons = resolveSeasons(media)
        val episodes = episodes(seasons)
        // Only cache non-empty catalogs so a transient upstream hiccup isn't poisoned in.
        if (episodes.isNotEmpty()) catalogs[media.id] = Catalog(seasons, episodes)
        return Catalog(seasons, episodes)
    }

    private fun resolveSeasons(media: Media): List<UniqueStreamParser.Season> {
        val titles = titles(media)
        if (titles.isEmpty()) error("UniqueStream needs an AniList title")
        // The API black-holes bursts from one IP for a minute or so, so keep the request count
        // low: stop at the first title query that returns anything, and at the first series whose
        // seasons carry our MAL id, instead of fanning out every title and probe up front.
        var lastError: Throwable? = null
        var candidates = emptyList<UniqueStreamParser.SearchCandidate>()
        for (query in titles) {
            candidates = runCatching {
                UniqueStreamParser.parseSearch(
                    json.parseToJsonElement(get("$BASE/search?query=${enc(query)}&limit=20")),
                )
            }.onFailure { lastError = it }.getOrDefault(emptyList())
            if (candidates.isNotEmpty()) break
        }
        if (candidates.isEmpty()) {
            error("UniqueStream has no entry for this title (${lastError?.message ?: "empty search results"})")
        }
        val ranked = candidates.sortedByDescending { candidate ->
            titles.maxOf { NativeProviderParsers.titleSelectionScore(it, candidate.title) }
        }
        // Probe the best-ranked series for their season lists; a MAL-id season match wins over
        // any title ranking, and the top series alone may only carry a different entry's seasons.
        val malId = media.idMal?.toString()
        val seasons = mutableListOf<UniqueStreamParser.Season>()
        for (candidate in ranked.take(MAX_PROBE_SERIES)) {
            runCatching {
                val root = json.parseToJsonElement(get("$BASE/series/${candidate.contentId}"))
                seasons += UniqueStreamParser.parseSeasons(root)
            }
            if (malId != null && seasons.any { it.malId == malId }) break
        }
        return UniqueStreamParser.selectSeasons(seasons.distinctBy { it.contentId }, malId, titles)
            .ifEmpty { error("UniqueStream has no season for this title") }
    }

    /**
     * Pages the selected seasons in the order the API lists them (which is chronological) under
     * one shared budget, so a series split into sagas costs no more requests than a single season.
     */
    private fun episodes(seasons: List<UniqueStreamParser.Season>): List<UniqueStreamParser.Episode> {
        val all = mutableListOf<UniqueStreamParser.Episode>()
        var budget = MAX_EPISODE_PAGES
        for (season in seasons) {
            if (budget <= 0) break
            val (episodes, used) = pages(season, budget)
            all += episodes
            budget -= used
        }
        // Absolute numbering means sagas cannot collide; if one ever restarts, the earliest wins.
        return all.distinctBy { it.number }
    }

    /** One season's episodes, 20 at a time, until a short page, the announced count, or [budget]. */
    private fun pages(
        season: UniqueStreamParser.Season,
        budget: Int,
    ): Pair<List<UniqueStreamParser.Episode>, Int> {
        val out = mutableListOf<UniqueStreamParser.Episode>()
        var used = 0
        var fetched = 0
        while (used < budget) {
            used++
            val raw = json.parseToJsonElement(
                get("$BASE/season/${season.contentId}/episodes?limit=$PAGE_SIZE&page=$used"),
            ) as? JsonArray ?: break
            out += UniqueStreamParser.parseEpisodes(raw)
            // Count raw entries, not parsed ones: clips and 7.5 recaps are dropped on the way in.
            fetched += raw.size
            val expected = season.episodeCount
            if (raw.size < PAGE_SIZE || (expected != null && fetched >= expected)) break
        }
        return out to used
    }

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter { it.isNotBlank() }.distinct().take(MAX_SEARCH_QUERIES)

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, */*")
            .get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("UniqueStream HTTP ${response.code}")
            return body
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun <K, V> boundedMap(maxEntries: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        },
    )

    companion object {
        private const val BASE = "https://anime.uniquestream.net/api/v1"
        private const val SUB_LOCALE = "ja-JP"
        private const val DUB_LOCALE = "en-US"
        private const val PAGE_SIZE = 20
        private const val MAX_EPISODE_PAGES = 15
        /** Pages spent locating one episode in a saga the shared budget never reached. */
        private const val SAGA_JUMP_PAGES = 8
        private const val MAX_SEARCH_QUERIES = 3
        /** Series probed for their season lists before giving up on a title. */
        private const val MAX_PROBE_SERIES = 4
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

/** Pure-JSON parsing for UniqueStream API payloads, kept separate so unit tests never touch the network. */
internal object UniqueStreamParser {
    data class SearchCandidate(val contentId: String, val title: String, val subbed: Boolean, val dubbed: Boolean)
    data class Season(
        val contentId: String,
        val title: String,
        val seasonNumber: Int?,
        val episodeCount: Int?,
        val malId: String?,
    )
    data class Episode(val number: Int, val contentId: String, val audioLocales: Set<String>)

    /** An `/episode/{id}/media/hls/{locale}` payload with its HLS block unpacked. */
    data class MediaResult(
        /** Identifies the AES key: the segment key is this value hex-decoded. */
        val mediaId: String?,
        val playlist: String?,
        /** Burnt-in en-US playlist, only used when the clean playlist is absent. */
        val hardSubEn: String?,
        val subtitles: List<SubtitleItem>,
    )

    /** `/search` results: the `series` array (movies carry no seasons and cannot resolve episodes). */
    fun parseSearch(root: Any?): List<SearchCandidate> =
        ((root as? JsonObject)?.get("series") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { item ->
                val contentId = item.string("content_id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                SearchCandidate(
                    contentId = contentId,
                    title = item.string("title").orEmpty(),
                    subbed = (item["subbed"] as? JsonPrimitive)?.booleanOrNull == true,
                    dubbed = (item["dubbed"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }

    /** `/series/{id}` payload: the `seasons` array. `mal_id` arrives as a string (e.g. "52299"). */
    fun parseSeasons(root: Any?): List<Season> =
        ((root as? JsonObject)?.get("seasons") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { item ->
                val contentId = item.string("content_id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                Season(
                    contentId = contentId,
                    title = item.string("title").orEmpty(),
                    seasonNumber = (item["season_number"] as? JsonPrimitive)?.intOrNull,
                    episodeCount = (item["episode_count"] as? JsonPrimitive)?.intOrNull,
                    malId = item.string("mal_id")?.takeIf(String::isNotBlank),
                )
            }

    /**
     * The seasons that can hold the AniList entry being watched, in the API's own (chronological)
     * order. A `mal_id` names exactly one season, and a season titled like the show itself is that
     * show's own entry. When neither holds, the series is one entry split into sagas — One Piece's
     * "East Blue (1-61)"/"Alabasta (62-135)" carry no MAL id and score nothing against "One Piece"
     * — and those number their episodes absolutely, so the whole list is returned and read as one
     * catalog. Picking the best-scoring saga instead is how the caller ends up serving Skypiea and
     * reporting episode 1 as missing.
     */
    fun selectSeasons(seasons: List<Season>, malId: String?, titles: List<String>): List<Season> {
        if (seasons.isEmpty()) return emptyList()
        if (malId != null) seasons.firstOrNull { it.malId == malId }?.let { return listOf(it) }
        val best = seasons.maxByOrNull { titleScore(it, titles) } ?: return emptyList()
        return if (titleScore(best, titles) >= MIN_SEASON_TITLE_SCORE) listOf(best) else seasons
    }

    /**
     * The saga holding absolute episode [number], from the announced counts alone: One Piece's
     * 61/74/71/119 … yield 1-61, 62-135, 136-206, 207-325, exactly the ranges the site prints in
     * its own season titles. Null unless every season announces a count, because a single gap
     * would slide every later range onto the wrong episodes — the failure this exists to avoid.
     */
    fun seasonForEpisode(seasons: List<Season>, number: Int): Season? {
        if (seasons.size < 2 || seasons.any { (it.episodeCount ?: 0) <= 0 }) return null
        var start = 1
        for (season in seasons) {
            val end = start + season.episodeCount!! - 1
            if (number in start..end) return season
            start = end + 1
        }
        return null
    }

    private fun titleScore(season: Season, titles: List<String>): Double =
        titles.maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, season.title) } ?: 0.0

    /**
     * `/season/{id}/episodes` page — a plain JSON array. Clips and fractional numbers (7.5
     * recaps) are dropped; what remains maps straight onto the app's Int episode sets.
     */
    fun parseEpisodes(root: Any?): List<Episode> =
        (root as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { item ->
            if ((item["is_clip"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val raw = (item["episode_number"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (raw % 1.0 != 0.0 || raw < 1.0) return@mapNotNull null
            val contentId = item.string("content_id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            Episode(
                number = raw.toInt(),
                contentId = contentId,
                audioLocales = (item["audio_locales"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .toSet(),
            )
        }

    /**
     * `/episode/{id}/media/hls/{locale}` payload, or null when there is no HLS block at all —
     * the API answers not-yet-encoded episodes with an error shape (`hls_not_ready: true` and
     * no `hls` key) instead. The DASH block is DRM-protected and ignored entirely.
     */
    fun parseMedia(root: Any?): MediaResult? {
        val obj = root as? JsonObject ?: return null
        val hls = obj["hls"] as? JsonObject ?: return null
        val mediaId = obj.string("media_id")?.takeIf(String::isNotBlank)
        val playlist = hls.string("playlist")?.takeIf(String::isNotBlank)
        val hardSubEn = (hls["hard_subs"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("locale") == "en-US" }
            ?.let { it.string("playlist")?.takeIf(String::isNotBlank) }
        val subtitles = (hls["subtitles"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { item ->
                val url = item.string("url")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val label = item.string("language")?.takeIf(String::isNotBlank) ?: "Subtitle"
                SubtitleItem(
                    url = url,
                    label = label,
                    language = label.substringBefore('-').lowercase(),
                    mimeType = item.string("mimeType")?.takeIf(String::isNotBlank),
                )
            }
        return MediaResult(mediaId, playlist, hardSubEn, subtitles)
    }

    /** The playlist a stream should play, with whatever soft subtitles belong to that choice. */
    data class Playback(val url: String, val subtitles: List<SubtitleItem>, val burntIn: Boolean)

    /**
     * UniqueStream mirrors Crunchyroll's packaging, in which the Japanese audio ships **no soft
     * subtitles at all**: `subtitles` came back empty on every title probed (Solo Leveling,
     * Frieren, Chainsaw Man, Dandadan) while `hard_subs` carried 9-15 burnt-in locales. Preferring
     * the clean playlist there hands the viewer untranslated Japanese, so a sub stream falls back
     * to the burnt-in en-US encode. That encode lives beside the clean one under the same media id
     * — verified by decrypting its first segment to a TS sync byte — so the synthesized AES key
     * still applies and nothing about playback changes.
     *
     * A dub needs no subtitles and its payload carries none; it always takes the clean playlist.
     */
    fun choosePlayback(media: MediaResult, audio: String): Playback? {
        val clean = media.playlist
        if (audio == "dub") {
            val url = clean ?: media.hardSubEn ?: return null
            return Playback(url, media.subtitles, burntIn = clean == null)
        }
        if (clean != null && media.subtitles.isNotEmpty()) return Playback(clean, media.subtitles, false)
        media.hardSubEn?.let { return Playback(it, emptyList(), burntIn = true) }
        return clean?.let { Playback(it, media.subtitles, false) }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    /** How close a season title must sit to the show's own before it is taken as *the* season. */
    private const val MIN_SEASON_TITLE_SCORE = 0.5
}
