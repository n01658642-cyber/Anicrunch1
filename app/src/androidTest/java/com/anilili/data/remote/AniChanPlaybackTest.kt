package com.anilili.data.remote

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.model.StreamItem
import com.anilili.ui.watch.videoMimeType
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live AniChan smoke test: session mint → servers → HLS reaching Media3 `STATE_READY`.
 *
 * Three things here cannot be checked off-device or in a unit test, and each has already been the
 * kind of thing that breaks quietly:
 *
 *  - **The session is minted, not inherited.** `/api/watch/servers` answers
 *    `401 {"detail":"session"}` to anything without the `anichan_ws` cookie, and the cookie only
 *    comes from a POST whose body carries a Cloudflare **Turnstile** token. The site currently
 *    publishes `turnstileSiteKey: null`, which is why an empty token is accepted — a setting they
 *    can change. When they do, this test is what says so, rather than the provider quietly
 *    dropping out of every catalogue.
 *  - **The segments are camouflaged.** They arrive as `.ts.png` / `.ts.webp` under an image content
 *    type. `STATE_READY` is what proves Media3 reads them as transport stream anyway.
 *  - **Subtitles decide whether a sub stream is watchable at all.** AniChan's own server publishes
 *    a dozen soft tracks; a resolve that returned the stream and lost them would look like success
 *    and play untranslated Japanese.
 *
 * Runs headless, so it does not need the handset unlocked.
 */
@RunWith(AndroidJUnit4::class)
class AniChanPlaybackTest {
    @Test
    fun frierenEpisodeOneMintsASessionAndBuffersOnMedia3() {
        val provider = AniChanProvider(
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(Duration.ofSeconds(30))
                .build(),
            Json { ignoreUnknownKeys = true; isLenient = true },
        )
        val media = Media(
            id = 154_587,
            idMal = 52_991,
            title = MediaTitle(romaji = "Sousou no Frieren", english = "Frieren: Beyond Journey's End"),
            seasonYear = 2023,
            episodes = 28,
            status = "FINISHED",
        )

        val availability = provider.episodeAvailability(media)
        assertEquals("AniChan lists 28 subbed episodes", (1..28).toSet(), availability.sub)
        assertTrue("AniChan did not list episode 1 as dubbed", 1 in availability.dub)

        val sources = provider.sources(media, "sub", 1)
        val stream = sources.streams.firstOrNull { it.isHls }
        assertTrue(
            "AniChan returned no HLS stream: ${sources.streams.map { "${it.type}:${it.quality}" }}",
            stream != null,
        )
        requireNotNull(stream)

        // The backends the app already reaches through anikoto must not come back under new names.
        val labels = sources.streams.mapNotNull { it.quality }
        assertTrue(
            "a megaplay re-host survived the filter: $labels",
            labels.none { it.contains("HD-", true) || it.contains("vidhawk", true) },
        )
        // Header-free is the whole reason referer is null; see AnimeHeavenDownloadAndCastTest for
        // what that null is load-bearing for.
        assertNull("AniChan stream named a referer", stream.referer)
        assertTrue("AniChan stream URL was not absolutised", stream.url.startsWith("https://"))

        assertTrue(
            "AniChan sub stream carries no subtitles, so it would play untranslated: " +
                sources.subtitles.map { it.label },
            sources.subtitles.isNotEmpty(),
        )
        assertTrue(
            "expected an English track among ${sources.subtitles.map { it.language }}",
            sources.subtitles.any { it.language == "en" },
        )

        // Load-bearing, and not obvious: the stream URL is `/api/watch/m3u8?sh=…`, so the path has
        // no `.m3u8` extension for Media3 to infer from. Without the explicit MIME type it falls
        // through to the progressive extractors and dies on
        // UnrecognizedInputFormatException — which is exactly what this test did before it mirrored
        // what PlayerSurface actually builds.
        assertEquals(MimeTypes.APPLICATION_M3U8, videoMimeType(stream.type, stream.url))

        assertTrue("AniChan stream did not reach Media3 READY", reachesReady(stream))
    }

    private fun reachesReady(stream: StreamItem): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val completed = CountDownLatch(1)
        var player: ExoPlayer? = null
        var error: String? = null

        instrumentation.runOnMainSync {
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36",
                )
                .setAllowCrossProtocolRedirects(true)
            val exo = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .build()
            player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) completed.countDown()
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    val causes = generateSequence(playbackError as Throwable) { it.cause }
                        .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
                    error = "${playbackError.errorCodeName}: $causes"
                    completed.countDown()
                }
            })
            // Mirrors PlayerSurface: the MIME type is set from the stream, not inferred from the URL.
            exo.setMediaItem(
                MediaItem.Builder()
                    .setUri(stream.url)
                    .setMimeType(videoMimeType(stream.type, stream.url))
                    .build(),
            )
            exo.prepare()
        }

        val reachedReady = completed.await(45, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { player?.release() }
        error?.let { throw AssertionError("AniChan playback failed: $it") }
        return reachedReady
    }
}
