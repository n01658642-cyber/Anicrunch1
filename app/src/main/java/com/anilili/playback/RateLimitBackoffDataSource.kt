package com.anilili.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.anilili.diagnostics.DiagnosticsLog
import java.io.IOException
import java.io.InterruptedIOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Waits out an HTTP 429 instead of failing the download on it.
 *
 * A source's CDN answering "Too Many Requests" partway through a season used to fail episode after
 * episode with DL-02: Media3's own retry waits 0–4s between its five attempts, so a whole episode
 * was written off in ~13s while the host was still serving 200s to other requests — a rate limit,
 * not a refusal. Reproduced on a Galaxy S25 Ultra on 2026-10-03: a ten-episode ReAnime batch lost
 * seven episodes this way, each failing on a playlist open that returned 429 in ~30ms.
 *
 * Two things change. A 429 is retried here after a real pause — the server's `Retry-After` when it
 * sends one, otherwise 5s doubling to 40s — before Media3 ever sees it. And the pause is recorded
 * per host in [HostCooldowns], which every request to that host waits on first, so the four segment
 * threads and the next episode in the queue back off together rather than each spending its own
 * attempts against a host that is already saying no.
 *
 * Only 429 is treated this way; every other failure passes through untouched, so a dead stream
 * still fails at the speed it always did.
 */
@UnstableApi
internal class RateLimitBackoffDataSource(
    private val upstream: DataSource,
    private val cooldowns: HostCooldowns,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val host = dataSpec.uri.host.orEmpty()
        return openWithRateLimitBackoff(
            host = host,
            cooldowns = cooldowns,
            rateLimit = ::rateLimitOf,
            onBackoff = { attempt, delayMs, retryAfter ->
                DiagnosticsLog.event(
                    "Download rate-limited host=$host attempt=$attempt waitMs=$delayMs " +
                        "retryAfter=${retryAfter ?: "none"}",
                )
            },
        ) {
            // Closed unconditionally before every open: Media3 reopens one chunk's source without
            // a close in between, and a failed open must not leave a half-open connection behind.
            runCatching { upstream.close() }
            upstream.open(dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}

/** A request the server refused for rate, with whatever `Retry-After` it sent. */
internal data class RateLimited(val retryAfter: String?)

/** Recognises a 429 from Media3's HTTP stack; null for every other failure. */
@UnstableApi
private fun rateLimitOf(error: IOException): RateLimited? {
    if (error !is HttpDataSource.InvalidResponseCodeException) return null
    if (error.responseCode != HTTP_TOO_MANY_REQUESTS) return null
    return RateLimited(
        error.headerFields.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull(),
    )
}

/**
 * Runs [open] for [host], waiting out the host's cooldown first and again after every rate-limited
 * failure, up to [MAX_RATE_LIMIT_RETRIES] retries. Anything [rateLimit] does not recognise, and the
 * last rate-limited failure, is rethrown as it was.
 */
internal fun <T> openWithRateLimitBackoff(
    host: String,
    cooldowns: HostCooldowns,
    rateLimit: (IOException) -> RateLimited?,
    nowMs: () -> Long = System::currentTimeMillis,
    onBackoff: (attempt: Int, delayMs: Long, retryAfter: String?) -> Unit = { _, _, _ -> },
    open: () -> T,
): T {
    var attempt = 0
    while (true) {
        cooldowns.awaitClear(host)
        try {
            return open()
        } catch (error: IOException) {
            val limited = rateLimit(error)
            if (limited == null || attempt >= MAX_RATE_LIMIT_RETRIES) throw error
            val delayMs = rateLimitDelayMs(attempt, limited.retryAfter, nowMs())
            cooldowns.coolDown(host, delayMs)
            attempt++
            onBackoff(attempt, delayMs, limited.retryAfter)
        }
    }
}

/**
 * When each host may next be asked, shared by every download request.
 *
 * [sleep] and [clock] are injectable for tests; the defaults block the calling download thread,
 * which is what Media3's segment executor expects of a DataSource. A cancelled download interrupts
 * that thread, and the interruption is surfaced as an [InterruptedIOException] so the cancel lands
 * immediately instead of after the cooldown.
 */
internal class HostCooldowns(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val until = ConcurrentHashMap<String, Long>()

    /** Pushes [host]'s next allowed request at least [delayMs] into the future. */
    fun coolDown(host: String, delayMs: Long) {
        val target = clock() + delayMs
        until.merge(host, target) { current, proposed -> maxOf(current, proposed) }
    }

    /** Blocks until [host] is out of its cooldown, if it is in one. */
    fun awaitClear(host: String) {
        while (true) {
            val remaining = (until[host] ?: return) - clock()
            if (remaining <= 0) {
                until.remove(host)
                return
            }
            try {
                sleep(remaining)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Cancelled while waiting out a rate limit")
            }
        }
    }
}

/**
 * How long to wait before retry number [attempt] (0-based) after a 429.
 *
 * A `Retry-After` the server sent wins — as seconds or as an HTTP date — clamped to
 * [MIN_RATE_LIMIT_WAIT_MS]..[MAX_RATE_LIMIT_WAIT_MS] so a missing or absurd value cannot make the
 * download either hammer the host or hang for an hour. Without one, 5s doubling: 5, 10, 20, 40.
 */
internal fun rateLimitDelayMs(attempt: Int, retryAfter: String?, nowMs: Long): Long {
    val advertised = retryAfter?.trim()?.let { value ->
        value.toLongOrNull()?.times(1_000)
            ?: runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)!!.time - nowMs
            }.getOrNull()
    }
    val delay = advertised ?: (BASE_RATE_LIMIT_WAIT_MS shl attempt.coerceIn(0, 10))
    return delay.coerceIn(MIN_RATE_LIMIT_WAIT_MS, MAX_RATE_LIMIT_WAIT_MS)
}

private const val HTTP_TOO_MANY_REQUESTS = 429

/** Retries per open before the 429 is handed to Media3; 5+10+20+40s = 75s at most per request. */
internal const val MAX_RATE_LIMIT_RETRIES = 4
private const val BASE_RATE_LIMIT_WAIT_MS = 5_000L
private const val MIN_RATE_LIMIT_WAIT_MS = 1_000L
private const val MAX_RATE_LIMIT_WAIT_MS = 120_000L
