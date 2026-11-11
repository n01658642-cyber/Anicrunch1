package com.anilili.ui.components

import android.os.Build
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anilili.ui.theme.LocalMidnightAccent
import com.anilili.ui.theme.Midnight

/** One destination; the label remains available to accessibility services. */
@Immutable
data class FloatingNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/** The capsule's one height; scrolling no longer changes it. */
val FloatingNavBarExpandedHeight = 56.dp
val FloatingNavBarMargin = 12.dp

/** An idle icon's slot: the 48dp touch minimum, so the rest is free for the open pill's label. */
private val ItemSize = 48.dp
private val IconSize = 22.dp
private val BarPadding = 6.dp
private val BarShape = RoundedCornerShape(20.dp)

// A gentle top light keeps the glass character without a white sheen across the icons.
// The nearly opaque center preserves contrast when bright artwork scrolls behind the bar.
// Built from the theme, so they are rebuilt only when the theme changes.
@Composable
private fun barGlass(): Brush {
    val top = Midnight.Line2
    val middle = Midnight.Panel2
    val bottom = Midnight.Bg2
    return remember(top, middle, bottom) {
        Brush.verticalGradient(
            0f to top.copy(alpha = 0.88f),
            0.4f to middle.copy(alpha = 0.96f),
            1f to bottom.copy(alpha = 0.98f),
        )
    }
}

/**
 * Live glass: what scrolls behind the bar, blurred and darkened enough that the idle icons keep
 * their contrast over a white poster. The tint is the painted glass's own middle colour.
 */
@Composable
private fun barBlur(): HazeStyle {
    val background = Midnight.Bg
    val tint = Midnight.Panel2
    return remember(background, tint) {
        HazeStyle(
            backgroundColor = background,
            tint = HazeTint(tint.copy(alpha = 0.62f)),
            blurRadius = 22.dp,
            noiseFactor = 0.04f,
        )
    }
}

@Composable
private fun barRim(): Brush {
    val top = Midnight.Text2
    val middle = Midnight.Line2
    val bottom = Midnight.Line
    return remember(top, middle, bottom) {
        Brush.verticalGradient(
            0f to top.copy(alpha = 0.18f),
            0.5f to middle.copy(alpha = 0.45f),
            1f to bottom.copy(alpha = 0.35f),
        )
    }
}

/**
 * A quiet, softly shaded glass surface. The current destination opens into a filled pill carrying
 * its name, so the page is named without a second row of text under every icon; the pill springs
 * open and its neighbours slide aside, which is what makes a tab change read as movement.
 *
 * One fixed height. The bar used to trim to a compact height on a downward scroll and grow back
 * on an upward one, a leftover of the Nuvio-style labelled bar; on this pill design the 4 dp step
 * read only as the bar twitching under the thumb.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun FloatingNavigationBar(
    items: List<FloatingNavItem>,
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    /** The content behind the bar, blurred through it on Android 12+; null keeps painted glass. */
    backdrop: HazeState? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = bottomInset + FloatingNavBarMargin),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // RenderEffect arrived in Android 12; below it Haze only lays a flat scrim, which is worse
        // than the painted glass, so older phones keep that.
        val blurred = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val inner = maxWidth - BarPadding * 2
        // Idle icons keep a thumb-sized target; whatever they leave is the ceiling on the pill, so
        // a long label ("Calendario") ellipsizes on a narrow phone instead of pushing icons off.
        val itemWidth = if (items.isEmpty()) ItemSize
        else minOf(ItemSize, inner / (items.size + 1))
        val pillMax = inner - itemWidth * (items.size - 1).coerceAtLeast(0)
        Row(
            // Full width, so the bar's edges stay put while the pill changes length per label;
            // the spare room is shared between the icons instead.
            modifier = Modifier
                .fillMaxWidth()
                .height(FloatingNavBarExpandedHeight)
                .shadow(6.dp, BarShape)
                .clip(BarShape)
                .then(
                    if (blurred) {
                        // A third of the resolution: the blur discards that detail anyway. On an
                        // S25 it cut the GPU 90th percentile while scrolling from 10ms to 6ms
                        // (painted glass: 4ms) with no visible difference.
                        Modifier.hazeEffect(backdrop!!, barBlur()) { inputScale = HazeInputScale.Fixed(0.33f) }
                    } else {
                        Modifier.background(barGlass())
                    },
                )
                .border(1.dp, barRim(), BarShape)
                .padding(horizontal = BarPadding)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                FloatingNavIcon(
                    item = item,
                    selected = item.route == selectedRoute,
                    width = itemWidth,
                    maxWidth = pillMax,
                    onClick = { onSelect(item.route) },
                )
            }
        }
    }
}

