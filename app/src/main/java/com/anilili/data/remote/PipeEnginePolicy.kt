package com.anilili.data.remote

/**
 * Whether this device can afford to keep the pipe's browser engine resident.
 *
 * A low-RAM television cannot, and the cost is not a slow app — it is the whole box locking up.
 * Android disables multiprocess WebView when `isLowRamDevice` is set, so the engine's renderer runs
 * *inside* this process: its memory is charged to us, and there is no child process for the system
 * to reclaim instead. Two reports show what that reaches. A TCL UnionTV (Android 9, 128 MB memory
 * class, armeabi-v7a) opening One Piece grew its native heap from 25 MB to 732 MB in 35 seconds and
 * to 1,196 MB over the next half hour, while the system's free memory fell from 830 MB to 265 MB on
 * a box that has about a gigabyte; the main thread was blocked for 39 minutes (`main.recovered
 * blockedMs=2352280`) and the viewer reported the television itself becoming unusable. A
 * MiTV-AESP0 on the same Android version reached 494 MB and was killed outright, which its owner
 * reported as "crash 5 second after loading one piece".
 *
 * Skipping the engine costs those devices the Miruro-sourced providers and nothing else — the
 * native scrapers are untouched. That is a smaller loss than it sounds: on both reports the pipe
 * had already failed (`every mirror wants a human check`, `bridge.ready ready=false`) and returned
 * no providers at all, so it was pure cost. When the same sessions gave up on it and fell through
 * to the native providers, a source resolved in three seconds and played at 1080p.
 *
 * Deliberately keyed on [isLowRamDevice] rather than a memory-class threshold. It is a flag the
 * manufacturer sets on purpose to tell apps to hold back, so it is a statement about the device
 * rather than a guess from a number, and it covers both reports. Widening this to cheap televisions
 * that never set it is a one-condition change if the evidence ever arrives; it has not yet.
 */
internal fun canHostPipeEngine(isTv: Boolean, isLowRamDevice: Boolean): Boolean =
    !(isTv && isLowRamDevice)
