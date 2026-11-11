package com.anilili.ui.watch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.Role
import com.anilili.data.settings.CaptionStyle
import com.anilili.data.settings.PlayerContentScale
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.adaptive.FocusFillRest
import com.anilili.errors.AnililiError
import com.anilili.errors.asFailure
import com.anilili.playback.SubtitleDelay
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.dialogs.MidnightChoiceRow
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightQuietAction
import com.anilili.ui.dialogs.MidnightRule
import com.anilili.ui.dialogs.MidnightSectionLabel
import com.anilili.ui.dialogs.MidnightSettingRow
import com.anilili.ui.dialogs.MidnightSettingSwitchRow
import com.anilili.ui.dialogs.MidnightStepperRow
import com.anilili.ui.dialogs.MidnightUnderlineSelector
import com.anilili.ui.dialogs.midnightFocus
import com.anilili.ui.theme.Midnight
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal data class PlayerQualityOption(
    val label: String,
    val selected: Boolean,
    /**
     * Video height in pixels, for the quality slider to order its stops by. Null means the option
     * has no place on that ladder — "Auto", and every subtitle or audio track reusing this type —
     * and sorts to the front. A label cannot stand in for it: "1080p" sorts before "720p".
     *
     * Declared before [onSelect] so the callers that pass their handler as a trailing lambda keep
     * binding it to the handler.
     */
    val height: Int? = null,
    val onSelect: () -> Unit,
)

/**
 * The player's general playback settings. Subtitle controls deliberately live in
 * [PlayerCaptionsSheet] so the CC button is their single, predictable entry point.
 */
@Composable
internal fun PlayerSettingsSheet(
    onDismiss: () -> Unit,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    fullscreen: Boolean = false,
    speed: Float? = null,
    onSpeedChange: (Float) -> Unit = {},
    qualityOptions: List<PlayerQualityOption> = emptyList(),
    qualityMessage: String? = null,
    serverOptions: List<PlayerQualityOption> = emptyList(),
    serverMessage: String? = null,
    audioSwitch: PlayerAudioSwitch? = null,
    onAudioSwitch: (PlayerAudioChoice) -> Unit = {},
    audioOptions: List<PlayerQualityOption> = emptyList(),
    audioFormat: String? = null,
    contentScale: PlayerContentScale? = null,
    onContentScaleChange: (PlayerContentScale) -> Unit = {},
    autoSkip: Boolean? = null,
    onAutoSkipChange: (Boolean) -> Unit = {},
    /** "Skip filler episodes"; null where there is no next episode to skip to, as offline. */
    autoSkipFiller: Boolean? = null,
    onAutoSkipFillerChange: (Boolean) -> Unit = {},
    skipTimingStatus: SkipTimingStatus? = null,
    skipReport: SkipReportState = SkipReportState.IDLE,
    onReportSkipTiming: (() -> Unit)? = null,
    onEnterPip: (() -> Unit)? = null,
    onOpenInExternalPlayer: (() -> Unit)? = null,
) {
    PlayerOptionsSheet(
        title = "Settings",
        paneTitle = "Player settings",
        closeDescription = "Close settings",
        onDismiss = onDismiss,
        fullscreen = fullscreen,
    ) {
        SheetSections(
            autoplay = autoplay,
            onAutoplayChange = onAutoplayChange,
            speed = speed,
            onSpeedChange = onSpeedChange,
            qualityOptions = qualityOptions,
            qualityMessage = qualityMessage,
            serverOptions = serverOptions,
            serverMessage = serverMessage,
            audioSwitch = audioSwitch,
            onAudioSwitch = onAudioSwitch,
            audioOptions = audioOptions,
            audioFormat = audioFormat,
            contentScale = contentScale,
            onContentScaleChange = onContentScaleChange,
            autoSkip = autoSkip,
            onAutoSkipChange = onAutoSkipChange,
            autoSkipFiller = autoSkipFiller,
            onAutoSkipFillerChange = onAutoSkipFillerChange,
            skipTimingStatus = skipTimingStatus,
            skipReport = skipReport,
            onReportSkipTiming = onReportSkipTiming,
            onEnterPip = onEnterPip,
            onOpenInExternalPlayer = onOpenInExternalPlayer,
        )
    }
}

/**
 * "Show signs too": a signs-only track merged into the dialogue track on screen, since the player
 * draws one text track at a time. Offered only where both exist; see SubtitleTrackMerge.kt.
 */
