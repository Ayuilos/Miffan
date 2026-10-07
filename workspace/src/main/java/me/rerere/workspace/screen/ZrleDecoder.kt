package me.rerere.workspace.screen

import java.io.DataInputStream
import java.io.IOException
import java.util.zip.Inflater

/**
 * ZRLE (RFC 6143 §7.7.6) for the client pixel [format]. A compressed pixel (CPIXEL) is 3 bytes
 * (blue, green, red) for 24-bit colour and the full pixel otherwise. One zlib stream spans the
 * connection.
 */
internal class ZrleDecoder(private val format: RfbPixelFormat) {
    private val inflater = Inflater()
    private var compressed = ByteArray(1 shl 16)
    private var data = ByteArray(1 shl 18)
    private var pos = 0
    private val palette = IntArray(128)

    /** Time spent inflating and decoding, excluding network reads. */
    var decodeNanos = 0L
        private set

    fun decode(input: DataInputStream, fb: Framebuffer, x: Int, y: Int, w: Int, h: Int) {
        val length = input.readInt()
        if (compressed.size < length) compressed = ByteArray(length)
        input.readFully(compressed, 0, length)
        val started = System.nanoTime()
        inflater.setInput(compressed, 0, length)
        var size = 0
        while (true) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            val n = inflater.inflate(data, size, data.size - size)
            size += n
            if (n == 0 && (inflater.needsInput() || inflater.finished())) break
        }
        pos = 0
        var ty = y
        while (ty < y + h) {
            val th = minOf(TILE, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tw = minOf(TILE, x + w - tx)
                decodeTile(fb, tx, ty, tw, th)
                tx += TILE
            }
            ty += TILE
        }
        if (pos != size) throw IOException("ZRLE rectangle has ${size - pos} trailing bytes")
        decodeNanos += System.nanoTime() - started
    }

    private fun decodeTile(fb: Framebuffer, x: Int, y: Int, w: Int, h: Int) {
        val sub = u8()
        val pixels = fb.pixels
        val stride = fb.width
        when {
            sub == 0 -> for (dy in 0 until h) {
                var o = (y + dy) * stride + x
                repeat(w) { pixels[o++] = cpixel() }
            }
            sub == 1 -> {
                val c = cpixel()
                for (dy in 0 until h) java.util.Arrays.fill(pixels, (y + dy) * stride + x, (y + dy) * stride + x + w, c)
            }
            sub in 2..16 -> {
                for (i in 0 until sub) palette[i] = cpixel()
                val bits = when {
                    sub == 2 -> 1
                    sub <= 4 -> 2
                    else -> 4
                }
                val mask = (1 shl bits) - 1
                for (dy in 0 until h) {
                    var o = (y + dy) * stride + x
                    var byte = 0
                    var left = 0
                    repeat(w) {
                        if (left == 0) {
                            byte = u8()
                            left = 8
                        }
                        left -= bits
                        pixels[o++] = palette[(byte shr left) and mask]
                    }
                }
            }
            sub == 128 -> {
                var i = 0
                val total = w * h
                while (i < total) {
                    val c = cpixel()
                    val run = runLength()
                    repeat(run) {
                        pixels[(y + i / w) * stride + x + i % w] = c
                        i++
                    }
                }
            }
            sub >= 130 -> {
                val size = sub - 128
                for (i in 0 until size) palette[i] = cpixel()
                var i = 0
                val total = w * h
                while (i < total) {
                    val index = u8()
                    val c = palette[index and 0x7F]
                    val run = if (index and 0x80 != 0) runLength() else 1
                    repeat(run) {
                        pixels[(y + i / w) * stride + x + i % w] = c
                        i++
                    }
                }
            }
            else -> throw IOException("Invalid ZRLE subencoding $sub")
        }
    }

    private fun runLength(): Int {
        var length = 1
        while (true) {
            val b = u8()
            length += b
            if (b != 255) return length
        }
    }

    private fun u8(): Int = data[pos++].toInt() and 0xFF

    private fun cpixel(): Int {
        val c = format.readZrleCompact(data, pos)
        pos += format.compactBytes
        return c
    }

    private companion object {
        const val TILE = 64
    }
}
