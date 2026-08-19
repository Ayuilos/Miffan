package me.rerere.workspace

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption

data class RootfsElfPageSizeViolation(
    val relativePath: String,
    val segmentIndex: Int,
    val fileOffset: Long,
    val virtualAddress: Long,
)

/** Validates the ELF address/offset congruence required by the Linux and glibc loaders. */
object RootfsPageSizeCompatibility {
    fun requireAllElfCompatible(rootfs: File, pageSizeBytes: Long) {
        requirePageSize(pageSizeBytes)
        require(Files.isDirectory(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs page-size check requires a real directory"
        }
        Files.walk(rootfs.toPath()).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                val relativePath = rootfs.toPath().relativize(path).joinToString("/")
                pageSizeViolation(path.toFile(), relativePath, pageSizeBytes)?.let { violation ->
                    throw IllegalArgumentException(violation.message(pageSizeBytes))
                }
            }
        }
    }

    /** Fast preflight for an installed Rootfs before every AI or interactive shell launch. */
    fun requireRuntimeCompatible(rootfs: File, pageSizeBytes: Long) {
        requirePageSize(pageSizeBytes)
        val canonicalRoot = rootfs.canonicalFile
        RUNTIME_CRITICAL_PATHS.asSequence()
            .map { relativePath -> relativePath to File(canonicalRoot, relativePath).canonicalFile }
            .filter { (_, file) -> file.isWithin(canonicalRoot) }
            .filter { (_, file) -> Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .distinctBy { (_, file) -> file.path }
            .forEach { (relativePath, file) ->
                pageSizeViolation(file, relativePath, pageSizeBytes)?.let { violation ->
                    throw IllegalArgumentException(violation.message(pageSizeBytes))
                }
            }
    }

    internal fun pageSizeViolation(
        file: File,
        relativePath: String,
        pageSizeBytes: Long,
    ): RootfsElfPageSizeViolation? {
        requirePageSize(pageSizeBytes)
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < ELF_IDENT_SIZE) return null
            val ident = ByteArray(ELF_IDENT_SIZE)
            input.readFully(ident)
            if (!ident.hasElfMagic()) return null

            val byteOrder = when (ident[ELF_DATA_INDEX].toInt() and 0xff) {
                ELF_DATA_LITTLE_ENDIAN -> ByteOrder.LITTLE_ENDIAN
                ELF_DATA_BIG_ENDIAN -> ByteOrder.BIG_ENDIAN
                else -> throw malformedElf(relativePath, "unknown byte order")
            }
            val layout = when (ident[ELF_CLASS_INDEX].toInt() and 0xff) {
                ELF_CLASS_32 -> ElfLayout.ELF32
                ELF_CLASS_64 -> ElfLayout.ELF64
                else -> throw malformedElf(relativePath, "unknown ELF class")
            }
            val header = readAt(input, 0, layout.headerSize, relativePath).order(byteOrder)
            val programHeaderOffset = layout.programHeaderOffset(header)
            val programHeaderEntrySize = layout.programHeaderEntrySize(header)
            val programHeaderCount = layout.programHeaderCount(header)
            if (programHeaderCount == 0) return null
            require(programHeaderEntrySize >= layout.minimumProgramHeaderSize) {
                "Malformed ELF /$relativePath: program header entry is too small"
            }
            val tableSize = runCatching {
                Math.multiplyExact(programHeaderEntrySize.toLong(), programHeaderCount.toLong())
            }.getOrElse { throw malformedElf(relativePath, "program header table overflows") }
            val tableEnd = runCatching {
                Math.addExact(programHeaderOffset, tableSize)
            }.getOrElse { throw malformedElf(relativePath, "program header table overflows") }
            require(programHeaderOffset >= 0 && tableEnd <= input.length()) {
                "Malformed ELF /$relativePath: program header table is outside the file"
            }

            repeat(programHeaderCount) { index ->
                val entryOffset = programHeaderOffset + index.toLong() * programHeaderEntrySize
                val entry = readAt(
                    input,
                    entryOffset,
                    layout.minimumProgramHeaderSize,
                    relativePath,
                ).order(byteOrder)
                if (entry.getInt(0) != PT_LOAD) return@repeat
                val fileOffset = layout.segmentFileOffset(entry)
                val virtualAddress = layout.segmentVirtualAddress(entry)
                require(fileOffset >= 0 && virtualAddress >= 0) {
                    "Malformed ELF /$relativePath: PT_LOAD values exceed the supported range"
                }
                val delta = runCatching { Math.subtractExact(virtualAddress, fileOffset) }
                    .getOrElse { throw malformedElf(relativePath, "PT_LOAD address delta overflows") }
                if (Math.floorMod(delta, pageSizeBytes) != 0L) {
                    return RootfsElfPageSizeViolation(
                        relativePath = relativePath,
                        segmentIndex = index,
                        fileOffset = fileOffset,
                        virtualAddress = virtualAddress,
                    )
                }
            }
        }
        return null
    }

    private fun readAt(
        input: RandomAccessFile,
        offset: Long,
        byteCount: Int,
        relativePath: String,
    ): ByteBuffer {
        require(offset >= 0 && offset <= input.length() - byteCount) {
            "Malformed ELF /$relativePath: truncated header"
        }
        val bytes = ByteArray(byteCount)
        input.seek(offset)
        input.readFully(bytes)
        return ByteBuffer.wrap(bytes)
    }

    private fun RootfsElfPageSizeViolation.message(pageSizeBytes: Long): String = buildString {
        append("Rootfs ELF /$relativePath is incompatible with ${pageSizeBytes / 1024} KB pages ")
        append(
            "(PT_LOAD $segmentIndex offset=0x${fileOffset.toString(16)}, " +
                "address=0x${virtualAddress.toString(16)}). "
        )
        if (relativePath.contains("x86_64")) {
            append("Use an arm64-v8a 16 KB emulator image or an x86_64 4 KB image.")
        } else {
            append("Reinstall a compatible Rootfs or use a device with a supported page size.")
        }
    }

    private fun File.isWithin(root: File): Boolean =
        path == root.path || path.startsWith(root.path + File.separator)

    private fun requirePageSize(pageSizeBytes: Long) {
        require(pageSizeBytes > 0 && pageSizeBytes and (pageSizeBytes - 1) == 0L) {
            "Page size must be a positive power of two: $pageSizeBytes"
        }
    }

    private fun ByteArray.hasElfMagic(): Boolean =
        this[0] == 0x7f.toByte() && this[1] == 'E'.code.toByte() &&
            this[2] == 'L'.code.toByte() && this[3] == 'F'.code.toByte()

    private fun malformedElf(relativePath: String, reason: String) =
        IllegalArgumentException("Malformed ELF /$relativePath: $reason")

    private enum class ElfLayout(
        val headerSize: Int,
        val minimumProgramHeaderSize: Int,
    ) {
        ELF32(headerSize = 52, minimumProgramHeaderSize = 32) {
            override fun programHeaderOffset(header: ByteBuffer): Long =
                header.getInt(28).toLong() and 0xffff_ffffL

            override fun programHeaderEntrySize(header: ByteBuffer): Int =
                header.getShort(42).toInt() and 0xffff

            override fun programHeaderCount(header: ByteBuffer): Int =
                header.getShort(44).toInt() and 0xffff

            override fun segmentFileOffset(entry: ByteBuffer): Long =
                entry.getInt(4).toLong() and 0xffff_ffffL

            override fun segmentVirtualAddress(entry: ByteBuffer): Long =
                entry.getInt(8).toLong() and 0xffff_ffffL
        },
        ELF64(headerSize = 64, minimumProgramHeaderSize = 56) {
            override fun programHeaderOffset(header: ByteBuffer): Long = header.getLong(32)

            override fun programHeaderEntrySize(header: ByteBuffer): Int =
                header.getShort(54).toInt() and 0xffff

            override fun programHeaderCount(header: ByteBuffer): Int =
                header.getShort(56).toInt() and 0xffff

            override fun segmentFileOffset(entry: ByteBuffer): Long = entry.getLong(8)

            override fun segmentVirtualAddress(entry: ByteBuffer): Long = entry.getLong(16)
        };

        abstract fun programHeaderOffset(header: ByteBuffer): Long
        abstract fun programHeaderEntrySize(header: ByteBuffer): Int
        abstract fun programHeaderCount(header: ByteBuffer): Int
        abstract fun segmentFileOffset(entry: ByteBuffer): Long
        abstract fun segmentVirtualAddress(entry: ByteBuffer): Long
    }

    private const val ELF_IDENT_SIZE = 16
    private const val ELF_CLASS_INDEX = 4
    private const val ELF_DATA_INDEX = 5
    private const val ELF_CLASS_32 = 1
    private const val ELF_CLASS_64 = 2
    private const val ELF_DATA_LITTLE_ENDIAN = 1
    private const val ELF_DATA_BIG_ENDIAN = 2
    private const val PT_LOAD = 1

    private val RUNTIME_CRITICAL_PATHS = listOf(
        "usr/bin/env",
        "bin/sh",
        "bin/bash",
        "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "usr/lib/aarch64-linux-gnu/libc.so.6",
        "usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        "usr/lib/x86_64-linux-gnu/libc.so.6",
        "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        "lib/aarch64-linux-gnu/libc.so.6",
        "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        "lib/x86_64-linux-gnu/libc.so.6",
    )
}
