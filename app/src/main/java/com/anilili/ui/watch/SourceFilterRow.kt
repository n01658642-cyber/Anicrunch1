package com.anilili.ui.watch

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill

/**
 * Audio and language filters above the server list.
 *
 * Each row is its own [focusGroup] so a D-pad moves along the chips with left/right and steps
 * between the rows — and on to the list below — with up/down, instead of wandering diagonally
 * into the servers.
 *
 * The language row only appears once something has been discovered, and says so while sources are
 * still being checked: an incomplete list that looks complete is worse than no list, because the
 * viewer concludes their language is unavailable and stops looking.
 *
 * @param isTopOfWindow set when nothing above this row can take focus — the television server
 *   dialog, where it is the first thing a D-pad reaches. Up then has no candidate, and Compose does
 *   not stop there: the search leaves the dialog's scrollable group and re-enters it from the
 *   opposite edge, landing on the **last** cell of the server grid, half of it clipped by the
 *   bottom of the screen. Up from there repeats the same jump, so the remote is stuck and only Down
 *   walks back. Reported by a tester on 2026-09-15 as "press up all the way, the cursor will go to
 *   the bottom right and can't press up to navigate", and reproduced exactly on the TV emulator.
 *   Cancelling the search is the honest answer: this row *is* the top, so Up has nothing to do.
 */
@OptIn(ExperimentalComposeUiApi::class) // FocusRequester.Cancel; see [isTopOfWindow].
@Composable
fun SourceFilterRow(
    audio: AudioFilter,
    onAudioChange: (AudioFilter) -> Unit,
    languages: List<String>,
    language: String?,
    onLanguageChange: (String?) -> Unit,
    stillChecking: Boolean,
    modifier: Modifier = Modifier,
    isTopOfWindow: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                // Only the audio row, never the Column: the language row below it has somewhere
                // real to go upwards, and a cancel on the parent would be inherited by both.
                //
                // And after `focusGroup()`, never before it. A chip collects focus properties by
                // walking outwards *until the first focus target*, which `focusGroup` is — so the
                // same modifier one position earlier is read by the group and by nothing inside it,
                // which is to say by nothing that is ever focused. That ordering was tried on the
                // emulator first and left the bug exactly as it was.
                .then(if (isTopOfWindow) Modifier.focusProperties { up = FocusRequester.Cancel } else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AudioFilter.entries.forEach { option ->
                val controlFocus1 = rememberFocusFill()
                FilterChip(
                    colors = controlFocus1.chipColors(FilterChipDefaults.filterChipColors()),
                    selected = audio == option,
                    onClick = { onAudioChange(option) },
                    label = { Text(option.label) },
                    modifier = Modifier.focusFill(controlFocus1, RoundedCornerShape(8.dp)),
                )
            }
        }

        // Shown while still checking even with nothing found yet, so an empty row reads as "still
        // looking" rather than "this title has no languages".
        if (languages.isNotEmpty() || stillChecking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (stillChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    if (stillChecking) "Checking servers for languages…" else "Language",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val controlFocus2 = rememberFocusFill()
                FilterChip(
                    colors = controlFocus2.chipColors(FilterChipDefaults.filterChipColors()),
                    selected = language == null,
                    onClick = { onLanguageChange(null) },
                    label = { Text("Any") },
                    modifier = Modifier.focusFill(controlFocus2, RoundedCornerShape(8.dp)),
                )
                languages.forEach { option ->
                    val controlFocus3 = rememberFocusFill()
                    FilterChip(
                        colors = controlFocus3.chipColors(FilterChipDefaults.filterChipColors()),
                        selected = language == option,
                        onClick = { onLanguageChange(if (language == option) null else option) },
                        label = { Text(option) },
                        modifier = Modifier
                            .padding(end = 0.dp)
                            .focusFill(controlFocus3, RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}
