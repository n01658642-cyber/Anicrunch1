package com.anilili.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.transformer.DefaultMuxer
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/**
 * A muxer that drops audio samples which do not move forward in time.
 *
 * Some encodes step their audio clock *backwards* where two pieces were spliced together. MegaPlay's
 * dub of Reincarnated as a Sword (AniList 139587) does it in every episode: 5.53 s in, the AAC
 * timestamps jump back 73 ms and replay three-and-a-bit frames of silence over ones already sent.
 * A player shrugs that off. The framework `MediaMuxer` does not — its writer refuses an audio sample
 * older than the one before it ("do not support out of order frames"), stops the track, and the
 * very next write throws `writeSampleData returned an error`. Every episode of the series failed
 * to export as `EXP-01/x7001` at the same two timestamps, a frame apart, on every retry.
 *
 * Dropping the overlap rather than retiming it keeps every surviving sample at its true time, so
 * lip sync is untouched; what is lost is the few frames that duplicated time already written.
 * Video is passed through as-is: B-frames make its presentation times legitimately non-monotonic.
 *
 * Only a short step back is an overlap. Past [MAX_DROPPED_STEP_US] it is a clock that restarted —
 * a second piece spliced on with its own zero, say — and dropping "until it passes the last written
 * sample" would then drop every sample to the end of the file: the export would report success and
 * hand over an episode that goes silent partway. That is refused instead, as a [MuxerException]
 * that names the jump, so it still fails as `EXP-01` but says why.
 */
@OptIn(UnstableApi::class)
internal class MonotonicAudioMuxer(
    private val delegate: Muxer,
    private val onClosed: (droppedSamples: Int) -> Unit = {},
) : Muxer {
    private val lastAudioTimeUs = HashMap<Int, Long>()
    private val audioTracks = HashSet<Int>()
    private var dropped = 0

    override fun addTrack(format: Format): Int {
        val trackId = delegate.addTrack(format)
        if (MimeTypes.isAudio(format.sampleMimeType)) audioTracks += trackId
        return trackId
    }

    override fun writeSampleData(trackId: Int, data: ByteBuffer, bufferInfo: BufferInfo) {
        if (trackId in audioTracks) {
            val last = lastAudioTimeUs[trackId]
            if (last != null && bufferInfo.presentationTimeUs <= last) {
                val stepUs = last - bufferInfo.presentationTimeUs
                if (stepUs > MAX_DROPPED_STEP_US) {
                    val detail = "Audio clock jumped back ${stepUs / 1000} ms at " +
                        "presentationTimeUs=${bufferInfo.presentationTimeUs}; not dropping the rest of the track"
                    throw MuxerException(detail, IllegalStateException(detail))
                }
                dropped++
                return
            }
            lastAudioTimeUs[trackId] = bufferInfo.presentationTimeUs
        }
        delegate.writeSampleData(trackId, data, bufferInfo)
    }

    override fun addMetadataEntry(entry: Metadata.Entry) {
        delegate.addMetadataEntry(entry)
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            onClosed(dropped)
        }
    }

    companion object {
        /**
         * The furthest back an audio sample may step and still be treated as overlap to drop. The
         * case this exists for steps back 73 ms; a second is fourteen times that and still far short
         * of anything that would leave an audible hole.
         */
        const val MAX_DROPPED_STEP_US = 1_000_000L
    }

    class Factory(
        private val delegate: Muxer.Factory = DefaultMuxer.Factory(),
        private val onClosed: (droppedSamples: Int) -> Unit = {},
    ) : Muxer.Factory {
        override fun create(path: String): Muxer = MonotonicAudioMuxer(delegate.create(path), onClosed)

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
            delegate.getSupportedSampleMimeTypes(trackType)

        override fun supportsWritingNegativeTimestampsInEditList(): Boolean =
            delegate.supportsWritingNegativeTimestampsInEditList()
    }
}
