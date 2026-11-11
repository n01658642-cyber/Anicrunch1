package com.anilili.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anilili.data.auth.SimklAuthManager
import com.anilili.data.auth.SimklCodeExpiredException
import com.anilili.data.auth.SimklDeviceCode
import com.anilili.data.auth.SimklSignIn
import com.anilili.data.remote.SimklSyncWorker
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.toFailure
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.dialogs.MidnightConfirmDialog
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightFilledAction
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightNoticeTone
import com.anilili.ui.dialogs.MidnightSettingRow
import com.anilili.ui.dialogs.midnightInitialFocus
import com.anilili.ui.profiles.QrCodeImage
import com.anilili.ui.theme.Midnight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The Simkl row in Settings ▸ Accounts, with its sign-in and its disconnect.
 *
 * Simkl is a tracker beside the list service, not a third one: connecting it changes nothing about
 * which of AniList or MyAnimeList is signed in, or about My List. It only receives — episodes
 * watched, saves, and the statuses chosen in the app — so it has a row of its own rather than a
 * place in the sign-in picker.
 *
 * A phone signs in through its browser and comes back through `anilili://auth/simkl`; a
 * television, or a phone without a browser, shows a code for simkl.com/pin instead.
 */
@Composable
internal fun SimklSettingRow() {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val scope = rememberCoroutineScope()
    val loggedIn by SimklAuthManager.loggedIn.collectAsState()
    val username by SimklAuthManager.username.collectAsState()
    val signIn by SimklAuthManager.signIn.collectAsState()
    var deviceSignInVisible by remember { mutableStateOf(false) }
    var disconnectVisible by remember { mutableStateOf(false) }
    var disconnected by remember { mutableStateOf(false) }
    // The sign-in's outcome is held process-wide so a browser return can land with no screen up;
    // once it has been seen here, leaving Settings retires it.
    DisposableEffect(Unit) { onDispose { SimklAuthManager.acknowledgeSignIn() } }

    fun connect() {
        disconnected = false
        SimklAuthManager.acknowledgeSignIn()
        if (!device.isTv) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(SimklAuthManager.authorizeUrl()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { DiagnosticsLog.throwable("Simkl browser sign-in could not open", it) }
                .isSuccess
            if (opened) return
        }
        deviceSignInVisible = true
    }

    Column {
        MidnightSettingRow(
            title = "Simkl",
            description = if (loggedIn) {
                "What you watch is tracked on Simkl too"
            } else {
                "Also track what you watch on Simkl"
            },
            busy = signIn is SimklSignIn.Working,
            connected = loggedIn,
            connectedText = if (loggedIn) username?.takeIf { it.isNotBlank() } ?: "Connected" else "Connect",
            onClick = { if (loggedIn) disconnectVisible = true else connect() },
        )
        when (val state = signIn) {
            is SimklSignIn.Connected -> MidnightInlineNotice(
                state.username?.let { "Simkl connected as $it" } ?: "Simkl connected",
                tone = MidnightNoticeTone.SUCCESS,
            )
            is SimklSignIn.Failed -> MidnightInlineNotice(state.failure.withCode(), tone = MidnightNoticeTone.ERROR)
            else -> if (disconnected) {
                MidnightInlineNotice("Simkl disconnected", tone = MidnightNoticeTone.SUCCESS)
            }
        }
    }

    if (deviceSignInVisible) {
        SimklDeviceSignInDialog(onDismiss = { deviceSignInVisible = false })
    }

    if (disconnectVisible) {
        MidnightConfirmDialog(
            kicker = "Simkl",
            title = "Disconnect Simkl?",
            body = "Nothing more is sent to Simkl from this device, and its access is revoked with " +
                "Simkl. Everything already on your Simkl account stays there.",
            confirmText = "Disconnect",
            onConfirm = {
                disconnectVisible = false
                scope.launch {
                    runCatching { SimklSyncWorker.cancelAll(context) }
                    SimklAuthManager.logout()
                    disconnected = true
                }
            },
            onDismiss = { disconnectVisible = false },
        )
    }
}

/** The device-flow sign-in: a code and a QR for simkl.com/pin, polled until it is approved. */
@Composable
private fun SimklDeviceSignInDialog(onDismiss: () -> Unit) {
    var code by remember { mutableStateOf<SimklDeviceCode?>(null) }
    var failure by remember { mutableStateOf<AnililiFailure?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        code = null
        failure = null
        try {
            val issued = SimklAuthManager.startDeviceSignIn()
            code = issued
            SimklAuthManager.awaitDeviceApproval(issued)
            onDismiss()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimklCodeExpiredException) {
            failure = error.toFailure(AnililiError.ACCOUNT_LOGIN_ABANDONED)
        } catch (error: Exception) {
            failure = error.toFailure(AnililiError.ACCOUNT_TRACKER_CONNECT_FAILED, "Simkl")
        }
    }

    MidnightDialogHost(
        onDismiss = onDismiss,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Simkl",
                title = "Connect Simkl",
                metadata = null,
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = { MidnightDialogFooter(quietText = "Cancel", onQuiet = onDismiss) },
    ) {
        failure?.let { problem ->
            MidnightInlineNotice(problem.withCode(), tone = MidnightNoticeTone.ERROR)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                MidnightFilledAction(
                    text = "Get a new code",
                    onClick = { attempt++ },
                    modifier = Modifier.midnightInitialFocus(),
                )
            }
            return@MidnightDialogHost
        }
        val issued = code
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (issued == null) {
                    CircularProgressIndicator(color = Color.Black)
                } else {
                    QrCodeImage(issued.verificationUriComplete, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                listOf(
                    "Scan the code with your phone, or go to simkl.com/pin.",
                    "Enter this code there, then approve Anilili.",
                ).forEachIndexed { index, step ->
                    Text(
                        "${index + 1}.  $step",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Midnight.Text,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    issued?.userCode ?: "…",
                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 3.sp),
                    fontWeight = FontWeight.W800,
                    color = Midnight.Text,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "This screen closes by itself once you approve.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Midnight.Text3,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
