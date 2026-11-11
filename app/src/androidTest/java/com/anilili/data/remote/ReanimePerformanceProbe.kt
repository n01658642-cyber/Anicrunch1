package com.anilili.data.remote

import android.util.Log
import android.webkit.WebView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.AppGraph
import com.anilili.data.cache.AppCache
import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures what ReAnime actually costs on a real handset for freshly aired episodes.
 *
 * ReAnime resolves through a browser — see [com.anilili.data.ProviderCatalog.webViewResolverProviders]
 * — so a headless probe would be worse than no probe at all: with nothing attached, `FlixcloudBridge`
 * spins for its full 12s timeout and then reports `not_attached_timeout`, which reads as "slow
 * provider" when it is really "no browser in the harness". The probe therefore attaches a real
 * WebView the way the watch screen does, and every number below includes that resolve.
 *
 * Deliberately paced: one title at a time with [PACE_MS] between them. flixcloud.cc and reanime.to
 * both sit behind Cloudflare, and a burst is what earns a challenge for the whole network rather
 * than for one request.
 */
@RunWith(AndroidJUnit4::class)
class ReanimePerformanceProbe {
    private data class Target(val id: Int, val mal: Int, val episode: Int, val name: String)

    /** Currently-airing titles, latest aired episode as of 2026-09-07 (AniList RELEASING). */
    private val targets = listOf(
        Target(178_789, 59_193, 11, "Mushoku Tensei S3"),
        Target(189_046, 61_316, 15, "Re:ZERO S4"),
        Target(185_874, 60_636, 7, "BLEACH TYBW: The Calamity"),
        Target(196_187, 62_076, 9, "Smoking Behind the Supermarket"),
    )

    @Test
    fun reanimeResolvesAndPlaysFourNewEpisodes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppGraph.init(context)
        attachResolverWebView()

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
        val timer = RequestTimer()
        val http = AppGraph.httpClient.newBuilder().eventListenerFactory { timer }.build()
        val appCache = AppCache(context, json)
        val anivexa = AnivexaClient(
            context,
            http,
            json,
            AniListClient(http, json),
            appCache,
            com.anilili.data.remote.KonohaClient(context, http, json, appCache),
        )

        val lines = mutableListOf<String>()
        var resolved = 0

        targets.forEachIndexed { index, target ->
            if (index > 0) runBlocking { delay(PACE_MS) }
            timer.reset()

            val seed = Media(
                id = target.id,
                idMal = target.mal,
                title = MediaTitle(romaji = target.name, english = target.name),
                seasonYear = 2026,
            )
            val startedNs = System.nanoTime()
            val outcome = runCatching {
                runBlocking {
                    anivexa.getSources("watch/reanime/${target.id}/sub/ep-${target.episode}", seed)
                }
            }
            val resolveMs = (System.nanoTime() - startedNs) / 1_000_000

            lines += ""
            lines += "── ${target.name}  ep ${target.episode}  (anilist ${target.id})"
            lines += "   resolve: ${resolveMs}ms"
            timer.rows().forEach { lines += "     $it" }

            val result = outcome.getOrNull()
            if (result == null) {
                lines += "   FAILED: ${outcome.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }}"
                return@forEachIndexed
            }
            resolved++
            lines += describe(result)

            val native = result.streams.firstOrNull { it.type == "hls" }
            if (native == null) {
                lines += "   playback: SKIPPED (no native HLS — embed-only, WebView player would be used)"
                return@forEachIndexed
            }
            lines += "   " + buffer(native)
        }

