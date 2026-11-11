package com.anilili.ui

import com.anilili.errors.AnililiFailure
import kotlinx.coroutines.CancellationException

/** Minimal load/success/error wrapper used by every screen's ViewModel. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>

    /**
     * A failed load, carrying the catalogued failure rather than a sentence.
     *
     * Every screen's ViewModel used to build this as `UiState.Error(e.message ?: "Failed to load")`
     * — which meant the text a viewer saw was whichever exception happened to surface, in whatever
     * wording its library chose, with no way to match it against a diagnostics bundle afterwards.
     * Holding the failure means the screen can show one line and a code, and the same code is
     * already in the log.
     *
     * There is deliberately no constructor taking a bare catalogue entry. Every failure here comes
     * from `report` or `toFailure`, so reaching this state and writing the diagnostics event are the
     * same act — a convenience that skipped the report is how the two drifted apart before.
     */
    data class Error(val failure: AnililiFailure) : UiState<Nothing>
}

/**
 * Rethrows [CancellationException] so a cancelled coroutine is never reported as a failure.
 * Call first inside every `catch (e: Exception)` / `onFailure` block in a ViewModel.
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
