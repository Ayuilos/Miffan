package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import me.rerere.workspace.screen.RemoteScreenState

/** A saved pin always wins over fresh metadata obtained through the verified SSH lease. */
internal fun expectedRdpCertificate(savedPin: String?, helperPin: String?): String? = savedPin ?: helperPin

/** Certificate receipt alone is insufficient: authentication and connection must also succeed. */
internal suspend fun persistVerifiedRdpCertificate(
    state: StateFlow<RemoteScreenState>, certificate: StateFlow<String?>, expectedPin: String,
    persist: suspend (String) -> Unit,
) {
    val terminal = state.first { it is RemoteScreenState.Connected || it is RemoteScreenState.Closed }
    if (terminal is RemoteScreenState.Connected && certificate.value == expectedPin) persist(expectedPin)
}
