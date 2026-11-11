package com.anilili.ui.random

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.anilili.ui.adaptive.FocusHaloGap
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusHalo
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.anilili.data.model.Media
import com.anilili.data.settings.SettingsStore
import com.anilili.data.settings.ThemeAccent
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.FloatingNavDisc
import com.anilili.ui.components.ShrinkingTitle
import com.anilili.ui.dialogs.CoverScreenWithDialogWindow
import com.anilili.ui.dialogs.popupGlass
import com.anilili.ui.dialogs.rememberPopupBackdrop
import com.anilili.ui.theme.LocalAppColors
import com.anilili.ui.theme.LocalMidnightAccent
import com.anilili.ui.theme.LocalThemeSpec
import com.anilili.ui.theme.Midnight
import com.anilili.ui.theme.ShowColourTheme
import com.anilili.ui.theme.appColors
import com.anilili.ui.theme.showSeed
import com.anilili.util.cached
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

/**
 * Home's dice, floating above the capsule's right-hand end. The die turns over once per tap — the
 * card stack it opens carries the dealing itself.
 */
@Composable
fun RandomAnimeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: HazeState? = null,
) {
    val spin = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    FloatingNavDisc(
        contentDescription = "Random anime",
        onClick = {
            scope.launch { spin.animateTo(spin.value + 360f, DieSettle) }
            onClick()
        },
        backdrop = backdrop,
        modifier = modifier,
    ) {
        Icon(
            Icons.Default.Casino,
            contentDescription = null,
            tint = LocalMidnightAccent.current,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = spin.value },
        )
    }
}

/**
 * The pick as a swipeable card stack, Tinder's way: drag the card right to open the title, left to
 * deal the next one. There are no buttons — the card is the control.
 *
 * The card follows the finger freely and tilts with it, about a pivot set by where it was grabbed,
 * so a card held by its top swings the other way from one held by its foot. Before it is let go it
 * says what letting go will do: a WATCH stamp and a green wash on the way right, SKIP and red on
 * the way left, with a tick under the thumb as it crosses the point of no return. Released past
 * that point, or flicked, it carries on along the throw and leaves the screen; anywhere short of
 * it, it springs home. The next card waits underneath and grows into place as the top one goes.
 *
 * The stack floats over the whole window rather than in a panel, because a card thrown off a
 * clipped panel vanishes at its edge instead of leaving the screen. Behind it is the app frosted
 * when Popups glass is on, and the plain dim every other popup uses when it is off.
 *
 * Above the stack, once the viewer has a list with anything in it, a row of chips says where the
 * cards come from: anything at all, or one of their own lists.
 */
@Composable
fun RandomAnimeDialog(
    state: RandomAnimeState,
    onSkip: () -> Unit,
    onOpen: (Int) -> Unit,
    onSource: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val pick = state.pick
    val isTv = LocalAppDeviceProfile.current.isTv
    // One swipe per card, so the card dealt next starts at rest however far the last was thrown.
    val swipe = remember(pick?.id) { SwipeState() }
    PickTheme(pick?.coverImage?.color) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            CoverScreenWithDialogWindow()
            val backdrop = rememberPopupBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Back is claimed here, ahead of the focus system: on a television Compose turns
                    // it into a focus exit first, and a focused card can swallow it there, leaving
                    // Back doing nothing but dropping the ring. Consumed on the way down, acted on
                    // the way up, like the player's own handler.
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Back) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyUp) onDismiss()
                        true
                    }
                    .then(
                        if (backdrop != null) {
                            Modifier.popupGlass(backdrop).background(Color.Black.copy(alpha = 0.38f))
                        } else {
                            // A television has no glass to hide the page behind, and the page there is
                            // busy — a hero, a header, rails — so it is dimmed nearly away rather than
                            // left to compete with the card and collide with the header.
                            Modifier.background(Color.Black.copy(alpha = if (isTv) 0.86f else 0.35f))
                        },
                    )
                    // A tap on the dim, not on the card, puts the stack away.
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    // Televisions crop their edges; keep the close button and hint inside what shows.
                    .then(if (isTv) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DialogHeader(onDismiss)
                    if (state.sources.size > 1) {
                        SourceChips(state.sources, state.source.key, onSource, isTv)
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val height = min(min(maxWidth - 40.dp, MaxCardWidth) * CardAspect, maxHeight - 16.dp)
                        CardStack(
                            state = state,
                            swipe = swipe,
                            onSkip = onSkip,
                            onOpen = onOpen,
                            modifier = Modifier.size(width = height / CardAspect, height = height),
                        )
                    }
                    SwipeHint(isTv)
                }
            }
        }
    }
}

