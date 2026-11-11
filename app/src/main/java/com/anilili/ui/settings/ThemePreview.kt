package com.anilili.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anilili.ui.theme.LocalAppColors

/** A round chip of [color], ringed so white and black both read against any row. */
@Composable
internal fun ThemeSwatch(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, LocalAppColors.current.line2, CircleShape),
    )
}

/**
 * The theme as it stands, drawn with the same roles the app reads: a filled and a tonal action, a
 * selected and a resting chip, the surface ladder, the accent family and the three text weights.
 *
 * Purely a picture — nothing in it can take focus, so a remote walks straight past it to the rows,
 * and a screen reader hears the rows rather than a list of colour names.
 */
@Composable
internal fun ThemePreview(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val scheme = colors.scheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(shape)
            .background(scheme.surfaceContainerLow, shape)
            .border(1.dp, colors.line, shape)
            .padding(14.dp)
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewPill("Play", scheme.primary, scheme.onPrimary)
            PreviewPill("Save", scheme.primaryContainer, scheme.onPrimaryContainer)
            PreviewPill("Sub", scheme.secondaryContainer, scheme.onSecondaryContainer)
            PreviewPill("Dub", Color.Transparent, scheme.onSurfaceVariant, border = colors.line2)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "Episode 12 · The Journey Continues",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.W700,
                color = scheme.onSurface,
            )
            Text("24 min · Sub and dub", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text("Aired 2 days ago", style = MaterialTheme.typography.labelSmall, color = colors.text3)
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.barTrack),
            ) {
                Box(Modifier.fillMaxWidth(0.62f).height(4.dp).clip(CircleShape).background(scheme.primary))
            }
        }
        // The surface ladder, lowest to highest, then the accent family: what AMOLED, style and
        // contrast actually move.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                scheme.background,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            ).forEach { tone ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tone)
                        .border(1.dp, colors.line, RoundedCornerShape(6.dp)),
                )
            }
            listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.error).forEach { tone ->
                ThemeSwatch(tone, Modifier.padding(start = 2.dp))
            }
        }
    }
}

@Composable
private fun PreviewPill(label: String, container: Color, content: Color, border: Color? = null) {
    val shape = RoundedCornerShape(99.dp)
    Box(
        Modifier
            .clip(shape)
            .background(container, shape)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W700, color = content)
    }
}
