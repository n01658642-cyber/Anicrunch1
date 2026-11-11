package com.anilili.ui.components



import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anilili.data.model.EpisodeItem
import com.anilili.data.settings.EpisodeLayout
import com.anilili.data.settings.EpisodeOrder
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvFocusTarget
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.dpadClickable
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.adaptive.tvFocusTarget

/** Episodes per range block. One block is meant to be one comfortable scroll. */
const val EPISODE_BLOCK_SIZE = 100

/**
 * How far an unreleased episode is faded. Low enough to read as unavailable at a glance, high
 * enough that the number stays legible — the row still answers "what is episode 9 called", it just
 * cannot be opened.
 */
const val UNAIRED_ALPHA = 0.38f

/**
 * Below this the range picker and the filter field are only noise: a single cour already fits in a
 * short scroll and has nothing worth filtering. Long-runners are the case those two exist for. The
 * layout toggle is not bound by it — how the list is drawn is a preference a twelve-episode season
 * has just as much as a thousand-episode one (see [EpisodeBrowserBar]'s `showFilter`).
 */
const val EPISODE_BROWSER_MIN_EPISODES = 12

/** One entry of the range picker: a contiguous slice of the list, labelled by its two ends. */
data class EpisodeBlock(val label: String, val episodes: List<EpisodeItem>)

/**
 * Slice [episodes] into labelled blocks. Chunking is by position rather than by episode number, so
 * gaps, recaps and decimal episodes (13.5) still produce even blocks, and a season that starts at
 * 1051 labels its first block from there instead of pretending it starts at 1.
 */
fun episodeBlocks(
    episodes: List<EpisodeItem>,
    blockSize: Int = EPISODE_BLOCK_SIZE,
): List<EpisodeBlock> = episodes.chunked(blockSize).map { chunk ->
    val first = chunk.first().displayNumber
    val last = chunk.last().displayNumber
    EpisodeBlock(if (first == last) first else "$first – $last", chunk)
}

/**
 * Number prefix first, then title. Numbers lead because this field doubles as "jump to episode":
 * on One Piece, typing 1050 should land on that episode rather than on every title that happens
 * to contain those digits.
 */
fun episodeMatchesQuery(episode: EpisodeItem, query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    if (episode.displayNumber.startsWith(trimmed, ignoreCase = true)) return true
    return episode.distinctTitle?.contains(trimmed, ignoreCase = true) == true
}

/**
 * Matches in list order, except that number matches come first. Titles carry stray digits — One
 * Piece names episodes after bounties, so "500" also hits "500,000,000" three hundred episodes
 * away — and the viewer typing a number wants that episode at the top, not buried mid-list.
 */
fun filterEpisodes(episodes: List<EpisodeItem>, query: String): List<EpisodeItem> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return episodes
    val (byNumber, byTitle) = episodes
        .filter { episodeMatchesQuery(it, trimmed) }
        .partition { it.displayNumber.startsWith(trimmed, ignoreCase = true) }
    return byNumber + byTitle
}

/**
 * Index of the block holding [number], falling back to the first. Keeps a resumed long-runner from
 * opening on episode 1 when the viewer is a thousand episodes in.
 */
fun blockIndexContaining(blocks: List<EpisodeBlock>, number: Double?): Int {
    if (number == null) return 0
    return blocks.indexOfFirst { block -> block.episodes.any { it.number == number } }
        .coerceAtLeast(0)
}

/**
 * The episode a season's list should open on: where the viewer left off, or — on a series long
 * enough to be split into blocks, with no history for it — the newest episode that has aired.
 * Someone opening One Piece for the first time is far likelier to be catching the new episode than
 * starting a thousand-episode run, and episode 1 is still one range chip away. A short season with
 * no history opens at its start, where a newcomer does begin.
 */
fun openingEpisode(
    episodes: List<EpisodeItem>,
    resumeEpisode: Double?,
    isUnaired: (EpisodeItem) -> Boolean = { false },
    blockSize: Int = EPISODE_BLOCK_SIZE,
): EpisodeItem? {
    if (episodes.isEmpty()) return null
    if (resumeEpisode != null) episodes.firstOrNull { it.number == resumeEpisode }?.let { return it }
    if (episodes.size <= blockSize) return null
    return episodes.lastOrNull { !isUnaired(it) } ?: episodes.last()
}

