package com.anilili.data.remote

import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.diagnostics.DiagnosticsLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Turns a Miruro iframe embed into a stream the app's own player can take.
 *
 * Miruro classes five of its providers as `player: "iframe"`, and the app has always honoured that
 * literally by mounting a WebView. For `twin` that is more deference than the page deserves: both
 * of its embeds hand out ordinary HLS that **no header gates at all**. Measured 2026-09-09 against
 * `otakuvid.online/embed/orbfg31qtwiv` with bare curl — curl's own User-Agent, no cookies, no
 * Referer: the page answered 200 (~1.0s), the master and media playlists 200, segments 200 as
 * `video/MP2T` h264+AAC with **no `#EXT-X-KEY`**, `Range` answered 206, a deep segment fetched
 * directly, and even a deliberately hostile `Referer: example.com` still returned 200.
 *
 * That last set of facts is why this is worth a resolver rather than a nicety. A header-free stream
 * is the case where the app's two standing hand-off limitations stop applying: the Cast receiver
 * fetches URLs itself and external players carry no headers, so both work on a stream with nothing
 * to strip. It also takes a WebView off the path on the old boxes where rebuilding one is fatal.
 *
 * Only the *resolve* moves to plain HTTP. Getting the embed URL in the first place is still the
 * pipe's WAF-gated `sources` call over [PipeBridge]. This is not the AnimePahe situation either:
 * nothing here is a clearance bound to the engine that solved it, because nothing is being solved.
 */
class MiruroEmbedResolver(
    client: OkHttpClient,
    private val json: Json,
) {
    // A resolve sits in front of first frame, so it gets a much tighter budget than the app-wide
    // 45s call timeout. The observed page fetch is ~1.0s TTFB; anything approaching this ceiling
    // has already lost to simply mounting the embed.
    private val client = client.newBuilder()
        .connectTimeout(EMBED_RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(EMBED_RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(EMBED_RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * [sources] with a native stream in front of the embed, or unchanged when it cannot be read.
     *
     * The embed is kept rather than replaced, and kept typed as an embed, so that the existing
     * per-resolution fallback still has somewhere to go: `pickProviderStream` prefers direct HLS
     * and `providerStreamOrder` then yields the embed behind it, which is exactly the roll-over the
     * player already performs when a stream fails. A provider that stops being readable this way
     * therefore degrades to precisely today's behaviour instead of losing the server.
     */
    suspend fun resolved(sources: SourcesResult): SourcesResult {
        val embed = embedPage(sources.streams) ?: return sources
        val native = resolve(embed.url) ?: return sources
        val subtitles = native.subtitleUrl
            ?.let { listOf(subtitleItem(it, native.subtitleLanguage)) }
            .orEmpty()
            .filterNot { new -> sources.subtitles.any { it.url == new.url } }
        return sources.copy(
            streams = listOf(
                StreamItem(
                    url = native.url,
                    type = "hls",
                    quality = null,
                    audio = embed.audio,
                    // Verified to need none, and saying so is load-bearing rather than lazy: a
                    // Referer here would be copied onto the Cast and external-player hand-offs,
                    // where it cannot be sent, and would make a working stream look gated.
                    referer = null,
                    isActive = true,
                    width = null,
                    height = null,
                ),
                // Normalised to `embed` so the fallback lands in the embed player. The pipe does
                // not always type these, and an untyped page URL would otherwise be picked as if
                // it were a direct media file.
                embed.copy(type = "embed"),
            ) + sources.streams.filterNot { it.url == embed.url },
            subtitles = sources.subtitles + subtitles,
        )
    }

    /**
     * The one stream that is the embed's page, or `null` when the provider sent none.
     *
     * "The first stream that is not a manifest" used to stand in for this, and Cog broke it: its
     * sources lead with AnimeGG's MP4s at four qualities and put the page last, so the resolver
     * fetched a 100 MB video as if it were HTML, failed to parse it, and was never going to find
     * anything. A stream typed as an embed is the page; failing that, an untyped link that is not a
     * video file may be, since the pipe does not always type these.
     */
    internal fun embedPage(streams: List<StreamItem>): StreamItem? =
        streams.firstOrNull { it.isEmbed }
            ?: streams.firstOrNull { !it.isHls && !it.isDash && !it.isMediaFile }

    /** The stream an embed page declares, or `null` when the page cannot be read or fetched. */
    internal suspend fun resolve(embedUrl: String): MiruroEmbedStream? = try {
        miruroEmbedStream(embedUrl, fetch(embedUrl), json)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never fatal: the caller falls back to the embed player, which is what shipped before.
        DiagnosticsLog.throwable("Embed resolve failed ${embedUrl.substringBefore('?')}", e)
        null
    }

    private fun subtitleItem(url: String, language: String?): SubtitleItem {
        val code = SUBTITLE_LANGUAGES[language]
        return SubtitleItem(
            url = url,
            label = code?.second ?: language?.uppercase() ?: "Subtitle",
            language = code?.first ?: language ?: "und",
            // VibePlayer's CDN serves its WebVTT as `application/octet-stream`, so the type has to
            // be stated or the player has only a `.vtt` extension to go on.
            mimeType = "text/vtt",
        )
    }

    private suspend fun fetch(url: String): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            // Nothing observed requires this — curl's own agent was served throughout — but these
            // are ad-funded hosts that could add a crawler rule at any time, and a browser agent
            // costs nothing to send.
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code} fetching $url")
                        // A video that reached here as an embed would otherwise be read whole
                        // into a String — a hundred megabytes on the heap of a TV box.
                        val mediaType = it.body?.contentType()
                        if (mediaType?.type == "video" || mediaType?.type == "audio") {
                            throw IOException("Not a page ($mediaType) at $url")
                        }
                        it.body?.string().orEmpty()
                    }
                    continuation.resumeWith(Result.success(body))
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }

    private companion object {
        const val EMBED_RESOLVE_TIMEOUT_MS = 10_000L
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** ISO-639-2 codes these hosts write into subtitle filenames, to code and display name. */
        val SUBTITLE_LANGUAGES = mapOf(
            "eng" to ("en" to "English"),
            "spa" to ("es" to "Spanish"),
            "por" to ("pt" to "Portuguese"),
            "fre" to ("fr" to "French"),
            "fra" to ("fr" to "French"),
            "ger" to ("de" to "German"),
            "deu" to ("de" to "German"),
            "ara" to ("ar" to "Arabic"),
            "rus" to ("ru" to "Russian"),
            "ita" to ("it" to "Italian"),
            "jpn" to ("ja" to "Japanese"),
        )
    }
}
