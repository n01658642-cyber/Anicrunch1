package com.anilili.ui.crunchyroll

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object CrColors {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF000000)
    val Card = Color(0xFF141414)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextMuted = Color(0xFF8C8C8C)
    val TabInactive = Color(0xFF8C8C8C)
    val Divider = Color(0xFF2A2A2A)

    /** Appearance accent (Settings theme), not a hardcoded Crunchyroll orange. */
    val accent: Color
        @Composable get() = MaterialTheme.colorScheme.primary
}
