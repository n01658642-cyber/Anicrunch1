package com.anilili.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anilili.data.pairing.PairingDiagnostics

/**
 * The pairing trace, on screen.
 *
 * Pairing breaks on networks we cannot reproduce — client isolation, guest VLANs, a router that
 * drops multicast between wired and wireless — and it breaks for someone holding a remote, with no
 * adb and no logcat. Everything needed to tell those apart is therefore printed where it can be
 * photographed: which addresses this device has, whether the mDNS registration or browse actually
 * succeeded, whether a television was seen but failed to resolve, and what any socket error was.
 */
@Composable
fun PairingDiagnosticsPanel(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val entries by PairingDiagnostics.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text(
            text = "Connection details",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entries.isEmpty()) {
            Text(
                text = "Nothing yet.",
                fontFamily = FontFamily.Monospace,
                fontSize = if (compact) 10.sp else 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (compact) 120.dp else 240.dp)
                .padding(top = 4.dp),
        ) {
            items(entries) { entry ->
                // A line that names a failure is the one a viewer is being asked to find, so it is
                // coloured rather than left to be spotted in a wall of grey.
                val alarming = entry.text.contains("FAIL", ignoreCase = true) ||
                    entry.text.contains("WARNING") ||
                    entry.text.contains("threw")
                Text(
                    text = "${PairingDiagnostics.stamp(entry.atMs)}  ${entry.text}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 10.sp else 13.sp,
                    fontWeight = if (alarming) FontWeight.Bold else FontWeight.Normal,
                    color = if (alarming) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
