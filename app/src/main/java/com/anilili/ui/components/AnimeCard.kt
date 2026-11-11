package com.anilili.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.anilili.data.DubIndex
import com.anilili.data.model.Media
import com.anilili.data.settings.PosterElement
import com.anilili.ui.adaptive.CaptionedCardShape
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.dpadClickable
import com.anilili.ui.adaptive.focusHighlight
import dev.chrisbanes.haze.HazeState

/**
 * Dense poster card shared by Home, Browse, and AniList library rows.
 *
 * Holding one opens the list-status menu. That is the only way to say "I have already seen this"
 * about a title you are not about to play — the case for anyone whose watch history was built on
 * another service and who is putting their list back together from the catalogue.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnimeCard(
    media: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val poster = rememberPosterElements()
    val titleStyle = if (device.isTv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelLarge
    // Two lines everywhere. One line turned most of the catalogue into a prefix — "Bleach the
    // Movie: Hell V…" beside "BLEACH Colorful!: Gote…" tells you which franchise and nothing else,
    // which is exactly the part a search result exists to answer.
    val titleMaxLines = 2
    // The block keeps its full height whether the title used one line or two, so a short title
    // next to a long one does not leave their metadata strips on different baselines.
    val titleBlockMinHeight = with(LocalDensity.current) {
        titleStyle.lineHeight.toDp() * titleMaxLines + 4.dp
    }
    var actionsVisible by remember(media.id) { mutableStateOf(false) }
    if (actionsVisible) {
        AnimeListActionsDialog(
            anilistId = media.id,
            title = media.title.preferred,
            cover = media.coverImage.best,
            metadata = listOfNotNull(
                media.format?.replace('_', ' '),
                media.seasonYear?.toString(),
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            onDismiss = { actionsVisible = false },
            kicker = "My list",
        )
    }
    Column(
        modifier = modifier
            // The title below is what names this card to a screen reader, so a card drawn without
            // one has to say the name itself; otherwise the merged node is left with the click
            // label alone and every poster on the screen announces as "Open details".
            .then(
                if (poster.shows(PosterElement.TITLE)) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = media.title.preferred }
                },
            )
            // The metadata line below sits on the card's bottom edge; a fully rounded highlight
            // clips its first letter away. With both captions switched off there is no text to
            // clip and the card is artwork alone, so the highlight closes back up around it.
            .focusHighlight(
                if (poster.shows(PosterElement.TITLE) || poster.shows(PosterElement.DETAILS)) {
                    CaptionedCardShape
                } else {
                    RoundedCornerShape(12.dp)
                },
            )
            // combinedClickable's long press is pointer-only; the remote gets its own.
            .dpadClickable(onClick = onClick, onLongClick = { actionsVisible = true })
            .combinedClickable(
                onClickLabel = "Open details",
                onLongClickLabel = "Move ${media.title.preferred} on your anime list",
                role = Role.Button,
                onClick = onClick,
                onLongClick = { actionsVisible = true },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Decorative: the title text below is part of the same merged semantics node.
            AsyncImage(
                // AniList's `large` rendition is already larger than these poster cards. Avoid
                // downloading `extraLarge` for every small rail/grid item on data-limited devices.
                model = media.coverImage.large ?: media.coverImage.extraLarge,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // The score sits top-right on every card presentation, so the eye finds it in the same
            // corner whichever rail or grid it is reading; 18+ takes the corner opposite.
            media.averageScore?.takeIf { poster.shows(PosterElement.SCORE) }?.let { score ->
                RatingBadge(score, Modifier.align(Alignment.TopEnd).padding(5.dp))
            }
            if (media.isAdult && poster.shows(PosterElement.ADULT)) {
                AdultBadge(Modifier.align(Alignment.TopStart).padding(5.dp))
            }
            // Only the positive case is shown. Every title in the catalogue has subtitles, so a
            // "SUB" badge would sit on every card and say nothing; a dub is the part a viewer is
            // actually unsure about, and it is unknown far more often than it is absent.
            if (poster.shows(PosterElement.DUB) && DubIndex.hasDub(media.id)) {
                DubBadge(Modifier.align(Alignment.BottomStart).padding(5.dp))
            }
        }
        if (poster.shows(PosterElement.TITLE)) {
            Text(
                text = media.title.preferred,
                style = titleStyle,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 5.dp)
                    .heightIn(min = titleBlockMinHeight),
            )
        }
        if (poster.shows(PosterElement.DETAILS)) {
            Text(
                text = listOfNotNull(
                    media.format?.replace('_', ' '),
                    media.seasonYear?.toString(),
                    media.episodes?.let { "$it EP" },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Without a title above it this line is the caption, and it would otherwise sit
                // flush against the artwork it belongs under.
                modifier = if (poster.shows(PosterElement.TITLE)) Modifier else Modifier.padding(top = 5.dp),
            )
        }
    }
}

/**
 * The one shape every label drawn over poster artwork takes: dub, score, progress, sizes, counts.
 *
 * Each card used to draw its own, and side by side on one poster they read as two different
 * systems — the TV score chip set its figure at 11sp beside a dub tag at the TV's 13sp, with a
 * different corner, padding and no outline. Every tag is now this plate: near-opaque black, because
 * it sits on arbitrary artwork and only a colour of its own keeps white text legible on a bright
 * frame, and a thin accent outline so it reads as the app's rather than the artwork's.
 *
 * [container] and [outlined] exist for the two tags that are signals rather than facts — new
 * episodes and 18+ — which keep their fill but share the geometry, so a corner stack of mixed tags
 * still lines up. The score also drops the outline; see [ScoreTag].
 */
