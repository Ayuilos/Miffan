package me.ayuilos.miffan.data.repository.relay

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Which route won a [raceRoutes]. */
enum class RouteWinner { DIRECT, RELAY }

/**
 * Starts the direct route at once and the relay after [relayHeadStartMillis], or as soon as the
 * direct route has failed. The first success wins and is returned at once: the loser is cancelled
 * but not waited for, since a blocking socket connect may ignore cancellation for many seconds. A
 * loser that still succeeds later is closed with [close].
 *
 * When both fail, [preferError] picks the error to report. Cancelling the caller cancels both.
 */
suspend fun <T : Any> raceRoutes(
    direct: suspend () -> T,
    relay: (suspend () -> T)?,
    relayHeadStartMillis: Long,
    close: suspend (T) -> Unit,
    preferError: (direct: Throwable, relay: Throwable) -> Throwable,
): Pair<T, RouteWinner> {
    if (relay == null) return direct() to RouteWinner.DIRECT
    // Not a child of the caller: the caller must not wait for a loser to finish.
    val race = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
    val result = CompletableDeferred<Pair<T, RouteWinner>>()
    val directFailed = CompletableDeferred<Unit>()
    val errors = AtomicReferenceArray<Throwable?>(2)
    val pending = AtomicInteger(2)

    fun failed(index: Int, error: Throwable) {
        errors.set(index, error)
        if (pending.decrementAndGet() == 0) {
            result.completeExceptionally(preferError(errors.get(0)!!, errors.get(1)!!))
        }
    }

    suspend fun finish(value: T, winner: RouteWinner) {
        // Nothing may escape a detached route: an uncaught exception there would crash the app.
        if (!result.complete(value to winner)) withContext(NonCancellable) { runCatching { close(value) } }
    }

    /** Runs one route; any failure counts, including a timeout's CancellationException, unless the race itself was cancelled. */
    suspend fun run(index: Int, winner: RouteWinner, route: suspend () -> T) {
        val value = try {
            route()
        } catch (error: Throwable) {
            if (error is CancellationException && !currentCoroutineContext().isActive) {
                // Cancelled: the race is decided or the caller left. Still settle the count, so a
                // race whose other route has also failed reports instead of waiting forever.
                failed(index, error)
                throw error
            }
            if (index == 0) directFailed.complete(Unit)
            failed(index, error)
            return
        }
        finish(value, winner)
    }

    race.launch { run(0, RouteWinner.DIRECT, direct) }
    race.launch {
        withTimeoutOrNull(relayHeadStartMillis) { directFailed.await() }
        if (result.isCompleted) {
            failed(1, CancellationException("Race already decided"))
            return@launch
        }
        run(1, RouteWinner.RELAY, relay)
    }
    try {
        return result.await()
    } finally {
        race.cancel()
    }
}
