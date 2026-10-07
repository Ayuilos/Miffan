package me.rerere.workspace.screen

/**
 * Maps full-resolution framebuffer regions to the output resolution shown on the phone. With
 * [scale] 2 each output pixel averages a 2×2 block, which halves Retina frames to point size.
 */
internal object ScreenScaler {
    /** Very large framebuffers (Retina Macs) are kept at half resolution on the phone. */
    fun scaleFor(width: Int, height: Int): Int = if (width.toLong() * height > 5_000_000L) 2 else 1

    fun outputSize(width: Int, height: Int, scale: Int): Pair<Int, Int> =
        ((width + scale - 1) / scale) to ((height + scale - 1) / scale)

    /** The smallest output rectangle covering [rect]. */
    fun outputRect(rect: RfbRect, scale: Int, width: Int, height: Int): RfbRect {
        val (outW, outH) = outputSize(width, height, scale)
        val left = rect.x / scale
        val top = rect.y / scale
        val right = minOf(outW, (rect.x + rect.width + scale - 1) / scale)
        val bottom = minOf(outH, (rect.y + rect.height + scale - 1) / scale)
        return RfbRect(left, top, maxOf(0, right - left), maxOf(0, bottom - top))
    }

    /** Writes the output pixels of [out] (output coordinates) into [dst] row-major. */
    fun copy(fb: Framebuffer, scale: Int, out: RfbRect, dst: IntArray) {
        val src = fb.pixels
        val stride = fb.width
        if (scale == 1) {
            for (row in 0 until out.height) {
                System.arraycopy(src, (out.y + row) * stride + out.x, dst, row * out.width, out.width)
            }
            return
        }
        val maxX = fb.width - 1
        val maxY = fb.height - 1
        var i = 0
        for (row in 0 until out.height) {
            val y0 = minOf((out.y + row) * 2, maxY)
            val y1 = minOf(y0 + 1, maxY)
            for (col in 0 until out.width) {
                val x0 = minOf((out.x + col) * 2, maxX)
                val x1 = minOf(x0 + 1, maxX)
                val a = src[y0 * stride + x0]
                val b = src[y0 * stride + x1]
                val c = src[y1 * stride + x0]
                val d = src[y1 * stride + x1]
                val r = ((a shr 16 and 0xFF) + (b shr 16 and 0xFF) + (c shr 16 and 0xFF) + (d shr 16 and 0xFF) + 2) shr 2
                val g = ((a shr 8 and 0xFF) + (b shr 8 and 0xFF) + (c shr 8 and 0xFF) + (d shr 8 and 0xFF) + 2) shr 2
                val bl = ((a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF) + 2) shr 2
                dst[i++] = 0xFF shl 24 or (r shl 16) or (g shl 8) or bl
            }
        }
    }

    /** Few large uploads beat many tiny ones; fall back to the bounding box past [limit] rects. */
    fun merge(rects: List<RfbRect>, limit: Int = 16): List<RfbRect> {
        val nonEmpty = rects.filter { it.width > 0 && it.height > 0 }
        if (nonEmpty.size <= limit) return nonEmpty
        val left = nonEmpty.minOf { it.x }
        val top = nonEmpty.minOf { it.y }
        val right = nonEmpty.maxOf { it.x + it.width }
        val bottom = nonEmpty.maxOf { it.y + it.height }
        return listOf(RfbRect(left, top, right - left, bottom - top))
    }
}
