package com.anilili.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill

/**
 * The keyboard a remote types on, drawn by the app instead of raised from the system.
 *
 * The platform IME is the wrong instrument on a television. It is a window of its own that docks
 * across the bottom of the screen, so whatever the viewer is typing *towards* — the results — ends
 * up behind it, and the app cannot lay out around it: Fire OS reports no IME insets at all, which
 * is the same gap that makes [com.anilili.ui.adaptive.TvNativeTextField] infer the keyboard's
 * presence from window focus. Drawing the keys instead makes them ordinary focusable composables.
 * The D-pad walks them, the results sit beside them, and nothing is ever covered by anything.
 *
 * Alphabetical rather than QWERTY. QWERTY earns its place on a surface with ten fingers and muscle
 * memory; on a D-pad every key costs the presses it takes to walk there, so the layout that wins is
 * the one whose next letter can be found without hunting.
 */
@Composable
fun TvOnScreenKeyboard(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    spanish: Boolean = false,
    firstKey: FocusRequester? = null,
) {
    // Rows take their height by weight rather than from a square key, so the block is exactly as
    // tall as the space it is given. Sizing keys by aspect ratio instead put the last row — space
    // and backspace, the two a typo needs — off the bottom of a 1080p television.
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        KeyboardRows.forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(KeyGap),
            ) {
                row.forEachIndexed { columnIndex, character ->
                    KeyboardKey(
                        onClick = { onKey(character) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(
                                if (rowIndex == 0 && columnIndex == 0 && firstKey != null) {
                                    Modifier.focusRequester(firstKey)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Text(
                            character.uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KeyGap),
        ) {
            KeyboardKey(
                onClick = { onKey(' ') },
                modifier = Modifier.weight(3f).height(ActionKeyHeight),
            ) {
                Text(
                    if (spanish) "Espacio" else "Space",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            KeyboardKey(
                onClick = onBackspace,
                modifier = Modifier.weight(1.5f).height(ActionKeyHeight),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = if (spanish) "Borrar carácter" else "Delete character",
                )
            }
            KeyboardKey(
                onClick = onClear,
                modifier = Modifier.weight(1.5f).height(ActionKeyHeight),
            ) {
                Text(
                    if (spanish) "Vaciar" else "Clear",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun KeyboardKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val fillFocus1 = rememberFocusFill()
    Surface(
        modifier = modifier
            // A smaller lift than the rest of the app's focus treatment: keys sit a few pixels
            // apart, and the standard 1.06 grows a key into the gap and over its neighbours.
            .focusFill(fillFocus1, KeyShape, focusedScale = 1.04f)
            .clickable(role = Role.Button, onClick = onClick),
        shape = KeyShape,
        color = fillFocus1.background(MaterialTheme.colorScheme.surface),
        contentColor = fillFocus1.content(LocalContentColor.current),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * Letters, then digits, in reading order six to a row.
 *
 * Six columns because it divides both alphabet and digits evenly and keeps the block roughly square
 * next to a results list, so no key is more than three presses from the row above or below it.
 */
private val KeyboardRows = listOf(
    "abcdef",
    "ghijkl",
    "mnopqr",
    "stuvwx",
    "yz0123",
    "456789",
)

private val KeyShape = RoundedCornerShape(8.dp)
private val KeyGap = 6.dp
private val ActionKeyHeight = 44.dp
