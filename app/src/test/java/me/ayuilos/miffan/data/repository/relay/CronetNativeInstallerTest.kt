package me.ayuilos.miffan.data.repository.relay

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CronetNativeInstallerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun pinnedSpecsAndMirrorsAreConsistent() {
        assertTrue(CronetNativeInstaller.DEFAULT_MIRROR_URLS.first().startsWith("https://maven.aliyun.com/"))
        assertTrue(CronetNativeInstaller.DEFAULT_MIRROR_URLS[1].startsWith("https://mirrors.cloud.tencent.com/"))
        assertEquals(2, CronetNativeInstaller.PINNED_SPECS.size)
        for ((abi, spec) in CronetNativeInstaller.PINNED_SPECS) {
            assertEquals(abi, spec.abi)
            assertEquals(spec.compressedBytes, spec.compressedEndInclusive - spec.compressedStart + 1)
            assertEquals(64, spec.sha256.length)
        }
    }

    @Test
    fun installsVia206RawDeflateRangeAndMarksReadOnly() {
        val raw = ByteArray(64 * 1024) { (it * 31 + 7).toByte() }
        val sha = sha256Hex(raw)
        val compressed = deflateRaw(raw)
        val prefix = ByteArray(128) { 0x5a }
        val archive = prefix + compressed + ByteArray(64) { 0x3c }
        val start = prefix.size.toLong()
        val endInclusive = start + compressed.size - 1

        var requestedRange: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cronet.aar") { ex ->
            requestedRange = ex.requestHeaders.getFirst("Range")
            val slice = archive.copyOfRange(start.toInt(), endInclusive.toInt() + 1)
            ex.responseHeaders.set("Content-Range", "bytes $start-$endInclusive/${archive.size}")
            ex.sendResponseHeaders(206, slice.size.toLong())
            ex.responseBody.use { it.write(slice) }
        }
        server.start()
        try {
            val rootDir = tempFolder.newFolder("cronet")
            val specs = mapOf(
                "arm64-v8a" to CronetNativeInstaller.PinnedSpec(
                    abi = "arm64-v8a",
                    zipEntry = "jni/arm64-v8a/${CronetNativeInstaller.LIB_FILE_NAME}",
                    compressedStart = start,
                    compressedEndInclusive = endInclusive,
                    compressedBytes = compressed.size.toLong(),
                    uncompressedBytes = raw.size.toLong(),
                    sha256 = sha,
                ),
            )
            val installer = CronetNativeInstaller(
                installRootDir = rootDir,
                okHttpClient = OkHttpClient(),
                supportedAbisProvider = { arrayOf("arm64-v8a") },
                mirrorUrls = listOf("http://127.0.0.1:${server.address.port}/cronet.aar"),
                specs = specs,
            )

            assertNull(installer.installedFile())
            val installed = installer.ensureInstalled()
            assertNotNull(installed)
            assertEquals("bytes=$start-$endInclusive", requestedRange)
            assertArrayEquals(raw, installed!!.readBytes())
            assertFalse(installed.canWrite())
            assertEquals(installed.absolutePath, installer.installedFile()?.absolutePath)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun rejectsTamperedPayloadAndFallsBackToFullZipMirror() {
        val raw = ByteArray(48 * 1024) { (it xor 0x55).toByte() }
        val sha = sha256Hex(raw)
        val entryPath = "jni/x86_64/${CronetNativeInstaller.LIB_FILE_NAME}"
        val zipBytes = buildZip(entryPath, raw)
        val tampered = deflateRaw(raw.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })

        val badHits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tampered.aar") { ex ->
            badHits.incrementAndGet()
            ex.responseHeaders.set("Content-Range", "bytes 0-${tampered.size - 1}/${tampered.size}")
            ex.sendResponseHeaders(206, tampered.size.toLong())
            ex.responseBody.use { it.write(tampered) }
        }
        server.createContext("/full.aar") { ex ->
            ex.sendResponseHeaders(200, zipBytes.size.toLong())
            ex.responseBody.use { it.write(zipBytes) }
        }
        server.start()
        try {
            val rootDir = tempFolder.newFolder("cronet-fallback")
            val specs = mapOf(
                "x86_64" to CronetNativeInstaller.PinnedSpec(
                    abi = "x86_64",
                    zipEntry = entryPath,
                    compressedStart = 0L,
                    compressedEndInclusive = tampered.size.toLong() - 1,
                    compressedBytes = tampered.size.toLong(),
                    uncompressedBytes = raw.size.toLong(),
                    sha256 = sha,
                ),
            )
            val base = "http://127.0.0.1:${server.address.port}"
            val installer = CronetNativeInstaller(
                installRootDir = rootDir,
                okHttpClient = OkHttpClient(),
                supportedAbisProvider = { arrayOf("x86_64") },
                mirrorUrls = listOf("$base/tampered.aar", "$base/full.aar"),
                specs = specs,
            )

            val installed = installer.ensureInstalled()
            assertNotNull(installed)
            assertEquals(1, badHits.get())
            assertArrayEquals(raw, installed!!.readBytes())
            assertFalse(installed.canWrite())
            val abiDir = File(rootDir, "x86_64")
            assertEquals(listOf(CronetNativeInstaller.LIB_FILE_NAME), abiDir.list()?.toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun unsupportedAbiReturnsNullWithoutNetworkCalls() {
        val installer = CronetNativeInstaller(
            installRootDir = tempFolder.newFolder("cronet-unsupported"),
            okHttpClient = OkHttpClient(),
            supportedAbisProvider = { arrayOf("armeabi-v7a") },
            mirrorUrls = listOf("http://127.0.0.1:1/unreachable.aar"),
        )
        assertNull(installer.installedFile())
        assertNull(installer.ensureInstalled())
    }

    private fun deflateRaw(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun buildZip(targetEntry: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(targetEntry))
            zos.write(data)
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
