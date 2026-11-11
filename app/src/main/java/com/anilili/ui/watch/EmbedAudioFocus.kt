package com.anilili.ui.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.anilili.diagnostics.DiagnosticsLog

/** What the system has asked of playback while something else wants the speaker. */
internal enum class AudioInterruption {
    /** Something else took the audio for a while — a call, a voice note. Resume afterwards. */
    PauseTransient,

    /** The audio is gone for good: another player started, or the headphones were pulled. */
    PauseFinal,

    /** The interruption is over and playback may continue. */
    Resume,
}

/**
 * Audio focus for the embed player, which the native one gets for free.
 *
 * `ExoPlayer.setAudioAttributes(.., handleAudioFocus = true)` in PlaybackService already pauses the
 * native player when a call arrives. The embed player is a `<video>` inside a WebView and receives
 * none of that, so an incoming call used to ring *over* the episode: the screen only pauses on
 * `ON_PAUSE`, which does not arrive while the phone is merely ringing, and never arrives at all for
 * a voice note or another app taking the speaker.
 *
 * Focus is held for as long as the player is [active] rather than only while it is playing. If it
 * were abandoned at the moment of the interruption-pause, the matching `AUDIOFOCUS_GAIN` would go to
 * somebody else and playback could never resume by itself — which is the whole point of the
 * transient case.
 *
 * `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` is deliberately ignored: answering it properly means lowering
 * the `<video>` volume and putting back exactly what the viewer had, and getting that restore wrong
 * leaves an episode playing silently. A notification chirp over dialogue is the smaller harm.
 */
@Composable
internal fun EmbedAudioFocus(active: Boolean, onInterruption: (AudioInterruption) -> Unit) {
    val context = LocalContext.current
    val currentOnInterruption by rememberUpdatedState(onInterruption)
    DisposableEffect(active, context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (!active || audioManager == null) return@DisposableEffect onDispose {}

        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            val interruption = when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> AudioInterruption.PauseFinal
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioInterruption.PauseTransient
                AudioManager.AUDIOFOCUS_GAIN -> AudioInterruption.Resume
                else -> return@OnAudioFocusChangeListener
            }
            DiagnosticsLog.event("EmbedWebView audio focus change=$change action=$interruption")
            currentOnInterruption(interruption)
        }

        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                // The system may not pause us for a notification, and we do not duck; see above.
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
        } else {
            null
        }
        val granted = if (request != null) {
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        DiagnosticsLog.event(
            "EmbedWebView audio focus requested granted=" +
                "${granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED}",
        )

        // Pulling the headphones out never raises a focus change, because nothing else asked for
        // the audio; without this the episode carries on out loud from the phone's own speaker.
        val becomingNoisy = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
                DiagnosticsLog.event("EmbedWebView audio becoming noisy; pausing")
                currentOnInterruption(AudioInterruption.PauseFinal)
            }
        }
        runCatching {
            context.registerReceiver(
                becomingNoisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            )
        }

        onDispose {
            runCatching { context.unregisterReceiver(becomingNoisy) }
            if (request != null) {
                audioManager.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(listener)
            }
        }
    }
}
