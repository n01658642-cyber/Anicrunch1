package com.anilili.ui.detail

internal fun isMovieFormat(format: String?): Boolean = format.equals("MOVIE", ignoreCase = true)

internal fun movieAwareWatchLabel(format: String?, hasResume: Boolean): String = when {
    isMovieFormat(format) && hasResume -> "Continue movie"
    isMovieFormat(format) -> "Watch movie"
    hasResume -> "Continue"
    else -> "Watch"
}

