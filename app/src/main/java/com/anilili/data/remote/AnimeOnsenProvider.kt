package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AnimeOnsen (animeonsen.xyz) — a real JSON API rather than a site to scrape, and the only source
 * here that publishes subtitles in nine languages.
 *
 * ```
 * GET  www.animeonsen.xyz/                       -> <meta name="ao-search-token">      (~1.0s)
 * POST auth.animeonsen.xyz/oauth/token           -> access_token, expires_in 604800    (~0.3s)
 * POST search.animeonsen.xyz/indexes/content/search {q} -> {hits:[{content_id,…}]}     (~0.3s)
 * GET  api.animeonsen.xyz/v4/content/{id}/extensive     -> mal_id, total_episodes      (~0.7s)
 * GET  api.animeonsen.xyz/v4/content/{id}/video/{ep}    -> stream, subtitles, skips    (~0.3s)
 * ```
 *
 * Three things make it worth a lane despite a small library (~1,100 titles, measured off the index
 * cursor), none of which any other provider here offers:
 *
 *  - **Nothing is parsed out of HTML.** Every catalogue answer is JSON, which matters most exactly
 *    where the app is weakest: a television spends seconds regex-parsing a 23KB page and drops the
 *    provider entirely ([[tv-catalogue-parse-cost]]). There is no page to parse.
 *  - **It states `mal_id`**, so a title match can be *confirmed* against an id rather than trusted,
 *    the same thing that makes [PaheProvider]'s matching exact instead of fuzzy.
 *  - **It ships per-episode skip times and nine subtitle languages.** The app has neither from any
 *    other native source — everything else is English, or English and Hindi.
 *
 * Two shapes here are unusual and deliberate. The stream is **MPEG-DASH**, the first in this app;
 * `media3-exoplayer-dash` is already a dependency so it costs nothing new, but no other provider
 * exercises that path. And the subtitles are **SubStation Alpha served from an extension-less URL**,
 * so [SubtitleItem.mimeType] has to be stated: left to infer, the player would read `/en-US/1` as
 * WebVTT and render nothing.
 *
 * Both the CDN and the subtitle endpoint are gated on `Referer: animeonsen.xyz` and nothing else —
 * measured 403 bare, 200 with it, on both hosts. The OAuth token is for the metadata API only and
 * never needs to reach the player, which is why [StreamItem.headers] stays empty.
 */
internal class AnimeOnsenProvider(private val client: OkHttpClient, private val json: Json) {

    /** What a confirmed title lookup is worth keeping: the id to ask about, and how many episodes. */
    private data class Entry(val contentId: String, val episodes: Int)

    private data class Token(val value: String, val expiresAtMs: Long)

    /**
     * AniList id to confirmed content id. In memory only, like [PaheProvider]'s session map: the
     * mapping is stable, but nothing here is worth a disk write and a cold process re-confirms in
     * one search plus one detail request.
     */
    private val entries = ConcurrentHashMap<Int, Entry>()

    @Volatile
    private var apiToken: Token? = null

    @Volatile
    private var searchToken: String? = null

