package com.anilili.ui.dialogs

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.FocusRingGap
import com.anilili.ui.adaptive.focusHalo
import com.anilili.ui.theme.LocalMidnightAccent
import com.anilili.ui.theme.Midnight
import kotlinx.coroutines.flow.first

/**
 * A settings category (§22.1): a 2dp purple marker and a tracked micro-label. The marker is the
 * category's identity — the group does not get a card, a tile or a background of its own.
 *
 * Pass [expanded] and [onToggle] to make the header its section's fold control. Folding earns the
 * one thing the header could not have before — a caret and a target — but the header stays a
 * marker and a micro-label, so a page of collapsed sections still reads as the same editorial
 * list rather than a stack of group cards.
 *
 * [icon] rides between the marker and the label at the label's own size and colour, so a folded
 * page can be surveyed by shape rather than by reading eleven uppercase words. It is a glyph, not
 * the icon tile §22.2 keeps out of the rows — no background, no box, no accent of its own.
 */
@Composable
fun MidnightSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    val device = LocalAppDeviceProfile.current
    val marker = LocalMidnightAccent.current
    val fold = if (expanded != null) onToggle else null
    var focused by remember { mutableStateOf(false) }
    val caretTurn by animateFloatAsState(
        targetValue = if (expanded == true) 90f else 0f,
        label = "midnight-section-caret",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .then(
                if (fold != null) {
                    Modifier
                        .clickable(role = Role.Button, onClick = fold)
                        .semantics {
                            stateDescription = if (expanded == true) "Expanded" else "Collapsed"
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (fold != null) {
                        Modifier.heightIn(min = if (device.isTv) 48.dp else 44.dp)
                    } else {
                        Modifier
                    },
                )
                .padding(
                    top = if (fold != null) 18.dp else 26.dp,
                    bottom = if (fold != null) 10.dp else 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 2.dp, height = 11.dp)
                    .background(marker),
            )
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    // The label beside it already names the category, so announcing the glyph too
                    // would have a screen reader read every header twice.
                    contentDescription = null,
                    tint = if (expanded == true) LocalMidnightAccent.current else Midnight.Text3,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(14.dp),
                )
            }
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
                fontWeight = FontWeight.W800,
                color = Midnight.Text3,
                modifier = Modifier
                    .padding(start = if (icon != null) 6.dp else 8.dp)
                    .then(if (fold != null) Modifier.weight(1f) else Modifier),
            )
            if (fold != null) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = if (expanded == true) LocalMidnightAccent.current else Midnight.Text3,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(caretTurn),
                )
            }
        }
        if (focused) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(LocalMidnightAccent.current.copy(alpha = 0.06f))
                    .border(1.dp, LocalMidnightAccent.current.copy(alpha = 0.55f)),
            )
        }
    }
}

/**
 * The row a settings search sent the viewer to, and the way to put the mark out again.
 *
 * Searching opens the *section* holding the answer, which lands on a page of rows that all look
 * alike: what you asked for is in front of you, unmarked, and you read every title over again to
 * find it. The mark closes that last gap.
 *
 * [onReached] ends it. This marks a row rather than describing a state of the setting, so it
 * survives scrolling — the row may well have opened below the fold — and goes out the moment the
 * viewer touches or focuses it, which is the moment it has done its work.
 *
 * [bottomReserve] is what the marked row asks to be scrolled clear of. Scrolling something into
 * view means the window's bottom edge, and the app's chrome floats over that edge rather than
 * sitting under it — so a row brought up from below lands half behind the navigation capsule unless
 * it says how much of the edge is not really free. The page supplies it, because the row cannot see
 * the chrome from here.
 */
class MidnightSettingSpotlight(
    val title: String,
    val bottomReserve: Dp = 0.dp,
    val onReached: () -> Unit,
)

/**
 * The marked row, by title, for every [MidnightSettingRow] beneath a settings page.
 *
 * A composition local rather than a parameter because the rows are forty separate calls spread over
 * two thousand lines, and a parameter would have to be threaded through every one of them to be
 * read by at most one. Defaulting to null keeps every other caller — the player sheets, the
 * pickers — exactly as it was.
 */
val LocalMidnightSettingSpotlight = compositionLocalOf<MidnightSettingSpotlight?> { null }

/**
 * One settings row (§22.2): title, one line of description, the current value as plain text.
 *
 * No icon tile, no pill around the value, no essay — the long explanation belongs in the picker
 * the row opens (§46). The whole row is the target so a remote lands on one thing per setting.
 */
