package me.ayuilos.miffan.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFileTypeTest {
    @Test
    fun `extensionless source files are previewed as text`() {
        assertEquals(WorkspacePreviewKind.TEXT, detectWorkspacePreviewKind("Dockerfile"))
        assertEquals(WorkspacePreviewKind.TEXT, detectWorkspacePreviewKind("Makefile"))
    }

    @Test
    fun `rich preview kinds are detected before generic text`() {
        assertEquals(WorkspacePreviewKind.MARKDOWN, detectWorkspacePreviewKind("README.md"))
        assertEquals(WorkspacePreviewKind.JSON, detectWorkspacePreviewKind("result.json"))
        assertEquals(WorkspacePreviewKind.DELIMITED_TEXT, detectWorkspacePreviewKind("table.csv"))
        assertEquals(WorkspacePreviewKind.HTML, detectWorkspacePreviewKind("index.html"))
        assertEquals(WorkspacePreviewKind.PDF, detectWorkspacePreviewKind("report.pdf"))
        assertEquals(WorkspacePreviewKind.DOCUMENT_TEXT, detectWorkspacePreviewKind("slides.pptx"))
    }

    @Test
    fun `mime type can identify extensionless image`() {
        assertEquals(
            WorkspacePreviewKind.IMAGE,
            detectWorkspacePreviewKind(fileName = "cover", mimeType = "image/png"),
        )
    }
}
