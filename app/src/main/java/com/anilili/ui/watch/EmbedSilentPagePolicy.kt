package com.anilili.ui.watch

/**
 * Whether an embed that loaded cleanly and then produced no video should be given up on.
 *
 * The load watchdog stops the moment `onPageFinished` fires, so before this the one thing nothing
 * watched was the page that serves a correct 200 and is simply empty. The walk ended there: no
 * error to report, so no failover, so the remaining providers were never tried and the viewer sat
 * on a black rectangle. Measured on a Fire TV Stick with Naruto Shippuden ep 414 — both of bonk's
 * HLS URLs refused (403 `domain forbidden`, then 500 from its Worker origin), the last embed
 * finished loading in fifteen seconds and had still shown nothing thirty-four seconds later.
 *
 * The signal is the video probe, not playback. An embed parked at 0:00 waiting for a gesture has a
 * `<video>` with a duration and is working; one with no video element at all is not going to start.
 *
 * [silentMs] counts only foreground time. `pauseTimers()` genuinely stops the page while the app is
 * away, so crediting background time would fail an embed that was still mid-startup during a phone
 * call.
 */
internal fun embedSilentPageFailed(
    pageFinished: Boolean,
    playbackAvailable: Boolean,
    loadError: String?,
    silentMs: Long,
    timeoutMs: Long,
): Boolean = pageFinished &&
    !playbackAvailable &&
    loadError == null &&
    silentMs >= timeoutMs