internal data class PlayerSignsToggle(
    val checked: Boolean,
    val signsLabel: String,
    val dialogueLabel: String,
    /** True while the two files are being fetched and merged. */
    val busy: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

/** All subtitle controls shown from the player's CC button. */
@Composable
internal fun PlayerCaptionsSheet(
    onDismiss: () -> Unit,
    onCaptionAppearance: () -> Unit,
    fullscreen: Boolean = false,
    subtitleOptions: List<PlayerQualityOption> = emptyList(),
    signsToggle: PlayerSignsToggle? = null,
    // Carries SUB-01 because "this episode has no subtitles" is a question about the *server*, and
    // the code is what makes "anikoto never has any" answerable from a report instead of a guess.
    emptyTrackMessage: String = AnililiError.SUBTITLE_NONE_AVAILABLE.asFailure().withCode(),
    subtitleDelayMs: Long? = null,
    onSubtitleDelayChange: (Long) -> Unit = {},
    persistDelayAcrossEpisodes: Boolean? = null,
    onPersistDelayAcrossEpisodesChange: (Boolean) -> Unit = {},
    onImportSubtitle: (() -> Unit)? = null,
    onSearchOpenSubtitles: (() -> Unit)? = null,
    syncState: SubtitleSyncState = SubtitleSyncState(),
    onCaptureSubtitleLine: () -> Unit = {},
    onSubtitleSyncChoice: (SubtitleSyncChoice) -> Unit = {},
) {
    PlayerOptionsSheet(
        title = "Captions",
        paneTitle = "Caption settings",
        closeDescription = "Close captions",
        onDismiss = onDismiss,
        fullscreen = fullscreen,
    ) {
        CaptionSections(
            subtitleOptions = subtitleOptions,
            signsToggle = signsToggle,
            emptyTrackMessage = emptyTrackMessage,
            onCaptionAppearance = onCaptionAppearance,
            subtitleDelayMs = subtitleDelayMs,
            onSubtitleDelayChange = onSubtitleDelayChange,
            persistDelayAcrossEpisodes = persistDelayAcrossEpisodes,
            onPersistDelayAcrossEpisodesChange = onPersistDelayAcrossEpisodesChange,
            onImportSubtitle = onImportSubtitle,
            onSearchOpenSubtitles = onSearchOpenSubtitles,
            syncState = syncState,
            onCaptureSubtitleLine = onCaptureSubtitleLine,
            onSubtitleSyncChoice = onSubtitleSyncChoice,
        )
    }
}



/** What the sync-by-capture flow is currently showing. */
internal data class SubtitleSyncState(
    val available: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val capturedPositionMs: Long? = null,
    val choices: List<SubtitleSyncChoice> = emptyList(),
)

/** One line the viewer can say they just heard, and the shift that would put it there. */
internal data class SubtitleSyncChoice(
    val startMs: Long,
    val text: String,
    val delayMs: Long,
)

/**
 * The in-playback options surface, in Midnight dress (design/midnight-anime/design.md §10, §27).
 *
 * The two presentations are not a styling choice. On TV a `ModalBottomSheet` opens a second window,
 * which the D-pad and TalkBack focus never reliably enter — the remote keeps driving the player
 * underneath — so TV gets the same sections as an in-window side panel where standard Compose focus
 * applies. A wide screen wants the panel for a different reason: a sheet rising from the bottom of
 * a landscape window is a short, wide strip that covers the subtitles and most of the picture, and
 * the thing being adjusted is usually the thing it is hiding. A panel down one edge leaves the
 * frame legible while it is being tuned.
 *
 * The choice is measured off this surface rather than read off the device, because the panel is
 * drawn inside the player and can only use the room the player has. A phone in portrait has no side
 * to put it on. An inline player — a 16:9 strip with the episode page beneath it — has the width but
 * not the height, unless the strip is a tablet's, which is taller than most phones are in landscape.
 * Owning the window ([fullscreen]) settles the height question on its own.
 *
 * What every presentation shares with the rest of the Midnight dialogs is the frame: a header that
 * stays put and a body that scrolls under it.
 */
@Composable
internal fun PlayerOptionsSheet(
    title: String,
    paneTitle: String,
    closeDescription: String,
    onDismiss: () -> Unit,
    fullscreen: Boolean = false,
    sections: @Composable ColumnScope.() -> Unit,
) {
    if (LocalAppDeviceProfile.current.isTv) {
        PlayerSidePanel(
            title = title,
            paneTitle = paneTitle,
            closeDescription = closeDescription,
            onDismiss = onDismiss,
            tv = true,
            // Fixed on television, because there the dp itself carries the viewer's interface
            // scale and a fraction of the screen would cancel it back out.
            panelWidth = SIDE_PANEL_MAX_WIDTH,
            content = sections,
        )
        return
    }
    // zIndex binds to siblings of the node it is on, so it has to sit on the outermost node this
    // function contributes — on the panel inside, it would only outrank this wrapper's own
    // children, and the player's floating skip action would draw over the open panel.
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(PLAYER_OPTIONS_Z)) {
        if (maxWidth >= SIDE_PANEL_MIN_SURFACE_WIDTH &&
            (fullscreen || maxHeight >= SIDE_PANEL_MIN_INLINE_HEIGHT)
        ) {
            PlayerSidePanel(
                title = title,
                paneTitle = paneTitle,
                closeDescription = closeDescription,
                onDismiss = onDismiss,
                tv = false,
                // Wide enough to read a setting and its value on one line, never so wide that it
                // takes the picture with it — on a 640dp landscape phone a fixed 420dp panel is
                // two thirds of the screen.
                panelWidth = (maxWidth * SIDE_PANEL_WIDTH_FRACTION)
                    .coerceIn(SIDE_PANEL_MIN_WIDTH, SIDE_PANEL_MAX_WIDTH),
                content = sections,
            )
        } else {
            PlayerModalSheet(
                title = title,
                closeDescription = closeDescription,
                onDismiss = onDismiss,
                sections = sections,
            )
        }
    }
}

