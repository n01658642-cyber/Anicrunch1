package com.anilili.data.profiles

import android.content.Context
import com.anilili.data.AppGraph
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class AvatarChoice(val name: String, val url: String)

/**
 * The anime characters a profile can wear, taken from AniList's most-favourited list.
 *
 * Fetched once and kept, so the picker opens instantly afterwards and still has pictures with no
 * connection (Coil's disk cache holds the images themselves). Refreshed at most weekly: the top of
 * that list barely moves, and every AniList request spends a shared, rate-limited budget.
 */
object ProfileAvatars {
    private const val PREFS = "anilili_profile_avatars"
    private const val KEY_LIST = "list"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AvatarChoice.serializer())
    private val mutex = Mutex()

    private val _choices = MutableStateFlow<List<AvatarChoice>>(emptyList())
    val choices: StateFlow<List<AvatarChoice>> = _choices.asStateFlow()

    suspend fun load(context: Context) = mutex.withLock {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (_choices.value.isEmpty()) {
            _choices.value = runCatching {
                prefs.getString(KEY_LIST, null)?.let { json.decodeFromString(serializer, it) }
            }.getOrNull().orEmpty()
        }
        val fresh = System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0L) < REFRESH_MS
        if (_choices.value.isNotEmpty() && fresh) return@withLock
        try {
            val fetched = AppGraph.aniList.popularCharacters().map { (name, url) -> AvatarChoice(name, url) }
            if (fetched.isNotEmpty()) {
                _choices.value = fetched
                prefs.edit()
                    .putString(KEY_LIST, json.encodeToString(serializer, fetched))
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply()
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            DiagnosticsLog.throwable("Profile avatars fetch failed", e)
        }
    }
}
