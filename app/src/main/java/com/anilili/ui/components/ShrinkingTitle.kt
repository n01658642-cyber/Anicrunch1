package com.anilili.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * A title that gives up size before it gives up words.
 *
 * A long name — "That Time I Got Reincarnated as a Slime Season 4" — ran past two lines at the
 * heading size and was cut with an ellipsis, and what got cut was the end: the season number, the
 * one part that says which entry you are looking at. Rather than clip that away, the text steps
 * down a point at a time until it fits, and only ellipsizes if it is still too long at
 * [minFontSize] — so shrinking is preferred to truncation, and truncation stays possible for a
 * title no size could fit.
 *
 * The measured passes are not drawn. Compose lays the text out, reports the overflow, and this
 * composable shrinks and asks again; painting each attempt would show the title flickering through
 * two or three sizes on every screen.
 */
@Composable
fun ShrinkingTitle(
    text: String,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    minFontSize: TextUnit = 13.sp,
    stepSize: TextUnit = 1.sp,
) {
    val startSize = style.fontSize
    // A style with no explicit size has nothing to step down from; draw it as-is rather than
    // guessing at a base the theme did not state.
    if (!startSize.isSpecified) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }

    var fontSize by remember(text, startSize, maxLines) { mutableStateOf(startSize) }
    var settled by remember(text, startSize, maxLines) { mutableStateOf(false) }
    val lineHeight = if (style.lineHeight.isSpecified && startSize.value > 0f) {
        // Keep the leading proportional to whatever size we end up at, or a shrunken title keeps
        // the original line spacing and still overflows the box vertically.
        (style.lineHeight.value * (fontSize.value / startSize.value)).sp
    } else {
        style.lineHeight
    }

    Text(
        text = text,
        style = style.copy(fontSize = fontSize, lineHeight = lineHeight),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.drawWithContent { if (settled) drawContent() },
        onTextLayout = { result ->
            when {
                !result.hasVisualOverflow -> settled = true
                fontSize.value - stepSize.value >= minFontSize.value ->
                    fontSize = (fontSize.value - stepSize.value).sp
                // Out of room to shrink: let the ellipsis do its job.
                else -> settled = true
            }
        },
    )
}
