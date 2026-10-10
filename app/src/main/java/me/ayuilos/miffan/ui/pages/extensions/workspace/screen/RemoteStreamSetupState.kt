package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import me.ayuilos.miffan.data.repository.RemoteCommandOutcome
import me.ayuilos.miffan.data.repository.RemoteStreamStatus
import me.ayuilos.miffan.data.repository.RemoteSunshinePermission

/**
 * High-performance mode setup for one host: the Sunshine check, pairing, and the encryption
 * change. Held by the view model so a rotation never loses a pairing the user is typing in.
 */
data class RemoteStreamSetupState(
    val connectionRevision: String? = null,
    val phase: RemoteStreamSetupPhase = RemoteStreamSetupPhase.IDLE,
    val status: RemoteStreamStatus? = null,
    /** The saved high-performance switch for this host, read with [status]. */
    val enabled: Boolean? = null,
    val error: Throwable? = null,
    /** The PIN to type into Sunshine while [phase] is PAIRING. */
    val pin: String? = null,
    val pairingError: Throwable? = null,
    /** Set once after a pairing that just succeeded, for a confirmation line. */
    val paired: Boolean = false,
    val enforcement: RemoteCommandOutcome? = null,
    val enforcementError: Throwable? = null,
    /** Starting Sunshine failed; null after a success or before trying. */
    val startFailed: Boolean = false,
    /** The macOS settings page last opened on the computer, for a "go to the Mac" line. */
    val settingsOpened: RemoteSunshinePermission? = null,
    val settingsFailed: Boolean = false,
) {
    val busy: Boolean get() = phase != RemoteStreamSetupPhase.IDLE

    /** Sunshine on this computer is fully usable: running, encrypted, this phone paired. */
    val streamReady: Boolean get() = status?.let { it.installed && it.running && it.encryptionEnforced && it.paired } == true
}

enum class RemoteStreamSetupPhase { IDLE, CHECKING, PAIRING, ENFORCING, STARTING }
