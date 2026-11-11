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
import com.anilili.data.model.StreamItem
import com.anilili.data.remote.AniListClient
import com.anilili.data.remote.AnivexaClient
import com.anilili.data.remote.FlixcloudBridge
import com.anilili.data.settings.DownloadQuality
import com.anilili.ui.watch.pickProviderStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic probe, not a regression test: it reports rather than asserts.
 *
 * Three field complaints point at the same seam — a download that comes back at a lower resolution
 * than the player showed, from a server other than the one on screen, with no subtitles. The
 * player picks its stream with [pickProviderStream] (provider policy, then highest `height`); the
 * downloader takes `streams.firstOrNull(canDownload)` in provider list order. This prints both
 * answers side by side for one episode on several servers, so the gap is measured rather than
 * argued about.
 */
@RunWith(AndroidJUnit4::class)
class DownloadSelectionProbeTest {
    @Test
    fun whatEachServerOffersAndWhatEachPathTakes() {
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
        val cache = AppCache(context, json)
        val anivexa = AnivexaClient(
            context,
            http,
            json,
            AniListClient(http, json),
            cache,
            com.anilili.data.remote.KonohaClient(context, http, json, cache),
        )

        val catalog = runBlocking { anivexa.getEpisodes(ANILIST_ID, providers = PROVIDERS) }
        log("catalog providers: ${catalog.providerNames}")

        for (provider in PROVIDERS) {
            val listing = catalog.provider(provider)
            val episode = listing?.episodes(Category.SUB)?.firstOrNull { it.displayNumber == EPISODE }
            if (episode == null) {
                log("$provider: does not list episode $EPISODE (sub)")
                continue
            }
            val attempt = runCatching { runBlocking { anivexa.getSources(episode.pipeId, null) } }
            attempt.exceptionOrNull()?.let {
                log("$provider: resolve threw ${it.javaClass.simpleName}: ${it.message}")
            }
            val sources = attempt.getOrNull() ?: continue
            log("$provider: ${sources.streams.size} streams, ${sources.subtitles.size} subtitles")
            sources.streams.forEachIndexed { index, stream ->
                log(
                    "  [$index] type=${stream.type} quality=${stream.quality} " +
                        "h=${stream.height} active=${stream.isActive} embed=${stream.isEmbed} " +
                        "key=${stream.playlistKey != null} " +
                        "canDownload=${EpisodeDownloads.canDownload(stream)} " +
                        "host=${hostOf(stream)}",
                )
            }
            sources.subtitles.forEachIndexed { index, subtitle ->
                log("  sub[$index] ${subtitle.language} / ${subtitle.label}")
            }
            val played = pickProviderStream(provider, sources)
            val downloaded = sources.streams.firstOrNull(EpisodeDownloads::canDownload)
            log("  PLAYER picks   : ${describe(played)}")
            log("  DOWNLOADER picks: ${describe(downloaded)}")
            if (played != null && downloaded != null && played.url != downloaded.url) {
                log("  >>> MISMATCH: the download is not the stream the player would use")
            }

            if (downloaded == null) continue
            probeEnqueue(context, provider, episode.displayNumber, downloaded, sources.subtitles.size)
        }
    }

    /** Prepares a real download so the manifest's own rendition ladder and text tracks are seen. */
    private fun probeEnqueue(
        context: Context,
        provider: String,
        episodeNumber: String,
        stream: StreamItem,
        subtitleCount: Int,
    ) {
        val metadata = EpisodeDownloadMetadata(
            anilistId = ANILIST_ID,
            seriesTitle = TITLE,
            episodeNumber = episodeNumber,
            provider = provider,
            category = Category.SUB.api,
            referer = stream.referer,
            headers = stream.headers,
        )
        val downloadId = EpisodeDownloads.idFor(ANILIST_ID, Category.SUB.api, episodeNumber)
        EpisodeDownloads.removeDirectly(context, downloadId)
        Thread.sleep(1_500)

        val settled = CountDownLatch(1)
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            EpisodeDownloads.enqueue(context, metadata, stream, DownloadQuality.BEST) { result ->
                failure = result.exceptionOrNull()
                settled.countDown()
            }
        }
        if (!settled.await(ENQUEUE_TIMEOUT_S, TimeUnit.SECONDS)) {
            log("  enqueue($provider): no result within ${ENQUEUE_TIMEOUT_S}s")
            return
        }
        if (failure != null) {
            log("  enqueue($provider): failed ${failure?.javaClass?.simpleName}: ${failure?.message}")
            return
        }
        Thread.sleep(3_000)
        val download = EpisodeDownloads.downloads(context).value.firstOrNull { it.id == downloadId }
        log(
            "  enqueue($provider): recorded quality=${download?.metadata?.quality} " +
                "streamType=${download?.metadata?.streamType} " +
                "requested=${download?.metadata?.requestedQuality} " +
                "sidecarSubtitles=$subtitleCount state=${download?.state}",
        )
        EpisodeDownloads.removeDirectly(context, downloadId)
        Thread.sleep(1_000)
    }

    private fun describe(stream: StreamItem?): String = stream?.let {
        "type=${it.type} quality=${it.quality} h=${it.height} host=${hostOf(it)}"
    } ?: "none"

    private fun hostOf(stream: StreamItem): String? =
        runCatching { android.net.Uri.parse(stream.url).host }.getOrNull()

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private companion object {
        const val TAG = "DownloadProbe"

        /** Bleach, the title in the report. */
        const val ANILIST_ID = 269
        const val TITLE = "Bleach"
        const val EPISODE = "298"
        const val ENQUEUE_TIMEOUT_S = 90L

        val PROVIDERS = listOf("reanime", "anikoto", "kaa", "animeheaven")
    }
}
