package com.anilili.data.remote

import com.anilili.data.model.EpisodeMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.parseToJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal Jikan (MyAnimeList) client. Only used for per-episode filler flags,
 * which AniList doesn't track.
 */
class JikanClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    /**
     * Episode numbers marked as filler on MAL. Empty when the title has none.
     *
     * Throws when any page fails rather than answering with what it has: the answer is cached for a
     * week, and a Jikan outage (it answers 504 whenever MAL refuses it) used to be stored as
     * "no filler" — or as the first page's fillers only — for that whole week.
     */
    fun fillerEpisodes(malId: Int): Set<Int> {
        val fillers = mutableSetOf<Int>()
        var page = 1
        while (page <= MAX_PAGES) {
            // Jikan allows three requests a second; a long series is a dozen pages back to back.
            if (page > 1) Thread.sleep(PAGE_SPACING_MS)
            val request = Request.Builder()
                .url("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page")
                .header("Accept", "application/json")
                .build()
            val root = client.newCall(request).execute().use { response ->
                // MAL has no episode list for this id: an answer, not a failure.
                if (response.code == 404) return fillers
                if (!response.isSuccessful) error("Jikan HTTP ${response.code}")
                json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            }
            val episodes = root["data"]?.jsonArray ?: break
            episodes.forEach { element ->
                val episode = element.jsonObject
                if (episode["filler"]?.jsonPrimitive?.booleanOrNull == true) {
                    episode["mal_id"]?.jsonPrimitive?.intOrNull?.let(fillers::add)
                }
            }
            val hasNext = root["pagination"]?.jsonObject
                ?.get("has_next_page")?.jsonPrimitive?.booleanOrNull ?: false
            if (!hasNext) break
            page++
        }
        return fillers
    }

    /**
     * Episode preview thumbnails from MAL via Jikan `/anime/{id}/videos/episodes`.
     * Free, no API key. Only episodes that have a video/preview entry get a still.
     */
    fun episodeVideoStills(malId: Int): List<EpisodeMetadata> {
        val out = mutableListOf<EpisodeMetadata>()
        var page = 1
        while (page <= MAX_VIDEO_PAGES) {
            if (page > 1) Thread.sleep(PAGE_SPACING_MS)
            val request = Request.Builder()
                .url("https://api.jikan.moe/v4/anime/$malId/videos/episodes?page=$page")
                .header("Accept", "application/json")
                .build()
            val root = client.newCall(request).execute().use { response ->
                if (response.code == 404) return out
                if (!response.isSuccessful) error("Jikan videos HTTP ${response.code}")
                json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            }
            val episodes = root["data"]?.jsonArray ?: break
            episodes.forEach { element ->
                val episode = element.jsonObject
                val malEp = episode["mal_id"]?.jsonPrimitive?.intOrNull
                    ?: episode["episode"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
                    ?: return@forEach
                val images = episode["images"]?.jsonObject
                val jpg = images?.get("jpg")?.jsonObject
                val thumb = jpg?.get("image_url")?.jsonPrimitive?.contentOrNull
                    ?: jpg?.get("large_image_url")?.jsonPrimitive?.contentOrNull
                    ?: images?.get("image_url")?.jsonPrimitive?.contentOrNull
                if (thumb.isNullOrBlank()) return@forEach
                val title = episode["title"]?.jsonPrimitive?.contentOrNull
                out += EpisodeMetadata(
                    number = malEp.toDouble(),
                    title = title,
                    still = thumb,
                )
            }
            val hasNext = root["pagination"]?.jsonObject
                ?.get("has_next_page")?.jsonPrimitive?.booleanOrNull ?: false
            if (!hasNext) break
            page++
        }
        return out
    }

    private companion object {
        // 100 episodes per page; 15 pages covers the longest running series that matter.
        const val MAX_PAGES = 15
        const val MAX_VIDEO_PAGES = 25
        const val PAGE_SPACING_MS = 400L
    }
}
