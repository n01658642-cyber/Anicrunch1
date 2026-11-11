package com.anilili.ui.watch

import androidx.compose.runtime.Composable
import com.anilili.ui.dialogs.MidnightChoiceRow
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightSectionLabel

/**
 * What a source looks like to the player, without dragging the watch screen's own model in here.
 *
 * [detail] carries whatever distinguishes one entry from the next — the audio variant, or a note
 * that this server does not have the episode — because a bare list of server names is not enough to
 * choose between them.
 */
data class PlayerSourceOption(
    val id: String,
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val enabled: Boolean,
    val onSelect: () -> Unit,
)

/**
 * Switching server without leaving the picture.
 *
 * The source list already exists on the watch screen, below the player — which meant that changing
 * a server mid-episode required backing out of fullscreen, finding the row, and going back in. That
 * is the one moment it is most likely to be wanted: a stream that has stalled or refused is exactly
 * when a viewer wants a different one, and the player is where they are standing when they find out.
 */
@Composable
internal fun PlayerSourcesSheet(
    onDismiss: () -> Unit,
    sources: List<PlayerSourceOption>,
    isLoading: Boolean = false,
    fullscreen: Boolean = false,
) {
    PlayerOptionsSheet(
        title = "Sources",
        paneTitle = "Video sources",
        closeDescription = "Close sources",
        onDismiss = onDismiss,
        fullscreen = fullscreen,
    ) {
        MidnightSectionLabel("Server")
        if (sources.isEmpty()) {
            MidnightInlineNotice(
                if (isLoading) {
                    "Looking for servers that carry this episode…"
                } else {
                    "No other server has this episode."
                },
            )
        } else {
            sources.forEach { source ->
                MidnightChoiceRow(
                    title = source.label,
                    subtitle = source.detail,
                    selected = source.selected,
                    enabled = source.enabled,
                    onSelect = source.onSelect,
                    claimInitialFocus = false,
                )
            }
            if (isLoading) MidnightHelper("Still checking the remaining servers…")
        }
    }
}

/**
 * How a player's own control row opens the server picker, which the screen above it owns.
 *
 * A composition local rather than a parameter, and deliberately so: [EmbedWebView] already takes
 * thirty-five arguments, and adding a thirty-sixth pushed the method Compose generates for it past
 * what ART's verifier will accept — the class was rejected outright at class-load with a
 * `VerifyError` naming a register, and the embed player took the whole app down with it on the
 * first episode that resolved to an embed. Nothing about the feature needs a parameter: there is
 * exactly one picker on screen and every player inside it wants the same one.
 */
val LocalPlayerSourcePicker = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/** The episode's download control as the player's own row draws it. */
class PlayerDownloadAction(val downloaded: Boolean, val onClick: () -> Unit)

/**
 * How a television player's control row offers the episode's download, which the screen above it
 * owns — a local for the same reason as [LocalPlayerSourcePicker]. On television the Watch route
 * *is* the player, so the summary under it that holds the download button on a phone is never on
 * screen; without this the row is the only way in and TV had no download at all. Null while the
 * episode has nothing to download.
 */
val LocalPlayerDownload = androidx.compose.runtime.compositionLocalOf<PlayerDownloadAction?> { null }
