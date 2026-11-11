package com.anilili.data.library

import kotlinx.serialization.Serializable

/**
 * A list the viewer named themselves — "Favourites", "Watch with Sam" — beside the fixed statuses.
 *
 * On AniList this is one of the account's own custom anime lists. MyAnimeList has no such thing, so
 * there it is a tag: every title carrying the tag is in the list. Signed out, it lives here only.
 *
 * Entries carry the same metadata a save does, so the list draws with no account and no network.
 */
@Serializable
data class CustomList(
    val name: String,
    val entries: List<WatchlistEntry> = emptyList(),
    val createdAt: Long = 0L,
)

/** The longest name offered. AniList has no documented cap; this keeps a chip on one line. */
internal const val MAX_CUSTOM_LIST_NAME = 40

/**
 * [raw] as a list name, or null when nothing usable is left.
 *
 * Commas are refused rather than kept because MyAnimeList stores tags as one comma-separated field:
 * "Sci-fi, space" would come back from a MAL account as two lists. Whitespace is collapsed so two
 * names that look identical on a chip are identical.
 */
internal fun normalizedListName(raw: String): String? =
    raw.replace(',', ' ')
        .trim()
        .replace(WHITESPACE_RUN, " ")
        .take(MAX_CUSTOM_LIST_NAME)
        .trim()
        .takeIf { it.isNotEmpty() }

private val WHITESPACE_RUN = Regex("\\s+")

/** List names are compared the way a viewer reads them: "Faves" and "faves" are one list. */
internal fun listKey(name: String): String = name.trim().lowercase()

internal fun Collection<String>.containsListName(name: String): Boolean = any { listKey(it) == listKey(name) }

/**
 * What this device has changed and the account has not heard yet.
 *
 * Recorded as the individual changes the viewer made rather than as "the list as it stands here",
 * because the account keeps moving meanwhile: a title added to the same list on the phone while this
 * television was offline must survive the television's push, and replaying the television's whole
 * list would delete it. Each field is keyed by [listKey].
 *
 * [creates] and [deletes] can hold the same name at once — deleted, then made again — which reads
 * as "strip whatever the account had in it, then start over", and both halves of the plan honour it.
 */
@Serializable
data class CustomListOps(
    val creates: List<String> = emptyList(),
    val deletes: List<String> = emptyList(),
    val adds: Map<String, List<Int>> = emptyMap(),
    val removes: Map<String, List<Int>> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = creates.isEmpty() && deletes.isEmpty() &&
            adds.values.all { it.isEmpty() } && removes.values.all { it.isEmpty() }

    fun created(name: String): CustomListOps =
        copy(creates = creates.filterNot { listKey(it) == listKey(name) } + name)

    /** Forgets everything pending for the list, and asks for it to go from the account. */
    fun deleted(name: String): CustomListOps {
        val key = listKey(name)
        return copy(
            creates = creates.filterNot { listKey(it) == key },
            deletes = deletes.filterNot { listKey(it) == key } + name,
            adds = adds - key,
            removes = removes - key,
        )
    }

    fun added(name: String, id: Int): CustomListOps {
        val key = listKey(name)
        return copy(
            adds = adds + (key to ((adds[key].orEmpty() - id) + id)),
            removes = (removes + (key to (removes[key].orEmpty() - id))).filterValues { it.isNotEmpty() },
        )
    }

    fun removed(name: String, id: Int): CustomListOps {
        val key = listKey(name)
        return copy(
            removes = removes + (key to ((removes[key].orEmpty() - id) + id)),
            adds = (adds + (key to (adds[key].orEmpty() - id))).filterValues { it.isNotEmpty() },
        )
    }

    /**
     * What is left once [pushed] has landed, keeping anything recorded while it was in flight and
     * anything in [kept] — the changes the push could not deliver, which must stay for the next one.
     */
    fun minus(pushed: CustomListOps, kept: CustomListOps = CustomListOps()): CustomListOps {
        fun names(current: List<String>, done: List<String>, stay: List<String>) =
            current.filter { name -> !done.containsListName(name) || stay.containsListName(name) }
        fun ids(current: Map<String, List<Int>>, done: Map<String, List<Int>>, stay: Map<String, List<Int>>) =
            current.mapValues { (key, list) ->
                list.filter { id -> id !in done[key].orEmpty() || id in stay[key].orEmpty() }
            }.filterValues { it.isNotEmpty() }
        return CustomListOps(
            creates = names(creates, pushed.creates, kept.creates),
            deletes = names(deletes, pushed.deletes, kept.deletes),
            adds = ids(adds, pushed.adds, kept.adds),
            removes = ids(removes, pushed.removes, kept.removes),
        )
    }
}

/** One title's custom lists as the account holds them right now. */
internal data class RemoteMembership(
    /** AniList's list-entry id, or the MyAnimeList anime id: whatever the service deletes or patches by. */
    val entryId: Int,
    /** Every list or tag the title carries, including MAL tags this app has been asked to hide. */
    val lists: List<String>,
    /** AniList's "custom lists only" entry — the kind that should go once it is in no list. */
    val hidden: Boolean = false,
)

/** One title's change, worked out against the account's current state. */
internal sealed interface CustomListEntryChange {
    val anilistId: Int

