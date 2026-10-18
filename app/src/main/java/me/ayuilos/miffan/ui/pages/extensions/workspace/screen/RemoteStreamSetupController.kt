package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.ayuilos.miffan.data.repository.RemoteStreamDirectPairing
import me.ayuilos.miffan.data.repository.RemoteStreamPairing
import me.ayuilos.miffan.data.repository.RemoteSunshinePermission

/**
 * High-performance mode setup shared by the professional settings, the easy-mode wizard and the
 * screen page's suggestion: the Sunshine check, pairing, the encryption change and the switch.
 * Each view model owns one, so a rotation never loses a pairing the user is typing in.
 */
class RemoteStreamSetupController(
    private val screens: RemoteScreenRepository,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<String, RemoteStreamSetupState>>(emptyMap())
    val states: StateFlow<Map<String, RemoteStreamSetupState>> = _states.asStateFlow()
    private var pairing: RemoteStreamPairing? = null
    private var pairingCancelled = false

    private fun update(hostId: String, change: (RemoteStreamSetupState) -> RemoteStreamSetupState) =
        _states.update { it + (hostId to change(it[hostId] ?: RemoteStreamSetupState())) }

    private fun <T> launch(operation: suspend () -> T, onResult: (Result<T>) -> Unit) {
        scope.launch {
            val result = try {
                Result.success(operation())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            onResult(result)
        }
    }

    /** Reads Sunshine's state on the computer (installed, running, encryption, pairing) and the switch. */
    fun check(hostId: String, revision: String) {
        val previous = _states.value[hostId] ?: RemoteStreamSetupState()
        if (previous.busy) return
        // A result from an earlier host identity must not describe the edited host.
        val retained = if (previous.connectionRevision == revision) previous else RemoteStreamSetupState()
        _states.update { it + (hostId to retained.copy(connectionRevision = revision, phase = RemoteStreamSetupPhase.CHECKING, error = null)) }
        launch({ screens.streamStatus(hostId, revision) to screens.getConfig(hostId)?.streamEnabled }) { result ->
            update(hostId) { it.copy(phase = RemoteStreamSetupPhase.IDLE, status = result.getOrNull()?.first,
                enabled = result.getOrNull()?.second ?: it.enabled, error = result.exceptionOrNull()) }
        }
    }

    /**
     * Pairs this phone: the computer is told to trust it directly, and only when that cannot be done
     * here does a PIN appear in [states] for the user to type into Sunshine. When someone is streaming,
     * [states] asks first; calling again with [interruptActiveStream] accepts cutting them off.
     */
    fun pair(hostId: String, revision: String, interruptActiveStream: Boolean = false) {
        val previous = _states.value[hostId] ?: return
        if (previous.busy || previous.connectionRevision != revision) return
        update(hostId) { it.copy(phase = RemoteStreamSetupPhase.PAIRING, pin = null, manualPinReason = null,
            confirmInterrupt = false, pairingError = null, paired = false) }
        pairingCancelled = false
        scope.launch {
            val result = try {
                when (val direct = screens.pairStreamDirectly(hostId, revision, interruptActiveStream = interruptActiveStream)) {
                    is RemoteStreamDirectPairing.Paired -> Result.success(direct.certificateSha256)
                    RemoteStreamDirectPairing.ActiveStream -> {
                        update(hostId) { it.copy(phase = RemoteStreamSetupPhase.IDLE, confirmInterrupt = true) }
                        return@launch
                    }
                    is RemoteStreamDirectPairing.ManualPinNeeded -> {
                        update(hostId) { it.copy(manualPinReason = direct.reason) }
                        pairWithPin(hostId, revision)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            // Closing the PIN dialog is not a failure worth reporting.
            update(hostId) { it.copy(phase = RemoteStreamSetupPhase.IDLE, pin = null, manualPinReason = null,
                pairingError = result.exceptionOrNull()?.takeUnless { pairingCancelled }, paired = result.isSuccess) }
            if (result.isSuccess) check(hostId, revision)
        }
    }

    /** The user would rather not cut off the stream on the computer. */
    fun dismissInterrupt(hostId: String) = update(hostId) { it.copy(confirmInterrupt = false) }

    private suspend fun pairWithPin(hostId: String, revision: String): Result<String> {
        return try {
            screens.startStreamPairing(hostId, revision).use { started ->
                pairing = started
                update(hostId) { it.copy(pin = started.pin) }
                Result.success(started.await())
            }
        } finally {
            pairing = null
        }
    }

    /** The user closed the PIN dialog: stop waiting for Sunshine. */
    fun cancelPairing() {
        pairingCancelled = true
        pairing?.close()
    }

    /** Called only from the confirmation that shows exactly what changes on the computer. */
    fun enforceEncryption(hostId: String, revision: String) {
        val previous = _states.value[hostId] ?: return
        if (previous.busy || previous.connectionRevision != revision) return
        update(hostId) { it.copy(phase = RemoteStreamSetupPhase.ENFORCING, enforcement = null, enforcementError = null) }
        launch({ screens.enforceStreamEncryption(hostId, revision) }) { result ->
            update(hostId) { it.copy(phase = RemoteStreamSetupPhase.IDLE,
                enforcement = result.getOrNull(), enforcementError = result.exceptionOrNull()) }
            check(hostId, revision)
        }
    }

    /** Starts Sunshine on the computer (the user asked for it), then reads its state again. */
    fun start(hostId: String, revision: String) {
        val previous = _states.value[hostId] ?: return
        if (previous.busy || previous.connectionRevision != revision) return
        update(hostId) { it.copy(phase = RemoteStreamSetupPhase.STARTING, startFailed = false) }
        launch({ screens.startSunshine(hostId, revision) }) { result ->
            update(hostId) { it.copy(phase = RemoteStreamSetupPhase.IDLE, startFailed = result.getOrNull()?.success != true) }
            check(hostId, revision)
        }
    }

    /** Opens the macOS privacy page for [permission] on the computer's screen; it changes nothing itself. */
    fun openPermissionSettings(hostId: String, revision: String, permission: RemoteSunshinePermission) {
        val previous = _states.value[hostId] ?: return
        if (previous.busy || previous.connectionRevision != revision) return
        launch({ screens.openSunshinePermissionSettings(hostId, revision, permission) }) { result ->
            val opened = result.getOrNull()?.success == true
            update(hostId) { it.copy(settingsOpened = permission.takeIf { opened }, settingsFailed = !opened) }
        }
    }

    fun setEnabled(hostId: String, enabled: Boolean, onResult: (Result<Boolean>) -> Unit = {}) {
        launch({ screens.setStreamEnabled(hostId, enabled) }) { result ->
            if (result.getOrNull() == true) update(hostId) { it.copy(enabled = enabled) }
            onResult(result)
        }
    }

    fun forget(hostId: String) = _states.update { it - hostId }
}
