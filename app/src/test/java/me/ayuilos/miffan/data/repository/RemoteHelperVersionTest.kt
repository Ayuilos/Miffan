package me.ayuilos.miffan.data.repository

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class RemoteHelperVersionTest {
    @Test fun `generated helpers match their immutable version locks`() {
        val rows = File(requireNotNull(System.getProperty("remoteHelper.versions"))).readLines()
            .filter { it.isNotBlank() }.map { it.split(" ") }
        assertTrue(rows.all { it.size == 3 })
        assertEquals("Duplicate helper version in versions.lock", rows.size, rows.map { it.take(2) }.distinct().size)
        for ((name, pattern) in mapOf("miffan.sh" to "MIFFAN_HELPER_VERSION=(\\d+)",
            "miffan.ps1" to "\\\$script:HelperVersion = (\\d+)")) {
            val message = "$name helper 内容变了：升 MIFFAN_HELPER_VERSION / \$script:HelperVersion，然后更新 versions.lock"
            val bytes = File(System.getProperty("remoteHelper.assets"), name).readBytes()
            val version = requireNotNull(Regex(pattern).find(bytes.toString(Charsets.UTF_8))) { message }.groupValues[1]
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val row = rows.singleOrNull { it[0] == name && it[1] == version }
            assertNotNull(message, row)
            assertEquals(message, row!![2], sha)
            assertEquals("Helper version must advance monotonically", rows.filter { it[0] == name }.maxOf { it[1].toInt() }, version.toInt())
        }
    }
}
