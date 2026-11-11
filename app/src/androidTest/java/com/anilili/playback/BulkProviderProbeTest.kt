package com.anilili.playback

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.AppGraph
import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import com.anilili.data.remote.FlixcloudBridge
import com.anilili.data.settings.DownloadQuality
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The anime page's queue, asked for the server the viewer pinned.
 *
 * The field report is "bulk downloading came from a default source and not the one I chose". The
 * queue resolves each episode itself through the repository, which walks the whole priority ladder
 * and *excludes* any server whose streams it judges undownloadable, so a pin can be lost without
 * anything appearing on screen. This reports which server each episode actually came from, and at
 * what resolution, then throws the downloads away.
 *
 * The catalogue is the merged one the anime page would hold, not a single named provider, because
 * the ladder-walk is the thing under test.
 */
@RunWith(AndroidJUnit4::class)
class BulkProviderProbeTest {
    @Test
    fun whichServerTheQueueActuallyUses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppGraph.init(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            FlixcloudBridge.attach(WebView(context))
        }
        val catalog = runBlocking { AppGraph.repository.episodes(ANILIST_ID) }
        log("catalogue: ${catalog.providerNames}")

        val episodes = catalog.provider(PREFERRED)?.episodes(Category.SUB)
            ?.filter { it.displayNumber in EPISODES }
            .orEmpty()
        log("${PREFERRED} lists ${episodes.map(com.anilili.data.model.EpisodeItem::displayNumber)}")
        if (episodes.isEmpty()) return

        for (episode in episodes) {
            val downloadId = EpisodeDownloads.idFor(ANILIST_ID, Category.SUB.api, episode.displayNumber)
            EpisodeDownloads.removeDirectly(context, downloadId)
            Thread.sleep(1_000)
            BulkEpisodeDownloads.enqueueEpisode(
                context = context,
                anilistId = ANILIST_ID,
                seriesTitle = TITLE,
                artworkUrl = null,
                category = Category.SUB,
                preferredProvider = PREFERRED,
                variant = VARIANT,
                episode = episode,
                catalog = catalog,
                quality = QUALITY,
            )
            var recorded: EpisodeDownload? = null
            val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
            while (System.currentTimeMillis() < deadline && recorded == null) {
                Thread.sleep(3_000)
                recorded = EpisodeDownloads.downloads(context).value.firstOrNull { it.id == downloadId }
            }
            log(
                "episode ${episode.displayNumber}: provider=${recorded?.metadata?.provider} " +
                    "quality=${recorded?.metadata?.quality} requested=${QUALITY.label} " +
                    "variant=${recorded?.metadata?.variant} asked=${VARIANT.route} " +
                    "subtitles=${recorded?.metadata?.subtitles?.size} " +
                    "attempted=${recorded?.metadata?.attemptedProviders}",
            )
            EpisodeDownloads.removeDirectly(context, downloadId)
            Thread.sleep(1_500)
        }
        BulkEpisodeDownloads.cancel()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private companion object {
        const val TAG = "BulkProbe"
        val ANILIST_ID: Int
            get() = InstrumentationRegistry.getArguments().getString("probeAnilistId")?.toIntOrNull()
                ?: 269
        const val TITLE = "Bleach"
        val PREFERRED: String
            get() = InstrumentationRegistry.getArguments().getString("probeProvider") ?: "reanime"
        val VARIANT: SourceVariant
            get() = SourceVariant.fromRoute(
                InstrumentationRegistry.getArguments().getString("probeVariant") ?: "sub",
            )
        val QUALITY: DownloadQuality
            get() = DownloadQuality.fromStored(
                InstrumentationRegistry.getArguments().getString("probeQuality"),
            )
        val EPISODES: Set<String>
            get() = (InstrumentationRegistry.getArguments().getString("probeEpisodes") ?: "298,299,300")
                .split(',')
                .map(String::trim)
                .toSet()
    }
}