    /** Replace the title's lists with [lists]; [existing] is null for a title the account lacks. */
    data class Write(
        override val anilistId: Int,
        val lists: List<String>,
        val existing: RemoteMembership?,
    ) : CustomListEntryChange

    /** A list-only entry left in no list at all: nothing is keeping it on the account. */
    data class Delete(override val anilistId: Int, val entryId: Int) : CustomListEntryChange
}

/**
 * The account's list names once [ops] are applied: its own order first, new names after.
 *
 * Only AniList has names apart from entries; on MyAnimeList a list is its tag, and exists exactly
 * while some title carries it.
 */
internal fun plannedListNames(remoteNames: List<String>, ops: CustomListOps): List<String> {
    val kept = remoteNames.filterNot { ops.deletes.containsListName(it) }
    return kept + ops.creates.filterNot { kept.containsListName(it) }
}

/**
 * The per-title writes that bring the account into line with [ops], read against [remote] — the
 * account as fetched at the start of the push, so another device's changes are built on rather than
 * overwritten.
 *
 * [canonicalNames] is the spelling each list should be written with; a name the viewer typed as
 * "faves" still lands on the account's "Faves". Titles in [unsyncable] are skipped: catalogue-only
 * titles have no id either service knows.
 */
internal fun plannedEntryChanges(
    remote: Map<Int, RemoteMembership>,
    ops: CustomListOps,
    canonicalNames: List<String>,
    unsyncable: (Int) -> Boolean = { false },
): List<CustomListEntryChange> {
    fun canonical(key: String): String =
        canonicalNames.firstOrNull { listKey(it) == key }
            ?: ops.creates.firstOrNull { listKey(it) == key }
            ?: key
    val deleted = ops.deletes.map(::listKey).toSet()
    val touched = linkedSetOf<Int>().apply {
        ops.adds.values.forEach(::addAll)
        ops.removes.values.forEach(::addAll)
        if (deleted.isNotEmpty()) {
            remote.forEach { (id, membership) ->
                if (membership.lists.any { listKey(it) in deleted }) add(id)
            }
        }
    }
    return touched.filterNot(unsyncable).mapNotNull { id ->
        val existing = remote[id]
        val current = existing?.lists.orEmpty()
        val removedKeys = ops.removes.filterValues { id in it }.keys
        val addedKeys = ops.adds.filterValues { id in it }.keys
        val next = current
            .filterNot { listKey(it) in deleted || listKey(it) in removedKeys }
            .let { kept -> kept + addedKeys.filterNot { key -> kept.any { listKey(it) == key } }.map(::canonical) }
        when {
            next.map(::listKey).toSet() == current.map(::listKey).toSet() -> null
            existing == null -> if (next.isEmpty()) null else CustomListEntryChange.Write(id, next, null)
            existing.hidden && next.isEmpty() -> CustomListEntryChange.Delete(id, existing.entryId)
            else -> CustomListEntryChange.Write(id, next, existing)
        }
    }
}

/**
 * This device's lists after a refresh: the account's, with this device's unpushed changes on top.
 *
 * The account is the truth for everything [ops] does not mention, which is what carries a list
 * made, emptied or deleted on another device here. A list the account no longer has and nothing
 * here is still creating was deleted somewhere else, and goes.
 *
 * [remoteMembers] is keyed by [listKey]. Local order is kept; lists new to this device follow it.
 * [keepLocal] marks titles that can never reach the account (catalogue-only ones), which stay in
 * whatever list they were put in here.
 */
internal fun mergedCustomLists(
    local: List<CustomList>,
    remoteNames: List<String>,
    remoteMembers: Map<String, List<WatchlistEntry>>,
    ops: CustomListOps,
    keepLocal: (Int) -> Boolean = { false },
    now: Long = System.currentTimeMillis(),
): List<CustomList> {
    val names = plannedListNames(remoteNames, ops)
    val ordered = local.map { it.name }.filter { names.containsListName(it) } +
        names.filterNot { name -> local.any { listKey(it.name) == listKey(name) } }
    return ordered.map { name ->
        val key = listKey(name)
        val mine = local.firstOrNull { listKey(it.name) == key }
        val localById = mine?.entries.orEmpty().associateBy { it.anilistId }
        val fromAccount = if (ops.deletes.containsListName(name)) emptyList() else remoteMembers[key].orEmpty()
        val removed = ops.removes[key].orEmpty().toSet()
        val added = ops.adds[key].orEmpty()
        val entries = buildList {
            fromAccount.forEach { remote ->
                if (remote.anilistId in removed) return@forEach
                // The account's title and cover are fresher; when it joined the list is ours, and a
                // title new to this device dates from the account's last touch of the entry.
                val addedAt = localById[remote.anilistId]?.addedAt
                    ?: remote.addedAt.takeIf { it > 0 }
                    ?: now
                add(remote.copy(addedAt = addedAt))
            }
            added.forEach { id -> localById[id]?.let(::add) }
            mine?.entries.orEmpty().filter { keepLocal(it.anilistId) && it.anilistId !in removed }.forEach(::add)
        }.distinctBy { it.anilistId }.sortedByDescending { it.addedAt }
        CustomList(
            // The account's spelling wins once it has one, so every device shows the same name.
            name = remoteNames.firstOrNull { listKey(it) == key } ?: mine?.name ?: name,
            entries = entries,
            createdAt = mine?.createdAt ?: now,
        )
    }
}
