package me.ayuilos.miffan.data.ai.tools

import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceArtifactTest {
    @Test
    fun `persisted artifact keeps original workspace id`() {
        val tool = tool(
            name = "workspace_write_file",
            input = """{"path":"/workspace/report.md","text":"hello"}""",
            output = """{
                "type":"workspace_artifact",
                "workspaceId":"original-workspace",
                "path":"/workspace/report.md",
                "name":"report.md",
                "mimeType":"text/markdown",
                "sizeBytes":5
            }""".trimIndent(),
        )

        val artifacts = tool.workspaceArtifacts(fallbackWorkspaceId = "new-workspace")

        assertEquals(1, artifacts.size)
        assertEquals("original-workspace", artifacts.single().workspaceId)
        assertEquals("/workspace/report.md", artifacts.single().path)
        assertEquals(5L, artifacts.single().sizeBytes)
    }

    @Test
    fun `persisted artifact keeps scope and maps private guest directories`() {
        val tool = tool(
            name = "workspace_publish_files",
            input = """{"paths":["/root/report.md"]}""",
            output = """{"artifacts":[{
                "type":"workspace_artifact",
                "workspaceId":"workspace",
                "scopeId":"assistant-a",
                "path":"/root/report.md",
                "name":"report.md"
            }]}""".trimIndent(),
        )

        val artifact = tool.workspaceArtifacts().single()

        assertEquals("assistant-a", artifact.scopeId)
        assertEquals(WorkspaceStorageArea.HOME, artifact.location().area)
        assertEquals("report.md", artifact.location().relativePath)
    }

    @Test
    fun `legacy write artifact falls back to current workspace`() {
        val tool = tool(
            name = "workspace_write_file",
            input = """{"path":"/workspace/legacy.txt","text":"hello"}""",
            output = """{"path":"/workspace/legacy.txt","name":"legacy.txt","sizeBytes":5}""",
        )

        val artifact = tool.workspaceArtifacts("fallback-workspace").single()

        assertEquals("fallback-workspace", artifact.workspaceId)
        assertEquals("/workspace/legacy.txt", artifact.path)
        assertEquals(null, artifact.scopeId)
        assertEquals(WorkspaceStorageArea.FILES, artifact.location().area)
    }

    @Test
    fun `read file output is not treated as generated artifact`() {
        val tool = tool(
            name = "workspace_read_file",
            input = """{"path":"/workspace/input.txt"}""",
            output = """{"path":"/workspace/input.txt","text":"input"}""",
        )

        assertTrue(tool.workspaceArtifacts("workspace").isEmpty())
    }

    @Test
    fun `publish tool returns multiple artifacts`() {
        val tool = tool(
            name = "workspace_publish_files",
            input = """{"paths":["/workspace/a.pdf","/workspace/b.png"]}""",
            output = """{"artifacts":[
                {"type":"workspace_artifact","workspaceId":"workspace","path":"/workspace/a.pdf","name":"a.pdf"},
                {"type":"workspace_artifact","workspaceId":"workspace","path":"/workspace/b.png","name":"b.png"}
            ]}""",
        )

        val artifacts = tool.workspaceArtifacts()

        assertEquals(listOf("a.pdf", "b.png"), artifacts.map { it.name })
    }

    private fun tool(name: String, input: String, output: String) = UIMessagePart.Tool(
        toolCallId = "call",
        toolName = name,
        input = input,
        output = listOf(UIMessagePart.Text(output)),
    )
}
