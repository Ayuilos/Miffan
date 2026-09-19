package me.ayuilos.miffan.data.repository

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import me.rerere.workspace.RemoteWorkspaceSession

/** JSch can swallow InterruptedException and report a channel-open error instead. */
internal suspend fun <T> runRemoteInterruptible(block: () -> T): T = try {
    runInterruptible(Dispatchers.IO, block)
} catch (error: Exception) {
    // Cancellation must not be recorded or displayed as a failed SSH connection.
    // Real failures on an active request keep their original exception.
    currentCoroutineContext().ensureActive()
    throw error
}

@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> runCancellableRemoteOperation(
    operation: RemoteWorkspaceSession.OperationHandle,
    block: () -> T,
): T {
    val cancelled = AtomicBoolean(false)
    val cancelOperation = {
        if (cancelled.compareAndSet(false, true)) operation.cancel()
    }
    // runInterruptible waits for a blocking JSch call to return. JSch may swallow
    // the thread interrupt, so close this operation's channel at cancellation time.
    val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion(
        onCancelling = true,
        invokeImmediately = true,
    ) { cause -> if (cause != null) cancelOperation() }
    try {
        return runRemoteInterruptible(block)
    } catch (error: CancellationException) {
        cancelOperation()
        throw error
    } finally {
        cancellationHook?.dispose()
    }
}
