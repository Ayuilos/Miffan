package me.ayuilos.miffan.data.repository.relay

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Which route won a [raceRoutes]. */
enum class RouteWinner { DIRECT, RELAY }

/**
 * Starts the direct route at once and the relay after [relayHeadStartMillis], or as soon as the
 * direct route has failed. The first success wins; a later success from the other route is closed
 * with [close]. At home the direct route wins before the relay starts, and away from home the
 * relay does not wait for every direct address to time out.
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
    return coroutineScope {
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
            if (!result.complete(value to winner)) close(value)
        }

        val directJob = launch {
            try {
                finish(direct(), RouteWinner.DIRECT)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                directFailed.complete(Unit)
                failed(0, error)
            }
        }
        val relayJob = launch {
            withTimeoutOrNull(relayHeadStartMillis) { directFailed.await() }
            if (result.isCompleted) return@launch
            try {
                finish(relay(), RouteWinner.RELAY)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failed(1, error)
            }
        }
        try {
            result.await()
        } finally {
            directJob.cancel()
            relayJob.cancel()
        }
    }
}
