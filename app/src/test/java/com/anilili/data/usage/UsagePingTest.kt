package com.anilili.data.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The once-a-day ping's rules (`docs/USAGE-PING.md`), shared with iOS and the desktop. */
class UsagePingTest {
    private val device = "a".repeat(64)

    private fun body(lastDay: String?, today: String, accounts: List<String> = emptyList()) =
        UsagePing.body(device, "android-tv", "0.1.83", 94, accounts, lastDay, today)
            ?.let { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun theDeviceIdIsTheSaltedHashEveryAppMakes() {
        // The same vector is in the iOS and desktop tests.
        assertEquals(
            "0eb0cde3e2356397f8217597eace93a803af7fcf676c9fa42d98c23a5f7f5b97",
            UsagePing.deviceId("9774d56d682e549c"),
        )
    }

    @Test
    fun thePersonHashIsTheOneEveryAppMakes() {
        // The same vector is in the iOS and desktop tests and in docs/USAGE-PING.md.
        assertEquals(
            "79f2ba9972b3e2f1fb4ee6ce0e47fe7ca11faa2c8d7d435eed44eac40f938860",
            UsagePing.personHash("anilist", 123456),
        )
        assertNotEquals(UsagePing.personHash("anilist", 123456), UsagePing.personHash("mal", 123456))
    }

    @Test
    fun onceADay() {
        assertNull(body(lastDay = "2026-10-12", today = "2026-10-12"))
    }

    @Test
    fun theBodyUsesTheContractsNames() {
        val sent = body(lastDay = "2026-10-11", today = "2026-10-12", accounts = listOf("anilist", "simkl"))!!
        assertEquals(
            setOf("device", "platform", "version", "build", "accounts", "first", "people", "env", "settings", "usage"),
            sent.keys,
        )
        assertEquals(device, sent["device"]!!.jsonPrimitive.content)
        assertEquals("android-tv", sent["platform"]!!.jsonPrimitive.content)
        assertEquals("0.1.83", sent["version"]!!.jsonPrimitive.content)
        assertEquals(94, sent["build"]!!.jsonPrimitive.int)
        assertEquals(listOf("anilist", "simkl"), sent["accounts"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(sent["first"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun versionTwoCarriesPeopleTheDeviceSettingsAndUsage() {
        val sent = UsagePing.body(
            device, "android-tv", "0.1.90", 101, listOf("anilist"), "2026-10-12", "2026-10-13",
            people = listOf("ab".repeat(32)),
            env = buildJsonObject { put("os", "11"); put("api", 30); put("maker", "Amazon"); put("model", "AFTKA") },
            settings = buildJsonObject { put("quality", "1080"); put("audio", "sub") },
            usage = UsagePing.Usage(minutes = 95, watched = 60, sessions = 3),
        )!!.let { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("ab".repeat(32)), sent["people"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("AFTKA", sent["env"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals(30, sent["env"]!!.jsonObject["api"]!!.jsonPrimitive.int)
        assertEquals("1080", sent["settings"]!!.jsonObject["quality"]!!.jsonPrimitive.content)
        val usage = sent["usage"]!!.jsonObject
        assertEquals(listOf(95, 60, 3), listOf("minutes", "watched", "sessions").map { usage[it]!!.jsonPrimitive.int })
    }

    @Test
    fun firstOnlyWithoutAnEarlierPing() {
        val fresh = body(lastDay = null, today = "2026-10-12")!!
        assertTrue(fresh["first"]!!.jsonPrimitive.boolean)
        assertTrue(fresh["accounts"]!!.jsonArray.isEmpty())
        assertFalse(body(lastDay = "2025-01-01", today = "2026-10-12")!!["first"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun accountsNameServicesInTheContractsOrder() {
        assertEquals(emptyList<String>(), UsagePing.accounts(anilist = false, mal = false, simkl = false))
        assertEquals(listOf("mal", "simkl"), UsagePing.accounts(anilist = false, mal = true, simkl = true))
    }

    @Test
    fun valuesAreCleanedForTheServer() {
        assertEquals("Dell Inc.", UsagePing.clean("  Dell   Inc.™ ", 40))
        assertEquals("Micro-Star International", UsagePing.clean("Micro-Star International Co., Ltd.", 24))
        assertEquals("", UsagePing.clean(null, 10))
    }
}