/** The phone-in-portrait presentation: the standard Midnight bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerModalSheet(
    title: String,
    closeDescription: String,
    onDismiss: () -> Unit,
    sections: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    PlayerPanelTheme {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Midnight.SheetSurface,
        contentColor = Midnight.Text,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 9.dp, bottom = 12.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 30.dp, height = 3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(PlayerPanelOutline),
            )
            PanelHeader(
                title = title,
                closeDescription = closeDescription,
                onDismiss = onDismiss,
            )
            ScrollHintColumn(
                fadeColor = Midnight.SheetSurface,
                modifier = Modifier.weight(1f, fill = false),
                content = sections,
            )
        }
    }
    }
}

/**
 * The side presentation: an in-window right-edge panel. Being in the player's own window means the
 * D-pad and TalkBack traverse it like any other Compose content. Focus is trapped inside while it
 * is open; Back, the close button, or a tap on the dimmed video dismiss.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PlayerSidePanel(
    title: String,
    paneTitle: String,
    closeDescription: String,
    onDismiss: () -> Unit,
    tv: Boolean,
    panelWidth: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Fallback only: `exit = FocusRequester.Cancel` below makes Compose's focus system swallow
    // Back before this ever runs, so the player claims the key in its remote handler instead.
    BackHandler(onBack = onDismiss)
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(tv) {
        // Only a remote arrives with nothing under its cursor. Pulling focus onto the close button
        // of a panel a thumb just opened would paint a focus ring nobody asked for.
        if (!tv) return@LaunchedEffect
        // Let the panel attach before grabbing focus from the player controls behind it.
        delay(64)
        runCatching { initialFocus.requestFocus() }
    }
    PlayerPanelTheme {
    Box(
        Modifier
            .fillMaxSize()
            // Only load-bearing on the television path, which reaches this composable directly
            // rather than through the wrapper that carries the same z.
            .zIndex(PLAYER_OPTIONS_Z)
            .background(Color.Black.copy(alpha = 0.55f))
            // The dimmed video is the sheet's scrim by another name, and it dismisses like one.
            // A tap gesture rather than `clickable` so the scrim never becomes a focus stop the
            // remote has to step over on its way out of the panel.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(panelWidth)
                // The episode drawer's frame, shared by every picker the player opens.
                .clip(PlayerPanelShape)
                .background(PlayerPanelSurface)
                // Taps inside the panel are the panel's own; without this they would reach the
                // scrim behind it and close what was just opened.
                .pointerInput(Unit) { detectTapGestures { } }
                // Keep the remote inside the panel; Back and the close button leave it.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .semantics { this.paneTitle = paneTitle }
                // A landscape phone puts its cutout and gesture pill down the panel's own edges.
                // Television has overscan instead, which it handles through TvSafeArea.
                .then(if (tv) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        ) {
            PanelHeader(
                title = title,
                closeDescription = closeDescription,
                onDismiss = onDismiss,
                closeModifier = Modifier.focusRequester(initialFocus),
            )
            ScrollHintColumn(
                fadeColor = PlayerPanelSurface,
                modifier = Modifier.weight(1f, fill = false),
                content = content,
            )
        }
    }
    }
}

/** Above every other player overlay: the screen lock sits at 2f, the skip action at none. */
private const val PLAYER_OPTIONS_Z = 10f

