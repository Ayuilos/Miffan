package me.rerere.workspace.screen

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Credentials for the remote VNC server. Apple Remote Desktop auth uses the macOS account. */
data class RfbCredentials(val username: String? = null, val password: String? = null) {
    override fun toString(): String = "RfbCredentials(username=$username, password=[redacted])"
}

data class RfbServerInfo(
    val protocolVersion: String,
    val securityTypes: List<Int>,
    val securityType: Int,
    val width: Int,
    val height: Int,
    val name: String,
)

sealed interface RfbEvent {
    data class FramebufferUpdated(val rectangles: Int, val encodings: Set<Int>) : RfbEvent
    data class Resized(val width: Int, val height: Int) : RfbEvent
    data object Bell : RfbEvent
    data class CutText(val text: String) : RfbEvent
}

class RfbAuthenticationException(message: String) : IOException(message)

/** Opaque ARGB framebuffer owned by one [RfbClient]. */
class Framebuffer(width: Int, height: Int) {
    var width: Int = width
        private set
    var height: Int = height
        private set
    var pixels: IntArray = IntArray(width * height)
        private set

    internal fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
        pixels = IntArray(width * height)
    }
}

/**
 * Minimal RFB 3.3/3.8 client. Not thread-safe for reads; input events may be sent from another
 * thread because writes are serialized on [output].
 */
