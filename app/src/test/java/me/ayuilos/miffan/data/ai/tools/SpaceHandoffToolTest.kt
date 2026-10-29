package me.ayuilos.miffan.data.ai.tools

import me.ayuilos.miffan.data.ai.computer.SpaceUsage
import me.ayuilos.miffan.data.ai.computer.parseSpaceUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpaceHandoffToolTest {
    @Test
    fun `paths name something inside the Shared folder`() {
        assertEquals("report.pdf", spaceSharedPath("report.pdf"))
        assertEquals("photos/a b.png", spaceSharedPath(" photos/a b.png "))
        assertEquals("报告.pdf", spaceSharedPath("/home/cua/Desktop/Shared/报告.pdf"))
        assertEquals("dir", spaceSharedPath("~/Desktop/Shared/dir/"))
        assertEquals("x.txt", spaceSharedPath("/workspace/Desktop/Shared/x.txt"))
    }

    @Test
    fun `paths outside the Shared folder are refused`() {
        for (bad in listOf("", "/home/cua/Desktop/Shared", "/home/cua/report.pdf", "~/report.pdf", "../x", "a/../../x",
            "./a", "a//b", "/etc/passwd", "a\nb", "/home/cua/Desktop/SharedX/a")) {
            assertNull(bad, spaceSharedPath(bad))
        }
    }

    @Test
    fun `usage reads memory without file cache and CPU over its cores`() {
        val mb = 1024L * 1024
        val line = "${900 * mb} ${4096 * mb} ${400 * mb} 1000000 1250000"
        assertEquals(SpaceUsage(500, 4096, 25), parseSpaceUsage(line, cpus = 2))
        // No limit set; no CPU reading.
        assertEquals(SpaceUsage(100, null, null), parseSpaceUsage("${100 * mb} max 0 x y", cpus = 2))
        assertNull(parseSpaceUsage("garbage", cpus = 2))
        assertNull(parseSpaceUsage("", cpus = null))
    }
}