/** The block holding [episode], or the first when there is none to open on. */
/** [episodes] in the viewer's chosen [order]; they arrive oldest first. */
fun orderEpisodes(episodes: List<EpisodeItem>, order: EpisodeOrder): List<EpisodeItem> =
    if (order == EpisodeOrder.NEWEST_FIRST) episodes.asReversed() else episodes

/**
 * [blocks] in the viewer's chosen [order]: newest first turns the range list round as well as the
 * episodes inside each range, so the picker reads the same way as the list under it.
 */
fun orderEpisodeBlocks(blocks: List<EpisodeBlock>, order: EpisodeOrder): List<EpisodeBlock> =
    if (order == EpisodeOrder.NEWEST_FIRST) {
        blocks.asReversed().map { it.copy(episodes = it.episodes.asReversed()) }
    } else {
        blocks
    }

fun blockIndexOf(blocks: List<EpisodeBlock>, episode: EpisodeItem?): Int {
    if (episode == null) return 0
    return blocks.indexOfFirst { block -> block.episodes.any { it.pipeId == episode.pipeId } }
        .coerceAtLeast(0)
}

/**
 * The control row above an episode list: range picker, filter/jump field, layout toggle. Callers
 * decide whether to show it at all; the range picker drops out on its own when every episode
 * already fits in one block, and a short season (see [EPISODE_BROWSER_MIN_EPISODES]) comes down to
 * the toggle alone via [showFilter].
 */
@Composable
fun EpisodeBrowserBar(
    blocks: List<EpisodeBlock>,
    selectedBlockIndex: Int,
    onSelectBlock: (Int) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    layout: EpisodeLayout,
    onToggleLayout: () -> Unit,
    modifier: Modifier = Modifier,
    // The TV player's episode grid is built around D-pad traversal of compact chips; swapping it
    // for tall rows there would be a downgrade, so that one caller drops the toggle.
    showLayoutToggle: Boolean = true,
    // A season too short to page through or filter still gets to choose grid or list, so the bar
    // stays and only its long-list halves go away.
    showFilter: Boolean = true,
    // On TV the D-pad never finds this row by itself — 2D focus search walks straight from the
    // episode list to whatever sits above it, the same way it skips the season selector. Callers
    // hand it a requester and aim their neighbours' up/down at it so the remote lands here.
    focusRequester: FocusRequester? = null,
    tvFocusTarget: TvFocusTarget? = null,
    // Only the detail page offers the sort; the player's own lists follow the running order.
    order: EpisodeOrder? = null,
    onToggleOrder: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .tvFocusTarget(tvFocusTarget)
            .focusGroup(),
        // With the filter gone the toggle is the only thing left, and it belongs on the same edge
        // it sits on in a full bar rather than adrift at the start of an empty row.
        horizontalArrangement = if (showFilter) Arrangement.spacedBy(8.dp) else Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showFilter) {
            if (blocks.size > 1) {
                EpisodeBlockPicker(
                    blocks = blocks,
                    selectedIndex = selectedBlockIndex,
                    onSelect = onSelectBlock,
                )
            }
            EpisodeFilterField(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.weight(1f),
            )
        }
        if (order != null) EpisodeOrderToggle(order = order, onToggle = onToggleOrder)
        if (showLayoutToggle) EpisodeLayoutToggle(layout = layout, onToggle = onToggleLayout)
    }
}

/**
 * Says which order is on show rather than which one a press gives, unlike the layout toggle: a bare
 * arrow cannot say "newest" or "oldest", so the word does, and the word has to describe the list.
 */
