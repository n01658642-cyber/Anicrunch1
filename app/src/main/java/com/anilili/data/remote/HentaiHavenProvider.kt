package com.anilili.data.remote

import com.anilili.data.cache.AppCache
import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Hentai Haven, since the site left WordPress for Next.js.
 *
 * The four-hop unwrapping this provider used to do is gone with the plugin that needed it:
 * `wp-content/plugins/player-logic/player.php` and `api.php` both 404 now, so there is no
 * `data=` iframe, no `x-secure-token`, no ROT13-and-base64 rounds and no per-playback
 * authorization token. What replaced it is a single GET — **the episode page carries the HLS URL
 * in its own HTML**, and the manifest is served without a User-Agent, Referer or age cookie.
 *
 * Two manifest backends appear, and both are ordinary master playlists, so neither needs telling
 * apart beyond finding the URL:
 *
 *  - `octopusmanifest.org/{uuid}/playlist.m3u8` — 720p and 360p fMP4 (segments are still *named*
 *    `.jpg` and still sniff as fMP4), plus subtitle renditions in around twenty languages.
 *  - `master-lengs.org/api/v3/hh/{slug}-{n}/master.m3u8` — 480/720/1080, no subtitles.
 *
 * Those subtitles are `EXT-X-MEDIA` renditions *inside* the manifest, so they arrive as text
 * tracks the player already offers and must not also be sideloaded — downloading a rendition URL
 * as though it were a file is the mistake that once wrote subtitle playlists into the video cache.
 *
 * Titles still carry no MAL or AniList id, so a series is bound by name. That part could no longer
 * be done by asking the site: `/search/?q=` returns **the same eighteen entries for every query,
 * including nonsense**, because the server-rendered page is a fixed grid and the real search is
 * behind a Turnstile challenge. Scoring against a fixed grid would clear the bar by luck sooner or
 * later, and a wrong hentai match is worse than none, so the slug list comes from the sitemap
 * instead and the match is made on the device — the same shape [HanimeProvider] already uses.
 */
