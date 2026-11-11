package com.anilili.ui.dialogs

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.FocusRingGap
import com.anilili.ui.adaptive.focusHalo
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.theme.LocalAppColors
import com.anilili.ui.theme.LocalMidnightAccent
import com.anilili.ui.theme.Midnight
import kotlinx.coroutines.android.awaitFrame

/**
 * The Midnight focus treatment: a thin outline and a faint wash — never a scale, never a halo.
 *
 * Spec §28.4: TV focus must not scale. Selection is expressed separately (rail / underline) by the
 * components below; this modifier is only the cursor, so a selected row and a focused row read as
 * two different facts even when they are the same row.
 *
 * Apply it *before* the modifier that makes the element focusable — `onFocusChanged` only observes
 * focus modifiers that follow it in the chain, and getting that order wrong leaves the outline
 * permanently unpainted while Material's grey ripple quietly stands in for it.
 */
fun Modifier.midnightFocus(
    shape: Shape = RectangleShape,
    wash: Boolean = true,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val outline = LocalMidnightAccent.current.copy(alpha = 0.55f)
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .then(
            if (focused) {
                Modifier
                    .border(1.dp, outline, shape)
                    .then(
                        if (wash) Modifier.background(LocalMidnightAccent.current.copy(alpha = 0.08f), shape) else Modifier,
                    )
            } else {
                Modifier
            },
        )
}

/**
 * Where the cursor starts when a dialog opens on a television (§31).
 *
 * Applied to the control holding the current value, so the first OK press confirms what is already
 * on screen rather than silently choosing something else. Touch devices are left alone: a phone
 * has no cursor to place, and stealing focus there only raises keyboards.
 */
fun Modifier.midnightInitialFocus(enabled: Boolean = true): Modifier = composed {
    val device = LocalAppDeviceProfile.current
    val requester = remember { FocusRequester() }
    val claim = device.isTv && enabled
    if (claim) {
        LaunchedEffect(Unit) {
            // One frame so the dialog window has taken focus before we place the cursor in it.
            awaitFrame()
            runCatching { requester.requestFocus() }
        }
    }
    if (claim) focusRequester(requester) else this
}

/** Section micro-label: uppercase, tracked, dim (§6.2 kickers are structure, not decoration). */
@Composable
fun MidnightSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
        fontWeight = FontWeight.W800,
        color = Midnight.Text3,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** One-line muted helper under a control (§46: one line on the surface). */
@Composable
fun MidnightHelper(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Midnight.Text3,
        modifier = modifier.padding(top = 7.dp),
    )
}

/**
 * Segmented selector: short, comparable, mutually exclusive options (§12).
 *
 * A rounded track with a rounded pill inside it — the shape a segmented control has in every
 * modern interface, and the shape that says "these are the options, this is the one". It replaced
 * a full-width tab row with a 2dp underline, which on a white-filled focus state left a hard
 * rectangle with a stub of a rule under it and read as a rendering fault rather than as a choice.
 *
 * The pill is white under the cursor and a fifth of white when merely chosen, the same three
 * states every other badge in the app uses.
 */
