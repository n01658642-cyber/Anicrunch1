package com.anilili.ui.components

import androidx.compose.runtime.Composable

/**
 * Runs [content] behind a non-inline call, so the compiler emits it as a method of its own.
 *
 * ART will not JIT-compile a method larger than 10 000 code units: it logs "Method exceeds compiler
 * instruction limit" and runs it in the interpreter for the life of the process. A composable's
 * body becomes one method, and `Box`, `Row`, `Column` and `remember` are all inline, so a screen
 * that keeps its whole layout inside one `Box` compiles every line of it into that one method.
 * `EmbedWebView` reached 24 144 units that way and recomposed on every position tick — interpreted,
 * twice a second, for as long as an episode played.
 *
 * Wrapping a section here turns it into a composable lambda, which the compiler emits as a
 * separate class with its own method. Nothing about the section changes: it still reads and writes
 * the same locals (the compiler captures them), it still sits in the same parent layout — this
 * emits no node, so `Modifier.align` and friends reach the enclosing `Box` as before — and it gains
 * its own recompose scope, so a state read inside it no longer invalidates the parent.
 *
 * Measure with `dexdump -d` on the built APK rather than guessing where to cut: sizes are in
 * "insns size", and the goal is every method under the ceiling, not the file under some line count.
 */
@Composable
fun CompiledSeparately(content: @Composable () -> Unit) {
    content()
}