@Composable
fun PosterTag(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
    container: Color = Color.Black.copy(alpha = .78f),
    content: Color = Color.White,
    outlined: Boolean = true,
) {
    val style = MaterialTheme.typography.labelSmall
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier
            .then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier)
            .clip(shape)
            .background(container)
            .then(if (outlined) Modifier.border(1.dp, accent.copy(alpha = .6f), shape) else Modifier)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            // Sized to the type rather than fixed, so the glyph keeps its proportion on television,
            // where labelSmall is two points larger, and never makes a scored tag taller than a bare one.
            Icon(
                icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .padding(end = 2.dp)
                    .size(with(LocalDensity.current) { style.fontSize.toDp() }),
            )
        }
        Text(text, style = style, color = content, fontWeight = FontWeight.Bold)
    }
}

/**
 * Marks a title the dub index positively found an English dub for.
 *
 * Shared rather than private because every poster presentation should agree: a badge that appears
 * on Home but not in the library reads as the library having different information, when in fact
 * it is the same index answering the same question.
 */
@Composable
fun DubBadge(modifier: Modifier = Modifier) {
    PosterTag("DUB", modifier, description = "English dub available")
}

/**
 * "3 NEW" on a library poster whose series has broadcast past where the viewer left it.
 *
 * Filled with the accent colour rather than the black every other poster badge wears, because it is
 * the only one that says there is something to do here; the score and the dub marker are facts about
 * the title, and blending in is right for those.
 *
 * The count is capped rather than printed in full: a long-runner picked up at episode 40 is
 * genuinely a thousand episodes behind, and a poster corner is not where that number belongs.
 */
@Composable
fun NewEpisodesBadge(count: Int, modifier: Modifier = Modifier) {
    PosterTag(
        if (count > NEW_EPISODE_COUNT_CAP) "$NEW_EPISODE_COUNT_CAP+ NEW" else "$count NEW",
        modifier,
        description = if (count == 1) "1 new episode" else "$count new episodes",
        container = MaterialTheme.colorScheme.primary,
        content = MaterialTheme.colorScheme.onPrimary,
        outlined = false,
    )
}

private const val NEW_EPISODE_COUNT_CAP = 99

/**
 * One corner button drawn on top of a card's artwork.
 *
 * Its own [clickable] sits above the card's, so a tap here never also opens or resumes the title.
 * The disc behind the glyph is what keeps it legible: artwork is arbitrary, and a bare white icon
 * disappears into any bright frame. The label rides on the icon rather than on the click, so a
 * screen reader names the button instead of only naming what activating it would do.
 *
 * Pointer-only by design. A D-pad has no way to reach a control drawn inside a card — the card is
 * one focus target — so on television every one of these lives behind the long-press menu instead,
 * and the caller is responsible for not drawing it there.
 */
@Composable
fun CardCornerAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The artwork under the button, frosted through the disc on Android 12+. */
    backdrop: HazeState? = null,
) {
    val frosted = backdrop != null && backdropBlurAvailable
    Box(
        modifier
            .size(30.dp)
            .clip(CircleShape)
            // Frosted, the disc can be lighter and still hold the glyph: the blur already takes
            // the busy detail out from under it, which is what the heavier scrim was there for.
            .backdropBlur(backdrop, tint = Color.Black.copy(alpha = .30f), radius = 10.dp)
            .background(Color.Black.copy(alpha = if (frosted) 0f else .55f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun AdultBadge(modifier: Modifier = Modifier) {
    PosterTag(
        "18+",
        modifier,
        description = "Adult content",
        container = MaterialTheme.colorScheme.error,
        content = MaterialTheme.colorScheme.onError,
        outlined = false,
    )
}

/** AniList's community score on a poster, shared by every media card presentation. */
@Composable
fun RatingBadge(score: Int, modifier: Modifier = Modifier) {
    ScoreTag("$score%", "Rated $score percent", modifier)
}

/**
 * A score in whatever form the card has it — AniList's percentage or the viewer's own 7.5.
 *
 * The one plain tag without the outline: the star already carries the accent, and a hairline
 * around it as well made the score the loudest mark on a poster that is mostly saying "dub".
 */
@Composable
fun ScoreTag(score: String, description: String, modifier: Modifier = Modifier) {
    PosterTag(score, modifier, icon = Icons.Default.Star, description = description, outlined = false)
}

val GridContentPadding = PaddingValues(16.dp)
