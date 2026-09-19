package me.ayuilos.miffan.ui.pages.extensions.workspace

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.repository.RemoteCheckRecord
import me.ayuilos.miffan.data.repository.RemoteConnectionActivity
import me.ayuilos.miffan.data.repository.RemoteHostRuntimeState
import me.ayuilos.miffan.data.repository.RemoteWorkspaceRuntimeState
import me.ayuilos.miffan.testutils.workspaceTestResources
import me.ayuilos.miffan.utils.workspaceErrorMessage
import me.rerere.workspace.SshKeyException
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLocalizationTest {
    private val locales = listOf("values", "values-zh", "values-zh-rTW", "values-ja", "values-ko-rKR", "values-ru")
    private val workspace = WorkspaceEntity(
        id = "project", name = "Project", root = "remote:project", createdAt = 1, updatedAt = 1,
        kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host", remotePath = "/srv/project",
        shellStatus = WorkspaceShellStatus.READY.name,
    )
    private val host = RemoteHostEntity(
        id = "host", name = "Server", host = "example.invalid", port = 22, username = "test",
        authType = "PASSWORD", trustedHostKeySha256 = "test", createdAt = 1, updatedAt = 1,
    )

    @Test fun workspaceResourcesCoverEverySupportedLanguageWithMatchingPlaceholders() {
        fun load(locale: String): Map<String, String> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/strings.xml")).getElementsByTagName("string")
            return (0 until nodes.length).map { nodes.item(it) }.associate {
                it.attributes.getNamedItem("name").nodeValue to it.textContent
            }.filterKeys { it.startsWith("workspace_") }
        }
        val source = load("values")
        val placeholders = Regex("%[0-9]+\\$[a-z]")
        for (locale in locales) {
            val target = load(locale)
            assertTrue("Missing workspace translations in $locale", target.keys.containsAll(source.keys))
            for ((key, text) in source) {
                assertTrue("Empty $locale/$key", target.getValue(key).isNotBlank())
                assertEquals("Format arguments in $locale/$key",
                    placeholders.findAll(text).map { it.value }.sorted().toList(),
                    placeholders.findAll(target.getValue(key)).map { it.value }.sorted().toList())
            }
        }
    }

    @Test fun compactConnectionStatusUsesStateInsteadOfTranslatedSubstringMatching() {
        for (locale in locales) {
            val resources = workspaceTestResources(locale)
            val checked = RemoteWorkspaceRuntimeState(lastDirectoryCheck = RemoteCheckRecord(1_000, true))
            assertEquals(resources.getString(R.string.workspace_on_demand_directory_passed),
                workspaceCardRemoteStatus(resources, host, workspace, null, checked))
            val failed = RemoteHostRuntimeState(lastConnection = RemoteCheckRecord(2_000, false))
            assertEquals(resources.getString(R.string.workspace_last_connection_failed),
                workspaceCardRemoteStatus(resources, host, workspace, failed, checked))
            val connecting = checked.copy(activity = RemoteConnectionActivity.CONNECTING)
            assertEquals(resources.getString(R.string.workspace_connecting_remote_server),
                workspaceCardRemoteStatus(resources, host, workspace, failed, connecting))
        }
    }

    @Test fun keyErrorsAreLocalizedWithoutExposingParserDetails() {
        for (locale in locales) {
            val resources = workspaceTestResources(locale)
            val error = SshKeyException(SshKeyException.Reason.IMPORT_FAILED)
            assertEquals(resources.getString(R.string.workspace_error_key_import), error.workspaceErrorMessage(resources))
        }
    }
}
