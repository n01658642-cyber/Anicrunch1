package com.anilili.ui.tour

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.profiles.ProfilesUi
import com.anilili.ui.theme.LocalMidnightAccent
import com.anilili.ui.theme.Midnight
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The things on screen a tour step can point at. Each is tagged where it is drawn ([tourTarget]). */
enum class TourTarget { FOR_YOU, BECAUSE, ROW_HANDLE, DICE, ARRANGE_SETTINGS }

/**
 * One step: what it points at (null for a card on its own), what it says, and what it adds when
 * the thing is not on screen — a new viewer has no For You row yet, and still meets it here.
 */
internal data class TourStep(
    val target: TourTarget?,
    val title: String,
    val body: String,
    val whenMissing: String? = null,
)

/** This release's tour, as the device draws Home: drag on a phone, Settings on a television. */
internal fun tourSteps(isTv: Boolean): List<TourStep> = listOf(
    TourStep(
        target = null,
        title = "Home just got smarter",
        body = "A quick look at what's new — three things, under a minute.",
    ),
    TourStep(
        target = TourTarget.FOR_YOU,
        title = "For you",
        body = "Picked for you from everything you've watched. The more you watch, the better it gets.",
        whenMissing = "It shows up on Home once you've watched a few titles.",
    ),
    TourStep(
        target = TourTarget.BECAUSE,
        title = "Because you watched…",
        body = "More like the last series you finished, so there's always something lined up next.",
        whenMissing = "It appears on Home after you finish a series.",
    ),
    if (isTv) {
        TourStep(
            target = TourTarget.ARRANGE_SETTINGS,
            title = "Make Home yours",
            body = "In Settings → Appearance → Arrange Home, move rows up or down and hide the ones you don't need.",
        )
    } else {
        TourStep(
            target = TourTarget.ROW_HANDLE,
            title = "Make Home yours",
            body = "Hold a row's title and drag it up or down — let go and it snaps into place. " +
                "To hide rows: Settings → Appearance → Arrange Home.",
        )
    },
    TourStep(
        target = TourTarget.DICE,
        title = "Can't decide?",
        body = "Roll the dice for a random pick. Not feeling it? Roll again.",
    ),
)

/**
 * What's new, shown once: on the first open of a release that carries a new [TOUR_ID], whether the
 * app was updated or installed fresh — for a new viewer it is the welcome.
 *
 * "Seen" is a file of its own rather than a setting, so it belongs to the install and not to a
 * profile: a television shared by four profiles shows it once, not four times. It is written when
 * the tour starts, so closing the app halfway through does not bring it back.
 */
object WhatsNewTour {
    /** Change this for the next release's tour; every install then shows it once more. */
    const val TOUR_ID = "home-2026-10"
    private const val SEEN_FILE = "whats-new-seen"

    var active by mutableStateOf(false)
        private set
    var step by mutableIntStateOf(0)
        private set

    /** Where each tagged target is on screen right now, in window coordinates; only while active. */
    internal val bounds = mutableStateMapOf<TourTarget, Rect>()

    /** Set by the Home that is on screen: scrolls a target's row into view. */
    internal var bringIntoView: (suspend (TourTarget) -> Unit)? = null

    fun startIfDue(context: Context) {
        if (active) return
        val file = File(context.applicationContext.filesDir, SEEN_FILE)
        if (runCatching { file.readText().trim() }.getOrNull() == TOUR_ID) return
        runCatching { file.writeText(TOUR_ID) }
            .onFailure { DiagnosticsLog.throwable("WhatsNewTour could not record itself as seen", it) }
        step = 0
        active = true
        DiagnosticsLog.event("WhatsNewTour started id=$TOUR_ID")
    }

    internal fun next(count: Int) {
        if (step + 1 >= count) finish() else step++
    }

    internal fun finish() {
        DiagnosticsLog.event("WhatsNewTour finished at step=$step")
        active = false
        step = 0
        bounds.clear()
    }
}

/**
 * Marks what [target] is. Costs nothing while no tour runs; during one it reports where the thing
 * is, every time it moves, so the highlight follows a row as Home scrolls it into place.
 */
fun Modifier.tourTarget(target: TourTarget): Modifier = composed {
    if (!WhatsNewTour.active) return@composed this
    DisposableEffect(target) { onDispose { WhatsNewTour.bounds.remove(target) } }
    onGloballyPositioned { WhatsNewTour.bounds[target] = it.boundsInWindow() }
}

