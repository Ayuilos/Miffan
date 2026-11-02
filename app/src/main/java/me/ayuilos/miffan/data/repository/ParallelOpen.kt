package me.ayuilos.miffan.data.repository

import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asExecutor

/**
 * Opens [first] and [second] at the same time (each costs round trips on a slow link) and returns
 * both, or closes whichever opened when the other failed.
 */
internal fun <A : Closeable, B : Closeable> openInParallel(first: () -> A, second: () -> B): Pair<A, B> {
    val other = CompletableFuture.supplyAsync(second, Dispatchers.IO.asExecutor())
    val a = try {
        first()
    } catch (error: Throwable) {
        other.whenComplete { b, _ -> runCatching { b?.close() } }
        throw error
    }
    val b = try {
        other.get()
    } catch (error: Throwable) {
        runCatching { a.close() }
        other.whenComplete { b, _ -> runCatching { b?.close() } }
        if (error is InterruptedException) Thread.currentThread().interrupt()
        throw (error as? ExecutionException)?.cause ?: error
    }
    return a to b
}

/**
 * Starts opening a resource now, on IO and as a child of [parent] (so cancelling the caller
 * cancels it), for a caller that will probably [take] it later. [close] without a [take] closes it
 * whenever it finishes opening, or cancels the attempt.
 */
internal class EarlyOpen<T : Closeable>(parent: Job?, open: suspend () -> T) : Closeable {
    private val claimed = AtomicBoolean(false)
    private val job = SupervisorJob(parent)
    private val opening: Deferred<T> = CoroutineScope(Dispatchers.IO + job).async { open() }

    init {
        // The parent must not wait for this job forever: it ends with the attempt.
        opening.invokeOnCompletion { job.complete() }
    }

    /** The opened resource, now owned by the caller; rethrows the opening's failure. */
    suspend fun take(): T {
        val value = opening.await()
        check(claimed.compareAndSet(false, true)) { "Already taken or closed" }
        return value
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun close() {
        if (!claimed.compareAndSet(false, true)) return
        opening.invokeOnCompletion { cause -> if (cause == null) runCatching { opening.getCompleted().close() } }
        opening.cancel()
    }
}
