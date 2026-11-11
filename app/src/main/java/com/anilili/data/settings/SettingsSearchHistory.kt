package com.anilili.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What the viewer has looked for in Settings before, most recent first.
 *
 * Kept apart from `SearchHistoryStore`, which remembers anime titles: offering "clear cache" while
 * somebody searches for a show, or a show's name while they search Settings, would be noise in both
 * directions. It is also deliberately not part of the backup document — a list of settings someone
 * went looking for says more about them than it is worth carrying between devices.
 */
object SettingsSearchHistory {
    private const val PREFS = "anilili_settings_search_history"
    private const val KEY = "queries"
    private const val MAX_ENTRIES = 8

    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history = _history.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        _history.value = runCatching {
            store.getString(KEY, null)
                ?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
        }.getOrNull().orEmpty()
    }

    /**
     * Remember a term that was actually used to reach a setting.
     *
     * An existing term moves to the front rather than appearing twice, so the list reads as "most
     * recent" and never fills with one query typed on five different days.
     */
    fun record(query: String) {
        val term = query.trim()
        if (term.isEmpty()) return
        val next = (listOf(term) + _history.value.filterNot { it.equals(term, ignoreCase = true) })
            .take(MAX_ENTRIES)
        if (next == _history.value) return
        _history.value = next
        persist(next)
    }

    fun remove(query: String) {
        val next = _history.value.filterNot { it.equals(query.trim(), ignoreCase = true) }
        if (next == _history.value) return
        _history.value = next
        persist(next)
    }

    fun clear() {
        if (_history.value.isEmpty()) return
        _history.value = emptyList()
        persist(emptyList())
    }

    private fun persist(entries: List<String>) {
        val store = prefs ?: return
        runCatching {
            store.edit()
                .putString(KEY, json.encodeToString(ListSerializer(String.serializer()), entries))
                .apply()
        }
    }
}
