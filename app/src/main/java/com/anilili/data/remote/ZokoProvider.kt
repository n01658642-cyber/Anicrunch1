package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.data.model.hasUsableWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64

/**
 * Zoko (zokoanime.video) — an embed page keyed by MAL id, read in the app's own player rather
 * than shown in a WebView.
 *
 * ```
 * GET /stream/mal/{malId}/{episode}/{sub|dub}   -> ~2KB HTML carrying window.__P   (~0.1s)
 * ```
 *
 * Their documentation also offers `/stream/anilist/…`, and it is a Fastify 404 for every id tried
 * (2026-09-22), so this goes by [Media.idMal]. The `hsub` track the docs list answered the same
 * video as `sub` on every title measured, and an unknown track name silently falls back to sub, so
 * only `sub` and `dub` are ever asked for.
 *
 * `__P` is the whole player configuration: base64 over the UTF-8 JSON XOR'd with a fixed key (see
 * [ZokoParser.KEY]). Inside is a plain HLS master — on `hls.1embed.buzz` when this was written,
 * on `hls2.aniwatchtv.uk` by 2026-09-26 — VTT subtitles and the intro/outro windows.
 *
 * The playlists — master *and* variant — answer 403 without `Referer: https://zokoanime.video/`,
 * and any other Referer is refused too. On the 2026-09-26 host the segments are gated the same
 * way (403 bare on anything Cloudflare has not cached); playback is unaffected, because Media3
 * sends the stream's default request properties on every request, segments included. Its
 * uncached segments are slow — see `degradedFastProviders` in ProviderCatalog. A bare request can
 * look header-free when it lands on Cloudflare's 60s cache of a playlist someone else just warmed,
 * so the gate was measured on episodes nobody had fetched: 403 bare, 200 with the Referer and
 * Origin pair `mediaRequestHeaders` derives from [StreamItem.referer].
 */
internal class ZokoProvider(private val client: OkHttpClient, private val json: Json) {

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val page = page(malId(media), track(audio), episode)
        val payload = ZokoParser.payload(page, json)
            ?: error("Zoko has no ${track(audio)} stream for episode $episode")
        return ZokoParser.sources(payload)
    }

    /**
     * Whether the episode exists, for the catalogue's binary search.
     *
     * A miss is a 200 carrying a "Not found." card and no payload, so that is the only thing read
     * as absent. Anything else — a refused request, a dropped connection — throws, so a network
     * blip fails the lookup instead of being learnt as a shorter catalogue.
     */
    fun hasEpisode(malId: Int, audio: String, episode: Int): Boolean =
        ZokoParser.hasPayload(page(malId, track(audio), episode))

    fun malId(media: Media): Int = media.idMal?.takeIf { it > 0 }
        ?: error("Zoko is keyed by MAL id and AniList has none for this title")

    private fun page(malId: Int, track: String, episode: Int): String {
        val request = Request.Builder()
            .url("${ZokoParser.BASE}/stream/mal/$malId/$episode/$track")
            .header("User-Agent", USER_AGENT)
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Zoko HTTP ${response.code} for episode $episode")
            return response.body?.string().orEmpty()
        }
    }

    private fun track(audio: String): String = if (audio.equals("dub", ignoreCase = true)) "dub" else "sub"

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Pure payload handling for [ZokoProvider], kept network-free for unit tests. */
internal object ZokoParser {
    const val BASE = "https://zokoanime.video"
    const val REFERER = "$BASE/"

    /**
     * The XOR key from the site's own `core/obfuscate.js`. It is a constant they can change at any
     * time. If it does change, [payload] stops parsing, and every episode then reads as missing
     * rather than playing garbage.
     */
    const val KEY = "otaku-embed-v1"

    private val PAYLOAD = Regex("""window\.__P\s*=\s*"([A-Za-z0-9+/=]+)"""")

    fun hasPayload(page: String): Boolean = PAYLOAD.containsMatchIn(page)

    /**
     * The decoded player configuration, or null for a miss page or one that no longer decodes.
     *
     * The site XORs the UTF-8 *bytes* (`unescape(encodeURIComponent(…))` before the XOR) with an
     * ASCII key, so a byte-wise XOR here is exact, including for non-Latin titles.
     */
    fun payload(page: String, json: Json): JsonObject? {
        val blob = PAYLOAD.find(page)?.groupValues?.get(1) ?: return null
        val bytes = blob.decodeBase64()?.toByteArray() ?: return null
        val key = KEY.toByteArray(Charsets.US_ASCII)
        for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        return runCatching { json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject }.getOrNull()
    }

    fun sources(payload: JsonObject): SourcesResult {
        val src = payload.string("src")?.takeIf { it.startsWith("http") }
            ?: error("Zoko payload carried no stream")
        val stream = StreamItem(
            url = src,
            type = "hls",
            // One 1080p rendition on every title measured, but the master says so itself.
            quality = null,
            audio = null,
            referer = REFERER,
            isActive = true,
            width = null,
            height = null,
        )

        // The track marked `default` goes first. Every track is published as `lang: "en"`
        // whatever it is, so the label is the only honest statement of the language.
        val subtitles = (payload["subtitles"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { !it.string("src").isNullOrBlank() }
            .sortedByDescending { (it["default"] as? JsonPrimitive)?.booleanOrNull == true }
            .map { track ->
                val label = track.string("label")?.trim()?.takeIf { it.isNotEmpty() } ?: "Subtitles"
                SubtitleItem(track.string("src").orEmpty(), label, language(label))
            }
            .distinctBy(SubtitleItem::url)

        return SourcesResult(streams = listOf(stream), subtitles = subtitles, skip = skip(payload), download = null)
    }

    /**
     * Intro and outro windows, or null so AniSkip gets its turn. Several titles publish no `skip`
     * at all.
     */
    private fun skip(payload: JsonObject): SkipTimes? {
        val skip = payload["skip"] as? JsonObject ?: return null
        val intro = skip["intro"] as? JsonObject
        val outro = skip["outro"] as? JsonObject
        return SkipTimes(
            introStart = intro?.number("start"),
            introEnd = intro?.number("end"),
            outroStart = outro?.number("start"),
            outroEnd = outro?.number("end"),
        ).takeIf { it.hasUsableWindow() }
    }

    /**
     * The ISO code for a label such as "Portuguese (Portuguese - Brazilian [Full])". The language
     * name is always the first word.
     */
    fun language(label: String): String =
        LANGUAGES[label.substringBefore(' ').substringBefore('(').lowercase()] ?: "und"

    private val LANGUAGES = mapOf(
        "english" to "en", "spanish" to "es", "portuguese" to "pt", "french" to "fr",
        "german" to "de", "italian" to "it", "arabic" to "ar", "russian" to "ru",
        "indonesian" to "id", "korean" to "ko", "japanese" to "ja", "chinese" to "zh",
        "danish" to "da", "dutch" to "nl", "finnish" to "fi", "norwegian" to "no",
        "polish" to "pl", "swedish" to "sv", "turkish" to "tr", "thai" to "th",
        "vietnamese" to "vi", "hindi" to "hi", "malay" to "ms",
    )

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
}
