package me.ayuilos.miffan.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceImagesTest {
    private val context = WorkspaceImageContext("ws-1", null, "/home/me", "msg-1")

    @Test
    fun workspacePathsBecomeWorkspaceUris() {
        val text = "Here it is:\n\n![Screen](/workspace/.miffan/shot now.png)\n![Real](/home/me/a.png \"title\")"
        val rewritten = text.withWorkspaceImages(context)
        assertTrue(rewritten.contains("![Real](miffan-workspace://file?w=ws-1&p=%2Fhome%2Fme%2Fa.png&m=msg-1 \"title\")"))
        // A path with a space is not a valid bare Markdown destination and stays as written.
        assertTrue(rewritten.contains("![Screen](/workspace/.miffan/shot now.png)"))
    }

    @Test
    fun otherImagesAreUntouched() {
        val text = "![web](https://example.com/a.png) ![phone](file:///data/a.png) ![elsewhere](/etc/passwd) [link](/workspace/a.txt)"
        assertEquals(text, text.withWorkspaceImages(context))
        assertEquals("![x](/workspace/a.png)", "![x](/workspace/a.png)".withWorkspaceImages(null))
    }

    @Test
    fun embeddedPathsAreFound() {
        val text = "![a](/workspace/a.png) and ![b](https://x/b.png) and ![c](/home/me/c.png)"
        assertEquals(setOf("/workspace/a.png", "/home/me/c.png"), embeddedWorkspaceImagePaths(text, context))
    }
}
