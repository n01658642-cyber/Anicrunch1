package com.anilili.ui.watch

/** What the current episode's skip-intro/outro capability is doing. */
enum class SkipTimingStatus(val playerMessage: String) {
    PROVIDER("Skip timing supplied by this server"),
    WAITING_FOR_DURATION("Skip timing will be checked when playback starts"),
    CHECKING("Checking skip timing for this video…"),
    ANISKIP("Skip timing matched by AniSkip"),
    UNAVAILABLE("Skip timing is unavailable for this episode"),
    SERVICE_ERROR("Skip timing service is temporarily unavailable"),
}

/**
 * How far a report of a wrong AniSkip timing has got.
 *
 * The database is community-submitted, so a downvote is the only thing that improves a bad window
 * for anyone else. The row reports its own progress rather than raising a toast, because the player
 * is regularly fullscreen on a television where a toast is both easy to miss and unreachable.
 */
enum class SkipReportState(val rowTitle: String, val rowDescription: String?) {
    IDLE("Report wrong skip timing", "Tells AniSkip this episode's window is off"),
    SENDING("Reporting…", null),
    SENT("Reported", "Thanks — AniSkip has your vote"),
    FAILED("Report failed", "Could not reach AniSkip. Try again"),
}