@Composable
private fun EpisodeOrderToggle(order: EpisodeOrder, onToggle: () -> Unit) {
    val fill = rememberFocusFill()
    val label = if (order == EpisodeOrder.NEWEST_FIRST) "Newest" else "Oldest"
    Row(
        modifier = Modifier
            .focusFill(fill, RoundedCornerShape(10.dp))
            .background(fill.background(Color.Transparent), RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "Change episode order") { onToggle() }
            .semantics { contentDescription = "$label episodes first" }
            .padding(start = 8.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.SwapVert,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = fill.content(LocalContentColor.current),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            color = fill.content(LocalContentColor.current),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
internal fun EpisodeBlockPicker(
    blocks: List<EpisodeBlock>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = blocks.getOrNull(selectedIndex) ?: blocks.first()
    Box {
        val fillFocus1 = rememberFocusFill()
        Row(
            modifier = Modifier
                .focusFill(fillFocus1, RoundedCornerShape(10.dp))
                .background(fillFocus1.background(Color.Transparent), RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Choose episode range") { expanded = true }
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                color = fillFocus1.content(LocalContentColor.current),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = fillFocus1.content(LocalContentColor.current))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            blocks.forEachIndexed { index, block ->
                DropdownMenuItem(
                    text = { Text(block.label) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                    trailingIcon = if (index == selectedIndex) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodeFilterField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    if (device.isTv) {
        TvNativeTextField(
            value = query,
            onValueChange = onQueryChange,
            hint = "Filter episodes",
            modifier = modifier,
        )
    } else {
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val colors = MaterialTheme.colorScheme
        val textStyle = MaterialTheme.typography.labelLarge.copy(color = colors.onSurface)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = modifier,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .border(1.dp, colors.outline, RoundedCornerShape(10.dp))
                        .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = colors.onSurfaceVariant,
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                "Filter episodes…",
                                style = textStyle.copy(color = colors.onSurfaceVariant),
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        // The glyph stays 20dp to suit the compact field, but the touch target
                        // has to stay a real one — a bare 20dp clickable Icon is well under the
                        // 48dp minimum and is genuinely hard to hit.
                        val extraFocus1 = rememberFocusFill()
                        IconButton(
                            colors = extraFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(36.dp).focusFill(extraFocus1, CircleShape),
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Clear filter",
                                modifier = Modifier.size(20.dp),
                                tint = extraFocus1.content(colors.onSurfaceVariant),
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun EpisodeLayoutToggle(layout: EpisodeLayout, onToggle: () -> Unit) {
    val showsGridNext = layout == EpisodeLayout.LIST
    val controlFocus2 = rememberFocusFill()
    IconButton(
        colors = controlFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),
        onClick = onToggle,
        modifier = Modifier.focusFill(controlFocus2, CircleShape),
    ) {
        // The glyph shows the layout the press switches TO, which is how view toggles are read.
        Icon(
            imageVector = if (showsGridNext) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
            contentDescription = if (showsGridNext) "Show as grid" else "Show as list",
        )
    }
}

/**
 * Compact number chip used by the grid layout on every screen. Carries its own D-pad activation:
 * the TV episode grid is the one place a remote lands on hundreds of these in a row, and the
 * default clickable does not fire on select there.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeNumberChip(
    episode: EpisodeItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float = 0f,
    onLongClick: (() -> Unit)? = null,
    unaired: Boolean = false,
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.primary
        episode.filler -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    // An unaired chip takes no input at all: no click, no long-press, and no focus for a remote to
    // land on. Leaving it focusable would let a D-pad stop on something that cannot do anything,
    // which reads as the app having hung rather than as the episode not being out.
    val enabled = !unaired
    val fillFocus2 = rememberFocusFill()
    Box(
        modifier = modifier
            .alpha(if (unaired) UNAIRED_ALPHA else 1f)
            // Both branches claim select in the preview pass, before the clickable below can act
            // on it; the long-press one is only the same job with a stopwatch attached.
            .then(
                when {
                    !enabled -> Modifier
                    onLongClick != null -> Modifier.dpadClickable(onClick = onClick, onLongClick = onLongClick)
                    else -> Modifier.onPreviewKeyEvent { event ->
                        val keyCode = event.nativeKeyEvent.keyCode
                        val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                        if (!activate) {
                            false
                        } else {
                            if (event.type == KeyEventType.KeyUp) onClick()
                            true
                        }
                    }
                },
            )
            .then(if (enabled) Modifier.focusFill(fillFocus2, RoundedCornerShape(8.dp)) else Modifier)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fillFocus2.background(background))
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp),
            )
            .then(
                when {
                    !enabled -> Modifier
                    onLongClick != null -> Modifier.combinedClickable(
                        onLongClickLabel = "Mark episode watched",
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                    else -> Modifier.clickable(onClick = onClick)
                },
            )
            .then(
                if (unaired) {
                    Modifier.semantics { disabled(); contentDescription = "Episode ${episode.displayNumber}, not released yet" }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            episode.displayNumber,
            style = MaterialTheme.typography.labelLarge,
            color = fillFocus2.content(if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface),
        )
        if (selected) {
            EqualizerWaveIndicator(
                barCount = 3,
                color = fillFocus2.content(MaterialTheme.colorScheme.onPrimary),
                barWidth = 2.dp,
                maxHeight = 10.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 5.dp),
            )
        }
        if (episode.filler) {
            EpisodeFillerBadge(
                compact = true,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 3.dp, start = 4.dp),
            )
        }
        // Watched underline: the selected chip is already fully highlighted, so it skips it.
        if (!selected) {
            WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
