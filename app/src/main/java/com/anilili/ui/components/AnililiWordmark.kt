package com.anilili.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Home header wordmark. Branding shows full "ANIME" in the theme accent (not clipped).
 */
@Composable
fun AnililiWordmark(modifier: Modifier = Modifier) {
    Text(
        text = "ANIME",
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        letterSpacing = 1.5.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        modifier = modifier.heightIn(min = 24.dp),
        style = MaterialTheme.typography.titleLarge,
    )
}
