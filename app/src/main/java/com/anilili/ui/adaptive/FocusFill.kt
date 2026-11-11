package com.anilili.ui.adaptive

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.anilili.ui.theme.LocalAppColors

/**
 * The treatment for a control that says "the remote is pointing at me" by **filling solid** with the
 * theme's accent — white in the default monochrome theme — and switching its copy to `onPrimary`.
 *
 * **Not on a television any more.** There the control keeps its resting colours and [focusFill]
 * draws [focusHighlight]'s ring round it instead: the solid fill came with a glow viewers found too
 * heavy, and the ring is what Home's posters already wear, so controls and pictures now read the
 * same. [focused] still reports focus there, for callers that bold a label or similar; the colour
 * helpers below simply stop swapping. The rest of this note is the phone's treatment.
 *
 * A rim around a button is a hairline at a metre's viewing distance, and it has to compete with the
 * control's own border and corner radius; against artwork it can read as a drawing mistake rather
 * than as emphasis. Inverting the whole control cannot be missed and needs no contrast against
 * whatever is behind it — white is the brightest thing the panel can produce and black type on it
 * is the most legible pair there is.
 *
 * It is deliberately *not* how a focused **card** is drawn. A poster or an episode still is a
 * picture, and filling it white would erase the thing being pointed at, so artwork keeps the rim
 * and the lift from [focusHighlight]. The split is "controls fill, pictures get a rim", and it is
 * why both treatments exist side by side.
 *
 * Usage is three parts, because a modifier cannot reach the colours of the content inside it:
 *
 * ```
 * val fill = rememberFocusFill()
 * Button(
 *     colors = fill.buttonColors(),
 *     modifier = Modifier.focusFill(fill, shape),
 * ) { … }
 * ```
 */
@Stable
class FocusFillState internal constructor() {
    var focused by mutableStateOf(false)
        internal set

    // Refreshed by [rememberFocusFill] on every composition. Plain fields, not state: the theme is a
    // static composition local, so a theme change recomposes every reader of these anyway.
    internal var fillColor: Color = Color.White
    internal var fillContentColor: Color = Color.Black

    /** True on a television, where focus is the ring and the colours never swap. */
    internal var ring: Boolean = false

    /** Whether the fill is showing: focused, and not on a television. */
    private val filled: Boolean get() = focused && !ring

    /** The accent under the cursor, [rest] otherwise — for controls that draw their own background. */
    fun background(rest: Color = FocusFillRest): Color = if (filled) fillColor else rest

    /** The accent's copy colour under the cursor, [rest] otherwise — for text and icons inside one. */
    fun content(rest: Color = Color.White): Color = if (filled) fillContentColor else rest

    // Copy only enabled colors so each control keeps its own disabled and resting treatment.
    fun buttonColors(rest: ButtonColors): ButtonColors = rest.copy(
        containerColor = background(rest.containerColor),
        contentColor = content(rest.contentColor),
    )

    fun iconButtonColors(rest: IconButtonColors): IconButtonColors = rest.copy(
        containerColor = background(rest.containerColor),
        contentColor = content(rest.contentColor),
    )

    fun chipColors(rest: ChipColors): ChipColors = rest.copy(
        containerColor = background(rest.containerColor),
        labelColor = content(rest.labelColor),
        leadingIconContentColor = content(rest.leadingIconContentColor),
        trailingIconContentColor = content(rest.trailingIconContentColor),
    )

    fun chipColors(rest: SelectableChipColors): SelectableChipColors = if (!filled) rest else rest.copy(
        containerColor = fillColor,
        labelColor = fillContentColor,
        leadingIconColor = fillContentColor,
        trailingIconColor = fillContentColor,
        selectedContainerColor = fillColor,
        selectedLabelColor = fillContentColor,
        selectedLeadingIconColor = fillContentColor,
        selectedTrailingIconColor = fillContentColor,
    )

    @Composable
    fun buttonColors(
        container: Color = FocusFillRest,
        content: Color = Color.White,
    ): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = background(container),
        contentColor = content(content),
        disabledContainerColor = Color.White.copy(alpha = 0.07f),
        disabledContentColor = Color.White.copy(alpha = 0.38f),
    )

    @Composable
    fun iconButtonColors(
        container: Color = Color.Transparent,
        content: Color = Color.White,
    ): IconButtonColors = IconButtonDefaults.iconButtonColors(
        containerColor = background(container),
        contentColor = content(content),
        disabledContentColor = Color.White.copy(alpha = 0.38f),
    )
}

/** The fill a control carries under the cursor, and the colour its content has to become. */
val FocusFillColor: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.focusFill
val FocusFillContentColor: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.focusContent

/**
 * What a focusable control looks like at rest: translucent white, so it tints towards whatever is
 * behind it instead of sitting on top of it as a grey card would.
 */
val FocusFillRest = Color.White.copy(alpha = 0.15f)

@Composable
fun rememberFocusFill(): FocusFillState {
    val colors = LocalAppColors.current
    val ring = LocalAppDeviceProfile.current.isTv
    return remember { FocusFillState() }.apply {
        fillColor = colors.focusFill
        fillContentColor = colors.focusContent
        this.ring = ring
    }
}

/**
 * Reports focus into [state] and applies the lift, without the rim — the fill is the whole cue.
 * On a television [focusHighlight] draws its ring instead, and the colours do not swap.
 *
 * `hasFocus` as well as `isFocused`, so a control that holds focusable children (a chip wrapping a
 * text field, a row that is itself a button) lights up when the cursor is anywhere inside it.
 */
@Composable
fun Modifier.focusFill(
    state: FocusFillState,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = if (LocalAppDeviceProfile.current.isTv) 1.06f else 1.025f,
): Modifier = this
    .onFocusChanged { state.focused = it.isFocused || it.hasFocus }
    .focusHighlight(shape, focusedScale, rim = false)
