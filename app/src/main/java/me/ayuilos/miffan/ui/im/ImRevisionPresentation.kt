package me.ayuilos.miffan.ui.im

import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.data.revision.*
import me.ayuilos.miffan.utils.textLines

/** [label] is a whole sentence, or a format taking [detail] (or the string [detailRes]) as its argument. */
internal data class ImRevisionChange(val label: Int, val detail: String? = null, val detailRes: Int? = null)
internal data class ImDiffLine(val text: String, val added: Boolean)

internal fun imRevisionChanges(revision: Revision, parent: Revision?): List<ImRevisionChange> {
    return when (revision.subject) {
        RevisionSubject.ASSISTANT -> {
            val current = AssistantRevisionRecorder.restore(revision.snapshot)
            val previous = parent?.let { AssistantRevisionRecorder.restore(it.snapshot) }
            if (previous == null) return listOf(ImRevisionChange(R.string.im_p5_origin_baseline, current.name))
            buildList {
                if (current.name != previous.name) add(ImRevisionChange(R.string.im_p5_name))
                if (current.avatar != previous.avatar) add(ImRevisionChange(R.string.im_p5_profile))
                if (current.systemPrompt != previous.systemPrompt) add(ImRevisionChange(R.string.im_p5_personality))
                if (current.learnedPreferences != previous.learnedPreferences) add(ImRevisionChange(R.string.im_p5_preferences))
                if (current.enableWebSearch != previous.enableWebSearch) add(ImRevisionChange(R.string.im_p5_web))
                if (current.enableMemory != previous.enableMemory || current.useGlobalMemory != previous.useGlobalMemory) add(ImRevisionChange(R.string.im_p5_remember))
                if (current.chatModelId != previous.chatModelId) add(ImRevisionChange(R.string.im_p5_model))
                if (current.reasoningLevel != previous.reasoningLevel) add(ImRevisionChange(R.string.im_think_title))
                if (current.maxTokens != previous.maxTokens || current.contextMessageLimit != previous.contextMessageLimit ||
                    current.temperature != previous.temperature) add(ImRevisionChange(R.string.im_revision_reply_settings))
                addAll(permissionChanges(previous, current))
                if (current.copy(name = previous.name, avatar = previous.avatar, systemPrompt = previous.systemPrompt,
                        learnedPreferences = previous.learnedPreferences, enableWebSearch = previous.enableWebSearch,
                        enableMemory = previous.enableMemory, useGlobalMemory = previous.useGlobalMemory,
                        chatModelId = previous.chatModelId, reasoningLevel = previous.reasoningLevel, maxTokens = previous.maxTokens,
                        contextMessageLimit = previous.contextMessageLimit, temperature = previous.temperature,
                        workspaceId = previous.workspaceId, workspaceScopeId = previous.workspaceScopeId,
                        workspaceShellEnabled = previous.workspaceShellEnabled,
                        workspaceShellApprovalRequired = previous.workspaceShellApprovalRequired,
                        workspacePermissionRevision = previous.workspacePermissionRevision,
                        workspaceShellApprovalTarget = previous.workspaceShellApprovalTarget,
                        workspaceShellApprovalVia = previous.workspaceShellApprovalVia,
                        computerUse = previous.computerUse) != previous) add(ImRevisionChange(R.string.im_p5_other_settings))
            }
        }
        RevisionSubject.MEMORY -> {
            val current = MemoryRepository.restore(revision.snapshot).associateBy { it.id }
            val previous = parent?.let { MemoryRepository.restore(it.snapshot).associateBy { memory -> memory.id } }.orEmpty()
            buildList {
                current.values.forEach { memory ->
                    if (memory.id !in previous) add(ImRevisionChange(R.string.im_p5_memory_added, memory.content))
                    else if (previous[memory.id]?.content != memory.content) add(ImRevisionChange(R.string.im_p5_memory_changed, memory.content))
                }
                previous.values.filter { it.id !in current }.forEach { add(ImRevisionChange(R.string.im_p5_memory_removed, it.content)) }
            }
        }
        RevisionSubject.AI_SERVICES -> listOf(ImRevisionChange(R.string.im_revision_ai_services))
    }
}

/** Permission changes are named one by one: the settings history is where they are audited. */
private fun permissionChanges(previous: Assistant, current: Assistant): List<ImRevisionChange> = buildList {
    if (current.workspaceId != previous.workspaceId) {
        add(ImRevisionChange(if (current.workspaceId == null) R.string.im_revision_workspace_removed else R.string.im_revision_workspace_bound))
    }
    if (current.computerUse != previous.computerUse) add(ImRevisionChange(R.string.im_revision_computer_use, detailRes = when (current.computerUse) {
        ComputerUseMode.ASK -> R.string.computer_use_mode_ask
        ComputerUseMode.AUTO -> R.string.computer_use_mode_auto
        ComputerUseMode.OFF -> R.string.computer_use_mode_off
    }))
    if (current.workspaceShellEnabled != previous.workspaceShellEnabled) add(ImRevisionChange(R.string.im_revision_shell,
        detailRes = if (current.workspaceShellEnabled) R.string.im_revision_allowed else R.string.im_revision_not_allowed))
    if (current.workspaceShellApprovalRequired != previous.workspaceShellApprovalRequired) add(ImRevisionChange(R.string.im_revision_shell_ask,
        detailRes = if (current.workspaceShellApprovalRequired) R.string.im_revision_on else R.string.im_revision_off))
    if (current.workspaceShellApprovalTarget != previous.workspaceShellApprovalTarget) add(ImRevisionChange(
        if (current.workspaceShellApprovalTarget != null) R.string.im_revision_shell_always else R.string.im_revision_shell_always_removed))
}

/** Compare original prompt/preference lines and individual memory entries, retaining repeated lines. */
internal fun imRevisionDiff(revision: Revision, parent: Revision?): List<ImDiffLine> {
    return when (revision.subject) {
        RevisionSubject.ASSISTANT -> {
            val before = parent?.let { AssistantRevisionRecorder.restore(it.snapshot) }
            val after = AssistantRevisionRecorder.restore(revision.snapshot)
            imCompareLines(before?.systemPrompt.textLines(), after.systemPrompt.textLines()) +
                imCompareLines(before?.learnedPreferences.textLines(), after.learnedPreferences.textLines())
        }
        RevisionSubject.MEMORY -> imCompareLines(
            parent?.let { MemoryRepository.restore(it.snapshot).map { memory -> "#${memory.id} ${memory.content}" } }.orEmpty(),
            MemoryRepository.restore(revision.snapshot).map { memory -> "#${memory.id} ${memory.content}" },
        )
        RevisionSubject.AI_SERVICES -> emptyList()
    }
}

private fun imCompareLines(before: List<String>, after: List<String>): List<ImDiffLine> =
    me.ayuilos.miffan.utils.diffLines(before, after).map { (text, added) -> ImDiffLine(text, added) }
