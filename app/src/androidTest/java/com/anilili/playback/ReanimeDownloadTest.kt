package com.anilili.playback

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.AppGraph
import com.anilili.data.cache.AppCache
import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.remote.AniListClient
import com.anilili.data.remote.AnivexaClient
import com.anilili.data.remote.FlixcloudBridge
import com.anilili.data.settings.DownloadQuality
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves on a real handset that a ReAnime episode can now be saved offline.
 *
 * ReAnime's playlists arrive XOR'd under `window.__pk`, which the app carries as
 * [com.anilili.data.model.StreamItem.playlistKey]. Until [EpisodeDownloads.upstreamFactory] gained
 * the Flixcloud and UniqueStream decoders, the downloader's transport only added headers, so
 * [EpisodeDownloads.canDownload] had to refuse any stream carrying a key — the picker showed a
 * download glyph (the forecast reads the provider's name) and the screen below had no button.
 *
 * The decisive step is the manifest prepare: `DownloadHelper` parses the HLS playlist, and an
 * undecoded one fails with "The source did not return a valid HLS playlist". Reaching QUEUED at
 * all therefore means the decode happened. The test then waits for real bytes, because a manifest
 * that parses would still be worthless if the segments could not be fetched.
 *
 * ReAnime resolves through a browser (see
 * [com.anilili.data.ProviderCatalog.webViewResolverProviders]), so a WebView is attached the way
 * the watch screen does it; without one `FlixcloudBridge` reports `not_attached_timeout`.
 */
@RunWith(AndroidJUnit4::class)
class ReanimeDownloadTest {
    @Test
    fun reanimeEpisodeDownloadsDespiteItsObfuscatedPlaylist() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppGraph.init(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            FlixcloudBridge.attach(WebView(context))
        }

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
        val http = AppGraph.httpClient
        val appCache = AppCache(context, json)
        val anivexa = AnivexaClient(
            context,
            http,
            json,
            AniListClient(http, json),
            appCache,
            com.anilili.data.remote.KonohaClient(context, http, json, appCache),
        )
        val seed = Media(
            id = ANILIST_ID,
            idMal = MAL_ID,
            title = MediaTitle(romaji = TITLE, english = TITLE),
            seasonYear = 2023,
        )

        val sources = runBlocking {
            anivexa.getSources("watch/reanime/$ANILIST_ID/sub/ep-$EPISODE", seed)
        }
        log("streams: " + sources.streams.joinToString { "${it.type}${if (it.playlistKey != null) "+key" else ""}" })

        val stream = sources.streams.firstOrNull(EpisodeDownloads::canDownload)
        assertNotNull(
            "ReAnime returned no downloadable stream — resolve produced " +
                sources.streams.joinToString { it.type },
            stream,
        )
        requireNotNull(stream)
        log("chosen: type=${stream.type} host=${runCatching { android.net.Uri.parse(stream.url).host }.getOrNull()}")

        // The whole point of the change: this stream carries a key, and used to be refused for it.
        assertNotNull(
            "ReAnime stream carried no playlistKey, so this run does not exercise the decoder",
            stream.playlistKey,
        )
        log("playlistKey present (${stream.playlistKey?.length} chars)")

        val metadata = EpisodeDownloadMetadata(
            anilistId = ANILIST_ID,
            seriesTitle = TITLE,
            episodeNumber = EPISODE.toString(),
            provider = "reanime",
            category = "sub",
            referer = stream.referer,
            headers = stream.headers,
        )
        val downloadId = EpisodeDownloads.idFor(ANILIST_ID, "sub", EPISODE.toString())
        // A previous run's copy would make "bytes arrived" true without fetching anything.
        EpisodeDownloads.removeDirectly(context, downloadId)
        Thread.sleep(2_000)

        val enqueued = CountDownLatch(1)
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            EpisodeDownloads.enqueue(context, metadata, stream, DownloadQuality.BEST) { result ->
                failure = result.exceptionOrNull()
                enqueued.countDown()
            }
        }
        assertTrue(
            "enqueue never reported a result within ${ENQUEUE_TIMEOUT_S}s",
            enqueued.await(ENQUEUE_TIMEOUT_S, TimeUnit.SECONDS),
        )
        assertTrue(
            "manifest prepare failed: ${failure?.javaClass?.simpleName}: ${failure?.message}",
            failure == null,
        )
        log("manifest prepared and queued")

        var bytes = 0L
        var state: EpisodeDownloadState? = null
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(BYTES_TIMEOUT_S)
        while (System.currentTimeMillis() < deadline && bytes <= 0) {
            Thread.sleep(2_000)
            val download = EpisodeDownloads.downloads(context).value.firstOrNull { it.id == downloadId }
            bytes = download?.bytesDownloaded ?: 0L
            state = download?.state
            if (state == EpisodeDownloadState.FAILED) break
        }
        log("state=$state bytes=$bytes")

        EpisodeDownloads.removeDirectly(context, downloadId)
        assertTrue("download reported FAILED", state != EpisodeDownloadState.FAILED)
        assertTrue(
            "no segment bytes arrived within ${BYTES_TIMEOUT_S}s (state=$state)",
            bytes > 0,
        )
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private companion object {
        const val TAG = "ReanimeDownloadTest"
        const val ANILIST_ID = 154_587
        const val MAL_ID = 52_991
        const val EPISODE = 1
        const val TITLE = "Sousou no Frieren"
        const val ENQUEUE_TIMEOUT_S = 120L
        const val BYTES_TIMEOUT_S = 120L
    }
}
