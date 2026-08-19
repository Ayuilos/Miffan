package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs the post-failure executor health probe without replacing the primary operation error. */
internal suspend fun probeExecutorRootfsAfterFailure(
    originalError: Throwable,
    probe: () -> Boolean,
): Boolean = try {
    withContext(Dispatchers.IO) { probe() }
} catch (probeError: Throwable) {
    if (probeError !== originalError) {
        originalError.addSuppressed(probeError)
    }
    false
}
