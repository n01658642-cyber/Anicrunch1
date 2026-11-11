package com.anilili.ui.watch


import android.view.inputmethod.EditorInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anilili.data.AppGraph
import com.anilili.data.auth.OpenSubtitlesAuthManager
import com.anilili.data.remote.OpenSubtitleSearchResult
import com.anilili.data.settings.SettingsStore
import com.anilili.errors.AnililiError
import com.anilili.errors.toFailure
import com.anilili.playback.AttachedSubtitle
import com.anilili.playback.downloadOpenSubtitle
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.TvTextInputType
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import java.util.Locale
import kotlinx.coroutines.launch
import com.anilili.util.cached

/** In-player OpenSubtitles browser shared by touch and TV native playback. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun OpenSubtitlesPanel(
    initialQuery: String,
    initialEpisode: String,
    onDismiss: () -> Unit,
    onAttach: (AttachedSubtitle) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val client = AppGraph.openSubtitles
    val device = LocalAppDeviceProfile.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val accountLoggedIn by OpenSubtitlesAuthManager.loggedIn.collectAsState()
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var season by remember { mutableStateOf("") }
    var episode by remember(initialEpisode) { mutableStateOf(openSubtitlesEpisodeField(initialEpisode)) }
    // Seeded from the saved choice and only falling back to the device when there is none, so a
    // viewer whose phone is not in the language they read sets it once instead of every episode.
    val savedLanguages by SettingsStore.subtitleSearchLanguages.collectAsState()
    var languages by remember(savedLanguages) {
        mutableStateOf(
            savedLanguages.ifBlank { defaultOpenSubtitlesLanguages(Locale.getDefault().language) },
        )
    }
    var results by remember { mutableStateOf<List<OpenSubtitleSearchResult>>(emptyList()) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }
    var automaticSearchStarted by remember(initialQuery, initialEpisode) { mutableStateOf(false) }

    suspend fun searchCurrentEpisode() {
        if (busyMessage != null) return
        // Remembered on use rather than on every keystroke: a half-typed code is not a preference,
        // and searching with it is the moment it becomes one.
        SettingsStore.setSubtitleSearchLanguages(languages)
        busyMessage = "Searching $query${episode.takeIf(String::isNotBlank)?.let { " · episode $it" }.orEmpty()}…"
        errorMessage = null
        runCatching {
            OpenSubtitlesAuthManager.withSession {
                client.search(
                    query = query,
                    languages = languages,
                    seasonNumber = season.toIntOrNull(),
                    episodeNumber = episode.toIntOrNull(),
                )
            }
        }.onSuccess {
            results = it
            hasSearched = true
        }.onFailure { error ->
            // The sign-in check throws from inside withSession, so a search made without one keeps
            // SUB-07 and its "Open settings" rather than reading as a search that came back empty.
            errorMessage = error.toFailure(
                AnililiError.SUBTITLE_SEARCH_FAILED,
                attributes = mapOf("languages" to languages, "hasEpisode" to episode.isNotBlank()),
            ).withCode()
        }
        busyMessage = null
        // A remote has nowhere obvious to go once results replace the empty-state text, and the
        // first result is what the search was for. One frame's grace so the rows exist to take it.
        if (device.isTv && results.isNotEmpty()) {
            kotlinx.coroutines.delay(64)
            runCatching { resultsFocus.requestFocus() }
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(64)
        runCatching { closeFocus.requestFocus() }
    }
    LaunchedEffect(accountLoggedIn) {
        if (!accountLoggedIn || automaticSearchStarted) return@LaunchedEffect
        while (busyMessage != null) kotlinx.coroutines.delay(16)
        automaticSearchStarted = true
        searchCurrentEpisode()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxHeight(0.94f)
                .widthIn(max = 620.dp)
                .fillMaxWidth(0.94f)
                .background(Color(0xFF1B1B1F), RoundedCornerShape(20.dp))
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "OpenSubtitles",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
                val controlFocus1 = rememberFocusFill()
                IconButton(
                    colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                    onClick = onDismiss,
                    modifier = Modifier
                        .focusRequester(closeFocus)
                        .focusFill(controlFocus1, RoundedCornerShape(24.dp)),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close OpenSubtitles", tint = controlFocus1.content(Color.White))
                }
            }
            Text(
                "Uses the free OpenSubtitles REST API. No paid AI translation or transcription services are used.",
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))

            if (!client.isConfigured) {
                Text(
                    "Online search is not configured in this build. Add a free OpenSubtitles API consumer key as the openSubtitlesApiKey Gradle property or OPEN_SUBTITLES_API_KEY environment variable. Manual file import still works.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            if (!accountLoggedIn) {
                Text(
                    "Sign in once under Settings → Playback → OpenSubtitles. Your saved account will renew the API session automatically.",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            if (device.isTv) {
                    // A Compose text field cannot be typed into on a television: the D-pad never
                    // hands it an input connection a TV IME will attach to, which is why picking a
                    // different subtitle language here was impossible. TvNativeTextField is the
                    // real EditText the rest of the app already uses for TV entry.
                    TvNativeTextField(
                        value = query,
                        onValueChange = { query = it },
                        hint = "Anime or movie title",
                        imeAction = EditorInfo.IME_ACTION_SEARCH,
                        onImeAction = { scope.launch { searchCurrentEpisode() } },
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TvNativeTextField(
                            value = season,
                            onValueChange = { season = it.filter(Char::isDigit).take(3) },
                            hint = "Season",
                            inputType = TvTextInputType.NUMBER,
                            modifier = Modifier.weight(0.25f),
                        )
                        TvNativeTextField(
                            value = episode,
                            onValueChange = { episode = it.filter(Char::isDigit).take(4) },
                            hint = "Episode",
                            inputType = TvTextInputType.NUMBER,
                            modifier = Modifier.weight(0.25f),
                        )
                        TvNativeTextField(
                            value = languages,
                            onValueChange = { languages = it },
                            hint = "Languages: en, es, fr",
                            modifier = Modifier.weight(0.5f),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Anime or movie title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = season,
                            onValueChange = { season = it.filter(Char::isDigit).take(3) },
                            label = { Text("Season") },
                            singleLine = true,
                            modifier = Modifier.weight(0.25f),
                        )
                        OutlinedTextField(
                            value = episode,
                            onValueChange = { episode = it.filter(Char::isDigit).take(4) },
                            label = { Text("Episode") },
                            singleLine = true,
                            modifier = Modifier.weight(0.25f),
                        )
                        OutlinedTextField(
                            value = languages,
                            onValueChange = { languages = it },
                            label = { Text("Languages") },
                            supportingText = { Text("Codes: en, es, fr") },
                            singleLine = true,
                            modifier = Modifier.weight(0.5f),
                        )
                    }
                }
                // Deliberately not disabled while a search runs. A disabled Button stops being
                // focusable, so pressing Search dropped the remote's focus on the spot and the
                // player underneath claimed it — the reported "you can't interact with that window
                // anymore". It stays focusable and the guard lives in searchCurrentEpisode.
                val controlFocus2 = rememberFocusFill()
                Button(
                    colors = controlFocus2.buttonColors(ButtonDefaults.buttonColors()),
                    onClick = {
                        if (query.isNotBlank() && languages.isNotBlank()) {
                            scope.launch { searchCurrentEpisode() }
                        }
                    },
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .focusFill(controlFocus2, RoundedCornerShape(20.dp)),
                ) {
                    Text(if (busyMessage == null) "Search" else "Searching…")
                }

                if (results.isEmpty() && busyMessage == null && errorMessage == null) {
                    Text(
                        if (hasSearched) {
                            "No subtitles found for $query${episode.takeIf(String::isNotBlank)?.let { " episode $it" }.orEmpty()}. You can adjust the title, season, episode, or languages and search again."
                        } else {
                            "This opens with an automatic search for $initialQuery${episode.takeIf(String::isNotBlank)?.let { " episode $it" }.orEmpty()}."
                        },
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            results.take(MAX_VISIBLE_RESULTS).forEachIndexed { index, result ->
                    SubtitleResultRow(
                        result = result,
                        // Rows keep their click for the same reason the Search button keeps its
                        // enabled state: going un-clickable mid-download drops the remote's focus.
                        onClick = {
                            if (busyMessage != null) return@SubtitleResultRow
                            scope.launch {
                                busyMessage = "Downloading subtitle…"
                                errorMessage = null
                                runCatching {
                                    OpenSubtitlesAuthManager.withSession {
                                        downloadOpenSubtitle(context, client, result)
                                    }
                                }.onSuccess(onAttach)
                                    .onFailure { error ->
                                        errorMessage = error
                                            .toFailure(AnililiError.SUBTITLE_DOWNLOAD_FAILED)
                                            .withCode()
                                    }
                                busyMessage = null
                            }
                        },
                        modifier = if (index == 0) Modifier.focusRequester(resultsFocus) else Modifier,
                    )
            }

            busyMessage?.let { message ->
                Row(
                    Modifier.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text(message, color = Color.White)
                }
            }
            errorMessage?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SubtitleResultRow(
    result: OpenSubtitleSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val badges = buildList {
        if (result.trusted) add("trusted")
        if (result.hearingImpaired) add("CC")
        if (result.aiTranslated) add("AI")
        if (result.machineTranslated) add("machine")
    }.joinToString(" · ")
    val fillFocus1 = rememberFocusFill()
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .focusFill(fillFocus1, RoundedCornerShape(12.dp))
            .background(fillFocus1.background(Color.White.copy(alpha = 0.07f)), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(
            result.releaseName,
            color = fillFocus1.content(Color.White),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            buildString {
                append(result.language.uppercase())
                append(" · ")
                append(result.downloads)
                append(" downloads")
                if (badges.isNotBlank()) append(" · $badges")
            },
            color = fillFocus1.content(Color.White.copy(alpha = 0.65f)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (result.fileName.isNotBlank() && result.fileName != result.releaseName) {
            Text(
                result.fileName,
                color = fillFocus1.content(Color.White.copy(alpha = 0.48f)),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private const val MAX_VISIBLE_RESULTS = 20

internal fun openSubtitlesEpisodeField(value: String): String = value.trim()
    .toDoubleOrNull()
    ?.takeIf { it >= 1.0 && it % 1.0 == 0.0 && it <= 9_999.0 }
    ?.toInt()
    ?.toString()
    .orEmpty()

internal fun defaultOpenSubtitlesLanguages(systemLanguage: String): String = systemLanguage
    .trim()
    .lowercase()
    .takeIf { it.matches(Regex.cached("[a-z]{2,3}")) }
    ?.let { if (it == "en") "en" else "$it,en" }
    ?: "en"
