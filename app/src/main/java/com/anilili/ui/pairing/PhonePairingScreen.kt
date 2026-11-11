package com.anilili.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anilili.data.auth.AccountService
import com.anilili.data.pairing.DiscoveredTv
import com.anilili.data.pairing.PAIRING_SERVICE_ANILIST
import com.anilili.data.pairing.PAIRING_SERVICE_MAL
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.profile.LoginWebView

/**
 * "Link a TV" on the phone: pick the account, pick the television, compare six digits, sign in.
 *
 * The sign-in itself is the ordinary WebView login — the one that already works on a touchscreen
 * with a real keyboard. Nothing obtained here is kept: the credential belongs to the television,
 * and this phone's own account is untouched.
 */
@Composable
fun PhonePairingScreen(
    onFinished: () -> Unit,
    onCancel: () -> Unit,
    vm: PhonePairingViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var service by remember { mutableStateOf(PAIRING_SERVICE_ANILIST) }

    DisposableEffect(Unit) {
        vm.startBrowsing(context)
        onDispose { vm.stopBrowsing() }
    }
    BackHandler {
        vm.cancel()
        onCancel()
    }

    // The sign-in takes over the whole screen, exactly as it does for a local login.
    (state as? PhonePairingState.Authorizing)?.let { authorizing ->
        LoginWebView(
            authorizeUrl = authorizing.authorizeUrl,
            isRedirect = vm::isPairingRedirect,
            extractResult = vm::extractPairingResult,
            onResult = vm::onAuthorizationResult,
            onCancel = vm::cancelAuthorization,
        )
        return
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Drawn edge to edge over the whole window, so it keeps its own content out from under the
        // status bar; without this the title sat beneath the clock when Settings opened it.
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
            when (val current = state) {
                is PhonePairingState.Browsing -> BrowsingContent(
                    vm = vm,
                    service = service,
                    onServiceChange = { service = it },
                )

                is PhonePairingState.Connecting -> Centered {
                    CircularProgressIndicator()
                    Body("Connecting to ${current.tvName}…")
                }

                is PhonePairingState.Confirm -> Centered {
                    Text(
                        "Check your television",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = current.code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black,
                        fontSize = 48.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    Body("${current.tvName} should be showing these same six digits. Only continue if it is.")
                    Row(
                        modifier = Modifier.padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val extraFocus1 = rememberFocusFill()
                        Button(
                            colors = extraFocus1.buttonColors(ButtonDefaults.buttonColors()),onClick = vm::confirm, modifier = Modifier.focusFill(extraFocus1, RoundedCornerShape(24.dp)),) { Text("They match", fontWeight = FontWeight.Bold) }
                        val extraFocus2 = rememberFocusFill()
                        OutlinedButton(
                            colors = extraFocus2.buttonColors(ButtonDefaults.outlinedButtonColors()),onClick = {
                            vm.decline()
                            onCancel()
                        }, modifier = Modifier.focusFill(extraFocus2, RoundedCornerShape(24.dp)),) { Text("Stop") }
                    }
                }

                is PhonePairingState.Sending -> Centered {
                    CircularProgressIndicator()
                    Body("Sending the account to your television…")
                }

                is PhonePairingState.Linked -> Centered {
                    Text(
                        "${current.tvName} is signed in",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    )
                    Body("${current.serviceLabel} is now connected on that television.")
                    val extraFocus3 = rememberFocusFill()
                    Button(
                        colors = extraFocus3.buttonColors(ButtonDefaults.buttonColors()),onClick = onFinished, modifier = Modifier.padding(top = 20.dp).focusFill(extraFocus3, RoundedCornerShape(24.dp))) { Text("Done") }
                }

                is PhonePairingState.Failed -> Centered {
                    Text(
                        "Pairing didn't finish",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                    )
                    // The line and the code, then the hint underneath: a viewer reads the first two
                    // and passes them on, and follows the third if they mean to fix it themselves.
                    Body(current.failure.withCode())
                    current.failure.detail?.let { Body(it) }
                    Row(
                        modifier = Modifier.padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val extraFocus4 = rememberFocusFill()
                        Button(
                            colors = extraFocus4.buttonColors(ButtonDefaults.buttonColors()),onClick = { vm.restart(context) }, modifier = Modifier.focusFill(extraFocus4, RoundedCornerShape(24.dp)),) { Text("Try again") }
                        val extraFocus5 = rememberFocusFill()
                        OutlinedButton(
                            colors = extraFocus5.buttonColors(ButtonDefaults.outlinedButtonColors()),onClick = onCancel, modifier = Modifier.focusFill(extraFocus5, RoundedCornerShape(24.dp)),) { Text("Close") }
                    }
                    PairingDiagnosticsPanel(modifier = Modifier.padding(top = 20.dp))
                }

                is PhonePairingState.Authorizing -> Unit
            }
        }
    }
}