@Composable
fun MidnightSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    busy: Boolean = false,
    showChevron: Boolean = onClick != null,
    connected: Boolean? = null,
    connectedText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** A mark before the title — a brand glyph on the About rows, nothing anywhere else. */
    leading: (@Composable () -> Unit)? = null,
) {
    MidnightSettingRowScaffold(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        role = Role.Button,
        title = title,
        description = description,
        danger = danger,
        leading = leading,
        trailing = {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalMidnightAccent.current,
                )
                connected != null -> MidnightConnectionStatus(
                    text = connectedText ?: if (connected) "Connected" else "Not connected",
                    connected = connected,
                )
                trailing != null -> trailing()
                value != null -> Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W700,
                    color = if (enabled) LocalMidnightAccent.current else Midnight.Text3,
                    maxLines = 1,
                )
            }
            if (showChevron) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Midnight.Text3,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp),
                )
            }
        },
    )
}

/** A settings row whose value is a switch (§23). Tapping anywhere on the row toggles it. */
@Composable
fun MidnightSettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    MidnightSettingRowScaffold(
        modifier = modifier,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        role = Role.Switch,
        title = title,
        description = description,
        danger = false,
        trailing = { MidnightSwitch(checked = checked, enabled = enabled) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MidnightSettingRowScaffold(
    modifier: Modifier,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    role: Role,
    title: String,
    description: String?,
    danger: Boolean,
    trailing: @Composable () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val device = LocalAppDeviceProfile.current
    var focused by remember { mutableStateOf(false) }
    // The same rounded badge every choice row in the app wears: white under the cursor with its
    // copy and its control inverted, a faint card otherwise. No rule between rows — the gap and
    // the corner are what separate them now.
    val shape = RoundedCornerShape(MidnightRowRadius)
    val fill = if (focused) MidnightFocusFill else MidnightRestFill
    // A television's row does not fill under the cursor (see MidnightFocusFill), so a danger row
    // stays red there.
    val ink = when {
        focused && !device.isTv -> MidnightFocusCopy
        !enabled -> Midnight.Text3
        danger -> Midnight.Red
        else -> Midnight.Text
    }
    val support = if (focused) MidnightFocusCopy.copy(alpha = 0.66f) else Midnight.Text3
    // Matched by title because that is the only name a row and the search index share; a row the
    // index has since had renamed out from under it simply goes unmarked, which is why
    // `SettingsSearchIndexTest` reads both back out of the source and pins them together.
    val spotlight = LocalMidnightSettingSpotlight.current?.takeIf { it.title == title }
    val bringIntoView = remember { BringIntoViewRequester() }
    val focusRequester = remember { FocusRequester() }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val reserve = with(LocalDensity.current) { spotlight?.bottomReserve?.toPx() ?: 0f }
    LaunchedEffect(spotlight) {
        if (spotlight == null) return@LaunchedEffect
        // A row already under the cursor when the mark arrives has been reached by definition, and
        // would otherwise wear both at once with nothing left to put the mark out.
        if (focused) {
            spotlight.onReached()
            return@LaunchedEffect
        }
        // Asking before the row has been measured would ask for a point rather than a row, and
        // land it under the chrome the reserve is there to clear.
        val measured = snapshotFlow { rowSize }.first { it.height > 0 }
        bringIntoView.bringIntoView(
            Rect(0f, 0f, measured.width.toFloat(), measured.height + reserve),
        )
        // A television hands the cursor to the row as well. Leaving it where it landed meant the
        // search pane's disappearance dropped focus onto the top bar's Home tab, and Down from
        // there found whatever row sat under the bar — two above "Arrange Home", measured — so a
        // remote had to hunt for the setting it had just searched for. Taking focus also puts the
        // mark out (onFocusChanged below): the cursor is now the mark. A touch screen has no
        // cursor and is left alone, and a row with no action is not a focus stop to give it to.
        if (device.isTv && onClick != null && enabled) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            // A television's cursor; ahead of the clip, which would shave the ring off.
            .focusHalo(focused && device.isTv, shape, FocusRingGap)
            .clip(shape)
            .background(fill, shape)
            .bringIntoViewRequester(bringIntoView)
            .onSizeChanged { rowSize = it }
            // Ahead of the clickable below, which is the focus target it names.
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused || it.hasFocus
                if (focused) spotlight?.onReached?.invoke()
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = role) {
                        spotlight?.onReached?.invoke()
                        onClick()
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (device.isTv) 56.dp else 53.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                CompositionLocalProvider(LocalContentColor provides ink) { leading() }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (focused) FontWeight.W700 else FontWeight.W600,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = support,
                        // On a television one line at rest keeps a list of rows scannable, and the
                        // row under the cursor gives the whole of it — a remote has no other way to
                        // read the rest (the download dialog's "Switch servers per episode" ended
                        // mid-sentence on TV). A touch screen has no cursor: a tap toggles the row
                        // rather than focusing it, so a clipped line there could never be read
                        // ("Accent in the player … off keeps them white ov…"). Phones get it whole.
                        maxLines = if (focused || !device.isTv) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            CompositionLocalProvider(LocalMidnightAccent provides ink) { trailing() }
        }
        if (spotlight != null) MidnightSettingSpotlightMark()
    }
}

/**
 * The mark on the row a search asked for: the cursor's wash, breathing, behind the section header's
 * own 2dp purple marker down the leading edge.
 *
 * The marker rather than the cursor's full rim is what keeps the two apart. On a television a row
 * that is a control takes the cursor itself, which puts the mark out; one that is not (a read-only
 * row) keeps the mark while the cursor sits elsewhere, and two identical rims would then be a page
 * with two cursors on it.
 *
 * The gentle pulse draws attention after the list scrolls to the answer. Cheap boxes get a still
 * mark, on the same reasoning as every other effect they are spared.
 */
@Composable
private fun BoxScope.MidnightSettingSpotlightMark() {
    val wash = LocalMidnightAccent.current
    val marker = LocalMidnightAccent.current
    val breath: State<Float> = if (LocalAppDeviceProfile.current.useLowCostTvEffects) {
        remember { mutableStateOf(1f) }
    } else {
        rememberInfiniteTransition(label = "midnight-spotlight").animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "midnight-spotlight-breath",
        )
    }
    Box(
        Modifier
            .matchParentSize()
            // Read inside the draw so the breath costs a redraw a frame and not a recomposition.
            .drawBehind {
                val alpha = breath.value
                drawRect(color = wash.copy(alpha = 0.10f * alpha))
                drawRect(
                    color = marker.copy(alpha = alpha),
                    size = Size(2.dp.toPx(), size.height),
                )
            },
    )
}

