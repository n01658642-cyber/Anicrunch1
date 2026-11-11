package com.anilili.data.library

import android.content.SharedPreferences
import com.anilili.data.AppGraph
import com.anilili.data.auth.AccountService
import com.anilili.data.model.MediaListEntry
import com.anilili.data.remote.CustomListWrite
import com.anilili.data.remote.isCatalogueNativeMediaId
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The viewer's custom lists, kept on the device and in step with the signed-in account.
 *
 * Every change lands here first and is recorded as a pending [CustomListOps]; a push then carries
 * the pending changes to the account and a refresh brings back what the account holds, with
 * anything still pending laid on top. Signed out, the changes simply wait — signing in pushes them.
 *
 * Shares `miruro_library` with [LibraryStore] so a TV profile switch swaps these with the rest of
 * the library.
 */
object CustomListStore {
    private const val KEY_LISTS = "custom_lists"
    private const val KEY_OPS = "custom_list_ops"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pushMutex = Mutex()

    private val _lists = MutableStateFlow<List<CustomList>>(emptyList())
    val lists = _lists.asStateFlow()

    /**
     * Every tag the signed-in MyAnimeList account uses, hidden ones included — what Settings offers
     * to show or hide. Empty for AniList and when signed out.
     */
    private val _malTags = MutableStateFlow<List<String>>(emptyList())
    val malTags = _malTags.asStateFlow()

    @Volatile private var ops = CustomListOps()

    internal fun init(prefs: SharedPreferences) {
        this.prefs = prefs
        _lists.value = runCatching {
            prefs.getString(KEY_LISTS, null)?.let {
                json.decodeFromString(ListSerializer(CustomList.serializer()), it)
            }
        }.getOrNull().orEmpty()
        ops = runCatching {
            prefs.getString(KEY_OPS, null)?.let { json.decodeFromString(CustomListOps.serializer(), it) }
        }.getOrNull() ?: CustomListOps()
    }

    fun list(name: String): CustomList? = _lists.value.firstOrNull { listKey(it.name) == listKey(name) }

    fun listsContaining(anilistId: Int): List<CustomList> =
        _lists.value.filter { list -> list.entries.any { it.anilistId == anilistId } }

    /**
     * Make a new, empty list. Returns the name it was stored under, or null when the name is
     * unusable or already taken.
     */
    @Synchronized
    fun create(rawName: String): String? {
        val name = normalizedListName(rawName) ?: return null
        if (list(name) != null) return null
        // A list named after a MAL tag the viewer hid would vanish on the next refresh. Making it is
        // asking to see it, so the tag comes out of hiding.
        SettingsStore.hiddenMalTags.value.firstOrNull { listKey(it) == listKey(name) }?.let { tag ->
            SettingsStore.setHiddenMalTags(SettingsStore.hiddenMalTags.value - tag)
        }
        update(
            lists = listOf(CustomList(name, createdAt = System.currentTimeMillis())) + _lists.value,
            ops = ops.created(name),
        )
        DiagnosticsLog.event("Custom list created")
        return name
    }

    /** Rename [from] to [rawTo], keeping its titles. Returns the new name, or null when refused. */
    @Synchronized
    fun rename(from: String, rawTo: String): String? {
        val current = list(from) ?: return null
        val to = normalizedListName(rawTo) ?: return null
        if (to == current.name) return to
        if (listKey(to) != listKey(current.name) && list(to) != null) return null
        var next = ops.deleted(current.name).created(to)
        current.entries.forEach { entry ->
            if (!isCatalogueNativeMediaId(entry.anilistId)) next = next.added(to, entry.anilistId)
        }
        update(
            lists = _lists.value.map { if (it === current) it.copy(name = to) else it },
            ops = next,
        )
        DiagnosticsLog.event("Custom list renamed titles=${current.entries.size}")
        return to
    }

    @Synchronized
    fun delete(name: String) {
        val current = list(name) ?: return
        update(lists = _lists.value - current, ops = ops.deleted(current.name))
        DiagnosticsLog.event("Custom list deleted titles=${current.entries.size}")
    }

