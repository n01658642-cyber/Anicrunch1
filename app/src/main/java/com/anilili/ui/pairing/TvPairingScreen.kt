package com.anilili.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import kotlinx.coroutines.android.awaitFrame

/**
 * "Link with phone" on the television.
 *
 * The whole point of this screen is that it asks for exactly one press: the viewer compares six
 * digits with the phone in their hand and presses Confirm. There is no text entry anywhere in it,
 * which is what the WebView login could never manage with a remote.
 */
@Composable
fun TvPairingScreen(
    onFinished: () -> Unit,
    onCancel: () -> Unit,
    vm: TvPairingViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.start(context) }
    BackHandler {
        vm.cancel()
        onCancel()
    }

    Surface(
        // Back has to leave pairing, not travel the focus graph. Compose maps it to
        // FocusDirection.Exit and a nested BackHandler loses to the host screen's — measured on the
        // TV emulator, where one press went from here all the way out to Home rather than back to
        // the library. Claiming it in preview is the only place it is still ours; the BackHandler
        // above stays for gesture navigation, which never reaches this.
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val isBack = event.key == Key.Back || event.key == Key.Escape
            if (isBack && event.type == KeyEventType.KeyUp) {
                vm.cancel()
                onCancel()
                true
            } else {
                isBack
            }
        },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            // Scrollable because the content is not a fixed height: a television with Ethernet and
            // Wi-Fi both up prints two addresses, and the diagnostics panel grows with the trace.
            // Without this the bottom of either simply leaves the screen, and TV overscan eats a
            // little more of it again.
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val current = state) {
                is TvPairingState.Starting -> Busy("Getting ready…")

                is TvPairingState.Waiting -> {
                    Heading("Sign in from your phone")
                    Body(
                        "Open Anilili on your phone, go to your profile and choose " +
                            "\"Link a TV\". This television will appear in the list.",
                    )
                    if (current.addresses.isEmpty()) {
                        Body("This television has no network address — check its connection.")
                    } else {
                        Body("If it doesn't appear, type one of these on the phone instead:")
                        current.addresses.forEach { address ->
                            Text(
                                text = address,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    CircularProgressIndicator(Modifier.padding(top = 20.dp))
                    Actions(
                        secondaryLabel = "Cancel",
                        onSecondary = {
                            vm.cancel()
                            onCancel()
                        },
                    )
                    PairingDiagnosticsPanel(
                        modifier = Modifier.padding(top = 20.dp).fillMaxWidth(0.8f),
                        compact = true,
                    )
                }

                is TvPairingState.Confirm -> {
                    Heading("Does your phone show this code?")
                    Text(
                        text = current.code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Black,
                        fontSize = 72.sp,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                    Body(
                        "Only continue if the same six digits are on your phone. " +
                            "This will connect ${current.serviceLabel}.",
                    )
                    Actions(
                        primaryLabel = "Yes, they match",
                        onPrimary = vm::confirm,
                        secondaryLabel = "No, stop",
                        onSecondary = {
                            vm.decline()
                            onCancel()
                        },
                    )
                }

                is TvPairingState.Linking -> Busy("Finishing sign-in on your phone…")

                is TvPairingState.Linked -> {
                    Heading("${current.serviceLabel} connected")
                    Body("Your lists will appear here in a moment.")
                    Actions(primaryLabel = "Done", onPrimary = onFinished)
                }

                is TvPairingState.Failed -> {
                    Heading("Pairing didn't finish")
                    Body(current.failure.withCode())
                    current.failure.detail?.let { Body(it) }
                    Actions(
                        primaryLabel = "Try again",
                        onPrimary = { vm.start(context) },
                        secondaryLabel = "Cancel",
                        onSecondary = onCancel,
                    )
                    PairingDiagnosticsPanel(
                        modifier = Modifier.padding(top = 20.dp).fillMaxWidth(0.8f),
                        compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun Busy(text: String) {
    CircularProgressIndicator()
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
}

/**
 * The primary control takes focus as it appears. Without it a remote has nowhere to go — the
 * screen it replaced owned the focus, and this one would be a dead end.
 */
@Composable
private fun Actions(
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    Row(
        modifier = Modifier.padding(top = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (primaryLabel != null) {
            val controlFocus1 = rememberFocusFill()
            Button(
                colors = controlFocus1.buttonColors(ButtonDefaults.buttonColors()),
                onClick = onPrimary,
                modifier = Modifier.focusRequester(focus).focusFill(controlFocus1, RoundedCornerShape(20.dp)),
            ) {
                Text(primaryLabel, fontWeight = FontWeight.Bold)
            }
        }
        if (secondaryLabel != null) {
            val controlFocus2 = rememberFocusFill()
            OutlinedButton(
                colors = controlFocus2.buttonColors(ButtonDefaults.outlinedButtonColors()),
                onClick = onSecondary,
                modifier = Modifier
                    .then(if (primaryLabel == null) Modifier.focusRequester(focus) else Modifier)
                    .focusFill(controlFocus2, RoundedCornerShape(20.dp)),
            ) {
                Text(secondaryLabel)
            }
        }
    }
    LaunchedEffect(primaryLabel, secondaryLabel) {
        awaitFrame()
        runCatching { focus.requestFocus() }
    }
}
