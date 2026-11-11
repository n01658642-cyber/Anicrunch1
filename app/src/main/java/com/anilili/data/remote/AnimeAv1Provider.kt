package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AnimeAV1 (animeav1.com) — Spanish: Japanese audio with Spanish subtitles burned in, and a Latino
 * Spanish dub where one exists. The one server here that is not in English, which is why it is
 * never chosen automatically unless the viewer asked for Spanish; see
 * [com.anilili.data.ProviderCatalog.declaredLanguage].
 *
 * A SvelteKit site, so every page has a `__data.json` twin carrying the page's data as JSON:
 *
 * ```
 * GET /catalogo/__data.json?search=<title>      -> results[{slug,title}]                  (~0.3s)
 * GET /media/<slug>/__data.json                 -> media{malId, episodes[{number}]}        (~0.3s)
 * GET /media/<slug>/<n>/__data.json             -> episode{variants{SUB,DUB}}, embeds{SUB,DUB}
 * ```
 *
 * The title page carries the MyAnimeList id, so a search hit is confirmed rather than believed —
 * the same shape as [AnimeOnsenProvider]. Plain OkHttp is served with no challenge (2026-09-25).
 *
 * Of the embeds each episode lists, three resolve without a browser, tried in this order:
 *  - VOE: the page carries its config as an encoded JSON string ([AnimeAv1Parser.voeConfig]) whose
 *    `source` is an H.264 HLS master that needs no Referer — measured 0.4s for the page and 0.9s for
 *    a 2MB segment. `voe.sx` itself is a stub that redirects in script to a rotating domain.
 *  - YourUpload: `og:video`, a 302 to an MP4 that is refused without the embed's Referer.
 *  - MP4Upload, through [AllAnimeExternalExtractor]: an MP4 behind a Referer, and slow — its embed
 *    page and its file both took 15s+ to answer on 2026-09-25, which is why it is last.
 * The site's own UPNShare player encrypts its API replies; StreamTape and VidHide are left alone.
 */
internal class AnimeAv1Provider(private val client: OkHttpClient, private val json: Json) {

    private data class Entry(val slug: String, val episodes: List<Int>)

    /** AniList id to confirmed entry; stable, and a cold process re-confirms in two requests. */
    private val entries = ConcurrentHashMap<Int, Entry>()

    private val externalHosts by lazy { AllAnimeExternalExtractor(client, json) }

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val entry = entry(media)
        if (entry.episodes.isEmpty()) error("AnimeAV1 lists no episodes for this title")
        val first = entry.episodes.min()
        // The dub is only stated per episode. Asking every episode would cost a request each, so
        // the first episode's claim is trusted across the run, the way AniChan's `dubAvailable`
        // is: a dub that stops early costs a failed resolve the picker falls out of.
        val dubbed = runCatching { AnimeAv1Parser.variants(data("media/${entry.slug}/$first")) }
            .getOrNull()?.contains(DUB) == true
        val numbers = entry.episodes.toSet()
        return EpisodeAvailability(sub = numbers, dub = if (dubbed) numbers else emptySet())
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val entry = entry(media)
        if (episode !in entry.episodes) error("AnimeAV1 has no episode $episode")
        val dub = audio.equals("dub", ignoreCase = true)
        val embeds = AnimeAv1Parser.embeds(data("media/${entry.slug}/$episode"), if (dub) DUB else SUB)
        // First host that answers wins. Asking every host costs the slowest one's time, and a single
        // MP4Upload stall is longer than the whole attempt budget.
        val streams = embeds
            .filter { AnimeAv1Parser.hostRank(it.server) != Int.MAX_VALUE }
            .sortedBy { AnimeAv1Parser.hostRank(it.server) }
            .asSequence()
            .map { embed -> resolve(embed, dub) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .mapIndexed { index, stream -> stream.copy(isActive = index == 0) }
        if (streams.isEmpty()) {
            error("AnimeAV1 offered no resolvable host for episode $episode (${embeds.joinToString { it.server }})")
        }
        return SourcesResult(streams, emptyList(), null, null)
    }

