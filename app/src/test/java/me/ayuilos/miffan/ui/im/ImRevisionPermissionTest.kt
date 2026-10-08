package me.ayuilos.miffan.ui.im

import java.time.Instant
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.model.withWorkspaceBinding
import me.ayuilos.miffan.data.revision.AssistantRevisionRecorder
import me.ayuilos.miffan.data.revision.Revision
import me.ayuilos.miffan.data.revision.RevisionAuthor
import me.ayuilos.miffan.data.revision.RevisionSubject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ImRevisionPermissionTest {
    private val base = Assistant()

    private fun revision(assistant: Assistant) = Revision(
        id = Uuid.random().toString(), subject = RevisionSubject.ASSISTANT, subjectId = assistant.id.toString(),
        parentId = null, author = RevisionAuthor.USER, summary = "", snapshot = AssistantRevisionRecorder.snapshot(assistant),
        trigger = null, toolCallId = null, revertOf = null, createdAt = Instant.EPOCH,
    )

    private fun changes(before: Assistant, after: Assistant) = imRevisionChanges(revision(after), revision(before))

    @Test
    fun permissionChangesAreNamedInsteadOfOtherSettings() {
        assertEquals(listOf(ImRevisionChange(R.string.im_revision_computer_use, detailRes = R.string.computer_use_mode_auto)),
            changes(base, base.copy(computerUse = ComputerUseMode.AUTO)))
        // Binding also resets the scope and permission revision; only the binding is reported.
        assertEquals(listOf(ImRevisionChange(R.string.im_revision_workspace_bound)),
            changes(base, base.withWorkspaceBinding(Uuid.random())))
        val bound = base.withWorkspaceBinding(Uuid.random())
        assertEquals(listOf(ImRevisionChange(R.string.im_revision_workspace_removed)),
            changes(bound, bound.withWorkspaceBinding(null)))
        assertEquals(listOf(ImRevisionChange(R.string.im_revision_shell_ask, detailRes = R.string.im_revision_off)),
            changes(base, base.copy(workspaceShellApprovalRequired = false)))
    }

    @Test
    fun unrelatedChangesStillCountAsOtherSettings() {
        assertEquals(listOf(ImRevisionChange(R.string.im_p5_other_settings)), changes(base, base.copy(temperature = 0.3f)))
    }
}