@Composable
private fun CardStack(
    state: RandomAnimeState,
    swipe: SwipeState,
    onSkip: () -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pick = state.pick
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val isTv = LocalAppDeviceProfile.current.isTv
    val open = { media: Media -> scope.launch { swipe.leanAndThrow(1) { onOpen(media.id) } } }
    val skip = { scope.launch { swipe.leanAndThrow(-1, onSkip) } }
    // The stack is one focus stop for its whole life — the cards inside come and go, the stop does
    // not — so the cursor is never left on a card that has just been thrown away.
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // A dialog's window attaches a frame or two after it is composed; on a slow box the first
        // request throws. Retried briefly, as the television's top bar does for its own tabs.
        repeat(FOCUS_ATTEMPTS) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos {}
        }
    }
    Box(
        modifier = modifier
            .onSizeChanged { swipe.size = it }
            // The remote's swipe. Left and right lean the card out and throw it, OK opens it, and
            // Down is held so the cursor cannot wander off the stack; Up is left alone, which is
            // how the close button above is reached. Held keys repeat, and a repeat is ignored:
            // holding Left should throw one card, not race through the deck.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val repeat = event.nativeKeyEvent.repeatCount > 0
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (!repeat && pick != null) skip()
                        true
                    }
                    Key.DirectionRight -> {
                        if (!repeat && pick != null) open(pick)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when {
                            repeat -> Unit
                            pick != null -> open(pick)
                            state.failure != null -> onSkip()
                        }
                        true
                    }
                    Key.DirectionDown -> true
                    else -> false
                }
            }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // The gestures sit on the stack, which stays put, not on the card, which moves: read off
            // the card itself the finger would barely move at all, and the throw would have no speed.
            .then(
                if (pick == null) {
                    Modifier.pointerInput(state.failure) {
                        // An empty slot after a failure is the way to ask again.
                        detectTapGestures { if (state.failure != null) onSkip() }
                    }
                } else {
                    Modifier
                        .pointerInput(pick.id) {
                            val tracker = VelocityTracker()
                            detectDragGestures(
                                onDragStart = { down ->
                                    tracker.resetTracking()
                                    swipe.grabbedTop = down.y < size.height / 2f
                                },
                                onDragEnd = {
                                    val velocity = tracker.calculateVelocity()
                                    scope.launch {
                                        swipe.release(velocity, onSkip = onSkip, onOpen = { onOpen(pick.id) })
                                    }
                                },
                                onDragCancel = { scope.launch { swipe.home() } },
                            ) { change, amount ->
                                if (swipe.thrown) return@detectDragGestures
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val next = swipe.offset.value + amount
                                if (swipe.crossesDecision(next.x)) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                scope.launch { swipe.offset.snapTo(next) }
                            }
                        }
                        .pointerInput(pick.id) { detectTapGestures { open(pick) } }
                        // Without buttons, the two answers have to reach a screen reader some other way.
                        .semantics {
                            contentDescription = pick.title.preferred
                            customActions = listOf(
                                CustomAccessibilityAction("Watch") { open(pick); true },
                                CustomAccessibilityAction("Skip") { skip(); true },
                            )
                        }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The card underneath: a little smaller and set back, growing to full size as the top one
        // is dragged clear, so it is already in place by the time it is uncovered.
        key(state.upNext?.id) {
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) { appear.animateTo(1f, tween(260)) }
            AnimeCard(
                media = state.upNext,
                rolling = state.rolling,
                failure = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val lift = abs(swipe.progress)
                        val s = UnderScale + (1f - UnderScale) * lift
                        scaleX = s
                        scaleY = s
                        alpha = appear.value
                    },
            )
        }
        AnimeCard(
            media = pick,
            rolling = state.rolling,
            failure = state.failure?.withCode(),
            swipe = swipe,
            // The television's ring, on the card itself so it travels with a throw. Phones mark
            // nothing: a finger needs no cursor.
            ringed = isTv && focused,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val o = swipe.offset.value
                    translationX = o.x
                    translationY = o.y
                    rotationZ = swipe.rotation
                },
        )
    }
}

