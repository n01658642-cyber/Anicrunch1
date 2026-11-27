package com.anilili.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Resolve the image URL for an episode card:
 * 1) episode-specific still
 * 2) season / series fallback (banner or cover) when the still is missing
 */
internal fun episodeArtworkImage(episodeImage: String?, fallbackImage: String?): String? =
    episodeImage?.takeIf { it.isNotBlank() } ?: fallbackImage?.takeIf { it.isNotBlank() }

/**
 * Series art handed to rows when an episode still is absent. Always allowed — empty black boxes
 * are worse than a repeated banner; Coil's onError still swaps failed CDN URLs at runtime.
 */
internal fun episodeArtworkFallback(episodes: List<EpisodeItem>, fallbackImage: String?): String? =
    fallbackImage?.takeIf { it.isNotBlank() }

/**
 * Episode artwork with series fallback when the still is missing or the CDN 404s.
 */
@Composable
fun EpisodeArtwork(
    image: String?,
    blurred: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    compact: Boolean = false,
    /** Series banner/cover when [image] is null or the CDN fails. */
    errorFallback: String? = null,
) {
    var loadFailed by remember(image, errorFallback) { mutableStateOf(false) }
    val primary = image?.takeIf { it.isNotBlank() }
    val secondary = errorFallback?.takeIf { it.isNotBlank() }
    val model = when {
        primary != null && !loadFailed -> primary
        secondary != null -> secondary
        else -> primary
    }
    Box(modifier = modifier.clipToBounds()) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = contentScale,
                onError = {
                    if (primary != null && secondary != null && !loadFailed && model == primary) {
                        loadFailed = true
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (blurred) {
                            Modifier
                                .graphicsLayer {
                                    scaleX = 1.10f
                                    scaleY = 1.10f
                                }
                                .blur(16.dp)
                        } else {
                            Modifier
                        },
                    ),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)))
        }
        if (blurred) {
            Surface(
                color = Color.Black.copy(alpha = 0.35f),
                shape = RoundedCornerShape(if (compact) 6.dp else 10.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(4.dp),
            ) {
                Icon(
                    Icons.Default.VisibilityOff,
                    contentDescription = null,
                    tint = Color.White.copy(0.9f),
                    modifier = Modifier
                        .padding(6.dp)
                        .size(if (compact) 16.dp else 20.dp),
                )
            }
        }
    }
}
