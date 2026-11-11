package com.anilili.playback

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.AppGraph
import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import com.anilili.data.remote.FlixcloudBridge
import com.anilili.ui.watch.videoMimeType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What every server would hand a Cast receiver, one line per stream, for an off-device replay.
 *
 * The field request is "more servers that cast". A receiver fetches its queue itself, with none of
 * this app's headers and none of its `DataSource` wrappers, from a web page that needs CORS on every
 * playlist and segment — so whether a server casts is a property of the addresses it resolves to,
 * which this device can produce and the dev machine can then fetch the way a receiver would.
 *
 * Each line is the stream as resolved plus the queue [CastStreamRouting.castItems] would publish,
 * with the proxy assumed to be serving (the replay asks the proxy itself). Nothing is asserted:
 * this is a measurement, and a server that resolves nothing is a line saying so.
 */
@RunWith(AndroidJUnit4::class)
class CastReachProbeTest {
    @Test
    fun dumpWhatEachServerWouldCast() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppGraph.init(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            FlixcloudBridge.attach(WebView(context))
        }
        val catalog = runBlocking { AppGraph.repository.episodes(ANILIST_ID) }
        log("catalogue: ${catalog.providerNames}")

        for (provider in catalog.providers) {
            for (category in Category.entries) {
                val episode = provider.episodes(category).firstOrNull { it.number == EPISODE } ?: continue
                val line = JSONObject().put("provider", provider.name).put("category", category.api)
                val result = runCatching {
                    runBlocking {
                        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                            AppGraph.repository.sources(
                                episode.pipeId,
                                provider.name,
                                SourceVariant.defaultFor(category),
                                ANILIST_ID,
                                allowInteractiveChallenges = false,
                            )
                        }
                    }
                }
                val sources = result.getOrNull()
                if (sources == null) {
                    line.put("error", result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "timeout")
                    emit(line)
                    continue
                }
                val subtitles = JSONArray()
                sources.subtitles.forEach { subtitles.put(it.url) }
                line.put("subtitles", subtitles)
                val streams = JSONArray()
                sources.streams.forEach { stream ->
                    val mime = videoMimeType(stream.type, stream.url)
                    val item = MediaItem.Builder()
                        .setUri(stream.url)
                        .setMediaId(stream.url)
                        .setMimeType(mime)
                        .setSubtitleConfigurations(
                            sources.subtitles.map {
                                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it.url))
                                    .setMimeType(MimeTypes.TEXT_VTT)
                                    .build()
                            },
                        )
                        .build()
                    val castable = !stream.isEmbed && CastStreamRouting.canCastQueue(listOf(item), stream.playlistKey)
                    val cast = CastStreamRouting.castItems(listOf(item), stream.referer, proxyServing = true).single()
                    streams.put(
                        JSONObject()
                            .put("type", stream.type)
                            .put("mime", mime)
                            .put("quality", stream.quality)
                            .put("url", stream.url)
                            .put("referer", stream.referer ?: JSONObject.NULL)
                            .put("headers", JSONObject(stream.headers))
                            .put("keyed", stream.playlistKey != null)
                            .put("castable", castable)
                            .put("castUrl", cast.localConfiguration?.uri?.toString())
                            .put("probeUrl", CastStreamRouting.proxyProbeUrl(listOf(item), stream.referer) ?: JSONObject.NULL),
                    )
                }
                line.put("streams", streams)
                emit(line)
            }
        }
        log("done")
    }

    private fun emit(line: JSONObject) {
        // Logcat truncates long lines; chunk with a sequence number so the replay can reassemble.
        val text = line.toString()
        val id = System.nanoTime()
        text.chunked(CHUNK).forEachIndexed { index, part ->
            Log.i(TAG, "CHUNK $id ${index + 1}/${(text.length + CHUNK - 1) / CHUNK} $part")
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private companion object {
        const val TAG = "CastReach"
        const val CHUNK = 3_000
        const val RESOLVE_TIMEOUT_MS = 45_000L
        val ANILIST_ID: Int
            get() = InstrumentationRegistry.getArguments().getString("probeAnilistId")?.toIntOrNull() ?: 154_587
        val EPISODE: Double
            get() = InstrumentationRegistry.getArguments().getString("probeEpisode")?.toDoubleOrNull() ?: 1.0
    }
}
