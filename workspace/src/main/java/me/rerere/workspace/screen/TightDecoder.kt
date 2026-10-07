package me.rerere.workspace.screen

import java.io.DataInputStream
import java.io.IOException
import java.util.zip.Inflater

/** Platform JPEG decoding (BitmapFactory on Android, ImageIO in JVM tests). */
fun interface RfbJpegDecoder {
    /** Decodes [bytes] into [target] rows starting at ([x], [y]) with the framebuffer's stride. */
    fun decode(bytes: ByteArray, length: Int, target: Framebuffer, x: Int, y: Int, w: Int, h: Int)
}

/**
 * Tight encoding for the 32bpp/depth-24 format set by [RfbClient]: TPIXEL is 3 bytes R, G, B.
 * Four zlib streams persist across rectangles and may be reset by the server.
 */
internal class TightDecoder(private val jpeg: RfbJpegDecoder?) {
    private val streams = Array(4) { Inflater() }
    private var compressed = ByteArray(1 shl 16)
    private var data = ByteArray(1 shl 18)
    private val palette = IntArray(256)

    var decodeNanos = 0L
        private set

    val supportsJpeg: Boolean get() = jpeg != null

    fun decode(input: DataInputStream, fb: Framebuffer, x: Int, y: Int, w: Int, h: Int) {
        val control = input.readUnsignedByte()
        for (i in 0 until 4) if (control and (1 shl i) != 0) streams[i].reset()
        when (val kind = control shr 4) {
            FILL -> {
                val c = tpixel(input)
                val started = System.nanoTime()
                for (dy in 0 until h) java.util.Arrays.fill(fb.pixels, (y + dy) * fb.width + x, (y + dy) * fb.width + x + w, c)
                decodeNanos += System.nanoTime() - started
            }
            JPEG -> {
                val length = compactLength(input)
                ensureCompressed(length)
                input.readFully(compressed, 0, length)
                val started = System.nanoTime()
                requireNotNull(jpeg) { "Server sent JPEG without client support" }
                    .decode(compressed, length, fb, x, y, w, h)
                decodeNanos += System.nanoTime() - started
            }
            in 0..7 -> basic(input, kind, fb, x, y, w, h)
            else -> throw IOException("Invalid Tight control byte $control")
        }
    }

    private fun basic(input: DataInputStream, kind: Int, fb: Framebuffer, x: Int, y: Int, w: Int, h: Int) {
        val stream = kind and 0x3
        val filter = if (kind and 0x4 != 0) input.readUnsignedByte() else FILTER_COPY
        var paletteSize = 0
        val rowBytes: Int
        when (filter) {
            FILTER_COPY, FILTER_GRADIENT -> rowBytes = w * 3
            FILTER_PALETTE -> {
                paletteSize = input.readUnsignedByte() + 1
                for (i in 0 until paletteSize) palette[i] = tpixel(input)
                rowBytes = if (paletteSize == 2) (w + 7) / 8 else w
            }
            else -> throw IOException("Invalid Tight filter $filter")
        }
        val size = rowBytes * h
        if (data.size < size) data = ByteArray(size)
        if (size < MIN_TO_COMPRESS) {
            input.readFully(data, 0, size)
        } else {
            val length = compactLength(input)
            ensureCompressed(length)
            input.readFully(compressed, 0, length)
            val inflater = streams[stream]
            inflater.setInput(compressed, 0, length)
            var read = 0
            while (read < size) {
                val n = inflater.inflate(data, read, size - read)
                if (n == 0 && (inflater.needsInput() || inflater.finished())) throw IOException("Truncated Tight data")
                read += n
            }
        }
        val started = System.nanoTime()
        val pixels = fb.pixels
        val stride = fb.width
        when (filter) {
            FILTER_COPY -> {
                var p = 0
                for (dy in 0 until h) {
                    var o = (y + dy) * stride + x
                    repeat(w) {
                        pixels[o++] = rgb(data[p], data[p + 1], data[p + 2])
                        p += 3
                    }
                }
            }
            FILTER_PALETTE -> for (dy in 0 until h) {
                var o = (y + dy) * stride + x
                val row = dy * rowBytes
                if (paletteSize == 2) {
                    for (dx in 0 until w) {
                        val bit = (data[row + dx / 8].toInt() shr (7 - dx % 8)) and 1
                        pixels[o++] = palette[bit]
                    }
                } else {
                    for (dx in 0 until w) pixels[o++] = palette[data[row + dx].toInt() and 0xFF]
                }
            }
            FILTER_GRADIENT -> {
                val prev = IntArray(w * 3)
                val cur = IntArray(w * 3)
                var p = 0
                for (dy in 0 until h) {
                    var o = (y + dy) * stride + x
                    for (dx in 0 until w) {
                        for (c in 0 until 3) {
                            val left = if (dx > 0) cur[(dx - 1) * 3 + c] else 0
                            val up = prev[dx * 3 + c]
                            val upLeft = if (dx > 0) prev[(dx - 1) * 3 + c] else 0
                            val predicted = (left + up - upLeft).coerceIn(0, 255)
                            cur[dx * 3 + c] = (predicted + (data[p++].toInt() and 0xFF)) and 0xFF
                        }
                        pixels[o++] = 0xFF shl 24 or (cur[dx * 3] shl 16) or (cur[dx * 3 + 1] shl 8) or cur[dx * 3 + 2]
                    }
                    cur.copyInto(prev)
                }
            }
        }
        decodeNanos += System.nanoTime() - started
    }

    private fun ensureCompressed(length: Int) {
        if (compressed.size < length) compressed = ByteArray(length)
    }

    private fun tpixel(input: DataInputStream): Int {
        val r = input.readUnsignedByte()
        val g = input.readUnsignedByte()
        val b = input.readUnsignedByte()
        return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }

    private fun rgb(r: Byte, g: Byte, b: Byte): Int =
        0xFF shl 24 or (r.toInt() and 0xFF shl 16) or (g.toInt() and 0xFF shl 8) or (b.toInt() and 0xFF)

    private fun compactLength(input: DataInputStream): Int {
        var b = input.readUnsignedByte()
        var length = b and 0x7F
        if (b and 0x80 != 0) {
            b = input.readUnsignedByte()
            length = length or ((b and 0x7F) shl 7)
            if (b and 0x80 != 0) length = length or (input.readUnsignedByte() shl 14)
        }
        return length
    }

    private companion object {
        const val FILL = 0x8
        const val JPEG = 0x9
        const val FILTER_COPY = 0
        const val FILTER_PALETTE = 1
        const val FILTER_GRADIENT = 2
        const val MIN_TO_COMPRESS = 12
    }
}
