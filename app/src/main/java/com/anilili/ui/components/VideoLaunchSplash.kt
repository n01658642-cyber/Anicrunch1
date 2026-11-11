package com.anilili.ui.components

import android.os.SystemClock
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.anilili.R
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the intro may wait for its first frame. A box whose decoder never renders one is let go
 * rather than kept on a black screen. Counted up to the first frame only: a cold start on a slow box
 * can spend four seconds composing the app behind before the clip shows, and a ceiling counted from
 * launch cut the clip off mid-way there, with the profile picker opening over it and its sound still
 * playing.
 */
private const val VideoLaunchSplashFirstFrameCeilingMillis = 8_000L

/**
 * How far past the clip's own length the intro may run once it is playing, for a clip that stalls.
 */
private const val VideoLaunchSplashPlaybackSlackMillis = 2_000L

/**
 * How long the hand-over to the app takes. The splash starts leaving this far before the clip's
 * end, so the cross-fade happens over the last of the motion instead of on a frozen final frame,
 * and the sound fades down with the picture.
 */
const val VideoLaunchSplashExitMillis = 700

/**
 * TRIAL: the launch intro as a video (`res/raw/launch_intro.mp4`) instead of the water-fill mark.
 *
 * Plays once from the APK, then hands over on the clip's end, on an error, on the ceilings above, or
 * on a click. Every one of those takes the sound down with the picture. The player owns a hardware decoder for as long as it lives — on the single-decoder
 * SoCs many TV boxes carry, that is the same decoder the episode player needs — so it is released
 * the moment this leaves composition, never kept around for a replay.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoLaunchSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latestOnFinished by rememberUpdatedState(onFinished)
    val startedAt = remember { SystemClock.elapsedRealtime() }
    // Set by whichever way out comes first; the rest are ignored.
    var finishing by remember { mutableStateOf(false) }
    val finish = {
        if (!finishing) {
            finishing = true
            latestOnFinished()
        }
    }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.launch_intro)))
            // Held on its first frame until that frame is on screen. Started straight away, the audio
            // clock ran on while a cold start was still laying out the surface, and the video joined
            // two seconds in with the whole opening dropped.
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                DiagnosticsLog.event("Launch intro first frame after ${SystemClock.elapsedRealtime() - startedAt}ms")
                player.play()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    DiagnosticsLog.event("Launch intro ended after ${SystemClock.elapsedRealtime() - startedAt}ms")
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                DiagnosticsLog.event("Launch intro failed: ${error.errorCodeName} ${error.message}")
                finish()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        val started = withTimeoutOrNull(VideoLaunchSplashFirstFrameCeilingMillis) {
            while (!player.isPlaying) delay(50)
        }
        if (started == null) {
            DiagnosticsLog.event("Launch intro showed no frame within ${VideoLaunchSplashFirstFrameCeilingMillis}ms")
            finish()
            return@LaunchedEffect
        }
        // Leave once only the exit's worth of the clip remains.
        val playingSince = SystemClock.elapsedRealtime()
        while (true) {
            val duration = player.duration
            if (duration > 0 && duration - player.currentPosition <= VideoLaunchSplashExitMillis) break
            if (player.playbackState == Player.STATE_ENDED) break
            if (duration > 0 &&
                SystemClock.elapsedRealtime() - playingSince > duration + VideoLaunchSplashPlaybackSlackMillis
            ) {
                DiagnosticsLog.event("Launch intro stalled; left ${player.currentPosition}ms into the clip")
                break
            }
            delay(30)
        }
        finish()
    }
    LaunchedEffect(finishing) {
        if (!finishing) return@LaunchedEffect
        // Fade the sound out alongside the picture; the player is released when the exit finishes.
        val steps = 14
        repeat(steps) { step ->
            player.volume = 1f - (step + 1f) / steps
            delay(VideoLaunchSplashExitMillis / steps.toLong())
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                finish()
            },
    ) {
        // A TextureView rather than PlayerView's SurfaceView: a SurfaceView is composited outside
        // the view hierarchy, so the exit's fade and zoom never reached the picture and it cut out.
        AndroidView(
            factory = { viewContext -> TextureView(viewContext).also(player::setVideoTextureView) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