    /** Put [entry] into, or take it out of, the list called [name]. */
    @Synchronized
    fun setMember(name: String, entry: WatchlistEntry, member: Boolean) {
        val current = list(name) ?: return
        val present = current.entries.any { it.anilistId == entry.anilistId }
        if (present == member) return
        val entries = if (member) {
            listOf(entry.copy(addedAt = System.currentTimeMillis())) + current.entries
        } else {
            current.entries.filterNot { it.anilistId == entry.anilistId }
        }
        var next = when {
            // A catalogue-only title has no id either service knows, so it lives in the list here only.
            isCatalogueNativeMediaId(entry.anilistId) -> ops
            member -> ops.added(current.name, entry.anilistId)
            else -> ops.removed(current.name, entry.anilistId)
        }
        // MyAnimeList has no empty lists — a list is a tag, and a tag nobody carries is gone. Emptying
        // one here would read as a deletion on the next refresh; held as pending, it stays until the
        // next title is added to it, exactly as a list made here and not yet filled does.
        if (!member && entries.isEmpty() && AccountService.active == AccountService.MAL) {
            next = next.created(current.name)
        }
        update(
            lists = _lists.value.map { if (it === current) it.copy(entries = entries) else it },
            ops = next,
        )
    }

    /**
     * Take the account's lists after a refresh. [entries] is the account's whole collection;
     * [aniListNames] is AniList's own list of names, null on MyAnimeList, where the tags are the lists.
     */
    @Synchronized
    internal fun hydrateFromAccount(
        service: AccountService,
        entries: List<MediaListEntry>,
        aniListNames: List<String>?,
    ) {
        val hiddenTags = if (service == AccountService.MAL) SettingsStore.hiddenMalTags.value else emptySet()
        _malTags.value = if (service == AccountService.MAL) {
            entries.flatMap { it.customLists }.distinctBy(::listKey).sortedBy { it.lowercase() }
        } else {
            emptyList()
        }
        fun shown(name: String) = hiddenTags.none { listKey(it) == listKey(name) }
        val members = LinkedHashMap<String, MutableList<WatchlistEntry>>()
        val tagOrder = mutableListOf<String>()
        entries.forEach { entry ->
            val media = entry.media ?: return@forEach
            entry.customLists.filter(::shown).forEach { name ->
                if (!tagOrder.containsListName(name)) tagOrder += name
                members.getOrPut(listKey(name)) { mutableListOf() } += WatchlistEntry(
                    anilistId = media.id,
                    title = media.title.preferred,
                    cover = media.coverImage.best,
                    format = media.format,
                    averageScore = media.averageScore,
                    addedAt = entry.updatedAt * 1_000L,
                )
            }
        }
        val names = (aniListNames ?: tagOrder.sortedBy { it.lowercase() }).filter(::shown)
        val merged = mergedCustomLists(
            local = _lists.value,
            remoteNames = names,
            remoteMembers = members,
            ops = ops,
            keepLocal = ::isCatalogueNativeMediaId,
        )
        if (merged != _lists.value) {
            _lists.value = merged
            persistLists()
        }
        // Only what the last push has not already tried. What it kept on purpose — an empty MAL
        // list, a title MAL has no id for — would otherwise be re-sent by every refresh, and every
        // push ends in a refresh: a loop that never settles.
        if (!ops.isEmpty && ops != settledOps) pushPending()
    }

    /** What was left pending when the last push succeeded — pending by design, not by failure. */
    private var settledOps: CustomListOps? = null

    /**
     * The account is being left — a sign-out, or a switch to the other service. Everything on the
     * device becomes pending again, so whichever account comes next receives these lists rather
     * than the device quietly keeping a copy of the old account's.
     */
    @Synchronized
    internal fun onAccountLeft() {
        _malTags.value = emptyList()
        var next = CustomListOps()
        _lists.value.forEach { list ->
            next = next.created(list.name)
            list.entries.forEach { entry ->
                if (!isCatalogueNativeMediaId(entry.anilistId)) next = next.added(list.name, entry.anilistId)
            }
        }
        ops = next
        persistOps()
    }

    /** Merge lists from a backup: same-named lists pool their titles, new ones are added. */
    @Synchronized
    fun importLists(imported: List<CustomList>): Int {
        var lists = _lists.value
        var next = ops
        var added = 0
        imported.forEach { incoming ->
            val name = normalizedListName(incoming.name) ?: return@forEach
            val existing = lists.firstOrNull { listKey(it.name) == listKey(name) }
            val fresh = incoming.entries.filter { entry ->
                existing?.entries?.none { it.anilistId == entry.anilistId } ?: true
            }
            if (existing == null) {
                lists = lists + CustomList(name, fresh, incoming.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis())
                next = next.created(name)
                added++
            } else {
                lists = lists.map { if (it === existing) it.copy(entries = fresh + it.entries) else it }
            }
            fresh.forEach { if (!isCatalogueNativeMediaId(it.anilistId)) next = next.added(name, it.anilistId) }
        }
        update(lists, next)
        return added
    }

