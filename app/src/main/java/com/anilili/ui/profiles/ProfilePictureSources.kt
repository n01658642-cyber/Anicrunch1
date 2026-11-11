package com.anilili.ui.profiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anilili.data.profiles.PhonePictureUpload
import com.anilili.diagnostics.DiagnosticsLog
import io.nayuki.qrcodegen.QrCode
import java.io.IOException
import kotlin.math.floor

/**
 * "Send a photo from your phone": a QR code for the phone to scan, then what the TV hears back —
 * progress while the photo arrives, the reason when one is refused, and the photo itself to check
 * before it is used. Listens only while this is on screen.
 */
@Composable
internal fun PhonePictureScreen(
    name: String,
    colorIndex: Int,
    onUse: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val upload = remember { PhonePictureUpload(context, name) }
    var link by remember { mutableStateOf<String?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    val state by upload.state.collectAsState()
    DisposableEffect(upload) { onDispose { upload.close() } }
    LaunchedEffect(upload) {
        val address = try {
            upload.start()
        } catch (e: IOException) {
            DiagnosticsLog.throwable("Profile picture upload could not listen", e)
            null
        }
        if (address == null) {
            unreachable = true
            return@LaunchedEffect
        }
        link = address
        upload.serve()
    }

    (state as? PhonePictureUpload.State.Received)?.let { received ->
        PicturePreview(
            name = name,
            colorIndex = colorIndex,
            url = received.url,
            onUse = { onUse(received.url) },
            anotherLabel = "Send a different photo",
            onAnother = upload::reset,
        )
        return
    }

    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { backFocus.requestSoon() }
    Row(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!unreachable) {
            Box(
                Modifier
                    .size(QR_BOX)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                val address = link
                if (address == null) {
                    CircularProgressIndicator(color = Color.Black)
                } else {
                    QrCodeImage(address, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(56.dp))
        }
        Column(Modifier.widthIn(max = 520.dp)) {
            Text(
                "Send a photo from your phone",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            if (unreachable) {
                Text(
                    "This TV isn't connected to a home network, so a phone can't reach it. Connect " +
                        "it to Wi-Fi or Ethernet and try again, or pick a character instead.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                listOf(
                    "Scan the code with your phone's camera.",
                    "Choose a photo for $name.",
                    "It shows up here for you to check before it's used.",
                ).forEachIndexed { index, step ->
                    Text(
                        "${index + 1}.  $step",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your phone needs to be on the same Wi-Fi as this TV.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                link?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No camera? Open $it in the phone's browser.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                UploadStatus(state)
            }
            Spacer(Modifier.height(24.dp))
            PillButton("Back", onClick = onBack, modifier = Modifier.focusRequester(backFocus))
        }
    }
}

@Composable
private fun UploadStatus(state: PhonePictureUpload.State) {
    Box(Modifier.height(56.dp).fillMaxWidth()) {
        when (state) {
            PhonePictureUpload.State.Waiting -> Text(
                "Waiting for a photo…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is PhonePictureUpload.State.Receiving -> Column {
                Text("Receiving the photo…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                val fraction = state.fraction
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            is PhonePictureUpload.State.Failed -> Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            is PhonePictureUpload.State.Received -> Unit
        }
    }
}

/** The received or picked photo as it will look, to accept or replace before anything is saved. */
@Composable
internal fun PicturePreview(
    name: String,
    colorIndex: Int,
    url: String,
    onUse: () -> Unit,
    anotherLabel: String,
    onAnother: () -> Unit,
) {
    val useFocus = remember { FocusRequester() }
    LaunchedEffect(url) { useFocus.requestSoon() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Use this photo?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        ProfileAvatar(name = name, avatarUrl = url, colorIndex = colorIndex, size = 200.dp)
        Spacer(Modifier.height(12.dp))
        Text(name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton("Use this photo", onClick = onUse, modifier = Modifier.focusRequester(useFocus))
            PillButton(anotherLabel, onClick = onAnother)
        }
    }
}

private val QR_BOX = 280.dp

/**
 * Whole-pixel modules, centred: a module width with a fraction in it leaves anti-aliased seams
 * between neighbours, which some phone cameras read as quiet zones and fail on.
 */
@Composable
internal fun QrCodeImage(text: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(modifier) {
        val cell = floor(size.minDimension / qr.size).coerceAtLeast(1f)
        val origin = Offset((size.width - cell * qr.size) / 2f, (size.height - cell * qr.size) / 2f)
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.getModule(x, y)) {
                    drawRect(Color.Black, origin + Offset(x * cell, y * cell), Size(cell, cell))
                }
            }
        }
    }
}
