package me.rerere.workspace

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsPageSizeCompatibilityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `ELF load segment can support 4 KB but fail on 16 KB pages`() {
        val elf = tmp.newFile("libc.so.6")
        elf.writeBytes(elf64(fileOffset = 0, virtualAddress = 4L * 1024))

        assertNull(
            RootfsPageSizeCompatibility.pageSizeViolation(
                elf,
                "usr/lib/x86_64-linux-gnu/libc.so.6",
                4L * 1024,
            )
        )
        val violation = RootfsPageSizeCompatibility.pageSizeViolation(
            elf,
            "usr/lib/x86_64-linux-gnu/libc.so.6",
            16L * 1024,
        )

        requireNotNull(violation)
        assertEquals(0, violation.segmentIndex)
        assertEquals(4L * 1024, violation.virtualAddress - violation.fileOffset)
    }

    @Test
    fun `full Rootfs scan rejects an incompatible shared library`() {
        val rootfs = tmp.newFolder("rootfs")
        val libc = File(rootfs, "usr/lib/x86_64-linux-gnu/libc.so.6")
        requireNotNull(libc.parentFile).mkdirs()
        libc.writeBytes(elf64(fileOffset = 0, virtualAddress = 4L * 1024))

        val error = assertThrows(IllegalArgumentException::class.java) {
            RootfsPageSizeCompatibility.requireAllElfCompatible(rootfs, 16L * 1024)
        }

        assertTrue(error.message.orEmpty().contains("libc.so.6"))
        assertTrue(error.message.orEmpty().contains("16 KB"))
    }

    @Test
    fun `runtime preflight follows only critical paths inside Rootfs`() {
        val rootfs = tmp.newFolder("runtime-rootfs")
        val libc = File(rootfs, "usr/lib/x86_64-linux-gnu/libc.so.6")
        requireNotNull(libc.parentFile).mkdirs()
        libc.writeBytes(elf64(fileOffset = 0, virtualAddress = 4L * 1024))

        val error = assertThrows(IllegalArgumentException::class.java) {
            RootfsPageSizeCompatibility.requireRuntimeCompatible(rootfs, 16L * 1024)
        }

        assertTrue(error.message.orEmpty().contains("arm64-v8a 16 KB"))
    }

    @Test
    fun `non ELF files are ignored`() {
        val rootfs = tmp.newFolder("text-rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("#!/bin/sh\n")

        RootfsPageSizeCompatibility.requireAllElfCompatible(rootfs, 16L * 1024)
        RootfsPageSizeCompatibility.requireRuntimeCompatible(rootfs, 16L * 1024)
    }

    private fun elf64(fileOffset: Long, virtualAddress: Long): ByteArray =
        ByteBuffer.allocate(64 + 56)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(0, 0x7f.toByte())
                put(1, 'E'.code.toByte())
                put(2, 'L'.code.toByte())
                put(3, 'F'.code.toByte())
                put(4, 2) // ELFCLASS64
                put(5, 1) // ELFDATA2LSB
                putLong(32, 64) // e_phoff
                putShort(54, 56) // e_phentsize
                putShort(56, 1) // e_phnum
                putInt(64, 1) // PT_LOAD
                putLong(64 + 8, fileOffset)
                putLong(64 + 16, virtualAddress)
                putLong(64 + 48, 4L * 1024)
            }
            .array()
}
