package me.rerere.workspace

import com.jcraft.jsch.SftpProgressMonitor
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A file operation stopped making progress; writes may already have reached the server. */
class RemoteFileTimeoutException(cause: Throwable? = null) :
    IOException("Remote file operation timed out; check remote state before retrying", cause)

/** Metadata has a deadline; active uploads/downloads extend it whenever bytes move. */
internal class SftpIdleGuard(
    private val timeoutMillis: Long,
    private val disconnect: () -> Unit,
) : SftpProgressMonitor {
    private val lock = Any()
    private var lastProgress = System.nanoTime()
    private var finished = false
    private var timedOut = false

    fun touch() = synchronized(lock) { lastProgress = System.nanoTime() }

    override fun init(op: Int, src: String?, dest: String?, max: Long) = touch()
    override fun count(count: Long): Boolean = synchronized(lock) {
        if (count > 0) lastProgress = System.nanoTime()
        !timedOut
    }
    override fun end() = touch()

    fun <T> run(block: (SftpIdleGuard) -> T): T {
        val interval = timeoutMillis.coerceAtMost(250)
        val alarm = scheduler.scheduleWithFixedDelay({
            val expired = synchronized(lock) {
                if (!finished && !timedOut &&
                    System.nanoTime() - lastProgress >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                ) {
                    timedOut = true
                    true
                } else false
            }
            if (expired) runCatching(disconnect)
        }, interval, interval, TimeUnit.MILLISECONDS)
        try {
            val result = block(this)
            synchronized(lock) {
                finished = true
                if (timedOut) throw RemoteFileTimeoutException()
            }
            return result
        } catch (error: Exception) {
            synchronized(lock) {
                finished = true
                if (timedOut && error !is RemoteFileTimeoutException) {
                    throw RemoteFileTimeoutException(error)
                }
            }
            throw error
        } finally {
            synchronized(lock) { finished = true }
            alarm.cancel(false)
        }
    }

    private companion object {
        val scheduler = ScheduledThreadPoolExecutor(2) { task ->
            Thread(task, "miffan-sftp-timeout").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
