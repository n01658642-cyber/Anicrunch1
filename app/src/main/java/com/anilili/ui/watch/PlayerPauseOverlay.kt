package com.anilili.ui.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * How long a pause has to hold before the billing appears.
 *
 * Most pauses are short and are about the picture, not the episode: a subtitle to re-read, a
 * background gag, a frame worth a screenshot. Covering the left of the frame instantly put the
 * panel in the way of the exact thing the pause was for. Fifteen seconds is well past that and
 * into "put the phone down", which is the pause the panel was written for — the one where you come
 * back and want to know what you are part-way through.
 */
internal const val PAUSE_OVERLAY_DELAY_MS = 15_000L

/**
 * What the picture becomes while it is paused: the episode's own billing, over a darkened frame.
 *
 * A paused player is a still frame and nothing else, which is the one moment the viewer is reading
 * the screen rather than watching it — usually to work out what they are part-way through. The
 * overlay answers that without making them leave the player, and it doubles as the thing that tells
 * a paused episode apart from a stalled one at a glance.
 *
 * It deliberately waits for the controls to go away. Drawn underneath them the two sets of text
 * overlap, and the controls are the layer a viewer is interacting with when they are up.
 */
@Composable
internal fun PlayerPauseOverlay(
    visible: Boolean,
    seriesTitle: String,
    episodeTitle: String,
    providerName: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Horizontal rather than the vertical scrims the controls use: the text is a column
                // down the left, so darkening the right of the frame would only hide picture that
                // nothing is written over.
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.82f),
                        0.55f to Color.Black.copy(alpha = 0.40f),
                        1f to Color.Transparent,
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .statusBarsPadding()
                    .padding(start = 28.dp, end = 24.dp)
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (seriesTitle.isNotBlank()) {
                    // The series' own logo, as streaming apps bill a paused episode; the title in
                    // type wherever there is none.
                    PlayerSeriesName(
                        title = seriesTitle,
                        maxHeight = 96.dp,
                        maxWidth = 360.dp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Text(
                            text = seriesTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (episodeTitle.isNotBlank()) {
                    Text(
                        text = episodeTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (providerName.isNotBlank()) {
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                        // Enough to place the episode, short enough that it never reaches the
                        // controls when they come back up on a short screen.
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
