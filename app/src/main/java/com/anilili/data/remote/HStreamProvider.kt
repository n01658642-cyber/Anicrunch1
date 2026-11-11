package com.anilili.data.remote

import android.media.MediaCodecList
import android.os.Build
import com.anilili.data.cache.AppCache
import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.diagnostics.DiagnosticsLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * hstream.moe — a Laravel site with English subtitles on every episode and DASH up to 4K.
 *
 * Reaching a stream is two requests, both probed live on 2026-10-03:
 *
 *  1. The episode page (`/hentai/{series}-{n}`) carries the episode's numeric id in a hidden
 *     `#e_id` input and a CSRF token in `data-csrf`, and sets a session cookie.
 *  2. `POST /player/api {episode_id}` returns the mirror list and the episode's path on them. It
 *     answers 419 without **both** the session cookie and the `X-CSRF-TOKEN` header, and 200 with
 *     them — nothing else is checked, not even the User-Agent. There is no captcha on this path;
 *     the captcha the page shows gates only `/get-download`.
 *
 * The files are then served by five mirrors with no Referer, cookie or User-Agent requirement:
 * `720/manifest.mpd` is H.264, `1080/` and `2160/` are AV1, and `x264.720p.mp4` is the H.264 file
 * the site itself hands to devices that cannot play AV1. Segments are *named* `.webp` and are
 * ordinary fMP4 chunks; DASH takes the type from the manifest, so the name never matters.
 *
 * Titles carry no MAL, AniList, TMDB or AniDB id — every image is self-hosted and named by slug,
 * and the covers are the publisher's key art rather than AniList's — so a series is bound by name.
 * The slugs are romaji, which is AniList's own title form: 16 of 20 sampled series matched
 * exactly, and [seriesScore] exists for the remaining four.
 */