    private fun resolve(embed: AnimeAv1Parser.Embed, dub: Boolean): List<StreamItem> {
        // Named on the stream as well as declared on the provider: the dub's spoken language is the
        // one thing the picker's language row can read off a resolve.
        val audio = if (dub) "Spanish" else null
        return runCatching {
            when (AnimeAv1Parser.hostRank(embed.server)) {
                RANK_VOE -> listOfNotNull(voe(embed.url, audio))
                // The extractor labels its streams for MKissa, whose hosts it was written for.
                RANK_MP4UPLOAD -> externalHosts.resolve(embed.url, "", audio.orEmpty())
                    .map { it.copy(audio = audio, quality = "MP4Upload") }
                RANK_YOURUPLOAD -> listOfNotNull(yourUpload(embed.url, audio))
                else -> emptyList()
            }
        }.onFailure { DiagnosticsLog.throwable("AnimeAV1 ${embed.server} unresolved", it) }
            .getOrDefault(emptyList())
    }

    private fun voe(url: String, audio: String?): StreamItem? {
        var page = get(url, referer = "$BASE/")
        // voe.sx answers with a script that moves to the domain of the day; follow it once.
        if (AnimeAv1Parser.voeConfig(json, page) == null) {
            val next = AnimeAv1Parser.voeRedirect(page) ?: return null
            page = get(next, referer = "$BASE/")
        }
        val source = AnimeAv1Parser.voeConfig(json, page)?.let(AnimeAv1Parser::voeSource) ?: return null
        return StreamItem(
            url = source,
            type = "hls",
            quality = "VOE",
            audio = audio,
            referer = null,
            isActive = false,
            width = null,
            height = null,
        )
    }

    private fun yourUpload(url: String, audio: String?): StreamItem? {
        val html = get(url, referer = "$BASE/")
        val file = AnimeAv1Parser.ogVideo(html) ?: return null
        // The file answers 500 without a Referer and 302s to the CDN with one.
        val referer = "https://www.yourupload.com/"
        return StreamItem(
            url = file,
            type = "mp4",
            quality = "YourUpload",
            audio = audio,
            referer = referer,
            isActive = false,
            width = null,
            height = null,
            headers = mapOf("Referer" to referer),
        )
    }

    /**
     * The entry for [media], found by title and confirmed by MyAnimeList id.
     *
     * AnimeAV1 files titles under their romaji name, so that is searched first. Without a MAL id to
     * confirm against, only an exact title match is taken: a wrong match plays another show.
     */
    private fun entry(media: Media): Entry {
        entries[media.id]?.let { return it }
        val titles = listOfNotNull(media.title.romaji, media.title.english, media.title.userPreferred)
            .map(String::trim).filter(String::isNotEmpty).distinct()
        val hits = titles.asSequence()
            .flatMap { title ->
                runCatching { AnimeAv1Parser.searchResults(data("catalogo", "search" to title)) }
                    .getOrDefault(emptyList()).asSequence()
            }
            .distinctBy(AnimeAv1Parser.Hit::slug)
            .take(CONFIRM_LIMIT)
            .toList()
        if (hits.isEmpty()) error("AnimeAV1 has no entry for this title")
        val malId = media.idMal
        val confirmed = hits.firstNotNullOfOrNull { hit ->
            if (malId == null && titles.none { AnimeAv1Parser.sameTitle(it, hit.title) }) {
                return@firstNotNullOfOrNull null
            }
            val detail = runCatching { AnimeAv1Parser.media(data("media/${hit.slug}")) }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            if (malId != null && detail.malId != malId) return@firstNotNullOfOrNull null
            Entry(hit.slug, detail.episodes)
        } ?: error("AnimeAV1 has no confirmed match for this title")
        entries[media.id] = confirmed
        return confirmed
    }

    /** The page data behind [path], un-flattened; see [AnimeAv1Parser.pageData]. */
    private fun data(path: String, vararg query: Pair<String, String>): JsonObject {
        val url = "$BASE/$path/__data.json".toHttpUrl().newBuilder()
            .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val body = get(url.toString(), referer = "$BASE/")
        return AnimeAv1Parser.pageData(json.parseToJsonElement(body))
            ?: error("AnimeAV1 returned no page data for $path")
    }