/** Where one card is in its swipe. */
@Stable
private class SwipeState {
    val offset = Animatable(Offset.Zero, Offset.VectorConverter)
    var size by mutableStateOf(IntSize.Zero)

    /** Grabbed above the middle? The tilt turns the other way for a card held by its foot. */
    var grabbedTop = true
    var thrown = false
        private set
    private var decided = false

    private val width: Float get() = size.width.coerceAtLeast(1).toFloat()

    /** -1 (all the way to skip) to 1 (all the way to watch); either end is the point of no return. */
    val progress: Float get() = (offset.value.x / (width * DecisionFraction)).coerceIn(-1f, 1f)

    val rotation: Float get() = offset.value.x / width * MaxTiltDegrees * if (grabbedTop) 1f else -1f

    /** True once each time a drag to [x] crosses the decision line, either way, for the haptic. */
    fun crossesDecision(x: Float): Boolean {
        val now = abs(x) >= width * DecisionFraction
        if (now == decided) return false
        decided = now
        return true
    }

    /** Thrown if it got far or fast enough, otherwise home. */
    suspend fun release(velocity: Velocity, onSkip: () -> Unit, onOpen: () -> Unit) {
        val x = offset.value.x
        val flung = abs(velocity.x) > FlingVelocity && sign(velocity.x) == sign(x) && abs(x) > width * 0.08f
        when {
            x >= width * DecisionFraction || (flung && x > 0) -> throwCard(1, velocity, onOpen)
            x <= -width * DecisionFraction || (flung && x < 0) -> throwCard(-1, velocity, onSkip)
            else -> home()
        }
    }

    /** Back to rest with Tinder's give: a spring loose enough to overshoot once. */
    suspend fun home() {
        decided = false
        offset.animateTo(Offset.Zero, CardHome)
    }

    /**
     * The remote's swipe: a press has no drag to watch, so the card leans out to the decision line
     * first — tilted, its stamp at full strength — and holds there a beat before it goes. Thrown
     * straight off, the verdict was on screen for a few frames and the press read as the card
     * simply vanishing.
     */
    suspend fun leanAndThrow(direction: Int, then: () -> Unit) {
        if (thrown) return
        grabbedTop = true
        offset.animateTo(Offset(direction * width * DecisionFraction, 0f), tween(LeanMs))
        delay(LeanHoldMs)
        throwCard(direction, Velocity(direction * MinThrowSpeed, 0f), then)
    }

    /**
     * Throws the card off in [direction] (-1 left, 1 right) and then runs [then], once. It keeps the
     * throw's heading — a card flicked up and right leaves up and right — and its speed, never
     * slower than a brisk flick, so a slow drag past the line still goes with some conviction.
     */
    suspend fun throwCard(direction: Int, velocity: Velocity, then: () -> Unit) {
        if (thrown) return
        thrown = true
        val start = offset.value
        val targetX = direction * width * ThrowDistance
        val slope = when {
            abs(velocity.x) > 300f -> velocity.y / velocity.x
            abs(start.x) > 1f -> start.y / start.x
            else -> 0f
        }.coerceIn(-1.2f, 1.2f)
        val target = Offset(targetX, start.y + (targetX - start.x) * slope)
        val speed = maxOf(abs(velocity.x), MinThrowSpeed)
        val millis = (abs(targetX - start.x) / speed * 1000f).toInt().coerceIn(140, 340)
        offset.animateTo(target, tween(millis, easing = LinearEasing))
        then()
    }
}

/**
 * One card: the poster edge to edge, the facts over a dark foot, and — while it is being dragged —
 * the verdict it is heading for. [media] null is a card on its way (the die) or one that failed.
 */
