package com.anilili.diagnostics

/**
 * Whether the catalogue cache is doing its job on this device, in a handful of numbers.
 *
 * A bundle carries thousands of events and nobody reads them by hand. The question that matters
 * for this cache is not what one provider did, it is the ratio: a device fetching every provider
 * every time is a cache that is not being written, or is being evicted before it is read, and that
 * looks completely normal one event at a time.
 *
 * Kept apart from [DiagnosticsLog] so it can be tested. The numbers here are the ones that were
 * actually missing while chasing a cache that appeared to store rows and never read one back, and
 * a summary nobody can check is worth about as much as no summary.
 */
internal object CatalogueCacheSummary {
    fun render(events: List<DiagnosticEvent>): String {
        val all = events.filter { it.name == "catalog.summary" }
        if (all.isEmpty()) return "no catalogue lookups in this window"
        // A refresh is supposed to ask every provider, so counting one into the ratio makes a
        // healthy device look like a cache that is not working. Kept visible, kept out of the sum.
        val refreshes = all.count { it.attributes["refresh"] == "true" }
        val summaries = all.filter { it.attributes["refresh"] != "true" }
        fun total(key: String) = summaries.sumOf { it.attributes[key]?.toIntOrNull() ?: 0 }
        val drifted = events.filter { it.name == "catalog.availability.changed" }
        // Rows written against rows read back. The cache deletes anything it cannot decode without
        // saying so, so a device that stores rows and reads none back is a real bug that otherwise
        // looks exactly like a device that never stored one — the two are only separable here.
        val rowsStored = events.count { it.name == "catalog.availability.stored" }
        val rowReads = events.filter { it.name == "catalog.availability.row" }
        val rowsFound = rowReads.count { (it.attributes["providers"]?.toIntOrNull() ?: -1) >= 0 }
        return buildString {
            append("lookups=${summaries.size}")
            if (refreshes > 0) append(" (+$refreshes refresh)")
            append(" asked=${total("asked")} answered=${total("answered")}")
            append(" fromDisk=${total("fromDisk")} fromSeed=${total("fromSeed")} fetched=${total("fetched")}")
            appendLine()
            append("rows stored=$rowsStored read=${rowReads.size} found=$rowsFound")
            if (rowsStored > 0 && rowReads.isNotEmpty() && rowsFound == 0) {
                append(" — stored rows are never coming back; the cache is dropping them")
            }
            // A wrong remembered answer is the failure this cache can cause, and the viewer only
            // sees a source that will not play. Listed per provider so a bundle names it.
            if (drifted.isNotEmpty()) {
                appendLine()
                append("answers that changed under a remembered value: ${drifted.size}")
                drifted.take(MAX_DRIFT_LINES).forEach { event ->
                    appendLine()
                    append("  ${event.attributes["provider"]} id=${event.attributes["anilistId"]} ")
                    append("from=${event.attributes["from"]} ")
                    append("sub ${event.attributes["wasSub"]}->${event.attributes["nowSub"]} ")
                    append("dub ${event.attributes["wasDub"]}->${event.attributes["nowDub"]}")
                }
            }
        }
    }

    private const val MAX_DRIFT_LINES = 8
}