class RfbClient(
    input: InputStream,
    output: OutputStream,
    private val credentials: RfbCredentials?,
    jpeg: RfbJpegDecoder? = null,
) {
    private val counter = CountingInputStream(BufferedInputStream(input, 1 shl 16))
    private val input = DataInputStream(counter)
    private val output = DataOutputStream(BufferedOutputStream(output))
    private val zrle = ZrleDecoder()
    private val tight = TightDecoder(jpeg)
    lateinit var framebuffer: Framebuffer
        private set

    /** Bytes received from the server so far. */
    val bytesRead: Long get() = counter.count

    /** Client-side inflate + decode time so far, excluding network reads. */
    val decodeNanos: Long get() = zrle.decodeNanos + tight.decodeNanos

    fun handshake(preferredEncodings: IntArray = defaultEncodings(jpegQuality = if (tight.supportsJpeg) 6 else null)): RfbServerInfo {
        val versionBytes = ByteArray(12).also(input::readFully)
        val serverVersion = String(versionBytes, StandardCharsets.US_ASCII)
        require(serverVersion.startsWith("RFB ")) { "Not an RFB server" }
        val minor = serverVersion.substring(8, 11).toInt()
        val major = serverVersion.substring(4, 7).toInt()
        val use38 = major > 3 || minor >= 7
        output.write((if (use38) "RFB 003.008\n" else "RFB 003.003\n").toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        val offered: List<Int>
        val chosen: Int
        if (use38) {
            val count = input.readUnsignedByte()
            if (count == 0) throw RfbAuthenticationException(readReason())
            offered = List(count) { input.readUnsignedByte() }
            chosen = chooseSecurity(offered)
            output.writeByte(chosen)
            output.flush()
        } else {
            chosen = input.readInt()
            if (chosen == 0) throw RfbAuthenticationException(readReason())
            offered = listOf(chosen)
        }
        when (chosen) {
            SECURITY_NONE -> Unit
            SECURITY_VNC -> RfbAuth.vnc(input, output, requireNotNull(credentials?.password) { "VNC password required" })
            SECURITY_ARD -> RfbAuth.ard(
                input, output,
                requireNotNull(credentials?.username) { "Username required" },
                requireNotNull(credentials?.password) { "Password required" },
            )
            else -> throw RfbAuthenticationException("Unsupported security type $chosen")
        }
        if (use38 || chosen != SECURITY_NONE) {
            if (input.readInt() != 0) {
                throw RfbAuthenticationException(if (use38) readReason() else "Authentication failed")
            }
        }

        output.writeByte(1) // shared session: keep other viewers connected
        output.flush()
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        input.skipBytes(16) // server pixel format; we always override it below
        val name = String(ByteArray(input.readInt()).also(input::readFully), StandardCharsets.UTF_8)
        framebuffer = Framebuffer(width, height)

        setPixelFormat()
        setEncodings(preferredEncodings)
        return RfbServerInfo(serverVersion.trim(), offered, chosen, width, height, name)
    }

    fun requestUpdate(incremental: Boolean) = synchronized(output) {
        output.writeByte(3)
        output.writeByte(if (incremental) 1 else 0)
        output.writeShort(0)
        output.writeShort(0)
        output.writeShort(framebuffer.width)
        output.writeShort(framebuffer.height)
        output.flush()
    }

    fun pointer(x: Int, y: Int, buttonMask: Int) = synchronized(output) {
        output.writeByte(5)
        output.writeByte(buttonMask)
        output.writeShort(x.coerceIn(0, framebuffer.width - 1))
        output.writeShort(y.coerceIn(0, framebuffer.height - 1))
        output.flush()
    }

    fun key(keysym: Int, down: Boolean) = synchronized(output) {
        output.writeByte(4)
        output.writeByte(if (down) 1 else 0)
        output.writeShort(0)
        output.writeInt(keysym)
        output.flush()
    }

    /** Blocks until one server message has been applied to [framebuffer]. */
    fun readMessage(): RfbEvent {
        return when (val type = input.readUnsignedByte()) {
            0 -> readFramebufferUpdate()
            1 -> {
                input.skipBytes(1)
                input.readUnsignedShort()
                input.skipBytes(input.readUnsignedShort() * 6)
                readMessage()
            }
            2 -> RfbEvent.Bell
            3 -> {
                input.skipBytes(3)
                val bytes = ByteArray(input.readInt()).also(input::readFully)
                RfbEvent.CutText(String(bytes, StandardCharsets.ISO_8859_1))
            }
            else -> throw IOException("Unknown server message $type")
        }
    }

    private fun readFramebufferUpdate(): RfbEvent {
        input.skipBytes(1)
        val count = input.readUnsignedShort()
        val encodings = mutableSetOf<Int>()
        var resized: RfbEvent.Resized? = null
        repeat(count) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val encoding = input.readInt()
            encodings += encoding
            when (encoding) {
                ENCODING_RAW -> readRaw(x, y, w, h)
                ENCODING_COPY_RECT -> copyRect(input.readUnsignedShort(), input.readUnsignedShort(), x, y, w, h)
                ENCODING_ZRLE -> zrle.decode(input, framebuffer, x, y, w, h)
                ENCODING_TIGHT -> tight.decode(input, framebuffer, x, y, w, h)
                ENCODING_DESKTOP_SIZE -> {
                    framebuffer.resize(w, h)
                    resized = RfbEvent.Resized(w, h)
                }
                else -> throw IOException("Server sent unrequested encoding $encoding")
            }
        }
        return resized ?: RfbEvent.FramebufferUpdated(count, encodings)
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        val row = ByteArray(w * 4)
        val fb = framebuffer
        for (dy in 0 until h) {
            input.readFully(row)
            var offset = (y + dy) * fb.width + x
            var i = 0
            while (i < row.size) {
                fb.pixels[offset++] = 0xFF shl 24 or
                    (row[i + 2].toInt() and 0xFF shl 16) or
                    (row[i + 1].toInt() and 0xFF shl 8) or
                    (row[i].toInt() and 0xFF)
                i += 4
            }
        }
    }

    private fun copyRect(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        val fb = framebuffer
        val copy = IntArray(w * h)
        for (dy in 0 until h) System.arraycopy(fb.pixels, (sy + dy) * fb.width + sx, copy, dy * w, w)
        for (dy in 0 until h) System.arraycopy(copy, dy * w, fb.pixels, (y + dy) * fb.width + x, w)
    }

    private fun chooseSecurity(offered: List<Int>): Int {
        val hasUser = credentials?.username != null
        val hasPassword = credentials?.password != null
        return when {
            SECURITY_NONE in offered -> SECURITY_NONE
            hasUser && hasPassword && SECURITY_ARD in offered -> SECURITY_ARD
            hasPassword && SECURITY_VNC in offered -> SECURITY_VNC
            else -> throw RfbAuthenticationException("No supported security type in $offered")
        }
    }

    private fun setPixelFormat() = synchronized(output) {
        output.writeByte(0)
        output.write(ByteArray(3))
        output.writeByte(32) // bits per pixel
        output.writeByte(24) // depth
        output.writeByte(0) // little endian
        output.writeByte(1) // true colour
        output.writeShort(255)
        output.writeShort(255)
        output.writeShort(255)
        output.writeByte(16)
        output.writeByte(8)
        output.writeByte(0)
        output.write(ByteArray(3))
        output.flush()
    }

    private fun setEncodings(encodings: IntArray) = synchronized(output) {
        output.writeByte(2)
        output.writeByte(0)
        output.writeShort(encodings.size)
        encodings.forEach(output::writeInt)
        output.flush()
    }

    private fun readReason(): String =
        String(ByteArray(input.readInt()).also(input::readFully), StandardCharsets.UTF_8)

    companion object {
        const val SECURITY_NONE = 1
        const val SECURITY_VNC = 2
        const val SECURITY_ARD = 30

        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_TIGHT = 7
        const val ENCODING_ZRLE = 16
        const val ENCODING_DESKTOP_SIZE = -223
        private const val ENCODING_COMPRESS_LEVEL_0 = -256
        private const val ENCODING_QUALITY_LEVEL_0 = -32

        /** Tight first when JPEG is available; [jpegQuality] 0..9 also enables lossy Tight. */
        fun defaultEncodings(jpegQuality: Int?, compressLevel: Int = 6): IntArray = buildList {
            if (jpegQuality != null) add(ENCODING_TIGHT)
            add(ENCODING_ZRLE)
            if (jpegQuality == null) add(ENCODING_TIGHT)
            add(ENCODING_COPY_RECT)
            add(ENCODING_RAW)
            add(ENCODING_DESKTOP_SIZE)
            add(ENCODING_COMPRESS_LEVEL_0 + compressLevel)
            if (jpegQuality != null) add(ENCODING_QUALITY_LEVEL_0 + jpegQuality)
        }.toIntArray()
    }
}

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }
}
