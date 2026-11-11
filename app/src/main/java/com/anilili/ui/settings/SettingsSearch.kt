package com.anilili.ui.settings

import com.anilili.ui.theme.appCanvas
import com.anilili.ui.theme.canvasColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anilili.data.settings.SettingsSearchHistory
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.PageSearchField
import com.anilili.ui.components.TvOnScreenKeyboard
import com.anilili.ui.theme.LocalMidnightAccent
import com.anilili.ui.theme.Midnight
import kotlinx.coroutines.android.awaitFrame

/**
 * Search across every setting, from the top of the Settings index.
 *
 * The page is a grid of eleven categories, which is a fast way to browse and a slow way to find one
 * switch whose category you cannot guess — "is 'Blur episode images' under Content or Player?" is
 * exactly the question this removes.
 *
 * Suggestions cover both halves of the ask: the settings a query matches, and the queries this
 * viewer has run before. Past queries appear only as completions for something already being typed
 * — the fastest finish for someone halfway through "subtit" is usually what they typed last time —
 * and never on an untouched field, where they would push the categories down to advertise a history
 * nobody asked to see.
 *
 * Picking a result opens the section holding it and marks the row it named, because a section is a
 * page of rows that all look alike and landing on it otherwise means reading every title over again
 * to find the one just asked for. The destination list resolves the title to its actual item key,
 * scrolls there, then shows the mark. The search index does not need to know layout positions.
 */
