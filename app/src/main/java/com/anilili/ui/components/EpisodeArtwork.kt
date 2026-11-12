package com.anilili.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.anilili.data.model.EpisodeItem

/** Provider art wins when available; the anime/season art keeps sparse catalogs presentable. */
internal fun episodeArtworkImage(episodeImage: String?, fallbackImage: String?): String? =
    episodeImage ?: fallbackImage

/**
 * The fallback to hand [episodeArtworkImage] for [episodes], or null when it would be the only
 * picture in the list.
 *
 * The fallback is the season banner — a wide still from the show — and on a provider that ships no
 * per-episode art (bonk, among others) it was painted on every row, so twelve episodes came out as
 * twelve copies of the same frame. That does not keep a sparse catalog presentable; it reads as a
 * list that failed to load, and it hides the one fact the rows could still have told apart, which
 * is that there is no thumbnail for this episode. A blank frame behind the EP badge says that
 * honestly.
 *
 * A list of one is exempt: a movie or a single special cannot look repeated, and there the poster
 * is the best picture available.
 */
internal fun episodeArtworkFallback(episodes: List<EpisodeItem>, fallbackImage: String?): String? =
    // Multi-episode lists: never paint the series banner on every row. That reads as "one download
    // thumb took over the list". Only a single-episode title (movie/OVA) uses the series art.
    fallbackImage?.takeIf { episodes.size <= 1 }

/**
 * Episode artwork, blurred when the viewer has asked for spoiler-free episode images.
 *
 * Only draws. Tapping a thumbnail used to flip that preference for every episode image in the app,
 * which meant a tap aimed at an episode — the thing every other part of the row does — hid or
 * revealed the whole list instead of playing it. The preference lives in Settings alone now, and a
 * tap here falls through to the card like a tap anywhere else on it.
 */
@Composable
fun EpisodeArtwork(
    image: String?,
    blurred: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    compact: Boolean = false,
) {
    Box(modifier = modifier.clipToBounds()) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (blurred) {
                        Modifier
                            .graphicsLayer {
                                // Fill the soft edge introduced by the blur without changing the
                                // card's measured size or the sharp overlays drawn by the caller.
                                scaleX = 1.10f
                                scaleY = 1.10f
                            }
                            .blur(20.dp)
                    } else {
                        Modifier
                    },
                ),
        )
        if (blurred) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Compose blur is not rendered on older Android versions. This veil is the
                    // privacy fallback and also keeps high-contrast stills unreadable everywhere.
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.68f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.58f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(50),
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = if (compact) 6.dp else 9.dp,
                            vertical = if (compact) 4.dp else 6.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = null,
                            modifier = Modifier.size(if (compact) 14.dp else 17.dp),
                        )
                        if (!compact) {
                            Text(
                                text = "Spoilers hidden",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
