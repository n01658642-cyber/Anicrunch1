package com.anilili.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import kotlinx.coroutines.android.awaitFrame

/** Full-screen cover viewer that reuses the same Coil URL/cache as the detail-page thumbnail. */
@Composable
fun CoverImagePreview(
    imageUrl: String?,
    title: String,
    onDismiss: () -> Unit,
) {
    if (imageUrl.isNullOrBlank()) return
    val closeFocusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.97f))
                .clickable(onClickLabel = "Close cover preview", onClick = onDismiss),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "$title cover",
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 36.dp),
                contentScale = ContentScale.Fit,
            )
            val controlFocus1 = rememberFocusFill()
            IconButton(
                colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .focusRequester(closeFocusRequester)
                    .focusFill(controlFocus1, CircleShape),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = controlFocus1.content(Color.White))
            }
        }
    }

    // Makes the preview immediately operable with a keyboard or TV remote as well as touch.
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { closeFocusRequester.requestFocus() }
    }
}