/**
 * The width from which the options surface moves to the side. Every phone in landscape clears it (a
 * 360dp portrait phone is 640dp on its side) and no phone in portrait does, which is exactly the
 * split that matters.
 */
private val SIDE_PANEL_MIN_SURFACE_WIDTH = 600.dp

/**
 * The height an inline player needs before a full-height panel is worth drawing in it. A tablet's
 * 16:9 strip clears it; a landscape phone's does not, and there the sheet covers less than a panel
 * squeezed into 200dp would.
 */
private val SIDE_PANEL_MIN_INLINE_HEIGHT = 420.dp

private const val SIDE_PANEL_WIDTH_FRACTION = 0.42f
private val SIDE_PANEL_MIN_WIDTH = 300.dp
private val SIDE_PANEL_MAX_WIDTH = 420.dp

/** Kicker, title and a quiet close, over the hairline every Midnight header ends with (§11). */
@Composable
private fun PanelHeader(
    title: String,
    closeDescription: String,
    onDismiss: () -> Unit,
    closeModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "NOW PLAYING",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
                fontWeight = FontWeight.W800,
                color = PlayerPanelMutedText,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W800,
                color = Midnight.Text,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        val extraFocus1 = rememberFocusFill()
        IconButton(
            colors = extraFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
            onClick = onDismiss,
            modifier = closeModifier
                .size(40.dp).focusFill(extraFocus1, CircleShape),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = closeDescription,
                tint = extraFocus1.content(Midnight.Text3),
                modifier = Modifier.size(18.dp),
            )
        }
    }
    MidnightRule()
}

