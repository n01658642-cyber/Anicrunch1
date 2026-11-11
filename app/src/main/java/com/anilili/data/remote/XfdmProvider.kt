package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 稀饭动漫 (`dm1.xfdm.pro`), a Chinese-language site serving progressive MP4.
 *
 * **This is the app's first Bangumi-keyed provider, and that is the whole reason it is here.**
 * xfdm names every cover image after the Bangumi subject it belongs to —
 * `/pic/cover/l/6f/42/571784_leRlL.jpg` is subject 571784 — so a search result can be *confirmed by
 * id* rather than scored by name. Seven of seven titles checked on 2026-09-14 matched the subject
 * id this app already carries, so a candidate whose cover id disagrees is discarded outright and
 * there is no fuzzy path for a wrong series to arrive through. The Chinese names in
 * [KonohaBangumi.search_names] are only how the page is *found*; the id is how it is *believed*.
 *
 * That matters twice over, because neither of the usual matching routes works here:
 *  - AniList carries no Chinese title at all, so there is nothing to search this site with until
 *    the Bangumi join has run. A title the join did not place is simply unavailable here.
 *  - [NativeProviderParsers.normalize] reduces to `[^a-z0-9]`, which erases CJK completely. Every
 *    shared title scorer built on it compares two empty strings for these names and would score
 *    unrelated shows as identical. None of them is used below.
 *
 * Searching by [KonohaBangumi.name_cn] alone is not enough either: sites title a page with whatever
 * name their uploader used. `search_names` carries Bangumi's aliases for exactly that reason.
 *
 * **What it costs the viewer.** The subtitles are Chinese and *burned into the picture*. ffprobe on
 * all three channels reports video and audio only — there is no text track to disable, and the
 * app's own libass path has nothing to render. A viewer who wants English attaches it over the top
 * through the OpenSubtitles panel or a local file, where [com.anilili.playback.SubtitleDelay] is
 * the trim. Measured against Crunchyroll-timed English subtitles across 22 episodes of two shows,
 * the two share a timeline: 32 confident measurements, median +0.25s, every one inside ±1.5s, and
 * no drift within an episode — so the default offset of zero is honest and the manual nudge is
 * enough. This provider therefore publishes no subtitles of its own rather than a Chinese track it
 * cannot actually produce.
 *
 * **What it is good at.** The stream needs no headers: the MP4 answers `206 Partial Content` with
 * no Referer and no User-Agent, so [StreamItem.referer] is left null for the same reason
 * [AnimeHeavenProvider] leaves it null — it keeps a cast off `StreamCorsProxy` and lets an external
 * player, which cannot send headers, route the file. `player_aaaa` carries `encrypt: 0` and the URL
 * in the page HTML, so resolving is three plain GETs and never a WebView.
 *
 * **Its catalogue is Japanese television animation and nothing else.** Searching it for 凡人修仙传,
 * one of the largest donghua there is, returns zero results while the same sweep on a Japanese
 * title returns one; donghua live on other Chinese sites entirely. There is no dub: the channels
 * are mirrors, not audio tracks, so nothing is published under [EpisodeAvailability.dub].
 */
internal class XfdmProvider(private val client: OkHttpClient, private val json: Json) {
    private data class Catalog(val subjectPath: String, val episodes: List<XfdmParser.Episode>)

    private val catalogs = boundedMap<Int, Catalog>(64)
    private val misses = boundedMap<Int, Long>(128)

    /** The site's own URL for a title, the half of a lookup worth keeping. See [cachedSubjectPath]. */
    private val subjectPaths = boundedMap<Int, String>(256)

    /** (title, episode) pairs whose shipped URL a player has already refused. See [distrustShipped]. */
    private val distrusted = boundedMap<Pair<Int, Int>, Long>(256)

