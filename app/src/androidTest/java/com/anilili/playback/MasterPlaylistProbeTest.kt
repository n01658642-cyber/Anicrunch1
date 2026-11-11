package com.anilili.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anilili.data.AppGraph
import com.anilili.data.cache.AppCache
import com.anilili.data.model.Category
import com.anilili.data.remote.AniListClient
import com.anilili.data.remote.AnivexaClient
import com.anilili.data.remote.FlixcloudBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prints the decoded multivariant playlist a download prepares against.
 *
 * The selection probe showed ReAnime recording 480p for a `BEST` request while two other servers
 * recorded 1080p from the same title. Either the ladder ReAnime serves this app has no better
 * rendition in it, or the ladder is there and the download's track selection is not taking it —
 * and those are opposite fixes, so the playlist itself has to be read.
 */
@RunWith(AndroidJUnit4::class)
class MasterPlaylistProbeTest {
    @Test
    fun printTheLadderReanimeServes() {
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
            log("$PROVIDER does not list episode $EPISODE")
            return
        }
        val sources = runBlocking { anivexa.getSources(episode.pipeId, null) }
        val stream = sources.streams.firstOrNull(EpisodeDownloads::canDownload) ?: run {
            log("no downloadable stream")
            return
        }
        log("master: ${stream.url}")
        log("key: ${stream.playlistKey?.take(24)}… (${stream.playlistKey?.length} chars)")

        val headers = mediaRequestHeaders(
            referer = stream.referer,
            userAgent = USER_AGENT,
            extraHeaders = stream.headers,
        ) + ("User-Agent" to USER_AGENT)
        val master = read(stream.url, headers, stream.playlistKey)
        log("---- master playlist ----")
        master.lineSequence().take(60).forEach { log("  $it") }

        // Follow the first variant URI so the media playlist's own shape is on record too.
        val variant = master.lineSequence()
            .filterNot { it.startsWith("#") }
            .firstOrNull { it.isNotBlank() }
        if (variant != null) {
            val resolved = Uri.parse(stream.url).buildUpon()
                .path(Uri.parse(stream.url).path?.substringBeforeLast('/') + "/" + variant)
                .build()
                .toString()
                .takeUnless { variant.startsWith("http") } ?: variant
            log("---- first variant: $resolved ----")
            runCatching { read(resolved, headers, stream.playlistKey) }
                .onSuccess { media -> media.lineSequence().take(12).forEach { log("  $it") } }
                .onFailure { log("  variant read failed: ${it.message}") }
        }
    }

    private fun read(url: String, headers: Map<String, String>, key: String?): String {
        val source = FlixcloudPlaylistDataSource(
            OkHttpDataSource.Factory(AppGraph.httpClient)
                .setUserAgent(USER_AGENT)
                .setDefaultRequestProperties(headers)
                .createDataSource(),
        ) { key }
        source.open(DataSpec(Uri.parse(url)))
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == -1) break
            out.write(buffer, 0, read)
        }
        source.close()
        return out.toString("UTF-8")
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG: $line")
    }

    private companion object {
        const val TAG = "MasterProbe"
        const val ANILIST_ID = 269
        const val EPISODE = "298"
        val PROVIDER: String
            get() = InstrumentationRegistry.getArguments().getString("probeProvider") ?: "reanime"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/137.0.0.0 Mobile Safari/537.36"
    }
}