@Composable
private fun AnimeCard(
    media: Media?,
    rolling: Boolean,
    failure: String?,
    modifier: Modifier = Modifier,
    swipe: SwipeState? = null,
    ringed: Boolean = false,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            // Ahead of the clip, which would shave off a ring drawn outside the card.
            .focusHalo(ringed, shape, FocusHaloGap)
            .shadow(14.dp, shape)
            .clip(shape)
            .background(Midnight.Hover)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (media == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TumblingDie(rolling = rolling, size = 48)
                if (failure != null) {
                    Text(
                        text = failure,
                        style = MaterialTheme.typography.bodySmall,
                        color = Midnight.Text2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                    )
                    Text(
                        text = "Tap to try again",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.W700,
                        color = LocalMidnightAccent.current,
                    )
                }
            }
            return@Box
        }
        AsyncImage(
            model = media.coverImage.best,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(CardScrim))
        if (swipe != null) {
            // Read in the draw phase only, so a drag repaints the card without recomposing it.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = abs(swipe.progress) * 0.28f }
                    .background(if (swipe.offset.value.x >= 0f) Midnight.Green else Midnight.Red),
            )
            Stamp(
                text = "WATCH",
                colour = Midnight.Green,
                rotation = -18f,
                strength = { swipe.progress.coerceAtLeast(0f) },
                modifier = Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 30.dp),
            )
            Stamp(
                text = "SKIP",
                colour = Midnight.Red,
                rotation = 18f,
                strength = { (-swipe.progress).coerceAtLeast(0f) },
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 30.dp),
            )
        }
        CardInfo(media, Modifier.align(Alignment.BottomStart))
    }
}

