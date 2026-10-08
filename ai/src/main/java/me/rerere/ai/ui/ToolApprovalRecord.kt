package me.rerere.ai.ui

import kotlinx.serialization.Serializable

@Serializable
data class ToolApprovalRecord(
    val requestedAt: Long? = null,
    val decidedAt: Long? = null,
    val decision: ToolDecision? = null,
    val via: ToolDecisionVia? = null,
)

@Serializable
enum class ToolDecision { ALLOWED, DECLINED, ANSWERED, REPLIED_IN_CHAT, CANCELLED, AUTO_ALLOWED }

@Serializable
enum class ToolDecisionVia { CARD, PARTNER_SCREEN, ALWAYS_ALLOW, STANDING_ALWAYS_ALLOW, NO_ASK_SETTING, CHAT_REPLY, STOP }

/** First decision wins, including when a settled call is interrupted before execution finishes. */
fun UIMessagePart.Tool.recordDecision(decision: ToolDecision, via: ToolDecisionVia, at: Long): UIMessagePart.Tool =
    if (approvalRecord?.decision != null) this else copy(
        approvalRecord = (approvalRecord ?: ToolApprovalRecord()).copy(decidedAt = at, decision = decision, via = via),
    )
