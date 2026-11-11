package com.anilili.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.anilili.data.AnimeBackdropChoice
import com.anilili.data.AppGraph
import com.anilili.data.library.LibraryStore
import com.anilili.data.profiles.PhonePictureUpload
import com.anilili.data.profiles.ProfilePictures
import com.anilili.data.settings.AppBackdropImages
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberTvFocusTarget
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightFilledAction
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightNoticeTone
import com.anilili.ui.dialogs.MidnightQuietAction
import com.anilili.ui.dialogs.MidnightSectionLabel
import com.anilili.ui.dialogs.MidnightSettingRow
import com.anilili.ui.dialogs.midnightInitialFocus
import com.anilili.ui.profiles.QrCodeImage
import com.anilili.ui.theme.Midnight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** A title the picker offers: enough to draw its row and to fetch its backdrops. */
private data class BackdropTitle(val id: Int, val title: String, val cover: String?)

/**
 * "Background ▸ From an anime": pick a title, then one of its wide pictures.
 *
 * The title step opens on what the viewer has been watching and what is trending, so a television
 * reaches a background without a single key typed; the search box is there for anything else, and
 * answers from the bundled catalogue as each letter lands — no AniList request per keystroke.
 *
 * Back from the pictures returns to the titles rather than closing, which is where a remote's Back
 * is expected to go.
 */
@Composable
internal fun AnimeBackdropPickerDialog(onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<BackdropTitle?>(null) }
    val title = chosen
    if (title == null) {
        BackdropTitleStep(onPick = { chosen = it }, onDismiss = onDismiss)
    } else {
        BackdropImageStep(title = title, onBack = { chosen = null }, onDone = onDismiss)
    }
}

@Composable
private fun BackdropTitleStep(onPick: (BackdropTitle) -> Unit, onDismiss: () -> Unit) {
    val device = LocalAppDeviceProfile.current
    var query by remember { mutableStateOf("") }
    val history by LibraryStore.history.collectAsState()
    val recent = remember(history) {
        history.sortedByDescending { it.updatedAt }
            .distinctBy { it.anilistId }
            .take(RECENT_LIMIT)
            .map { BackdropTitle(it.anilistId, it.title, it.cover) }
    }
    val trending by produceState(emptyList<BackdropTitle>()) {
        value = runCatching { AppGraph.repository.trending(1) }
            .onFailure { DiagnosticsLog.throwable("Background picker trending failed", it) }
            .getOrNull()
            ?.items
            .orEmpty()
            .take(TRENDING_LIMIT)
            .map { BackdropTitle(it.id, it.title.preferred, it.coverImage.best) }
    }
    val suggestions by produceState(emptyList<BackdropTitle>(), query) {
        val typed = query.trim()
        if (typed.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        delay(SEARCH_DEBOUNCE_MS)
        value = runCatching { AppGraph.repository.titleSuggestions(typed) }.getOrDefault(emptyList())
            .map { BackdropTitle(it.id, it.title, null) }
    }
    val fieldFocus = rememberTvFocusTarget()
    val firstRow = remember { FocusRequester() }

    MidnightDialogHost(
        onDismiss = onDismiss,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Appearance",
                title = "Background from an anime",
                metadata = null,
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = { MidnightDialogFooter(quietText = "Close", onQuiet = onDismiss) },
    ) {
        MidnightHelper(
            "Choose a title, then one of its wide pictures. It fills the app's background, " +
                "darkened so posters and text stay readable.",
        )
        Spacer(Modifier.height(10.dp))
        if (device.isTv) {
            TvNativeTextField(
                value = query,
                onValueChange = { query = it },
                hint = "Search for a title",
                imeAction = EditorInfo.IME_ACTION_SEARCH,
                tvFocusTarget = fieldFocus,
                onMoveDown = { runCatching { firstRow.requestFocus() } },
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search for a title") },
                singleLine = true,
                shape = RoundedCornerShape(9.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
            )
        }
        Spacer(Modifier.height(8.dp))

        @Composable
        fun rows(titles: List<BackdropTitle>, first: Boolean) {
            titles.forEachIndexed { index, item ->
                val isFirst = first && index == 0
                MidnightSettingRow(
                    title = item.title,
                    onClick = { onPick(item) },
                    modifier = if (isFirst) Modifier.focusRequester(firstRow).midnightInitialFocus() else Modifier,
                    leading = item.cover?.let { cover -> { BackdropTitleCover(cover) } },
                )
            }
        }

        if (query.isNotBlank()) {
            if (suggestions.isEmpty()) {
                MidnightHelper("No titles match yet. Keep typing, or try the English or Japanese name.")
            } else {
                rows(suggestions, first = true)
            }
        } else {
            if (recent.isNotEmpty()) {
                MidnightSectionLabel("Recently watched", Modifier.padding(top = 6.dp, bottom = 4.dp))
                rows(recent, first = true)
            }
            if (trending.isNotEmpty()) {
                MidnightSectionLabel("Trending", Modifier.padding(top = 10.dp, bottom = 4.dp))
                rows(trending, first = recent.isEmpty())
            }
            if (recent.isEmpty() && trending.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
            }
        }
    }
}

@Composable
private fun BackdropTitleCover(url: String) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(width = 30.dp, height = 42.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Midnight.Panel2),
    )
}

