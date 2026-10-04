package me.ayuilos.miffan.ui.pages.extensions.workspace

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Capture remote output only. Keystrokes (including passwords) never enter this buffer. */
internal class TerminalOutputCapture(private val limit: Int = 64 * 1024) {
    private val output = ByteArrayOutputStream()
    var truncated = false
        private set

    fun append(bytes: ByteArray) {
        val count = minOf(bytes.size, limit - output.size())
        output.write(bytes, 0, count)
        if (count < bytes.size) truncated = true
    }

    fun text(): String = output.toString(Charsets.UTF_8.name())
        .replace(Regex("\\x1B\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)"), "")
        .replace(Regex("\\x1B\\[[0-?]*[ -/]*[@-~]"), "")
        .replace("\r\n", "\n")
        .filter { it == '\n' || it == '\t' || it.code >= 32 && it.code != 127 }
}

internal fun terminalCommandResult(
    status: String, exitCode: Int?, output: String, truncated: Boolean, error: String?,
    sessionId: String? = null, sessionReused: Boolean = false, sessionOpen: Boolean = false,
) =
    buildJsonObject {
        sessionId?.let {
            put("sessionId", it)
            put("sessionReused", sessionReused)
            put("sessionOpen", sessionOpen)
        }
        put("status", status)
        exitCode?.let { put("exitCode", it) }
        put("output", output)
        put("outputType", "merged_pty_output")
        put("truncated", truncated)
        error?.let { put("error", it) }
    }.toString()
