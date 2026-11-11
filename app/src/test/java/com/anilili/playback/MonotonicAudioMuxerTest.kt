package com.anilili.playback

import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The timestamps here are the ones MegaPlay's Reincarnated as a Sword dub actually carries: AAC
 * frames 21333 µs apart, with the clock stepping back 73 ms at 5.53 s. The framework muxer died on
 * exactly the samples this wrapper must now drop.
 */
class MonotonicAudioMuxerTest {
    private class Recording : Muxer {
        val tracks = mutableListOf<Format>()
        val written = mutableListOf<Pair<Int, Long>>()
        var closed = false

        override fun addTrack(format: Format): Int {
            tracks += format
            return tracks.lastIndex
        }

        override fun writeSampleData(trackId: Int, data: ByteBuffer, bufferInfo: BufferInfo) {
            written += trackId to bufferInfo.presentationTimeUs
        }

        override fun addMetadataEntry(entry: Metadata.Entry) = Unit

        override fun close() {
            closed = true
        }
    }

    private val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
    private val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()

    private fun Muxer.write(trackId: Int, timeUs: Long) =
        writeSampleData(trackId, ByteBuffer.allocate(6), BufferInfo(timeUs, 6, 0))

    @Test
    fun `audio that steps backwards is dropped until it passes the last written sample`() {
        val recording = Recording()
        var dropped = -1
        val muxer = MonotonicAudioMuxer(recording) { dropped = it }
        val track = muxer.addTrack(audio)
        val source = listOf(5_382_166L, 5_403_500L, 5_424_833L, 5_351_833L, 5_373_166L, 5_394_500L, 5_415_833L, 5_437_166L)
        source.forEach { muxer.write(track, it) }
        muxer.close()

        assertEquals(
            listOf(5_382_166L, 5_403_500L, 5_424_833L, 5_437_166L),
            recording.written.map { it.second },
        )
        assertEquals(4, dropped)
        assertEquals(true, recording.closed)
    }

    @Test
    fun `a repeated audio timestamp is dropped`() {
        val recording = Recording()
        val muxer = MonotonicAudioMuxer(recording)
        val track = muxer.addTrack(audio)
        listOf(0L, 21_333L, 21_333L, 42_666L).forEach { muxer.write(track, it) }

        assertEquals(listOf(0L, 21_333L, 42_666L), recording.written.map { it.second })
    }

    @Test
    fun `video keeps its out-of-order presentation times`() {
        val recording = Recording()
        val muxer = MonotonicAudioMuxer(recording)
        val videoTrack = muxer.addTrack(video)
        val audioTrack = muxer.addTrack(audio)
        // I P B B: B-frames present before the P-frame they were decoded after.
        val frames = listOf(0L, 125_125L, 41_708L, 83_416L)
        frames.forEach { muxer.write(videoTrack, it) }
        muxer.write(audioTrack, 10_000L)

        assertEquals(frames, recording.written.filter { it.first == videoTrack }.map { it.second })
        assertEquals(listOf(10_000L), recording.written.filter { it.first == audioTrack }.map { it.second })
    }

    @Test
    fun `each audio track keeps its own clock`() {
        val recording = Recording()
        val muxer = MonotonicAudioMuxer(recording)
        val first = muxer.addTrack(audio)
        val second = muxer.addTrack(audio)
        muxer.write(first, 100_000L)
        muxer.write(second, 50_000L)

        assertEquals(listOf(first to 100_000L, second to 50_000L), recording.written)
    }

    /**
     * A clock that restarts is not an overlap. Dropping until it caught up would silence the rest of
     * the episode while the export reported success, so it fails loudly instead.
     */
    @Test
    fun `a long jump back fails rather than silencing the rest of the track`() {
        val recording = Recording()
        val muxer = MonotonicAudioMuxer(recording)
        val track = muxer.addTrack(audio)
        muxer.write(track, 600_000_000)
        muxer.write(track, 600_021_333)

        assertThrows(MuxerException::class.java) { muxer.write(track, 0) }
        assertEquals(listOf(track to 600_000_000L, track to 600_021_333L), recording.written)
    }

    @Test
    fun `a step back of exactly the limit is still dropped`() {
        val recording = Recording()
        var dropped = -1
        val muxer = MonotonicAudioMuxer(recording) { dropped = it }
        val track = muxer.addTrack(audio)
        muxer.write(track, 2_000_000)
        muxer.write(track, 2_000_000 - MonotonicAudioMuxer.MAX_DROPPED_STEP_US)
        muxer.close()

        assertEquals(1, dropped)
        assertEquals(listOf(track to 2_000_000L), recording.written)
    }
}
