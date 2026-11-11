package com.anilili.ui.profiles

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.anilili.data.settings.ProfileLook
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.theme.renderAppBackdrop
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import coil.request.ImageRequest
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import android.view.WindowManager
import coil.compose.SubcomposeAsyncImage
import com.anilili.data.auth.AccountAdoption
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.profiles.AvatarChoice
import com.anilili.data.profiles.LocalProfile
import com.anilili.data.profiles.ProfileAvatars
import com.anilili.data.profiles.ProfilePictures
import com.anilili.data.profiles.Profiles
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.adaptive.FocusFillColor
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.dialogs.MidnightConfirmDialog
import com.anilili.ui.pairing.TvPairingScreen
import com.anilili.ui.profile.LoginWebView
import com.anilili.ui.profile.confirmSignedIn
import com.anilili.ui.rethrowIfCancellation
import com.anilili.ui.watch.findActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which profile screen is open over the app, when one is. */
sealed interface ProfileOverlay {
    /** "Who's watching?" — pick someone to switch to. */
    data object Picker : ProfileOverlay

    /** The same row, where choosing a profile edits it instead. */
    data object Manage : ProfileOverlay

    /** Name and picture for [profileId], or for a new profile when null. */
    data class Edit(val profileId: String?, val returnTo: ProfileOverlay) : ProfileOverlay

    /** Settings ▸ Profile lock, for the profile in use. */
    data object Lock : ProfileOverlay
}

/** Opened from the TV top bar and from Settings; drawn by [ProfilesHost]. */
object ProfilesUi {
    private val _overlay = MutableStateFlow<ProfileOverlay?>(null)
    val overlay = _overlay.asStateFlow()

    /**
     * Whether the launch intro has finished. Profile screens are dialog windows, which draw above
     * the activity and so above the intro too; they wait for it instead.
     */
    private val _launchIntroDone = MutableStateFlow(false)
    internal val launchIntroDone = _launchIntroDone.asStateFlow()
    fun markLaunchIntroDone() { _launchIntroDone.value = true }

    /**
     * A new launch plays the intro again. Leaving with Back finishes the activity but keeps the
     * process, and with it this flag; the next launch then opened "Who's watching?" straight over
     * the intro, which played on underneath and was still there when the viewer had chosen.
     */
    fun resetLaunchIntro() { _launchIntroDone.value = false }

    /**
     * Whether the remote is kept from the app until launch has settled: while the intro plays or
     * leaves, and while "Who's watching?" is due. The home page sits focused under the intro, so
     * presses made during it moved around a page nobody could see yet. Once the picker's window is
     * up it takes the keys itself; this only covers the moment before it is.
     */
    val holdsLaunchInput: Boolean
        get() = !_launchIntroDone.value || _overlay.value == ProfileOverlay.Picker

    /**
     * Set while the picker is the one shown at launch. A locked profile in use cannot be walked past
     * there — Back and Close are withheld until someone is chosen, with the PIN where it is asked
     * for — or the lock would guard nothing but the switch button.
     */
    private val _launchPick = MutableStateFlow(false)
    internal val launchPick = _launchPick.asStateFlow()

    fun showPicker(atLaunch: Boolean = false) {
        _launchPick.value = atLaunch
        _overlay.value = ProfileOverlay.Picker
    }

    internal fun launchPickDone() { _launchPick.value = false }
    fun showLock() { _overlay.value = ProfileOverlay.Lock }
    fun showManage() { _overlay.value = ProfileOverlay.Manage }
    internal fun show(overlay: ProfileOverlay?) { _overlay.value = overlay }
}

/** Further attempts after a failed portrait load, and the pause before each. */
private const val AVATAR_RETRIES = 2
private const val AVATAR_RETRY_DELAY_MS = 3_000L

private val AvatarColors = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFFD81B60), Color(0xFF6D4C41),
)

/**
 * Everything profiles put on screen, television only. Mounted once inside the app's root so the
 * device profile, theme and interface scale all reach it.
 *
 * The first-run setup and a new profile's account step take over by themselves; the picker and the
 * manage screen open when [ProfilesUi] is asked.
 */
