package com.anilili.data.update

import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.raise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Primary update channel for this fork: public GitHub Releases on
 * n01658642-cyber/Anicrunch1. "Check for updates" in Settings hits latest release
 * and picks an APK asset (prefer arm64, then universal).
 */
object GitHubUpdateSource {
    private const val OWNER = "n01658642-cyber"
    private const val REPO = "Anicrunch1"
    private const val API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    fun fetchManifest(client: OkHttpClient, json: Json, preferArm64: Boolean = true): UpdateManager.UpdateInfo {
        val request = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Anime-UpdateCheck")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AnililiError.UPDATE_NETWORK_FAILED.raise(
                    detail = "GitHub releases HTTP ${response.code}",
                )
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                AnililiError.UPDATE_NETWORK_FAILED.raise(detail = "Empty GitHub release body")
            }
            return parseRelease(json, body, preferArm64)
        }
    }

    fun parseRelease(json: Json, body: String, preferArm64: Boolean): UpdateManager.UpdateInfo {
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
        val name = root["name"]?.jsonPrimitive?.content.orEmpty()
        val notes = root["body"]?.jsonPrimitive?.content.orEmpty()
        val assets = root["assets"] as? JsonArray ?: JsonArray(emptyList())

        data class Asset(val name: String, val url: String, val size: Long)

        val list = assets.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val n = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!n.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
            val url = o["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val size = o["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            Asset(n, url, size)
        }
        if (list.isEmpty()) {
            AnililiError.UPDATE_NETWORK_FAILED.raise(detail = "No APK assets on latest release")
        }

        fun score(a: Asset): Int {
            val n = a.name.lowercase()
            return when {
                preferArm64 && n.contains("arm64") -> 0
                n.contains("universal") || (!n.contains("armeabi") && !n.contains("arm64") && !n.contains("x86")) -> 1
                n.contains("armeabi") -> 2
                else -> 3
            }
        }
        val chosen = list.sortedBy(::score).first()
        val mirrors = list.filter { it.url != chosen.url }.map { it.url }

        // Version from tag: v-crunchy-... stays as tag; prefer versionName-looking tags
        val version = tag.removePrefix("v").ifBlank { name.ifBlank { tag } }
        DiagnosticsLog.event("GitHub update: tag=$tag asset=${chosen.name} size=${chosen.size}")
        return UpdateManager.UpdateInfo(
            version = version,
            changelog = notes.ifBlank { name.ifBlank { tag } },
            apkUrl = chosen.url,
            sizeBytes = chosen.size,
            mirrors = mirrors,
            build = null,
        )
    }
}
