package com.anilili.data.settings

import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup registry has to be edited by hand every time a setting is added, and the failure mode
 * of forgetting is silent: the setting simply never appears in anyone's backup, and nobody finds
 * out until they restore one and it is missing.
 *
 * So the registry is checked against the store itself. A new preference must be listed as backed up
 * or as deliberately excluded — either is a decision, and neither can be reached by accident.
 */
class SettingsBackupKeyTest {

    // A key held in a map or list counts too: the per-section liquid-glass switches are one key
    // per GlassSection, declared together as GLASS_KEYS rather than as five separate fields.
    private fun declaredKeyNames(): Set<String> = SettingsStore::class.java.declaredFields
        .flatMap { field ->
            field.isAccessible = true
            when (val value = runCatching { field.get(SettingsStore) }.getOrNull()) {
                is Preferences.Key<*> -> listOf(value)
                is Map<*, *> -> value.values.filterIsInstance<Preferences.Key<*>>()
                is Iterable<*> -> if (field.name.startsWith("BACKUP_") || field.name == "backupKeys" || field.name == "nonBackupKeys") {
                    emptyList()
                } else {
                    value.filterIsInstance<Preferences.Key<*>>()
                }
                else -> emptyList()
            }
        }
        .map { it.name }
        .toSet()

    @Test
    fun `every preference in the store is either backed up or deliberately left out`() {
        val registered = (SettingsStore.backupKeys + SettingsStore.nonBackupKeys).map { it.name }.toSet()
        val missing = declaredKeyNames() - registered
        assertTrue(
            "These settings are in neither SettingsStore.backupKeys nor nonBackupKeys, so a " +
                "backup would quietly lose them: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the registry names nothing the store does not have`() {
        val registered = (SettingsStore.backupKeys + SettingsStore.nonBackupKeys).map { it.name }.toSet()
        assertEquals(
            "The backup registry names keys the store no longer declares",
            emptySet<String>(),
            registered - declaredKeyNames(),
        )
    }

    @Test
    fun `nothing is both backed up and excluded`() {
        val backedUp = SettingsStore.backupKeys.map { it.name }.toSet()
        val excluded = SettingsStore.nonBackupKeys.map { it.name }.toSet()
        assertEquals(emptySet<String>(), backedUp intersect excluded)
    }

    @Test
    fun `a preference is listed under exactly one type`() {
        val names = SettingsStore.backupKeys.map { it.name }
        val repeated = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(
            "A key listed under two types would be written twice, at whichever type ran last: $repeated",
            repeated.isEmpty(),
        )
    }

    @Test
    fun `the mirror this device found is never carried to another one`() {
        val backedUp = SettingsStore.backupKeys.map { it.name }
        assertTrue("last_pipe_origin" !in backedUp)
        assertTrue("migrated_from_shared_preferences" !in backedUp)
    }
}
