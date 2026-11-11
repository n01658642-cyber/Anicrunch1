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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Takes one episode all the way: resolve, download, export, and report what the viewer ends up
 * holding — resolution, size, and whether any subtitle reached the Downloads folder beside the MP4.
 *
 * Reports rather than asserts: the question being asked is "what does this actually produce", and
 * a failed assertion would hide the rest of the answer.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndDownloadProbeTest {
    @Test
    fun downloadAndExportOneEpisode() {
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
        val catalog = runBlocking { anivexa.getEpisodes(ANILIST_ID, providers = listOf(PROVIDER)) }
        val episode = catalog.provider(PROVIDER)?.episodes(Category.SUB)
            ?.firstOrNull { it.displayNumber == EPISODE } ?: run {
            log("$PROVIDER does not list episode $EPISODE"); return
        }
        val sources = runBlocking { anivexa.getSources(episode.pipeId, null) }
        val stream = sources.streams.firstOrNull(EpisodeDownloads::canDownload) ?: run {
            log("no downloadable stream"); return
        }
        log("stream ${stream.type} ${stream.quality}; ${sources.subtitles.size} sidecar subtitles")

        val metadata = EpisodeDownloadMetadata(
            anilistId = ANILIST_ID,
            seriesTitle = TITLE,
            episodeNumber = EPISODE,
            provider = PROVIDER,
            category = Category.SUB.api,
            referer = stream.referer,
            headers = stream.headers,
            subtitles = sources.subtitles.map {
                EpisodeDownloadSubtitle(
                    url = it.url,
                    label = it.label,
                    language = it.language,
                    referer = it.referer,
                    headers = it.headers,
                )
            },
        )
        val downloadId = EpisodeDownloads.idFor(ANILIST_ID, Category.SUB.api, EPISODE)
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
        enqueued.await(120, TimeUnit.SECONDS)
        if (failure != null) {
            log("enqueue failed: ${failure?.message}"); return
        }

        var download: EpisodeDownload? = null
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(DOWNLOAD_TIMEOUT_MIN)
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            download = EpisodeDownloads.downloads(context).value.firstOrNull { it.id == downloadId }
            log(
                "  state=${download?.state} bytes=${download?.bytesDownloaded} " +
                    "" +
                    "subs=${download?.subtitleDownloaded}/${download?.subtitleTotal}",
            )
            if (download?.isComplete == true || download?.state == EpisodeDownloadState.FAILED) break
        }
        log("download finished: state=${download?.state} quality=${download?.metadata?.quality}")
        if (download?.isComplete != true) return

        EpisodeExport.request(context, downloadId, deleteAppCopyAfter = false)
        val exportDeadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(EXPORT_TIMEOUT_MIN)
        var exported: ExportedEpisode? = null
        while (System.currentTimeMillis() < exportDeadline) {
            Thread.sleep(5_000)
            exported = EpisodeExport.exported(context).value.firstOrNull { it.downloadId == downloadId }
            val status = EpisodeExport.statuses(context).value[downloadId]
            log("  export state=${status?.state} failure=${status?.failure?.code}")
            if (exported != null || status?.state == EpisodeExportState.FAILED) break
        }
        log(
            "exported: file=${exported?.fileName} size=${exported?.sizeBytes} " +
                "quality=${exported?.metadata?.quality} sidecars=${exported?.subtitles?.size}",
        )
        exported?.subtitles?.forEach { log("  sidecar: ${it.uri} (${it.language}/${it.label})") }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private companion object {
        const val TAG = "E2EProbe"
        const val ANILIST_ID = 269
        const val TITLE = "Bleach"
        const val EPISODE = "298"
        const val PROVIDER = "reanime"
        const val DOWNLOAD_TIMEOUT_MIN = 12L
        const val EXPORT_TIMEOUT_MIN = 6L
    }
}
