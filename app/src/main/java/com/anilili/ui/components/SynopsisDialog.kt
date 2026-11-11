package com.anilili.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/**
 * The full synopsis, for when the page it came from had room for five lines of it.
 *
 * A remote has no scrollbar to grab and no way to select text, so Up/Down are handled here
 * explicitly and drive the scroll a screenful at a time: relying on a focusable scroll container
 * to pick D-pad keys up leaves long descriptions unreadable past the first screen. Back closes it
 * through the dialog's own dismiss, and the close button takes focus on open so the remote always
 * has somewhere to be.
 */
@Composable
fun SynopsisDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
) {
    if (description.isBlank()) return
    val scroll = rememberScrollState()
    val closeFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val scrollStep by animateFloatAsState(600f, label = "synopsisScrollStep")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(onClickLabel = "Close synopsis", onClick = onDismiss)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            scope.launch { scroll.animateScrollBy(scrollStep) }
                            true
                        }
                        Key.DirectionUp -> {
                            scope.launch { scroll.animateScrollBy(-scrollStep) }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 56.dp)
                    .verticalScroll(scroll),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 26.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            val controlFocus1 = rememberFocusFill()
            IconButton(
                colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .focusRequester(closeFocus)
                    .focusFill(controlFocus1, CircleShape),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = controlFocus1.content(Color.White))
            }
        }
    }

    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { closeFocus.requestFocus() }
    }
}
