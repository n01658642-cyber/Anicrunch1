package com.anilili.ui.watch

import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.R
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.EpisodeFillerBadge
import kotlinx.coroutines.delay

internal enum class TvPlayerControl {
    PREVIOUS,
    REWIND,
    PLAY_PAUSE,
    FORWARD,
    NEXT,
    EPISODES,
    MY_LIST,
    MUTE,
    CAPTIONS,
    SETTINGS,
    FULLSCREEN,
}

/**
 * Stable left-to-right remote order; the progress bar is deliberately display-only.
 * Loudness is the remote's own volume rocker, so the row carries a single mute toggle rather
 * than a level pair — three near-identical speaker glyphs read as one broken control.
 */
internal fun tvPlayerControlOrder(
    hasEpisodes: Boolean = false,
    hasList: Boolean = false,
    hasCaptions: Boolean = false,
    hasSettings: Boolean = false,
    hasFullscreen: Boolean = false,
): List<TvPlayerControl> = buildList {
    add(TvPlayerControl.PREVIOUS)
    add(TvPlayerControl.REWIND)
    add(TvPlayerControl.PLAY_PAUSE)
    add(TvPlayerControl.FORWARD)
    add(TvPlayerControl.NEXT)
    if (hasEpisodes) add(TvPlayerControl.EPISODES)
    if (hasList) add(TvPlayerControl.MY_LIST)
    add(TvPlayerControl.MUTE)
    if (hasCaptions) add(TvPlayerControl.CAPTIONS)
    if (hasSettings) add(TvPlayerControl.SETTINGS)
    if (hasFullscreen) add(TvPlayerControl.FULLSCREEN)
}

