package me.ayuilos.miffan.data.thread

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ChatGroup
import me.ayuilos.miffan.data.model.ChatOrganization
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatOrganizationMigrationTest {
    private val a = Assistant()
    private val b = Assistant()
    private val removed = Uuid.random()

    @Test
    fun firstMigrationMergesOnlyValidExistingPinsAndCleansDeletedMembers() {
        val original = Settings(
            assistants = listOf(a, b),
            chatOrganization = ChatOrganization(
                pinned = listOf(b.id, removed),
                groups = listOf(ChatGroup(name = "Group", chatIds = listOf(a.id, removed))),
            ),
        )
        val migrated = original.withMigratedChatOrganization(setOf(a.id.toString(), b.id.toString(), removed.toString(), "bad"))
        assertEquals(listOf(b.id, a.id), migrated.chatOrganization.pinned)
        assertEquals(listOf(a.id), migrated.chatOrganization.groups.single().chatIds)
        assertTrue(migrated.chatOrganization.legacyPinsMigrated)
        assertFalse(original.chatOrganization.legacyPinsMigrated)
        assertSame(migrated, migrated.withMigratedChatOrganization(setOf(a.id.toString())))
    }

    @Test
    fun alreadyMigratedSettingsDoNotReimportLocalPinsButStillRetainChats() {
        val original = Settings(
            assistants = listOf(a),
            chatOrganization = ChatOrganization(pinned = listOf(removed), legacyPinsMigrated = true),
        )
        val migrated = original.withMigratedChatOrganization(setOf(a.id.toString()))
        assertTrue(migrated.chatOrganization.pinned.isEmpty())
        assertTrue(migrated.chatOrganization.legacyPinsMigrated)
        assertSame(migrated, migrated.withMigratedChatOrganization(setOf(a.id.toString())))
    }

    @Test
    fun deletedPartnersLeaveEmptyGroupsAndNoPins() {
        val group = ChatGroup(name = "Kept", chatIds = listOf(removed))
        val migrated = Settings(assistants = emptyList(), chatOrganization = ChatOrganization(groups = listOf(group)))
            .withMigratedChatOrganization(setOf(removed.toString()))
        assertTrue(migrated.chatOrganization.pinned.isEmpty())
        assertEquals(listOf(group.copy(chatIds = emptyList())), migrated.chatOrganization.groups)
        assertTrue(migrated.chatOrganization.legacyPinsMigrated)
    }

    @Test
    fun placeholderIsNeverTransformed() {
        val dummy = Settings.dummy()
        assertSame(dummy, dummy.withMigratedChatOrganization(setOf(a.id.toString())))
    }

    private class Fixture(settings: Settings, pins: Set<String>) {
        val flow = MutableStateFlow(settings)
        val store = mockk<SettingsStore>()
        val list = mockk<ThreadListState>(relaxed = true)
        val migration: ChatOrganizationMigration

        init {
            every { store.settingsFlow } returns flow
            every { list.legacyPins() } returns pins
            coEvery { store.updateIfCurrent(any(), any()) } coAnswers {
                val expected = firstArg<Settings>()
                val transform = secondArg<(Settings) -> Settings>()
                if (flow.value != expected) false
                else {
                    flow.value = transform(flow.value)
                    true
                }
            }
            migration = ChatOrganizationMigration(store, list)
        }
    }

    @Test
    fun waitsForRealSettingsAndClearsOnlyAfterCommit() = runTest {
        val fixture = Fixture(Settings.dummy(), setOf(a.id.toString()))
        every { fixture.list.clearLegacyPins() } answers {
            assertFalse(fixture.flow.value.init)
            assertTrue(fixture.flow.value.chatOrganization.legacyPinsMigrated)
            assertEquals(listOf(a.id), fixture.flow.value.chatOrganization.pinned)
        }
        val job = async { fixture.migration.migrate() }
        runCurrent()
        assertFalse(job.isCompleted)
        coVerify(exactly = 0) { fixture.store.updateIfCurrent(any(), any()) }
        verify(exactly = 0) { fixture.list.clearLegacyPins() }
        fixture.flow.value = Settings(assistants = listOf(a))
        job.await()
        verify(exactly = 1) { fixture.list.clearLegacyPins() }
    }

    @Test
    fun concurrentAndRepeatedCallsPersistOnce() = runTest {
        val fixture = Fixture(Settings(assistants = listOf(a)), setOf(a.id.toString()))
        List(4) { async { fixture.migration.migrate() } }.awaitAll()
        fixture.migration.migrate()
        assertEquals(listOf(a.id), fixture.flow.value.chatOrganization.pinned)
        coVerify(exactly = 1) { fixture.store.updateIfCurrent(any(), any()) }
    }

    @Test
    fun alreadyMigratedStateClearsLeftoverKeysWithoutWritingSettings() = runTest {
        val settings = Settings(assistants = listOf(a), chatOrganization = ChatOrganization(legacyPinsMigrated = true))
        val fixture = Fixture(settings, setOf(a.id.toString()))
        fixture.migration.migrate()
        assertSame(settings, fixture.flow.value)
        coVerify(exactly = 0) { fixture.store.updateIfCurrent(any(), any()) }
        verify(exactly = 1) { fixture.list.clearLegacyPins() }
    }

    @Test
    fun failedPersistenceLeavesLegacyKeysForRetry() = runTest {
        val fixture = Fixture(Settings(assistants = listOf(a)), setOf(a.id.toString()))
        coEvery { fixture.store.updateIfCurrent(any(), any()) } throws IllegalStateException("write failed")
        try {
            fixture.migration.migrate()
            fail("Expected persistence failure")
        } catch (_: IllegalStateException) {
            assertFalse(fixture.flow.value.chatOrganization.legacyPinsMigrated)
        }
        verify(exactly = 0) { fixture.list.clearLegacyPins() }
    }

    @Test
    fun concurrentSettingsEditIsPreservedAndDeletedPartnerIsNotRepinned() = runTest {
        val fixture = Fixture(Settings(assistants = listOf(a, b)), setOf(a.id.toString(), b.id.toString()))
        var attempts = 0
        coEvery { fixture.store.updateIfCurrent(any(), any()) } coAnswers {
            attempts++
            if (attempts == 1) {
                fixture.flow.value = fixture.flow.value.copy(assistants = listOf(b), miffanHelpEnabled = false)
                false
            } else {
                assertEquals(fixture.flow.value, firstArg<Settings>())
                fixture.flow.value = secondArg<(Settings) -> Settings>()(fixture.flow.value)
                true
            }
        }
        fixture.migration.migrate()
        assertEquals(2, attempts)
        assertFalse(fixture.flow.value.miffanHelpEnabled)
        assertEquals(listOf(b.id), fixture.flow.value.chatOrganization.pinned)
    }

    @Test
    fun competingMigrationIsObservedRatherThanOverwritten() = runTest {
        val fixture = Fixture(Settings(assistants = listOf(a, b)), setOf(a.id.toString()))
        coEvery { fixture.store.updateIfCurrent(any(), any()) } coAnswers {
            fixture.flow.value = fixture.flow.value.copy(
                chatOrganization = ChatOrganization(pinned = listOf(b.id), legacyPinsMigrated = true),
            )
            false
        }
        fixture.migration.migrate()
        assertEquals(listOf(b.id), fixture.flow.value.chatOrganization.pinned)
        coVerify(exactly = 1) { fixture.store.updateIfCurrent(any(), any()) }
        verify(exactly = 1) { fixture.list.clearLegacyPins() }
    }
}
