package com.anilili.ui.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import kotlinx.coroutines.delay

/** How long the unlock button stays up after a tap wakes it. */
private const val UNLOCK_PROMPT_MS = 3_000L

/**
 * The locked-screen layer, shared by the native and embed players.
 *
 * Locking is for watching with the phone in a pocket, in bed, or handed to a child: it hides the
 * controls and swallows every touch on the picture, so a stray palm cannot seek, pause, or drag the
 * brightness. It is not a security lock — one tap raises the unlock button, and a second on that
 * button ends it, the same two-step YouTube uses.
 *
 * Draw this above the controls and the gesture surface, and skip both while [locked]; this layer is
 * the only thing that may see a touch. Back is swallowed too, so leaving the episode by accident is
 * not a way past the lock either — it raises the same prompt a tap does.
 */
@Composable
internal fun PlayerLockOverlay(
    locked: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!locked) return
    var promptVisible by remember { mutableStateOf(true) }
    // A fresh tap must restart the countdown even while the prompt is already up, so the key is a
    // counter rather than the boolean — re-setting `true` over `true` would not relaunch the effect.
    var promptTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(promptTick) {
        promptVisible = true
        delay(UNLOCK_PROMPT_MS)
        promptVisible = false
    }
    BackHandler(enabled = true) { promptTick++ }

    Box(
        modifier
            .fillMaxSize()
            // Above every control the players draw, so nothing underneath is reachable by touch.
            .zIndex(2f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { promptTick++ },
                    onDoubleTap = { promptTick++ },
                    onLongPress = { promptTick++ },
                    onPress = { /* consumes the down, so drags never reach the player */ },
                )
            },
    ) {
        AnimatedVisibility(
            visible = promptVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
        ) {
            val extraFocus1 = rememberFocusFill()
            IconButton(
                colors = extraFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                onClick = {
                    DiagnosticsLog.event("Player screen unlocked")
                    onUnlock()
                },
                modifier = Modifier
                    .size(52.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .semantics { contentDescription = "Unlock screen" }.focusFill(extraFocus1, CircleShape),
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null, tint = extraFocus1.content(Color.White))
            }
        }
        AnimatedVisibility(
            visible = promptVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        ) {
            Text(
                "Screen locked",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** The control-bar button that starts a lock. Paired with [PlayerLockOverlay], which ends it. */
@Composable
internal fun PlayerLockButton(onLock: () -> Unit, modifier: Modifier = Modifier) {
    PlayerControlIconButton(
        label = "Lock screen",
        icon = Icons.Default.Lock,
        onClick = {
            DiagnosticsLog.event("Player screen locked")
            onLock()
        },
        modifier = modifier,
    )
}
