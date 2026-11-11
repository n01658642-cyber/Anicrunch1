package com.anilili.ui.components

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightMediaHeader

/**
 * The search at the top of a page that lists things the viewer already has, in whichever form the
 * device it is running on can actually work.
 *
 * A text field is a focus stop, and on television focusing one is what opens the keyboard. That is
 * fine for a thumb, which chooses to touch the field, and a trap for a remote, which has no way
 * past it: down from the top of the page raised the keyboard, Back closed the keyboard and handed
 * focus straight back to the field, and down raised it again — with the whole of the page's content
 * behind the loop. Settings shipped that exact bug and was reported from a Fire Stick within the
 * hour; these two pages carried the same field and the same trap.
 *
 * So television gets the field's twin rather than the field: something the same size in the same
 * place that the D-pad can travel over harmlessly, and that opens the field in a dialog when it is
 * pressed on purpose. What matters is not where the keyboard is but why it came — asked for rather
 * than walked into — and Back then has two rungs to climb down, keyboard and then dialog, instead
 * of a loop. Touch is unchanged.
 *
 * Shared by My List and Downloads so the two pages cannot drift into two different-looking answers
 * to the same question — which is exactly what happened to their headers before this existed.
 */
@Composable
fun PageSearch(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    resultSummary: String? = null,
) {
    if (!LocalAppDeviceProfile.current.isTv) {
        PageSearchField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            clearLabel = clearLabel,
            modifier = modifier,
        )
        return
    }

    var editing by remember { mutableStateOf(false) }
    PageSearchButton(
        value = value,
        placeholder = placeholder,
        onClick = { editing = true },
        modifier = modifier,
    )
    if (editing) {
        PageSearchDialog(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            clearLabel = clearLabel,
            resultSummary = resultSummary,
            onDismiss = { editing = false },
        )
    }
}

/**
 * The search field itself.
 *
 * Filled and fully rounded rather than the outlined box the rest of the app uses for forms. These
 * fields sit alone under a large page title with no card around them, and a hairline rectangle
 * there reads as an unfinished form rather than as a place to type.
 *
 * Touch only. Pages reach it through [PageSearch], which is what decides that; a call from a screen
 * that television also runs is the keyboard trap described there. The one direct caller is the
 * Settings search, whose own call site is already held behind the same test.
 */
@Composable
fun PageSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                val extraFocus1 = rememberFocusFill()
                IconButton(
                    colors = extraFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = { onValueChange("") }, modifier = Modifier.focusFill(extraFocus1, CircleShape),) {
                    Icon(Icons.Default.Close, contentDescription = clearLabel)
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(50),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        singleLine = true,
    )
}

/**
 * What television sees in the field's place: the same shape, holding the query rather than editing
 * it.
 *
 * Built to the field's dimensions on purpose. The page above and below it is laid out for something
 * 56dp tall and fully rounded, and a remote that has learned where the search sits should find it
 * in the same place — the only difference being that landing on it does nothing until Select.
 *
 * It shows the current query rather than the placeholder whenever there is one, because the list
 * underneath is filtered by it and nothing else on the page says so.
 */
@Composable
private fun PageSearchButton(
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val fillFocus1 = rememberFocusFill()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusFill(fillFocus1, shape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = shape,
        color = fillFocus1.background(MaterialTheme.colorScheme.surface),
        contentColor = fillFocus1.content(LocalContentColor.current),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = fillFocus1.content(MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Text(
                value.ifEmpty { placeholder },
                style = MaterialTheme.typography.bodyLarge,
                color = fillFocus1.content(if (value.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The field, once television has asked for it.
 *
 * [TvNativeTextField] rather than a Compose field because Fire TV's IME will acknowledge a show
 * request without ever attaching to one, and because it leaves the keyboard closed until Select
 * begins editing — so even here the keyboard is never raised by focus alone.
 *
 * The page keeps filtering live behind the dialog, which is why the match count is repeated inside
 * it: the count is the only part of the filtered page a fullscreen TV keyboard leaves visible, and
 * without it there is no way to tell a query that found nothing from one still being typed.
 */
@Composable
private fun PageSearchDialog(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    resultSummary: String?,
    onDismiss: () -> Unit,
) {
    MidnightDialogHost(
        onDismiss = onDismiss,
        header = {
            MidnightMediaHeader(
                kicker = "Filter",
                title = placeholder,
                metadata = null,
                onDismiss = null,
            )
        },
    ) {
        TvNativeTextField(
            value = value,
            onValueChange = onValueChange,
            hint = placeholder,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            imeAction = EditorInfo.IME_ACTION_SEARCH,
            // Done on the keyboard means "I have typed it" — the results are the page behind this,
            // so the useful thing to do with that press is get out of the way and show them.
            onImeAction = onDismiss,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (resultSummary != null) {
                Text(
                    resultSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            if (value.isNotEmpty()) {
                // The field's own trailing clear is an icon inside a text box, which a remote
                // cannot reach; this is the same affordance as something the D-pad can land on.
                val extraFocus2 = rememberFocusFill()
                TextButton(
                    colors = extraFocus2.buttonColors(ButtonDefaults.textButtonColors()),onClick = { onValueChange("") }, modifier = Modifier.focusFill(extraFocus2, RoundedCornerShape(24.dp)),) { Text(clearLabel) }
            }
        }
    }
}