        lines.forEach { Log.i(TAG, it) }
        println(lines.joinToString("\n"))
        assertTrue(
            "ReAnime resolved $resolved/${targets.size} new episodes; see logcat -s $TAG",
            resolved > 0,
        )
    }

    /**
     * Dumps what ReAnime's embed actually publishes per episode: the `servers` row shape and the
     * whole chapter vocabulary, not just the two fields the parser reads.
     *
     * Exists because the timing run above found `intro_chapter` absent on one episode and five
     * seconds long on another, which is not a plausible opening — so the question is whether the
     * opening is somewhere else in `chapters` under a title the parser does not look for.
     */
    @Test
    fun dumpsServerAndChapterVocabulary() {
        val client = AppGraph.httpClient.newBuilder().build()
        val lines = mutableListOf<String>()

        targets.forEachIndexed { index, target ->
            if (index > 0) Thread.sleep(PACE_MS)
            lines += ""
            lines += "── ${target.name}  ep ${target.episode}"

            val flix = client.newCall(
                okhttp3.Request.Builder()
                    .url("https://reanime.to/api/flix/${target.id}/${target.episode}")
                    .header("Referer", "https://reanime.to/")
                    .build(),
            ).execute().use { it.body?.string().orEmpty() }

            // Raw row text, so fields the app does not model (softsub, continue) are still visible.
            // Scanned rather than matched: Android's ICU engine rejects the bare `}` that a brace
            // regex needs, which is the trap already recorded against RareAnimes in ProviderCatalog.
            val rows = flix.indicesOf("\"dataLink\"").map { at ->
                val open = flix.lastIndexOf('{', at).coerceAtLeast(0)
                val close = flix.indexOf('}', at).let { if (it < 0) flix.length else it + 1 }
                flix.substring(open, close)
            }
            lines += "   servers: ${rows.size}"
            rows.forEach { lines += "     ${it.take(200)}" }

            val embed = rows.firstOrNull { it.contains("\"HD-2\"") } ?: rows.firstOrNull()
            val link = embed?.let { Regex(""""dataLink"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
            if (link == null) {
                lines += "   no dataLink"
                return@forEachIndexed
            }

            val html = client.newCall(
                okhttp3.Request.Builder().url(link).header("Referer", "https://reanime.to/").build(),
            ).execute().use { it.body?.string().orEmpty() }

            lines += "   intro_chapter: " + html.after("intro_chapter", 70)
            lines += "   outro_chapter: " + html.after("outro_chapter", 70)
            lines += "   chapters: " + html.after("chapters:", 400)
            lines += "   default_audio_track: " + (Regex("""default_audio_track\s*:\s*(\d+)""")
                .find(html)?.groupValues?.get(1) ?: "absent")
        }

        lines.forEach { Log.i(TAG, it) }
        println(lines.joinToString("\n"))
    }

    private fun describe(result: SourcesResult): String {
        val streams = result.streams.joinToString(" | ") { stream ->
            buildString {
                append(stream.quality ?: stream.type)
                append('/').append(stream.type)
                if (stream.isActive) append("*")
                if (stream.playlistKey != null) append(" key")
            }
        }
        val skip = result.skip?.let { "intro ${it.introStart}-${it.introEnd} outro ${it.outroStart}-${it.outroEnd}" }
            ?: "none"
        return "   streams: ${result.streams.size} [$streams]\n" +
            "   subtitles: ${result.subtitles.size} [${result.subtitles.take(4).joinToString { "${it.label}(${it.language})" }}]\n" +
            "   skip: $skip"
    }

    /** Buffers to Media3 READY through the same key-aware chain PlaybackService builds. */
    private fun buffer(stream: StreamItem): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val completed = CountDownLatch(1)
        var player: ExoPlayer? = null
        var error: String? = null
        var tracks = "none"
        val startedNs = System.nanoTime()

        instrumentation.runOnMainSync {
            val headers = mutableMapOf<String, String>()
            stream.referer?.let { referer ->
                headers["Referer"] = referer
                headers["Origin"] = android.net.Uri.parse(referer).let { "${it.scheme}://${it.host}" }
            }
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(
                    "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                )
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)
            // Mirror PlaybackService: the flixcloud master playlist arrives XOR'd under playlistKey.
            val keyed = androidx.media3.datasource.DataSource.Factory {
                com.anilili.playback.FlixcloudPlaylistDataSource(http.createDataSource()) {
                    stream.playlistKey
                }
            }
            val exo = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(keyed))
                .build()
            player = exo
            exo.addListener(object : Player.Listener {
                override fun onTracksChanged(value: androidx.media3.common.Tracks) {
                    tracks = value.groups
                        .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                        .flatMap { group ->
                            (0 until group.length).map { group.getTrackFormat(it) }
                        }
                        .joinToString { "${it.language}/${it.label}" }
                        .ifBlank { "none" }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) completed.countDown()
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error = generateSequence(playbackError as Throwable) { it.cause }
                        .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
                    completed.countDown()
                }
            })
            exo.setMediaItem(MediaItem.fromUri(stream.url))
            exo.prepare()
        }

        val ready = completed.await(45, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - startedNs) / 1_000_000
        instrumentation.runOnMainSync { player?.release() }
        return when {
            error != null -> "playback: ERROR after ${ms}ms — $error"
            !ready -> "playback: TIMEOUT after ${ms}ms"
            else -> "playback: READY in ${ms}ms  audio=[$tracks]"
        }
    }

    /**
     * The hidden resolver WebView the watch screen normally supplies. Without it `resolve` waits
     * out its whole timeout and reports `not_attached_timeout` — see the class comment.
     */
    private fun attachResolverWebView() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            FlixcloudBridge.attach(WebView(context))
        }
    }

    /** Per-request wall time, so the WebView resolve can be told apart from the HTTP hops. */
    private class RequestTimer : EventListener() {
        private val starts = ConcurrentHashMap<Call, Long>()
        private val rows = Collections.synchronizedList(mutableListOf<String>())

        fun reset() = rows.clear()
        fun rows(): List<String> = synchronized(rows) { rows.toList() }

        override fun callStart(call: Call) {
            starts[call] = System.nanoTime()
        }

        override fun callEnd(call: Call) = record(call, "ok")

        override fun callFailed(call: Call, ioe: IOException) = record(call, "FAIL ${ioe.javaClass.simpleName}")

        private fun record(call: Call, outcome: String) {
            val startedNs = starts.remove(call) ?: return
            val ms = (System.nanoTime() - startedNs) / 1_000_000
            val url = call.request().url
            // Path only: the flixcloud master URL carries a per-request JWT.
            rows += "${ms}ms  ${url.host}${url.encodedPath}  $outcome"
        }
    }

    private companion object {
        const val TAG = "ReanimePerf"
        const val PACE_MS = 4_000L

        fun String.indicesOf(needle: String): List<Int> {
            val found = mutableListOf<Int>()
            var at = indexOf(needle)
            while (at >= 0) {
                found += at
                at = indexOf(needle, at + needle.length)
            }
            return found
        }

        /** The [length] characters following [needle], or "absent". */
        fun String.after(needle: String, length: Int): String {
            val at = indexOf(needle)
            if (at < 0) return "absent"
            val from = at + needle.length
            return substring(from, minOf(from + length, this.length))
        }
    }
}
