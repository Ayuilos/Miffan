package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteWorkspacePathTest {
    private val paths = RemoteWorkspacePath("/srv/projects/demo")

    @Test fun mapsVirtualAndRealPathsToSameFile() {
        assertEquals("/srv/projects/demo/src/Main.kt", paths.absolute("src/Main.kt"))
        assertEquals("/srv/projects/demo/src/Main.kt", paths.absolute("/workspace/src/Main.kt"))
        assertEquals("/srv/projects/demo/src/Main.kt", paths.absolute("/srv/projects/demo/src/Main.kt"))
        assertEquals("src/Main.kt", paths.relative("/srv/projects/demo/src/Main.kt"))
    }

    @Test fun rejectsEscapesAndOtherAbsolutePaths() {
        listOf("../secret", "a/../../secret", "/etc/passwd", "/srv/projects/demo2/file", "a//b", "*.txt")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) { paths.absolute(path) }
            }
    }

    @Test fun rootIsAllowedOnlyForDirectoryOperations() {
        assertEquals("/srv/projects/demo", paths.absolute("/workspace", allowRoot = true))
        assertThrows(IllegalArgumentException::class.java) { paths.absolute("/workspace") }
    }

    @Test fun realPathInsideWorkspaceNamedDirectoryWinsOverVirtualAlias() {
        val nested = RemoteWorkspacePath("/workspace/project")
        assertEquals("/workspace/project/a", nested.absolute("/workspace/project/a"))
        assertEquals("/workspace/project/a", nested.absolute("a"))
    }
}