/**
 * The switch (§23): 34×18 track, 12dp thumb. One of the few controls that is legitimately a
 * capsule, so it is the only place a pill radius is welcome.
 */
@Composable
fun MidnightSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val offset by animateDpAsState(if (checked) 19.dp else 3.dp, label = "midnight-switch")
    val track = when {
        !enabled -> Midnight.DisabledFill
        checked -> LocalMidnightAccent.current
        else -> Midnight.BarTrack
    }
    // Read off the track rather than fixed, because the track follows the row it sits in: white
    // on a dark row, near-black on the white one under the cursor. A constant dark thumb vanished
    // on the focused row — same colour as its own track — and the switch read as an empty capsule
    // with no way to tell on from off.
    val thumb = when {
        !enabled -> Midnight.DisabledContent
        checked -> if (track.luminance() > 0.4f) Color(0xFF07070A) else Color.White
        else -> Midnight.Text2.copy(alpha = 0.75f)
    }
    Box(
        modifier = modifier
            .size(width = 34.dp, height = 18.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(track),
    ) {
        Box(
            Modifier
                .padding(start = offset)
                .align(Alignment.CenterStart)
                .size(12.dp)
                .clip(CircleShape)
                .background(thumb),
        )
    }
}

/**
 * The picker every settings choice opens (§45–46): the long explanation lives here, on one screen,
 * instead of under every row on the list behind it.
 *
 * It is the same host as the download sheet, so a phone gets a sheet, a television gets the
 * asymmetric card, and the cursor starts on the value that is already stored.
 */
@Composable
fun <T> MidnightChoicePicker(
    kicker: String,
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    explanation: String? = null,
    description: (T) -> String? = { null },
    trailing: (T) -> String? = { null },
    optionEnabled: (T) -> Boolean = { true },
    notice: String? = null,
    noticeTone: MidnightNoticeTone = MidnightNoticeTone.INFO,
    dismissOnSelect: Boolean = true,
    leading: ((T) -> (@Composable () -> Unit)?) = { null },
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    MidnightDialogHost(
        onDismiss = onDismiss,
        modifier = modifier,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = kicker,
                title = title,
                metadata = null,
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = {
            MidnightDialogFooter(quietText = "Close", onQuiet = onDismiss)
        },
    ) {
        if (!explanation.isNullOrBlank()) {
            MidnightHelper(explanation)
            Spacer(Modifier.size(6.dp))
        }
        options.forEach { option ->
            MidnightChoiceRow(
                title = label(option),
                subtitle = description(option),
                trailing = trailing(option),
                selected = option == selected,
                enabled = optionEnabled(option),
                leading = leading(option),
                onSelect = {
                    onSelect(option)
                    if (dismissOnSelect) onDismiss()
                },
            )
        }
        body?.invoke(this)
        if (!notice.isNullOrBlank()) {
            MidnightInlineNotice(notice, tone = noticeTone)
        }
    }
}
