// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals

class AppUpgradeTest {
    @Test
    fun speakKeysPlayVersionsAreBridgedToTheMigrationVersion() {
        listOf(100, 101, 102).forEach { version ->
            assertEquals(
                SPEAKKEYS_SETTINGS_MIGRATION_BASELINE,
                normalizeStoredSettingsMigrationVersion(version),
            )
        }
    }

    @Test
    fun unrelatedMigrationVersionsAreUnchanged() {
        assertEquals(0, normalizeStoredSettingsMigrationVersion(0))
        assertEquals(99, normalizeStoredSettingsMigrationVersion(99))
        assertEquals(103, normalizeStoredSettingsMigrationVersion(103))
        assertEquals(3201, normalizeStoredSettingsMigrationVersion(3201))
        assertEquals(3202, normalizeStoredSettingsMigrationVersion(3202))
    }
}
