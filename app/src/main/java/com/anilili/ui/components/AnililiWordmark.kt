package com.anilili.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Home header wordmark. Branding shows "ANIME" in the theme accent.
 */
@Composable
fun AnililiWordmark(modifier: Modifier = Modifier) {
    Text(
        text = "ANIME",
        style = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}