internal class HStreamProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: AppCache,
    /** Whether 1080p AV1 should lead. Asked once per resolve; see [deviceDecodesAv1InHardware]. */
    private val preferAv1: () -> Boolean = ::deviceDecodesAv1InHardware,
) {
    /** Slug per AniList id. Cheap to hold and it saves a catalogue pass on every episode change. */
    private val slugs = ConcurrentHashMap<Int, String>()

    suspend fun episodeAvailability(media: Media): EpisodeAvailability {
        val slug = seriesSlug(media) ?: error("HStream has no match for this title")
        val episodes = catalogue()[slug].orEmpty().toSet()
        if (episodes.isEmpty()) error("HStream listed no episodes for $slug")
        // Japanese audio only; every language this site has is a subtitle file.
        return EpisodeAvailability(episodes, emptySet())
    }

    suspend fun sources(media: Media, episode: Int): SourcesResult {
        val slug = seriesSlug(media) ?: error("HStream has no match for this title")
        val pageUrl = "$BASE/hentai/$slug-$episode"
        val page = fetchPage(pageUrl)
        val episodeId = HStreamParser.episodeId(page.html)
            ?: error("HStream episode $episode carried no episode id")
        val csrf = HStreamParser.csrfToken(page.html)
            ?: error("HStream episode $episode carried no CSRF token")

        val body = postPlayerApi(pageUrl, episodeId, csrf, page.cookies)
        val player = HStreamParser.player(json, body)
            ?: error("HStream player API answered without a stream path")
        val result = HStreamParser.sources(player, preferAv1(), mirrorStart = (0 until MIRROR_SPREAD).random())
        DiagnosticsLog.event(
            "HStream resolved slug=$slug episode=$episode streams=${result.streams.size} " +
                "subtitles=${result.subtitles.size} lead=${result.streams.firstOrNull()?.quality}",
        )
        return result
    }

    /**
     * The series slug for an AniList entry, matched against the sitemap locally.
     *
     * Anything under the threshold is treated as "no source" rather than played, because the
     * failure being guarded against is not an empty screen but the wrong video.
     */
    private suspend fun seriesSlug(media: Media): String? {
        slugs[media.id]?.let { return it }
        val candidates = aniListTitleCandidates(media)
        if (candidates.isEmpty()) return null
        val catalogue = runCatching { catalogue() }.getOrElse {
            DiagnosticsLog.throwable("HStream catalogue unavailable", it)
            return null
        }
        val found = catalogue.keys
            .mapNotNull { slug ->
                val score = seriesScore(candidates, slug)
                if (score >= MATCH_THRESHOLD) slug to score else null
            }
            .maxByOrNull { it.second }
            ?.first
        return found?.also { slugs[media.id] = it }
    }

    /**
     * Every series the site publishes, with its episode numbers. The sitemap lists both kinds of
     * page, so one 430KB fetch answers matching and episode availability at once; [cacheIf] keeps
     * an empty parse from being stored, so one bad pass cannot pin "no source" for hours.
     */
    private suspend fun catalogue(): Map<String, List<Int>> = cache.getOrFetch(
        key = CACHE_KEY,
        serializer = MapSerializer(String.serializer(), ListSerializer(Int.serializer())),
        ttlMs = CATALOGUE_TTL_MS,
        cacheIf = { it.isNotEmpty() },
    ) {
        val series = HStreamParser.catalogue(get("$BASE/sitemap.xml"))
        DiagnosticsLog.event("HStream catalogue downloaded series=${series.size}")
        series
    }

    private class Page(val html: String, val cookies: String)

    private suspend fun fetchPage(url: String): Page = runInterruptible(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,*/*")
            .get().build()
        client.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HStream HTTP ${response.code}")
            Page(html, HStreamParser.cookieHeader(response.headers("Set-Cookie")))
        }
    }

    private suspend fun postPlayerApi(
        pageUrl: String,
        episodeId: String,
        csrf: String,
        cookies: String,
    ): String = runInterruptible(Dispatchers.IO) {
        // The provider client keeps no cookie jar, so the session the page opened has to be carried
        // across by hand; without it the token alone is refused with the same 419.
        if (cookies.isBlank()) error("HStream episode page set no session cookie")
        val payload = """{"episode_id":"$episodeId"}""".toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url("$BASE/player/api")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Cookie", cookies)
            .header("X-CSRF-TOKEN", csrf)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", pageUrl)
            .header("Origin", BASE)
            .post(payload).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HStream player API HTTP ${response.code}")
            text
        }
    }

    private suspend fun get(url: String): String = runInterruptible(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/xml,text/html,*/*")
            .get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HStream HTTP ${response.code}")
            text
        }
    }

    companion object {
        const val BASE = "https://hstream.moe"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private const val CACHE_KEY = "hstream-series"

        /**
         * Shorter than Hentai Haven's day, because here the same cached sitemap is also what says
         * which episodes exist, and a new episode should not wait a day to appear.
         */
        private const val CATALOGUE_TTL_MS = 6L * 60 * 60 * 1000

        /** Same bar hanime and Hentai Haven use. */
        private const val MATCH_THRESHOLD = 0.85

        /** How many mirrors to spread the first pick over; the API has returned five every time. */
        private const val MIRROR_SPREAD = 5

        /**
         * How well a site slug stands for one of an AniList entry's titles, 0..1.
         *
         * The plain slug-as-title comparison already lands most series. Each extra spelling below
         * rescued one measured miss, and the number check closes the one false match the extra
         * leniency would otherwise open:
         *
         *  - `ito-o-yoru` is AniList's "Ito wo Yoru" — the site writes the particle を as "o".
         *  - `imouto-paradise-s2` is AniList's "Imouto Paradise! 2".
         *  - `inyouchuu-the-animation` drops a subtitle AniList keeps after a colon, and
         *    `yarichin-kateikyoushi-netori-houkoku` keeps the part *before* AniList's colon.
         *  - But "Imouto Paradise!" (season one) scored 0.93 against the season-two slug, over the
         *    bar. So a pair whose standalone numbers differ is never a match: a season or sequel
         *    number is exactly the difference a bigram score is too forgiving about.
         */
        fun seriesScore(candidates: List<String>, slug: String): Double {
            val siteTitles = siteTitleVariants(slug)
            var best = 0.0
            for (candidate in candidates.flatMap(::aniListTitleVariants).distinct()) {
                for (site in siteTitles) {
                    if (!numbersAgree(candidate, site)) continue
                    val score = titleSimilarity(candidate, site)
                    if (score > best) best = score
                }
            }
            return best
        }

        /** The ways a slug can be read as a title; see [seriesScore] for why each one exists. */
        fun siteTitleVariants(slug: String): List<String> {
            val base = slug.replace('-', ' ')
                .replace(SEASON_SUFFIX, " $1")
            val withoutAnimation = base.replace(THE_ANIMATION_SUFFIX, "")
            return listOf(base, withoutAnimation)
                .flatMap { listOf(it, it.replace(PARTICLE_O, "wo")) }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        }

        private fun aniListTitleVariants(title: String): List<String> = listOf(
            title,
            title.substringBefore(':'),
            title.replace(THE_ANIMATION_SUFFIX, ""),
        ).map(String::trim).filter(String::isNotEmpty)

        /** Whether two titles carry the same standalone numbers, in any order. */
        fun numbersAgree(left: String, right: String): Boolean =
            standaloneNumbers(left) == standaloneNumbers(right)

        private fun standaloneNumbers(title: String): List<String> =
            normalizeHanimeTitle(title).split(' ').filter { token -> token.all(Char::isDigit) }.sorted()

        private val SEASON_SUFFIX = Regex("""\s+s(\d+)$""")
        private val THE_ANIMATION_SUFFIX = Regex("""(?i)[\s:]+the animation\s*$""")
        /** A standalone "o", the site's romanisation of the particle を that AniList writes "wo". */
        private val PARTICLE_O = Regex("""(?<= )o(?= )""")
    }
}