internal fun opensTvPlayerControls(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
    keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
    keyCode == KeyEvent.KEYCODE_ENTER ||
    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

/** The layer of TV player chrome a Back press should retire, topmost first. */
internal enum class TvPlayerBackTarget {
    OPEN_SUBTITLES,
    CAPTION_APPEARANCE,
    EPISODE_DRAWER,
    CAPTIONS,
    SETTINGS,
    CONTROLS,

    /** Nothing of the player's is showing; Back belongs to the screen. */
    NONE,
}

/**
 * Which layer of player chrome Back retires, so one press peels one layer: the panels drawn over
 * the control row go first, then the row itself, and only with nothing showing does Back reach the
 * screen and leave the episode. Waiting out the auto-hide timer used to be the only way to be rid
 * of the row.
 *
 * Shared by both players so the native and embed paths stay in lockstep. Off TV — and whenever the
 * player is windowed rather than owning the remote — the player claims nothing.
 */
internal fun tvPlayerBackTarget(
    isTv: Boolean,
    playerOwnsRemote: Boolean,
    captionAppearanceVisible: Boolean = false,
    episodeDrawerVisible: Boolean = false,
    openSubtitlesVisible: Boolean = false,
    captionsVisible: Boolean = false,
    settingsVisible: Boolean = false,
    controlsVisible: Boolean = false,
): TvPlayerBackTarget = when {
    !isTv || !playerOwnsRemote -> TvPlayerBackTarget.NONE
    openSubtitlesVisible -> TvPlayerBackTarget.OPEN_SUBTITLES
    captionAppearanceVisible -> TvPlayerBackTarget.CAPTION_APPEARANCE
    episodeDrawerVisible -> TvPlayerBackTarget.EPISODE_DRAWER
    captionsVisible -> TvPlayerBackTarget.CAPTIONS
    settingsVisible -> TvPlayerBackTarget.SETTINGS
    controlsVisible -> TvPlayerBackTarget.CONTROLS
    else -> TvPlayerBackTarget.NONE
}

/**
 * Whether an Up press retires the TV control row, once the player has tried moving the ring up.
 *
 * Up still walks the row first: from the buttons to the seek bar, and from the seek bar to a skip
 * action floating over it. Only when nothing is above the ring does Up close the row, which used
 * to sit out its auto-hide timer instead. Nothing drawn inside the picture can be reached from the
 * row (see [tvEpisodeChangeFocus]), so "nowhere to go" means the top edge of the controls.
 *
 * A held key's repeats never close it. The first press may have opened the row, and the repeats
 * that follow should walk up to the seek bar and stop there, not close the row again at once.
 */
internal fun tvUpClosesControls(movedFocus: Boolean, isRepeat: Boolean): Boolean =
    !movedFocus && !isRepeat

/** Where the remote belongs once the stream underneath the TV player has been swapped. */
internal enum class TvEpisodeChangeFocus {
    /** Put the ring back on the control row: it is on screen with nothing inside it focused. */
    CONTROLS,

    /** Hand the remote to the picture, which is what a key press then reopens the row from. */
    SURFACE,

    /** The row is up and still holds the remote. Nothing to do — but keep watching; see below. */
    HOLD,

    /** Not this player's remote to move: something else is holding it deliberately. */
    UNCHANGED,
}

/**
 * Who the remote answers to after an episode change, which is where the control row used to lose it.
 *
 * The row is not retired by an episode change — a viewer who pressed Next on it is still looking at
 * it — but the surface underneath reclaimed focus for every new stream, and that surface is a
 * full-screen focus target. Compose's 2-D focus search only accepts a candidate extending past the
 * focused rect, so nothing drawn *inside* the picture is reachable from it: the row stayed lit and
 * answered no direction at all. The only ways out were OK, which Compose maps to
 * [androidx.compose.ui.focus.FocusDirection.Enter] and so descends into the row, and Back, which
 * closed the row so that reopening it re-ran the explicit focus request.
 *
 * So the row keeps the remote across the swap ([HOLD][TvEpisodeChangeFocus.HOLD]), which is what
 * leaves the ring on Next for a viewer skipping several episodes. It only has to be re-aimed when
 * the swap took focus with it — the episode drawer closing over its own selection, or Next going
 * disabled on the last episode — and it stands down entirely for a panel or a screen reader, both
 * of which are holding focus deliberately.
 */
internal fun tvEpisodeChangeFocus(
    isTv: Boolean,
    playerOwnsRemote: Boolean,
    screenReaderActive: Boolean = false,
    panelVisible: Boolean = false,
    controlsVisible: Boolean = false,
    controlsFocused: Boolean = false,
): TvEpisodeChangeFocus = when {
    !isTv || !playerOwnsRemote -> TvEpisodeChangeFocus.UNCHANGED
    screenReaderActive -> TvEpisodeChangeFocus.UNCHANGED
    panelVisible -> TvEpisodeChangeFocus.UNCHANGED
    !controlsVisible -> TvEpisodeChangeFocus.SURFACE
    controlsFocused -> TvEpisodeChangeFocus.HOLD
    else -> TvEpisodeChangeFocus.CONTROLS
}

/**
 * Who the remote answers to once one of the player's transient overlays leaves the screen.
 *
 * The bottom-right action ("Skip Intro", "Next Episode", "+85s") and the up-next card are both
 * focusable — they have to be, or a remote could never answer them — and both are gone the instant
 * they are pressed. Compose does not re-aim the ring when the node holding it is removed, so the
 * press that dismissed the overlay also left the window with nothing focused, and with no focused
 * node every key but Back lands nowhere. The control row underneath is no help: it is still lit, but
 * there is no D-pad route into it from the picture (see [tvEpisodeChangeFocus]), so the player reads
 * as frozen until the episode is closed and reopened.
 *
 * It looks like a fault of whichever server was playing, because only the servers that carry their
 * own intro/outro timings draw those buttons at all — anikoto, which supplies both, is where it was
 * reported.
 *
 * The question is the same one an episode change asks, and gets the same answer: hand the row back
 * the remote when it is up with nothing inside it focused, hand it to the picture when the row is
 * down, and leave a row that still holds it alone — a viewer whose ring never left the row pressed
 * nothing that needs undoing.
 */
internal fun tvOverlayDismissFocus(
    isTv: Boolean,
    playerOwnsRemote: Boolean,
    screenReaderActive: Boolean = false,
    panelVisible: Boolean = false,
    controlsVisible: Boolean = false,
    controlsFocused: Boolean = false,
): TvEpisodeChangeFocus = tvEpisodeChangeFocus(
    isTv = isTv,
    playerOwnsRemote = playerOwnsRemote,
    screenReaderActive = screenReaderActive,
    panelVisible = panelVisible,
    controlsVisible = controlsVisible,
    controlsFocused = controlsFocused,
)

/**
 * Whether a press that reached the player's own key handling is spent putting the ring back on the
 * control row, instead of on whatever that press would otherwise have done.
 *
 * The row can be lit while something else holds the remote. On the native player that is the picture
 * underneath, which 2-D search cannot leave for anything drawn inside it (see [tvEpisodeChangeFocus]).
 * On the embed it is the WebView: its key hook reads Up, Down and OK as "open the row", so a row that
 * was already open answered them by doing nothing. Either way every one of those presses counted as
 * interaction, so the row never timed out either — lit, and deaf to everything but the remote's own
 * play/pause and seek keys, for as long as the viewer kept trying. Reported from a Fire TV, after Next
 * and Previous.
 *
 * The up-next card or a skip holding the ring is not a stray: they sit beside the row on purpose. A
 * panel keeps its own presses.
 */
internal fun tvPressReclaimsControls(
    controlsVisible: Boolean,
    controlsFocused: Boolean,
    overlayFocused: Boolean = false,
    panelVisible: Boolean = false,
): Boolean = controlsVisible && !controlsFocused && !overlayFocused && !panelVisible

/**
 * Where the remote goes when the player finds that nothing inside it is focused while it should be
 * holding the remote.
 *
 * Compose does not re-aim the ring when the node holding it leaves, and with nothing focused every key
 * but Back lands nowhere — only the remote's media keys still work, because those go to the media
 * session instead. [tvEpisodeChangeFocus] and [tvOverlayDismissFocus] each re-aim it after one known
 * way of losing it; this is the net under all of them, answering the same way: the row when it is up,
 * the picture when it is not, and a panel or a screen reader left alone.
 */
internal fun tvFocusLostTarget(
    isTv: Boolean,
    playerOwnsRemote: Boolean,
    playerHasFocus: Boolean,
    screenReaderActive: Boolean = false,
    panelVisible: Boolean = false,
    controlsVisible: Boolean = false,
): TvEpisodeChangeFocus = if (playerHasFocus) {
    TvEpisodeChangeFocus.UNCHANGED
} else {
    tvEpisodeChangeFocus(
        isTv = isTv,
        playerOwnsRemote = playerOwnsRemote,
        screenReaderActive = screenReaderActive,
        panelVisible = panelVisible,
        controlsVisible = controlsVisible,
        controlsFocused = false,
    )
}

/**
 * Whether the bottom-right action takes the remote as it appears on a TV, so OK alone answers it.
 *
 * Every timed skip does — "Skip Recap", "Skip Intro", "Skip Outro", and the auto-skip's "Skipping …"
 * countdown. Each lasts only as long as the part of the episode it skips, and reaching one from the
 * control row took Up three times before OK, by which point much of an opening had played. The
 * fixed "+Ns" does not: it is offered whenever the row is up, so taking the remote for it would turn
 * every OK meant for play/pause into an 85-second jump.
 */
internal fun tvSkipActionTakesRemote(label: String?): Boolean = label?.startsWith("Skip") == true

/**
 * Whether a control row opened over bare picture starts on the fixed "+Ns" skip instead of
 * play/pause.
 *
 * Only for Down. The button floats above the seek bar, so from play/pause it took Up twice before
 * OK — reported by a viewer who uses it to jump every opening. OK and Up keep opening on play/pause,
 * which is what [tvSkipActionTakesRemote] protects: OK is the play/pause key, and the press that
 * opens the row must not turn the next one into an 85-second jump. Down was the one key with no
 * use of its own there. A row opened from the skip itself is a viewer asking for the row.
 */
internal fun tvRowOpensOnFixedSkip(
    openedByDown: Boolean,
    fixedSkipShown: Boolean,
    skipActionFocused: Boolean,
): Boolean = openedByDown && fixedSkipShown && !skipActionFocused

/**
 * Whether a pointer that just revealed the control row should also leave Compose's Touch input mode.
 *
 * A TV is not only driven by a D-pad — mice and air-mice are common on boxes — and a pointer tap can
 * leave Compose in [androidx.compose.ui.input.InputMode.Touch]. `Modifier.clickable` reports
 * `canFocus = false` for the whole of that mode, so every button in the row refuses focus, and
 * `FocusRequester.requestFocus()` does **not** throw for that refusal: the retry loops guarding the
 * reveal read a silent no as a yes and stop after one attempt. The row then sits lit on screen while
 * the remote is still on the full-screen surface behind it, where Compose's 2-D focus search cannot
 * reach anything drawn inside the picture (see [tvEpisodeChangeFocus]) — so no direction does
 * anything and the controls read as dead rather than as unfocused.
 *
 * Declined under a screen reader. TalkBack consumes the D-pad and moves its own accessibility focus,
 * so there is no remote to rescue, and claiming keyboard mode would only put a Compose focus ring in
 * the way of explore-by-touch.
 */
internal fun claimsKeyboardModeOnPointerReveal(screenReaderActive: Boolean): Boolean =
    !screenReaderActive

internal fun opensTvPlayerControls(key: Key): Boolean = key == Key.DirectionLeft ||
    key == Key.DirectionRight ||
    key == Key.DirectionUp ||
    key == Key.DirectionDown ||
    key == Key.DirectionCenter ||
    key == Key.Enter ||
    key == Key.NumPadEnter

@Composable
internal fun TvPlayerControls(
    seriesTitle: String = "",
    episodeTitle: String = "",
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long = 0L,
    isPlaying: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    playPauseFocusRequester: FocusRequester,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onSeek: ((Long) -> Unit)? = null,
    /** The episode's intro/outro/recap windows, drawn on the timeline; empty where none are known. */
    markers: List<PlayerSeekMarker> = emptyList(),
    onEpisodes: (() -> Unit)? = null,
    onCaptions: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    /** Opens the server/source picker. Null where the screen has no other source to offer. */
    onSources: (() -> Unit)? = null,
    /** Opens the episode's download options. Null where the episode has nothing to download. */
    download: PlayerDownloadAction? = null,
    /**
     * A filler episode's skip. The title card wears the FILLER badge and the row gains a button
     * beside Next: the card is out of the remote's reach on purpose (see the Back arrow above it).
     */
    fillerSkip: PlayerFillerSkip? = null,
    /**
     * Signals that the viewer is still working the row, so it isn't retired underneath them.
     *
     * A remote press already reaches the caller's own key handling, which is what has been keeping
     * the auto-hide timer at bay; a pointer dragging the seek bar reaches nothing else, and a slow
     * scrub would otherwise time out mid-drag.
     */
    onInteract: () -> Unit = {},
    /**
     * How far the top of the seek bar sits above the bottom edge, so a caller can float an action
     * just clear of the timeline rather than clear of the whole bar.
     */
    onSeekAnchorChanged: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    // While the bar has focus, Left/Right move a preview instead of the video. The commit is
    // deferred so a run of presses is one seek rather than a dozen, which is also what stops the
    // player rebuffering at every step.
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var scrubPresses by remember { mutableIntStateOf(0) }
    var focusedLabel by remember { mutableStateOf(if (isPlaying) "Pause" else "Play") }
    val visibleFocusedLabel = if (focusedLabel == "Play" || focusedLabel == "Pause") {
        if (isPlaying) "Pause" else "Play"
    } else {
        focusedLabel
    }
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)

    Box(modifier.fillMaxSize()) {
        // Back arrow, then the title card beside it. The arrow is for a pointer: TV boxes are often
        // driven by a mouse or an air-mouse, which may have no Back key at all, and the player
        // otherwise had no way out on screen. The D-pad skips it — a remote has Back, and a stop up
        // here would change where Up from the seek bar lands.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 32.dp, top = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            TvPlayerBackArrow()
            if (seriesTitle.isNotBlank() || episodeTitle.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 620.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (seriesTitle.isNotBlank()) {
                        PlayerSeriesName(
                            title = seriesTitle,
                            maxHeight = 46.dp,
                            maxWidth = 300.dp,
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            Text(
                                text = seriesTitle,
                                color = Color.White,
                                fontSize = 22.sp,
                                lineHeight = 25.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (episodeTitle.isNotBlank()) {
                        Text(
                            text = episodeTitle,
                            color = Color.White.copy(alpha = 0.70f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (fillerSkip != null) {
                        EpisodeFillerBadge(Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        val density = LocalDensity.current
        // See PlayerControlsScaffold: the anchor is measured from the seek track down, not from
        // the gradient's top edge, so a floating action sits by the timeline and still clears
        // every control below it — including the ones this row only shows sometimes.
        //
        // Ten dp of the old padding now belongs to the pointer band around the track (see
        // TvSeekPointerHeight), so the bar stands the same height as before and the anchor still
        // clears everything a pointer can press.
        val barTopPadding = 20.dp
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged {
                    onSeekAnchorChanged(
                        controlsSeekAnchorFor(with(density) { it.height.toDp() }, barTopPadding),
                    )
                }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        .32f to Color.Black.copy(alpha = 0.68f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    ),
                )
                .padding(start = 32.dp, end = 32.dp, top = barTopPadding, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val seekable = onSeek != null && durationMs > 0L
            val shownMs = scrubMs ?: positionMs
            val shownProgress = if (durationMs > 0L) {
                (shownMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                progress
            }
            val currentOnSeek by rememberUpdatedState(onSeek)
            val currentDurationMs by rememberUpdatedState(durationMs)
            val currentOnInteract by rememberUpdatedState(onInteract)
            // A TV is not only ever driven by a remote: plenty of boxes have a USB mouse or an
            // air-mouse remote, and a pointer expects to press the bar rather than walk to it.
            // The drawn track is 4dp tall, which is nothing to aim at, so the pointer is given a
            // band around it instead of the line itself.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TvSeekPointerHeight)
                    .then(
                        if (seekable) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                // Drag and tap are separate detectors on purpose: the drag one
                                // leaves the press unconsumed until the pointer has travelled far
                                // enough to be a scrub, so a plain click still reaches the tap
                                // detector and lands as one seek.
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            currentOnInteract()
                                            scrubMs = tvPointerSeekMs(offset.x, size.width, currentDurationMs)
                                        },
                                        onDragEnd = {
                                            scrubMs?.let { currentOnSeek?.invoke(it) }
                                            scrubMs = null
                                            scrubPresses = 0
                                        },
                                        onDragCancel = {
                                            scrubMs = null
                                            scrubPresses = 0
                                        },
                                        onHorizontalDrag = { change, _ ->
                                            currentOnInteract()
                                            scrubMs = tvPointerSeekMs(change.position.x, size.width, currentDurationMs)
                                        },
                                    )
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        currentOnInteract()
                                        // A click names a spot outright, so it commits at once —
                                        // there is no run of presses to wait out the way there is
                                        // on a remote.
                                        scrubMs = null
                                        scrubPresses = 0
                                        currentOnSeek?.invoke(
                                            tvPointerSeekMs(offset.x, size.width, currentDurationMs),
                                        )
                                    }
                                }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BufferedSeekTrack(
                    playedFraction = shownProgress,
                    bufferedFraction = bufferedFraction(bufferedPositionMs, durationMs),
                    enabled = durationMs > 0L,
                    markerFractions = remember(markers, durationMs) {
                        playerSeekMarkerFractions(markers, durationMs)
                    },
                    trackHeight = if (scrubMs != null) 8.dp else 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (scrubMs != null) 8.dp else 4.dp)
                        .then(
                            if (seekable) {
                                Modifier
                                    .focusHighlight(RoundedCornerShape(6.dp))
                                    .onFocusChanged { state ->
                                        if (state.isFocused) {
                                            focusedLabel = "Seek"
                                        } else {
                                            // Leaving the bar abandons an uncommitted preview
                                            // rather than seeking somewhere the viewer navigated
                                            // away from.
                                            scrubMs = null
                                            scrubPresses = 0
                                        }
                                    }
                                    .focusable()
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when {
                                            event.key == Key.DirectionLeft || event.key == Key.DirectionRight -> {
                                                val forward = event.key == Key.DirectionRight
                                                scrubPresses += 1
                                                val step = tvScrubStepMs(scrubPresses, durationMs)
                                                val from = scrubMs ?: positionMs
                                                scrubMs = (from + if (forward) step else -step)
                                                    .coerceIn(0L, durationMs)
                                                true
                                            }
                                            event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                                scrubMs?.let { onSeek?.invoke(it) }
                                                scrubMs = null
                                                scrubPresses = 0
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
            // A viewer who stops moving has chosen their spot; committing on idle means OK is
            // optional rather than a step they have to discover.
            LaunchedEffect(scrubMs) {
                val target = scrubMs ?: return@LaunchedEffect
                delay(TV_SCRUB_COMMIT_DELAY_MS)
                if (scrubMs == target) {
                    onSeek?.invoke(target)
                    scrubMs = null
                    scrubPresses = 0
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The elapsed side keeps its accent while a scrub is uncommitted: on a remote the
                // number is the only thing saying the preview has not been applied yet.
                TvTimePill(
                    text = formatTvPlayerTime(shownMs),
                    color = Color.White,
                    bold = scrubMs != null,
                )
                Text(
                    text = visibleFocusedLabel,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                TvTimePill(
                    text = "-${formatTvPlayerTime(remainingMs)}",
                    color = Color.White.copy(alpha = 0.68f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvControlButton("Previous episode", enabled = hasPrevious, onFocused = { focusedLabel = it }, onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = null)
                }
                TvControlButton("Rewind 10 seconds", onFocused = { focusedLabel = it }, onClick = onRewind) {
                    Icon(Icons.Default.FastRewind, contentDescription = null)
                }
                TvControlButton(
                    label = if (isPlaying) "Pause" else "Play",
                    onClick = onPlayPause,
                    onFocused = { focusedLabel = it },
                    large = true,
                    modifier = Modifier.focusRequester(playPauseFocusRequester),
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                }
                TvControlButton("Forward 10 seconds", onFocused = { focusedLabel = it }, onClick = onForward) {
                    Icon(Icons.Default.FastForward, contentDescription = null)
                }
                TvControlButton("Next episode", enabled = hasNext, onFocused = { focusedLabel = it }, onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = null)
                }
                fillerSkip?.let { skip ->
                    TvControlButton(
                        label = "Skip filler · ${skip.target}",
                        onFocused = { focusedLabel = it },
                        onClick = skip.onSkip,
                    ) {
                        Icon(Icons.Default.KeyboardDoubleArrowRight, contentDescription = null)
                    }
                }
                onEpisodes?.let { callback ->
                    TvControlButton("Episode list", onFocused = { focusedLabel = it }, onClick = callback) {
                        Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null)
                    }
                }
                onSources?.let { callback ->
                    TvControlButton("Servers", onFocused = { focusedLabel = it }, onClick = callback) {
                        Icon(Icons.Default.Dns, contentDescription = null)
                    }
                }
                download?.let { action ->
                    TvControlButton(
                        label = if (action.downloaded) "Downloaded" else "Download",
                        onFocused = { focusedLabel = it },
                        onClick = action.onClick,
                    ) {
                        Icon(
                            if (action.downloaded) Icons.Default.CheckCircle else Icons.Default.Download,
                            contentDescription = null,
                        )
                    }
                }
                onCaptions?.let { callback ->
                    TvControlButton("Captions", onFocused = { focusedLabel = it }, onClick = callback) {
                        Icon(Icons.Default.ClosedCaption, contentDescription = null)
                    }
                }
                onSettings?.let { callback ->
                    TvControlButton("Playback settings", onFocused = { focusedLabel = it }, onClick = callback) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvControlButton(
    label: String,
    enabled: Boolean = true,
    large: Boolean = false,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val controlFocus2 = rememberFocusFill()
    IconButton(
        colors = controlFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(if (large) 62.dp else 48.dp)
            .onFocusChanged { if (it.isFocused) onFocused(label) }
            .semantics { contentDescription = label }
            .focusFill(controlFocus2, RoundedCornerShape(32.dp), focusedScale = if (large) 1.15f else 1.10f),
    ) {
        icon()
    }
}

/**
 * The only thing Left/Right puts on screen while the viewer travels the timeline.
 *
 * Seeking used to open the whole control row, which took the remote with it: the next press moved
 * the focus ring along the buttons instead of moving the picture, so a viewer could travel exactly
 * ten seconds before having to start again. The row no longer opens — but seeking with nothing on
 * screen would be seeking blind, so this says where the press landed and gets out of the way.
 *
 * Deliberately not focusable and deliberately not a control: it reports, it cannot be operated.
 */
@Composable
internal fun TvSeekIndicator(
    positionMs: Long,
    durationMs: Long,
    forward: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (forward) Icons.Default.FastForward else Icons.Default.FastRewind,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    formatTvPlayerTime(positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                formatTvPlayerTime(durationMs),
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
    }
}

/**
 * The TV timestamp chip. Same treatment as [PlayerTimePill], sized for a ten-foot viewing distance.
 *
 * Not shared with the phone one because the two disagree about everything except the idea: this
 * carries the larger type scale, and it takes a colour because the elapsed side turns accent while a
 * scrub is waiting to commit.
 */
@Composable
private fun TvTimePill(
    text: String,
    color: Color,
    bold: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.46f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

private fun formatTvPlayerTime(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

/** How long the bar waits after the last press before committing the seek it is previewing. */
internal const val TV_SCRUB_COMMIT_DELAY_MS = 900L

/**
 * How many times a focus request aimed at the row is retried before it is given up on.
 *
 * The row is composed in the same frame as the state that shows it, but the request that aims the
 * remote at it runs from a coroutine a frame or two behind, and a [FocusRequester] nothing holds yet
 * throws rather than queueing. Dropping that throw left the row on screen with no focused control —
 * the same dead row an episode change used to produce, reached a different way.
 *
 * A throw is the only signal available: a granted request reports nothing at all, so this retries on
 * failure only and stops as soon as the requester is attached.
 */
internal const val TV_CONTROLS_FOCUS_ATTEMPTS = 5

/** How long to wait between those attempts. */
internal const val TV_CONTROLS_FOCUS_RETRY_MS = 48L

/**
 * How long a stream swap keeps watching the row it left focused.
 *
 * [tvEpisodeChangeFocus] cannot be answered in one look: the control that had the ring can be
 * disabled by the very update that brought the new stream — Next, arriving on the last episode —
 * and Compose clears focus a frame or two after the swap rather than with it. Nothing else can
 * take the row's focus this soon after an episode changes, so a loss inside this window is always
 * the swap's doing and always leaves the row unusable.
 */
internal const val TV_CONTROLS_REFOCUS_WINDOW_MS = 1_000L

/**
 * How long the player waits before believing it really has lost the remote ([tvFocusLostTarget]).
 *
 * Long enough for a move between two of its own controls, or a requester attached a frame late, to
 * settle; short enough that the viewer's next press has somewhere to land.
 */
internal const val TV_FOCUS_LOSS_SETTLE_MS = 400L

/**
 * How long [tvOverlayDismissFocus] waits before believing an overlay is really gone.
 *
 * The bottom-right action is one call site serving several labels, so "Skip Intro" becoming "+85s"
 * is a node Compose keeps and a ring that never moves — reacting to the gap between them would take
 * the remote off a button still under the viewer's thumb. Long enough to see the replacement arrive,
 * and Compose needs the frame anyway to finish clearing the focus the overlay took with it.
 */
internal const val TV_OVERLAY_DISMISS_SETTLE_MS = 64L

/**
 * How tall a band around the seek track answers a pointer.
 *
 * The track is drawn 4dp tall, which a mouse cannot reasonably be asked to hit — and a click that
 * misses it fell through to the layer behind, which reads the press as "dismiss the controls", so
 * the bar appeared to shut itself rather than seek.
 */
internal val TvSeekPointerHeight = 24.dp

/**
 * Where a pointer landing [x] pixels into a track [widthPx] wide asks the episode to go.
 *
 * A pointer names a spot outright rather than nudging from the current one, which is the opposite
 * of what the remote does and the same as what the phone's slider does.
 */
internal fun tvPointerSeekMs(x: Float, widthPx: Int, durationMs: Long): Long {
    if (widthPx <= 0 || durationMs <= 0L) return 0L
    val fraction = (x / widthPx.toFloat()).coerceIn(0f, 1f)
    return (fraction * durationMs).toLong().coerceIn(0L, durationMs)
}

/**
 * How far one Left/Right press moves the preview.
 *
 * A fixed ten seconds is what made the old +10s button tedious on a long episode, so the step
 * grows while the key is being worked: ten seconds to place yourself precisely, then thirty, then
 * a minute once it is clear the viewer is travelling. It is also capped at a fiftieth of the
 * episode, so a short one never jumps in minute-sized leaps.
 */
internal fun tvScrubStepMs(presses: Int, durationMs: Long): Long {
    val base = when {
        presses <= 4 -> 10_000L
        presses <= 10 -> 30_000L
        else -> 60_000L
    }
    val proportional = (durationMs / 50L).coerceAtLeast(5_000L)
    return minOf(base, proportional).coerceAtLeast(5_000L)
}

/**
 * Back, for a pointer. It presses the back dispatcher rather than sending a Back key, so it does what
 * the screen's own Back does — leave a downloaded episode, leave fullscreen on the watch page — in
 * one click, where the remote's key first retires the player's panels and controls one by one.
 */
@Composable
private fun TvPlayerBackArrow() {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.62f))
            .focusProperties { canFocus = false }
            .clickable(onClickLabel = "Back") { dispatcher?.onBackPressed() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
    }
}
