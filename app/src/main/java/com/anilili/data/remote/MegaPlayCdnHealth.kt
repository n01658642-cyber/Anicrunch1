package com.anilili.data.remote

import com.anilili.data.remote.LegacyTrustAnchors.withLegacyTrustAnchors
import com.anilili.diagnostics.DiagnosticsLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val HEALTH_POLL_MS = 30_000L
private const val FAILED_HOST_STICKY_MS = 5 * 60_000L
private val MEGAPLAY_MEDIA_PATH = Regex("^/anime/[a-f0-9]{32}/[a-f0-9]{32}/", RegexOption.IGNORE_CASE)

internal fun isMegaPlayMedia(url: HttpUrl, referer: String?): Boolean =
    url.isHttps && url.port == 443 &&
        referer?.toHttpUrlOrNull()?.host == "megaplay.buzz" &&
        MEGAPLAY_MEDIA_PATH.containsMatchIn(url.encodedPath)

/** The health endpoint supplies DNS hosts, not arbitrary URLs or addresses. */
private fun healthHost(value: String?): String? {
    val host = value?.lowercase()?.trim()?.removeSuffix(".") ?: return null
    if (host.length !in 4..253 || !host.contains('.')) return null
    if (!host.last().isLetter() || host.endsWith(".localhost") || host.endsWith(".local") ||
        host.endsWith(".internal") || host.endsWith(".test") || host.endsWith(".invalid")) return null
    val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    return host.takeIf { it.split('.').all(label::matches) }
}

/** Same five-minute failed-host memory as newclient v4.17, bounded and expiry-safe on an outage. */
internal class MegaPlayHealthState(private val nowMs: () -> Long) {
    private val failedUntil = linkedMapOf<String, Long>()
    private var fallback: String? = null

    @Synchronized fun accept(body: String): Boolean {
        if (body.length > 16_384) return false
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
        val replacement = healthHost((root["fallback"] as? JsonPrimitive)?.contentOrNull) ?: return false
        val failed = root["failed"] as? JsonArray ?: return false
        if (failed.size > 64) return false
        val hosts = failed.map { healthHost((it as? JsonPrimitive)?.contentOrNull) ?: return false }
        val now = nowMs()
        failedUntil.entries.removeAll { it.value <= now }
        fallback = replacement
        hosts.filterNot { it == replacement }.forEach { failedUntil[it] = now + FAILED_HOST_STICKY_MS }
        while (failedUntil.size > 64) failedUntil.remove(failedUntil.keys.first())
        return true
    }

    @Synchronized fun rewrite(url: HttpUrl): HttpUrl {
        val replacement = fallback ?: return url
        if ((failedUntil[url.host] ?: 0L) <= nowMs()) return url
        if (url.host == replacement || url.host.endsWith(".$replacement")) return url
        return url.newBuilder().host(replacement).build()
    }
}

/**
 * Shared by native resolution, either playback transport, downloads and the local player proxy.
 * Refresh is single-flight and never awaited by a media request. No timer survives playback:
 * active requests poll at most once per 30 seconds, and stale failure rules expire in five minutes.
 */
object MegaPlayCdnHealth {
    private fun nowMs() = System.nanoTime() / 1_000_000
    private val state = MegaPlayHealthState(::nowMs)
    private val lock = Any()
    private var nextRefreshMs = 0L
    private var refreshing = false
    private val client by lazy {
        OkHttpClient.Builder().withLegacyTrustAnchors().dns(AppDns).addInterceptor(AppDns.InterceptionRetry)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS).build()
    }

    fun rewrite(url: String, referer: String?): String {
        if (referer == null) return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (!isMegaPlayMedia(parsed, referer)) return url
        refreshIfNeeded()
        return state.rewrite(parsed).toString()
    }

    fun refreshIfNeeded() {
        synchronized(lock) {
            val now = nowMs()
            if (refreshing || now < nextRefreshMs) return
            refreshing = true
            nextRefreshMs = now + HEALTH_POLL_MS
        }
        try {
            val request = Request.Builder()
                .url("https://megaplay.buzz/lib/check_domain.json?cache_burst=${System.currentTimeMillis()}")
                .header("Accept", "application/json").header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = finished(e)

                override fun onResponse(call: Call, response: Response) {
                    val failure = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException("MegaPlay health HTTP ${it.code}")
                            val source = it.body?.source() ?: throw IOException("Empty MegaPlay health")
                            if (source.request(16_385)) throw IOException("Oversized MegaPlay health")
                            if (!state.accept(source.readUtf8())) throw IOException("Invalid MegaPlay health")
                        }
                    }.exceptionOrNull()
                    finished(failure)
                }
            })
        } catch (e: Exception) {
            finished(e)
        }
    }

    private fun finished(error: Throwable?) {
        synchronized(lock) { refreshing = false }
        if (error != null) DiagnosticsLog.throwable("MegaPlay health refresh failed (retaining unexpired rules)", error)
    }
}

class MegaPlayCdnHealthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val rewritten = MegaPlayCdnHealth.rewrite(original.url.toString(), original.header("Referer"))
        val request = if (rewritten == original.url.toString()) original else original.newBuilder().url(rewritten).build()
        return chain.proceed(request)
    }
}
