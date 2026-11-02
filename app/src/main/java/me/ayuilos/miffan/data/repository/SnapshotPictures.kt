package me.ayuilos.miffan.data.repository

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** A capture's checksum as the helper names it: cksum's `crc-size`, or an MD5 on Windows. */
private val DIGEST = Regex("[0-9a-f-]{1,80}")

/** A JPEG is ~150 KB; the PNG fallback of a large screen can be several MB. */
private const val MAX_PICTURE = 24 * 1024 * 1024
private const val MAX_LINE = 4096

/**
 * The helper's `snapshot serve`, open for the whole screen: it first names where cua-driver writes each
 * capture, then answers each request line with one header line and the picture's raw bytes, so a
 * picture is neither base64 nor a new command on the computer.
 */
internal class SnapshotPictures(input: InputStream, private val output: OutputStream) {
    sealed interface Reply {
        /** The capture is the picture the request named. */
        data class Same(val digest: String) : Reply
        class Picture(val format: String, val digest: String, val bytes: ByteArray) : Reply
    }

    private val input = DataInputStream(BufferedInputStream(input, 64 * 1024))

    /** Everything read so far, headers included. */
    @Volatile var bytesRead = 0L
        private set

    /** Waits for `ready PATH` and returns PATH, where cua-driver should write each capture. */
    fun ready(): String {
        val line = line()
        if (line.startsWith("error ")) error("snapshot serve failed: ${line.substringAfter(' ')}")
        require(line.startsWith("ready ") && line.length > "ready ".length) { "Unexpected snapshot greeting" }
        return line.substring("ready ".length)
    }

    /** The picture at JPEG [quality] (20–90), or [Reply.Same] when it is still the one [since] names. */
    fun request(quality: Int, since: String?): Reply {
        require(since == null || since.matches(DIGEST)) { "Unexpected snapshot digest" }
        output.write(("$quality" + (since?.let { " $it" } ?: "") + "\n").toByteArray(Charsets.US_ASCII))
        output.flush()
        val words = line().split(' ')
        return when (words[0]) {
            "same" -> Reply.Same(digest(words.getOrNull(1)))
            "jpeg", "png" -> {
                check(words.size == 3) { "Unexpected snapshot header" }
                val length = words[2].toIntOrNull()
                check(length != null && length in 1..MAX_PICTURE) { "Unexpected snapshot length" }
                val bytes = ByteArray(length)
                input.readFully(bytes)
                bytesRead += length
                Reply.Picture(words[0], digest(words[1]), bytes)
            }
            "error" -> error("snapshot failed: ${words.getOrNull(1).orEmpty().take(40)}")
            else -> error("Unexpected snapshot reply")
        }
    }

    private fun digest(value: String?): String {
        check(value != null && value.matches(DIGEST)) { "Unexpected snapshot digest" }
        return value
    }

    private fun line(): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("snapshot serve ended")
            bytesRead++
            if (b == '\n'.code) break
            check(line.size() < MAX_LINE) { "snapshot header too long" }
            line.write(b)
        }
        return line.toString(Charsets.UTF_8.name()).removeSuffix("\r")
    }
}