@Composable
private fun ColumnScope.SheetSections(
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    speed: Float?,
    onSpeedChange: (Float) -> Unit,
    qualityOptions: List<PlayerQualityOption>,
    qualityMessage: String?,
    serverOptions: List<PlayerQualityOption>,
    serverMessage: String?,
    audioSwitch: PlayerAudioSwitch?,
    onAudioSwitch: (PlayerAudioChoice) -> Unit,
    audioOptions: List<PlayerQualityOption>,
    audioFormat: String?,
    contentScale: PlayerContentScale?,
    onContentScaleChange: (PlayerContentScale) -> Unit,
    autoSkip: Boolean?,
    onAutoSkipChange: (Boolean) -> Unit,
    autoSkipFiller: Boolean?,
    onAutoSkipFillerChange: (Boolean) -> Unit,
    skipTimingStatus: SkipTimingStatus?,
    skipReport: SkipReportState,
    onReportSkipTiming: (() -> Unit)?,
    onEnterPip: (() -> Unit)?,
    onOpenInExternalPlayer: (() -> Unit)?,
) {
    MidnightSectionLabel("Volume")
    PlayerSliderRow { MediaVolumeSlider(modifier = Modifier.weight(1f), showPercentLabel = true) }

    speed?.let { current ->
        MidnightSectionLabel("Playback speed")
        SpeedSlider(current, onSpeedChange)
    }

    // The message is shown with an empty ladder as well as a populated one. Embed servers keep
    // their resolutions inside their own player and not every one of them exposes an API to read
    // them, so "no quality control here" is a real answer a viewer needs — and hiding the whole
    // section instead sent them hunting through a sheet that had silently dropped the row.
    if (qualityOptions.isNotEmpty() || qualityMessage != null) {
        MidnightSectionLabel("Quality")
        when {
            qualityOptions.isEmpty() -> MidnightInlineNotice(qualityMessage.orEmpty())
            qualityOptions.size > 1 -> QualitySlider(qualityOptions)
            else -> qualityOptions.forEach { option ->
                MidnightChoiceRow(
                    title = option.label,
                    selected = option.selected,
                    onSelect = option.onSelect,
                    // The panel places its own cursor on the close button; nothing may steal it.
                    claimInitialFocus = false,
                )
            }
        }
        if (qualityOptions.isNotEmpty()) qualityMessage?.let { MidnightHelper(it) }
    }

    // Mirrors of this episode on one server, which is a different question from the provider
    // picker outside the player — that one changes who resolved the episode, this one changes
    // which of that provider's players is loaded. They are named after the host rather than a
    // resolution, so they cannot be folded into the ladder above.
    if (serverOptions.size > 1) {
        MidnightSectionLabel("Video server")
        serverOptions.forEach { option ->
            MidnightChoiceRow(
                title = option.label,
                selected = option.selected,
                onSelect = option.onSelect,
                claimInitialFocus = false,
            )
        }
        serverMessage?.let { MidnightHelper(it) }
    }

    audioSwitch?.let { switch ->
        MidnightSectionLabel("Audio")
        MidnightUnderlineSelector(
            options = switch.choices,
            selected = switch.selected,
            onSelect = onAudioSwitch,
            tabLabel = PlayerAudioChoice::label,
            claimInitialFocus = false,
        )
        // Say the server out loud when the switch changes one. The episode reloads and starts from
        // a different provider, and finding that out from a stall is worse than reading it here.
        switch.other?.takeIf { it.provider != switch.selected.provider }?.let {
            MidnightHelper("${it.label} plays from ${it.provider}.")
        }
    }

    if (audioOptions.size > 1) {
        // "Audio track" rather than "Audio": these are the tracks inside the stream now playing,
        // which is a different question from the sub/dub switch — that one changes which stream —
        // and the two must not read as competing answers when both are on screen.
        MidnightSectionLabel("Audio track")
        audioOptions.forEach { option ->
            MidnightChoiceRow(
                title = option.label,
                selected = option.selected,
                onSelect = option.onSelect,
                claimInitialFocus = false,
            )
        }
    }

    // A readout, not a control: a viewer with a receiver asking why there is no Atmos gets the
    // answer here — the stream's own format, and whether this device or the receiver decodes it.
    audioFormat?.let {
        MidnightSectionLabel("Audio format")
        MidnightHelper(it)
    }

    contentScale?.let { current ->
        MidnightSectionLabel("Screen size")
        MidnightUnderlineSelector(
            options = PlayerContentScale.entries,
            selected = current,
            onSelect = onContentScaleChange,
            tabLabel = { it.label },
            claimInitialFocus = false,
        )
    }

    MidnightSectionLabel("Playback")
    MidnightSettingSwitchRow(
        title = "Auto-play next episode",
        checked = autoplay,
        onCheckedChange = onAutoplayChange,
    )
    autoSkip?.let {
        MidnightSettingSwitchRow(
            title = "Auto-skip intro, recap and outro",
            description = skipTimingStatus?.playerMessage,
            checked = it,
            onCheckedChange = onAutoSkipChange,
        )
    }
    autoSkipFiller?.let {
        MidnightSettingSwitchRow(
            title = "Skip filler episodes",
            description = "Next and auto-play go to the next episode that isn't filler",
            checked = it,
            onCheckedChange = onAutoSkipFillerChange,
        )
    }
    // Only offered for AniSkip's own windows: a provider bakes its timings into the stream payload
    // and has nowhere to hear a complaint about them.
    onReportSkipTiming?.takeIf { skipTimingStatus == SkipTimingStatus.ANISKIP }?.let { report ->
        MidnightSettingRow(
            title = skipReport.rowTitle,
            description = skipReport.rowDescription,
            onClick = { if (skipReport != SkipReportState.SENT) report() },
        )
    }
    onEnterPip?.let {
        MidnightSettingRow(title = "Picture-in-picture", onClick = it)
    }

    SleepTimerSection()
    onOpenInExternalPlayer?.let {
        MidnightSectionLabel("Trouble playing?")
        MidnightSettingRow(
            title = "Open in another player",
            description = "Hands this episode to VLC, mpv or MX Player, which carry their own decoders",
            onClick = it,
        )
    }
}

