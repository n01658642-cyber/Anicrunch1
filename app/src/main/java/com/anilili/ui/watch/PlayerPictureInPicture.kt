package com.anilili.ui.watch

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the activity is showing the player in picture-in-picture.
 *
 * The window is a thumbnail of the picture and nothing in it can be aimed at, so every overlay the
 * players draw over the video — the pause panel, the clock, the controls, the skip and next-episode
 * prompts — stands down while this is true. Without it they drew as they would full screen: leaving
 * for another app pauses the episode, and the mini-player showed the paused clock chip over the
 * blurred pause panel instead of the frame.
 *
 * Provided by WatchScreen from MainActivity's `onPictureInPictureModeChanged`.
 */
internal val LocalPlayerInPictureInPicture = compositionLocalOf { false }
