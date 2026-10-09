package me.rerere.workspace

/** Frames are emitted by the shell, not matched against its prompt or echoed command text. */
internal class PersistentShellProtocol(private val token: String) {
    private val begin = "\u001eMIFFAN_${token}_BEGIN\u001f"
    private val ready = "\u001eMIFFAN_${token}_READY\u001f"
    private val end = "\u001eMIFFAN_${token}_END:"
    private val pending = StringBuilder()
    private var started = false
    private var finished = false

    data class Chunk(
        val output: ByteArray,
        val exitCode: Int? = null,
        val ready: Boolean = false,
        val started: Boolean = false,
    )

    @Synchronized
    fun feed(bytes: ByteArray): Chunk {
        if (finished) return Chunk(byteArrayOf())
        pending.append(bytes.toString(Charsets.ISO_8859_1))
        var becameReady = false
        val readyIndex = if (!started) pending.indexOf(ready) else -1
        if (readyIndex >= 0) {
            pending.delete(0, readyIndex + ready.length)
            becameReady = true
        }
        if (!started) {
            val index = pending.indexOf(begin)
            if (index < 0) {
                if (pending.length >= begin.length) pending.delete(0, pending.length - begin.length + 1)
                return Chunk(byteArrayOf(), ready = becameReady)
            }
            pending.delete(0, index + begin.length)
            started = true
        }
        val index = pending.indexOf(end)
        if (index >= 0) {
            val terminator = pending.indexOf("\u001f", index + end.length)
            if (terminator >= 0) {
                val code = pending.substring(index + end.length, terminator).toIntOrNull()
                if (code != null && code in 0..255) {
                    finished = true
                    val output = pending.substring(0, index).toByteArray(Charsets.ISO_8859_1)
                    pending.clear()
                    return Chunk(output, code, becameReady, started = true)
                }
            }
            if (pending.length - index <= end.length + 8) {
                val output = pending.substring(0, index).toByteArray(Charsets.ISO_8859_1)
                pending.delete(0, index)
                return Chunk(output, ready = becameReady, started = true)
            }
        }
        // Emit prompts immediately; retain only bytes that could actually be a split marker.
        var keep = minOf(pending.length, end.length - 1)
        while (keep > 0 && !pending.endsWith(end.take(keep))) keep--
        val count = pending.length - keep
        val output = pending.substring(0, count).toByteArray(Charsets.ISO_8859_1)
        pending.delete(0, count)
        return Chunk(output, ready = becameReady, started = true)
    }

    @Synchronized
    fun remainder(): ByteArray = if (started && !finished) {
        pending.toString().toByteArray(Charsets.ISO_8859_1).also { pending.clear() }
    } else byteArrayOf()

    private val variable = "__mf_${token.take(12)}"

    /** Acknowledged small uploads prevent PTY input queues from silently dropping long scripts. */
    fun uploads(command: String, cwd: String?): List<String> = buildList {
        fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
        val acknowledgement = "; command printf '\\036MIFFAN_${token}_READY\\037'\n"
        var batch = "${variable}_cmd=''; ${variable}_cwd=''"
        fun appendAssignment(assignment: String) {
            val next = "$batch; $assignment"
            if ((next + acknowledgement).toByteArray().size <= 768) {
                batch = next
            } else {
                add(batch + acknowledgement)
                batch = assignment
            }
        }
        fun assign(suffix: String, value: String) {
            var start = 0
            while (start < value.length) {
                var end = minOf(start + 128, value.length)
                if (end < value.length && value[end - 1].isHighSurrogate()) end--
                val chunk = value.substring(start, end)
                start = end
                appendAssignment("${variable}_$suffix=\"\$${variable}_$suffix\"${quote(chunk)}")
            }
        }
        assign("cmd", command)
        assign("cwd", cwd.orEmpty())
        add(batch + acknowledgement)
    }

    fun invocation(): String = buildString {
        // Parse invocation and trailer together before sudo/read can consume user input.
        append("command printf '\\036MIFFAN_${token}_BEGIN\\037'; ")
        append("if [ -z \"\$${variable}_cwd\" ] || cd \"\$${variable}_cwd\"; then ")
        append("eval \"\$${variable}_cmd\"; ${variable}_status=\$?; else ${variable}_status=\$?; fi; ")
        append("command printf '\\036MIFFAN_${token}_END:%s\\037' \"\$${variable}_status\"; ")
        append("unset ${variable}_cmd ${variable}_cwd ${variable}_status\n")
    }
}
