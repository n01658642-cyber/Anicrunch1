package com.anilili.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.anilili.data.settings.PosterElement
import com.anilili.data.settings.SettingsStore

/**
 * Which of a poster's badges and captions this viewer has left switched on.
 *
 * Read through one helper rather than by collecting the preference at each card, because the same
 * poster is drawn by five different composables — the catalogue grid, the library grid, Continue
 * Watching, the television rails and the offline shelf — and a badge that obeys the setting on one
 * screen but not another reads as a bug in the screen rather than as a setting that missed it.
 */
@Immutable
@JvmInline
value class PosterElementVisibility internal constructor(private val hidden: Set<PosterElement>) {
    /** Asks the positive question, so call sites read as "if this card shows a dub marker". */
    fun shows(element: PosterElement): Boolean = element !in hidden
}

/** The current poster preference, recomposing every card that reads it when it changes. */
@Composable
fun rememberPosterElements(): PosterElementVisibility {
    val hidden by SettingsStore.hiddenPosterElements.collectAsState()
    return remember(hidden) { PosterElementVisibility(hidden) }
}
