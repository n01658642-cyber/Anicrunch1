package com.anilili.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.AppGraph
import com.anilili.data.cache.AppCache
import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.remote.AniListClient
import com.anilili.data.remote.AnivexaClient
import com.anilili.data.settings.DownloadQuality
import com.anilili.ui.watch.videoMimeType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves on a real handset the two things AnimeHeaven was added for, neither of which follows from
 * the episode merely playing.
 *
 * The provider's whole argument is that its CDN needs no headers, so
 * [com.anilili.data.model.StreamItem.referer] is deliberately null. Two behaviours hang off that
 * one field and both were previously reasoned about rather than measured:
 *
 *  - **Casting.** [CastStreamRouting] treats a named referer as the app's own signal that a source
 *    is gated and rewrites it through [StreamCorsProxy]. A source that names none must reach the
 *    receiver untouched — this asserts the queue comes back identical, which is what "it casts
 *    directly" actually means in this codebase. It does not need a Chromecast on the network,
 *    because the receiver is not what decides this; the rewrite is.
 *  - **Downloading.** A direct MP4 is a byte copy with no manifest to parse and no transmux, so
 *    reaching real bytes here exercises a different path from every HLS provider's download.
 *
 * The mime type is checked alongside because it is the other half of the same property: an
 * external player routes on a `video/…` type and takes no headers (see
 * `ExternalPlayerSharingTest`), so a header-free stream is only useful to one if it is also typed.
 *
 * No WebView is attached, unlike `ReanimeDownloadTest`: AnimeHeaven resolves over plain OkHttp, and
 * needing one would itself be a failure.
 */
@RunWith(AndroidJUnit4::class)
class AnimeHeavenDownloadAndCastTest {
    @Test
    fun animeHeavenStreamCastsUnproxiedAndDownloadsAsADirectFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppGraph.init(context)

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
            title = MediaTitle(romaji = TITLE, english = ENGLISH_TITLE),
            seasonYear = 2023,
        )

        val sources = runBlocking {
            anivexa.getSources("watch/animeheaven/$ANILIST_ID/sub/animeheaven-$EPISODE", seed)
        }
        log("streams: " + sources.streams.joinToString { "${it.type}/${it.quality}" })
        assertTrue("AnimeHeaven returned no streams", sources.streams.isNotEmpty())

        val stream = sources.streams.firstOrNull(EpisodeDownloads::canDownload)
        assertNotNull(
            "no downloadable stream — resolve produced " + sources.streams.joinToString { it.type },
            stream,
        )
        requireNotNull(stream)
        log("chosen: type=${stream.type} host=${android.net.Uri.parse(stream.url).host}")

        // The property the rest of this test depends on. If AnimeHeaven ever starts needing a
        // referer, the two claims below stop being true and this is where that shows up.
        assertNull("AnimeHeaven stream named a referer, so it is no longer header-free", stream.referer)
        assertTrue("expected a progressive file, got ${stream.type}", !stream.isHls && !stream.isEmbed)

        // --- Casting -------------------------------------------------------------------------
        val mimeType = videoMimeType(stream.type, stream.url)
        assertEquals("a receiver and an external player both route on this", "video/mp4", mimeType)

        val item = MediaItem.Builder()
            .setUri(stream.url)
            .setMediaId(stream.url)
            .setMimeType(mimeType)
            .build()
        // The proxy is treated as available: the point here is that a stream naming no referer is
        // handed over untouched even when proxying *could* have happened.
        val cast = CastStreamRouting.castItems(listOf(item), stream.referer, proxyServing = true)

        val castUri = cast.single().localConfiguration?.uri?.toString()
        assertEquals("the receiver must be handed the CDN URL itself", stream.url, castUri)
        assertTrue(
            "stream was rewritten through the CORS proxy despite naming no referer",
            !StreamCorsProxy.isProxied(castUri.orEmpty()),
        )
        log("cast queue unchanged: $castUri")

        // --- Downloading ---------------------------------------------------------------------
        val metadata = EpisodeDownloadMetadata(
            anilistId = ANILIST_ID,
            seriesTitle = ENGLISH_TITLE,
            episodeNumber = EPISODE.toString(),
            provider = "animeheaven",
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
            "enqueue failed: ${failure?.javaClass?.simpleName}: ${failure?.message}",
            failure == null,
        )
        log("queued")

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
        assertTrue("no bytes arrived within ${BYTES_TIMEOUT_S}s (state=$state)", bytes > 0)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private companion object {
        const val TAG = "AnimeHeavenDownloadTest"
        const val ANILIST_ID = 154_587
        const val MAL_ID = 52_991
        const val EPISODE = 1
        const val TITLE = "Sousou no Frieren"
        const val ENGLISH_TITLE = "Frieren: Beyond Journey's End"
        const val ENQUEUE_TIMEOUT_S = 120L
        const val BYTES_TIMEOUT_S = 120L
    }
}
