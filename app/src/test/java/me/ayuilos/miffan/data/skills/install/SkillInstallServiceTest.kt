package me.ayuilos.miffan.data.skills.install

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SkillInstallServiceTest {
    @Test
    fun `preview exposes metadata not skill instructions and apply is one use`() = runBlocking {
        val secret = "REMOTE-INSTRUCTION-MUST-NOT-LEAK"
        val target = FakeTarget()
        val service = service(
            target = target,
            skillFile = skillMarkdown(
                description = "private remote description",
                allowedTools = "Read Bash(git:*)",
                body = secret,
            ),
            extraFiles = listOf(file("scripts/check.sh", "echo safe")),
        )

        val preview = service.previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        val serialized = Json.encodeToString(preview)

        assertEquals("safe-skill", preview.skill.name)
        assertEquals(2, preview.skill.declaredToolCount)
        assertTrue(preview.skill.allowedToolsAreDeclarationsOnly)
        assertEquals(COMMIT, preview.revision)
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("private remote description"))
        assertFalse(serialized.contains("scripts/check.sh"))
        assertFalse(serialized.contains("Bash(git:*)"))
        assertTrue(SkillInstallRiskCategory.SCRIPT_LIKE_FILES_PRESENT in preview.riskCategories)
        assertEquals(preview.summaries, decodeSkillInstallPreviewSummaries(preview.previewId))

        val applied = service.apply(preview.previewId)
        val replayed = service.apply(preview.previewId)

        assertTrue(applied.applied)
        assertEquals("safe-skill", applied.skillName)
        assertArrayEquals(
            skillMarkdown(
                description = "private remote description",
                allowedTools = "Read Bash(git:*)",
                body = secret,
            ).toByteArray(),
            target.installedFiles.getValue("SKILL.md"),
        )
        assertFalse(replayed.applied)
        assertEquals(SkillInstallErrorCode.PREVIEW_NOT_FOUND, replayed.errorCode)
    }

    @Test
    fun `workspace preview pins destination`() = runBlocking {
        val workspaceTarget = FakeTarget()
        val service = SkillInstallService(
            sourceClient = fakeSource(),
            workspaceTargetResolver = WorkspaceSkillInstallTargetResolver { workspaceId ->
                if (workspaceId != "workspace-1") return@WorkspaceSkillInstallTargetResolver null
                ResolvedWorkspaceSkillInstallTarget(
                    workspaceId = workspaceId,
                    workspaceName = "Android Lab",
                    identity = "$workspaceId:root-1",
                    target = workspaceTarget,
                )
            },
        )

        val preview = service.previewInWorkspace(REQUEST_URL, "workspace-1")
        val applied = service.apply(preview.previewId)

        assertEquals(SkillInstallScope.WORKSPACE, preview.destination.scope)
        assertEquals("workspace-1", preview.destination.workspaceId)
        assertEquals("Android Lab", preview.destination.workspaceName)
        assertEquals("/workspace/.miffan/skills", preview.destination.path)
        assertTrue(preview.summaries.any { it.contains("workspace 'Android Lab' (workspace-1)") })
        assertTrue(applied.applied)
        assertEquals(preview.destination, applied.destination)
        assertEquals("safe-skill", workspaceTarget.installedName)
    }

    @Test
    fun `workspace identity is revalidated after preview`() = runBlocking {
        val workspaceTarget = FakeTarget()
        var identity = "workspace-1:root-1"
        val service = SkillInstallService(
            sourceClient = fakeSource(),
            workspaceTargetResolver = WorkspaceSkillInstallTargetResolver { workspaceId ->
                ResolvedWorkspaceSkillInstallTarget(
                    workspaceId = workspaceId,
                    workspaceName = "Android Lab",
                    identity = identity,
                    target = workspaceTarget,
                )
            },
        )
        val preview = service.previewInWorkspace(REQUEST_URL, "workspace-1")
        identity = "workspace-1:root-2"

        val result = service.apply(preview.previewId)

        assertFalse(result.applied)
        assertEquals(SkillInstallErrorCode.TARGET_CHANGED, result.errorCode)
        assertTrue(workspaceTarget.installedFiles.isEmpty())
    }

    @Test
    fun `workspace install requires an available bound workspace`() {
        val service = SkillInstallService(
            sourceClient = fakeSource(),
            workspaceTargetResolver = WorkspaceSkillInstallTargetResolver { null },
        )

        expectError(SkillInstallErrorCode.WORKSPACE_REQUIRED) {
            service.previewInWorkspace(REQUEST_URL, null)
        }
        expectError(SkillInstallErrorCode.TARGET_NOT_FOUND) {
            service.previewInWorkspace(REQUEST_URL, "missing")
        }
    }

    @Test
    fun `tampering with capability summaries cannot authorize install`() = runBlocking {
        val target = FakeTarget()
        val service = service(target)
        val preview = service.previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        val tampered = preview.previewId.replace("Install skill", "Silently install skill")

        assertNotEquals(preview.previewId, tampered)
        assertFalse(service.apply(tampered).applied)
        assertTrue(service.apply(preview.previewId).applied)
    }

    @Test
    fun `unsafe relative paths are rejected`() {
        listOf(
            "../escape.md",
            "nested/../../escape.md",
            "/absolute.md",
            "C:\\escape.md",
            "nested\\escape.md",
            "nested//empty.md",
            "nested/./dot.md",
        ).forEach { unsafePath ->
            expectError(SkillInstallErrorCode.UNSAFE_PATH) {
                service(extraFiles = listOf(file(unsafePath, "bad")))
                    .previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
            }
        }
    }

    @Test
    fun `symbolic links and non-file entries are rejected`() {
        listOf(
            RemoteSkillEntryKind.SYMBOLIC_LINK,
            RemoteSkillEntryKind.DIRECTORY,
            RemoteSkillEntryKind.OTHER,
        ).forEach { kind ->
            expectError(SkillInstallErrorCode.UNSUPPORTED_ENTRY) {
                service(
                    extraFiles = listOf(
                        RemoteSkillFile("unsafe", ByteArray(0), kind),
                    )
                ).previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
            }
        }
    }

    @Test
    fun `file count per-file and package size limits are enforced`() {
        val tooMany = (1..SkillInstallService.MAX_FILE_COUNT).map { index ->
            file("references/$index.md", "x")
        }
        expectError(SkillInstallErrorCode.TOO_MANY_FILES) {
            service(extraFiles = tooMany).previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        }
        expectError(SkillInstallErrorCode.FILE_TOO_LARGE) {
            service(
                extraFiles = listOf(
                    RemoteSkillFile("large.bin", ByteArray(SkillInstallService.MAX_FILE_BYTES + 1)),
                )
            ).previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        }
        expectError(SkillInstallErrorCode.PACKAGE_TOO_LARGE) {
            service(
                extraFiles = (1..5).map { index ->
                    RemoteSkillFile("$index.bin", ByteArray(SkillInstallService.MAX_FILE_BYTES))
                }
            ).previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        }
    }

    @Test
    fun `frontmatter requires strict non-reserved name and safe tool declarations`() {
        listOf("Uppercase", "has_underscore", "has.dot", "extension-management").forEach { name ->
            val expected = SkillInstallErrorCode.INVALID_SKILL_NAME
            expectError(expected) {
                service(skillFile = skillMarkdown(name = name))
                    .previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
            }
        }
        expectError(SkillInstallErrorCode.INVALID_SKILL_FILE) {
            service(skillFile = skillMarkdown(allowedTools = "Read <script>"))
                .previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        }
        expectError(SkillInstallErrorCode.INVALID_SKILL_FILE) {
            service(skillFile = "No frontmatter").previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        }
    }

    @Test
    fun `existing skill is rejected both before and after preview`() = runBlocking {
        val existing = FakeTarget(existing = true)
        expectError(SkillInstallErrorCode.CONFLICT) {
            service(existing).previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        }

        val raced = FakeTarget()
        val service = service(raced)
        val preview = service.previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        raced.existing = true
        val result = service.apply(preview.previewId)

        assertFalse(result.applied)
        assertEquals(SkillInstallErrorCode.CONFLICT, result.errorCode)
        assertTrue(raced.installedFiles.isEmpty())
    }

    @Test
    fun `expired preview is consumed without writing`() = runBlocking {
        var now = 1_000L
        val target = FakeTarget()
        val service = SkillInstallService(
            sourceClient = fakeSource(),
            workspaceTargetResolver = resolver(target),
            currentTimeMillis = { now },
        )
        val preview = service.previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        now += 11 * 60 * 1000L

        val result = service.apply(preview.previewId)

        assertFalse(result.applied)
        assertEquals(SkillInstallErrorCode.PREVIEW_EXPIRED, result.errorCode)
        assertTrue(target.installedFiles.isEmpty())
    }

    @Test
    fun `source metadata must match request and immutable revision`() {
        expectError(SkillInstallErrorCode.INVALID_SOURCE) {
            service(
                source = source().copy(requestedUrl = "https://skills.sh/someone-else/repo/skill")
            ).previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        }
        expectError(SkillInstallErrorCode.INVALID_SOURCE) {
            service(source = source().copy(revision = "main"))
                .previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        }
        expectError(SkillInstallErrorCode.INVALID_SOURCE) {
            service(
                source = source().copy(canonicalUrl = "https://github.com/o/r?token=secret")
            ).previewInWorkspace(REQUEST_URL, WORKSPACE_ID)
        }
        expectError(SkillInstallErrorCode.INVALID_SOURCE) {
            service().previewInWorkspace("http://skills.sh/o/r/skill", WORKSPACE_ID)
        }
    }

    private fun service(
        target: FakeTarget = FakeTarget(),
        source: VerifiedSkillSource = source(),
        skillFile: String = skillMarkdown(),
        extraFiles: List<RemoteSkillFile> = emptyList(),
    ) = SkillInstallService(
        sourceClient = fakeSource(source, skillFile, extraFiles),
        workspaceTargetResolver = resolver(target),
    )

    private fun resolver(target: FakeTarget) = WorkspaceSkillInstallTargetResolver { workspaceId ->
        ResolvedWorkspaceSkillInstallTarget(
            workspaceId = workspaceId,
            workspaceName = "Test Workspace",
            identity = "$workspaceId:root",
            target = target,
        )
    }

    private fun fakeSource(
        source: VerifiedSkillSource = source(),
        skillFile: String = skillMarkdown(),
        extraFiles: List<RemoteSkillFile> = emptyList(),
    ) = RemoteSkillSourceClient {
        RemoteSkillPackage(
            source = source,
            files = listOf(file("SKILL.md", skillFile)) + extraFiles,
        )
    }

    private fun source() = VerifiedSkillSource(
        requestedUrl = REQUEST_URL,
        canonicalUrl = "https://github.com/example/skills/tree/$COMMIT/skills/safe-skill",
        provider = "github",
        revision = COMMIT,
    )

    private fun skillMarkdown(
        name: String = "safe-skill",
        description: String = "A useful remote skill",
        allowedTools: String? = null,
        body: String = "Follow these instructions.",
    ) = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: $description")
        if (allowedTools != null) appendLine("allowed-tools: $allowedTools")
        appendLine("---")
        append(body)
    }

    private fun file(path: String, content: String) =
        RemoteSkillFile(path, content.toByteArray())

    private fun expectError(
        expected: SkillInstallErrorCode,
        block: suspend () -> Unit,
    ) {
        try {
            runBlocking { block() }
            fail("Expected SkillInstallException with code $expected")
        } catch (error: SkillInstallException) {
            assertEquals(expected, error.code)
        }
    }

    private class FakeTarget(
        var existing: Boolean = false,
    ) : SkillInstallTarget {
        var installedName: String? = null
        var installedFiles: Map<String, ByteArray> = emptyMap()

        override fun exists(skillName: String) = existing

        override fun installNewAtomically(
            skillName: String,
            files: Map<String, ByteArray>,
        ): Boolean {
            if (existing) return false
            installedName = skillName
            installedFiles = files.mapValues { it.value.copyOf() }
            existing = true
            return true
        }
    }

    private companion object {
        const val REQUEST_URL = "https://skills.sh/example/skills/safe-skill"
        const val WORKSPACE_ID = "workspace-1"
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
