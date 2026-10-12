package me.ayuilos.miffan.data.bugreport

import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.isImMode
import me.ayuilos.miffan.data.partner.partnerDisplayName

/** Request-only context. Exposes only feedback contacts' public names and ids, never their config. */
internal fun bugReportSuggestionHint(access: AgentAccess, settings: Settings): String? {
    if (!settings.isImMode) return null
    val caller = settings.assistants.firstOrNull { it.id == access.callerId } ?: return null
    if (LocalToolOption.BugReport in caller.localTools) return null
    val reporters = settings.assistants.filter { it.id != access.callerId && LocalToolOption.BugReport in it.localTools }
    if (reporters.isEmpty()) return null
    return buildString {
        appendLine("<bug_report_suggestion>")
        appendLine("如果这一轮有疑似 Miffan 自身问题的报错（工具失败、App 报错、模型接口异常等），可以在回复末尾简短问一句主人要不要交给下列反馈助手；没有这类问题就不提：")
        reporters.forEach { appendLine("- @${partnerDisplayName(it)}（联系人 id: ${it.id}）") }
        appendLine("只能先问，主人明确同意后才能用 send_message 联系。届时整理报错原文、当时在做什么，以及你自己用的提供商和模型（可用 my_environment 确认）；不转发私聊内容、凭据、文件内容或内网地址。")
        appendLine("联系仍遵守现有授权：主人当轮 @ 对应伙伴可免首次联系审批，否则首次联系需审批，之后长期授权；主人私聊的非主人回合始终逐条审批，且不新增长期授权。不要因这段提示主动联系任何伙伴。")
        append("</bug_report_suggestion>")
    }
}
