package com.anilili.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anilili.data.profiles.LocalProfile
import com.anilili.data.profiles.Profiles
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.adaptive.FocusFillColor

/** A profile lock is this many digits. */
internal const val PIN_LENGTH = 4

private val DigitKeys = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)

/**
 * Four dots and a number pad. The pad is walked with the D-pad and pressed with OK; a remote with
 * number keys types straight in, and its delete key takes a digit back. [onEntered] gets the
 * [length] digits the moment the last one lands, and the dots clear for another try — the caller
 * decides whether it was right and says so through [error].
 */
@Composable
internal fun PinPad(
    title: String,
    body: String?,
    error: String?,
    onEntered: (String) -> Unit,
    avatar: (@Composable () -> Unit)? = null,
    kicker: String? = null,
    actions: @Composable () -> Unit = {},
    length: Int = PIN_LENGTH,
) {
    var digits by remember { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstKey.requestSoon() }
    fun type(digit: Char) {
        if (digits.length >= length) return
        digits += digit
        if (digits.length == length) {
            val entered = digits
            digits = ""
            onEntered(entered)
        }
    }
    fun back() { digits = digits.dropLast(1) }

    // Who and what on the left, the pad and its buttons on the right. Stacked, the two came to about
    // 630dp, and a 1080p TV is 540dp tall: the buttons under the pad were drawn off the screen.
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 32.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = DigitKeys[event.key]
                when {
                    digit != null -> { type(digit); true }
                    event.key == Key.Delete -> { back(); true }
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A fixed width, so the pad stays put as the text beside it changes from step to step.
        Column(
            Modifier.width(400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            avatar?.let {
                it()
                Spacer(Modifier.height(16.dp))
            }
            kicker?.let { StepLabel(it) }
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            body?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.semantics { contentDescription = "${digits.length} of $length digits entered" },
            ) {
                repeat(length) { index ->
                    val filled = index < digits.length
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (filled) Color.White else Color.Transparent)
                            .border(2.dp, Color.White.copy(alpha = if (filled) 1f else 0.5f), CircleShape),
                    )
                }
            }
            Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.width(64.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PadKeys(firstKey, ::type, ::back)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { actions() }
        }
    }
}

/** 1 2 3 / 4 5 6 / 7 8 9 / · 0 ⌫ — the layout of every phone keypad, so nobody has to look. */
@Composable
private fun PadKeys(firstKey: FocusRequester, type: (Char) -> Unit, back: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { digit ->
                    PadKey(
                        label = digit.toString(),
                        onClick = { type(digit) },
                        modifier = if (digit == '1') Modifier.focusRequester(firstKey) else Modifier,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.size(PadKeySize))
            PadKey(label = "0", onClick = { type('0') })
            PadKey(label = null, onClick = back)
        }
    }
}

private val PadKeySize = 56.dp

/** One pad key: a digit, or the backspace when [label] is null. */
@Composable
private fun PadKey(label: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(PadKeySize)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label ?: "Delete",
                onClick = onClick,
            )
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (focused) 0.22f else 0.08f))
            .border(if (focused) 3.dp else 0.dp, if (focused) FocusFillColor else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        } else {
            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Choosing a new lock: the four digits, then the same four again. [onSet] gets them once both
 * agree; [onSkip] is offered while adding a profile, where the lock is optional.
 */
@Composable
internal fun PinSetup(
    title: String,
    body: String,
    onSet: (String) -> Unit,
    onCancel: () -> Unit,
    cancelLabel: String = "Cancel",
    stepLabel: String? = null,
    extraAction: (@Composable () -> Unit)? = null,
) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val confirming = first != null
    PinPad(
        title = if (confirming) "Enter it again" else title,
        body = if (confirming) "The same four digits, to be sure." else body,
        kicker = stepLabel,
        error = error,
        onEntered = { pin ->
            val chosen = first
            when {
                chosen == null -> { first = pin; error = null }
                chosen == pin -> onSet(pin)
                else -> { first = null; error = "Those didn't match. Choose the PIN again." }
            }
        },
        actions = {
            extraAction?.invoke()
            PillButton(cancelLabel, onClick = onCancel)
        },
    )
}

private enum class GateStage { PIN, MASTER_CODE, NEW_PIN }