@Composable
fun ProfilesHost() {
    if (!LocalAppDeviceProfile.current.isTv) return
    val introDone by ProfilesUi.launchIntroDone.collectAsState()
    if (!introDone) return
    val context = LocalContext.current
    val profiles by Profiles.profiles.collectAsState()
    val activeId by Profiles.activeId.collectAsState()
    val overlay by ProfilesUi.overlay.collectAsState()
    LaunchedEffect(Unit) { ProfileAvatars.load(context) }

    val active = profiles.firstOrNull { it.id == activeId }
    when {
        profiles.isEmpty() -> FullScreen(onBack = null) { SetupWizard() }
        active != null && !active.accountStepDone ->
            FullScreen(onBack = null) { AccountStep(profile = active, onDone = Profiles::markAccountStepDone) }
        overlay != null -> {
            val close = { ProfilesUi.show(null) }
            val launchPick by ProfilesUi.launchPick.collectAsState()
            // The launch picker over a locked profile in use has no way out but choosing, and Manage
            // opened from it goes back to it rather than out into the locked profile.
            val lockedLaunch = launchPick && active?.locked == true
            val mustChoose = overlay == ProfileOverlay.Picker && lockedLaunch
            when (val current = overlay) {
                ProfileOverlay.Picker, ProfileOverlay.Manage -> FullScreen(
                    onBack = when {
                        mustChoose -> null
                        lockedLaunch -> ({ ProfilesUi.show(ProfileOverlay.Picker) })
                        else -> close
                    },
                ) {
                    ProfileRow(
                        manage = current == ProfileOverlay.Manage,
                        onClose = {
                            ProfilesUi.launchPickDone()
                            close()
                        },
                        mustChoose = mustChoose,
                    )
                }
                ProfileOverlay.Lock -> FullScreen(onBack = close) {
                    if (active != null) ProfileLockSettings(profile = active, onDone = close) else close()
                }
                is ProfileOverlay.Edit -> {
                    val back = { ProfilesUi.show(current.returnTo) }
                    FullScreen(onBack = back) {
                        val existing = profiles.firstOrNull { it.id == current.profileId }
                        if (existing == null) {
                            AddProfileFlow(onDone = back)
                        } else {
                            ProfileEditor(existing = existing, onDone = back)
                        }
                    }
                }
                null -> Unit
            }
        }
    }
}

/**
 * An opaque layer over the whole app while "Who's watching?" is due or open, television only.
 *
 * The picker is a dialog window, so it cannot draw until the intro has finished leaving — and the
 * frames between the intro's last and the window's first showed Home, which read as the app opening
 * and then being taken away. Drawn in the activity, under the intro, this is already there when the
 * intro leaves; the picker's window then opens over it, and Home only appears once someone is chosen.
 * Harmless when the picker is opened later from the top bar: the window covers the screen anyway.
 */
@Composable
fun LaunchPickerCover() {
    // Asked directly: this sits beside the app's root, above where the device profile is provided.
    if (!com.anilili.ui.adaptive.rememberIsTelevision()) return
    val overlay by ProfilesUi.overlay.collectAsState()
    if (overlay != ProfileOverlay.Picker) return
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // A mouse click would otherwise fall through to the page under the cover.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    )
}

/**
 * A full-screen window of its own, so the D-pad cannot wander into the page underneath and Back
 * belongs to this screen rather than to the navigation graph. [onBack] null means Back does
 * nothing: the first-run setup has nowhere to go back to.
 */
@Composable
private fun FullScreen(onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = { onBack?.invoke() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = onBack != null,
            dismissOnClickOutside = false,
        ),
    ) {
        // The keyboard pans this window up rather than resizing it, and whatever that uncovers is
        // the dim layer — made opaque, so it is black rather than a strip of the app underneath.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            dialogWindow?.setDimAmount(1f)
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}

/** A profile's picture: always a circle, the character portrait or its coloured initial. */
@Composable
fun ProfileAvatar(
    name: String,
    avatarUrl: String?,
    colorIndex: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    /**
     * Whether a portrait that cannot be loaded falls back to the initial. True for a profile's own
     * picture, where the initial is the profile's; false in the picker, where it would be a
     * character's initial in the profile's colour and read as a choice that is not there.
     */
    initialOnFailure: Boolean = true,
) {
    val initial: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize().background(AvatarColors[colorIndex.mod(AvatarColors.size)]),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
    Box(modifier.size(size).clip(CircleShape)) {
        if (avatarUrl == null) {
            initial()
        } else {
            val context = LocalContext.current
            val placeholder: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)))
            }
            // A failed load is asked for again rather than settled on the letter: a stalled
            // connection is the usual cause, and the next attempt normally lands.
            var attempt by remember(avatarUrl) { mutableIntStateOf(0) }
            var failedAttempt by remember(avatarUrl) { mutableIntStateOf(-1) }
            val request = remember(avatarUrl, attempt) {
                ImageRequest.Builder(context)
                    .data(avatarUrl)
                    // Not part of the cache key; only makes each retry a new request.
                    .setParameter("attempt", attempt, memoryCacheKey = null)
                    .build()
            }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Portraits are tall; the face sits in the upper part of the frame.
                alignment = Alignment.TopCenter,
                // Loading is a plain placeholder, deliberately not the initial. The initial is a
                // finished avatar of its own (the first choice in the picker), so showing it while
                // a portrait downloaded made a slow load — three measured at 20s on the emulator,
                // a stalled connection retried at the timeout — read as "this one has no picture".
                loading = { placeholder() },
                error = { if (attempt < AVATAR_RETRIES || !initialOnFailure) placeholder() else initial() },
                onError = { failure ->
                    DiagnosticsLog.throwable(
                        "Profile avatar load failed attempt=$attempt url=$avatarUrl",
                        failure.result.throwable,
                    )
                    failedAttempt = attempt
                },
                modifier = Modifier.fillMaxSize(),
            )
            LaunchedEffect(failedAttempt) {
                if (failedAttempt == attempt && attempt < AVATAR_RETRIES) {
                    delay(AVATAR_RETRY_DELAY_MS)
                    attempt++
                }
            }
        }
    }
}

