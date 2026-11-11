package com.anilili.ui.components

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.anilili.data.auth.AccountService
import com.anilili.data.library.CustomList
import com.anilili.data.library.CustomListStore
import com.anilili.data.library.MAX_CUSTOM_LIST_NAME
import com.anilili.data.library.normalizedListName
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.rememberTvFocusTarget
import com.anilili.ui.dialogs.MidnightConfirmDialog
import com.anilili.ui.dialogs.MidnightFilledAction
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightModal
import com.anilili.ui.dialogs.MidnightModalTitle
import com.anilili.ui.dialogs.MidnightNoticeTone
import com.anilili.ui.dialogs.MidnightQuietAction
import com.anilili.ui.dialogs.midnightInitialFocus
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay

/** Where a list lives, in the words the naming and managing dialogs use. */
internal fun customListHome(service: AccountService?): String = when (service) {
    AccountService.ANILIST -> "It syncs to your AniList account as a custom list."
    AccountService.MAL -> "MyAnimeList has no custom lists, so it syncs as a tag on each title in it."
    null -> "It stays on this device until you sign in to AniList or MyAnimeList."
}

/**
 * Asks for a list's name, for a new list or a renamed one. [onSave] answers with the name it stored,
 * or null to keep the dialog open — the name is taken, or has nothing left once tidied.
 */
@Composable
internal fun CustomListNameDialog(
    title: String,
    confirmText: String,
    onSave: (String) -> String?,
    onDismiss: () -> Unit,
    initial: String = "",
) {
    val device = LocalAppDeviceProfile.current
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldFocus = rememberTvFocusTarget()
    val phoneFocus = remember { FocusRequester() }
    fun save() {
        val name = normalizedListName(value)
        if (name == null) {
            error = "Give the list a name."
            return
        }
        if (onSave(name) == null) error = "You already have a list called $name."
    }
    MidnightModal(onDismiss = onDismiss) {
        MidnightModalTitle(
            kicker = "My lists",
            title = title,
            body = customListHome(AccountService.active),
        )
        val fieldModifier = Modifier.fillMaxWidth().padding(top = 14.dp)
        if (device.isTv) {
            TvNativeTextField(
                value = value,
                onValueChange = {
                    value = it.take(MAX_CUSTOM_LIST_NAME)
                    error = null
                },
                hint = "List name",
                modifier = fieldModifier,
                imeAction = EditorInfo.IME_ACTION_DONE,
                onImeAction = ::save,
                tvFocusTarget = fieldFocus,
                autoOpenKeyboard = true,
                autoOpenToken = title,
            )
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it.take(MAX_CUSTOM_LIST_NAME)
                    error = null
                },
                modifier = fieldModifier.focusRequester(phoneFocus),
                placeholder = { Text("List name") },
                singleLine = true,
                shape = RoundedCornerShape(9.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
        }
        error?.let { MidnightInlineNotice(it, tone = MidnightNoticeTone.ERROR) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MidnightQuietAction(text = "Cancel", onClick = onDismiss, modifier = Modifier.padding(end = 6.dp))
            MidnightFilledAction(text = confirmText, onClick = ::save)
        }
    }
    LaunchedEffect(Unit) {
        if (device.isTv) {
            delay(80)
            runCatching { fieldFocus.requester.requestFocus() }
        } else {
            awaitFrame()
            runCatching { phoneFocus.requestFocus() }
        }
    }
}

/**
 * Rename or delete one list: the header's Edit on My List. Each choice opens its own dialog in place
 * of this one rather than on top of it, which a remote's Back key cannot sort out.
 */
@Composable
internal fun CustomListManageDialog(list: CustomList, onDismiss: () -> Unit) {
    var mode by remember(list.name) { mutableStateOf(ManageMode.CHOOSE) }
    val service = AccountService.active
    when (mode) {
        ManageMode.RENAME -> CustomListNameDialog(
            title = "Rename ${list.name}",
            confirmText = "Rename",
            initial = list.name,
            onSave = { name -> CustomListStore.rename(list.name, name)?.also { onDismiss() } },
            onDismiss = onDismiss,
        )
        ManageMode.DELETE -> MidnightConfirmDialog(
            kicker = "Delete list",
            title = "Delete ${list.name}?",
            body = buildString {
                append(
                    when (list.entries.size) {
                        0 -> "The list goes. It is empty. "
                        1 -> "The list goes, and the one anime in it leaves it. "
                        else -> "The list goes, and the ${list.entries.size} anime in it leave it. "
                    },
                )
                append(
                    when (service) {
                        AccountService.ANILIST -> "It is deleted from your AniList account too. Titles " +
                            "that were only in this list come off AniList with it; anything you are " +
                            "watching, planning or finished keeps its status."
                        AccountService.MAL -> "The tag is taken off those titles on MyAnimeList too. " +
                            "Their status, progress and score stay."
                        null -> "Nothing else in your library changes."
                    },
                )
            },
            confirmText = "Delete",
            onConfirm = {
                CustomListStore.delete(list.name)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        ManageMode.CHOOSE -> MidnightModal(onDismiss = onDismiss) {
            MidnightModalTitle(
                kicker = "My lists",
                title = list.name,
                body = "${list.entries.size} anime. ${customListHome(service)}",
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MidnightQuietAction(
                    text = "Delete",
                    onClick = { mode = ManageMode.DELETE },
                    destructive = true,
                    modifier = Modifier.padding(end = 6.dp),
                )
                MidnightQuietAction(
                    text = "Close",
                    onClick = onDismiss,
                    modifier = Modifier.padding(end = 6.dp),
                )
                MidnightFilledAction(
                    text = "Rename",
                    onClick = { mode = ManageMode.RENAME },
                    modifier = Modifier.midnightInitialFocus(),
                )
            }
        }
    }
}

private enum class ManageMode { CHOOSE, RENAME, DELETE }

/** A new list made from inside a title's menu, which also puts that title in it. */
internal fun createListWith(name: String, entry: com.anilili.data.library.WatchlistEntry): String? {
    val created = CustomListStore.create(name) ?: return null
    CustomListStore.setMember(created, entry, true)
    return created
}

/** Whether [anilistId] is in [list], for the checkboxes that show it. */
internal fun CustomList.holds(anilistId: Int): Boolean = entries.any { it.anilistId == anilistId }
