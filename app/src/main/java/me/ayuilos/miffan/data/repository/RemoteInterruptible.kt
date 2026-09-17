package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** JSch can swallow InterruptedException and report a channel-open error instead. */
internal suspend fun <T> runRemoteInterruptible(block: () -> T): T = try {
    runInterruptible(Dispatchers.IO, block)
} catch (error: Exception) {
    // Cancellation must not be recorded or displayed as a failed SSH connection.
    // Real failures on an active request keep their original exception.
    currentCoroutineContext().ensureActive()
    throw error
}