    private val tokenLock = Any()

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val entry = entry(media)
        // Sub only, and this is read off the stream rather than assumed: the DASH manifest declares
        // exactly one audio AdaptationSet, `lang="und"`, so there is no dub to offer and no track to
        // switch to. Claiming one would put a picker row here that can never resolve.
        return EpisodeAvailability.counts(sub = entry.episodes, dub = 0)
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        if (audio.equals("dub", ignoreCase = true)) error("AnimeOnsen publishes no dub")
        val entry = entry(media)
        if (episode !in 1..entry.episodes) error("AnimeOnsen has no episode $episode")
        val body = api("content/${entry.contentId}/video/$episode")
            ?: error("AnimeOnsen returned no video payload for episode $episode")
        return AnimeOnsenParser.sources(body)
    }

    /**
     * The content id for [media], found by title and **confirmed by MyAnimeList id**.
     *
     * Searching is the only way in — the API is keyed by its own opaque `content_id` — but every
     * detail response carries `mal_id`, so a candidate can be checked rather than believed. The
     * check costs one request per candidate and this runs inside the catalogue's budget, so only
     * the first few relevance-ordered hits are worth paying for; a real match is at the top or is
     * not there.
     *
     * When AniList has no MAL id for the title there is nothing to confirm against, and the fall
     * back is a deliberately strict title score rather than "take the first hit" — a wrong match
     * here is worse than no match, because it plays the wrong show rather than failing.
     */
    private fun entry(media: Media): Entry {
        entries[media.id]?.let { return it }
        val titles = listOfNotNull(media.title.english, media.title.romaji, media.title.userPreferred)
            .map(String::trim).filter(String::isNotEmpty).distinct()

        val hits = titles.flatMap { title ->
            runCatching { AnimeOnsenParser.hits(search(title)) }.getOrDefault(emptyList())
        }.distinctBy(AnimeOnsenParser.Hit::contentId)
        if (hits.isEmpty()) error("AnimeOnsen has no entry for this title")

        val malId = media.idMal
        val confirmed = if (malId != null) {
            hits.take(CONFIRM_LIMIT).firstNotNullOfOrNull { hit ->
                val detail = runCatching { AnimeOnsenParser.detail(api("content/${hit.contentId}/extensive")) }
                    .getOrNull() ?: return@firstNotNullOfOrNull null
                detail.takeIf { it.malId == malId }?.let { Entry(hit.contentId, it.episodes) }
            }
        } else {
            val best = hits.maxByOrNull { hit -> hit.titles.maxOfOrNull { score(titles, it) } ?: 0.0 }
            val strong = best?.takeIf { hit ->
                (hit.titles.maxOfOrNull { score(titles, it) } ?: 0.0) >= TITLE_FLOOR
            }
            strong?.let { hit ->
                val detail = AnimeOnsenParser.detail(api("content/${hit.contentId}/extensive"))
                Entry(hit.contentId, detail.episodes)
            }
        } ?: error("AnimeOnsen has no confirmed match for this title")

        if (confirmed.episodes <= 0) error("AnimeOnsen lists no episodes for this title")
        entries[media.id] = confirmed
        return confirmed
    }

    private fun score(queries: List<String>, candidate: String): Double =
        queries.maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, candidate) } ?: 0.0

    private fun search(query: String): JsonObject? {
        val request = Request.Builder()
            .url(SEARCH_URL)
            .headers(browserHeaders())
            .header("Authorization", "Bearer ${searchToken()}")
            .post(json.encodeToString(SearchQuery.serializer(), SearchQuery(query)).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    private fun api(path: String): JsonObject? {
        val request = Request.Builder()
            .url("$API_URL/$path")
            .headers(browserHeaders())
            .header("Authorization", "Bearer ${apiToken()}")
            .get().build()
        return execute(request)
    }

    private fun execute(request: Request): JsonObject? = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("AnimeOnsen answered HTTP ${response.code} for ${request.url.encodedPath}")
        runCatching { json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject }.getOrNull()
    }

    /**
     * The API bearer, minted at most once a week.
     *
     * The credentials are the site's own public client, taken from its bundle exactly as its browser
     * sends them — this is a `client_credentials` grant for an anonymous reader, not somebody's
     * account. `expires_in` is seven days, so this is effectively a per-install cost.
     */
    private fun apiToken(): String = synchronized(tokenLock) {
        apiToken?.takeIf { System.currentTimeMillis() < it.expiresAtMs }?.let { return it.value }
        val form = "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&grant_type=client_credentials"
        val request = Request.Builder()
            .url(TOKEN_URL)
            .headers(browserHeaders())
            .post(form.toRequestBody(FORM_MEDIA_TYPE))
            .build()
        val body = execute(request) ?: error("AnimeOnsen token response was not JSON")
        val value = (body["access_token"] as? JsonPrimitive)?.contentOrNull
            ?: error("AnimeOnsen token response carried no access_token")
        val lifetimeMs = ((body["expires_in"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_TOKEN_SECONDS) * 1000L
        apiToken = Token(value, System.currentTimeMillis() + lifetimeMs - TOKEN_SLACK_MS)
        value
    }

    /**
     * The Meilisearch key, which is published in the landing page's head rather than issued.
     *
     * A separate credential from the API bearer and not interchangeable with it — the search index
     * refuses the API token outright. Held for the process because it is a page scrape, and the one
     * piece of HTML this provider touches.
     */
    private fun searchToken(): String = synchronized(tokenLock) {
        searchToken?.let { return it }
        val request = Request.Builder().url("$BASE/").headers(browserHeaders()).get().build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("AnimeOnsen landing page answered HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val token = AnimeOnsenParser.searchToken(html) ?: error("AnimeOnsen published no search token")
        searchToken = token
        token
    }

    private fun browserHeaders() = okhttp3.Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Accept", "application/json, text/plain, */*")
        // Every host in the chain — auth, api, search and the CDN — gates on this and nothing else.
        .add("Referer", "$BASE/")
        .add("Origin", BASE)
        .build()

    @kotlinx.serialization.Serializable
    private data class SearchQuery(val q: String)

    private companion object {
        const val BASE = AnimeOnsenParser.BASE
        const val API_URL = "https://api.animeonsen.xyz/v4"
        const val SEARCH_URL = "https://search.animeonsen.xyz/indexes/content/search"
        const val TOKEN_URL = "https://auth.animeonsen.xyz/oauth/token"

        /** The site's own public reader credentials, as its browser bundle sends them. */
        const val CLIENT_ID = "f296be26-28b5-4358-b5a1-6259575e23b7"
        const val CLIENT_SECRET =
            "349038c4157d0480784753841217270c3c5b35f4281eaee029de21cb04084235"

        /** How many relevance-ordered hits are worth a confirming detail request. */
        const val CONFIRM_LIMIT = 3

        /** Only used when AniList has no MAL id to confirm against; see [entry]. */
        const val TITLE_FLOOR = 0.6

        const val DEFAULT_TOKEN_SECONDS = 3_600
        const val TOKEN_SLACK_MS = 60_000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Pure payload handling for [AnimeOnsenProvider], kept network-free for unit tests. */
internal object AnimeOnsenParser {
    const val BASE = "https://www.animeonsen.xyz"

    /**
     * SubStation Alpha, stated rather than inferred.
     *
     * The subtitle URLs end in `/en-US/1` with no extension, and the player's own guess for an
     * unrecognised URL is WebVTT — which parses an ASS file into nothing at all, silently. This is
     * the same string as `MimeTypes.TEXT_SSA`, written out so the data layer does not depend on
     * Media3.
     */
    const val SUBTITLE_MIME = "text/x-ssa"

    /** One search result. All three title spellings are kept because any of them may be the match. */
    data class Hit(val contentId: String, val titles: List<String>)

    data class Detail(val malId: Int?, val episodes: Int)

    private val SEARCH_TOKEN = Regex(
        """<meta[^>]*\bname=["']ao-search-token["'][^>]*\bcontent=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    /** Also matched with the attributes the other way round; head tags are not ordered by contract. */
    private val SEARCH_TOKEN_REVERSED = Regex(
        """<meta[^>]*\bcontent=["']([^"']+)["'][^>]*\bname=["']ao-search-token["']""",
        RegexOption.IGNORE_CASE,
    )

    fun searchToken(html: String): String? =
        (SEARCH_TOKEN.find(html) ?: SEARCH_TOKEN_REVERSED.find(html))?.groupValues?.get(1)

    fun hits(root: JsonObject?): List<Hit> =
        (root?.get("hits") as? JsonArray).orEmpty().mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val id = row.string("content_id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val titles = listOfNotNull(
                row.string("content_title"),
                row.string("content_title_en"),
                row.string("content_title_jp"),
            ).filter(String::isNotBlank)
            Hit(id, titles)
        }

    fun detail(root: JsonObject?): Detail {
        val body = root ?: error("AnimeOnsen returned no detail payload")
        return Detail(
            malId = body.int("mal_id"),
            episodes = body.int("total_episodes") ?: 0,
        )
    }

    fun sources(root: JsonObject): SourcesResult {
        val uri = root["uri"] as? JsonObject ?: error("AnimeOnsen video payload carried no uri")
        val stream = uri.string("stream")?.takeIf(String::isNotBlank)
            ?: error("AnimeOnsen video payload carried no stream")

        val subtitles = (uri["subtitles"] as? JsonObject).orEmptyObject()
            .mapNotNull { (tag, value) ->
                val url = (value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                SubtitleItem(
                    url = url,
                    label = label(tag),
                    language = language(tag),
                    mimeType = SUBTITLE_MIME,
                    // The subtitle host gates on the same Referer the CDN does, and a track is
                    // fetched by the player rather than by this provider, so it has to carry its own.
                    referer = "$BASE/",
                )
            }
            // English first because it is the one the player marks default; the rest alphabetically
            // so the menu is stable rather than however the JSON happened to be ordered.
            .sortedWith(compareByDescending<SubtitleItem> { it.language == "en" }.thenBy { it.label })

        return SourcesResult(
            streams = listOf(
                StreamItem(
                    url = stream,
                    // The first DASH source in the app. `media3-exoplayer-dash` already ships.
                    type = "dash",
                    quality = "AnimeOnsen",
                    audio = null,
                    // Measured: 403 without, 200 with, on the same URL seconds apart.
                    referer = "$BASE/",
                    isActive = true,
                    width = null,
                    height = null,
                ),
            ),
            subtitles = subtitles,
            skip = skip(root),
            download = null,
        )
    }

    /**
     * The intro window, which AnimeOnsen states per episode in whole seconds as strings.
     *
     * `metadata.episode` is a three-element array — the number, this episode's data, then every
     * episode's titles — and only the middle element carries timings.
     *
     * `nextEpisode_s` is deliberately not mapped onto [SkipTimes.outroStart]. It is the cue to offer
     * the next episode, not the start of a window with a known end, and claiming an outro range the
     * payload does not contain would put a skip button on screen with nowhere to skip to.
     */
    private fun skip(root: JsonObject): SkipTimes? {
        val episode = (root["metadata"] as? JsonObject)?.get("episode") as? JsonArray ?: return null
        val current = episode.getOrNull(1) as? JsonObject ?: return null
        val start = current.seconds("skipIntro_s")
        val end = current.seconds("skipIntro_e")
        if (start == null || end == null || end <= start) return null
        return SkipTimes(introStart = start, introEnd = end, outroStart = null, outroEnd = null)
    }

    /** A display name for a locale tag; the API only ever publishes this closed set. */
    fun label(tag: String): String = when (tag) {
        "ar-ME" -> "Arabic"
        "de-DE" -> "German"
        "en-US" -> "English"
        "es-ES" -> "Spanish"
        "es-LA" -> "Spanish (Latin America)"
        "fr-FR" -> "French"
        "it-IT" -> "Italian"
        "pt-BR" -> "Portuguese (Brazil)"
        "ru-RU" -> "Russian"
        else -> tag
    }

    /** The primary subtag, which is what the player matches a preferred language against. */
    fun language(tag: String): String = tag.substringBefore('-').lowercase().ifBlank { "und" }

    private fun JsonObject?.orEmptyObject(): Map<String, kotlinx.serialization.json.JsonElement> =
        this ?: emptyMap()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    /** Timings arrive as quoted whole seconds (`"55"`), not numbers. */
    private fun JsonObject.seconds(name: String): Double? {
        val raw = this[name] as? JsonPrimitive ?: return null
        return raw.doubleOrNull ?: raw.contentOrNull?.trim()?.toDoubleOrNull()
    }
}