@Composable
fun <T> MidnightUnderlineSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    tabLabel: (T) -> String,
    modifier: Modifier = Modifier,
    claimInitialFocus: Boolean = true,
) {
    val device = LocalAppDeviceProfile.current
    val trackShape = RoundedCornerShape(MidnightRowRadius + 2.dp)
    val pillShape = RoundedCornerShape(MidnightRowRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(MidnightRestFill, trackShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            var focused by remember { mutableStateOf(false) }
            val fill = when {
                focused -> MidnightFocusFill
                isSelected -> MidnightSelectedFill
                else -> Color.Transparent
            }
            val ink = when {
                focused -> MidnightFocusCopy
                isSelected -> Midnight.Text
                else -> Midnight.Text3
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (device.isTv) 44.dp else 38.dp)
                    .midnightInitialFocus(claimInitialFocus && isSelected)
                    .onFocusChanged { focused = it.isFocused || it.hasFocus }
                    // A television's cursor; ahead of the clip, which would shave the ring off.
                    .focusHalo(focused && device.isTv, pillShape, FocusRingGap)
                    .clip(pillShape)
                    .background(fill, pillShape)
                    .midnightSelectable(
                        selected = isSelected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = { onSelect(option) },
                    ),
            ) {
                Text(
                    text = tabLabel(option),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected || focused) FontWeight.W700 else FontWeight.W600,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/**
 * Selection with the Midnight cursor instead of Material's.
 *
 * A television gets no ripple: the grey focus overlay Material paints is a second, off-palette
 * focus state fighting the purple one (§28.2). Touch keeps its ripple, which is press feedback
 * rather than a cursor.
 */
private fun Modifier.midnightSelectable(
    selected: Boolean,
    enabled: Boolean,
    role: Role,
    onClick: () -> Unit,
): Modifier = composed {
    val device = LocalAppDeviceProfile.current
    val interactionSource = remember { MutableInteractionSource() }
    selectable(
        selected = selected,
        interactionSource = interactionSource,
        indication = if (device.isTv) null else LocalIndication.current,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}

/**
 * Flat choice row: title, optional subtitle, optional trailing value, radio on the right (§13).
 *
 * Selected is three signals — bright text, faint purple wash and the 2dp rail — so it survives
 * colour-blind viewing and three metres of couch distance. Focus adds only the thin cursor, drawn
 * on top so selected+focused stays legible (§28.3).
 */
@Composable
fun MidnightChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    claimInitialFocus: Boolean = true,
    /** A mark before the title — the colour swatch in the accent picker. */
    leading: (@Composable () -> Unit)? = null,
) {
    MidnightRowScaffold(
        title = title,
        subtitle = subtitle,
        selected = selected,
        enabled = enabled,
        onSelect = onSelect,
        role = Role.RadioButton,
        claimInitialFocus = claimInitialFocus && selected,
        modifier = modifier,
        leading = leading,
        trailing = {
            trailing?.let { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall,
                    color = Midnight.Text3,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        },
        indicator = {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
                else -> {
                    // Reads the row's own content colour, so the radio inverts with the badge it
                    // sits in: white on a dark row, near-black on the white one under the cursor.
                    val ink = LocalContentColor.current
                    val ring = if (selected) ink else ink.copy(alpha = 0.42f)
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .border(1.dp, ring, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(ink),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** A multi-select row: unlike [MidnightChoiceRow], several siblings may be checked at once. */
@Composable
fun MidnightSelectionRow(
    title: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    claimInitialFocus: Boolean = false,
) {
    MidnightRowScaffold(
        title = title,
        subtitle = subtitle,
        selected = selected,
        enabled = enabled,
        onSelect = { onSelectedChange(!selected) },
        role = Role.Checkbox,
        claimInitialFocus = claimInitialFocus,
        modifier = modifier,
        trailing = {
            trailing?.let { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall,
                    color = Midnight.Text3,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        },
        indicator = {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) LocalMidnightAccent.current else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (selected) LocalMidnightAccent.current else Midnight.RadioRing,
                        shape = RoundedCornerShape(3.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Midnight.OnAccent,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        },
    )
}

/**
 * Shared status row for list states (§14): neutral rows, one violet rail on the current value,
 * a check instead of a radio, and no rainbow palette. Dropped is the only tonal exception.
 */
@Composable
fun MidnightStatusRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    destructive: Boolean = false,
    // The cursor opens on the row holding the current value (§31). A menu whose title has no value
    // yet has no such row, and nothing claiming the cursor leaves a remote unable to move inside the
    // dialog at all, so those callers name the row that should take it.
    claimInitialFocus: Boolean = selected,
    icon: ImageVector? = null,
) {
    MidnightRowScaffold(
        title = label,
        subtitle = subtitle,
        selected = selected,
        enabled = enabled,
        onSelect = onSelect,
        role = Role.RadioButton,
        claimInitialFocus = claimInitialFocus,
        modifier = modifier,
        titleColor = if (destructive && !selected) Color(0xFFA87D84) else Color.Unspecified,
        leading = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    // The glyph is a landmark for finding a row again, not a second selection
                    // signal — the rail and the check own that — so it only brightens with the
                    // row's own copy rather than taking the accent.
                    tint = when {
                        destructive && !selected -> Color(0xFFA87D84)
                        selected -> LocalMidnightAccent.current
                        enabled -> Midnight.Text2
                        else -> Midnight.Text3
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        indicator = {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalMidnightAccent.current,
                )
                selected -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Current status",
                    tint = LocalMidnightAccent.current,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    )
}

/**
 * The common anatomy of choice and status rows: a rounded badge, copy, trailing, indicator.
 *
 * Three states, told by the fill:
 *
 *  - **focused** — solid white with black copy. The row the remote is pointing at inverts, which
 *    is the loudest thing a monochrome panel can say and needs no contrast against whatever is
 *    behind the panel.
 *  - **selected** — a fifth of white, copy still white. Chosen, but not where the cursor is.
 *  - **neither** — the faintest wash, so a list reads as a stack of cards rather than as text on
 *    a hairline grid.
 *
 * Corners rather than full-bleed rows with a rule between them: a badge is a target, and on a
 * television a target has to look like one from the far side of a room.
 */
@Composable
private fun MidnightRowScaffold(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    role: Role,
    claimInitialFocus: Boolean,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    indicator: @Composable () -> Unit = {},
) {
    val device = LocalAppDeviceProfile.current
    val minHeight = if (device.isTv) 52.dp else 46.dp
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(MidnightRowRadius)
    val fill = when {
        focused -> MidnightFocusFill
        selected -> MidnightSelectedFill
        else -> MidnightRestFill
    }
    // Everything inside a filled row has to invert with it, including whatever colour a caller
    // asked for: a destructive red on white still reads as destructive, a muted grey does not.
    // A television's row does not fill, so it keeps the caller's colour under the ring.
    val copy = when {
        focused && !device.isTv -> MidnightFocusCopy
        titleColor != Color.Unspecified -> titleColor
        focused -> MidnightFocusCopy
        selected -> Midnight.Text
        enabled -> Midnight.Text2
        else -> Midnight.Text3
    }
    val support = if (focused) MidnightFocusCopy.copy(alpha = 0.66f) else Midnight.Text3
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            // A television's cursor; ahead of the clip, which would shave the ring off.
            .focusHalo(focused && device.isTv, shape, FocusRingGap)
            .clip(shape)
            .background(fill, shape)
            .midnightInitialFocus(claimInitialFocus)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .midnightSelectable(
                selected = selected,
                enabled = enabled,
                role = role,
                onClick = onSelect,
            ),
    ) {
        CompositionLocalProvider(LocalContentColor provides copy) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected || focused) FontWeight.W700 else FontWeight.W600,
                        color = copy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = support,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                trailing()
                indicator()
            }
        }
    }
}

/** The corner every row, badge and button in the Midnight parts is cut to. */
val MidnightRowRadius = 12.dp

/**
 * Under the cursor: solid accent (white when monochrome), with the accent's own copy colour.
 *
 * Not on a television, where the cursor is the white ring the rest of the app uses
 * ([com.anilili.ui.adaptive.focusHalo], drawn by each row): there a focused row only brightens a
 * little and keeps its own copy colour. The solid fill came with the app's glow, which viewers
 * found too heavy, and a row inverting while every other control rings read as two conventions.
 */
val MidnightFocusFill: Color
    @Composable @ReadOnlyComposable get() = if (LocalAppDeviceProfile.current.isTv) {
        LocalAppColors.current.scheme.onSurface.copy(alpha = 0.10f)
    } else {
        LocalAppColors.current.focusFill
    }
val MidnightFocusCopy: Color
    @Composable @ReadOnlyComposable get() = if (LocalAppDeviceProfile.current.isTv) {
        LocalAppColors.current.scheme.onSurface
    } else {
        LocalAppColors.current.focusContent
    }

/** Chosen, but the cursor is elsewhere: a sixth of the accent. */
val MidnightSelectedFill: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.focusFill.copy(alpha = 0.16f)

/** Neither: just enough of the text colour to read as a card. */
val MidnightRestFill: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.scheme.onSurface.copy(alpha = 0.045f)

/**
 * A value that is nudged rather than chosen from a list (§30): two targets a remote can land on,
 * with the number between them.
 */
@Composable
fun MidnightStepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    decreaseLabel: String = "−",
    increaseLabel: String = "+",
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
) {
    val device = LocalAppDeviceProfile.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (device.isTv) 52.dp else 46.dp)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.W600,
            color = Midnight.Text2,
            modifier = Modifier.weight(1f),
        )
        MidnightQuietAction(
            text = decreaseLabel,
            onClick = onDecrease,
            enabled = decreaseEnabled,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.W800,
            color = Midnight.Text,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        MidnightQuietAction(
            text = increaseLabel,
            onClick = onIncrease,
            enabled = increaseEnabled,
        )
    }
}
