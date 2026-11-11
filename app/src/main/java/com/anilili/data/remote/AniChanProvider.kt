package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AniChan (anichan.to) — JSON throughout and keyed directly by AniList id, like [AniBdProvider],
 * so it needs no search request and no title matching: `media.id` is the lookup.
 *
 * ```
 * GET  /api/watch/episodes?anilistId=154587      -> {"episodes":28,"dubAvailable":true}   (~200ms)
 * POST /api/watch/session   {"token":""}         -> Set-Cookie: anichan_ws=…  Max-Age=7200
 * GET  /api/watch/servers?anilistId=&ep=&category= -> {"servers":[…]}                      (~700ms)
 * ```
 *
 * Its own server answers a **1080p** h264/AAC master with soft subtitles in a dozen languages, and
 * every URL measured — master, variant and segments — returned 206 with
 * `Access-Control-Allow-Origin: *` and no Referer, so [StreamItem.referer] is left null for the
 * same reason [AnimeHeavenProvider] leaves it null: that is what keeps a cast off `StreamCorsProxy`.
 *
 * Segments arrive as `.ts.png` / `.ts.webp` with an image content type. That is camouflage, not a
 * different container — the same trick AniKoto's CDN uses.
 */
internal class AniChanProvider(private val client: OkHttpClient, private val json: Json) {

    /**
     * The `anichan_ws` cookie `/api/watch/servers` demands, and when it stops being usable.
     *
     * `/api/watch/servers` answers `401 {"detail":"session"}` without it, and no combination of
     * browser headers or a cookie jar substitutes — the session is minted by an explicit POST.
     */
    private data class Session(val cookie: String, val expiresAtMs: Long)