@Composable
private fun BrowsingContent(
    vm: PhonePairingViewModel,
    service: String,
    onServiceChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val televisions by vm.televisions.collectAsStateWithLifecycle()
    var manualAddress by remember { mutableStateOf("") }

    Text("Link a TV", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
    Text(
        "Sign a television into your account from here. Both devices need to be on the same " +
            "network.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val extraFocus6 = rememberFocusFill()
        FilterChip(
            colors = extraFocus6.chipColors(FilterChipDefaults.filterChipColors()),
            selected = service == PAIRING_SERVICE_ANILIST,
            onClick = { onServiceChange(PAIRING_SERVICE_ANILIST) },
            label = { Text(AccountService.ANILIST.label) }, modifier = Modifier.focusFill(extraFocus6, RoundedCornerShape(8.dp)),)
        val extraFocus7 = rememberFocusFill()
        FilterChip(
            colors = extraFocus7.chipColors(FilterChipDefaults.filterChipColors()),
            selected = service == PAIRING_SERVICE_MAL,
            onClick = { onServiceChange(PAIRING_SERVICE_MAL) },
            label = { Text(AccountService.MAL.label) }, modifier = Modifier.focusFill(extraFocus7, RoundedCornerShape(8.dp)),)
    }

    if (televisions.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.padding(4.dp))
            Text(
                "Looking for televisions running Anilili…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(televisions, key = { it.name }) { tv ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { vm.connect(tv, service) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(tv.name, fontWeight = FontWeight.Bold)
                        Text(
                            "${tv.host}:${tv.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // Some routers isolate clients or drop multicast between wired and wireless, and then the list
    // above stays empty however long it spins. The television prints its address for this case.
    Text(
        "Not listed? Enter the address shown on the television.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = manualAddress,
            onValueChange = { manualAddress = it },
            singleLine = true,
            placeholder = { Text("192.168.1.50:47001") },
            modifier = Modifier.weight(1f),
        )
        val extraFocus8 = rememberFocusFill()
        Button(
            colors = extraFocus8.buttonColors(ButtonDefaults.buttonColors()),
            onClick = {
                parseManualAddress(manualAddress)?.let { vm.connect(it, service) }
            },
            enabled = parseManualAddress(manualAddress) != null, modifier = Modifier.focusFill(extraFocus8, RoundedCornerShape(24.dp)),) {
            Text("Connect")
        }
    }
    PairingDiagnosticsPanel(modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = { content() },
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** `host:port`, as printed on the television. Rejects anything that is not both. */
internal fun parseManualAddress(value: String): DiscoveredTv? {
    val trimmed = value.trim()
    val separator = trimmed.lastIndexOf(':')
    if (separator <= 0 || separator == trimmed.length - 1) return null
    val host = trimmed.substring(0, separator).trim()
    val port = trimmed.substring(separator + 1).trim().toIntOrNull() ?: return null
    if (host.isEmpty() || port !in 1..65535) return null
    return DiscoveredTv(name = host, host = host, port = port)
}