@Composable
private fun ColumnScope.CaptionSections(
    subtitleOptions: List<PlayerQualityOption>,
    signsToggle: PlayerSignsToggle?,
    emptyTrackMessage: String,
    onCaptionAppearance: () -> Unit,
    subtitleDelayMs: Long?,
    onSubtitleDelayChange: (Long) -> Unit,
    persistDelayAcrossEpisodes: Boolean?,
    onPersistDelayAcrossEpisodesChange: (Boolean) -> Unit,
    onImportSubtitle: (() -> Unit)?,
    onSearchOpenSubtitles: (() -> Unit)?,
    syncState: SubtitleSyncState,
    onCaptureSubtitleLine: () -> Unit,
    onSubtitleSyncChoice: (SubtitleSyncChoice) -> Unit,
) {
    MidnightSectionLabel("Subtitle track")
    if (subtitleOptions.isEmpty()) {
        MidnightInlineNotice(emptyTrackMessage)
    } else {
        subtitleOptions.forEach { option ->
            MidnightChoiceRow(
                title = option.label,
                selected = option.selected,
                onSelect = option.onSelect,
                claimInitialFocus = false,
            )
        }
    }
    signsToggle?.let { toggle ->
        // Left enabled while busy and simply deaf to presses: on a remote, disabling the row the
        // cursor is on drops focus out of the panel.
        MidnightSettingSwitchRow(
            title = "Show signs too",
            description = if (toggle.busy) {
                "Fetching both tracks…"
            } else {
                "${toggle.signsLabel} on top of ${toggle.dialogueLabel}"
            },
            checked = toggle.checked,
            onCheckedChange = { if (!toggle.busy) toggle.onCheckedChange(it) },
        )
    }

    // The two numbers a viewer reaches for mid-episode, straight under the track they apply to. They
    // used to sit at the foot of the panel, below the import rows and the line-capture flow, and the
    // size was not here at all but one screen further in, behind "Caption appearance".
    val captionStyle by SettingsStore.captionStyle.collectAsState()
    MidnightSectionLabel("Timing and size")
    subtitleDelayMs?.let { current ->
        // Quarter-second steps, applied while the episode plays, so each press can be judged
        // against the video behind the panel.
        PlayerAdjustRow(
            label = "Delay",
            value = current.formatSubtitleDelay(),
            decreaseDescription = "Show subtitles 0.25 seconds earlier",
            increaseDescription = "Show subtitles 0.25 seconds later",
            onDecrease = { onSubtitleDelayChange(current - SubtitleDelay.STEP_MS) },
            onIncrease = { onSubtitleDelayChange(current + SubtitleDelay.STEP_MS) },
            decreaseEnabled = current > -SubtitleDelay.MAX_MS,
            increaseEnabled = current < SubtitleDelay.MAX_MS,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            MidnightHelper(
                when {
                    current == 0L -> "Subtitles play as the provider timed them."
                    SubtitleDelay.isAutomatic ->
                        "Measured for this stream — its subtitles were cut for a different encode."
                    current > 0L -> "Subtitles are held back."
                    else -> "Subtitles run ahead."
                },
                modifier = Modifier.weight(1f).padding(bottom = 7.dp),
            )
            if (current != 0L) {
                MidnightQuietAction(text = "Reset", onClick = { onSubtitleDelayChange(0L) })
            }
        }
        persistDelayAcrossEpisodes?.let { persist ->
            MidnightSettingSwitchRow(
                title = "Keep across episodes",
                description = if (persist) {
                    "Saved for every episode in this season"
                } else {
                    "Each episode uses its own provider timing"
                },
                checked = persist,
                onCheckedChange = onPersistDelayAcrossEpisodesChange,
            )
        }
    }
    PlayerAdjustRow(
        label = "Text size",
        value = "${captionStyle.textSizeValue}",
        decreaseDescription = "Smaller subtitles",
        increaseDescription = "Larger subtitles",
        onDecrease = {
            SettingsStore.setCaptionTextSize(captionStyle.textSizeValue - CaptionStyle.TEXT_SIZE_FINE_STEP)
        },
        onIncrease = {
            SettingsStore.setCaptionTextSize(captionStyle.textSizeValue + CaptionStyle.TEXT_SIZE_FINE_STEP)
        },
        decreaseEnabled = captionStyle.textSizeValue > CaptionStyle.MIN_TEXT_SIZE,
        increaseEnabled = captionStyle.textSizeValue < CaptionStyle.MAX_TEXT_SIZE,
    )

    // Nudging by a quarter second at a time is fine for a small drift and useless for a file that
    // is eight seconds out. Capturing a line instead asks the viewer the one question they can
    // actually answer — which line should be on screen right now — and derives the shift from it.
    if (syncState.available) {
        MidnightSectionLabel("Sync to a line")
        MidnightSettingRow(
            title = "Capture line",
            description = "Press as a line should appear, then pick which one it was",
            onClick = onCaptureSubtitleLine,
        )
        when {
            syncState.isLoading -> MidnightHelper("Reading the subtitle file…")
            syncState.errorMessage != null -> MidnightInlineNotice(syncState.errorMessage)
            syncState.capturedPositionMs != null && syncState.choices.isEmpty() ->
                MidnightHelper(AnililiError.SUBTITLE_NO_LINES_NEAR.asFailure().withCode())
            syncState.choices.isNotEmpty() -> {
                MidnightHelper("Which line should have been showing?")
                syncState.choices.forEach { choice ->
                    MidnightChoiceRow(
                        title = choice.text,
                        subtitle = choice.delayMs.formatSubtitleDelay(),
                        selected = false,
                        onSelect = { onSubtitleSyncChoice(choice) },
                        claimInitialFocus = false,
                    )
                }
            }
        }
    }

    MidnightSectionLabel("Appearance")
    MidnightSettingRow(
        title = "Caption appearance",
        description = "Font, colour, background and position",
        onClick = onCaptionAppearance,
    )

    if (onImportSubtitle != null || onSearchOpenSubtitles != null) {
        MidnightSectionLabel("Add subtitles")
        onSearchOpenSubtitles?.let {
            MidnightSettingRow(
                title = "Search OpenSubtitles",
                description = "Uses a free OpenSubtitles account",
                onClick = it,
            )
        }
        onImportSubtitle?.let {
            MidnightSettingRow(
                title = "Import subtitle file",
                description = "SRT, VTT, ASS, SSA and TTML from this device",
                onClick = it,
            )
        }
    }
}

