package com.anilili.data.remote

import com.anilili.data.model.EpisodeMetadata
import com.anilili.data.model.Media
import com.anilili.data.model.streamingEpisodeMetadata
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Episode stills from Crunchyroll sources.
 *
 * Primary: AniList streamingEpisodes (almost always CR CDN thumbnails when licensed).
 */
class CrunchyrollEpisodeClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun episodeStills(media: Media): List<EpisodeMetadata> {
        val fromAniList = streamingEpisodeMetadata(media.streamingEpisodes)
        if (fromAniList.isNotEmpty()) {
            DiagnosticsLog.event(
                "CR stills from AniList streaming id=${media.id} rows=${fromAniList.size}",
            )
            return fromAniList
        }
        return searchCrunchyroll(media)
    }

    private fun searchCrunchyroll(media: Media): List<EpisodeMetadata> {
        val query = media.title.english ?: media.title.romaji ?: return emptyList()
        return try {
            val url =
                "https://www.crunchyroll.com/content/v2/discover/search" +
                    "?q=" + java.net.URLEncoder.encode(query, "UTF-8") +
                    "&n=6&type=series&locale=en-US"
            val body = get(url) ?: return emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            val items = root["data"]?.jsonArray
                ?: root["items"]?.jsonArray
                ?: return emptyList()
            val seriesId = items.firstOrNull()?.jsonObject
                ?.get("id")?.jsonPrimitive?.contentOrNull
            if (seriesId.isNullOrBlank()) return emptyList()
            DiagnosticsLog.event("CR search hit id=${media.id} series=$seriesId")
            emptyList()
        } catch (e: Exception) {
            DiagnosticsLog.throwable("Crunchyroll search failed id=${media.id}", e)
            emptyList()
        }
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }
}
