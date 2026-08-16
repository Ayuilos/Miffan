package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsCatalogTest {
    @Test
    fun `selects pinned archive using Android ABI order`() {
        val arm = RootfsCatalog.forAndroidAbis(listOf("armeabi-v7a", "arm64-v8a", "x86_64"))
        val x86 = RootfsCatalog.forAndroidAbis(listOf("x86_64"))

        assertEquals("arm64-v8a", arm.androidAbi)
        assertTrue(arm.url.contains("base-arm64.tar.gz"))
        assertEquals("x86_64", x86.androidAbi)
        assertTrue(x86.url.contains("base-amd64.tar.gz"))
    }

    @Test
    fun `rejects unsupported architectures`() {
        assertThrows(IllegalStateException::class.java) {
            RootfsCatalog.forAndroidAbis(listOf("armeabi-v7a"))
        }
    }

    @Test
    fun `catalog sources are pinned to HTTPS and SHA256`() {
        listOf(
            RootfsCatalog.forAndroidAbis(listOf("arm64-v8a")),
            RootfsCatalog.forAndroidAbis(listOf("x86_64")),
        ).forEach { source ->
            assertTrue(source.url.startsWith("https://"))
            assertTrue(source.sha256.matches(Regex("[0-9a-f]{64}")))
            assertEquals(RootfsCatalog.VERSION, source.version)
        }
    }
}
