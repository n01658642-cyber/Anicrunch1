package com.anilili.playback

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.AppGraph
import com.anilili.data.cache.AppCache
import com.anilili.data.model.Category
import com.anilili.data.remote.AniListClient
import com.anilili.data.remote.AnivexaClient
import com.anilili.data.remote.FlixcloudBridge
import com.anilili.data.settings.DownloadQuality
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The anime page's own download control, which reaches the downloader by a different road than the
 * player's does.
 *
 * The watch screen picks a stream from sources it already has and calls [EpisodeDownloads.enqueue]
 * directly. A row on the detail page has resolved nothing yet, so it hands the episode to
 * [BulkEpisodeDownloads], which resolves a provider itself, filters with
 * [EpisodeDownloads.canDownload] and only then enqueues. Both predicates changed when the Flixcloud
 * and UniqueStream decoders were wired into the downloader, so both roads are worth walking — and
 * this one has the extra hazard that `resolveDownloadable` silently *excludes* a provider whose
 * streams it judges undownloadable and moves to the next, which is how ReAnime used to disappear
 * from this path without any visible failure.
 *
 * Asserting the provider is therefore the point: a plain "something downloaded" would pass just as
 * happily on a fallback provider that was never broken.
 */
@RunWith(AndroidJUnit4::class)
class ReanimeListDownloadTest {
    @Test
    fun theAnimePageQueuesReanimeRatherThanSkippingPastIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppGraph.init(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            FlixcloudBridge.attach(WebView(context))
        }

        // ReAnime's listing is asked for by name rather than taken from the merged catalogue.
        // `repository.episodes` fans out to every provider under a time budget and keeps whoever
        // answers; ReAnime measures ~4.4s against a ~6s budget, so in a bare instrumentation
        // harness — no warm cache, no pipe WebView — it routinely misses and the run reduces to
        // "ReAnime was not in the catalogue", which says nothing about downloading. Naming it here
        // makes the test deterministic and keeps the failure it *can* report about this change.
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
        val catalog = runBlocking {
            anivexa.getEpisodes(ANILIST_ID, providers = listOf("reanime"))
        }
        log("catalog providers: ${catalog.providerNames}")

        val episode = catalog.provider("reanime")
            ?.episodes(Category.SUB)
            ?.firstOrNull { it.displayNumber == EPISODE }
        assertNotNull("ReAnime does not list episode $EPISODE for this title", episode)
        requireNotNull(episode)
        log("using episode ${episode.displayNumber} (pipeId=${episode.pipeId})")

        val downloadId = EpisodeDownloads.idFor(ANILIST_ID, Category.SUB.api, episode.displayNumber)
        EpisodeDownloads.removeDirectly(context, downloadId)
        Thread.sleep(2_000)

        val settled = CountDownLatch(1)
        var outcome: EpisodeQueueOutcome? = null
        BulkEpisodeDownloads.enqueueEpisode(
            context = context,
            anilistId = ANILIST_ID,
            seriesTitle = TITLE,
            artworkUrl = null,
            category = Category.SUB,
            preferredProvider = "reanime",
            episode = episode,
            catalog = catalog,
            quality = DownloadQuality.BEST,
        ) { result ->
            outcome = result
            settled.countDown()
        }

        // QUEUED reports no outcome on this callback — silence is the success case there, so a
        // timeout is expected and only a *reported* outcome is news.
        settled.await(RESOLVE_TIMEOUT_S, TimeUnit.SECONDS)
        log("queue outcome: ${outcome ?: "none reported (queued)"}")
        assertTrue(
            "the queue gave up on this episode: $outcome",
            outcome == null || outcome == EpisodeQueueOutcome.QUEUED,
        )

        var bytes = 0L
        var provider: String? = null
        var state: EpisodeDownloadState? = null
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(BYTES_TIMEOUT_S)
        while (System.currentTimeMillis() < deadline && bytes <= 0) {
            Thread.sleep(2_000)
            val download = EpisodeDownloads.downloads(context).value.firstOrNull { it.id == downloadId }
            bytes = download?.bytesDownloaded ?: 0L
            provider = download?.metadata?.provider
            state = download?.state
            if (state == EpisodeDownloadState.FAILED) break
        }
        log("provider=$provider state=$state bytes=$bytes")

        EpisodeDownloads.removeDirectly(context, downloadId)
        assertTrue("download reported FAILED", state != EpisodeDownloadState.FAILED)
        assertEquals(
            "the queue fell through to another provider, so ReAnime is still being skipped here",
            "reanime",
            provider,
        )
        assertTrue("no segment bytes arrived within ${BYTES_TIMEOUT_S}s", bytes > 0)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private companion object {
        const val TAG = "ReanimeListDownloadTest"
        const val ANILIST_ID = 154_587
        const val EPISODE = "1"
        const val TITLE = "Sousou no Frieren"
        const val RESOLVE_TIMEOUT_S = 90L
        const val BYTES_TIMEOUT_S = 150L
    }
}