/** Pure parsing for [HStreamProvider], kept separable so the site's shape can be tested offline. */
internal object HStreamParser {
    /** What `/player/api` says about one episode. */
    data class Player(
        val streamPath: String,
        val mirrors: List<String>,
        /** Language code to display name, for the machine-translated `.ass` files. */
        val extraSubtitles: Map<String, String>,
    )

    /**
     * Series slug to its episode numbers, from the sitemap.
     *
     * A sitemap entry is an episode when it is a series entry plus `-{n}`. Deciding by "ends in a
     * number" alone would misfile any series whose own name ends in one, so the series has to be
     * present for an entry to count as its episode.
     */
    fun catalogue(xml: String): Map<String, List<Int>> {
        // Close to half a megabyte with a match on every line: exactly the walk `findAll` makes
        // quadratic in native memory on Android. See mapMatches.
        val entries = SITEMAP_ENTRY.mapMatches(xml) { match -> match.group(1) }.toHashSet()
        val episodes = HashMap<String, MutableSet<Int>>()
        for (entry in entries) {
            val numbered = TRAILING_NUMBER.matchEntire(entry) ?: continue
            val series = numbered.groupValues[1]
            if (series !in entries) continue
            val number = numbered.groupValues[2].toIntOrNull() ?: continue
            episodes.getOrPut(series) { HashSet() }.add(number)
        }
        return episodes.mapValues { (_, numbers) -> numbers.sorted() }
    }

    fun episodeId(html: String): String? = EPISODE_ID.find(html)?.groupValues?.get(1)

    fun csrfToken(html: String): String? =
        (DATA_CSRF.find(html) ?: META_CSRF.find(html))?.groupValues?.get(1)?.takeIf(String::isNotBlank)

    /** A `Cookie` header out of a response's `Set-Cookie` lines: each `name=value`, attributes dropped. */
    fun cookieHeader(setCookies: List<String>): String = setCookies
        .map { it.substringBefore(';').trim() }
        .filter { it.contains('=') }
        .joinToString("; ")

