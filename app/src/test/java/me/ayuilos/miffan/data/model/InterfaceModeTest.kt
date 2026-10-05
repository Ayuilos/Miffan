package me.ayuilos.miffan.data.model

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.datastore.InterfaceModeMigration
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceModeTest {
    @Test
    fun freshInstallationsStartInImWhileUpgradesKeepProfessionalAndChoose() {
        assertEquals(InterfaceModeState(mode = InterfaceMode.IM), initialInterfaceMode(0, hasSavedProviders = false))
        assertEquals(
            InterfaceModeState(mode = InterfaceMode.PROFESSIONAL, choicePending = true),
            initialInterfaceMode(3, hasSavedProviders = false),
        )
        // Older releases may have saved providers before the launch counter existed.
        assertTrue(initialInterfaceMode(0, hasSavedProviders = true).choicePending)
    }

    @Test
    fun missingPreferenceMigrationRecordsTheShellOnceWithoutTouchingOtherPreferences() = runBlocking {
        val migration = InterfaceModeMigration()
        val upgraded = migration.migrate(preferencesOf(SettingsStore.LAUNCH_COUNT to 5, SettingsStore.THEME_ID to "kept"))
        assertEquals(
            InterfaceModeState(mode = InterfaceMode.PROFESSIONAL, choicePending = true),
            JsonInstant.decodeFromString<InterfaceModeState>(upgraded[SettingsStore.INTERFACE_MODE]!!),
        )
        assertEquals("kept", upgraded[SettingsStore.THEME_ID])
        assertFalse(migration.shouldMigrate(upgraded))
        assertEquals(upgraded, migration.migrate(upgraded))

        val fresh = migration.migrate(emptyPreferences())
        assertEquals(
            InterfaceModeState(mode = InterfaceMode.IM),
            JsonInstant.decodeFromString<InterfaceModeState>(fresh[SettingsStore.INTERFACE_MODE]!!),
        )
    }

    @Test
    fun choosingAModeClearsThePendingChoiceAndOlderBackupsAsk() {
        val restored = JsonInstant.decodeFromString<Settings>("{}")
        assertEquals(InterfaceModeState(mode = InterfaceMode.PROFESSIONAL, choicePending = true), restored.interfaceMode)
        assertFalse(restored.isImMode)

        val im = restored.withInterfaceMode(InterfaceMode.IM)
        assertTrue(im.isImMode)
        assertFalse(im.interfaceMode.choicePending)
        assertEquals(restored.copy(interfaceMode = im.interfaceMode), im)

        val professional = im.withInterfaceMode(InterfaceMode.PROFESSIONAL)
        assertEquals(InterfaceModeState(InterfaceMode.PROFESSIONAL, choicePending = false), professional.interfaceMode)
    }
}
