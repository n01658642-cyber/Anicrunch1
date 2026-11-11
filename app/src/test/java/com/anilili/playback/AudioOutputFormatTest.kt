package com.anilili.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputFormatTest {

    /** What every source we carry actually serves: `CODECS="avc1.…,mp4a.40.2"`, two channels. */
    @Test
    fun `the AAC stereo every source serves reads as decoded here`() {
        val format = AudioOutputFormat(
            sampleMimeType = MimeTypes.AUDIO_AAC,
            codecs = "mp4a.40.2",
            channelCount = 2,
            route = audioRoute(C.ENCODING_PCM_16BIT, offload = false),
        )
        assertEquals("AAC · Stereo · decoded on this device", format.label)
    }

    @Test
    fun `an Atmos bitstream reads as passed through`() {
        val format = AudioOutputFormat(
            sampleMimeType = MimeTypes.AUDIO_E_AC3_JOC,
            codecs = "ec+3",
            channelCount = 6,
            route = audioRoute(C.ENCODING_E_AC3_JOC, offload = false),
        )
        assertEquals("Dolby Digital Plus with Atmos · 5.1 · passed through to your receiver", format.label)
    }

    /** Offload hands compressed audio to the phone's own DSP; the receiver still gets PCM. */
    @Test
    fun `offload is not passthrough`() {
        assertEquals(AudioRoute.DECODED, audioRoute(C.ENCODING_AAC_LC, offload = true))
        assertEquals(AudioRoute.DECODED, audioRoute(C.ENCODING_PCM_FLOAT, offload = false))
        assertEquals(AudioRoute.PASSTHROUGH, audioRoute(C.ENCODING_AC3, offload = false))
    }

    @Test
    fun `parts not known yet are left out rather than guessed`() {
        assertEquals("AAC", AudioOutputFormat(MimeTypes.AUDIO_AAC, null, Format.NO_VALUE).label)
        assertEquals("HE-AAC · Mono", AudioOutputFormat(MimeTypes.AUDIO_AAC, "mp4a.40.5", 1).label)
    }

    @Test
    fun `an unexpected codec still names itself`() {
        assertEquals("ALAC", audioCodecLabel("audio/alac", null))
        assertEquals("3 channels", audioChannelLabel(3))
    }
}
