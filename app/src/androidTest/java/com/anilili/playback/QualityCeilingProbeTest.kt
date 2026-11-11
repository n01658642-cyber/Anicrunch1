package com.anilili.playback

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.AppGraph
import com.anilili.data.cache.AppCache
import com.anilili.data.model.Category
import com.anilili.data.remote.AniListClient
import com.anilili.data.remote.AnivexaClient
import com.anilili.data.settings.DownloadQuality
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the resolution the viewer picks actually bound what the download takes?
 *
 * Asked on a server whose ladder is known to reach 1080p for this episode, so a ceiling that is
 * ignored and a ceiling that is honoured produce different numbers.
 */
@RunWith(AndroidJUnit4::class)
class QualityCeilingProbeTest {
    @Test
    fun eachCeilingAgainstAKnown1080pLadder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppGraph.init(context)
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
            log("$PROVIDER does not list $EPISODE"); return
        }

        for (quality in DownloadQuality.entries) {
            val sources = runBlocking { anivexa.getSources(episode.pipeId, null) }
            val stream = sources.streams.firstOrNull(EpisodeDownloads::canDownload) ?: continue
            val metadata = EpisodeDownloadMetadata(
                anilistId = ANILIST_ID,
                seriesTitle = TITLE,
                episodeNumber = EPISODE,
                provider = PROVIDER,
                category = Category.SUB.api,
                referer = stream.referer,
                headers = stream.headers,
            )
            val downloadId = EpisodeDownloads.idFor(ANILIST_ID, Category.SUB.api, EPISODE)
            EpisodeDownloads.removeDirectly(context, downloadId)
            Thread.sleep(1_500)
            val settled = CountDownLatch(1)
            var failure: Throwable? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                EpisodeDownloads.enqueue(context, metadata, stream, quality) { result ->
                    failure = result.exceptionOrNull()
                    settled.countDown()
                }
            }
            settled.await(90, TimeUnit.SECONDS)
            Thread.sleep(2_500)
            val recorded = EpisodeDownloads.downloads(context).value.firstOrNull { it.id == downloadId }
            log(
                "requested=${quality.label} -> recorded=${recorded?.metadata?.quality} " +
                    "failure=${failure?.message}",
            )
            EpisodeDownloads.removeDirectly(context, downloadId)
            Thread.sleep(1_500)
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private companion object {
        const val TAG = "QualityProbe"
        const val ANILIST_ID = 269
        const val TITLE = "Bleach"
        const val EPISODE = "298"
        val PROVIDER: String
            get() = InstrumentationRegistry.getArguments().getString("probeProvider") ?: "anikoto"
    }
}
