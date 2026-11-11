package com.anilili.ui.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A scrolling column that says so while there is more below.
 *
 * The server list is the case that asked for it: a dozen or more rows, a sheet that shows six, and
 * rows that end cleanly at a card edge, so nothing on screen suggested the list went on. While the
 * column can still scroll down, its foot fades into [fadeColor] (the surface it sits on) and a
 * chevron sits over the fade; a tap on the chevron turns about a page. Both go once the end is on
 * screen, and a list that fits never shows them at all.
 *
 * The chevron answers taps only and is never a focus stop: on a remote, Down already scrolls the
 * list, and one more stop to step over on the way would be a cost with nothing to show for it.
 */
@Composable
internal fun ScrollHintColumn(
    fadeColor: Color,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    Box(modifier) {
        Column(
            modifier = Modifier.verticalScroll(scrollState),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        AnimatedVisibility(
            visible = scrollState.canScrollForward,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            // No pointer handling on the fade itself, so a tap on the half-hidden row under it
            // still reaches that row.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to fadeColor.copy(alpha = 0f),
                            1f to fadeColor,
                        ),
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                        .pointerInput(scrollState) {
                            detectTapGestures {
                                scope.launch {
                                    scrollState.animateScrollTo(
                                        scrollState.value + (scrollState.viewportSize * 0.8f).toInt(),
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        // Decorative to a screen reader, which scrolls the list itself.
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
