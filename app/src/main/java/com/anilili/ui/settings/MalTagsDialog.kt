package com.anilili.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.anilili.data.library.CustomListStore
import com.anilili.data.library.listKey
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightModalTitle
import com.anilili.ui.dialogs.MidnightSelectionRow

/**
 * Which of the MyAnimeList account's tags show up as lists on My List.
 *
 * On MAL a custom list is a tag, so every tag the viewer ever used for their own bookkeeping comes
 * with the account. Unticking one only stops it being shown here: the tag stays on MyAnimeList as
 * it was, and the app writes it back unchanged whenever it touches a title that carries it.
 */
@Composable
internal fun MalTagsDialog(onDismiss: () -> Unit) {
    val tags by CustomListStore.malTags.collectAsState()
    val hidden by SettingsStore.hiddenMalTags.collectAsState()
    fun isHidden(tag: String) = hidden.any { listKey(it) == listKey(tag) }
    MidnightDialogHost(
        onDismiss = onDismiss,
        header = {
            MidnightModalTitle(
                kicker = "MyAnimeList",
                title = "Tags shown as lists",
                body = "MyAnimeList has no custom lists, so Anilili shows your tags as lists instead. " +
                    "Untick a tag to keep it off My List — it stays on MyAnimeList untouched.",
            )
        },
        footer = { MidnightDialogFooter(quietText = "Done", onQuiet = onDismiss) },
    ) {
        if (tags.isEmpty()) {
            MidnightInlineNotice(
                "No tags on your MyAnimeList yet. Lists you make here become tags, and any tags " +
                    "you add on MyAnimeList appear after the next sync.",
            )
            return@MidnightDialogHost
        }
        MidnightHelper("${tags.count { !isHidden(it) }} of ${tags.size} shown")
        tags.forEachIndexed { index, tag ->
            MidnightSelectionRow(
                title = tag,
                selected = !isHidden(tag),
                onSelectedChange = { show ->
                    val others = hidden.filterNot { listKey(it) == listKey(tag) }.toSet()
                    SettingsStore.setHiddenMalTags(if (show) others else others + tag)
                    // Takes effect on the next refresh; asking for one now makes it immediate.
                    com.anilili.data.library.LibraryStore.refreshRemoteLibrary(force = true)
                },
                claimInitialFocus = index == 0,
            )
        }
    }
}
