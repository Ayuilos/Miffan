package me.ayuilos.miffan.data.datastore

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.InterfaceModeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class LoadedSettingsTest {
    @Test
    fun startupConsumerWaitsForPersistedAssistantsAndMigratedMode() = runTest {
        val flow = MutableStateFlow(Settings.dummy())
        val loaded = async { flow.awaitLoadedSettings() }
        runCurrent()
        assertFalse(loaded.isCompleted)

        val persisted = Settings(
            interfaceMode = InterfaceModeState(InterfaceMode.PROFESSIONAL, choicePending = true),
            assistants = DEFAULT_ASSISTANTS.map { it.copy(name = "Restored assistant") },
        )
        flow.value = persisted
        assertSame(persisted, loaded.await())
    }

    @Test
    fun alreadyLoadedSettingsAreReturnedWithoutWaitingForAnotherEmission() = runTest {
        val persisted = Settings(interfaceMode = InterfaceModeState(InterfaceMode.IM))
        assertSame(persisted, MutableStateFlow(persisted).awaitLoadedSettings())
    }
}
