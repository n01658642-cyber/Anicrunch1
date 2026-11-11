package com.anilili.errors

import androidx.media3.common.PlaybackException

/**
 * Which of this app's playback entries a Media3 failure is.
 *
 * The player's own code says what went wrong in the player's terms. This says what it means for the
 * viewer, which is a different question with a different answer: `ERROR_CODE_IO_BAD_HTTP_STATUS` and
 * `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` are two facts about one situation — this server is not
 * handing over this episode — and both want the same sentence and the same "try another server".
 * The player's code is not thrown away for that: it rides along as the second half of the code, so a
 * report reads `PLAY-02/h403` and says both things at once.
 *
 * Kept as a pure function of the pieces rather than of the exception so the mapping can be tested
 * without a player: `PlaybackException` cannot be constructed outside Media3.
 *
 * Media3 groups its codes in documented thousands — 2000s for input/output, 4000s for decoding — and
 * the ranges are matched rather than the individual constants, because a Media3 upgrade adding
 * `ERROR_CODE_IO_…_2009` should land in the entry the rest of its family already uses instead of
 * quietly falling through to "Playback stopped".
 */
internal fun playbackFailureEntry(
    errorCode: Int,
    /** True when the renderer that gave up was an audio one; see `shouldRetryDecoderAtLowerVideoResolution`. */
    isAudioFailure: Boolean = false,
    /** True when what failed is a file on this device rather than a stream from a server. */
    isLocalMedia: Boolean = false,
    /** True when the network answered a video host's TLS handshake with something else; see [isTlsIntercepted]. */
    networkIntercepted: Boolean = false,
): AnililiError = when {
    // Nothing about a file already on this device is a server's fault, so none of the entries that
    // say "try another server" can be true of it. It is also the one playback failure where the app
    // itself is the most likely culprit — see the transmux repair in EpisodeExport — which is why
    // this one asks for a diagnostics report instead of another attempt.
    isLocalMedia -> AnililiError.PLAYBACK_DOWNLOAD_UNPLAYABLE
    errorCode in DECODING_ERROR_CODES ->
        if (isAudioFailure) AnililiError.PLAYBACK_AUDIO_DECODER_FAILED else AnililiError.PLAYBACK_DECODER_FAILED
    errorCode in IO_ERROR_CODES ->
        if (networkIntercepted) AnililiError.PLAYBACK_NETWORK_BLOCKED else AnililiError.PLAYBACK_SOURCE_UNREACHABLE
    else -> AnililiError.PLAYBACK_FAILED
}

/** Media3's input/output band: bad status, connection failed, timeout, cleartext refused, … */
private val IO_ERROR_CODES = PlaybackException.ERROR_CODE_IO_UNSPECIFIED..2999

/** Media3's decoding band: decoder query, decoder init, malformed samples, unsupported format. */
private val DECODING_ERROR_CODES = PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED..4999
