package com.anilili.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.EpisodeFillerBadge

/**
 * The player's offer to pass over a filler episode: where the skip lands ("Episode 27",
 * "Season 2"), and the press that goes there. Null on the players whenever the episode is not
 * filler, or nothing that is not filler follows it.
 */
@Immutable
data class PlayerFillerSkip(
    val target: String,
    val onSkip: () -> Unit,
)

/**
 * The tag under the touch players' episode title: the same FILLER badge every episode list wears,
 * so the viewer knows what they are watching, joined to the skip it offers. One target rather
 * than two, since a badge too small to hit would be the thing a thumb lands on.
 */
@Composable
internal fun PlayerFillerTag(
    skip: PlayerFillerSkip,
    modifier: Modifier = Modifier,
    onInteract: () -> Unit = {},
) {
    val shape = RoundedCornerShape(8.dp)
    val focus = rememberFocusFill()
    Row(
        modifier = modifier
            .clip(shape)
            .background(focus.background(Color.Black.copy(alpha = 0.48f)))
            .semantics { contentDescription = "Filler episode. Skip to ${skip.target}" }
            .focusFill(focus, shape)
            .clickable(role = Role.Button) {
                onInteract()
                skip.onSkip()
            }
            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EpisodeFillerBadge()
        Text(
            text = "Skip to ${skip.target}",
            color = focus.content(Color.White),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = focus.content(Color.White.copy(alpha = 0.85f)),
            modifier = Modifier.size(16.dp),
        )
    }
}
