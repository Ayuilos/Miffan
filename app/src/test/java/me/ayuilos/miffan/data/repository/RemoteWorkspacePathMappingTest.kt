package me.ayuilos.miffan.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteWorkspacePathMappingTest {
    @Test fun realPathTakesPrecedenceOverVirtualAlias() {
        assertEquals("a.txt", mapRemoteRootfsPath("/workspace/project", "/workspace/project/a.txt"))
        assertEquals("a.txt", mapRemoteRootfsPath("/workspace/project", "/workspace/a.txt"))
    }

    @Test fun rejectsUnrelatedAbsolutePaths() {
        assertThrows(IllegalStateException::class.java) {
            mapRemoteRootfsPath("/srv/project", "/srv/other/private")
        }
    }
}