/**
 * Starts the tour once Home is really what the viewer is looking at: the launch intro over, and no
 * profile screen ("Who's watching?", a PIN, first-run setup) waiting on top. A moment later still,
 * so the rows it points at have drawn. Call from each Home.
 */
@Composable
fun StartWhatsNewTourWhenSettled() {
    val context = LocalContext.current
    val introDone by ProfilesUi.launchIntroDone.collectAsState()
    val profileOverlay by ProfilesUi.overlay.collectAsState()
    LaunchedEffect(introDone, profileOverlay) {
        if (!introDone || profileOverlay != null) return@LaunchedEffect
        delay(TOUR_START_DELAY_MS)
        WhatsNewTour.startIfDue(context)
    }
}

/** Lets [WhatsNewTour] scroll this Home to a target. [scroll] gets the target, and ignores ones it lacks. */
@Composable
fun RegisterTourScroller(scroll: suspend (TourTarget) -> Unit) {
    val latest by rememberUpdatedState(scroll)
    DisposableEffect(Unit) {
        val scroller: suspend (TourTarget) -> Unit = { latest(it) }
        WhatsNewTour.bringIntoView = scroller
        onDispose { if (WhatsNewTour.bringIntoView === scroller) WhatsNewTour.bringIntoView = null }
    }
}

/**
 * The tour itself, over everything: the screen dimmed with the step's target cut out of the dark
 * and ringed, and a card beside it saying what it is. Touches never reach the app underneath; on a
 * remote the card's two buttons hold the cursor, and Back ends the tour.
 */
@Composable
fun WhatsNewTourOverlay() {
    if (!WhatsNewTour.active) return
    val device = LocalAppDeviceProfile.current
    val density = LocalDensity.current
    val steps = remember(device.isTv) { tourSteps(device.isTv) }
    val index = WhatsNewTour.step.coerceIn(0, steps.lastIndex)
    val step = steps[index]
    val accent = LocalMidnightAccent.current
    BackHandler { WhatsNewTour.finish() }

    // Bring the step's target on screen, then wait for it to report where it is. A target that
    // never does (no For You row yet, say) gets the card on its own, with [TourStep.whenMissing].
    var settled by remember(index) { mutableStateOf(step.target == null) }
    LaunchedEffect(index) {
        val target = step.target ?: return@LaunchedEffect
        WhatsNewTour.bringIntoView?.invoke(target)
        withTimeoutOrNull(TARGET_WAIT_MS) { snapshotFlow { WhatsNewTour.bounds[target] }.first { it != null } }
        delay(SCROLL_SETTLE_MS)
        settled = true
    }

    var origin by remember { mutableStateOf(Offset.Zero) }
    val padding = with(density) { 8.dp.toPx() }
    val found = step.target?.let { WhatsNewTour.bounds[it] }
        ?.translate(-origin)
        ?.inflate(padding)
        ?.takeIf { settled }
    val holeAlpha by animateFloatAsState(if (found != null) 1f else 0f, tween(220), label = "tour-hole-alpha")
    var lastHole by remember { mutableStateOf(Rect.Zero) }
    if (found != null) lastHole = found
    val hole by animateRectAsState(lastHole, tween(320), label = "tour-hole")

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            // Swallow every touch: the tour is the only thing on screen that answers.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        val corner = with(density) { 18.dp.toPx() }
        val ring = with(density) { 2.dp.toPx() }
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = SCRIM_ALPHA))
            if (holeAlpha > 0f) {
                drawRoundRect(
                    color = Color.Black.copy(alpha = holeAlpha),
                    topLeft = hole.topLeft,
                    size = hole.size,
                    cornerRadius = CornerRadius(corner),
                    blendMode = BlendMode.DstOut,
                )
                drawRoundRect(
                    color = accent.copy(alpha = holeAlpha),
                    topLeft = hole.topLeft,
                    size = hole.size,
                    cornerRadius = CornerRadius(corner),
                    style = Stroke(ring),
                )
            }
        }

        // The card: below the target when it fits, above it when not, centred when there is no
        // target. A television rail is taller than the room either side of it, and centred there the
        // card sat on the posters; docked at the bottom it covers only the captions under them.
        val margin = with(density) { 16.dp.toPx() }
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val cardWidth = if (device.isTv) 460.dp else (maxWidth - 32.dp).coerceAtMost(440.dp)
        val cardWidthPx = with(density) { cardWidth.toPx() }
        var cardHeight by remember { mutableIntStateOf(0) }
        val target = found
        val x = if (target == null) {
            (screenW - cardWidthPx) / 2f
        } else {
            (target.center.x - cardWidthPx / 2f).coerceIn(margin, (screenW - cardWidthPx - margin).coerceAtLeast(margin))
        }
        val y = when {
            target == null -> (screenH - cardHeight) / 2f
            target.bottom + margin + cardHeight <= screenH - margin * 2 -> target.bottom + margin
            target.top - margin - cardHeight >= margin * 2 -> target.top - margin - cardHeight
            else -> screenH - cardHeight - margin * 2
        }
        val cardAlpha by animateFloatAsState(if (settled && cardHeight > 0) 1f else 0f, tween(200), label = "tour-card")

        TourCard(
            step = step,
            index = index,
            count = steps.size,
            showMissing = settled && step.target != null && found == null,
            isTv = device.isTv,
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(cardWidth)
                .graphicsLayer { alpha = cardAlpha }
                .onSizeChanged { cardHeight = it.height },
        )
    }
}