@Composable
internal fun SettingsSearch(
    entries: List<SettingsSearchEntry>,
    spanish: Boolean,
    onOpenSection: (SettingsSearchEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val history by SettingsSearchHistory.history.collectAsState()
    val results = remember(query, entries) { searchSettings(query, entries) }
    // Only ever a completion for something already being typed. An untouched field shows the
    // category grid and nothing else: a list of what this person last went looking for is not what
    // the page is for, and it pushed the categories down to make room for itself.
    val recent = remember(query, history) {
        val term = query.trim()
        if (term.isEmpty()) {
            emptyList()
        } else {
            history.filter {
                it.contains(term, ignoreCase = true) && !it.equals(term, ignoreCase = true)
            }
        }.take(3)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PageSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = if (spanish) "Buscar ajustes" else "Search settings",
            clearLabel = if (spanish) "Borrar búsqueda" else "Clear search",
        )

        recent.forEach { term ->
            SettingsSearchRow(
                title = term,
                subtitle = if (spanish) "Búsqueda reciente" else "Recent search",
                leading = {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = Midnight.Text3,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailing = {
                    val extraFocus1 = rememberFocusFill()
                    IconButton(
                        colors = extraFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = { SettingsSearchHistory.remove(term) }, modifier = Modifier.focusFill(extraFocus1, CircleShape),) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = if (spanish) "Quitar" else "Remove",
                            tint = extraFocus1.content(Midnight.Text3),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                // Completing the field rather than jumping: the viewer asked for the words back,
                // and the settings underneath update as soon as they land.
                onClick = { query = term },
            )
        }

        results.forEach { entry ->
            SettingsSearchRow(
                title = entry.title,
                subtitle = entry.sectionTitle,
                onClick = {
                    SettingsSearchHistory.record(query)
                    query = ""
                    onOpenSection(entry)
                },
            )
        }

        if (query.isNotBlank() && results.isEmpty()) {
            Text(
                if (spanish) "Ningún ajuste coincide." else "No setting matches that.",
                style = MaterialTheme.typography.bodySmall,
                color = Midnight.Text3,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * The bar that stands in for the search field on a television, above the category grid.
 *
 * It looks like the field it replaces and behaves like a button, which is the whole point: a real
 * text field is a focus stop that opens the keyboard by being landed on, so the D-pad could not
 * travel past it to the categories behind — Back closed the keyboard and handed focus straight
 * back, Down opened it again, and the whole of Settings sat behind that loop. Nothing here opens
 * until Select asks it to.
 */
@Composable
internal fun TvSettingsSearchBar(
    spanish: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fillFocus1 = rememberFocusFill()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusFill(fillFocus1, RoundedCornerShape(10.dp), focusedScale = 1.02f)
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = fillFocus1.background(MaterialTheme.colorScheme.surface),
        contentColor = fillFocus1.content(LocalContentColor.current),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = fillFocus1.content(Midnight.Text2),
                modifier = Modifier.size(20.dp),
            )
            Text(
                if (spanish) "Buscar ajustes" else "Search settings",
                style = MaterialTheme.typography.bodyLarge,
                color = fillFocus1.content(Midnight.Text2),
            )
        }
    }
}

/**
 * The same search, given the whole screen, with the keys beside the results instead of over them.
 *
 * Results on the left, query and keyboard on the right, in one window — not a dialog with a system
 * keyboard on top of it, which is what this was and why it needed replacing: the IME docked across
 * the lower half of the screen and buried everything the viewer was typing towards, leaving two
 * visible results out of six on a card that was itself only 62% of the width. Nothing overlaps
 * here, so the list can be as long as the screen and every match is legible while it is being
 * narrowed.
 *
 * Blank query lists every setting rather than nothing, which makes this an index as well as a
 * search: eleven categories are a fast way to browse and a slow way to find one switch whose
 * category you cannot guess, and a flat list of all of them answers the second case without typing.
 *
 * Back leaves. Compose maps it to `FocusDirection.Exit` and a nested `BackHandler` loses to the
 * host screen's, so it is claimed in preview here — the same treatment TV pairing needs.
 */
@Composable
internal fun TvSettingsSearchPane(
    entries: List<SettingsSearchEntry>,
    spanish: Boolean,
    onOpenSection: (SettingsSearchEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val history by SettingsSearchHistory.history.collectAsState()
    // Everything, best first, rather than a handful: the list has a whole screen column to itself
    // and a viewer narrowing a search should see the narrowing happen, not a fixed six.
    val results = remember(query, entries) {
        if (query.isBlank()) {
            entries.sortedWith(compareBy({ it.sectionTitle }, { it.title }))
        } else {
            searchSettings(query, entries, limit = 40)
        }
    }
    val recent = remember(query, history) {
        val term = query.trim()
        if (term.isEmpty()) {
            emptyList()
        } else {
            history.filter {
                it.contains(term, ignoreCase = true) && !it.equals(term, ignoreCase = true)
            }
        }.take(2)
    }
    val firstKey = remember { FocusRequester() }
    // A new query is a new list, so it starts at the top. Left where the last one was, the first
    // and best match sat scrolled off above the header.
    val resultsState = rememberLazyListState()
    LaunchedEffect(query) { resultsState.scrollToItem(0) }
    // The keyboard is what the viewer came here to use, so the remote starts on it. Guarded because
    // a requester whose node has not attached yet throws rather than returning false.
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { firstKey.requestFocus() }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val isBack = event.key == Key.Back || event.key == Key.Escape
                if (isBack && event.type == KeyEventType.KeyUp) {
                    onDismiss()
                    true
                } else {
                    isBack
                }
            },
        color = canvasColor(),
    ) {
        Row(
            Modifier.fillMaxSize().appCanvas().padding(horizontal = 34.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // Each pane is a focus group so the two only exchange focus sideways. Without them the
            // panes are one flat field of candidates and Down off the last result went to whatever
            // sat lowest on the screen, which is the keyboard's space bar: a list that ends by
            // throwing the remote across the screen.
            Column(Modifier.weight(1f).fillMaxHeight().focusGroup()) {
                Text(
                    when {
                        query.isBlank() -> if (spanish) "Todos los ajustes" else "All settings"
                        results.isEmpty() -> if (spanish) "Sin resultados" else "No results"
                        else -> if (spanish) "Resultados" else "Results"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Midnight.Text2,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                if (query.isNotBlank() && results.isEmpty() && recent.isEmpty()) {
                    Text(
                        if (spanish) "Ningún ajuste coincide." else "No setting matches that.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Midnight.Text3,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                LazyColumn(
                    state = resultsState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(recent, key = { "recent-$it" }) { term ->
                        SettingsSearchRow(
                            title = term,
                            subtitle = if (spanish) "Búsqueda reciente" else "Recent search",
                            leading = {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = null,
                                    tint = LocalContentColor.current,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = { query = term },
                        )
                    }
                    items(results, key = { "${it.sectionId}-${it.title}" }) { entry ->
                        SettingsSearchRow(
                            title = entry.title,
                            subtitle = entry.sectionTitle,
                            onClick = {
                                SettingsSearchHistory.record(query)
                                onOpenSection(entry)
                            },
                        )
                    }
                }
            }
            Column(
                Modifier.weight(0.8f).fillMaxHeight().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TvSearchQueryField(query = query, spanish = spanish)
                TvOnScreenKeyboard(
                    onKey = { query += it },
                    onBackspace = { query = query.dropLast(1) },
                    onClear = { query = "" },
                    spanish = spanish,
                    firstKey = firstKey,
                    // Whatever the field left: the keys share it out between their rows, so the
                    // block ends at the bottom of the screen rather than past it.
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * What has been typed so far, drawn rather than edited.
 *
 * There is no text field here at all — the keys own the query, so this only has to show it. It
 * carries the accent border a focused field would, because it is the thing the keyboard is typing
 * into even though focus is never on it.
 */
@Composable
private fun TvSearchQueryField(query: String, spanish: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, LocalMidnightAccent.current),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Midnight.Text3,
                modifier = Modifier.size(20.dp),
            )
            Text(
                query.ifEmpty { if (spanish) "Buscar ajustes" else "Search settings" },
                style = MaterialTheme.typography.titleMedium,
                color = if (query.isEmpty()) Midnight.Text3 else Midnight.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsSearchRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val fillFocus2 = rememberFocusFill()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .focusFill(fillFocus2, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = fillFocus2.background(MaterialTheme.colorScheme.surface),
        contentColor = fillFocus2.content(LocalContentColor.current),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = fillFocus2.content(LocalContentColor.current),
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = fillFocus2.content(Midnight.Text3),
                )
            }
            trailing?.invoke()
        }
    }
}