internal class HentaiHavenProvider(
    private val client: OkHttpClient,
    private val cache: AppCache,
) {
    /** Slug per AniList id. Cheap to hold and it saves a catalogue pass on every episode change. */
    private val slugs = ConcurrentHashMap<Int, String>()

    suspend fun episodeAvailability(media: Media): EpisodeAvailability {
        val slug = seriesSlug(media) ?: error("Hentai Haven has no match for this title")
        val episodes = HentaiHavenParser.episodeNumbers(get("$BASE/watch/$slug/"), slug)
        if (episodes.isEmpty()) error("Hentai Haven listed no episodes for $slug")
        // Japanese audio; the subtitle languages ride in the manifest, and there is no dub track.
        return EpisodeAvailability(episodes, emptySet())
    }

    suspend fun sources(media: Media, episode: Int): SourcesResult {
        val slug = seriesSlug(media) ?: error("Hentai Haven has no match for this title")
        // Trailing slash is the canonical form; without it every resolve eats a 301 first.
        val episodeUrl = "$BASE/watch/$slug/episode-$episode/"
        val hls = HentaiHavenParser.manifestUrl(get(episodeUrl))
            ?: error("Hentai Haven episode $episode carried no manifest")

        val stream = StreamItem(
            url = hls,
            type = "hls",
            quality = "Hentai Haven",
            audio = null,
            referer = "$BASE/",
            isActive = true,
            width = null,
            height = null,
            // Carried over from the WordPress CDN, where Cronet died about a second into playback
            // while the same manifest played clean on the plain stack. Not re-measured against
            // these two hosts; the cost of keeping it is only that Cronet goes unused.
            avoidCronet = true,
        )
        DiagnosticsLog.event("HentaiHaven resolved slug=$slug episode=$episode host=${hls.hostOrBlank()}")
        return SourcesResult(listOf(stream), emptyList(), null, null)
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
            DiagnosticsLog.throwable("Hentai Haven catalogue unavailable", it)
            return null
        }
        val found = catalogue
            .mapNotNull { slug ->
                val title = slugAsTitle(slug)
                val score = candidates.maxOf { titleSimilarity(it, title) }
                if (score >= MATCH_THRESHOLD) slug to score else null
            }
            .maxByOrNull { it.second }
            ?.first
        return found?.also { slugs[media.id] = it }
    }

    /**
     * Every series slug the site publishes. Persisted, because it is a megabyte of XML to rebuild
     * and the library grows by a title at a time; [cacheIf] keeps an empty parse from being stored,
     * so one bad pass cannot pin "no hentai source exists" for a day.
     */
    private suspend fun catalogue(): List<String> = cache.getOrFetch(
        key = CACHE_KEY,
        serializer = ListSerializer(String.serializer()),
        ttlMs = CATALOGUE_TTL_MS,
        cacheIf = { it.isNotEmpty() },
    ) { downloadCatalogue() }

    private suspend fun downloadCatalogue(): List<String> {
        val slugs = SERIES_SITEMAPS.flatMap { sitemap ->
            runCatching { HentaiHavenParser.sitemapSlugs(get("$BASE/$sitemap")) }
                .onFailure { DiagnosticsLog.throwable("Hentai Haven sitemap $sitemap failed", it) }
                .getOrDefault(emptyList())
        }.distinct()
        DiagnosticsLog.event("HentaiHaven catalogue downloaded series=${slugs.size}")
        return slugs
    }

    private suspend fun get(url: String): String = runInterruptible(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE/")
            .header("Accept", "text/html,application/xml,*/*")
            .get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Hentai Haven HTTP ${response.code}")
            text
        }
    }

    private fun String.hostOrBlank(): String =
        runCatching { java.net.URI(this).host }.getOrNull().orEmpty()

    companion object {
        private const val BASE = "https://hentaihaven.xxx"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        /**
         * The English series sitemaps. The index lists the same set again per locale and another
         * thirty-odd for the site's unrelated rule34 section; those carry no `/watch/` slug we can
         * play, so enumerating the index would cost megabytes to learn nothing.
         */
        private val SERIES_SITEMAPS = listOf("en-manga-sitemap-1.xml", "en-manga-sitemap-2.xml")

        private const val CACHE_KEY = "hentaihaven-series"

        /** Around a thousand slugs and a megabyte to rebuild; the library grows slowly. */
        private const val CATALOGUE_TTL_MS = 24L * 60 * 60 * 1000

        /** Same bar hanime uses; these two catalogues disagree mostly on punctuation, not words. */
        private const val MATCH_THRESHOLD = 0.85

        /** `onaji-zemi-no-someya-san` reads as a title once the hyphens are spaces. */
        fun slugAsTitle(slug: String): String = slug.replace('-', ' ')
    }
}

/** Pure parsing for [HentaiHavenProvider], kept separable so the site's shape can be tested offline. */
internal object HentaiHavenParser {
    /**
     * The HLS URL an episode page carries.
     *
     * Deliberately matched on nothing but the `.m3u8` ending rather than on either backend's host
     * or path: the site has already moved its manifests once inside one release, and the two
     * shapes in use share no structure beyond being absolute HTTPS.
     */
    fun manifestUrl(html: String): String? =
        MANIFEST.find(html)?.value?.takeIf(String::isNotBlank)

    /**
     * Series slugs from one sitemap. The episode URLs live in a sitemap of their own, so a
     * `/watch/{slug}` here is always a series.
     */
    fun sitemapSlugs(xml: String): List<String> =
        // A sitemap is the better part of a megabyte and every slug in it matches, so this is
        // exactly the walk `findAll` makes quadratic in native memory on Android. See mapMatches.
        SITEMAP_ENTRY.mapMatches(xml) { match -> match.group(1) }.distinct()

    /**
     * Episode numbers a series page links to **for that series only**. The page also carries
     * "Popular" and "See More" widgets linking straight to other series' episodes, so an unscoped
     * scan silently inflates the count — a two-episode title picked up a third from the sidebar.
     */
    fun episodeNumbers(html: String, slug: String): Set<Int> {
        val scoped = Regex("""/watch/${Regex.escape(slug)}/episode-(\d+)""")
        return scoped.mapMatches(html) { match -> match.group(1)?.toIntOrNull() }.toSet()
    }

    private val MANIFEST = Regex("""https://[A-Za-z0-9.-]+/[A-Za-z0-9/_.-]+\.m3u8""")
    private val SITEMAP_ENTRY = Regex("""https://hentaihaven\.xxx/watch/([a-z0-9-]+)/?<""")
}
