package com.anilili.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/**
 * Records the actual list declarations before any rows are composed. Search order is not display
 * order, and conditional rows and notices change indices, so the search index cannot supply them.
 * Only the small declaration list is eager; row composition remains lazy.
 */
internal class IndexedSettingsContent : LazyListScope {
    private val keys = mutableListOf<Any?>()
    private val declarations = mutableListOf<LazyListScope.() -> Unit>()

    fun indexOf(key: Any?): Int = if (key == null) -1 else keys.indexOf(key)

    fun emit(scope: LazyListScope) {
        declarations.forEach { it(scope) }
    }

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        keys += key
        declarations += { item(key, contentType, content) }
    }

    override fun items(
        count: Int,
        key: ((Int) -> Any)?,
        contentType: (Int) -> Any?,
        itemContent: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        repeat(count) { keys += key?.invoke(it) }
        declarations += { items(count, key, contentType, itemContent) }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        keys += key
        declarations += { stickyHeader(key, contentType, content) }
    }
}
