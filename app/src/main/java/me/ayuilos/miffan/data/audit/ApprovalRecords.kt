package me.ayuilos.miffan.data.audit

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.*

internal fun UIMessagePart.Tool.waitForApproval(at: Long): UIMessagePart.Tool = copy(
    approvalState = ToolApprovalState.Pending,
    approvalRecord = approvalRecord ?: ToolApprovalRecord(requestedAt = at),
)

internal fun UIMessagePart.Tool.recordAutomaticApproval(definition: Tool?, at: Long): UIMessagePart.Tool {
    if (approvalState !is ToolApprovalState.Auto || definition == null) return this
    // autoApprovedBy is provenance only; it must never grant permission or change the gate.
    return definition.autoApprovedBy(inputAsJson())?.let { recordDecision(ToolDecision.AUTO_ALLOWED, it, at) } ?: this
}

internal fun UIMessagePart.Tool.recordCardDecision(state: ToolApprovalState, via: ToolDecisionVia, at: Long): UIMessagePart.Tool {
    val updated = copy(approvalState = state)
    if (!isPending) return updated
    val decision = when (state) {
        ToolApprovalState.Approved -> ToolDecision.ALLOWED
        is ToolApprovalState.Denied -> ToolDecision.DECLINED
        is ToolApprovalState.Answered -> ToolDecision.ANSWERED
        else -> return updated
    }
    return updated.recordDecision(decision, via, at)
}
