package me.rerere.workspace.screen

/**
 * The client pixel formats Miffan requests, both little-endian true colour. [RGB565] trades
 * colour depth for bandwidth: lossless encodings send 2 bytes per pixel instead of 3.
 */
enum class RfbPixelFormat(
    val bitsPerPixel: Int,
    val depth: Int,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int,
) {
    RGB888(32, 24, 255, 255, 255, 16, 8, 0),
    RGB565(16, 16, 31, 63, 31, 11, 5, 0);

    val bytesPerPixel: Int get() = bitsPerPixel / 8

    /** ZRLE CPIXEL / Tight TPIXEL size: 3 bytes when 24-bit colour sits in a 32-bit pixel. */
    val compactBytes: Int get() = if (this == RGB888) 3 else bytesPerPixel

    /** Reads a full little-endian pixel value. */
    fun readPixel(data: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until bytesPerPixel) value = value or ((data[offset + i].toInt() and 0xFF) shl (8 * i))
        return value
    }

    /** ZRLE CPIXEL: the low-order bytes of the little-endian pixel. */
    fun readZrleCompact(data: ByteArray, offset: Int): Int = when (this) {
        RGB888 -> 0xFF shl 24 or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset].toInt() and 0xFF)
        else -> toArgb(readPixel(data, offset))
    }

    /** Tight TPIXEL: red, green, blue bytes for 24-bit colour, otherwise the native pixel. */
    fun readTightCompact(data: ByteArray, offset: Int): Int = when (this) {
        RGB888 -> 0xFF shl 24 or
            ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)
        else -> toArgb(readPixel(data, offset))
    }

    fun red(pixel: Int) = pixel shr redShift and redMax
    fun green(pixel: Int) = pixel shr greenShift and greenMax
    fun blue(pixel: Int) = pixel shr blueShift and blueMax

    fun toArgb(pixel: Int): Int = argb(red(pixel), green(pixel), blue(pixel))

    /** Builds an opaque ARGB colour from components in this format's ranges. */
    fun argb(r: Int, g: Int, b: Int): Int =
        0xFF shl 24 or (scale(r, redMax) shl 16) or (scale(g, greenMax) shl 8) or scale(b, blueMax)

    private fun scale(value: Int, max: Int): Int = if (max == 255) value else (value * 255 + max / 2) / max
}