    @Volatile
    private var session: Session? = null
    private val sessionLock = Any()

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val root = getJson("$BASE/api/watch/episodes?anilistId=${media.id}", cookie = null).second
            ?: error("AniChan returned no episode payload")
        return AniChanParser.availability(root)
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val category = if (audio.equals("dub", ignoreCase = true)) "dub" else "sub"
        return AniChanParser.sources(servers(media.id, episode, category))
    }

    private fun servers(anilistId: Int, episode: Int, category: String): JsonArray {
        val url = "$BASE/api/watch/servers?anilistId=$anilistId&ep=$episode&category=$category"
        var (code, body) = getJson(url, session())
        if (code == 401) {
            // The cookie outlives its 2h window silently, and the only way to learn that is to be
            // refused; the client-side clock is a hint, not the authority.
            synchronized(sessionLock) { session = null }
            val retried = getJson(url, session())
            code = retried.first
            body = retried.second
        }
        if (code != 200) error("AniChan HTTP $code for episode $episode")
        return (body?.get("servers") as? JsonArray) ?: JsonArray(emptyList())
    }

    /**
     * A live `anichan_ws` cookie, minted if the held one is missing or spent.
     *
     * The `token` is a Cloudflare Turnstile response, and the site currently publishes
     * `turnstileSiteKey: null` at `/site-config`, which is why an empty one is accepted. That is a
     * setting they can change: if it comes back, this POST starts failing and the provider needs a
     * Turnstile solve rather than a patch here.
     */
    private fun session(): String = synchronized(sessionLock) {
        session?.takeIf { System.currentTimeMillis() < it.expiresAtMs }?.let { return it.cookie }
        val request = Request.Builder()
            .url("$BASE/api/watch/session")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE/")
            .post("""{"token":""}""".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("AniChan session refused with HTTP ${response.code}")
            val cookie = response.headers("Set-Cookie")
                .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
                ?.substringBefore(';')
                ?: error("AniChan session response carried no $SESSION_COOKIE cookie")
            // The body's `exp` is in seconds; a minute of slack matches what the site's own client
            // subtracts, and the 401 retry above covers the case where it is wrong anyway.
            val expiry = runCatching {
                json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
            }.getOrNull()?.number("exp")?.times(1000)?.toLong()
            session = Session(cookie, (expiry ?: (System.currentTimeMillis() + DEFAULT_SESSION_MS)) - SESSION_SLACK_MS)
            cookie
        }
    }

    private fun getJson(url: String, cookie: String?): Pair<Int, JsonObject?> {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", "$BASE/")
            .apply { cookie?.let { header("Cookie", it) } }
            .get().build()
        client.newCall(request).execute().use { response ->
            val body = runCatching {
                json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
            }.getOrNull()
            return response.code to body
        }
    }

    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

    private companion object {
        /** Shared with the parser, which needs it to absolutise the URLs the API returns. */
        const val BASE = AniChanParser.BASE
        const val SESSION_COOKIE = "anichan_ws"
        const val DEFAULT_SESSION_MS = 7_200_000L
        const val SESSION_SLACK_MS = 60_000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Pure payload handling for [AniChanProvider], kept network-free for unit tests. */
internal object AniChanParser {
    const val BASE = "https://anichan.to"

    fun availability(root: JsonObject): EpisodeAvailability {
        val count = root.number("episodes")?.toInt() ?: 0
        if (count <= 0) error("AniChan has no entry for this title")
        // `dubAvailable` is the only thing published about the dub — there is no separate count,
        // and the API answers a playable stream for any episode asked for, including ones no dub
        // can plausibly exist for (One Piece ep1100 returns real segment bytes). So this trusts the
        // site's own claim across the whole range, the same trust every other provider's catalogue
        // gets, and a wrong one costs a failed resolve the picker falls out of.
        val dubbed = (root["dubAvailable"] as? JsonPrimitive)?.booleanOrNull == true
        return EpisodeAvailability.counts(sub = count, dub = if (dubbed) count else 0)
    }

    fun sources(servers: JsonArray): SourcesResult {
        val usable = servers.mapNotNull { it as? JsonObject }
            .filterNot { skip(it.string("name").orEmpty()) }
            .filter { !it.string("stream").isNullOrBlank() }
            .sortedBy { it.number("rank")?.toInt() ?: Int.MAX_VALUE }
        if (usable.isEmpty()) error("AniChan returned no playable server")

        val streams = usable.mapIndexed { index, server ->
            StreamItem(
                url = absolute(server.string("stream").orEmpty()),
                // Everything kept here publishes `hls`; the embeds are dropped by [skip].
                type = "hls",
                quality = server.string("label")?.trim()?.trimStart('★')?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: server.string("name")
                    ?: "Server ${index + 1}",
                audio = null,
                // Measured refusing nothing; see [AniChanProvider].
                referer = null,
                isActive = index == 0,
                width = null,
                height = null,
            )
        }

        // Subtitles come off the leading server rather than the union of all of them: each list is
        // cut for that server's own encode, and merging them would put a second copy of every
        // language in the menu with no way to tell which belongs to the stream being played.
        val subtitles = (usable.first()["subtitles"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            // Only the `.vtt` side is taken. Each track also publishes an `.ass` carrying real
            // styling, which would be the better source, but nothing in the player reads SSA/ASS.
            .mapNotNull { track ->
                val url = track.string("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = track.string("lang")?.takeIf { it.isNotBlank() } ?: "Subtitles"
                SubtitleItem(absolute(url), label, language(label))
            }
            .distinctBy(SubtitleItem::url)

        return SourcesResult(streams = streams, subtitles = subtitles, skip = null, download = null)
    }

    /**
     * Backends dropped because the app already reaches them by a shorter route.
     *
     * AniChan aggregates: alongside its own server it offers `megaplay_hd1`/`megaplay_hd2`, which is
     * the `megaplay.buzz` backend `anikoto` already resolves in [AnivexaClient], and `vidhawk`,
     * which is itself a megaplay re-host and arrives here as `type: "embed"` with no stream. Taking
     * them would put one upstream in the picker three times under three names.
     *
     * A deny list rather than an allow list on purpose: an unknown new server is worth offering,
     * while a duplicate is a specific, named thing.
     */
    private fun skip(name: String): Boolean =
        name.startsWith("megaplay", ignoreCase = true) || name.equals("vidhawk", ignoreCase = true)

    /** Stream and subtitle URLs come back site-relative; the site's own client prefixes them too. */
    private fun absolute(url: String): String =
        escapeSpaces(if (url.startsWith("/")) "$BASE$url" else url)

    /**
     * Percent-encode the spaces AniChan leaves in its subtitle paths.
     *
     * Its tracks are named after the language: the English one is literally
     * `.../subs/English (CR)0.vtt?md5=...`, with a space in the path. A space is not legal in a
     * request target, and [android.net.Uri.parse] — which is how `PlayerSurface` turns a
     * [SubtitleItem] into a `MediaItem.SubtitleConfiguration` — does not encode it, so the space
     * survives all the way to the transport and the fetch is refused before a request goes out.
     * The track appears in the menu, the viewer selects it, and nothing is ever drawn.
     *
     * Only the space is rewritten. The parentheses beside it are legal and the CDN accepts them
     * either way (measured: `%20(CR)` and `%20%28CR%29` both answer 200, while `+` answers 403, so
     * this cannot be done with a form encoder). Encoding the whole path with a general-purpose
     * escaper would be worse than this: any `%` already in a URL would be re-encoded to `%25` and a
     * working link would break.
     */
    private fun escapeSpaces(url: String): String = url.replace(" ", "%20")

    private fun language(label: String): String = when {
        label.startsWith("eng", true) -> "en"
        label.startsWith("spa", true) -> "es"
        label.startsWith("por", true) -> "pt"
        label.startsWith("fre", true) || label.startsWith("fra", true) -> "fr"
        label.startsWith("ger", true) || label.startsWith("deu", true) -> "de"
        label.startsWith("ita", true) -> "it"
        label.startsWith("ara", true) -> "ar"
        label.startsWith("rus", true) -> "ru"
        label.startsWith("ind", true) -> "id"
        label.startsWith("kor", true) -> "ko"
        label.startsWith("jap", true) -> "ja"
        label.startsWith("chi", true) || label.startsWith("zho", true) -> "zh"
        else -> "und"
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
}
