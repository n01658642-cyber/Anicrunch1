package com.anilili.data.recs

import android.content.SharedPreferences
import com.anilili.data.model.MediaListEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What the taste model needs that the library does not keep: the account's scores and dates, and
 * the dice's swipes.
 *
 * The library's status snapshot is status only — that is all its screens need — and widening it
 * would touch every reader of it. This keeps its own copy, written from the same account refresh.
 *
 * Shares `miruro_library` with [com.anilili.data.library.LibraryStore], like CustomListStore, so a
 * TV profile switch carries each viewer's taste with the rest of their library.
 */
object TasteStore {
    @Serializable
    private data class StoredRating(val id: Int, val status: String, val score: Double = 0.0, val updatedAtSec: Long = 0)

    @Serializable
    private data class StoredFeedback(val id: Int, val weight: Double)

    private const val KEY_RATINGS = "taste_ratings"
    private const val KEY_FEEDBACK = "taste_dice"

    /** Swipes remembered; the oldest go first. A few hundred is a long evening of rolling. */
    private const val MAX_FEEDBACK = 300

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences

    private val _ratings = MutableStateFlow<List<ListRating>>(emptyList())
    val ratings = _ratings.asStateFlow()

    private val _feedback = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val feedback = _feedback.asStateFlow()

    fun init(libraryPrefs: SharedPreferences) {
        prefs = libraryPrefs
        _ratings.value = decode(KEY_RATINGS, StoredRating.serializer())
            .map { ListRating(it.id, it.status, it.score, it.updatedAtSec * 1000) }
        // LinkedHashMap keeps swipe order, which is what the cap trims by.
        _feedback.value = decode(KEY_FEEDBACK, StoredFeedback.serializer())
            .associateTo(LinkedHashMap()) { it.id to it.weight }
    }

    /** Called with every account refresh's entries, the same ones the library's snapshot is built from. */
    fun recordAccount(entries: List<MediaListEntry>) {
        val ratings = entries.mapNotNull { entry ->
            val id = entry.media?.id ?: return@mapNotNull null
            val status = entry.status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ListRating(id, status, entry.score, entry.updatedAt * 1000)
        }.distinctBy { it.anilistId }
        _ratings.value = ratings
        persist(
            KEY_RATINGS,
            ratings.map { StoredRating(it.anilistId, it.status, it.score, it.updatedAtMs / 1000) },
            StoredRating.serializer(),
        )
    }

    /** Signing out, or into another account: its taste is not this viewer's. */
    fun clearAccount() {
        _ratings.value = emptyList()
        if (::prefs.isInitialized) prefs.edit().remove(KEY_RATINGS).apply()
    }

    /** A dice card swiped right ([opened]) or away. */
    fun recordDice(anilistId: Int, opened: Boolean) {
        if (!::prefs.isInitialized) return
        val updated = LinkedHashMap(_feedback.value)
        updated.remove(anilistId)
        updated[anilistId] = if (opened) TasteEngine.DICE_OPENED else TasteEngine.DICE_SKIPPED
        while (updated.size > MAX_FEEDBACK) updated.remove(updated.keys.first())
        _feedback.value = updated
        persist(KEY_FEEDBACK, updated.map { (id, weight) -> StoredFeedback(id, weight) }, StoredFeedback.serializer())
    }

    private fun <T> decode(key: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        prefs.getString(key, null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(serializer), it) }.getOrNull() }
            .orEmpty()

    private fun <T> persist(key: String, items: List<T>, serializer: kotlinx.serialization.KSerializer<T>) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(key, json.encodeToString(ListSerializer(serializer), items)).apply()
    }
}