@Composable
fun ProfileAvatar(profile: LocalProfile, size: Dp, modifier: Modifier = Modifier) =
    ProfileAvatar(profile.name, profile.avatarUrl, profile.colorIndex, size, modifier)

/** The top-bar button: the active profile's picture, opening the switcher. */
@Composable
fun ProfileSwitchButton(modifier: Modifier = Modifier) {
    val profiles by Profiles.profiles.collectAsState()
    val activeId by Profiles.activeId.collectAsState()
    val active = profiles.firstOrNull { it.id == activeId } ?: return
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusHighlight(CircleShape, focusedScale = 1.12f)
            .clip(CircleShape)
            .border(if (focused) 3.dp else 0.dp, if (focused) FocusFillColor else Color.Transparent, CircleShape)
            .ringClickable("Switch profile, ${active.name}") { ProfilesUi.showPicker() },
        contentAlignment = Alignment.Center,
    ) {
        ProfileAvatar(active, size = 34.dp)
    }
}

// ---- Who's watching / manage ----

@Composable
private fun ProfileRow(manage: Boolean, onClose: () -> Unit, mustChoose: Boolean = false) {
    val profiles by Profiles.profiles.collectAsState()
    val activeId by Profiles.activeId.collectAsState()
    // The profile under the cursor, whose own background the screen shows. Starts on the active
    // one, which is where the cursor starts.
    var focusedId by remember { mutableStateOf(activeId) }
    val looks by produceState(emptyMap<String, ProfileLook>(), profiles, activeId) {
        val current = SettingsStore.currentLook()
        value = profiles.associate { profile ->
            // A profile never opened starts from the active profile's settings, so that is its look.
            profile.id to (Profiles.savedSettingsFile(profile.id)?.let { SettingsStore.lookIn(it) } ?: current)
        }
    }
    // The ring round the focused tile is that profile's accent too, and turns with the background.
    // A Monochrome profile's accent is white, which is what the ring is anyway.
    val focusedAccent = focusedId?.let(looks::get)?.accent
    val ringColor by animateColorAsState(
        targetValue = when {
            focusedAccent != null -> Color(focusedAccent)
            looks.isEmpty() -> FocusFillColor
            else -> Color.White
        },
        animationSpec = tween(durationMillis = LOOK_FADE_MS),
        label = "profileRing",
    )
    Box(Modifier.fillMaxSize()) {
        ProfileLookBackdrop(looks, focusedId)
        ProfileRowContent(
            manage = manage,
            onClose = onClose,
            onProfileFocused = { focusedId = it },
            ringColor = ringColor,
            mustChoose = mustChoose,
        )
    }
}

/**
 * Behind "Who's watching?": the focused profile's own background — its picture, darkened and
 * blurred as that profile set it, or a glow of its accent colour when it chose no picture — so each
 * person's tile previews the app they are about to open. Moving the cursor crossfades from one look
 * to the next rather than cutting.
 *
 * Every profile's look is read and its picture baked once, when the screen opens, so the fade never
 * waits on a decode. Profiles that look the same (the usual case until someone changes theirs) do
 * not fade at all.
 */
@Composable
private fun ProfileLookBackdrop(looks: Map<String, ProfileLook>, focusedId: String?) {
    val context = LocalContext.current
    val view = LocalView.current
    val pictures = remember { mutableStateMapOf<ProfileLook, ImageBitmap>() }
    LaunchedEffect(looks) {
        val window = IntSize(view.rootView.width, view.rootView.height)
        if (window.width <= 0 || window.height <= 0) return@LaunchedEffect
        looks.values.distinct().filter { it.backdropUrl != null && it !in pictures }.forEach { look ->
            renderAppBackdrop(context, look.backdropUrl!!, window, look.dim, look.blur)?.let { pictures[look] = it }
        }
    }
    val look = focusedId?.let(looks::get)
    Crossfade(
        targetState = look,
        animationSpec = tween(durationMillis = LOOK_FADE_MS),
        label = "profileLook",
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        val picture = shown?.let(pictures::get)
        val accent = shown?.accent
        when {
            picture != null -> Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // A wash of the profile's accent from the top of the screen, fading out well above the
            // tiles so their names stay on the canvas.
            accent != null -> Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            0f to Color(accent).copy(alpha = 0.45f),
                            0.5f to Color(accent).copy(alpha = 0.14f),
                            1f to Color.Transparent,
                            center = Offset(size.width / 2f, 0f),
                            radius = size.maxDimension * 0.75f,
                        ),
                    )
                },
            )
            else -> Box(Modifier.fillMaxSize())
        }
    }
}

