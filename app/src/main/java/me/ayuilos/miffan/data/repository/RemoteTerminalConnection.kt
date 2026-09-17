package me.ayuilos.miffan.data.repository

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.workspace.RemoteTerminalSession

/** One manually opened terminal, permanently bound to the SSH identity used at connection time. */
class RemoteTerminalConnection internal constructor(
    private val terminal: RemoteTerminalSession,
    private val onClose: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    val input: InputStream get() = terminal.input
    val output: OutputStream get() = terminal.output
    val isConnected: Boolean get() = !closed.get() && terminal.isConnected
    val exitStatus: Int get() = terminal.exitStatus

    fun resize(columns: Int, rows: Int) {
        check(!closed.get()) { "Remote terminal is closed" }
        terminal.resize(columns, rows)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                terminal.close()
            } finally {
                onClose()
            }
        }
    }
}