    private fun get(url: String, referer: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("AnimeAV1 HTTP ${response.code} for ${url.substringBefore('?')}")
            return response.body?.string().orEmpty()
        }
    }

    private companion object {
        const val BASE = AnimeAv1Parser.BASE
        const val SUB = "SUB"
        const val DUB = "DUB"
        const val RANK_VOE = 0
        const val RANK_YOURUPLOAD = 1
        const val RANK_MP4UPLOAD = 2
        /** Each candidate costs a title-page request inside the catalogue budget. */
        const val CONFIRM_LIMIT = 3
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Pure payload handling for [AnimeAv1Provider], kept network-free for unit tests. */
internal object AnimeAv1Parser {
    const val BASE = "https://animeav1.com"

    data class Hit(val slug: String, val title: String)
    data class Detail(val malId: Int?, val episodes: List<Int>)
    data class Embed(val server: String, val url: String)

    /**
     * The route's own data out of a SvelteKit `__data.json`.
     *
     * The file holds one node per layout level; the route's is the last one that is data. Each
     * node is "devalue"-flattened: `data` is one array, its first entry is the root object, and
     * every value in an object or array is the *index* of the real value in that same array.
     * Negative indices are devalue's markers for undefined, NaN and the like, all read as null.
     */
    fun pageData(root: JsonElement): JsonObject? {
        val nodes = (root as? JsonObject)?.get("nodes") as? JsonArray ?: return null
        val flat = nodes.lastOrNull { node ->
            (node as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNull } == "data"
        }?.let { (it as JsonObject)["data"] as? JsonArray } ?: return null
        return hydrate(flat, 0, depth = 0) as? JsonObject
    }

    private fun hydrate(flat: JsonArray, index: Int, depth: Int): JsonElement {
        if (index < 0 || index >= flat.size || depth > MAX_DEPTH) return JsonNull
        return when (val value = flat[index]) {
            is JsonObject -> JsonObject(value.mapValues { (_, ref) -> resolveRef(flat, ref, depth) })
            // A typed value such as ["Date", "2026-…"] is written with its type name first and
            // carries literals, not references.
            is JsonArray -> if ((value.firstOrNull() as? JsonPrimitive)?.isString == true) {
                value
            } else {
                JsonArray(value.map { ref -> resolveRef(flat, ref, depth) })
            }
            else -> value
        }
    }

    private fun resolveRef(flat: JsonArray, ref: JsonElement, depth: Int): JsonElement {
        val index = (ref as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return JsonNull
        return hydrate(flat, index, depth + 1)
    }

    fun searchResults(page: JsonObject): List<Hit> =
        (page["results"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val slug = obj.text("slug") ?: return@mapNotNull null
            Hit(slug, obj.text("title").orEmpty())
        }

    fun media(page: JsonObject): Detail {
        val media = page["media"] as? JsonObject ?: error("AnimeAV1 page carried no media")
        val episodes = (media["episodes"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("number") as? JsonPrimitive)?.doubleOrNull }
            // Recaps are numbered 12.5 here and there; the app's episode lists are whole numbers.
            .filter { it > 0 && it % 1.0 == 0.0 }
            .map(Double::toInt)
            .distinct()
            .sorted()
        return Detail(media.int("malId"), episodes)
    }

    /** Which of `SUB` / `DUB` an episode page says it has. */
    fun variants(page: JsonObject): Set<String> {
        val variants = (page["episode"] as? JsonObject)?.get("variants") as? JsonObject ?: return emptySet()
        return variants.filter { (_, count) -> ((count as? JsonPrimitive)?.intOrNull ?: 0) > 0 }.keys
    }

    fun embeds(page: JsonObject, category: String): List<Embed> =
        ((page["embeds"] as? JsonObject)?.get(category) as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val server = obj.text("server") ?: return@mapNotNull null
            val url = obj.text("url") ?: return@mapNotNull null
            Embed(server, url)
        }

    /**
     * Hosts the app can read, in the order they are worth trying; anything else is [Int.MAX_VALUE].
     *
     * On measurement (2026-09-25): VOE's HLS needs no header at all and answered fastest; YourUpload's
     * file answered a mid-file Range request in 1.2-1.4s; MP4Upload's took 15-18s to send a first
     * byte, long enough that a player waiting on it looks broken. It stays as the last resort.
     */
    fun hostRank(server: String): Int = when (server.trim().lowercase()) {
        "voe" -> 0
        "yourupload" -> 1
        "mp4upload" -> 2
        else -> Int.MAX_VALUE
    }

    /**
     * The player config VOE hides in `<script type="application/json">["…"]</script>`.
     *
     * The one string in it is the config JSON put through, in order: base64, reversed, every
     * character shifted up by three, base64 again, a scatter of junk tokens, then ROT13. Undone in
     * reverse here. The same scheme the public VOE extractors undo; when VOE changes it, this
     * returns null and the next host is tried.
     */
    fun voeConfig(json: Json, html: String): JsonObject? = runCatching {
        val open = html.indexOf(VOE_CONFIG_TAG)
        if (open < 0) return null
        val start = open + VOE_CONFIG_TAG.length
        val end = html.indexOf("</script>", start)
        if (end <= start) return null
        val encoded = (json.parseToJsonElement(html.substring(start, end)) as? JsonArray)
            ?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return null
        var text = rot13(encoded)
        VOE_JUNK.forEach { text = text.replace(it, "") }
        val shifted = String(java.util.Base64.getDecoder().decode(pad(text)), Charsets.ISO_8859_1)
            .map { (it.code - 3).toChar() }
            .joinToString("")
            .reversed()
        val decoded = String(java.util.Base64.getDecoder().decode(pad(shifted)), Charsets.UTF_8)
        json.parseToJsonElement(decoded) as? JsonObject
    }.getOrNull()

    fun voeSource(config: JsonObject): String? =
        config.text("source")?.takeIf { it.startsWith("http") }

    /**
     * Where voe.sx's stub page sends the browser: the first `window.location.href = '…'` that
     * assigns an absolute URL.
     *
     * Only an assignment counts. The stub reads the same property first —
     * `new URL(window.location.href)` — and taking the first quote after that read picked up a
     * query parameter's name instead of the destination.
     */
    fun voeRedirect(html: String): String? {
        var from = 0
        while (true) {
            val marker = html.indexOf(LOCATION_HREF, from)
            if (marker < 0) return null
            from = marker + LOCATION_HREF.length
            var i = from
            while (i < html.length && html[i].isWhitespace()) i++
            if (i >= html.length || html[i] != '=' || html.getOrNull(i + 1) == '=') continue
            i++
            while (i < html.length && html[i].isWhitespace()) i++
            val quote = html.getOrNull(i)?.takeIf { it == '\'' || it == '"' } ?: continue
            val end = html.indexOf(quote, i + 1)
            if (end < 0) return null
            html.substring(i + 1, end).takeIf { it.startsWith("https://") }?.let { return it }
        }
    }

    private const val LOCATION_HREF = "window.location.href"

    private const val VOE_CONFIG_TAG = "<script type=\"application/json\">"
    private val VOE_JUNK = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

    private fun rot13(value: String): String = buildString(value.length) {
        value.forEach { c ->
            append(
                when (c) {
                    in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                    in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                    else -> c
                },
            )
        }
    }

    private fun pad(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)

    fun ogVideo(html: String): String? {
        val marker = html.indexOf("og:video\"")
        if (marker < 0) return null
        val content = html.indexOf("content=\"", marker)
        if (content < 0) return null
        val start = content + "content=\"".length
        val end = html.indexOf('"', start)
        if (end <= start) return null
        return html.substring(start, end).takeIf { it.startsWith("http") && !it.endsWith("/novideo.mp4") }
    }

    fun sameTitle(left: String, right: String): Boolean =
        normalizeHanimeTitle(left) == normalizeHanimeTitle(right)

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private const val MAX_DEPTH = 64
}