/** How long one profile's background takes to give way to the next. */
private const val LOOK_FADE_MS = 700

@Composable
private fun ProfileRowContent(
    manage: Boolean,
    onClose: () -> Unit,
    onProfileFocused: (String) -> Unit,
    ringColor: Color,
    mustChoose: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by Profiles.profiles.collectAsState()
    val activeId by Profiles.activeId.collectAsState()
    var switchingTo by remember { mutableStateOf<LocalProfile?>(null) }
    // A locked profile asks for its PIN before whatever choosing it was going to do.
    var unlocking by remember { mutableStateOf<Pair<LocalProfile, () -> Unit>?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(manage, unlocking) { if (unlocking == null) firstFocus.requestSoon() }

    unlocking?.let { (profile, then) ->
        // Back from the PIN pad returns to the row, not out of the picker altogether.
        BackHandler { unlocking = null }
        PinGate(
            profile = profile,
            onUnlocked = {
                unlocking = null
                then()
            },
            onCancel = { unlocking = null },
        )
        return
    }
    fun guarded(profile: LocalProfile, action: () -> Unit) {
        if (profile.locked) unlocking = profile to action else action()
    }

    switchingTo?.let { target ->
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProfileAvatar(target, size = 120.dp)
            Spacer(Modifier.height(24.dp))
            Text("Switching to ${target.name}…", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (manage) "Manage profiles" else "Who's watching?",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (manage) {
                "Choose a profile to rename it or change its picture."
            } else {
                "Each profile keeps its own history, My List, settings and AniList or MAL account."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(40.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        ) {
            items(profiles, key = LocalProfile::id) { profile ->
                val isFirst = profile.id == (profiles.firstOrNull { it.id == activeId } ?: profiles.first()).id
                ProfileTile(
                    profile = profile,
                    current = profile.id == activeId,
                    editing = manage,
                    onFocused = { onProfileFocused(profile.id) },
                    ringColor = ringColor,
                    modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = {
                        when {
                            manage -> guarded(profile) {
                                ProfilesUi.show(ProfileOverlay.Edit(profile.id, ProfileOverlay.Manage))
                            }
                            // Staying on the profile in use needs its PIN only at launch: later, the
                            // person choosing it is already in it.
                            profile.id == activeId ->
                                if (mustChoose) guarded(profile, onClose) else onClose()
                            else -> guarded(profile) {
                                val activity = context.findActivity() as? Activity ?: return@guarded
                                switchingTo = profile
                                scope.launch { Profiles.switchTo(activity, profile.id) }
                            }
                        }
                    },
                )
            }
            if (profiles.size < Profiles.MAX_PROFILES) {
                item(key = "add") {
                    AddProfileTile(
                        onClick = {
                            val returnTo = if (manage) ProfileOverlay.Manage else ProfileOverlay.Picker
                            ProfilesUi.show(ProfileOverlay.Edit(null, returnTo))
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (manage) {
                PillButton("Done", onClick = { ProfilesUi.show(ProfileOverlay.Picker) })
            } else {
                PillButton("Manage profiles", onClick = { ProfilesUi.show(ProfileOverlay.Manage) })
                if (!mustChoose) PillButton("Close", onClick = onClose)
            }
        }
    }
}

@Composable
private fun ProfileTile(
    profile: LocalProfile,
    current: Boolean,
    editing: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    ringColor: Color = FocusFillColor,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(140.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .ringClickable(profile.name, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ProfileAvatar(
                profile,
                size = 120.dp,
                modifier = Modifier
                    .focusHighlight(CircleShape, focusedScale = 1.08f, rim = false)
                    // Only the tile under the cursor is ringed. The profile in use used to keep a
                    // ring of its own as well, which read as a second cursor.
                    .then(if (focused) Modifier.border(4.dp, ringColor, CircleShape) else Modifier),
            )
            if (editing) {
                Box(
                    Modifier.size(120.dp).clip(CircleShape).background(Color.Black.copy(.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            profile.name,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) Color.White else Color.White.copy(.72f),
            fontWeight = if (focused || current) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(140.dp)
            .onFocusChanged { focused = it.isFocused }
            .ringClickable("Add profile", onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(120.dp)
                .focusHighlight(CircleShape, focusedScale = 1.08f, rim = false)
                .clip(CircleShape)
                // focusHighlight rings it; the tile only brightens.
                .background(Color.White.copy(if (focused) .18f else .1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = if (focused) Color.White else Color.White.copy(.8f),
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Add profile", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(.72f))
    }
}

// ---- First-run setup ----

private enum class SetupStep { NAME, AVATAR, LOCK }

/**
 * Runs once per television: the first profile is named and given a picture, and adopts whatever
 * the device already holds — an update keeps its history and its sign-in. The account step follows
 * on its own, from [ProfilesHost], because the new profile starts with it not yet offered.
 */
@Composable
private fun SetupWizard() {
    var step by remember { mutableStateOf(SetupStep.NAME) }
    var name by remember { mutableStateOf("") }
    var pictureUrl by remember { mutableStateOf<String?>(null) }
    val colorIndex = remember { AvatarColors.indices.random() }
    BackHandler(enabled = step != SetupStep.NAME) {
        step = if (step == SetupStep.LOCK) SetupStep.AVATAR else SetupStep.NAME
    }

    when (step) {
        SetupStep.NAME -> NameStep(
            title = "Welcome! Who's watching?",
            body = "Set up a profile for yourself. Everyone who uses this TV can have their own, " +
                "with their own history, My List, settings and AniList or MyAnimeList account.",
            name = name,
            onNameChange = { name = it },
            onNext = { if (name.isNotBlank()) step = SetupStep.AVATAR },
            stepLabel = "Step 1 of 4",
        )
        SetupStep.AVATAR -> AvatarStep(
            name = name,
            colorIndex = colorIndex,
            selectedUrl = null,
            title = "Pick a picture",
            stepLabel = "Step 2 of 4",
            confirmLabel = "Select a picture to continue",
            onConfirm = { url ->
                pictureUrl = url
                step = SetupStep.LOCK
            },
            onBack = { step = SetupStep.NAME },
        )
        SetupStep.LOCK -> {
            fun finish(pin: String?) {
                val created = Profiles.createFirst(name, pictureUrl, colorIndex)
                if (pin != null) Profiles.setPin(created.id, pin)
                DiagnosticsLog.event("Profiles first profile created locked=${pin != null}")
            }
            PinSetup(
                title = "Lock your profile?",
                body = "Optional: a 4-digit PIN, asked for whenever someone opens this profile. " +
                    "You can add or change it later in Settings.",
                stepLabel = "Step 3 of 4",
                onSet = { pin -> finish(pin) },
                onCancel = { finish(null) },
                cancelLabel = "Skip",
            )
        }
    }
}

@Composable
private fun NameStep(
    title: String,
    body: String,
    name: String,
    onNameChange: (String) -> Unit,
    onNext: () -> Unit,
    stepLabel: String? = null,
) {
    val fieldFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestSoon() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        stepLabel?.let { StepLabel(it) }
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 620.dp),
        )
        Spacer(Modifier.height(32.dp))
        Box(Modifier.widthIn(max = 420.dp).focusRequester(fieldFocus)) {
            TvNativeTextField(
                value = name,
                onValueChange = { onNameChange(it.take(Profiles.MAX_NAME_LENGTH)) },
                hint = "Your name",
                onImeAction = onNext,
                onMoveDown = { nextFocus.requestFocus() },
            )
        }
        Spacer(Modifier.height(24.dp))
        PillButton(
            "Next",
            enabled = name.isNotBlank(),
            onClick = onNext,
            modifier = Modifier.focusRequester(nextFocus),
        )
    }
}

/** What the picture grid offers, in order. */
private sealed interface AvatarTile {
    data object Initial : AvatarTile

    /** The profile's own photo, when it has one, so it can be chosen again after looking around. */
    data class OwnPhoto(val url: String) : AvatarTile
    data object FromPhone : AvatarTile
    data object FromTv : AvatarTile
    data class Character(val choice: AvatarChoice) : AvatarTile
}

/**
 * The picture grid: the coloured initial first, then the viewer's own photo — sent from a phone, or
 * picked from the TV's storage where the box has a file picker — then AniList's most-favourited
 * characters. Offline on a first run the character list is simply empty.
 *
 * A photo is shown full size to accept or replace before [onConfirm] gets it; a refused one says why
 * here, above the grid.
 */
@Composable
private fun AvatarStep(
    name: String,
    colorIndex: Int,
    selectedUrl: String?,
    title: String,
    confirmLabel: String,
    onConfirm: (String?) -> Unit,
    onBack: () -> Unit,
    stepLabel: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val choices by ProfileAvatars.choices.collectAsState()
    val firstFocus = remember { FocusRequester() }
    val tvTileFocus = remember { FocusRequester() }
    // The system picker is another activity; coming back from it, nothing here holds focus.
    var refocusTvTile by remember { mutableStateOf(false) }
    var fromPhone by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val canPickOnTv = remember {
        Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .resolveActivity(context.packageManager) != null
    }
    val pickOnTv = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        refocusTvTile = true
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            when (val result = ProfilePictures.import(context, uri)) {
                is ProfilePictures.Result.Saved -> picked = result.url
                is ProfilePictures.Result.Rejected -> problem = result.message
            }
            importing = false
        }
    }
    val openTvPicker = {
        problem = null
        try {
            pickOnTv.launch("image/*")
        } catch (e: ActivityNotFoundException) {
            problem = "This TV has no way to browse its files. Send a photo from your phone instead."
        }
    }

    if (fromPhone) {
        BackHandler { fromPhone = false }
        PhonePictureScreen(
            name = name,
            colorIndex = colorIndex,
            onUse = { url ->
                fromPhone = false
                onConfirm(url)
            },
            onBack = { fromPhone = false },
        )
        return
    }
    picked?.let { url ->
        BackHandler { picked = null }
        PicturePreview(
            name = name,
            colorIndex = colorIndex,
            url = url,
            onUse = {
                picked = null
                onConfirm(url)
            },
            anotherLabel = "Pick a different one",
            onAnother = {
                picked = null
                openTvPicker()
            },
        )
        return
    }

    LaunchedEffect(Unit) { firstFocus.requestSoon() }
    LaunchedEffect(refocusTvTile) {
        if (refocusTvTile) {
            tvTileFocus.requestSoon()
            refocusTvTile = false
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        stepLabel?.let { StepLabel(it) }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Choose a character for $name, or use your own photo. You can change it later in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val notice = problem
        when {
            importing -> Text(
                "Reading the photo…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            notice != null -> Text(
                notice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        val tiles: List<AvatarTile> = remember(choices, selectedUrl, canPickOnTv) {
            buildList {
                add(AvatarTile.Initial)
                if (selectedUrl != null && ProfilePictures.isOwn(selectedUrl)) add(AvatarTile.OwnPhoto(selectedUrl))
                add(AvatarTile.FromPhone)
                if (canPickOnTv) add(AvatarTile.FromTv)
                choices.forEach { add(AvatarTile.Character(it)) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            gridItems(
                tiles,
                key = { tile ->
                    when (tile) {
                        AvatarTile.Initial -> "initial"
                        is AvatarTile.OwnPhoto -> "own"
                        AvatarTile.FromPhone -> "phone"
                        AvatarTile.FromTv -> "tv"
                        is AvatarTile.Character -> tile.choice.url
                    }
                },
            ) { tile ->
                when (tile) {
                    AvatarTile.FromPhone -> ActionOption(
                        label = "From your phone",
                        icon = Icons.Default.PhoneAndroid,
                        onClick = {
                            problem = null
                            fromPhone = true
                        },
                    )
                    AvatarTile.FromTv -> ActionOption(
                        label = "From this TV",
                        icon = Icons.Default.Folder,
                        enabled = !importing,
                        onClick = openTvPicker,
                        modifier = Modifier.focusRequester(tvTileFocus),
                    )
                    else -> {
                        val url = when (tile) {
                            is AvatarTile.OwnPhoto -> tile.url
                            is AvatarTile.Character -> tile.choice.url
                            else -> null
                        }
                        val selected = url == selectedUrl
                        AvatarOption(
                            name = when (tile) {
                                is AvatarTile.Character -> tile.choice.name
                                is AvatarTile.OwnPhoto -> "Your photo"
                                else -> name
                            },
                            url = url,
                            colorIndex = colorIndex,
                            selected = selected,
                            modifier = if (selected) Modifier.focusRequester(firstFocus) else Modifier,
                            onClick = { onConfirm(url) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("Back", onClick = onBack)
            Text(
                confirmLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A tile in the picture grid that leads somewhere rather than being a picture itself. */
@Composable
private fun ActionOption(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .ringClickable(label) { if (enabled) onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .focusHighlight(CircleShape, focusedScale = 1.12f, rim = false)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (focused) 0.18f else 0.08f))
                .then(if (focused) Modifier.border(4.dp, FocusFillColor, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) Color.White else Color.White.copy(.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AvatarOption(
    name: String,
    url: String?,
    colorIndex: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .ringClickable(name, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProfileAvatar(
            name = name,
            avatarUrl = url,
            colorIndex = colorIndex,
            size = 88.dp,
            initialOnFailure = false,
            modifier = Modifier
                .focusHighlight(CircleShape, focusedScale = 1.12f, rim = false)
                .then(
                    when {
                        focused -> Modifier.border(4.dp, FocusFillColor, CircleShape)
                        selected -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else -> Modifier
                    },
                ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) Color.White else Color.White.copy(.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ---- Account step ----

private enum class AccountFlow { ANILIST, MAL, PHONE }

/**
 * Offered once per profile: straight after the first-run setup, and on the first start after
 * switching into a newly added one. Skipping is a real answer — the profile works signed out and
 * can sign in later from Settings.
 */
@Composable
private fun AccountStep(profile: LocalProfile, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val token by AuthManager.token.collectAsState()
    val malLoggedIn by MalAuthManager.loggedIn.collectAsState()
    var flow by remember { mutableStateOf<AccountFlow?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val signedInTo = when {
        token != null -> "AniList"
        malLoggedIn -> "MyAnimeList"
        else -> null
    }

    when (flow) {
        AccountFlow.ANILIST -> {
            LoginWebView(
                authorizeUrl = remember { AuthManager.authorizeUrl() },
                isRedirect = AuthManager::isRedirect,
                extractResult = AuthManager::extractToken,
                onResult = { value ->
                    flow = null
                    AccountAdoption.adoptAniList(value)
                    confirmSignedIn(context, "AniList")
                },
                onCancel = { flow = null },
            )
            return
        }
        AccountFlow.MAL -> {
            LoginWebView(
                authorizeUrl = remember { MalAuthManager.authorizeUrl() },
                isRedirect = MalAuthManager::isRedirect,
                extractResult = MalAuthManager::extractCode,
                onResult = { code ->
                    flow = null
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            AccountAdoption.adoptMalCode(code)
                            confirmSignedIn(context, "MyAnimeList")
                        } catch (e: Exception) {
                            e.rethrowIfCancellation()
                            MalAuthManager.logout()
                            DiagnosticsLog.throwable("Profiles MAL sign-in failed", e)
                            error = "MyAnimeList sign-in did not finish. Try again, or skip for now."
                        } finally {
                            busy = false
                        }
                    }
                },
                onCancel = { flow = null },
            )
            return
        }
        AccountFlow.PHONE -> {
            TvPairingScreen(onFinished = { flow = null }, onCancel = { flow = null })
            return
        }
        null -> Unit
    }

    LaunchedEffect(signedInTo, busy) { if (!busy) firstFocus.requestSoon() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (Profiles.profiles.value.size == 1) StepLabel("Step 4 of 4")
        ProfileAvatar(profile, size = 96.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            if (signedInTo == null) "Connect your list, ${profile.name}" else "You're all set, ${profile.name}",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (signedInTo == null) {
                "Sign in to AniList or MyAnimeList to keep your episode progress and lists in sync " +
                    "on every device. This profile's sign-in is its own — nobody else on this TV shares it."
            } else {
                "This profile is signed in to $signedInTo. Your progress will sync as you watch."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(32.dp))
        when {
            busy -> CircularProgressIndicator()
            signedInTo != null -> PillButton("Start watching", onClick = onDone, modifier = Modifier.focusRequester(firstFocus))
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(
                        "Sign in with AniList",
                        onClick = { error = null; flow = AccountFlow.ANILIST },
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                    PillButton("Sign in with MyAnimeList", onClick = { error = null; flow = AccountFlow.MAL })
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton("Use my phone", onClick = { error = null; flow = AccountFlow.PHONE })
                    PillButton("Skip for now", onClick = onDone)
                }
            }
        }
    }
}

// ---- Edit / add ----

/**
 * Adding a profile walks the same two steps as the first-run setup — a name, then a picture — so
 * choosing the picture is asked for rather than left to be found. Pressing Done on the keyboard
 * used to save straight away with nothing chosen, which is how a tester ended up with no avatar.
 * The profile is only created once a picture (or the initial) is picked; Back steps back.
 */
/** A picture picked in the add-profile flow; a null [url] is the coloured initial. */
private data class ChosenPicture(val url: String?)

@Composable
private fun AddProfileFlow(onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var pickingPicture by remember { mutableStateOf(false) }
    // The picture chosen, held while the optional lock step is asked; wrapped so "no picture" (the
    // coloured initial) can be told apart from "not chosen yet".
    var picture by remember { mutableStateOf<ChosenPicture?>(null) }
    val colorIndex = remember { AvatarColors.indices.random() }
    BackHandler(enabled = pickingPicture && picture == null) { pickingPicture = false }
    picture?.let { (url) ->
        BackHandler { picture = null }
        fun finish(pin: String?) {
            Profiles.add(name, url, colorIndex)?.let { added -> if (pin != null) Profiles.setPin(added.id, pin) }
            onDone()
        }
        PinSetup(
            title = "Lock $name?",
            body = "Optional: a 4-digit PIN, asked for whenever someone opens this profile. " +
                "You can add or change it later in Settings.",
            stepLabel = "Step 3 of 3",
            onSet = { pin -> finish(pin) },
            onCancel = { finish(null) },
            cancelLabel = "Skip",
        )
        return
    }
    if (!pickingPicture) {
        NameStep(
            title = "Add a profile",
            body = "A new profile starts signed out with nothing watched, and a copy of the current " +
                "settings. Switch to it to sign in.",
            name = name,
            onNameChange = { name = it },
            onNext = { if (name.isNotBlank()) pickingPicture = true },
            stepLabel = "Step 1 of 3",
        )
    } else {
        AvatarStep(
            name = name,
            colorIndex = colorIndex,
            selectedUrl = null,
            title = "Pick a picture for $name",
            stepLabel = "Step 2 of 3",
            confirmLabel = "Select a picture to continue",
            onConfirm = { url -> picture = ChosenPicture(url) },
            onBack = { pickingPicture = false },
        )
    }
}

@Composable
private fun ProfileEditor(existing: LocalProfile, onDone: () -> Unit) {
    var name by remember { mutableStateOf(existing.name) }
    var pickingPicture by remember { mutableStateOf(false) }
    var avatarUrl by remember { mutableStateOf(existing.avatarUrl) }
    val colorIndex = existing.colorIndex
    var confirmDelete by remember { mutableStateOf(false) }
    val activeId by Profiles.activeId.collectAsState()
    val nameFocus = remember { FocusRequester() }
    val avatarFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }

    if (pickingPicture) {
        BackHandler { pickingPicture = false }
        AvatarStep(
            name = name.ifBlank { "this profile" },
            colorIndex = colorIndex,
            selectedUrl = avatarUrl,
            title = "Choose a picture",
            confirmLabel = "Select a picture to use it",
            onConfirm = { avatarUrl = it; pickingPicture = false },
            onBack = { pickingPicture = false },
        )
        return
    }

    LaunchedEffect(Unit) { nameFocus.requestSoon() }
    val save = {
        if (name.isNotBlank()) {
            Profiles.update(existing.copy(name = name.trim(), avatarUrl = avatarUrl))
            onDone()
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Edit profile",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))
        // Stacked, not side by side: Left and Right inside the name field move its caret, while Up
        // and Down leave a single-line field cleanly, so the remote reaches the picture from there.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            var avatarFocused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .focusRequester(avatarFocus)
                    .onFocusChanged { avatarFocused = it.isFocused }
                    .ringClickable("Change picture") { pickingPicture = true },
                contentAlignment = Alignment.BottomEnd,
            ) {
                ProfileAvatar(
                    name = name.ifBlank { "?" },
                    avatarUrl = avatarUrl,
                    colorIndex = colorIndex,
                    size = 128.dp,
                    modifier = Modifier
                        .focusHighlight(CircleShape, focusedScale = 1.08f, rim = false)
                        .then(if (avatarFocused) Modifier.border(4.dp, FocusFillColor, CircleShape) else Modifier),
                )
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                }
            }
            Box(Modifier.width(420.dp).focusRequester(nameFocus)) {
                TvNativeTextField(
                    value = name,
                    onValueChange = { name = it.take(Profiles.MAX_NAME_LENGTH) },
                    hint = "Name",
                    onImeAction = save,
                    onMoveUp = { avatarFocus.requestFocus() },
                    onMoveDown = { saveFocus.requestFocus() },
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton(
                "Save",
                enabled = name.isNotBlank(),
                onClick = save,
                modifier = Modifier.focusRequester(saveFocus),
            )
            PillButton("Cancel", onClick = onDone)
            if (existing.id != activeId) {
                PillButton("Delete profile", onClick = { confirmDelete = true }, destructive = true)
            }
        }
        if (existing.id == activeId && Profiles.profiles.value.size > 1) {
            Spacer(Modifier.height(16.dp))
            Text(
                "This is the profile in use. Switch to another one to delete it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        MidnightConfirmDialog(
            kicker = "Delete profile",
            title = "Delete ${existing.name}?",
            body = "Its watch history, My List and sign-ins on this TV are removed. Anything already " +
                "synced to AniList or MyAnimeList stays in that account.",
            confirmText = "Delete",
            onConfirm = {
                confirmDelete = false
                Profiles.delete(existing.id)
                onDone()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ---- Bits ----

/**
 * Clickable without the default rectangular focus and press wash: a circle's focus is the ring it
 * draws itself, and a grey square behind a round picture reads as a layout mistake.
 */
@Composable
private fun Modifier.ringClickable(label: String, onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClickLabel = label,
    onClick = onClick,
)

@Composable
internal fun StepLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
internal fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val fill = rememberFocusFill()
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = fill.buttonColors(
            content = if (destructive) MaterialTheme.colorScheme.error else Color.White,
        ),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
        modifier = modifier.focusFill(fill, RoundedCornerShape(24.dp)),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Focus has to land for the remote to work at all, and on a slow box the node may not be attached
 * on the first frame — requestFocus() then throws. Retried across a few frames, as the top bar does.
 */
internal suspend fun FocusRequester.requestSoon() {
    repeat(10) {
        withFrameNanos {}
        if (runCatching { requestFocus() }.isSuccess) return
    }
}
