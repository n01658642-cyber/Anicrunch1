package com.anilili.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the native player's audio is and where it is decoded, for the settings sheet to show.
 *
 * Exists because of a passthrough request: viewers with a soundbar or AV receiver expect Atmos
 * and assume the app is decoding it away. It is not — Media3's stock audio sink already bitstreams
 * AC-3, E-AC-3 (with or without Atmos), TrueHD and DTS whenever the HDMI/eARC sink accepts them —
 * but every source we carry serves AAC stereo, so there is nothing to pass through. Showing the
 * real format answers the question where it is asked instead of in a support thread.
 */
data class AudioOutputFormat(
    val sampleMimeType: String?,
    val codecs: String?,
    val channelCount: Int,
    /** Null until the audio track is configured, which follows the format by a moment. */
    val route: AudioRoute? = null,
) {
    /** "AAC · Stereo · decoded on this device". Parts that are not known yet are left out. */
    val label: String
        get() = listOfNotNull(
            audioCodecLabel(sampleMimeType, codecs),
            audioChannelLabel(channelCount),
            route?.label,
        ).joinToString(" · ")
}

enum class AudioRoute(val label: String) {
    /** PCM out of a decoder — software, MediaCodec, or the DSP via offload. */
    DECODED("decoded on this device"),

    /** The compressed bitstream handed over untouched for the receiver to decode. */
    PASSTHROUGH("passed through to your receiver"),
}

/**
 * The route an [AudioSink.AudioTrackConfig] describes. Offload also takes compressed audio, but
 * the phone's own DSP decodes it, so it counts as decoded here: the viewer's question is whether
 * their receiver gets the bitstream.
 */
@OptIn(UnstableApi::class)
internal fun audioRoute(encoding: Int, offload: Boolean): AudioRoute =
    if (offload || encoding == C.ENCODING_INVALID || Util.isEncodingLinearPcm(encoding)) {
        AudioRoute.DECODED
    } else {
        AudioRoute.PASSTHROUGH
    }

internal fun audioCodecLabel(sampleMimeType: String?, codecs: String?): String? =
    when (sampleMimeType) {
        null -> null
        MimeTypes.AUDIO_AAC -> when (codecs?.lowercase()) {
            "mp4a.40.5" -> "HE-AAC"
            "mp4a.40.29" -> "HE-AAC v2"
            else -> "AAC"
        }
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus with Atmos"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_RAW -> "PCM"
        // Anything unexpected still says what it is rather than vanishing from the line.
        else -> sampleMimeType.substringAfter('/').substringBefore(';').uppercase()
    }

internal fun audioChannelLabel(channelCount: Int): String? = when {
    channelCount <= 0 -> null
    channelCount == 1 -> "Mono"
    channelCount == 2 -> "Stereo"
    channelCount == 6 -> "5.1"
    channelCount == 8 -> "7.1"
    else -> "$channelCount channels"
}

/** Publishes [AudioOutputFormat] for the ExoPlayer [PlaybackService] is playing through. */
@OptIn(UnstableApi::class)
object AudioOutputMonitor {
    private val state = MutableStateFlow<AudioOutputFormat?>(null)
    val current: StateFlow<AudioOutputFormat?> = state.asStateFlow()

    /**
     * The player whose answer [state] holds; only it may change or clear it. Main thread only:
     * analytics callbacks arrive on the player's application looper, and the service's is main.
     *
     * Ownership rather than a reset on [attach]: a service instance that is created and stands
     * down at once ("foreground start with nothing queued") builds a player of its own, and
     * resetting there wiped the answer of the player actually playing. Seen on the TV bench, where
     * the settings sheet then showed no format at all.
     */
    private var owner: ExoPlayer? = null

    fun attach(player: ExoPlayer) {
        player.addAnalyticsListener(Listener(player))
    }

    /** Called as [player] is released, so its last answer does not outlive it. */
    fun detach(player: ExoPlayer) {
        if (owner === player) clear()
    }

    private fun clear() {
        owner = null
        state.value = null
    }

    private class Listener(private val player: ExoPlayer) : AnalyticsListener {
        // The renderer's input format, not the manifest's: an HLS variant's CODECS attribute
        // carries no channel count, and the parsed samples do.
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // Kept across this player's own format change: the track is only rebuilt when the
            // change needs it, and when it is, onAudioTrackInitialized corrects this a moment later.
            val route = if (owner === player) state.value?.route else null
            owner = player
            state.value = AudioOutputFormat(
                sampleMimeType = format.sampleMimeType,
                codecs = format.codecs,
                channelCount = format.channelCount,
                route = route,
            )
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            if (owner !== player) return
            val route = audioRoute(audioTrackConfig.encoding, audioTrackConfig.offload)
            state.value = state.value?.copy(route = route)
        }

        // Cast, a stop, or a stream with no audio: no line rather than the last episode's.
        override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, counters: DecoderCounters) {
            if (owner === player) clear()
        }
    }
}
