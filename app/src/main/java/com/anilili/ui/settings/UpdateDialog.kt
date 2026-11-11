package com.anilili.ui.settings

import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anilili.data.settings.SettingsStore
import com.anilili.data.update.UpdateManager
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.dialogs.MidnightFilledAction
import com.anilili.ui.dialogs.MidnightModal
import com.anilili.ui.dialogs.MidnightModalTitle
import com.anilili.ui.dialogs.MidnightPage
import com.anilili.ui.dialogs.MidnightProgressLine
import com.anilili.ui.dialogs.MidnightQuietAction
import com.anilili.ui.dialogs.MidnightRule
import com.anilili.ui.dialogs.midnightInitialFocus
import com.anilili.ui.theme.Midnight

/**
 * Renders the update flow dialogs (available -> downloading -> install / failed)
 * driven by [UpdateManager.state]. Mounted once at the app root.
 *
 * All five states share the one centered modal (design/midnight-anime/design.md §21): kicker,
 * version, changelog between rules rather than in a nested card, then a quiet way out and one
 * purple action.
 */
@Composable
fun UpdatePromptHost() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()

    when (val s = state) {
        is UpdateManager.State.Available -> {
            val notes = remember(s.update.changelog) { parseReleaseNotes(s.update.changelog) }
            var expanded by rememberSaveable { mutableStateOf(false) }
            // A phone reads the whole release on a page of its own, the actions pinned under it,
            // rather than stretching the prompt to the height of the screen. A television keeps
            // expanding in place: the remote already has the prompt's buttons in reach (§21).
            if (expanded && !LocalAppDeviceProfile.current.isTv) {
                MidnightPage(onDismiss = { expanded = false }) {
                    MidnightModalTitle(
                        kicker = "What's new",
                        title = "Anilili ${UpdateManager.offeredLabel(s.update)}",
                        body = "You have ${UpdateManager.installedLabel(s.update)}.",
                    )
                    MidnightRule(Modifier.padding(top = 12.dp))
                    ReleaseNotes(
                        blocks = notes,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                    )
                    MidnightRule()
                    UpdateOfferActions()
                }
            } else {
                MidnightModal(onDismiss = UpdateManager::dismiss) {
                    MidnightModalTitle(
                        kicker = "Update available",
                        title = "Anilili ${UpdateManager.offeredLabel(s.update)}",
                        body = "You have ${UpdateManager.installedLabel(s.update)}.",
                    )
                    if (notes.isNotEmpty()) {
                        // Collapsed, the prompt shows the opening blocks - usually the first
                        // section's heading and a few bullets - and says there is more.
                        val truncated = notes.size > COLLAPSED_RELEASE_NOTE_BLOCKS
                        MidnightRule(Modifier.padding(top = 12.dp))
                        // Collapsed, the notes are a few lines so the dialog stays a small
                        // prompt; the whole release is one press away. Expanded (television
                        // only) they scroll rather than pushing the actions off the screen.
                        val notesScroll = rememberScrollState()
                        val scope = rememberCoroutineScope()
                        val stepPx = with(LocalDensity.current) { 96.dp.toPx() }
                        var notesFocused by remember { mutableStateOf(false) }
                        val notesFocus = remember { FocusRequester() }
                        // Opened, the cursor goes to the notes themselves, so the remote can read
                        // them: text has no focus stops of its own, and without one Down jumped
                        // straight from "Show less" to the buttons with most of the notes unseen.
                        LaunchedEffect(expanded) { if (expanded) runCatching { notesFocus.requestFocus() } }
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .then(
                                    if (expanded) {
                                        Modifier
                                            .focusRequester(notesFocus)
                                            .onFocusChanged { notesFocused = it.isFocused }
                                            // Up and Down scroll while there is more that way,
                                            // then let the cursor move on as usual.
                                            .onPreviewKeyEvent { event ->
                                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                                val delta = when (event.key) {
                                                    Key.DirectionDown -> if (notesScroll.canScrollForward) stepPx else return@onPreviewKeyEvent false
                                                    Key.DirectionUp -> if (notesScroll.canScrollBackward) -stepPx else return@onPreviewKeyEvent false
                                                    else -> return@onPreviewKeyEvent false
                                                }
                                                scope.launch { notesScroll.animateScrollBy(delta) }
                                                true
                                            }
                                            .focusable()
                                            .border(
                                                1.dp,
                                                if (notesFocused) Midnight.Line2 else Color.Transparent,
                                                RoundedCornerShape(8.dp),
                                            )
                                            .verticalScroll(notesScroll)
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            ReleaseNotes(
                                blocks = notes,
                                maxBlocks = if (expanded) Int.MAX_VALUE else COLLAPSED_RELEASE_NOTE_BLOCKS,
                                compact = true,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = if (expanded) 8.dp else 0.dp),
                            )
                        }
                        // Only worth a control when there is more to see; a short release note
                        // that already fits should not offer to "read more" and then show the
                        // same lines.
                        if (expanded || truncated) {
                            MidnightQuietAction(
                                text = if (expanded) "Show less" else "Read more",
                                onClick = { expanded = !expanded },
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        MidnightRule()
                    }
                    UpdateOfferActions()
                }
            }
        }

        is UpdateManager.State.Downloading -> MidnightModal(onDismiss = {}) {
            MidnightModalTitle(
                kicker = "Downloading",
                title = "Anilili ${UpdateManager.offeredLabel(s.update)}",
                body = null,
            )
            MidnightProgressLine(
                progress = s.progress,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        is UpdateManager.State.ReadyToInstall -> MidnightModal(onDismiss = UpdateManager::dismiss) {
            MidnightModalTitle(
                kicker = "Ready to install",
                title = "Anilili ${UpdateManager.offeredLabel(s.update)} is downloaded",
                body = "If nothing happens, allow installs from this app when Android asks, then " +
                    "choose Install again.",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                MidnightQuietAction(text = "Later", onClick = UpdateManager::dismiss)
                MidnightFilledAction(
                    text = "Install",
                    onClick = { UpdateManager.install(context) },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .midnightInitialFocus(),
                )
            }
        }

        is UpdateManager.State.UpToDate -> MidnightModal(onDismiss = UpdateManager::dismiss) {
            MidnightModalTitle(
                kicker = "No update available",
                title = if (s.latestPublishedVersion == UpdateManager.currentVersion) {
                    "You're on the latest version"
                } else {
                    "This build is already newer"
                },
                body = if (s.latestPublishedVersion == UpdateManager.currentVersion) {
                    "v${UpdateManager.currentVersion} is the latest published release."
                } else {
                    "You have v${UpdateManager.currentVersion}; the latest published release is " +
                        "v${s.latestPublishedVersion}."
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                MidnightFilledAction(
                    text = "Close",
                    onClick = UpdateManager::dismiss,
                    modifier = Modifier.midnightInitialFocus(),
                )
            }
        }

        is UpdateManager.State.Failed -> MidnightModal(onDismiss = UpdateManager::dismiss) {
            MidnightModalTitle(
                kicker = "Update failed",
                title = s.failure.message,
                // A dialog is one of the few places with room for the whole explanation, so the
                // detail — which install setting to allow, which host would not answer — is shown
                // here rather than waiting behind another tap.
                body = listOfNotNull(s.failure.detail, s.failure.code).joinToString("\n\n"),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                MidnightFilledAction(
                    text = "Close",
                    onClick = UpdateManager::dismiss,
                    modifier = Modifier.midnightInitialFocus(),
                )
            }
        }

        else -> Unit
    }
}

/** "Don't remind me", "Not now" and "Update", shared by the prompt and the full release notes. */
@Composable
private fun UpdateOfferActions() {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        // Turning prompts off for good is the quietest thing on the row: it used to be the first
        // focusable, and a TV viewer pressing OK on the default silently stopped ever hearing
        // about an update again.
        MidnightQuietAction(
            text = "Don't remind me",
            onClick = {
                // Same switch as Settings > "Check for updates on launch"; manual checks from
                // Settings keep working.
                SettingsStore.setUpdateCheckOnLaunch(false)
                UpdateManager.dismiss()
            },
        )
        MidnightQuietAction(text = "Not now", onClick = UpdateManager::dismiss)
        MidnightFilledAction(
            text = "Update",
            onClick = { UpdateManager.download(context) },
            modifier = Modifier
                .padding(start = 4.dp)
                .midnightInitialFocus(),
        )
    }
}
