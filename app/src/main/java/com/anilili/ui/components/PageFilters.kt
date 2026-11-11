package com.anilili.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill

/** One entry in a [PageFilterPill] menu. A null [value] is the "no filter" choice. */
data class PageFilterOption(val value: String?, val label: String)

/**
 * The filter row shared by the pages that list things the viewer already has.
 *
 * Two named pills and a compact sort control, in that order. The pills share the width and the
 * control takes only what it needs: it is an icon, so it has nothing to truncate, while a pill
 * squeezed to a third of the row would lose the value it exists to display.
 */
@Composable
fun PageFilterRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One rounded filter pill: current value, a chevron, and its menu. */
@Composable
fun PageFilterPill(
    label: String,
    options: List<PageFilterOption>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        val fillFocus1 = rememberFocusFill()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .focusFill(fillFocus1, RoundedCornerShape(50))
                .clickable { expanded = true },
            shape = RoundedCornerShape(50),
            color = fillFocus1.background(MaterialTheme.colorScheme.surface),
            contentColor = fillFocus1.content(LocalContentColor.current),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = fillFocus1.content(LocalContentColor.current),
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = fillFocus1.content(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { expanded = false; onSelect(option.value) },
                )
            }
        }
    }
}

/**
 * The sort control at the end of a filter row.
 *
 * It holds whatever the page wants to keep off the row — sort order, and any filter nobody changes
 * twice in a session. The menu is the caller's, because the groups differ per page; what is shared
 * is that they all live behind one icon rather than competing for a phone's width.
 */
@Composable
fun PageSortControl(
    contentDescription: String,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        val fillFocus2 = rememberFocusFill()
        Surface(
            modifier = Modifier
                .focusFill(fillFocus2, RoundedCornerShape(50))
                .clickable { expanded = true }
                .semantics { this.contentDescription = contentDescription },
            shape = RoundedCornerShape(50),
            color = fillFocus2.background(MaterialTheme.colorScheme.surface),
            contentColor = fillFocus2.content(LocalContentColor.current),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(20.dp), tint = fillFocus2.content(LocalContentColor.current))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = fillFocus2.content(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menu { expanded = false }
        }
    }
}

/** A heading inside a [PageSortControl] menu, so its groups read as groups. */
@Composable
fun PageMenuSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** One row of a [PageSortControl] menu, ticked when it is the active choice. */
@Composable
fun PageMenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
        onClick = onClick,
    )
}

/** The small dark label that sits over the corner of a poster; see [PosterTag]. */
@Composable
fun CornerBadge(text: String, modifier: Modifier = Modifier) {
    PosterTag(text, modifier)
}
