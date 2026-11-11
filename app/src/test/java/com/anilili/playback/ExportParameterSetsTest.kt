package com.anilili.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.source.TrackGroupArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export repair reads its parameter sets out of the source's codec-specific data, so a source
 * that names a codec without carrying one is not an unrepairable stream — it is a preparation that
 * never read a segment. These pin the difference, which the `avc1` in the finished file cannot show.
 */
class ExportParameterSetsTest {
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0xAC.toByte())
    private val pps = byteArrayOf(0x68, 0xEF.toByte(), 0x84.toByte(), 0xF2.toByte(), 0xC0.toByte())

    private fun groupsOf(vararg formats: Format) =
        TrackGroupArray(*formats.map { TrackGroup(it) }.toTypedArray())

    private fun videoFormat(csd: List<ByteArray>, codecs: String? = "avc1.640028"): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs(codecs)
            .setInitializationData(csd)
            .build()

    private fun audioFormat(): Format =
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("ja").build()

    private fun parameterSetsOf(format: Format) = avcParameterSets(groupsOf(format))

    @Test
    fun `a sample format hands over both parameter sets`() {
        val (readSps, readPps) = checkNotNull(parameterSetsOf(videoFormat(listOf(sps, pps))))
        assertTrue(sps.contentEquals(readSps))
        assertTrue(pps.contentEquals(readPps))
    }

    @Test
    fun `annex-b start codes are stripped off both lengths`() {
        val prefixed = listOf(byteArrayOf(0, 0, 0, 1) + sps, byteArrayOf(0, 0, 1) + pps)
        val (readSps, readPps) = checkNotNull(parameterSetsOf(videoFormat(prefixed)))
        assertTrue(sps.contentEquals(readSps))
        assertTrue(pps.contentEquals(readPps))
    }

    /**
     * The regression this exists for. Chunkless HLS preparation describes the track from the
     * multivariant playlist's `CODECS` attribute and nothing else, so the video format has an empty
     * csd — which is exactly what the repair used to read, and silently give up on.
     */
    @Test
    fun `a manifest-only format carries nothing to repair with`() {
        assertNull(parameterSetsOf(videoFormat(emptyList())))
    }

    @Test
    fun `an sps without its pps is not enough`() {
        assertNull(parameterSetsOf(videoFormat(listOf(sps))))
    }

    @Test
    fun `the video track is found past the audio ones`() {
        val groups = groupsOf(audioFormat(), videoFormat(listOf(sps, pps)))
        assertTrue(sps.contentEquals(checkNotNull(avcParameterSets(groups)).first))
    }

    @Test
    fun `a source with no video track says so rather than blaming the parameter sets`() {
        assertEquals("video=none groups=1", videoTrackSummary(groupsOf(audioFormat())))
    }

    @Test
    fun `the summary separates a named codec from a carried one`() {
        assertEquals(
            "video=video/avc codecs=avc1.640028 csd=0 nalTypes=none",
            videoTrackSummary(groupsOf(videoFormat(emptyList()))),
        )
        assertEquals(
            "video=video/avc codecs=avc1.640028 csd=2 nalTypes=7/8",
            videoTrackSummary(groupsOf(videoFormat(listOf(sps, pps)))),
        )
    }
}