/**
 * Asking for [profile]'s lock before whatever [onUnlocked] opens. A wrong PIN says so and clears;
 * there is no lock-out, since this keeps a housemate out of a profile rather than guarding money.
 *
 * "Forgot PIN?" takes the master code ([Profiles.masterCodeMatches]) and then a new PIN, or no lock
 * at all; [onReset] runs once that is saved, and defaults to carrying on as if the PIN had been right.
 */
@Composable
internal fun PinGate(
    profile: LocalProfile,
    onUnlocked: () -> Unit,
    onCancel: (() -> Unit)?,
    body: String = "Enter the PIN for ${profile.name}.",
    onReset: () -> Unit = onUnlocked,
) {
    var stage by remember { mutableStateOf(GateStage.PIN) }
    var error by remember { mutableStateOf<String?>(null) }
    fun backToPin() { error = null; stage = GateStage.PIN }
    // Back steps out of the reset rather than out of the gate altogether.
    BackHandler(enabled = stage != GateStage.PIN, onBack = ::backToPin)
    when (stage) {
        GateStage.PIN -> PinPad(
            title = "${profile.name} is locked",
            body = body,
            error = error,
            avatar = { ProfileAvatar(profile, size = 96.dp) },
            onEntered = { pin ->
                if (Profiles.pinMatches(profile, pin)) onUnlocked() else error = "Wrong PIN. Try again."
            },
            actions = {
                PillButton("Forgot PIN?", onClick = { error = null; stage = GateStage.MASTER_CODE })
                onCancel?.let { PillButton("Back", onClick = it) }
            },
        )
        GateStage.MASTER_CODE -> PinPad(
            title = "Reset ${profile.name}'s PIN",
            body = "Enter the ${Profiles.MASTER_CODE_LENGTH}-digit master code. " +
                "If you don't have it, ask on the Anilili Discord server.",
            error = error,
            avatar = { ProfileAvatar(profile, size = 96.dp) },
            length = Profiles.MASTER_CODE_LENGTH,
            onEntered = { code ->
                if (Profiles.masterCodeMatches(code)) {
                    DiagnosticsLog.event("Profiles master code accepted id=${profile.id}")
                    error = null
                    stage = GateStage.NEW_PIN
                } else {
                    error = "That isn't the master code."
                }
            },
            actions = { PillButton("Back", onClick = ::backToPin) },
        )
        GateStage.NEW_PIN -> PinSetup(
            title = "Choose a new PIN for ${profile.name}",
            body = "Four digits, asked for whenever someone opens this profile.",
            onSet = { pin ->
                Profiles.setPin(profile.id, pin)
                onReset()
            },
            extraAction = {
                PillButton("Remove lock", onClick = {
                    Profiles.setPin(profile.id, null)
                    onReset()
                }, destructive = true)
            },
            onCancel = ::backToPin,
            cancelLabel = "Back",
        )
    }
}

/**
 * Settings ▸ Profile lock, for [profile]: set one when there is none; otherwise ask for the current
 * PIN, then offer to change or remove it.
 */
@Composable
internal fun ProfileLockSettings(profile: LocalProfile, onDone: () -> Unit) {
    var unlocked by remember { mutableStateOf(!profile.locked) }
    var changing by remember { mutableStateOf(!profile.locked) }
    when {
        !unlocked -> PinGate(
            profile = profile,
            body = "Enter the current PIN to change or remove it.",
            onUnlocked = { unlocked = true },
            onCancel = onDone,
            onReset = onDone,
        )
        changing -> PinSetup(
            title = if (profile.locked) "Choose a new PIN" else "Lock ${profile.name}",
            body = "Four digits, asked for whenever someone opens this profile.",
            onSet = { pin ->
                Profiles.setPin(profile.id, pin)
                onDone()
            },
            onCancel = onDone,
        )
        else -> Column(
            Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { first.requestSoon() }
            ProfileAvatar(profile, size = 96.dp)
            Spacer(Modifier.height(16.dp))
            Text("${profile.name}'s lock", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "The PIN is asked for whenever someone opens this profile.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton("Change PIN", onClick = { changing = true }, modifier = Modifier.focusRequester(first))
                PillButton("Remove lock", onClick = {
                    Profiles.setPin(profile.id, null)
                    onDone()
                }, destructive = true)
                PillButton("Cancel", onClick = onDone)
            }
        }
    }
}