    /**
     * Which episodes this site carries, from the precomputed file when there is one.
     *
     * The crawl writes an entry per episode it resolved, so the keys of [KonohaLiliSources.episodes]
     * *are* the answer — asking the site would produce the same set at the cost of a suggest call
     * and a subject page, measured at 984ms and 505ms on a phone. Without this the shipped URLs
     * still save the watch page but the catalogue pays full price, which is most of the wait.
     *
     * A record older than the newest episode simply reports fewer episodes, and an airing title is
     * re-crawled nightly, so the window where that is wrong is a day at most.
     */
    fun episodeAvailability(
        media: Media,
        bangumi: KonohaBangumi?,
        shipped: KonohaLiliSources?,
    ): EpisodeAvailability {
        shipped?.episodes?.keys
            ?.mapNotNull(String::toIntOrNull)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return EpisodeAvailability(sub = it.toSet(), dub = emptySet()) }
        return EpisodeAvailability(
            sub = catalog(media, bangumi).episodes.map { it.number }.toSet(),
            dub = emptySet(),
        )
    }

    /**
     * @param audio unused: the channels are mirrors of one file, and there is no dub.
     * @param shipped URLs the metadata build already resolved, when it has crawled this title.
     *   A hit here skips the site entirely — no search, no subject page, no watch page — which is
     *   the difference between ~1,750ms and a file the app usually already holds.
     */
    fun sources(
        media: Media,
        bangumi: KonohaBangumi?,
        shipped: KonohaLiliSources?,
        audio: String,
        episode: Int,
    ): SourcesResult {
        shippedUrl(media.id, shipped, episode)?.let { return result(listOf(it)) }

        val catalog = catalog(media, bangumi)
        val channels = catalog.episodes.filter { it.number == episode }
        if (channels.isEmpty()) error("XFDM episode $episode is not in the catalog")

        val resolved = resolvePreferredMirror(channels)
        if (resolved.isEmpty()) error("XFDM episode $episode returned no video source")
        return result(resolved)
    }

    /**
     * The precomputed URL for this episode, unless playback has already rejected it.
     *
     * A shipped URL is believed until a player says otherwise, at which point [distrustShipped]
     * takes it out of circulation for this process and the ordinary live resolve runs instead.
     * That is the whole healing story: the site restages a title at the year rollover and the file
     * on the CDN goes stale, and the cost of noticing is one failed play rather than a dead title.
     */
    private fun shippedUrl(mediaId: Int, shipped: KonohaLiliSources?, episode: Int): String? {
        if (shipped == null || distrusted.containsKey(mediaId to episode)) return null
        return shipped.urlFor(episode)
    }

    /**
     * Records that a shipped URL failed to play, so this episode resolves live from now on.
     *
     * Keyed per episode rather than per title: a season being refiled breaks every episode, but
     * one file going missing should not cost the other twenty-five their head start.
     */
    fun distrustShipped(mediaId: Int, episode: Int) {
        distrusted[mediaId to episode] = System.currentTimeMillis()
    }

    private fun result(urls: List<String>): SourcesResult =
        SourcesResult(
            streams = urls.mapIndexed { index, url ->
                StreamItem(
                    url = url,
                    type = if (url.contains(".m3u8", ignoreCase = true)) "hls" else "mp4",
                    quality = mirrorLabel(url, index),
                    audio = null,
                    // Deliberately null; see the class comment. Naming a referer the CDN never
                    // asked for would route every cast through the proxy for nothing.
                    referer = null,
                    isActive = index == 0,
                    width = null,
                    height = null,
                    avoidCronet = true,
                )
            },
            // Burned into the picture, so there is nothing to publish. See the class comment.
            subtitles = emptyList(),
            skip = null,
            download = null,
        )

    /**
     * One playable URL, preferring a mirror that is not behind the slow origin.
     *
     * Every channel is the *same file* — channels 1 and 2 of the title measured both report
     * `Content-Length: 368913834` and channels 1 and 3 report identical durations — so fetching
     * more than one buys no quality, only a different route to the same bytes. The routes are not
     * equal: `apn.moedot.net` redirects into China Unicom's consumer cloud and sustained
     * 10.2 Mbit/s from a European host, while `play.xfvod.pro` is Cloudflare-fronted and sustained
     * 28.7 Mbit/s for the same byte range. So the first channel is resolved, and a second is tried
     * only when the first landed on a host measured slow — typically one request, never more than
     * [MAX_MIRROR_PROBES].
     */
    private fun resolvePreferredMirror(channels: List<XfdmParser.Episode>): List<String> {
        val found = mutableListOf<String>()
        var unanswered: Throwable? = null
        for (channel in channels.take(MAX_MIRROR_PROBES)) {
            val url = runCatching { XfdmParser.videoUrl(get("$BASE${channel.watchPath}", "$BASE/")) }
                .onFailure { unanswered = it }
                .getOrNull()
                ?: continue
            found += url
            if (!isSlowHost(url)) break
        }
        if (found.isEmpty()) unanswered?.let { throw it }
        // A fast mirror found second still plays first; the slow one stays as manual failover.
        return found.sortedBy { isSlowHost(it) }
    }

    private fun isSlowHost(url: String): Boolean =
        runCatching { URI(url).host.orEmpty() }.getOrDefault("").lowercase() in SLOW_HOSTS

    private fun catalog(media: Media, bangumi: KonohaBangumi?): Catalog {
        catalogs[media.id]?.let { return it }
        misses[media.id]?.let { seenAt ->
            if (System.currentTimeMillis() - seenAt < MISS_TTL_MS) {
                error("XFDM has no entry for this title")
            }
            misses.remove(media.id)
        }
        val built = build(media, bangumi)
        if (built == null) {
            misses[media.id] = System.currentTimeMillis()
            error("XFDM has no entry for this title")
        }
        catalogs[media.id] = built
        subjectPaths[media.id] = built.subjectPath
        return built
    }

    /**
     * The subject path a previous lookup worked out, for [AnivexaClient] to persist and hand back.
     *
     * Finding it is the expensive half — a suggest call, and a search page behind it — and it is
     * also the stable half: it is derived from a Bangumi id, and this site's URL for a title does
     * not move. The episode list is the opposite on both counts, so only the path is kept.
     * [CatalogIdentityStore] explains the split in full.
     */
    fun cachedSubjectPath(mediaId: Int): String? = subjectPaths[mediaId]

    /** Plants a path a previous session learned, so this one can skip straight to the subject page. */
    fun seedSubjectPath(mediaId: Int, path: String) {
        subjectPaths[mediaId] = path
    }

    /**
     * Drops a seeded path the site has since moved, along with anything built on it.
     *
     * Without the catalogue eviction the retry would find the failed build still cached and fail
     * again identically, which is the failure mode the seeding is supposed to be safe against.
     */
    fun forgetSubjectPath(mediaId: Int) {
        subjectPaths.remove(mediaId)
        catalogs.remove(mediaId)
        misses.remove(mediaId)
    }

    /**
     * Returns null only for a title this site was asked about and did not have.
     *
     * A lookup that never got an answer must be rethrown rather than returned as null, or [catalog]
     * remembers it as "no entry" for six hours and the provider disappears from the title until the
     * process restarts — the trap [AnimeHeavenProvider.build] and [AnimeDekhoProvider.build] both
     * document, and the one that costs a whole provider on a slow television.
     *
     * A title with no Bangumi subject is a different thing and is *not* a transport failure: there
     * is nothing to search with and nothing to confirm against, so it is an honest miss.
     */
    private fun build(media: Media, bangumi: KonohaBangumi?): Catalog? {
        val subjectId = bangumi?.id ?: return null
        var unanswered: Throwable? = null

        // A path a previous session worked out skips the search entirely, which is the whole
        // saving: on this device the suggest call cost 796ms against the subject page's 500ms, so
        // a known path roughly halves a cold lookup. A path the site has since moved costs one
        // wasted request and then the ordinary search below, so it can be trusted cheaply.
        subjectPaths[media.id]?.let { known ->
            val page = runCatching { get("$BASE$known", "$BASE/") }
                .onFailure { unanswered = it }
                .getOrNull()
            val episodes = page?.let(XfdmParser::parseEpisodes).orEmpty()
            if (episodes.isNotEmpty()) return Catalog(known, episodes)
            subjectPaths.remove(media.id)
        }

        val queries = bangumi.search_names.filter { it.isNotBlank() }.take(MAX_SEARCH_QUERIES)
        if (queries.isEmpty()) {
            unanswered?.let { throw it }
            return null
        }

        for (query in queries) {
            // The site's own search box calls this, and it answers the same question the search
            // page does in 290 bytes rather than 33KB: the entry's id and a cover URL carrying the
            // Bangumi id. That is worth far more than the ~170ms it saves. Parsing 33KB of HTML
            // with regexes is what a television is worst at — see [[tv-catalogue-parse-cost]],
            // where the same page costs a TV box thirty to sixty times what it costs a phone, and
            // late providers time out of the catalogue entirely because of it.
            val path = runCatching {
                XfdmParser.subjectPathForBangumiId(
                    json.parseToJsonElement(
                        get("$BASE/index.php/ajax/suggest?mid=1&wd=${enc(query)}&limit=$SUGGEST_LIMIT", "$BASE/"),
                    ) as? JsonObject,
                    subjectId,
                )
            }.onFailure { unanswered = it }.getOrNull()
            // The full search page is kept as the fallback because the suggest route is one
            // undocumented endpoint on a site that owes us nothing, and the HTML path is already
            // written and tested. It confirms by the same cover id, so neither can pick a wrong
            // series, and only the cost differs.
                ?: runCatching { get("$BASE/search.html?wd=${enc(query)}", "$BASE/") }
                    .onFailure { unanswered = it }
                    .getOrNull()
                    ?.let { XfdmParser.subjectPathInSearchPage(it, subjectId) }
                ?: continue
            val page = runCatching { get("$BASE$path", "$BASE/") }
                .onFailure { unanswered = it }
                .getOrNull()
                ?: continue
            val episodes = XfdmParser.parseEpisodes(page)
            if (episodes.isNotEmpty()) return Catalog(path, episodes)
        }
        unanswered?.let { throw it }
        return null
    }

    /**
     * The mirror's own hostname, which is the only thing separating these entries.
     *
     * They are one encode on different hosts, so printing a resolution would print the same number
     * twice, and claiming one this code never measured would be worse: height is left null so the
     * player reports what it actually opened.
     */
    private fun mirrorLabel(url: String, index: Int): String {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return host.substringBefore('.').takeIf { it.isNotBlank() }
            ?.let { "Mirror ${index + 1} ($it)" }
            ?: "Mirror ${index + 1}"
    }

    private fun get(url: String, referer: String?): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .apply { referer?.let { header("Referer", it) } }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("XFDM HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
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
        private const val BASE = "https://dm1.xfdm.pro"
        private const val MAX_SEARCH_QUERIES = 3
        private const val MAX_MIRROR_PROBES = 2
        /**
         * Ten truncates, and silently. 进击的巨人 has thirteen entries and the endpoint returns the
         * later seasons first, so season one — the subject actually being looked for — fell off the
         * end and the title read as absent. The payload is a couple of hundred bytes either way.
         */
        private const val SUGGEST_LIMIT = 50
        private const val MISS_TTL_MS = 6L * 60L * 60L * 1000L

        /** Measured 10.2 Mbit/s against 28.7 on the Cloudflare mirror; see [resolvePreferredMirror]. */
        private val SLOW_HOSTS = setOf("apn.moedot.net")

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * Pure HTML parsing for [XfdmProvider], kept network-free for unit tests.
 *
 * Patterns are compiled once and held for the reason [NativeProviderParsers] spells out: an inline
 * `Regex` pays a `Pattern.compile` on every call, which is unnoticeable on a phone and tens of
 * times slower on a television — the difference between a provider appearing in the picker and
 * timing out of it. See [[tv-catalogue-parse-cost]].
 */
internal object XfdmParser {
    /** One episode on one channel. [watchPath] is site-absolute, e.g. `/watch/3500/2/1.html`. */
    data class Episode(val number: Int, val channel: Int, val watchPath: String)

    /**
     * A search card, as the cover id and the subject link that sit inside it.
     *
     * Cards are read as slices rather than with one lazy pattern across the page, so a card that
     * renders without a cover cannot borrow the *next* card's id and claim the wrong series.
     */
    private val cardStart = Regex(
        """<div\b[^>]*\bclass\s*=\s*['"][^'"]*\bpublic-list-box\b[^'"]*\bsearch-box\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `)` is excluded along with the quotes because the first of a card's two covers is a CSS
     * `background-image: ... url(https://.../515594_Uj20c.jpg)`, unquoted.
     */
    private val coverId = Regex(
        """/pic/cover/[^"'\s)]*?/(\d+)_[^"'\s)]*\.(?:jpg|jpeg|png|webp)""",
        RegexOption.IGNORE_CASE,
    )
    private val subjectHref = Regex("""href\s*=\s*['"](/bangumi/\d+\.html)['"]""", RegexOption.IGNORE_CASE)
    private val watchAnchor = Regex(
        """<a\b[^>]*\bhref\s*=\s*['"](/watch/\d+/(\d+)/\d+\.html)['"][^>]*>([\s\S]*?)</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val episodeNumber = Regex("""第\s*0*(\d+)\s*[话話集]""")
    private val playerConfig = Regex("""player_\w+\s*=\s*(\{[\s\S]*?\})\s*[;<]""", RegexOption.IGNORE_CASE)
    private val playerUrl = Regex("""["']url["']\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /**
     * The subject page for [bangumiId], or null when this page does not offer that subject.
     *
     * The cover filename is the site's own Bangumi id. Matching on it is what makes this provider
     * exact: a search for a Chinese name routinely returns neighbouring seasons of the same
     * franchise — 关于我转生变成史莱姆这档事 第四季 returns the third season and a side story beside it —
     * and picking by name would have to guess between them.
     *
     * Only the *first* cover id in a card is read, and that is load-bearing rather than tidiness:
     * the final card's slice runs to the end of the document and swallows the page's "热播影片"
     * rail, which carries a dozen unrelated covers. Reading them all would let the last result on
     * any search page claim to be almost any subject. The first is always the card's own, because
     * it is the `.cover` backdrop the site paints behind the entry before it draws the thumbnail.
     */
    fun subjectPathInSearchPage(html: String, bangumiId: Int): String? {
        val needle = bangumiId.toString()
        return cards(html).firstNotNullOfOrNull { card ->
            if (coverId.find(card)?.groupValues?.get(1) != needle) return@firstNotNullOfOrNull null
            subjectHref.find(card)?.groupValues?.get(1)
        }
    }

    /**
     * The subject path for [bangumiId] out of an `ajax/suggest` payload, or null.
     *
     * Each row carries the site's own entry id and a `pic` whose filename is the Bangumi id — the
     * same pairing the search page spells out in 33KB of markup, here in a couple of hundred bytes:
     *
     * ```json
     * {"list":[{"id":3500,"name":"在超市后门吸烟的二人",
     *           "pic":"https://img2.xfmanga.top/pic/cover/l/6f/42/571784_leRlL.jpg"}]}
     * ```
     *
     * The path is built from `id` rather than read from a link, because this payload has no links
     * in it; `/bangumi/{id}.html` is the shape every row on the search page resolves to.
     */
    fun subjectPathForBangumiId(root: JsonObject?, bangumiId: Int): String? {
        val needle = bangumiId.toString()
        val rows = root?.get("list") as? JsonArray ?: return null
        return rows.mapNotNull { it as? JsonObject }.firstNotNullOfOrNull { row ->
            val pic = (row["pic"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (coverId.find(pic)?.groupValues?.get(1) != needle) return@firstNotNullOfOrNull null
            (row["id"] as? JsonPrimitive)?.intOrNull?.let { "/bangumi/$it.html" }
        }
    }

    /**
     * Every episode on the subject page, across every channel, ascending.
     *
     * The number is read from the anchor's own label (`第01集`) rather than the last path segment,
     * because the segment is the site's ordinal within the channel: a channel that opens with a
     * special, or one that is missing an episode, would otherwise shift everything after it. The
     * channel *is* taken from the path, where it is unambiguous.
     */
    fun parseEpisodes(html: String): List<Episode> = watchAnchor.mapMatches(html) { match ->
        val path = match.group(1).orEmpty()
        val channel = match.group(2).orEmpty()
        val label = match.group(3).orEmpty()
        val number = episodeNumber.find(NativeProviderParsers.stripTags(label))
            ?.groupValues?.get(1)
            ?.toIntOrNull()
            ?: return@mapMatches null
        Episode(number = number, channel = channel.toIntOrNull() ?: 0, watchPath = path)
    }.distinctBy { it.channel to it.number }
        .sortedWith(compareBy({ it.number }, { it.channel }))

    /**
     * The file named by the watch page's `player_aaaa` blob, or null.
     *
     * The blob is MacCMS's own player state and on this site carries `encrypt: 0`, meaning the URL
     * is in the page as written and no player iframe has to be executed to reach it. The URL is
     * pulled with a pattern rather than a JSON parse because the blob also carries a free-text
     * `note` and a nested `vod_data`, and the app has no reason to model either.
     */
    fun videoUrl(html: String): String? {
        val config = playerConfig.find(html)?.groupValues?.get(1) ?: return null
        val raw = playerUrl.find(config)?.groupValues?.get(1) ?: return null
        // MacCMS emits the URL JSON-escaped (`https:\/\/...`).
        return raw.replace("\\/", "/").replace("\\\\", "\\").trim().takeIf { it.startsWith("http") }
    }

    /** [html] sliced one card per `public-list-box search-box`, each running to the next. */
    private fun cards(html: String): List<String> {
        val starts = cardStart.findAll(html).map { it.range.first }.toList()
        return starts.mapIndexed { index, start ->
            html.substring(start, starts.getOrNull(index + 1) ?: html.length)
        }
    }
}
