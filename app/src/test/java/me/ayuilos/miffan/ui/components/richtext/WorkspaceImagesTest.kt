package me.ayuilos.miffan.ui.components.richtext

import coil3.ImageLoader
import coil3.request.Options
import coil3.toUri
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceImagesTest {
    private val context = WorkspaceImageContext("ws-1", null, "/home/me", "msg-1")

    @Test
    fun imageFactoryDoesNotInitializeWorkspaceRecoveryDuringCoilSetup() {
        val factory = WorkspaceImageFetcher.Factory(
            workspaces = { error("Workspace graph must only be resolved inside fetch on IO") },
            cacheDir = File("unused-cache"),
        )
        val options = mockk<Options>()
        val loader = mockk<ImageLoader>()
        assertNull(factory.create("https://example.com/avatar.png".toUri(), options, loader))
        assertNotNull(factory.create("miffan-workspace://file?w=ws-1&p=shot.png".toUri(), options, loader))
    }

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
