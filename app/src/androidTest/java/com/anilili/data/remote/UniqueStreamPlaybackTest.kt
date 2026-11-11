package com.anilili.data.remote

import androidx.media3.common.MediaItem
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
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live UniqueStream smoke test. Resolves Solo Leveling episode 1 through the search → season
 * (MAL id) → episodes → signed HLS chain and proves the master playlist reaches Media3
 * STATE_READY, i.e. the device really buffered CDN bytes.
 */
@RunWith(AndroidJUnit4::class)
class UniqueStreamPlaybackTest {
    @Test
    fun soloLevelingEpisodeOneBuffersOnMedia3() {
        val provider = UniqueStreamProvider(
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(Duration.ofSeconds(30))
                .build(),
            Json { ignoreUnknownKeys = true },
        )
        // AniList 151807 / MAL 52299: "Solo Leveling" — season one's mal_id on the site.
        val media = Media(
            id = 151_807,
            idMal = 52_299,
            title = MediaTitle(
                romaji = "Ore dake Level Up na Ken",
                english = "Solo Leveling",
            ),
            seasonYear = 2024,
            episodes = 12,
            status = "FINISHED",
        )

        val availability = provider.episodeAvailability(media)
        assertTrue(
            "UniqueStream did not list episode 1 as subbed: ${availability.sub.sorted()}",
            1 in availability.sub,
        )
        assertTrue(
            "UniqueStream did not list episode 1 as dubbed: ${availability.dub.sorted()}",
            1 in availability.dub,
        )

        val sources = provider.sources(media, "sub", 1)
        val direct = sources.streams.firstOrNull { it.isHls }
        assertTrue(
            "UniqueStream returned no HLS stream: ${sources.streams.map { "${it.type}:${it.url}" }}",
            direct != null,
        )
        // STATE_READY only proves the bytes decrypt — it is blind to a stream that plays Japanese
        // audio with nothing on screen, which is what the clean playlist is when (as on every
        // probed title) the API ships no soft subtitles. A sub stream must be translated somehow:
        // either soft subtitle tracks, or the burnt-in en-US encode.
        val burntIn = direct!!.url.contains("/hard/en-US/")
        assertTrue(
            "UniqueStream sub stream carries no subtitles: burntIn=$burntIn " +
                "subs=${sources.subtitles.map { it.label }} url=${direct.url}",
            burntIn || sources.subtitles.isNotEmpty(),
        )
        assertTrue(
            "UniqueStream stream did not reach Media3 READY",
            reachesReady(direct),
        )
    }

    private fun reachesReady(stream: StreamItem): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val completed = CountDownLatch(1)
        var player: ExoPlayer? = null
        var error: String? = null

        instrumentation.runOnMainSync {
            val headers = mutableMapOf<String, String>()
            stream.referer?.let { referer ->
                val origin = android.net.Uri.parse(referer).let { "${it.scheme}://${it.host}" }
                headers["Referer"] = referer
                headers["Origin"] = origin
            }
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36",
                )
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)
            // Mirror PlaybackService's chain: uniquestream key.bin fetches are answered locally
            // from the stream's playlistKey instead of hitting the wrapped CDN endpoint.
            val keyed = androidx.media3.datasource.DataSource.Factory {
                com.anilili.playback.UniqueStreamKeyDataSource(http.createDataSource()) {
                    stream.playlistKey
                }
            }
            val exo = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(keyed))
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
            exo.setMediaItem(MediaItem.fromUri(stream.url))
            exo.prepare()
        }

        val reachedReady = completed.await(45, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { player?.release() }
        if (error != null) throw AssertionError("UniqueStream playback failed: $error")
        return reachedReady
    }
}
