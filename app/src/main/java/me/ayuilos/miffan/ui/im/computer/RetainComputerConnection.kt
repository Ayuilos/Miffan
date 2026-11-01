package me.ayuilos.miffan.ui.im.computer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import org.koin.compose.koinInject

/**
 * Connects to the computer behind [workspaceId] in the background while the page is visible, so
 * the partner's first action does not wait for a whole connection (over a relay that is many
 * seconds). An unreachable computer is ignored; leaving the page releases the connection to the
 * pool, which closes it after its usual idle time.
 */
@Composable
internal fun RetainComputerConnection(workspaceId: String?) {
    val repository: WorkspaceRepository = koinInject()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(workspaceId, lifecycleOwner) {
        val id = workspaceId ?: return@LaunchedEffect
        // A partner's own computer (a Space) is reached through its own lease; only real desktops here.
        if (runCatching { repository.getById(id) }.getOrNull()?.isSpace != false) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val lease = runCatching { repository.retainRemoteWorkspace(id) }.getOrNull()
            try { awaitCancellation() } finally { lease?.close() }
        }
    }
}
