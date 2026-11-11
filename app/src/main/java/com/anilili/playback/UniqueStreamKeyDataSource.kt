package com.anilili.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.anilili.diagnostics.DiagnosticsLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * UniqueStream's HLS playlists declare standard AES-128 encryption, but the `key.bin` they point
 * at is neither raw nor freely served: the site wraps the key in base64 and only sends it to
 * requests carrying an `x-am-media-id` header its own player adds. The wrapping is theatre —
 * unwrapped, the key is exactly the episode's media id read as hex bytes (verified against the
 * site's Shaka response filter and by decrypting a segment to a 0x47-synced TS header).
 *
 * This source therefore never lets `key.bin` requests leave the device: while the active stream
 * carries a `uniquestream:<mediaId>` playlist key it answers them with the hex-decoded 16 bytes,
 * which also sidesteps the header requirement and the CDN's burst black-holing for key fetches.
 * Everything else passes straight through to the upstream untouched.
 */
@UnstableApi
internal class UniqueStreamKeyDataSource(
    private val upstream: DataSource,
    private val playlistKey: () -> String?,
) : DataSource {
    private var buffered: ByteArray? = null
    private var bufferedPosition = 0
    private var requestedUri: Uri? = null
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        buffered = null
        bufferedPosition = 0
        requestedUri = null
        // Media3 reuses one chunk's DataSource for its initialisation segment and then its media,
        // so this can be reopened without a close in between. Closed unconditionally rather than
        // on the flag, which was measured drifting false while the delegate was open. See
        // KwikPlaylistDataSource.
        upstreamOpen = false
        runCatching { upstream.close() }

        val keyBytes = uniqueStreamKeyBytes(playlistKey())
        val path = dataSpec.uri.path.orEmpty()
        if (keyBytes == null || dataSpec.position != 0L || !path.endsWith("key.bin", ignoreCase = true)) {
            val length = upstream.open(dataSpec)
            upstreamOpen = true
            return length
        }

        if (synthesizedLogCount.getAndIncrement() < 3) {
            DiagnosticsLog.event("UniqueStream key synthesized host=${dataSpec.uri.host ?: "unknown"}")
        }
        requestedUri = dataSpec.uri
        buffered = keyBytes
        return keyBytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = buffered ?: return upstream.read(buffer, offset, length)
        if (bufferedPosition >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - bufferedPosition)
        data.copyInto(buffer, offset, bufferedPosition, bufferedPosition + count)
        bufferedPosition += count
        return count
    }

    override fun getUri(): Uri? = requestedUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (buffered != null) emptyMap() else upstream.responseHeaders

    override fun close() {
        buffered = null
        bufferedPosition = 0
        requestedUri = null
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    private companion object {
        val synthesizedLogCount = AtomicInteger(0)
    }
}

/**
 * The AES-128 segment key for a `uniquestream:<mediaId>` playlist key: the media id as hex.
 * Null for anything that is not a well-formed UniqueStream marker, so callers pass through.
 */
internal fun uniqueStreamKeyBytes(playlistKey: String?): ByteArray? {
    val mediaId = playlistKey
        ?.takeIf { it.startsWith("uniquestream:") }
        ?.removePrefix("uniquestream:")
        ?: return null
    if (mediaId.length != 32) return null
    return runCatching {
        ByteArray(16) { index -> mediaId.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}