/**
 * A round action floating beside the capsule, in the capsule's own glass, rim and shadow, so it
 * reads as part of the same chrome rather than a Material FAB dropped on top of it. Blurred exactly
 * when the capsule is: with Navigation glass off, or below Android 12, it is the painted glass too.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun FloatingNavDisc(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: HazeState? = null,
    icon: @Composable () -> Unit,
) {
    val blurred = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(
        modifier = modifier
            .size(FloatingNavDiscSize)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .then(
                if (blurred) {
                    Modifier.hazeEffect(backdrop!!, barBlur()) { inputScale = HazeInputScale.Fixed(0.33f) }
                } else {
                    Modifier.background(barGlass())
                },
            )
            .border(1.dp, barRim(), CircleShape)
            .clickable(onClickLabel = contentDescription, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

/** A touch under the capsule's 56dp, so the disc reads as its companion rather than its rival. */
val FloatingNavDiscSize = 52.dp

/**
 * One tab. Everything about the pill — its width, its fill, the glyph giving way to the name
 * — hangs off a single [progress] value read only in the layout and draw phases. Composition
 * never reruns during the animation, and nothing snaps: the earlier version let two size
 * animations fight over one box and toggled its padding in a single frame, which read as a stutter.
 */
@Composable
private fun FloatingNavIcon(
    item: FloatingNavItem,
    selected: Boolean,
    width: Dp,
    maxWidth: Dp,
    onClick: () -> Unit,
) {
    val accent = LocalMidnightAccent.current
    val idleTint = Midnight.Text2
    val onAccent = Midnight.OnAccent
    val progress = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) { progress.animateTo(if (selected) 1f else 0f, PillSpring) }
    val p = { progress.value.coerceIn(0f, 1f) }
    val density = LocalDensity.current
    val padPx = with(density) { PillPadding.roundToPx() }
    Layout(
        modifier = Modifier
            .fillMaxHeight()
            // The whole bar height stays the touch target; only the drawn pill is inset. No ripple:
            // the pill opening under the finger is the press feedback.
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = null,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = PillInset)
            .clip(PillShape)
            .drawBehind { drawRect(accent.copy(alpha = accent.alpha * p())) },
        content = {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = idleTint,
                modifier = Modifier.size(IconSize).graphicsLayer { alpha = 1f - p() },
            )
            Text(
                item.label,
                color = onAccent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                // The icon already names the tab to accessibility; reading it twice is noise.
                modifier = Modifier
                    .widthIn(max = maxWidth - PillPadding * 2)
                    // The name trails the pill open, so it never shows before there is room for it.
                    .graphicsLayer { alpha = ((p() - 0.35f) / 0.65f).coerceIn(0f, 1f) }
                    .clearAndSetSemantics {},
            )
        },
    ) { measurables, constraints ->
        val idle = width.roundToPx()
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val icon = measurables[0].measure(loose)
        val label = measurables[1].measure(loose)
        val open = (label.width + padPx * 2).coerceIn(idle, maxWidth.roundToPx())
        val w = (idle + (open - idle) * p()).roundToInt().coerceAtLeast(idle)
        val h = constraints.maxHeight
        // The open pill is the name alone; the glyph fades out where it stood, centred on the
        // pill, so the eye follows one shape growing rather than two things swapping places.
        layout(w, h) {
            icon.place((w - icon.width) / 2, (h - icon.height) / 2)
            label.place((w - label.width) / 2, (h - label.height) / 2)
        }
    }
}

/** Critically damped enough not to overshoot the bar's width, soft enough to read as motion. */
private val PillSpring = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
private val PillShape = RoundedCornerShape(50)
private val PillPadding = 14.dp

/** 35dp of pill in the 56dp bar. */
private val PillInset = 10.5.dp