/** Title, the facts that decide whether to bother, genres and a taste of the synopsis. */
@Composable
private fun CardInfo(media: Media, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // The shade belongs to the text, not to a fixed share of the card: a card-wide gradient
            // started at a set height, and on a television — larger type, the same card — the title
            // rose above it onto bright artwork and went unreadable. Grown from the block's own top,
            // it starts above the title however many lines the block takes.
            .background(InfoShade)
            .padding(start = 18.dp, end = 18.dp, bottom = 18.dp, top = 56.dp),
    ) {
        // Shrinks before it cuts, like the spotlight's title: an ellipsis took the end of the name
        // ("The Irregular at Magic High Scho…"), which is the part that says which entry it is.
        // Measured against the card, not judged by character count, so wide and narrow letters and
        // a television's larger type each get the size that actually fits.
        ShrinkingTitle(
            text = media.title.preferred,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.W800,
                shadow = TitleShadow,
            ),
            color = Color.White,
            maxLines = 2,
            minFontSize = TitleMinSize,
        )
        PickFacts(media, Modifier.padding(top = 4.dp))
        val genres = media.genres.take(3)
        if (genres.isNotEmpty()) {
            GenreChips(genres, Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        val synopsis = remember(media.description) { plainSynopsis(media.description) }
        if (synopsis != null) {
            Text(
                text = synopsis,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                // Up to three, but only as many as the text fills: a short synopsis keeps its one
                // or two lines, and only a long one takes the third rather than stopping at two.
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** A rubber-stamp verdict, faded and pressed in with the drag towards it. */
@Composable
private fun Stamp(
    text: String,
    colour: Color,
    rotation: Float,
    strength: () -> Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 3.sp),
        fontWeight = FontWeight.Black,
        color = colour,
        modifier = modifier
            .graphicsLayer {
                val s = strength()
                alpha = s
                rotationZ = rotation
                // Pressed down onto the card as it fades in, like a stamp landing.
                val scale = 1.25f - 0.25f * s
                scaleX = scale
                scaleY = scale
            }
            .border(4.dp, colour, shape)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/**
 * The only instructions the stack gives: which way is which, in the stamps' own colours. A remote
 * has no swipe, so the television names the arrows instead.
 */
@Composable
private fun SwipeHint(isTv: Boolean) {
    Text(
        text = buildAnnotatedString {
            if (isTv) {
                withStyle(SpanStyle(color = Midnight.Red, fontWeight = FontWeight.W800)) { append("◀  Skip") }
                append("      ")
                withStyle(SpanStyle(color = Midnight.Green, fontWeight = FontWeight.W800)) { append("Watch  ▶") }
            } else {
                append("Swipe ")
                withStyle(SpanStyle(color = Midnight.Red, fontWeight = FontWeight.W800)) { append("left to skip") }
                append("  ·  ")
                withStyle(SpanStyle(color = Midnight.Green, fontWeight = FontWeight.W800)) { append("right to watch") }
            }
        },
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.72f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 20.dp),
    )
}

/**
 * [ShowColourTheme]'s seeding, honouring the same Appearance switch, but with one shape whatever
 * the colour. ShowColourTheme composes its content in a different place for a grey poster than for
 * a coloured one, and around a dialog that tears the window down and builds it again — a swipe from
 * a monochrome cover to a bright one would blink the whole stack instead of dealing a card. The
 * accent is animated, so the light changes with the deal rather than snapping under it.
 */
@Composable
private fun PickTheme(colour: String?, content: @Composable () -> Unit) {
    val enabled by SettingsStore.showColourPages.collectAsState()
    val base = LocalAppColors.current
    val spec = LocalThemeSpec.current
    val seed = remember(colour) { showSeed(colour) }
    val colors = remember(enabled, seed, spec, base) {
        if (!enabled || seed == null) base
        else appColors(spec.copy(accent = ThemeAccent.CUSTOM, customSeed = seed), wallpaperSeed = null)
    }
    val accent by animateColorAsState(
        targetValue = if (colors === base) LocalMidnightAccent.current else colors.scheme.primary,
        animationSpec = tween(360),
        label = "pick accent",
    )
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalMidnightAccent provides accent,
        content = content,
    )
}

@Composable
private fun DialogHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "RANDOM PICK",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
            fontWeight = FontWeight.W800,
            color = LocalMidnightAccent.current,
            modifier = Modifier.weight(1f),
        )
        val closeFocus = rememberFocusFill()
        IconButton(
            colors = closeFocus.iconButtonColors(IconButtonDefaults.iconButtonColors()),
            onClick = onDismiss,
            modifier = Modifier.size(44.dp).focusFill(closeFocus, CircleShape),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = closeFocus.content(Color.White.copy(alpha = 0.85f)),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Where the cards come from, one chip per source. Picking one deals afresh from it.
 *
 * A row that scrolls rather than wraps: a viewer with a dozen custom lists would otherwise push the
 * card down by several lines. On a television it sits between the close button and the stack, so
 * Up from the card lands here and Up again reaches the close button.
 */
@Composable
private fun SourceChips(
    sources: List<DiceSource>,
    selectedKey: String,
    onSource: (String) -> Unit,
    isTv: Boolean,
) {
    val accent = LocalMidnightAccent.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = if (isTv) 8.dp else 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sources.forEach { source ->
            val selected = source.key == selectedKey
            val focus = rememberFocusFill()
            val shape = RoundedCornerShape(50)
            FilterChip(
                selected = selected,
                onClick = { onSource(source.key) },
                label = {
                    Text(
                        text = if (source.isAnywhere) source.label else "${source.label} · ${source.ids.size}",
                        maxLines = 1,
                        fontWeight = if (selected) FontWeight.W700 else FontWeight.Medium,
                    )
                },
                shape = shape,
                colors = focus.chipColors(
                    FilterChipDefaults.filterChipColors(
                        containerColor = Color.Black.copy(alpha = 0.32f),
                        labelColor = Color.White.copy(alpha = 0.85f),
                        selectedContainerColor = accent,
                        selectedLabelColor = Color.Black,
                    ),
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = Color.White.copy(alpha = 0.22f),
                    selectedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.focusFill(focus, shape),
            )
        }
    }
}

/**
 * Format, year, length and score — whatever of them the record carries — as one line of text.
 *
 * One Text rather than a row of pieces: a row let the score wrap onto a line of its own on a
 * television, whose type is larger against a card no wider, and left the star up on the first.
 *
 * Nothing is ever left out. When the whole line does not fit it gets smaller, like the title
 * above it, half a point at a time down to [FactsMinSize] — so a long run keeps its "101 eps" and
 * its score. Past that the separators close up; only a line still too long after both is
 * ellipsized, which no real record has needed. An ellipsis used to come first, and it took the
 * end of the line, which is the score — the one fact a pick is judged on (Slam Dunk read "★ 8…").
 * Measured against the card's real width, not decided by digit count, because a television's
 * larger type and a narrow phone run out of room at different lengths.
 */
@Composable
private fun PickFacts(media: Media, modifier: Modifier = Modifier) {
    val score = media.averageScore?.takeIf { it > 0 }
    val facts = factPieces(
        format = media.format?.let(::formatLabel),
        year = media.seasonYear ?: media.startDate?.year,
        episodes = episodeCount(media.episodes, media.nextAiringEpisode?.episode),
    )
    if (facts.isEmpty() && score == null) return
    val base = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val (fitting, style) = remember(facts, score, base, maxWidth) {
            val baseSize = base.fontSize.takeIf { it.isSpecified }?.value ?: 14f
            val sizes = generateSequence(baseSize) { it - FactsStep }
                .takeWhile { it >= FactsMinSize.value }
                .toList()
                .ifEmpty { listOf(baseSize) }
            val spaced = factLine(facts, score, FactsSeparator)
            val tight = factLine(facts, score, FactsSeparatorTight)
            val smallest = base.copy(fontSize = sizes.last().sp)
            val tries = sizes.asSequence().map { spaced to base.copy(fontSize = it.sp) } +
                sequenceOf(tight to smallest)
            tries.firstOrNull { (line, style) ->
                measurer.measure(line, style, maxLines = 1, softWrap = false).size.width <= maxWidth
            } ?: (tight to smallest)
        }
        Text(
            text = fitting,
            style = style,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun factLine(facts: List<String>, score: Int?, separator: String) = buildAnnotatedString {
    append(facts.joinToString(separator))
    if (score != null) {
        if (facts.isNotEmpty()) append(separator)
        withStyle(SpanStyle(color = StarColor)) { append("★ ") }
        append("$score%")
    }
}

/**
 * The fact line's pieces, all of them, in reading order. A single "episode" is left out because
 * it is a film's or a special's, where it says nothing; the score is appended by [factLine].
 */
/**
 * The count to show: the planned total when AniList has one, else what has aired so far.
 *
 * A show still airing with no announced end has no total at all — ONE PIECE and Detective Conan
 * both come back `episodes: null` — so the longest runs in the pool were exactly the ones whose
 * count went missing, which read as the line dropping three- and four-digit numbers. The next
 * episode's number less one is what has aired, the same count the Home spotlight shows.
 */
internal fun episodeCount(total: Int?, nextAiring: Int?): Int? =
    total ?: nextAiring?.minus(1)?.takeIf { it > 0 }

internal fun factPieces(format: String?, year: Int?, episodes: Int?): List<String> =
    listOfNotNull(format, year?.toString(), episodes?.takeIf { it > 1 }?.let { "$it eps" })

private const val FactsSeparator = "  ·  "
private const val FactsSeparatorTight = " · "

/**
 * Genre chips on one line, as many as fit whole. A plain row clipped the last one mid-word
 * ("Myster") wherever the card was narrower than three chips — on a television, every time.
 */
@Composable
private fun GenreChips(genres: List<String>, modifier: Modifier = Modifier) {
    androidx.compose.ui.layout.Layout(
        content = { genres.forEach { GenreChip(it) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placed = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var width = 0
        for (measurable in measurables) {
            val chip = measurable.measure(loose)
            val next = width + (if (placed.isEmpty()) 0 else gap) + chip.width
            if (next > constraints.maxWidth) break
            placed += chip
            width = next
        }
        val height = placed.maxOfOrNull { it.height } ?: 0
        // Reported at the slot's width, not the chips': narrower than a filled slot, the row was
        // centred in it, and the chips sat apart from the left-aligned title above them.
        layout(constraints.constrainWidth(width), height) {
            var x = 0
            placed.forEach { chip ->
                chip.place(x, (height - chip.height) / 2)
                x += chip.width + gap
            }
        }
    }
}

/** Smoked glass over the poster, like the spotlight's pills, so it reads over any artwork. */
@Composable
private fun GenreChip(label: String) {
    val shape = RoundedCornerShape(50)
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.92f),
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.40f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * The die: turning for as long as a card is on its way, then settling face-up on the next whole
 * turn, so it never comes to rest at an angle.
 */
@Composable
private fun TumblingDie(rolling: Boolean, size: Int, tint: Color = LocalMidnightAccent.current) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(rolling) {
        if (rolling) {
            while (true) {
                angle.animateTo(angle.value + 360f, tween(TURN_MS, easing = LinearEasing))
            }
        } else {
            val rest = kotlin.math.ceil(angle.value / 360f) * 360f
            angle.animateTo(rest, DieSettle)
        }
    }
    Icon(
        Icons.Default.Casino,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(size.dp)
            .graphicsLayer { rotationZ = angle.value },
    )
}

/**
 * The synopsis as plain prose: AniList's text still carries `<br>` and `<i>` with `asHtml: false`,
 * and its source credit trails after a blank line, which three lines have no room for.
 */
internal fun plainSynopsis(description: String?): String? {
    if (description.isNullOrBlank()) return null
    return description
        .replace(Regex.cached("(?i)<br\\s*/?>"), "\n")
        .replace(Regex.cached("<[^>]*>"), "")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .replace(Regex.cached("\\n{2,}"), "\n")
        .lines()
        .filterNot { line -> line.trim().let { it.startsWith("(Source:", true) || it.startsWith("Source:", true) } }
        .joinToString("\n")
        .trim()
        .takeIf { it.isNotEmpty() }
}

private fun formatLabel(format: String): String = when (format) {
    "TV" -> "TV"
    "TV_SHORT" -> "TV Short"
    "MOVIE" -> "Movie"
    "SPECIAL" -> "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> "Music"
    else -> format.lowercase().replaceFirstChar(Char::uppercase)
}

/** A light shade across the card's top, so the stamps read over a bright sky. */
private val CardScrim = Brush.verticalGradient(
    0.0f to Color.Black.copy(alpha = 0.18f),
    0.22f to Color.Transparent,
)

/** Behind the card's text, from nothing at the block's top to near-black at the card's foot. */
private val InfoShade = Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.28f to Color.Black.copy(alpha = 0.62f),
    1.0f to Color.Black.copy(alpha = 0.94f),
)

/** Smallest the card title shrinks to before it ellipsizes; still clearly the card's heading. */
private val TitleMinSize = 16.sp

/**
 * Smallest the fact line shrinks to — about four-fifths of its 14sp, still easily read from a
 * sofa. Below this the separators close up rather than the type getting any smaller.
 */
private val FactsMinSize = 11.sp
private const val FactsStep = 0.5f

private val TitleShadow = androidx.compose.ui.graphics.Shadow(
    color = Color.Black.copy(alpha = 0.7f),
    blurRadius = 10f,
)
private val MaxCardWidth = 380.dp
private const val CardAspect = 1.5f
private const val UnderScale = 0.94f
/** Share of the card's width a drag must cover to decide; the stamps are full strength there. */
private const val DecisionFraction = 0.38f
/** px/s; a quick flick decides even short of [DecisionFraction]. */
private const val FlingVelocity = 1_000f
/** Slowest a thrown card leaves at, px/s, so a slow drag past the line still goes decisively. */
private const val MinThrowSpeed = 2_400f
/** How far past centre a thrown card is sent, in card widths: comfortably off any screen. */
private const val ThrowDistance = 1.9f
private const val MaxTiltDegrees = 16f
/** A remote press: how long the card takes to lean out, and how long it shows its verdict. */
private const val LeanMs = 170
private const val LeanHoldMs = 120L
/** Frames to keep asking for the stack's focus while the dialog window attaches. */
private const val FOCUS_ATTEMPTS = 10
private val StarColor = Color(0xFFEFC66B)
private const val TURN_MS = 420
private val DieSettle = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)
private val CardHome = spring<Offset>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