// FocusRequester.Cancel: Left off Skip and Right off the forward button would otherwise carry the
// remote's cursor out of the card into the app underneath.
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TourCard(
    step: TourStep,
    index: Int,
    count: Int,
    showMissing: Boolean,
    isTv: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = LocalMidnightAccent.current
    val nextFocus = remember { FocusRequester() }
    val skipFocus = remember { FocusRequester() }
    val isFirst = index == 0
    val isLast = index == count - 1
    // On a remote the cursor starts on the forward button of every step, and stays in the card.
    LaunchedEffect(index, isTv) {
        if (!isTv) return@LaunchedEffect
        delay(FOCUS_DELAY_MS)
        runCatching { nextFocus.requestFocus() }
    }
    Column(
        modifier
            .background(Midnight.ModalSurface, RoundedCornerShape(22.dp))
            .border(1.dp, Midnight.Line2, RoundedCornerShape(22.dp))
            .padding(20.dp)
            .onPreviewKeyEvent { event ->
                // Up and Down have nowhere to go inside the card; outside it is the app the tour
                // is covering, which the cursor must not wander into.
                event.key == Key.DirectionUp || event.key == Key.DirectionDown
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (isFirst) "WHAT'S NEW" else "WHAT'S NEW · $index OF ${count - 1}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        Text(
            step.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Midnight.Text,
        )
        Text(
            if (showMissing && step.whenMissing != null) "${step.body} ${step.whenMissing}" else step.body,
            style = MaterialTheme.typography.bodyMedium,
            color = Midnight.Text2,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isFirst) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(count - 1) { dot ->
                        Box(
                            Modifier
                                .size(if (dot == index - 1) 8.dp else 6.dp)
                                .background(
                                    if (dot == index - 1) accent else Midnight.Text3.copy(alpha = .5f),
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (!isLast) {
                val skip = rememberFocusFill()
                TextButton(
                    onClick = { WhatsNewTour.finish() },
                    colors = skip.buttonColors(ButtonDefaults.textButtonColors(contentColor = Midnight.Text2)),
                    modifier = Modifier
                        .focusRequester(skipFocus)
                        .focusProperties { left = FocusRequester.Cancel }
                        .focusFill(skip, RoundedCornerShape(50)),
                ) { Text(if (isFirst) "Not now" else "Skip") }
            }
            val next = rememberFocusFill()
            Button(
                onClick = { WhatsNewTour.next(count) },
                colors = next.buttonColors(
                    ButtonDefaults.buttonColors(containerColor = accent, contentColor = Midnight.OnAccent),
                ),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .focusRequester(nextFocus)
                    .focusProperties { right = FocusRequester.Cancel }
                    .focusFill(next, RoundedCornerShape(50)),
            ) {
                Text(
                    when {
                        isFirst -> "Show me"
                        isLast -> "Got it"
                        else -> "Next"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private const val SCRIM_ALPHA = .74f
private const val TOUR_START_DELAY_MS = 1_200L
private const val TARGET_WAIT_MS = 1_200L
private const val SCROLL_SETTLE_MS = 250L
private const val FOCUS_DELAY_MS = 120L
