package me.rerere.workspace.screen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RfbClientTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** Server bytes for RFB 3.8 with security None and a [width]×[height] framebuffer. */
    private fun serverInit(width: Int, height: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write("RFB 003.008\n".toByteArray())
            writeByte(1); writeByte(RfbClient.SECURITY_NONE)
            writeInt(0)
            writeShort(width); writeShort(height)
            write(ByteArray(16))
            writeInt(4); write("test".toByteArray())
            body()
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.update(vararg rects: DataOutputStream.() -> Unit) {
        writeByte(0); writeByte(0); writeShort(rects.size)
        rects.forEach { it() }
    }

    private fun DataOutputStream.rectHeader(x: Int, y: Int, w: Int, h: Int, encoding: Int) {
        writeShort(x); writeShort(y); writeShort(w); writeShort(h); writeInt(encoding)
    }

    private fun DataOutputStream.raw32(color: Int) {
        // Little-endian BGRX as negotiated by RfbClient.setPixelFormat.
        writeByte(color and 0xFF); writeByte(color shr 8 and 0xFF); writeByte(color shr 16 and 0xFF); writeByte(0)
    }

    private fun connect(
        server: ByteArray,
        jpeg: RfbJpegDecoder? = null,
        format: RfbPixelFormat = RfbPixelFormat.RGB888,
    ): Pair<RfbClient, ByteArrayOutputStream> {
        val sent = ByteArrayOutputStream()
        val client = RfbClient(ByteArrayInputStream(server), sent, null, jpeg, format)
        client.handshake()
        return client to sent
    }

    @Test
    fun rawUpdateReportsTheChangedRect() {
        val (client, _) = connect(serverInit(4, 2) {
            update({
                rectHeader(1, 0, 2, 1, RfbClient.ENCODING_RAW)
                raw32(red); raw32(green)
            })
        })
        val event = client.readMessage() as RfbEvent.FramebufferUpdated
        assertEquals(listOf(RfbRect(1, 0, 2, 1)), event.rects)
        assertEquals(false, event.resized)
        assertEquals(red, client.framebuffer.pixels[1])
        assertEquals(green, client.framebuffer.pixels[2])
    }

    @Test
    fun copyRectMovesExistingPixels() {
        val (client, _) = connect(serverInit(4, 1) {
            update({ rectHeader(0, 0, 2, 1, RfbClient.ENCODING_RAW); raw32(red); raw32(blue) })
            update({ rectHeader(2, 0, 2, 1, RfbClient.ENCODING_COPY_RECT); writeShort(0); writeShort(0) })
        })
        client.readMessage()
        client.readMessage()
        assertArrayEquals(intArrayOf(red, blue, red, blue), client.framebuffer.pixels)
    }

    @Test
    fun desktopSizeReallocatesTheFramebuffer() {
        val (client, _) = connect(serverInit(4, 1) {
            update({ rectHeader(0, 0, 8, 3, RfbClient.ENCODING_DESKTOP_SIZE) })
        })
        val event = client.readMessage() as RfbEvent.FramebufferUpdated
        assertTrue(event.resized)
        assertTrue(event.rects.isEmpty())
        assertEquals(8, client.framebuffer.width)
        assertEquals(3, client.framebuffer.height)
    }

    @Test
    fun zrleSharesOneZlibStreamAcrossRectangles() {
        val deflater = Deflater()
        fun zlib(data: ByteArray): ByteArray {
            deflater.setInput(data)
            val out = ByteArray(1024)
            val n = deflater.deflate(out, 0, out.size, Deflater.SYNC_FLUSH)
            return out.copyOf(n)
        }
        fun cpixel(c: Int) = byteArrayOf((c and 0xFF).toByte(), (c shr 8 and 0xFF).toByte(), (c shr 16 and 0xFF).toByte())
        // Solid tile.
        val solid = zlib(byteArrayOf(1) + cpixel(green))
        // Two-colour packed palette, 4×1: indices 0,1,1,0 → bits 0110 0000.
        val packed = zlib(byteArrayOf(2) + cpixel(red) + cpixel(blue) + byteArrayOf(0b0110_0000))
        // Plain RLE: white ×3 then red ×1 (run length encoded as length-1).
        val rle = zlib(byteArrayOf(128.toByte()) + cpixel(white) + byteArrayOf(2) + cpixel(red) + byteArrayOf(0))
        val (client, _) = connect(serverInit(4, 3) {
            update(
                { rectHeader(0, 0, 4, 1, RfbClient.ENCODING_ZRLE); writeInt(solid.size); write(solid) },
                { rectHeader(0, 1, 4, 1, RfbClient.ENCODING_ZRLE); writeInt(packed.size); write(packed) },
                { rectHeader(0, 2, 4, 1, RfbClient.ENCODING_ZRLE); writeInt(rle.size); write(rle) },
            )
        })
        client.readMessage()
        assertArrayEquals(
            intArrayOf(green, green, green, green, red, blue, blue, red, white, white, white, red),
            client.framebuffer.pixels,
        )
    }

    @Test
    fun tightFillCopyAndJpegPaths() {
        val deflater = Deflater()
        val rgb = ByteArray(4 * 3) { i -> if (i % 3 == 2) 0xFF.toByte() else 0 } // four blue pixels
        deflater.setInput(rgb)
        val compressed = ByteArray(256).let { it.copyOf(deflater.deflate(it, 0, it.size, Deflater.SYNC_FLUSH)) }
        var jpegCalls = 0
        val jpeg = RfbJpegDecoder { _, length, fb, x, y, w, h ->
            jpegCalls++
            assertEquals(3, length)
            for (dy in 0 until h) for (dx in 0 until w) fb.pixels[(y + dy) * fb.width + x + dx] = white
        }
        val (client, _) = connect(serverInit(4, 3) {
            update(
                { rectHeader(0, 0, 4, 1, RfbClient.ENCODING_TIGHT); writeByte(0x80); writeByte(0xFF); writeByte(0); writeByte(0) },
                { rectHeader(0, 1, 4, 1, RfbClient.ENCODING_TIGHT); writeByte(0x00); writeByte(compressed.size); write(compressed) },
                { rectHeader(0, 2, 4, 1, RfbClient.ENCODING_TIGHT); writeByte(0x90); writeByte(3); write(byteArrayOf(1, 2, 3)) },
            )
        }, jpeg)
        client.readMessage()
        assertArrayEquals(
            intArrayOf(red, red, red, red, blue, blue, blue, blue, white, white, white, white),
            client.framebuffer.pixels,
        )
        assertEquals(1, jpegCalls)
    }

    @Test
    fun lowColorFormatIsRequestedAndDecodedInEveryEncoding() {
        // RGB565 little-endian: red 0xF800, green 0x07E0, blue 0x001F.
        fun DataOutputStream.p16(v: Int) { writeByte(v and 0xFF); writeByte(v shr 8 and 0xFF) }
        val deflater = Deflater()
        val zrle = ByteArray(64).let { out ->
            deflater.setInput(byteArrayOf(1, 0xE0.toByte(), 0x07)) // solid green CPIXEL (2 bytes)
            out.copyOf(deflater.deflate(out, 0, out.size, Deflater.SYNC_FLUSH))
        }
        val (client, sent) = connect(serverInit(2, 3) {
            update(
                { rectHeader(0, 0, 2, 1, RfbClient.ENCODING_RAW); p16(0xF800); p16(0x001F) },
                { rectHeader(0, 1, 2, 1, RfbClient.ENCODING_ZRLE); writeInt(zrle.size); write(zrle) },
                { rectHeader(0, 2, 2, 1, RfbClient.ENCODING_TIGHT); writeByte(0x80); p16(0xF800) },
            )
        }, format = RfbPixelFormat.RGB565)
        // Version (12) + security choice (1) + ClientInit (1), then SetPixelFormat: type, 3 pad, format.
        val message = sent.toByteArray().copyOfRange(14, 34)
        assertEquals(0, message[0].toInt())
        assertEquals(16, message[4].toInt()) // bits per pixel
        assertEquals(16, message[5].toInt()) // depth
        client.readMessage()
        assertArrayEquals(intArrayOf(red, blue, green, green, red, red), client.framebuffer.pixels)
    }

    @Test
    fun cursorShapeHonoursItsMaskAndHotspot() {
        val (client, _) = connect(serverInit(4, 1) {
            update({
                rectHeader(1, 0, 2, 1, RfbClient.ENCODING_CURSOR) // hotspot (1, 0), 2×1 pixels
                raw32(red); raw32(blue)
                writeByte(0b1000_0000) // only the first pixel is visible
            })
        })
        val event = client.readMessage() as RfbEvent.FramebufferUpdated
        val cursor = requireNotNull(event.cursor)
        assertTrue(event.rects.isEmpty())
        assertEquals(1, cursor.hotspotX)
        assertEquals(0, cursor.hotspotY)
        assertArrayEquals(intArrayOf(red, 0), cursor.pixels)
    }

    @Test
    fun clientMessagesUseRfbWireFormat() {
        val (client, sent) = connect(serverInit(10, 10) {})
        val afterHandshake = sent.size()
        client.pointer(3, 4, 1)
        client.key(RfbKeys.RETURN, true)
        client.clientCutText("hi")
        val bytes = sent.toByteArray().copyOfRange(afterHandshake, sent.size())
        assertArrayEquals(
            byteArrayOf(5, 1, 0, 3, 0, 4) +
                byteArrayOf(4, 1, 0, 0, 0, 0, 0xFF.toByte(), 0x0D) +
                byteArrayOf(6, 0, 0, 0, 0, 0, 0, 2, 'h'.code.toByte(), 'i'.code.toByte()),
            bytes,
        )
    }

    @Test
    fun appleRemoteDesktopAuthEncryptsCredentialsWithTheSharedSecret() {
        val prime = BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A63A3620FFFFFFFFFFFFFFFF", 16)
        val keyLength = 96
        val serverPrivate = BigInteger("123456789ABCDEF", 16)
        val serverPublic = BigInteger.valueOf(2).modPow(serverPrivate, prime)
        fun fixed(v: BigInteger) = v.toByteArray().let { if (it.size > keyLength) it.copyOfRange(it.size - keyLength, it.size) else ByteArray(keyLength - it.size) + it }
        val server = ByteArrayOutputStream()
        DataOutputStream(server).apply {
            writeShort(2); writeShort(keyLength); write(fixed(prime)); write(fixed(serverPublic))
        }
        val sent = ByteArrayOutputStream()
        RfbAuth.ard(DataInputStream(ByteArrayInputStream(server.toByteArray())), DataOutputStream(sent), "me", "secret")
        val response = sent.toByteArray()
        assertEquals(128 + keyLength, response.size)
        val clientPublic = BigInteger(1, response.copyOfRange(128, response.size))
        val key = MessageDigest.getInstance("MD5").digest(fixed(clientPublic.modPow(serverPrivate, prime)))
        val plain = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }
            .doFinal(response.copyOfRange(0, 128))
        assertEquals("me", String(plain, 0, 2)); assertEquals(0, plain[2].toInt())
        assertEquals("secret", String(plain, 64, 6)); assertEquals(0, plain[70].toInt())
    }

    @Test
    fun scalerAveragesTwoByTwoBlocksAndCoversOddEdges() {
        val fb = Framebuffer(3, 2)
        fb.pixels.indices.forEach { fb.pixels[it] = 0xFF000000.toInt() }
        fb.pixels[0] = 0xFF040404.toInt()
        val out = ScreenScaler.outputRect(RfbRect(0, 0, 3, 2), 2, 3, 2)
        assertEquals(RfbRect(0, 0, 2, 1), out)
        val dst = IntArray(2)
        ScreenScaler.copy(fb, 2, out, dst)
        assertEquals(0xFF010101.toInt(), dst[0])
        assertEquals(RfbRect(0, 0, 1, 1), ScreenScaler.outputRect(RfbRect(1, 1, 1, 1), 2, 3, 2))
        assertEquals(2, ScreenScaler.scaleFor(3600, 2338))
        assertEquals(1, ScreenScaler.scaleFor(2560, 1440))
    }

    @Test
    fun manySmallRectsMergeIntoTheirBoundingBox() {
        val rects = List(20) { RfbRect(it, it, 1, 1) }
        assertEquals(listOf(RfbRect(0, 0, 20, 20)), ScreenScaler.merge(rects))
        assertEquals(rects.take(3), ScreenScaler.merge(rects.take(3)))
    }

    @Test
    fun keysymsCoverLatinAndRejectOtherScripts() {
        assertEquals('a'.code, RfbKeys.forChar('a'))
        assertEquals(0xE9, RfbKeys.forChar('é'))
        assertEquals(RfbKeys.RETURN, RfbKeys.forChar('\n'))
        assertNull(RfbKeys.forChar('中'))
    }
}