    /** Carry pending changes to the signed-in account. Safe to call at any time; it does nothing idle. */
    fun pushPending() {
        val service = AccountService.active ?: return
        if (ops.isEmpty) return
        scope.launch {
            pushMutex.withLock {
                val sending = ops
                if (sending.isEmpty) return@withLock
                runCatching {
                    when (service) {
                        AccountService.ANILIST -> pushToAniList(sending)
                        AccountService.MAL -> pushToMal(sending)
                    }
                }.onSuccess { kept ->
                    synchronized(this@CustomListStore) {
                        ops = ops.minus(sending, kept)
                        settledOps = ops
                        persistOps()
                    }
                    DiagnosticsLog.event("${service.label} custom lists pushed outstanding=${!ops.isEmpty}")
                    if (sending != kept) LibraryStore.refreshRemoteLibrary(force = true)
                }.onFailure {
                    DiagnosticsLog.throwable("${service.label} custom list push failed", it)
                }
            }
        }
    }

    /** Pushes [sending] to AniList; returns what could not be delivered and must stay pending. */
    private suspend fun pushToAniList(sending: CustomListOps): CustomListOps {
        val repo = AppGraph.repository
        val collection = repo.aniListCollectionForCustomLists()
        val remoteNames = collection.customListNames
        val names = plannedListNames(remoteNames, sending)
        if (names != remoteNames) repo.setAniListCustomListNames(names)
        val remote = collection.lists.asSequence()
            .flatMap { it.entries }
            .filter { it.media != null }
            .distinctBy { it.media!!.id }
            .associate { it.media!!.id to RemoteMembership(it.id, it.customLists, it.hiddenFromStatusLists) }
        val changes = plannedEntryChanges(remote, sending, names, ::isCatalogueNativeMediaId)
        val writes = changes.filterIsInstance<CustomListEntryChange.Write>()
        val deletes = changes.filterIsInstance<CustomListEntryChange.Delete>()
        repo.saveAniListCustomLists(writes.map { CustomListWrite(it.anilistId, it.lists, create = it.existing == null) })
        repo.deleteAniListEntries(deletes.map { it.entryId })
        DiagnosticsLog.event(
            "AniList custom lists written names=${names.size} titles=${writes.size} " +
                "created=${writes.count { it.existing == null }} listOnlyDeleted=${deletes.size}",
        )
        return catalogueOnlyAdds(sending)
    }

    /** Pushes [sending] to MyAnimeList as tags; returns what must stay pending. */
    private suspend fun pushToMal(sending: CustomListOps): CustomListOps {
        val repo = AppGraph.repository
        val entries = repo.malAnimeList()
        val remote = entries.filter { it.media != null }
            .associate { it.media!!.id to RemoteMembership(it.id, it.customLists) }
        val canonical = _lists.value.map { it.name } + entries.flatMap { it.customLists }
        val changes = plannedEntryChanges(remote, sending, canonical, ::isCatalogueNativeMediaId)
        val unmapped = mutableSetOf<Int>()
        changes.filterIsInstance<CustomListEntryChange.Write>().forEach { write ->
            val malId = write.existing?.entryId ?: repo.malIdFor(write.anilistId)
            if (malId == null) {
                unmapped += write.anilistId
                DiagnosticsLog.event("MAL tags skipped id=${write.anilistId}: no MAL id on AniList")
                return@forEach
            }
            repo.malSetTags(malId, write.lists, create = write.existing == null)
        }
        // Lists with nothing in them cannot exist on MAL; they stay pending, and so on this device.
        val emptyHere = sending.creates.filter { name -> list(name)?.entries.isNullOrEmpty() }
        val unmappedAdds = sending.adds.mapValues { (_, ids) -> ids.filter { it in unmapped } }
            .filterValues { it.isNotEmpty() }
        val catalogue = catalogueOnlyAdds(sending)
        return CustomListOps(
            creates = emptyHere,
            adds = (unmappedAdds.keys + catalogue.adds.keys).associateWith { key ->
                (unmappedAdds[key].orEmpty() + catalogue.adds[key].orEmpty()).distinct()
            },
        )
    }

    /** Catalogue-only titles have no id either service knows; their additions are kept, never sent. */
    private fun catalogueOnlyAdds(sending: CustomListOps) = CustomListOps(
        adds = sending.adds.mapValues { (_, ids) -> ids.filter(::isCatalogueNativeMediaId) }
            .filterValues { it.isNotEmpty() },
    )

    private fun update(lists: List<CustomList>, ops: CustomListOps) {
        _lists.value = lists
        this.ops = ops
        persistLists()
        persistOps()
        pushPending()
    }

    private fun persistLists() {
        prefs.edit()
            .putString(KEY_LISTS, json.encodeToString(ListSerializer(CustomList.serializer()), _lists.value))
            .apply()
    }

    private fun persistOps() {
        prefs.edit().putString(KEY_OPS, json.encodeToString(CustomListOps.serializer(), ops)).apply()
    }
}
