package com.anilili.data.remote

import android.os.SystemClock
import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.util.cached
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * When a provider must stop, whatever it has not answered yet.
 *
 * Every caller already has a budget — 15s for a catalogue pass, 8s for a playback resolve — and
 * spends it as a coroutine timeout around `runInterruptible`. That cancels by interrupting the
 * worker thread, and OkHttp never looks at the interrupt flag, so the budget could not reach inside
 * a blocking provider at all. Measured on a viewer's device: five AnimeKai mirrors all hung, the
 * search ran 15 requests at 15s apiece, and the pass reported `AnimeKai match not found` after 180
 * seconds against a 15 second budget — never logging a timeout, because `withTimeoutOrNull` cannot
 * return until the block it wraps does.
 *
 * So the loops ask this between requests. The interrupt flag is the caller's real deadline and is
 * what normally fires; the wall clock is the backstop for the blocking calls that clear the flag on
 * their way out.
 */
internal class ProviderDeadline(budgetMs: Long, private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val expiresAt = now() + budgetMs

    fun expired(): Boolean = Thread.currentThread().isInterrupted || now() >= expiresAt
}

internal class AnimeKaiProvider(
    private val client: OkHttpClient,
    private val mirrors: List<String> = MIRRORS,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class Match(val base: String, val slug: String)
    private data class Page(val match: Match, val html: String)

    private val matches = ConcurrentHashMap<Int, Match>()

    /**
     * Where the next cold search starts, as an index into [mirrors].
     *
     * A search no longer walks all five mirrors, so the entry point has to move: left pinned at the
     * first one, a device whose first two mirrors were unreachable would never reach the third,
     * however many times it asked. A run that settles on a mirror parks here; a run that finds
     * nothing steps past the ones it tried.
     */
    @Volatile private var mirrorCursor = 0

    private val activeBase: String get() = mirrors[mirrorCursor]

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val deadline = ProviderDeadline(BUDGET_MS, now)
        val match = matches[media.id] ?: resolveMatch(media, deadline).also { matches[media.id] = it }
        val html = getKai("${match.base}/watch/${match.slug}", "${match.base}/")
        return NativeProviderParsers.dataAudioEpisodes(html).also {
            if (it.sub.isEmpty() && it.dub.isEmpty()) error("AnimeKai returned no episode catalog")
        }
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val deadline = ProviderDeadline(BUDGET_MS, now)
        val match = matches[media.id] ?: resolveMatch(media, deadline).also { matches[media.id] = it }
        val page = episodePage(match, episode, deadline)
        matches[media.id] = page.match
        val servers = AnimeKaiParser.parseServers(page.html)
        if (servers.isEmpty()) error("AnimeKai episode $episode has no servers")

        val preferredLanguages = if (audio == "dub") listOf("dub") else listOf("hsub", "sub", "softsub")
        val pool = servers.filter { it.language in preferredLanguages }
            .sortedByDescending { serverRank(it.videoUrl) }
        if (pool.isEmpty()) error("AnimeKai episode $episode has no ${audio.uppercase()} servers")
        val groupSubtitles = pool.flatMap { AnimeKaiParser.embedSubtitles(it.videoUrl) }.distinctBy(SubtitleItem::url)

        for (server in pool.take(MAX_EMBED_ATTEMPTS)) {
            // Each attempt is two or three more requests, so this loop is as able to outlive the
            // caller as the mirror walks are.
            if (deadline.expired()) break
            val resolved = runCatching { resolveEmbed(server, page.match.base, audio, groupSubtitles) }.getOrNull()
            if (resolved != null) return resolved
        }

        val embeds = pool.map { server ->
            StreamItem(
                url = NativeProviderParsers.absoluteUrl(page.match.base, server.videoUrl),
                type = "embed",
                quality = "AnimeKai ${server.name}",
                audio = audio,
                referer = "${page.match.base}/",
                isActive = false,
                width = null,
                height = null,
            )
        }.distinctBy(StreamItem::url).mapIndexed { index, stream -> stream.copy(isActive = index == 0) }
        if (embeds.isEmpty()) error("AnimeKai episode $episode has no playable sources")
        return SourcesResult(embeds, groupSubtitles, null, null)
    }

    /**
     * Finds the site's slug for [media], within [deadline].
     *
     * This used to be five mirrors by three titles of unconditional search, plus a MAL probe for
     * every one of seven ranked cards on every mirror — fifty requests to answer one question, all
     * sequential, and on a device where the mirrors hang that is minutes. Three things bound it now:
     * only [MATCH_MIRRORS] mirrors are tried per call (the cursor moves on so the rest are still
     * reachable next time), the later titles are skipped once a name match is confident enough to
     * be the answer, and the MAL probes are capped per mirror.
     *
     * What none of that may do is make a wrong match likelier, so the probe cap is per mirror
     * rather than per call — starving the second mirror of probes would leave it choosing on name
     * score alone, which is how a search lands on the wrong season. The title short-circuit is
     * likewise only taken when there is no MAL id to confirm against: with one, a later title can
     * still surface the card that the earlier ones missed.
     */
    private fun resolveMatch(media: Media, deadline: ProviderDeadline): Match {
        val titles = titles(media)
        for (offset in 0 until minOf(MATCH_MIRRORS, mirrors.size)) {
            if (deadline.expired()) break
            val base = mirrors[(mirrorCursor + offset) % mirrors.size]
            val seen = mutableSetOf<String>()
            var probes = 0
            var best: Pair<String, Double>? = null

            for (title in titles) {
                if (deadline.expired()) break
                val cards = runCatching { search(base, title) }.getOrDefault(emptyList())
                    .filter { seen.add(it.slug) }
                val ranked = cards.sortedByDescending { card -> score(media, card.title) }.take(RANKED_CARDS)

                for (card in ranked) {
                    val cardScore = score(media, card.title)
                    if (best == null || cardScore > best.second) best = card.slug to cardScore
                    if (media.idMal == null || probes >= MAL_PROBES_PER_MIRROR) continue
                    if (deadline.expired()) break
                    probes++
                    val watch = runCatching { getKai("$base/watch/${card.slug}", "$base/") }.getOrNull()
                    if (watch != null && AnimeKaiParser.malId(watch) == media.idMal) {
                        return settle(base, card.slug)
                    }
                }

                // Nothing left to learn: without a MAL id this score is already the answer, and the
                // remaining titles would search the same site for the same series.
                if (media.idMal == null && (best?.second ?: 0.0) >= MIN_MATCH_SCORE) break
            }

            best?.takeIf { it.second >= MIN_MATCH_SCORE }?.let { return settle(base, it.first) }
        }
        // The mirrors this call reached had nothing, so the next one starts past them.
        mirrorCursor = (mirrorCursor + MATCH_MIRRORS) % mirrors.size
        if (deadline.expired()) error("AnimeKai match search ran out of time")
        error("AnimeKai match not found")
    }

    /** Records the mirror that answered, so the next cold search starts there. */
    private fun settle(base: String, slug: String): Match {
        mirrors.indexOf(base).takeIf { it >= 0 }?.let { mirrorCursor = it }
        return Match(base, slug)
    }

    private fun search(base: String, query: String): List<AnimeKaiParser.Card> {
        val url = "$base/browser".toHttpUrl().newBuilder().addQueryParameter("keyword", query).build()
        return AnimeKaiParser.parseCards(getKai(url.toString(), "$base/"))
    }

    private fun episodePage(match: Match, episode: Int, deadline: ProviderDeadline): Page {
        val bases = (listOf(match.base, activeBase) + mirrors).distinct()
        for (base in bases) {
            // Every mirror here can hang for the client's full call timeout, and the slug is
            // already known, so there is nothing to gain from walking the rest past the deadline.
            if (deadline.expired()) break
            val url = "$base/watch/${match.slug}/ep-$episode"
            val html = runCatching { getKai(url, "$base/watch/${match.slug}") }.getOrNull()
            if (!html.isNullOrBlank() && AnimeKaiParser.parseServers(html).isNotEmpty()) {
                return Page(settle(base, match.slug), html)
            }
        }
        if (deadline.expired()) error("AnimeKai episode page search ran out of time")
        error("AnimeKai episode page is unavailable")
    }

    private fun resolveEmbed(
        server: AnimeKaiParser.Server,
        base: String,
        audio: String,
        groupSubtitles: List<SubtitleItem>,
    ): SourcesResult? {
        var embed = NativeProviderParsers.absoluteUrl(base, server.videoUrl)
        if (Regex.cached("""anikai\.(?:to|cc)/iframe/""", RegexOption.IGNORE_CASE).containsMatchIn(embed)) {
            val wrapper = getKai(embed, "$base/")
            val nested = Regex.cached("""<iframe\b[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(wrapper)?.groupValues?.get(1)
            if (!nested.isNullOrBlank()) embed = NativeProviderParsers.absoluteUrl(embed, nested)
        }

        val referer = "${origin(embed)}/"
        val html = getEmbed(embed, "$base/")
        val hls = AnimeKaiParser.findHls(html)?.let { NativeProviderParsers.absoluteUrl(embed, it) }
            ?: return null
        if (!isHls(hls, referer)) return null

        val subtitles = (
            AnimeKaiParser.embedSubtitles(embed) +
                AnimeKaiParser.pageSubtitles(html) +
                groupSubtitles
            ).distinctBy(SubtitleItem::url)
        val stream = StreamItem(
            url = hls,
            type = "hls",
            quality = "AnimeKai ${server.name}",
            audio = audio,
            referer = referer,
            isActive = true,
            width = null,
            height = null,
        )
        return SourcesResult(listOf(stream), subtitles, null, null)
    }

    private fun getKai(url: String, referer: String): String = execute(
        Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get().build(),
    )

    private fun getEmbed(url: String, referer: String): String = execute(
        Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "text/html,*/*")
            .get().build(),
    )

    private fun isHls(url: String, referer: String): Boolean = runCatching {
        val origin = origin(url)
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", origin)
            .get().build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful && response.body?.string().orEmpty().trimStart().startsWith("#EXTM3U")
        }
    }.getOrDefault(false)

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("AnimeKai HTTP ${response.code}")
        body
    }

    private fun score(media: Media, candidate: String): Double = titles(media).maxOfOrNull { title ->
        NativeProviderParsers.titleSelectionScore(title, candidate)
    } ?: 0.0

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter(String::isNotBlank).distinct()

    private fun serverRank(url: String): Int = when {
        Regex.cached("""vivibebe\.|bibiemb\.|vibeplayer\.""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> 3
        Regex.cached("""megaup|4spromax""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> 1
        else -> 2
    }

    private fun origin(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.authority}" }
    }.getOrDefault(url.substringBefore('/', ""))

    companion object {
        private val MIRRORS = listOf(
            "https://www3.anikai.cc",
            "https://www1.anikai.cc",
            "https://www2.anikai.cc",
            "https://www4.anikai.cc",
            "https://anikai.cc",
        )

        /**
         * The wall-clock backstop, for when the caller's interrupt is swallowed.
         *
         * Not the primary bound and deliberately loose: a healthy catalogue lookup here measured
         * 4.3s and a stream resolve 587ms, so twenty seconds cannot cut short a run that is
         * working, while the 180 second stall this exists to stop is nine times over it.
         */
        private const val BUDGET_MS = 20_000L

        /**
         * Mirrors per cold search. These are alternate hostnames for one site: when the first two
         * are unreachable the rest almost always are too — on the device that produced the 180
         * second stall, all five were — so the fourth and fifth attempt buy far less than they cost
         * a viewer who is waiting.
         */
        private const val MATCH_MIRRORS = 2

        /** Search results ranked by name before any are opened. */
        private const val RANKED_CARDS = 7

        /**
         * Cards opened to check their MAL id, per mirror. The ranking puts the intended season
         * first, so this is headroom for a near-tie rather than a search; it was previously every
         * ranked card, which is seven page loads per mirror to confirm one slug.
         */
        private const val MAL_PROBES_PER_MIRROR = 4

        /** Embeds tried before falling back to handing the player the iframes themselves. */
        private const val MAX_EMBED_ATTEMPTS = 8

        /** Below this a name match is too weak to accept without a MAL id confirming it. */
        private const val MIN_MATCH_SCORE = 0.35

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

internal object AnimeKaiParser {
    data class Card(val slug: String, val title: String)
    data class Server(val language: String, val name: String, val videoUrl: String)

    fun parseCards(html: String): List<Card> {
        val starts = Regex.cached("""<div\b[^>]*class=["'][^"']*\baitem\b[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html).toList()
        return starts.mapNotNull { match ->
            val end = starts.firstOrNull { it.range.first > match.range.first }?.range?.first
                ?: (match.range.first + 5_000).coerceAtMost(html.length)
            val block = html.substring(match.range.first, end)
            val href = Regex.cached("""href=["'](/watch/[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val slug = Regex.cached("""/watch/([a-z0-9][a-z0-9-]*)""", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val titleTag = Regex.cached("""<a\b[^>]*class=["'][^"']*\btitle\b[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
                .find(block)?.value.orEmpty()
            val title = NativeProviderParsers.attr(titleTag, "data-en")
                .ifBlank { NativeProviderParsers.attr(titleTag, "title") }
                .ifBlank {
                    Regex.cached("""<a\b[^>]*class=["'][^"']*\btitle\b[^"']*["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                        .find(block)?.groupValues?.get(1)?.let(NativeProviderParsers::stripTags).orEmpty()
                }
                .ifBlank { slug.replace('-', ' ') }
            Card(slug, title)
        }.distinctBy(Card::slug)
    }

    fun malId(html: String): Int? = (
        Regex.cached("""myanimelist\.net/anime/(\d+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex.cached("""data-mal(?:-id)?=["'](\d+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
        )?.toIntOrNull()

    fun parseServers(html: String): List<Server> {
        val groupTags = Regex.cached("""<[a-z0-9]+\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
            .filter { tag ->
                val classes = NativeProviderParsers.attr(tag.value, "class")
                Regex.cached("""(?:^|\s)server-items(?:\s|$)""", RegexOption.IGNORE_CASE).containsMatchIn(classes) &&
                    NativeProviderParsers.attr(tag.value, "data-id").isNotBlank()
            }.toList()
        val servers = mutableListOf<Server>()
        groupTags.forEachIndexed { index, group ->
            val language = NativeProviderParsers.attr(group.value, "data-id").lowercase()
            val end = groupTags.getOrNull(index + 1)?.range?.first ?: html.length
            val block = html.substring(group.range.last + 1, end)
            Regex.cached("""<(?:span|div|li|a)\b[^>]*\bdata-video\s*=\s*(["']).*?\1[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(block).forEach { tag ->
                    val video = NativeProviderParsers.attr(tag.value, "data-video")
                    if (video.isBlank()) return@forEach
                    val classes = NativeProviderParsers.attr(tag.value, "class")
                    if (!classes.contains("server", true)) return@forEach
                    val name = NativeProviderParsers.attr(tag.value, "data-name")
                        .ifBlank { NativeProviderParsers.attr(tag.value, "title") }
                        .ifBlank { "Server" }
                    servers += Server(language, name, video)
                }
        }
        return servers.distinctBy { "${it.language}:${it.videoUrl}" }
    }

    fun embedSubtitles(embed: String): List<SubtitleItem> {
        val url = queryValue(embed, listOf("sub", "caption_1", "c1_file", "sub_file", "subtitle"))
            ?: return emptyList()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return emptyList()
        val label = queryValue(embed, listOf("sub_1", "c1_label", "caption_label")) ?: "English"
        return listOf(SubtitleItem(url, label, language(label)))
    }

    fun pageSubtitles(html: String): List<SubtitleItem> = Regex.cached(
        """file\s*:\s*["']([^"']+\.vtt[^"']*)["'][\s\S]{0,100}?label\s*:\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    ).findAll(html).map { match ->
        val url = decodeJavascript(match.groupValues[1])
        val label = NativeProviderParsers.decodeEntities(match.groupValues[2]).ifBlank { "Subtitle" }
        SubtitleItem(url, label, language(label))
    }.distinctBy(SubtitleItem::url).toList()

    fun findHls(html: String): String? {
        val patterns = listOf(
            Regex.cached("""const\s+src\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex.cached("""(?:["']file["']|file)\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { regex -> regex.find(html)?.groupValues?.get(1) }
            ?.let(::decodeJavascript)
            ?: NativeProviderParsers.hlsUrls(html).firstOrNull()
    }

    private fun queryValue(url: String, names: List<String>): String? {
        names.forEach { name ->
            val raw = Regex("""[?&]${Regex.escape(name)}=([^&]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)
            if (raw != null) {
                return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
            }
        }
        return null
    }

    private fun decodeJavascript(value: String): String = NativeProviderParsers.decodeEntities(value)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\/", "/")

    private fun language(label: String): String = when {
        label.contains("english", true) -> "en"
        label.contains("spanish", true) -> "es"
        label.contains("french", true) -> "fr"
        label.contains("german", true) -> "de"
        label.contains("portuguese", true) -> "pt"
        else -> "und"
    }
}
