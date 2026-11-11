package com.anilili.data.remote

import com.anilili.data.auth.isJwtExpired
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class OpenSubtitleSearchResult(
    val fileId: Long,
    val language: String,
    val releaseName: String,
    val fileName: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
    val trusted: Boolean,
    val aiTranslated: Boolean,
    val machineTranslated: Boolean,
)

data class OpenSubtitleDownload(
    val url: String,
    val fileName: String,
    val remainingDownloads: Int?,
)

/**
 * Small client for the free OpenSubtitles REST API. The API consumer key identifies this app; the
 * viewer's account token is held in memory only and never written to disk. The password does not
 * stop here either, but it is not gone: OpenSubtitles issues no refresh token, so
 * [com.anilili.data.auth.OpenSubtitlesAuthManager] keeps the credentials in the device-bound
 * encrypted store and replays them to renew the session. AI translation/transcription endpoints
 * are deliberately outside this client.
 */
class OpenSubtitlesClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val apiKey: String,
    private val userAgent: String,
    private val baseUrl: String = "https://api.opensubtitles.com/api/v1",
) {
    @Volatile
    private var token: String? = null

    val isConfigured: Boolean get() = apiKey.isNotBlank()
    val isLoggedIn: Boolean get() = token?.takeUnless { isJwtExpired(it) } != null

    fun clearSession() {
        token = null
    }

    fun login(username: String, password: String) {
        requireConfigured()
        require(username.isNotBlank()) { "Enter your OpenSubtitles username." }
        require(password.isNotBlank()) { "Enter your OpenSubtitles password." }
        val body = buildJsonObject {
            put("username", username.trim())
            put("password", password)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = requestBuilder("$baseUrl/login")
            .post(body)
            .build()
        val root = executeJson(request)
        token = root["token"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("OpenSubtitles did not return a login token.")
    }

    fun search(
        query: String,
        languages: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): List<OpenSubtitleSearchResult> {
        requireConfigured()
        require(query.isNotBlank()) { "Enter a title to search for." }
        val normalizedLanguages = normalizeSubtitleLanguages(languages)
        require(normalizedLanguages.isNotBlank()) { "Enter at least one language code." }

        val url = "$baseUrl/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("query", query.trim())
            .addQueryParameter("languages", normalizedLanguages)
            .addQueryParameter("order_by", "download_count")
            .addQueryParameter("order_direction", "desc")
            .apply { seasonNumber?.takeIf { it > 0 }?.let { addQueryParameter("season_number", it.toString()) } }
            .apply { episodeNumber?.takeIf { it > 0 }?.let { addQueryParameter("episode_number", it.toString()) } }
            .build()
        return parseSearchResults(executeJson(requestBuilder(url.toString()).get().build()))
    }

    fun requestDownload(fileId: Long): OpenSubtitleDownload {
        requireConfigured()
        require(fileId > 0) { "Invalid OpenSubtitles file." }
        val body = buildJsonObject { put("file_id", fileId) }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val root = executeJson(requestBuilder("$baseUrl/download").post(body).build())
        val url = root["link"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("OpenSubtitles did not return a download link.")
        return OpenSubtitleDownload(
            url = url,
            fileName = root["file_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            remainingDownloads = root["remaining"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun requestBuilder(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("Api-Key", apiKey)
        .header("User-Agent", userAgent)
        .apply {
            token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
        }

    private fun executeJson(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            if (response.code == 401) token = null
            val message = runCatching {
                json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw IOException(message?.takeIf(String::isNotBlank) ?: "OpenSubtitles HTTP ${response.code}")
        }
        runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IOException("OpenSubtitles returned an invalid response.", it) }
    }

    private fun requireConfigured() {
        check(isConfigured) {
            "OpenSubtitles is not configured in this build. Add a free API consumer key first."
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun normalizeSubtitleLanguages(value: String): String = value
    .split(',', ' ', ';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .map(String::lowercase)
    .distinct()
    .take(3)
    .joinToString(",")

internal fun parseSearchResults(root: JsonObject): List<OpenSubtitleSearchResult> =
    root["data"]?.jsonArray.orEmpty().flatMap { item ->
        val attributes = item.jsonObject["attributes"]?.jsonObject ?: return@flatMap emptyList()
        val language = attributes["language"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val releaseName = attributes["release"]?.jsonPrimitive?.contentOrNull
            ?: attributes["feature_details"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
            ?: "OpenSubtitles"
        val downloads = attributes["download_count"]?.jsonPrimitive?.intOrNull ?: 0
        val hearingImpaired = attributes["hearing_impaired"]?.jsonPrimitive?.booleanOrNull == true
        val trusted = attributes["from_trusted"]?.jsonPrimitive?.booleanOrNull == true
        val aiTranslated = attributes["ai_translated"]?.jsonPrimitive?.booleanOrNull == true
        val machineTranslated = attributes["machine_translated"]?.jsonPrimitive?.booleanOrNull == true
        attributes["files"]?.jsonArray.orEmpty().mapNotNull { file ->
            val fileObject = file.jsonObject
            val fileId = fileObject["file_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            OpenSubtitleSearchResult(
                fileId = fileId,
                language = language,
                releaseName = releaseName,
                fileName = fileObject["file_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                downloads = downloads,
                hearingImpaired = hearingImpaired,
                trusted = trusted,
                aiTranslated = aiTranslated,
                machineTranslated = machineTranslated,
            )
        }
    }