    fun player(json: Json, body: String): Player? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val path = (root["stream_url"] as? JsonPrimitive)?.contentOrNull?.trim('/')
            ?.takeIf(String::isNotBlank) ?: return null
        val mirrors = (root["stream_domains"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trimEnd('/') }
            .filter { it.startsWith("https://") }
        if (mirrors.isEmpty()) return null
        // An empty set arrives as `[]` rather than `{}`, so anything that is not an object is none.
        val extras = (root["extra_subtitles"] as? JsonObject).orEmpty()
            .mapNotNull { (code, name) ->
                val label = (name as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                code.takeIf { LANGUAGE_CODE.matches(it) }?.let { it to label }
            }
            .toMap()
        return Player(path, mirrors, extras)
    }

    /**
     * The streams in the order the player should try them, each on a different mirror so a failed
     * mirror is stepped past rather than retried.
     *
     * 720p DASH leads because it is H.264 and plays everywhere. The 720p MP4 is the same encode as
     * one file — the fallback, and the one a download can use. 1080p AV1 leads only on a device with
     * a hardware AV1 decoder; offering it anywhere else would trade a working picture for a failed
     * first play. 4K and the 48fps interpolations are left out on bandwidth: a 2160p AV1 stream is
     * several times the 1080p bitrate for a picture most screens here cannot show.
     */
    fun sources(player: Player, preferAv1: Boolean, mirrorStart: Int): SourcesResult {
        val mirrors = player.mirrors
        fun mirror(index: Int) = mirrors[Math.floorMod(mirrorStart + index, mirrors.size)]
        fun file(index: Int, name: String) = "${mirror(index)}/${player.streamPath}/$name"

        class Rendition(val file: String, val type: String, val quality: String, val width: Int, val height: Int)
        val dash720 = Rendition("720/manifest.mpd", "dash", "HStream 720p", 1280, 720)
        val mp4 = Rendition("x264.720p.mp4", "mp4", "HStream 720p MP4", 1280, 720)
        val av1 = Rendition("1080/manifest.mpd", "dash", "HStream 1080p", 1920, 1080)
        val order = if (preferAv1) listOf(av1, dash720, mp4) else listOf(dash720, mp4)
        // The mirror follows the position in the list, not the rendition, so that consecutive
        // attempts never land on the same host.
        val streams = order.mapIndexed { index, rendition ->
            StreamItem(
                url = file(index, rendition.file),
                type = rendition.type,
                quality = rendition.quality,
                audio = null,
                referer = "${HStreamProvider.BASE}/",
                isActive = index == 0,
                width = rendition.width,
                height = rendition.height,
            )
        }

        // Subtitles off the leading mirror; every mirror carries the same files.
        val english = SubtitleItem(url = file(0, "eng.vtt"), label = "English", language = "en")
        val translated = player.extraSubtitles.entries
            .sortedBy { it.value }
            .map { (code, name) ->
                SubtitleItem(
                    url = file(0, "autotrans/$code.ass"),
                    label = "$name (auto-translated)",
                    language = code,
                    mimeType = SSA_MIME,
                )
            }
        return SourcesResult(streams = streams, subtitles = listOf(english) + translated, skip = null, download = null)
    }

    /** `MimeTypes.TEXT_SSA`, written out so the data layer does not depend on Media3. */
    private const val SSA_MIME = "text/x-ssa"

    private val SITEMAP_ENTRY = Regex("""<loc>https://hstream\.moe/hentai/([a-z0-9-]+)/?</loc>""")
    private val TRAILING_NUMBER = Regex("""(.+)-(\d+)""")
    private val EPISODE_ID = Regex("""<input[^>]*\bid="e_id"[^>]*\bvalue="(\d+)"""")
    private val DATA_CSRF = Regex("""data-csrf="([^"]+)"""")
    private val META_CSRF = Regex("""<meta name="csrf-token" content="([^"]+)"""")
    private val LANGUAGE_CODE = Regex("""[a-z]{2,3}(?:-[A-Za-z]{2,4})?""")
}

/**
 * Whether this device decodes AV1 in hardware. Computed once: the codec list is a binder round
 * trip and cannot change while the process runs.
 *
 * Software decoders are deliberately not counted. Android 10+ ships one, and a phone can push
 * 1080p through it, but a TV box cannot — and hstream offers the same picture in H.264 at 720p,
 * so a slow AV1 decode buys nothing a viewer would choose.
 */
internal fun deviceDecodesAv1InHardware(): Boolean = HARDWARE_AV1

private val HARDWARE_AV1: Boolean by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@lazy false
    runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) }
        }
    }.getOrDefault(false)
}
