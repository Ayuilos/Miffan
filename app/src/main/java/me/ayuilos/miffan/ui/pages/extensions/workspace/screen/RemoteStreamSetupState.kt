package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import me.ayuilos.miffan.data.repository.RemoteCommandOutcome
import me.ayuilos.miffan.data.repository.RemoteStreamStatus

/**
 * High-performance mode setup for one host: the Sunshine check, pairing, and the encryption
 * change. Held by the view model so a rotation never loses a pairing the user is typing in.
 */
data class RemoteStreamSetupState(
    val connectionRevision: String? = null,
    val phase: RemoteStreamSetupPhase = RemoteStreamSetupPhase.IDLE,
    val status: RemoteStreamStatus? = null,
    val error: Throwable? = null,
    /** The PIN to type into Sunshine while [phase] is PAIRING. */
    val pin: String? = null,
    val pairingError: Throwable? = null,
    /** Set once after a pairing that just succeeded, for a confirmation line. */
    val paired: Boolean = false,
    val enforcement: RemoteCommandOutcome? = null,
    val enforcementError: Throwable? = null,
) {
    val busy: Boolean get() = phase != RemoteStreamSetupPhase.IDLE
}

enum class RemoteStreamSetupPhase { IDLE, CHECKING, PAIRING, ENFORCING }