/**
 * A number nudged mid-episode — the subtitle delay, the caption size — as a label with boxed − and
 * + either side of the value.
 *
 * [MidnightStepperRow] draws its buttons as bare grey text, which read as a caption rather than
 * something to press, and lets the value's width follow its digits, so "+0.25 s" pushed the +
 * along the row on the first press. Here both buttons are filled targets and the value sits in a
 * box of its own width, so a thumb or a remote presses the same spot every time.
 */
@Composable
private fun PlayerAdjustRow(
    label: String,
    value: String,
    decreaseDescription: String,
    increaseDescription: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
) {
    val tv = LocalAppDeviceProfile.current.isTv
    val height = if (tv) 44.dp else 40.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.W600,
            color = Midnight.Text,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        PlayerAdjustButton(Icons.Default.Remove, decreaseDescription, onDecrease, decreaseEnabled, height)
        Box(
            modifier = Modifier
                .height(height)
                .widthIn(min = PlayerAdjustValueWidth)
                .clip(PlayerAdjustShape)
                .background(Color.White.copy(alpha = 0.06f))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value,
                // Equal-width digits, so the value does not wobble from one press to the next.
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.W700,
                color = Midnight.Text,
                maxLines = 1,
            )
        }
        PlayerAdjustButton(Icons.Default.Add, increaseDescription, onIncrease, increaseEnabled, height)
    }
}

@Composable
private fun PlayerAdjustButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    size: Dp,
) {
    val tv = LocalAppDeviceProfile.current.isTv
    val fill = rememberFocusFill()
    Box(
        modifier = Modifier
            .size(size)
            // Same order as MidnightQuietAction: a television's ring goes outside the clip, a
            // phone's fill inside it.
            .then(if (tv) Modifier.focusFill(fill, PlayerAdjustShape) else Modifier)
            .clip(PlayerAdjustShape)
            .then(if (tv) Modifier else Modifier.focusFill(fill, PlayerAdjustShape))
            .background(fill.background(FocusFillRest))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fill.content(if (enabled) Midnight.Text else Midnight.DisabledContent),
            modifier = Modifier.size(20.dp),
        )
    }
}

private val PlayerAdjustShape = RoundedCornerShape(10.dp)

/** Wide enough for the longest delay, "+30.00 s", so no value moves the + button. */
private val PlayerAdjustValueWidth = 84.dp

/**
 * The resolution ladder as one slider, in the shape the speed and volume rows already use, so the
 * three things a viewer reaches for mid-episode read as one control each rather than a list of
 * radio buttons that pushes everything below it off the panel.
 *
 * Unlike the other two, the change is committed on release rather than on every value: picking a
 * quality reselects a track — and on an embed source swaps the page URL and seeks back — so a drag
 * across four stops would otherwise reload the stream four times.
 */
/**
 * A slider row that says when it holds the cursor.
 *
 * Material's Slider draws no focus state of its own, and the television theme takes its ripple
 * away, so on a remote these three rows were the only controls in the app that gave no sign of
 * being aimed at — the thumb sits wherever the value is, which is not where the cursor is. They
 * cannot use the white fill the badges use, since that is the track's own colour and would
 * swallow the control, so they take a grey wash instead: enough to pick the row out, not enough
 * to compete with the filled part of the track.
 */
