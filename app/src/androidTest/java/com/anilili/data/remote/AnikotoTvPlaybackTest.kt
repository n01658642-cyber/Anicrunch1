package com.anilili.data.remote

import android.app.ActivityManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.AppGraph
import com.anilili.data.cache.AppCache
import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.model.StreamItem
import com.anilili.data.settings.PlaybackBufferSize
import com.anilili.playback.AdjustableLoadControl
import com.anilili.playback.mediaRequestHeaders
import com.anilili.playback.playbackBufferPolicy
import com.anilili.ui.watch.videoMimeType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live check of old/new MegaPlay layouts with TV buffering and an actual decoded video frame. */
@RunWith(AndroidJUnit4::class)
class AnikotoTvPlaybackTest {
    @Test
    fun nativeResolutionRetainsAdaptiveQualitiesAndRendersOnTv() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppGraph.init(context)
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val http = AppGraph.httpClient
        val cache = AppCache(context, json)
        val client = AnivexaClient(
            context, http, json, AniListClient(http, json), cache,
            KonohaClient(context, http, json, cache),
        )
        val samples = listOf(
            Media(id = 269, idMal = 269, title = MediaTitle(romaji = "Bleach"), episodes = 366) to 298,
            Media(id = 197754, idMal = 62331, title = MediaTitle(romaji = "LIAR GAME"), episodes = 25) to 25,
        )
        for ((media, episode) in samples) {
            val id = "watch/anikoto/${media.id}/sub/anikoto-$episode"
            val started = SystemClock.elapsedRealtime()
            val sources = client.getSources(id, media, allowInteractiveChallenges = false)
            val firstMs = SystemClock.elapsedRealtime() - started
            val stream = requireNotNull(sources.streams.firstOrNull { it.isHls }) {
                "No native HLS for ${media.id}/$episode"
            }
            assertEquals("https://megaplay.buzz/", stream.referer)
            val headers = mediaRequestHeaders(stream.referer, USER_AGENT, stream.headers)
            val master = http.newCall(okhttp3.Request.Builder().url(stream.url).apply {
                headers.forEach { (key, value) -> header(key, value) }
            }.build()).execute().use {
                assertTrue("Master HTTP ${it.code}", it.isSuccessful)
                it.body!!.string()
            }
            val qualities = master.lineSequence().count { it.startsWith("#EXT-X-STREAM-INF:") }
            assertTrue("Master lost the adaptive ladder: $qualities qualities", qualities >= 2)
            assertTrue("Expected sub metadata", sources.subtitles.isNotEmpty())
            val warmStarted = SystemClock.elapsedRealtime()
            assertTrue(client.getSources(id, media, allowInteractiveChallenges = false).streams.any { it.isHls })
            val warmMs = SystemClock.elapsedRealtime() - warmStarted
            Log.i(TAG, "id=${media.id} ep=$episode resolveFirstMs=$firstMs resolveRepeatMs=$warmMs qualities=$qualities")
            render(context, stream)
        }
    }

    private fun render(context: Context, stream: StreamItem) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val completed = CountDownLatch(1)
        var player: ExoPlayer? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        var error: String? = null
        var firstFrame = false
        val started = SystemClock.elapsedRealtime()
        try {
            instrumentation.runOnMainSync {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val http = OkHttpDataSource.Factory(AppGraph.httpClient)
                    .setUserAgent(USER_AGENT)
                    .setDefaultRequestProperties(mediaRequestHeaders(stream.referer, USER_AGENT, stream.headers))
                val exo = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                    .setLoadControl(AdjustableLoadControl {
                        playbackBufferPolicy(PlaybackBufferSize.AUTOMATIC, true, manager.isLowRamDevice, manager.memoryClass)
                    })
                    .build()
                player = exo
                // An offscreen surface proves decoding without needing to unlock the emulator.
                texture = SurfaceTexture(0)
                surface = Surface(texture)
                exo.setVideoSurface(surface)
                exo.volume = 0f
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(1280, 720).build()
                exo.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        firstFrame = true
                        val sizes = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).height } }
                        Log.i(TAG, "firstFrameMs=${SystemClock.elapsedRealtime() - started} videoHeights=$sizes selectedHeight=${exo.videoFormat?.height}")
                        completed.countDown()
                    }

                    override fun onPlayerError(playbackError: PlaybackException) {
                        error = generateSequence(playbackError as Throwable) { it.cause }
                            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
                        completed.countDown()
                    }
                })
                exo.setMediaItem(MediaItem.Builder().setUri(stream.url)
                    .setMimeType(videoMimeType(stream.type, stream.url)).build())
                exo.playWhenReady = true
                exo.prepare()
            }
            assertTrue("Timed out waiting for decoded frame", completed.await(45, TimeUnit.SECONDS))
            assertNull("Playback failed: $error", error)
            assertTrue("No decoded video frame", firstFrame)
        } finally {
            instrumentation.runOnMainSync {
                player?.release()
                surface?.release()
                texture?.release()
            }
        }
    }

    private companion object {
        const val TAG = "AnikotoTvProbe"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    }
}
