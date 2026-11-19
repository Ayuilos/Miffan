package me.ayuilos.miffan.data.repository.relay

import android.os.Build
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Installs `libcronet.143.0.7445.0.so` on demand without bundling native Cronet `.so` binaries
 * inside the base APK (~6.5 MiB/ABI savings for users who never use Relay).
 *
 * Only exact pinned slices of `org.chromium.net:cronet-embedded:143.7445.0` from mainland-first
 * official Maven mirrors are accepted, verified strictly by byte length + SHA-256, and marked
 * read-only before `System.load()` for Android 14+ W^X Safer DCL compatibility.
 */
class CronetNativeInstaller internal constructor(
    private val installRootDir: File,
    private val okHttpClient: OkHttpClient,
    private val supportedAbisProvider: () -> Array<String> = { Build.SUPPORTED_ABIS },
    private val mirrorUrls: List<String> = DEFAULT_MIRROR_URLS,
    private val specs: Map<String, PinnedSpec> = PINNED_SPECS,
) {
    constructor(
        installRootDir: File,
        okHttpClient: OkHttpClient,
    ) : this(installRootDir, okHttpClient, { Build.SUPPORTED_ABIS }, DEFAULT_MIRROR_URLS, PINNED_SPECS)
    internal data class PinnedSpec(
        val abi: String,
        val zipEntry: String,
        val compressedStart: Long,
        val compressedEndInclusive: Long,
        val compressedBytes: Long,
        val uncompressedBytes: Long,
        val sha256: String,
    )

    private val installLock = Any()
    @Volatile private var verifiedFile: File? = null

    private val downloadClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /** Returns an already-verified read-only native Cronet library, or null if not yet installed. */
    fun installedFile(): File? {
        val spec = resolveSpec() ?: return null
        verifiedFile?.takeIf { it.isFile && it.length() == spec.uncompressedBytes && !it.canWrite() }?.let { return it }

        val target = targetFile(spec)
        if (!target.isFile || target.length() != spec.uncompressedBytes) return null
        if (!verifyFileSha256(target, spec)) {
            runCatching {
                target.setWritable(true)
                target.delete()
            }
            return null
        }
        if (target.canWrite()) target.setReadOnly()
        if (target.canWrite()) return null
        verifiedFile = target
        return target
    }

    /** Downloads and verifies `libcronet.143.0.7445.0.so` if missing. Returns null if all mirrors fail or ABI unsupported. */
    fun ensureInstalled(): File? = synchronized(installLock) {
        installedFile()?.let { return it }
        val spec = resolveSpec() ?: return null

        val abiDir = File(installRootDir, spec.abi).apply { mkdirs() }
        val target = targetFile(spec)

        for (url in mirrorUrls) {
            val tmp = File(abiDir, "$LIB_FILE_NAME.tmp.${System.nanoTime()}")
            try {
                downloadAndVerify(url, spec, tmp)
                tmp.setReadOnly()
                if (target.exists()) {
                    target.setWritable(true)
                    target.delete()
                }
                if (!tmp.renameTo(target)) {
                    throw IOException("Failed to move verified Cronet library to ${target.absolutePath}")
                }
                if (target.canWrite()) target.setReadOnly()
                if (target.canWrite()) {
                    throw IOException("Cronet library must remain read-only for Safer DCL")
                }
                verifiedFile = target
                return target
            } catch (_: Throwable) {
                runCatching {
                    tmp.setWritable(true)
                    tmp.delete()
                }
            }
        }
        return null
    }

    private fun resolveSpec(): PinnedSpec? =
        supportedAbisProvider().firstNotNullOfOrNull { abi -> specs[abi] }

    private fun targetFile(spec: PinnedSpec): File =
        File(File(installRootDir, spec.abi), LIB_FILE_NAME)

    private fun downloadAndVerify(url: String, spec: PinnedSpec, outFile: File) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${spec.compressedStart}-${spec.compressedEndInclusive}")
            .header("Accept-Encoding", "identity")
            .build()

        downloadClient.newCall(request).execute().use { response ->
            when (response.code) {
                206 -> {
                    val contentRange = response.header("Content-Range").orEmpty()
                    val expectedPrefix = "bytes ${spec.compressedStart}-${spec.compressedEndInclusive}/"
                    if (contentRange.isNotEmpty() && !contentRange.startsWith(expectedPrefix)) {
                        throw IOException("Unexpected Content-Range $contentRange from $url")
                    }
                    val limited = ExactLengthInputStream(response.body.byteStream(), spec.compressedBytes)
                    val inflater = Inflater(true)
                    try {
                        InflaterInputStream(limited, inflater).use { inflated ->
                            copyAndVerify(inflated, spec, outFile)
                        }
                    } finally {
                        inflater.end()
                    }
                    if (limited.remaining() != 0L) {
                        throw IOException("Truncated 206 Range response (${limited.remaining()} compressed bytes short)")
                    }
                }
                200 -> {
                    ZipInputStream(response.body.byteStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null && entry.name != spec.zipEntry) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                        if (entry == null) {
                            throw IOException("Entry ${spec.zipEntry} missing in $url")
                        }
                        copyAndVerify(zis, spec, outFile)
                    }
                }
                else -> throw IOException("Mirror HTTP ${response.code} for $url")
            }
        }
    }

    private fun copyAndVerify(input: InputStream, spec: PinnedSpec, outFile: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        var total = 0L
        FileOutputStream(outFile).use { fos ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                total += n
                if (total > spec.uncompressedBytes) {
                    throw IOException("Cronet binary exceeds expected ${spec.uncompressedBytes} bytes")
                }
                digest.update(buf, 0, n)
                fos.write(buf, 0, n)
            }
            fos.fd.sync()
        }
        if (total != spec.uncompressedBytes) {
            throw IOException("Cronet binary size mismatch: got $total, expected ${spec.uncompressedBytes}")
        }
        val hex = digest.digest().toHexLower()
        if (hex != spec.sha256) {
            throw IOException("Cronet binary SHA-256 mismatch: got $hex, expected ${spec.sha256}")
        }
    }

    private fun verifyFileSha256(file: File, spec: PinnedSpec): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        var total = 0L
        file.inputStream().use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > spec.uncompressedBytes) return false
                digest.update(buf, 0, n)
            }
        }
        total == spec.uncompressedBytes && digest.digest().toHexLower() == spec.sha256
    }.getOrDefault(false)

    private fun ByteArray.toHexLower(): String {
        val chars = CharArray(size * 2)
        val digits = "0123456789abcdef"
        for (i in indices) {
            val v = this[i].toInt() and 0xff
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0f]
        }
        return String(chars)
    }

    private class ExactLengthInputStream(
        private val delegate: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        fun remaining(): Long = remaining

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = delegate.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0L) return -1
            val capped = minOf(len.toLong(), remaining).toInt()
            val n = delegate.read(b, off, capped)
            if (n < 0) throw EOFException("Unexpected EOF with $remaining bytes left")
            remaining -= n
            return n
        }
    }

    companion object {
        const val LIB_FILE_NAME = "libcronet.143.0.7445.0.so"
        const val CRONET_AAR_VERSION = "143.7445.0"

        val DEFAULT_MIRROR_URLS = listOf(
            "https://maven.aliyun.com/repository/google/org/chromium/net/cronet-embedded/143.7445.0/cronet-embedded-143.7445.0.aar",
            "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/org/chromium/net/cronet-embedded/143.7445.0/cronet-embedded-143.7445.0.aar",
            "https://dl.google.com/dl/android/maven2/org/chromium/net/cronet-embedded/143.7445.0/cronet-embedded-143.7445.0.aar",
        )

        internal val PINNED_SPECS = mapOf(
            "arm64-v8a" to PinnedSpec(
                abi = "arm64-v8a",
                zipEntry = "jni/arm64-v8a/libcronet.143.0.7445.0.so",
                compressedStart = 10441937L,
                compressedEndInclusive = 13712168L,
                compressedBytes = 3270232L,
                uncompressedBytes = 6500848L,
                sha256 = "7d955904c4fd86cece96f7bd5f95248bd7907b19da67bcc7b73f436d864b37c4",
            ),
            "x86_64" to PinnedSpec(
                abi = "x86_64",
                zipEntry = "jni/x86_64/libcronet.143.0.7445.0.so",
                compressedStart = 606516L,
                compressedEndInclusive = 4150543L,
                compressedBytes = 3544028L,
                uncompressedBytes = 7194688L,
                sha256 = "0419dfa8d333f7cc72538d23f6c1a7acd06ae63322dee0b85b5312c6feb051bd",
            ),
        )
    }
}
