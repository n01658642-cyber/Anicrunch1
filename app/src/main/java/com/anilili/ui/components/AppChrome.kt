package com.anilili.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import com.anilili.ui.adaptive.LocalAppDeviceProfile

/** Shared visibility signal for screen chrome while the user is actively browsing a list. */
val LocalAppChromeVisible = compositionLocalOf { true }

/**
 * Hands the chrome back, for a screen whose own view changed without the route changing.
 *
 * The root restores the bars on every route change, because a scroll-hidden bar must not be
 * inherited by the next page. Settings opens its sections in place, so leaving the bottom of Player
 * for the category grid kept the scroll-hidden title — and the grid is too short to scroll, so no
 * upward drag could ask for it back. Such a screen calls this whenever its in-place view changes.
 */
val LocalAppChromeReveal = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Room the overlaying chrome takes at the bottom of the window. The navigation bar draws over the
 * content rather than displacing it, so scrollable screens have to reserve this themselves or
 * their last rows sit underneath it forever.
 */
val LocalAppChromeBottomInset = compositionLocalOf { 0.dp }

/**
 * Screens that take the whole window over from inside a tab, while one of them is up.
 *
 * The sign-in pages are drawn by the tab that opened them (Settings, My List), so the route still
 * says "tab" and the phone's floating capsule stayed on top of them. That capsule frosts what is
 * behind it by recording the screen a second time, and a WebView recorded twice does not survive
 * it: MyAnimeList's and AniList's login pages painted, went blank, and painted again for as long as
 * they were open. Turning the Navigation bar glass off stopped it on the emulator, which is how the
 * capsule was pinned as the cause. A takeover has no use for the tab bar anyway — it covered the
 * bottom of the login form — so while one is shown the root drops the capsule and its blur.
 */
class AppChromeTakeover {
    private var shown by mutableIntStateOf(0)
    val active: Boolean get() = shown > 0

    internal fun acquire() { shown++ }
    internal fun release() { shown-- }
}

val LocalAppChromeTakeover = staticCompositionLocalOf { AppChromeTakeover() }

/** Hides the root navigation chrome for as long as the caller stays composed. */
@Composable
fun AppChromeTakeoverEffect() {
    val takeover = LocalAppChromeTakeover.current
    DisposableEffect(takeover) {
        takeover.acquire()
        onDispose { takeover.release() }
    }
}

/**
 * Breathing room a scrollable list leaves between its last row and [LocalAppChromeBottomInset].
 *
 * The inset alone only buys the capsule's own height, which lands the final poster and its title
 * flush against the glass: every pixel is there and it still reads as clipped, because the eye takes
 * a row touching the bar for a row continuing behind it. Each screen used to add its own 24 or 28dp
 * on top and they were already drifting apart, so the gap lives here and is the same everywhere it
 * is spent.
 */
val AppChromeListBottomGap = 48.dp

/**
 * Height of the TV top navigation, which draws over the content rather than displacing it.
 *
 * Both the bar and every screen that has to clear it take it from here, so the two cannot drift.
 */
val TvTopNavigationHeight = 82.dp

/**
 * Lays a TV destination out below the top navigation from its very first frame.
 *
 * The inset belongs to the destination, not to the NavHost around it. It used to be a
 * `padding(top = ...)` on the host, switched on the current route — and the route arrives late:
 * `currentBackStackEntryAsState` collects a flow, so the incoming screen composes once with the
 * *outgoing* route's inset before the state catches up. Home reserves nothing (it is full-bleed),
 * so every titled screen entered from it drew its heading over the wordmark for a frame and then
 * jumped clear of it. Reading the device profile instead of the route decides the same thing with
 * nothing to wait for.
 *
 * No-op off TV, where there is no top navigation to clear.
 */
@Composable
fun TvTopNavigationInset(content: @Composable () -> Unit) {
    if (!LocalAppDeviceProfile.current.isTv) {
        content()
        return
    }
    Box(Modifier.fillMaxSize().padding(top = TvTopNavigationHeight)) { content() }
}

/**
 * Keeps each screen's own top bar in sync with the root navigation bar.
 *
 * The bar keeps its full measured height whatever it is doing and slides out via a layer, so the
 * scaffold padding handed to the screen never changes and nothing below it is re-laid out. Earlier
 * versions animated the measured height to zero, which was smooth but still moved every item on
 * the screen by the bar height each time the chrome came and went.
 *
 * Screens must apply that padding as *scroll* padding (`contentPadding`) rather than layout
 * padding, so the list occupies the whole window and its rows pass under the bar as it retreats.
 * Applying it with `Modifier.padding` instead leaves a dead band where the bar used to be.
 *
 * [visible] defaults to the root chrome's own signal; a screen whose bar comes and goes on its own
 * terms — Browse watches its result grid rather than the shared scroll connection — passes its own.
 */
@Composable
fun ScrollAwareTopBar(
    visible: Boolean = LocalAppChromeVisible.current,
    content: @Composable () -> Unit,
) {
    val shift by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(220),
        label = "topBarShift",
    )
    Layout(content = content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val width = placeables.maxOfOrNull { it.width } ?: 0
        layout(width, height) {
            placeables.forEach { placeable ->
                placeable.placeWithLayer(0, 0) {
                    translationY = -height * shift
                }
            }
        }
    }
}
