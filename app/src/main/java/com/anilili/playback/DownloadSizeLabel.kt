package com.anilili.playback

import java.util.Locale

internal fun formatDownloadBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
}

/**
 * How far through the transfer is, as "6.5 MB / 37 MB" — or just "6.5 MB" when the total is not
 * known, which is the honest answer rather than a guess.
 *
 * A download in flight used to report only the bytes it had already fetched, which says nothing
 * about how much longer it has to go or whether the device has room for the rest.
 *
 * **The total is only available for some downloads.** Media3 has a real [contentLength] for a
 * progressive file, where it is the response's own Content-Length, but an HLS download is a
 * playlist of segments whose sizes are published nowhere: unless every segment carries an
 * `EXT-X-BYTERANGE` — and these providers' do not — `SegmentDownloader` leaves the length unset and
 * measures progress by counting segments instead. Those downloads keep showing the fetched size
 * alone. The total could be inferred from the segment fraction, but an inferred number in a field
 * people will read as the real one is worse than no number.
 */
internal fun downloadSizeLabel(bytesDownloaded: Long, contentLength: Long?): String? {
    if (bytesDownloaded <= 0L) return null
    val downloaded = formatDownloadBytes(bytesDownloaded)
    // Only when the total is actually ahead of the bytes fetched: a length the transfer has already
    // passed is a stale or wrong number, and "40 MB / 37 MB" reads as a bug even on a healthy
    // download.
    if (contentLength == null || contentLength < bytesDownloaded) return downloaded
    return "$downloaded / ${formatDownloadBytes(contentLength)}"
}

/** As [downloadSizeLabel], for a download whose row is already describing its state. */
internal fun EpisodeDownload.transferSizeLabel(): String? =
    downloadSizeLabel(bytesDownloaded, contentLength)
