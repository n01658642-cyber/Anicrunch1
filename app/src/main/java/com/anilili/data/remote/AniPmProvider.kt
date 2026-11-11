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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ani.pm — keyed by AniList id, JSON throughout, and played through its own "Settlar" embed
 * service, whose session hands back a plain HLS master rather than a player page.
 *
 * ```
 * GET ani.pm/api/anime/anipm-server/_packages?anilistId=            -> {episodes:{"1":{sub,dub}}}  (~130ms)
 * GET ani.pm/api/anime/playback-bootstrap/anilist/{id}?ep=&lang=&core=0 -> settlarSelection, skip (~130ms)
 * GET ani.pm/api/anime/settlar/session?selection=&provider=anipm&ep=&channel= -> embedUrl ?t=      (~150ms)
 * GET embed.settlar.io/api/embed/session?t=                         -> source, subtitles        (~200ms)
 * ```
 *
 * `_packages` is the catalogue because it is the inventory the `anipm` server actually plays from:
 * an episode outside it is refused at the session step with 424 "no matching self-hosted package"
 * (measured 2026-09-23 — Otome Game S2 lists dub to episode 10 and ep 12 dub is exactly that 424).
 *
 * All three hosts sit behind a Cloudflare TLS-fingerprint and User-Agent gate: Node's OpenSSL client
 * and an `okhttp/` User-Agent get 403, while Android's OkHttp with a browser User-Agent gets 200 on
 * every hop (measured on the phone emulator). Nothing else is needed — no Referer, no cookie — so
 * [StreamItem.referer] stays null and the player's own Chrome User-Agent carries the media, which
 * also answers CORS for the Cast receiver's origin.
 *
 * The content is not new: subtitles carry KickAssAnime's `kaa.lt` watermark on some titles and the
 * hianime ladder on others. What it adds is a different delivery path to the same episodes.
 */
internal class AniPmProvider(private val client: OkHttpClient, private val json: Json) {

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val root = getJson("$BASE/api/anime/anipm-server/_packages?anilistId=${media.id}")
            ?: error("ani.pm returned no package inventory")
        return AniPmParser.availability(root)
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val channel = if (audio.equals("dub", ignoreCase = true)) "dub" else "sub"
        val bootstrap = getJson(
            "$BASE/api/anime/playback-bootstrap/anilist/${media.id}?ep=$episode&lang=$channel&core=0",
        ) ?: error("ani.pm has no episode $episode")
        // A missing dub is not refused here: the bootstrap quietly answers with the sub and says so
        // only in effectiveLanguage. Playing that under a DUB label is the bug this check exists for.
        val effective = bootstrap.string("effectiveLanguage")
        if (effective != channel) error("ani.pm has no $channel for episode $episode (offers $effective)")
        val selection = bootstrap.string("settlarSelection") ?: error("ani.pm offered no selection")

        val sessionUrl = "$BASE/api/anime/settlar/session".toHttpUrl().newBuilder()
            .addQueryParameter("selection", selection)
            .addQueryParameter("provider", "anipm")
            .addQueryParameter("ep", episode.toString())
            .addQueryParameter("channel", channel)
            .addQueryParameter("telemetry", "0")
            .build().toString()
        val embedUrl = getJson(sessionUrl)?.string("embedUrl") ?: error("ani.pm session carried no embed")
        val token = embedUrl.toHttpUrl().queryParameter("t") ?: error("ani.pm embed carried no token")

        val embedSession = "$EMBED/api/embed/session".toHttpUrl().newBuilder()
            .addQueryParameter("t", token).build().toString()
        val session = getJson(embedSession) ?: error("Settlar session refused")
        return AniPmParser.sources(session, AniPmParser.skip(bootstrap))
    }

    private fun getJson(url: String): JsonObject? {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) error("ani.pm HTTP ${response.code} for ${request.url.encodedPath}")
            return runCatching {
                json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
            }.getOrNull()
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val BASE = "https://ani.pm"
        const val EMBED = "https://embed.settlar.io"

        // The gate refuses an `okhttp/` User-Agent outright; a browser one is the whole requirement.
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    }
}

/** Pure payload handling for [AniPmProvider], kept network-free for unit tests. */
internal object AniPmParser {

    fun availability(root: JsonObject): EpisodeAvailability {
        val episodes = root["episodes"] as? JsonObject ?: return EpisodeAvailability(emptySet(), emptySet())
        val sub = mutableSetOf<Int>()
        val dub = mutableSetOf<Int>()
        for ((key, value) in episodes) {
            val number = key.toIntOrNull()?.takeIf { it > 0 } ?: continue
            val entry = value as? JsonObject ?: continue
            if (entry.flag("sub")) sub += number
            if (entry.flag("dub")) dub += number
        }
        return EpisodeAvailability(sub, dub)
    }

    fun sources(session: JsonObject, skip: SkipTimes?): SourcesResult {
        val source = session.string("source")?.takeIf { it.startsWith("https://") }
            ?: error("Settlar session carried no stream")
        val kind = session.string("kind") ?: "hls"
        val stream = StreamItem(
            url = source,
            type = kind,
            // The master publishes its own ladder, from a single 1080p to 144p-1080p per title.
            quality = null,
            audio = null,
            referer = null,
            isActive = true,
            width = null,
            height = null,
        )
        val subtitles = (session["subtitles"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("url")?.startsWith("https://") == true }
            .sortedByDescending { (it["default"] as? JsonPrimitive)?.booleanOrNull == true }
            .map { track ->
                val code = track.string("srclang").orEmpty().lowercase()
                SubtitleItem(
                    url = track.string("url").orEmpty(),
                    label = track.string("label")?.trim()?.takeIf { it.isNotEmpty() } ?: "Subtitles",
                    language = LANGUAGES[code] ?: code.ifEmpty { "und" },
                )
            }
            .distinctBy(SubtitleItem::url)
        return SourcesResult(streams = listOf(stream), subtitles = subtitles, skip = skip, download = null)
    }

    /** `skip: {op:{start,end}, ed:{start,end}}` from the bootstrap, or null so AniSkip gets its turn. */
    fun skip(bootstrap: JsonObject): SkipTimes? {
        val skip = bootstrap["skip"] as? JsonObject ?: return null
        val op = skip["op"] as? JsonObject
        val ed = skip["ed"] as? JsonObject
        return SkipTimes(
            introStart = op?.number("start"),
            introEnd = op?.number("end"),
            outroStart = ed?.number("start"),
            outroEnd = ed?.number("end"),
        ).takeIf { it.hasUsableWindow() }
    }

    /** Settlar mixes two- and three-letter codes (`en`, `ind`, `tha`, `chi`) in one list. */
    private val LANGUAGES = mapOf(
        "ind" to "id", "tha" to "th", "chi" to "zh", "zho" to "zh", "kor" to "ko", "pol" to "pl",
        "tur" to "tr", "jpn" to "ja", "eng" to "en", "spa" to "es", "por" to "pt", "fre" to "fr",
        "fra" to "fr", "ger" to "de", "deu" to "de", "ita" to "it", "rus" to "ru", "ara" to "ar",
        "vie" to "vi", "may" to "ms", "msa" to "ms",
    )

    private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
}
