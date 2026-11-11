package com.anilili.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The cases this app and the desktop app must answer identically.
 *
 * They are the desktop repo's `fixtures/parity/`, copied into `src/test/resources/parity` by the
 * `syncParityCorpus` Gradle task whenever that checkout sits next to this one. The tests read the
 * copy, so they run the same with or without the sibling. A case one app fails is a disagreement
 * to settle, not a test to delete — see the corpus README.
 */
object ParityCorpus {
    fun load(name: String): JsonObject {
        val stream = ParityCorpus::class.java.getResourceAsStream("/parity/$name")
            ?: error("parity/$name is missing from the test resources")
        return Json.parseToJsonElement(stream.use { it.reader(Charsets.UTF_8).readText() }).jsonObject
    }

    /**
     * The cases under [key]. A section that is missing or empty fails rather than passing vacuously,
     * so a key renamed on one side cannot quietly switch a check off on the other.
     */
    fun JsonObject.cases(key: String): List<JsonObject> {
        val section = get(key) ?: error("the corpus has no \"$key\" section")
        return section.jsonArray.map { it.jsonObject }
            .also { check(it.isNotEmpty()) { "the corpus's \"$key\" section is empty" } }
    }

    fun JsonObject.string(key: String): String =
        get(key)?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("\"$key\" is not a string in $this")

    fun JsonObject.double(key: String): Double =
        get(key)?.jsonPrimitive?.content?.toDoubleOrNull() ?: error("\"$key\" is not a number in $this")

    /** [key] as an Int or null, telling an explicit `null` apart from a key that is not there. */
    fun JsonObject.nullableInt(key: String): Int? = when (val value = get(key)) {
        null -> error("\"$key\" is missing from $this")
        JsonNull -> null
        else -> value.jsonPrimitive.content.toIntOrNull() ?: error("\"$key\" is not an integer in $this")
    }

    /** One line naming a case, for a failure message: its input and, if it has one, its `note`. */
    fun JsonObject.describe(vararg keys: String): String {
        val inputs = keys.joinToString(", ") { "$it=${get(it).render()}" }
        val note = (get("note") as? JsonPrimitive)?.content
        return if (note == null) inputs else "$inputs ($note)"
    }

    private fun JsonElement?.render(): String = when (this) {
        null -> "<absent>"
        is JsonPrimitive -> if (isString) "\"$content\"" else content
        is JsonArray, is JsonObject -> toString()
    }
}
