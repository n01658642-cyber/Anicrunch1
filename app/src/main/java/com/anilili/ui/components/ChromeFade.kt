package com.anilili.ui.components

import com.anilili.ui.theme.appCanvasFade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * The fade the phone's top chrome is drawn in where hero artwork lies beneath it.
 *
 * The television header has always been a gradient laid over the hero: the artwork runs unbroken to
 * the top edge and the type stays legible because the fade is darkest exactly where it sits. An
 * opaque bar instead spends the top of the window before a single frame of the artwork appears, and
 * makes the hero read as a picture pasted into the page rather than as the page itself.
 *
 * The television bar runs two stops, 74% to nothing, because it has one row to cover. This one
 * carries up to two, so it holds the middle rather than falling away linearly — controls at the
 * bottom of a straight gradient sat on almost bare artwork and disappeared against a bright frame.
 * Black rather than the theme background because the app has no light scheme, and everything drawn
 * in here is white or purple either way.
 */
val AppChromeFade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = .86f),
    0.62f to Color.Black.copy(alpha = .48f),
    1f to Color.Transparent,
)

/**
 * Draws [AppChromeFade] behind top chrome, backed by the page's own colour when [overHero] is false —
 * or, given a [backdrop] that can blur, by that page frosted (see the note in the body).
 *
 * [overHero] is what keeps the fade honest, and it is the part worth recording. Scrolled off the
 * artwork the bar sits over ordinary content, and the first version of this had Home's wordmark
 * landing on One Piece's sky with a score badge showing through it — translucent chrome over cover
 * art is illegible, not clean. So the bare gradient holds only while artwork is what lies beneath,
 * and off it a backing in the page's own colour crossfades up. The backing still lets its last
 * stretch go to nothing, so rows pass under a soft edge rather than being cut off by a line.
 *
 * Crossfaded rather than switched: the bar and the list it reacts to move at the same time, and a
 * hard swap between the two treatments reads as a flash behind whatever the bar is carrying.
 *
 * Shared so the two screens that use it cannot drift into two different gradients.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.chromeFade(overHero: Boolean, backdrop: HazeState? = null): Modifier {
    val backingColor = MaterialTheme.colorScheme.background
    val backing = remember(backingColor) {
        Brush.verticalGradient(
            0f to backingColor,
            0.86f to backingColor,
            1f to backingColor.copy(alpha = 0f),
        )
    }
    val settled by animateFloatAsState(
        targetValue = if (overHero) 0f else 1f,
        animationSpec = tween(200),
        label = "chromeBacking",
    )
    // Frosted, the backing is the page itself blurred under a lighter wash of the page colour,
    // faded in by the same [settled] and cut to the same soft foot by a mask, so the only change is
    // what the backing is made of. The black fade then gives way partly, or the glass under the
    // wordmark would be smoked back to the flat bar it replaces.
    val frosted = backdrop != null && backdropBlurAvailable
    val glass = if (frosted) {
        Modifier.hazeEffect(
            backdrop!!,
            HazeStyle(
                backgroundColor = backingColor,
                tint = HazeTint(backingColor.copy(alpha = .55f)),
                blurRadius = 24.dp,
                noiseFactor = 0f,
            ),
        ) {
            alpha = settled
            mask = ChromeGlassMask
            inputScale = HazeInputScale.Fixed(0.33f)
            canDrawArea = { true }
        }
    } else {
        Modifier
    }
    return this.then(glass).drawBehind {
        if (frosted) {
            drawRect(AppChromeFade, alpha = 1f - .6f * settled)
        } else {
            drawRect(AppChromeFade)
        }
    }.then(
        // Drawn above the fade, as the flat backing always was; with a background picture it is the
        // picture's slice rather than a band of the canvas colour.
        if (frosted) Modifier else Modifier.appCanvasFade(alpha = { settled }) { canvas ->
            Brush.verticalGradient(
                0f to canvas,
                0.86f to canvas,
                1f to canvas.copy(alpha = 0f),
            )
        },
    )
}

private val ChromeGlassMask = Brush.verticalGradient(
    0f to Color.Black,
    0.86f to Color.Black,
    1f to Color.Transparent,
)
