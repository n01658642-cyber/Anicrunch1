package com.anilili.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.anilili.R

/**
 * The "ANILILI" header wordmark, drawn in the theme's accent.
 *
 * The PNG is a pure-white alpha mask, so tinting it is lossless: white in the default monochrome
 * theme exactly as before, and blood red, neon green or the wallpaper's colour when the viewer picks
 * one. Shared so the phone and television headers cannot drift into two different logos.
 */
@Composable
fun AnililiWordmark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.anilili_header_wordmark),
        contentDescription = stringResource(R.string.app_name),
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        modifier = modifier,
    )
}