@Composable
private fun PlayerSliderRow(content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(if (focused) PlayerSliderFocusFill else Color.Transparent, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private val PlayerSliderFocusFill = Color.White.copy(alpha = 0.13f)

/**
 * The three columns every slider row shares, so volume, speed and quality line up: an icon slot, a
 * track with [PlayerSliderTrackPadding] either side, and a value column read from the right.
 *
 * They used to be sized row by row — a 48 dp mute button beside bare 24 dp icons, value labels of
 * 40, 44 and 56 dp — so each row's icon, track end and value sat at a different x.
 */
internal val PlayerSliderIconSize = 40.dp
internal val PlayerSliderTrackPadding = 10.dp
internal val PlayerSliderValueWidth = 56.dp

@Composable
private fun PlayerSliderIcon(icon: ImageVector) {
    Box(Modifier.size(PlayerSliderIconSize), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Midnight.Text3)
    }
}

@Composable
internal fun PlayerSliderValue(text: String, color: Color = Midnight.Text) {
    Text(
        text,
        color = color,
        // Equal-width digits, so a value changing under a dragged slider does not jiggle.
        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.W700,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(PlayerSliderValueWidth),
    )
}

@Composable
private fun QualitySlider(options: List<PlayerQualityOption>) {
    // Ascending, so dragging right means more pixels. Auto has no rung on that ladder and leads.
    val ladder = remember(options) { options.sortedWith(compareBy(nullsFirst()) { it.height }) }
    val selected = remember(ladder) { ladder.indexOfFirst { it.selected }.coerceAtLeast(0) }
    var position by remember(selected) { mutableFloatStateOf(selected.toFloat()) }
    val shown = ladder[position.roundToInt().coerceIn(ladder.indices)]
    fun commit(index: Int) {
        val option = ladder[index.coerceIn(ladder.indices)]
        position = index.toFloat()
        if (!option.selected) option.onSelect()
    }
    PlayerSliderRow {
        PlayerSliderIcon(Icons.Default.HighQuality)
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { commit(position.roundToInt()) },
            valueRange = 0f..ladder.lastIndex.toFloat(),
            steps = (ladder.size - 2).coerceAtLeast(0),
            colors = midnightSliderColors(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = PlayerSliderTrackPadding)
                .semantics { contentDescription = "Video quality" }
                // Material3's Slider ignores D-pad keys, so on TV the quality could never be
                // changed here. One press is one stop, committed immediately — a remote cannot
                // drag, so there is no run of intermediate values to protect the stream from.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val next = when (event.key) {
                        Key.DirectionLeft -> position.roundToInt() - 1
                        Key.DirectionRight -> position.roundToInt() + 1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (next in ladder.indices) {
                        commit(next)
                        true
                    } else {
                        false
                    }
                },
        )
        PlayerSliderValue(shown.label)
    }
}

@Composable
private fun SpeedSlider(speed: Float, onSpeedChange: (Float) -> Unit) {
    val speeds = PlaybackSpeeds
    val index = remember(speed) {
        speeds.indexOfFirst { abs(it - speed) < 0.001f }
            .takeIf { it >= 0 }
            ?: speeds.indexOfFirst { it == 1f }.coerceAtLeast(0)
    }
    PlayerSliderRow {
        PlayerSliderIcon(Icons.Default.Speed)
        Slider(
            value = index.toFloat(),
            onValueChange = { onSpeedChange(speeds[it.roundToInt().coerceIn(0, speeds.lastIndex)]) },
            valueRange = 0f..speeds.lastIndex.toFloat(),
            steps = (speeds.size - 2).coerceAtLeast(0),
            colors = midnightSliderColors(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = PlayerSliderTrackPadding)
                .semantics { contentDescription = "Playback speed" }
                // Material3's Slider ignores D-pad keys, so on TV the value could never be
                // changed. Left/right step through the speed list; at the ends the event is
                // released so focus can still escape.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val next = when (event.key) {
                        Key.DirectionLeft -> index - 1
                        Key.DirectionRight -> index + 1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (next in speeds.indices) {
                        onSpeedChange(speeds[next])
                        true
                    } else {
                        false
                    }
                },
        )
        PlayerSliderValue(speed.formatPlaybackSpeed())
    }
}

private fun Long.formatSubtitleDelay(): String =
    if (this == 0L) "0.00 s" else String.format(Locale.US, "%+.2f s", this / 1000.0)

/**
 * Sliders in the player are white on a grey track.
 *
 * The brand violet went with the rest of the accent when these panels turned monochrome — over a
 * picture, a coloured track is one more thing competing with the frame behind it, and the filled
 * part of a slider has to read at a glance from the far side of a room.
 *
 * The thumb is grey rather than white: M3's thumb is a tall bar, and in the same white as the
 * filled track it read as a stray white line cutting through the row. Grey keeps it findable over
 * the dark inactive track without competing with the fill it marks the end of.
 */
@Composable
internal fun midnightSliderColors() = SliderDefaults.colors(
    thumbColor = Color(0xFF9E9E9E),
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
)
