package me.ayuilos.miffan.data.repository

import java.io.BufferedReader
import java.util.Base64
import me.rerere.workspace.RemoteChannelStream

/**
 * One long-lived `miffan.ps1 clip serve` per Windows screen connection. Typing on the screen page
 * writes the clipboard for every committed piece of text; through this channel a write takes tens
 * of milliseconds instead of a PowerShell process (about a second) each. Writes are already
 * serialized by the stream session; [prepare] opens the channel early so the first one is fast too.
 *
 * Any failure closes the channel for good and [write] returns false, so the caller falls back to
 * the one-shot clipboard command. The helper verifies every write by reading it back.
 */
internal class WindowsClipboardChannel(private val open: () -> RemoteChannelStream) {
    private var process: RemoteChannelStream? = null
    private var reader: BufferedReader? = null
    private var broken = false

    @Synchronized
    fun prepare() {
        if (broken || process != null) return
        try { start() } catch (_: Exception) { fail() }
    }

    /** True when the text was written and read back on the computer. */
    @Synchronized
    fun write(text: String): Boolean {
        if (broken) return false
        return try {
            if (process == null) start()
            val stream = requireNotNull(process)
            stream.output.write((Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "\n").toByteArray(Charsets.US_ASCII))
            stream.output.flush()
            // The first reply waits for the bridge and the MCP handshake; later ones take milliseconds.
            val reply = readLine(stream, REPLY_TIMEOUT_MILLIS)
            (reply != null && reply.contains("\"success\":true")).also { if (!it) fail() }
        } catch (_: Exception) {
            fail()
            false
        }
    }

    @Synchronized
    fun close() {
        runCatching { process?.close() }
        process = null
        reader = null
    }

    private fun start() {
        val stream = open()
        process = stream
        reader = stream.input.bufferedReader(Charsets.UTF_8)
    }

    private fun fail() {
        broken = true
        close()
    }

    /** JSch pipes have no read timeout; poll until a whole reply can be read or the deadline passes. */
    private fun readLine(stream: RemoteChannelStream, timeoutMillis: Long): String? {
        val lines = requireNotNull(reader)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!lines.ready() && stream.input.available() == 0) {
            if (!stream.isConnected || System.nanoTime() > deadline) return null
            Thread.sleep(5)
        }
        return lines.readLine()
    }

    private companion object {
        const val REPLY_TIMEOUT_MILLIS = 20_000L
    }
}
