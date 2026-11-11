package com.anilili.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

/**
 * Rewrites the queue that is handed to a Cast receiver so it can fetch what this device can.
 *
 * A receiver makes its own requests and takes none of our headers, so two things this app relies
 * on silently stop being true the moment playback moves off the phone: a `Referer` no longer
 * reaches a gated CDN, and a subtitle host that omits CORS is refused by the receiver's own
 * fetch. [StreamCorsProxy] answers both by carrying the referer inside the URL and replying with
 * `Access-Control-Allow-Origin: *`, so the rewrite is only ever a change of address.
 *
 * Deliberately narrow about what it rewrites:
 *
 * - **The stream, only when the provider named a referer.** That is this app's own signal that the
 *   source is gated; a source that never asked for one plays on a receiver today, and routing it
 *   through third-party infrastructure could only make it slower or more fragile.
 * - **WebVTT subtitles, always.** CORS is a separate gate from the referer, and a track dropped by
 *   the receiver looks exactly like a source with no subtitles. TTML is left alone because the
 *   proxy answers `text/vtt` and re-labelling a track is not a translation.
 *
 * **Nothing is published unverified.** [StreamCorsProxy] is third-party infrastructure that its own
 * documentation says must be survivable, and it refuses some pairs outright — measured 2026-09-20,
 * it answers 500 with an empty body for a `megaplay.buzz` referer carrying a target on another host,
 * which is exactly the pair anikoto produces (`fetch.nexabloom.top`). A receiver handed that fetches
 * the 500 and reports nothing but `STATE_IDLE`, so the caller asks first ([proxyProbeUrl]) and
 * passes the answer in as `proxyServing`.
 *
 * Since some point after 2026-09-20 it also allowlists the request's `Origin` to miruro's own
 * sites, which a receiver page can never send — so for casting it now refuses everything, and the
 * caller's question has to be asked with a receiver's Origin to hear that (see
 * `PlaybackService.receiverCanFetch`).
 *
 * The original items are kept by the caller and restored on the way back, so a local player never
 * inherits the detour. [restoreLocalItem] is the fallback for when they cannot be: `mediaId` is
 * the stream URL the player screen prepared, which is enough to undo the address change.
 */
internal object CastStreamRouting {

    /**
     * Whether a receiver could play this queue at all, before any question of what it can reach.
     *
     * A `playlistKey` is the one disqualifier, and it is absolute: those playlists arrive XOR'd and
     * are decoded by [FlixcloudPlaylistDataSource] and [UniqueStreamKeyDataSource], which are
     * `DataSource` wrappers on *this* device. A receiver fetching the same address gets the
     * encoded bytes and no way to read them, so ReAnime and UniqueStream cannot be cast however
     * healthy the network is. `EpisodeDownloads.canDownload` gates the downloader the same way, for
     * the same reason — it just gained the decoders and this cannot.
     *
     * An empty queue means the episode is playing in a WebView rather than here (an embed source),
     * which has no addresses to hand over either.
     *
     * Asked so a viewer is told, rather than left waiting out the load watchdog for something that
     * was never going to start; see `PlaybackService.handOverToReceiver`.
     */
    fun canCastQueue(items: List<MediaItem>, playlistKey: String?): Boolean =
        items.isNotEmpty() && playlistKey == null

    /**
     * The one address worth asking [StreamCorsProxy] about before handing this queue over, or null
     * when nothing in it would go through the proxy.
     *
     * The stream when the provider named a referer, because that is the address the episode cannot
     * start without; otherwise the first WebVTT track, which on an ungated source is the only thing
     * the detour is carrying. Reading the first item alone is enough — this app hands a receiver one
     * episode, and every handover on record is `items=1`.
     */
    fun proxyProbeUrl(items: List<MediaItem>, referer: String?): String? {
        val item = items.firstOrNull() ?: return null
        val config = item.localConfiguration ?: return null
        val source = config.uri.toString()
        val seed = item.mediaId.takeIf { it.isNotBlank() } ?: source
        referer?.takeIf(String::isNotBlank)
            ?.let { StreamCorsProxy.playlist(source, it, seed) }
            ?.let { return it }
        return config.subtitleConfigurations
            .firstOrNull { it.mimeType?.equals(MimeTypes.TEXT_VTT, ignoreCase = true) == true }
            ?.let { StreamCorsProxy.subtitle(it.uri.toString(), referer, seed) }
    }

    /**
     * The queue to hand a receiver, or [items] unchanged when nothing in it needs the proxy.
     *
     * [proxyServing] is the answer to [proxyProbeUrl] — false means the proxy refused the address
     * this was about to publish, and the detour is then worse than no detour: the receiver would
     * fetch a 500 and report the silent load failure this whole file exists to avoid. So the
     * addresses go over as they stand, which is exactly what an ungated source casts with today.
     */
    fun castItems(items: List<MediaItem>, referer: String?, proxyServing: Boolean): List<MediaItem> =
        items.map { castItem(it, referer, proxyServing) }

    /** The queue to hand back to the local player, undoing [castItems] by URL. */
    fun localItems(items: List<MediaItem>): List<MediaItem> = items.map(::restoreLocalItem)

    private fun castItem(item: MediaItem, referer: String?, proxyServing: Boolean): MediaItem {
        val source = item.localConfiguration?.uri?.toString() ?: return item
        val seed = item.mediaId.takeIf { it.isNotBlank() } ?: source
        val original = item.localConfiguration?.subtitleConfigurations.orEmpty()
        // A gated stream is the only one worth the detour; see the class comment.
        val streamUrl = referer?.takeIf { it.isNotBlank() && proxyServing }
            ?.let { StreamCorsProxy.playlist(source, it, seed) }
        val subtitles = original.mapNotNull { subtitle ->
            val isVtt = subtitle.mimeType?.equals(MimeTypes.TEXT_VTT, ignoreCase = true) == true
            if (!isVtt) return@mapNotNull subtitle
            // A WebVTT track is on the cast queue *because* the proxy lends it CORS. Without that
            // there is no knowing whether its host answers a receiver, and Google's receiver fails
            // the whole load over one track it cannot fetch — so the track goes rather than the
            // episode. In-manifest renditions are untouched, and the picture is what was asked for.
            if (!proxyServing) return@mapNotNull null
            val proxied = StreamCorsProxy.subtitle(subtitle.uri.toString(), referer, seed)
                ?: return@mapNotNull subtitle
            subtitle.buildUpon().setUri(Uri.parse(proxied)).build()
        }
        if (streamUrl == null && subtitles == original) return item
        return item.buildUpon()
            .apply { streamUrl?.let { setUri(it) } }
            .setSubtitleConfigurations(subtitles)
            .build()
    }

    /**
     * One item with its proxied stream address swapped back for the one it was prepared from.
     *
     * Subtitles are left as they are: a proxied WebVTT URL is a working WebVTT URL locally too,
     * and this path only runs when the snapshot taken on the way out has been lost.
     */
    fun restoreLocalItem(item: MediaItem): MediaItem {
        val current = item.localConfiguration?.uri?.toString() ?: return item
        if (!StreamCorsProxy.isProxied(current)) return item
        val original = item.mediaId.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return item
        return item.buildUpon().setUri(original).build()
    }
}
