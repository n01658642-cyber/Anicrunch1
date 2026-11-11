package com.anilili.ui.watch

internal data class SubtitleReprepareState(
    val positionMs: Long,
    val playWhenReady: Boolean,
)

/**
 * Keeps a subtitle-only media-item replacement invisible to the viewer.
 *
 * [playOnOpen] is the Settings switch of the same name. With it off, an episode the player has only
 * just been handed waits on the play button — unless it is one the player moved to itself, which is
 * "Autoplay next episode"'s business and not this switch's.
 *
 * [autoAdvanced] is that signal, carried from the view model rather than guessed at. It used to be
 * inferred from [currentPlayWhenReady] alone: a player still running when the item changed was
 * taken to be mid-binge. That reading holds only while one ExoPlayer instance survives the change,
 * so it said nothing at all for an episode arriving on an embed — where the previous page has
 * already been torn down — and the binge stopped dead at the first credits. [currentPlayWhenReady]
 * is still honoured on top of it, because a next-episode press made mid-episode is the viewer's own
 * playback continuing and must not be cut short either.
 */
internal fun subtitleReprepareState(
    replacingCurrentItem: Boolean,
    currentPositionMs: Long,
    currentPlayWhenReady: Boolean,
    requestedStartPositionMs: Long,
    playOnOpen: Boolean,
    autoAdvanced: Boolean = false,
): SubtitleReprepareState = if (replacingCurrentItem) {
    SubtitleReprepareState(
        positionMs = currentPositionMs.coerceAtLeast(0L),
        playWhenReady = currentPlayWhenReady,
    )
} else {
    SubtitleReprepareState(
        positionMs = requestedStartPositionMs.coerceAtLeast(0L),
        playWhenReady = playOnOpen || autoAdvanced || currentPlayWhenReady,
    )
}
