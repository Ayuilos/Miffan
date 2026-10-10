package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.flow.StateFlow
import me.rerere.workspace.screen.RemoteScreenFrameSink
import me.rerere.workspace.screen.RemoteScreenOptions
import me.rerere.workspace.screen.RfbJpegDecoder

// TEMPORARY (claude/p6c-ui): the P6E_SPEC contract, stubbed so the UI compiles before Codex's
// codex/p6e-data lands. Delete this file when merging it; the real members replace these.

enum class RemoteScreenQuality { SAVER, BALANCED, BEST }

data class RemoteStreamRequest(
    val quality: RemoteScreenQuality = RemoteScreenQuality.BALANCED,
    val skip: Boolean = false,
)

suspend fun RemoteScreenRepository.open(
    workspaceId: String, sink: RemoteScreenFrameSink, jpeg: RfbJpegDecoder?, options: RemoteScreenOptions,
    @Suppress("UNUSED_PARAMETER") stream: RemoteStreamRequest,
): RemoteScreenConnection = open(workspaceId, sink, jpeg, options)

val RemoteScreenConnection.streamRequested: Boolean get() = false
val RemoteScreenConnection.streamAddress: String? get() = null

interface RemoteAudioControl {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}

val RemoteDesktopSession.audio: RemoteAudioControl? get() = null
