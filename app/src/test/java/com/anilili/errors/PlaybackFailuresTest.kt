package com.anilili.errors

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a Media3 error code means to a viewer.
 *
 * The mapping is asserted rather than eyeballed because it decides the one thing the screen offers
 * them: `PLAY-02` says "try another server" and is right about a refused stream, while `PLAY-03`
 * says the same about a decoder the device does not have — and `PLAY-05` deliberately says neither,
 * because no server is involved in a file already on the device.
 */
class PlaybackFailuresTest {

    @Test
    fun `an input-output failure is the server not handing the episode over`() {
        assertEquals(
            AnililiError.PLAYBACK_SOURCE_UNREACHABLE,
            playbackFailureEntry(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(
            AnililiError.PLAYBACK_SOURCE_UNREACHABLE,
            playbackFailureEntry(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )
    }

    @Test
    fun `a decoding failure names the track that could not be decoded`() {
        assertEquals(
            AnililiError.PLAYBACK_DECODER_FAILED,
            playbackFailureEntry(PlaybackException.ERROR_CODE_DECODING_FAILED),
        )
        assertEquals(
            AnililiError.PLAYBACK_AUDIO_DECODER_FAILED,
            playbackFailureEntry(PlaybackException.ERROR_CODE_DECODING_FAILED, isAudioFailure = true),
        )
        // Lowering the resolution cannot help a format the device has no decoder for either, so it
        // belongs with the decoder entries rather than in the generic one.
        assertEquals(
            AnililiError.PLAYBACK_DECODER_FAILED,
            playbackFailureEntry(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
        )
    }

    /**
     * A download plays from this device. Every other playback entry either blames a server or offers
     * another one, and both would be a lie here — which is the whole reason `PLAY-05` exists.
     */
    @Test
    fun `a file on this device is never blamed on a server`() {
        assertEquals(
            AnililiError.PLAYBACK_DOWNLOAD_UNPLAYABLE,
            playbackFailureEntry(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, isLocalMedia = true),
        )
        assertEquals(
            AnililiError.PLAYBACK_DOWNLOAD_UNPLAYABLE,
            playbackFailureEntry(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                isAudioFailure = true,
                isLocalMedia = true,
            ),
        )
    }

    /**
     * The bands are matched rather than the constants, so a Media3 upgrade that adds a code lands in
     * the entry the rest of its family already uses instead of falling through to "Playback stopped".
     */
    @Test
    fun `an unfamiliar code lands with its own family`() {
        assertEquals(AnililiError.PLAYBACK_SOURCE_UNREACHABLE, playbackFailureEntry(2_999))
        assertEquals(AnililiError.PLAYBACK_DECODER_FAILED, playbackFailureEntry(4_999))
        assertEquals(AnililiError.PLAYBACK_FAILED, playbackFailureEntry(PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals(AnililiError.PLAYBACK_FAILED, playbackFailureEntry(PlaybackException.ERROR_CODE_TIMEOUT))
    }

    /**
     * A filtered video host fails the same way on every attempt, so it must not read as a dropped
     * connection — that sentence is what kept a tester retrying. Only an input-output failure can be
     * the network's doing; a decoder fault on an intercepted connection is still the decoder's.
     */
    @Test
    fun `a network answering the handshake itself is not a lost connection`() {
        assertEquals(
            AnililiError.PLAYBACK_NETWORK_BLOCKED,
            playbackFailureEntry(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, networkIntercepted = true),
        )
        assertEquals(
            AnililiError.PLAYBACK_DECODER_FAILED,
            playbackFailureEntry(PlaybackException.ERROR_CODE_DECODING_FAILED, networkIntercepted = true),
        )
        assertEquals(
            AnililiError.PLAYBACK_DOWNLOAD_UNPLAYABLE,
            playbackFailureEntry(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                isLocalMedia = true,
                networkIntercepted = true,
            ),
        )
    }
}