@Composable
private fun BackdropImageStep(title: BackdropTitle, onBack: () -> Unit, onDone: () -> Unit) {
    val device = LocalAppDeviceProfile.current
    val current by SettingsStore.appBackdropAnimeUrl.collectAsState()
    val choices by produceState<List<AnimeBackdropChoice>?>(null, title.id) {
        value = runCatching { AppGraph.repository.animeBackdrops(title.id) }
            .onFailure { DiagnosticsLog.throwable("Background picker backdrops failed id=${title.id}", it) }
            .getOrDefault(emptyList())
    }
    val columns = if (device.isTv) 3 else 2

    MidnightDialogHost(
        onDismiss = onBack,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Background from an anime",
                title = title.title,
                metadata = null,
                onDismiss = onDone.takeIf { showClose },
            )
        },
        footer = { MidnightDialogFooter(quietText = "Back to titles", onQuiet = onBack) },
    ) {
        val list = choices
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            list.isEmpty() -> {
                MidnightHelper("No wide pictures were found for this title. Try another season or a related title.")
                // Something to stand on, so Back and OK still mean something on a remote.
                MidnightQuietAction(
                    text = "Back to titles",
                    onClick = onBack,
                    modifier = Modifier.padding(top = 12.dp).midnightInitialFocus(),
                )
            }
            else -> {
                MidnightHelper("Pictures without text come first. Your pick is applied straight away.")
                Spacer(Modifier.height(10.dp))
                list.chunked(columns).forEachIndexed { rowIndex, row ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEachIndexed { columnIndex, choice ->
                            BackdropTile(
                                choice = choice,
                                selected = choice.url == current,
                                initialFocus = rowIndex == 0 && columnIndex == 0,
                                onPick = {
                                    SettingsStore.setAppBackdropAnime(choice.url, title.title)
                                    onDone()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackdropTile(
    choice: AnimeBackdropChoice,
    selected: Boolean,
    initialFocus: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    // The focus ring before the clip, or the clip shaves it off (TV focus convention).
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .midnightInitialFocus(initialFocus)
            .focusHighlight(shape)
            .clip(shape)
            .background(Midnight.Panel2)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onPick)
            .semantics { contentDescription = "Backdrop from ${choice.source}" },
    ) {
        AsyncImage(
            model = choice.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = if (selected) "In use" else choice.source,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The phone's photo picker, wired to store the result as the background. Returns the launcher; the
 * picked photo is copied into the app before the setting names it, so it survives the original being
 * deleted from the gallery.
 */
@Composable
internal fun rememberBackdropPhotoPicker(onProblem: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = AppBackdropImages.import(context, uri)) {
                is ProfilePictures.Result.Saved -> SettingsStore.setAppBackdropImage(result.url)
                is ProfilePictures.Result.Rejected -> onProblem(result.message)
            }
        }
    }
    return {
        try {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            onProblem("This device has no photo picker.")
        }
    }
}

/**
 * "Background ▸ Your own image" on a television: send a photo from a phone (a QR code, nothing to
 * install), or pick one from the TV's own storage where a file browser exists — many boxes ship
 * none, which is why the phone comes first.
 */
@Composable
internal fun TvBackdropImageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fromPhone by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val canPickOnTv = remember {
        Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .resolveActivity(context.packageManager) != null
    }
    val pickOnTv = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            when (val result = AppBackdropImages.import(context, uri)) {
                is ProfilePictures.Result.Saved -> {
                    SettingsStore.setAppBackdropImage(result.url)
                    onDismiss()
                }
                is ProfilePictures.Result.Rejected -> problem = result.message
            }
            importing = false
        }
    }

    if (fromPhone) {
        PhoneBackdropUpload(onBack = { fromPhone = false }, onUsed = onDismiss)
        return
    }

    MidnightDialogHost(
        onDismiss = onDismiss,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Appearance",
                title = "Your own background",
                metadata = null,
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = { MidnightDialogFooter(quietText = "Close", onQuiet = onDismiss) },
    ) {
        MidnightHelper("The picture fills the app's background, darkened so everything stays readable.")
        Spacer(Modifier.height(8.dp))
        MidnightSettingRow(
            title = "Send a photo from your phone",
            description = "Scan a code with your phone's camera; nothing to install",
            onClick = {
                problem = null
                fromPhone = true
            },
            modifier = Modifier.midnightInitialFocus(),
        )
        if (canPickOnTv) {
            MidnightSettingRow(
                title = "Pick from this TV's storage",
                description = "A USB stick or the TV's own files",
                busy = importing,
                onClick = {
                    problem = null
                    try {
                        pickOnTv.launch("image/*")
                    } catch (e: ActivityNotFoundException) {
                        problem = "This TV has no way to browse its files. Send a photo from your phone instead."
                    }
                },
            )
        }
        problem?.let { MidnightInlineNotice(it, tone = MidnightNoticeTone.ERROR) }
    }
}

/** The QR code, what arrives, and the arrived picture to accept before it is used. */
@Composable
private fun PhoneBackdropUpload(onBack: () -> Unit, onUsed: () -> Unit) {
    val context = LocalContext.current
    val upload = remember { PhonePictureUpload(context, AppBackdropImages.phoneUpload) }
    var link by remember { mutableStateOf<String?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    val state by upload.state.collectAsState()
    DisposableEffect(upload) { onDispose { upload.close() } }
    LaunchedEffect(upload) {
        val address = try {
            upload.start()
        } catch (e: IOException) {
            DiagnosticsLog.throwable("Background upload could not listen", e)
            null
        }
        if (address == null) {
            unreachable = true
            return@LaunchedEffect
        }
        link = address
        upload.serve()
    }
    val received = (state as? PhonePictureUpload.State.Received)?.url

    MidnightDialogHost(
        onDismiss = onBack,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Your own background",
                title = if (received != null) "Use this picture?" else "Send a photo from your phone",
                metadata = null,
                onDismiss = onBack.takeIf { showClose },
            )
        },
        footer = { MidnightDialogFooter(quietText = "Back", onQuiet = onBack) },
    ) {
        if (received != null) {
            AsyncImage(
                model = received,
                contentDescription = "The picture your phone sent",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Midnight.Panel2),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                MidnightQuietAction(text = "Send a different photo", onClick = upload::reset)
                MidnightFilledAction(
                    text = "Use as background",
                    onClick = {
                        SettingsStore.setAppBackdropImage(received)
                        onUsed()
                    },
                    modifier = Modifier.midnightInitialFocus(),
                )
            }
            return@MidnightDialogHost
        }
        if (unreachable) {
            MidnightInlineNotice(
                "This TV isn't connected to a home network, so a phone can't reach it. Connect it to " +
                    "Wi-Fi or Ethernet and try again.",
                tone = MidnightNoticeTone.ERROR,
            )
            return@MidnightDialogHost
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                val address = link
                if (address == null) {
                    CircularProgressIndicator(color = Color.Black)
                } else {
                    QrCodeImage(address, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                listOf(
                    "Scan the code with your phone's camera.",
                    "Choose a photo.",
                    "Check it here before it's used.",
                ).forEachIndexed { index, step ->
                    Text(
                        "${index + 1}.  $step",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Midnight.Text,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    "Your phone needs to be on the same Wi-Fi as this TV.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Midnight.Text3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        link?.let {
            Text(
                "No camera? Open $it in the phone's browser.",
                style = MaterialTheme.typography.labelSmall,
                color = Midnight.Text3,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        when (val s = state) {
            PhonePictureUpload.State.Waiting -> Text(
                "Waiting for a photo…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is PhonePictureUpload.State.Receiving -> {
                Text("Receiving the photo…", style = MaterialTheme.typography.bodyMedium, color = Midnight.Text)
                Spacer(Modifier.height(6.dp))
                val fraction = s.fraction
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            is PhonePictureUpload.State.Failed -> MidnightInlineNotice(s.message, tone = MidnightNoticeTone.ERROR)
            is PhonePictureUpload.State.Received -> Unit
        }
    }
}

private const val RECENT_LIMIT = 6
private const val TRENDING_LIMIT = 8
private const val SEARCH_DEBOUNCE_MS = 150L
