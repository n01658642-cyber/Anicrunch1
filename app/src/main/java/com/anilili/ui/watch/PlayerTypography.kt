package com.anilili.ui.watch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.anilili.R

/**
 * The player's own typeface.
 *
 * Nuvio sets its whole app in JetBrains Sans; that face carries no licence field and is JetBrains'
 * brand type rather than an openly published one, so this is Inter — the nearest open neo-grotesque,
 * under the SIL Open Font License (the licence text ships in `assets/licenses/inter-OFL.txt`).
 *
 * Scoped to the player rather than applied app-wide, because the rest of the app is set in the
 * Midnight type scale and changing that is a separate decision. The seam is real but it falls where
 * a viewer never sees the two together: the player is full-screen and owns the display for as long
 * as it is up.
 */
private val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Medium),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * The current type scale with every style reset in the player's face.
 *
 * Only the family is replaced; sizes, weights and line heights stay exactly as the app's own scale
 * defines them, so this changes how the player reads and not how it is laid out.
 */
@Composable
internal fun playerTypography() = MaterialTheme.typography.let { base ->
    remember(base) {
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = InterFontFamily),
            displayMedium = base.displayMedium.copy(fontFamily = InterFontFamily),
            displaySmall = base.displaySmall.copy(fontFamily = InterFontFamily),
            headlineLarge = base.headlineLarge.copy(fontFamily = InterFontFamily),
            headlineMedium = base.headlineMedium.copy(fontFamily = InterFontFamily),
            headlineSmall = base.headlineSmall.copy(fontFamily = InterFontFamily),
            titleLarge = base.titleLarge.copy(fontFamily = InterFontFamily),
            titleMedium = base.titleMedium.copy(fontFamily = InterFontFamily),
            titleSmall = base.titleSmall.copy(fontFamily = InterFontFamily),
            bodyLarge = base.bodyLarge.copy(fontFamily = InterFontFamily),
            bodyMedium = base.bodyMedium.copy(fontFamily = InterFontFamily),
            bodySmall = base.bodySmall.copy(fontFamily = InterFontFamily),
            labelLarge = base.labelLarge.copy(fontFamily = InterFontFamily),
            labelMedium = base.labelMedium.copy(fontFamily = InterFontFamily),
            labelSmall = base.labelSmall.copy(fontFamily = InterFontFamily),
        )
    }
}
