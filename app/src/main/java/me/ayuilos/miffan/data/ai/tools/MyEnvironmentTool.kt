package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.data.partner.partnerChatTitle
import me.ayuilos.miffan.data.partner.partnerDisplayName
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** Names only at this boundary: connection objects and paths must never enter the payload. */
fun createMyEnvironmentTool(
    access: AgentAccess,
    settings: Settings,
    model: Model,
    providerName: String,
    toolNames: () -> Set<String>,
    skillNames: List<String>,
    workspaceName: String?,
    computerName: String?,
    groups: suspend () -> List<PartnerChat>,
    turnContactIds: Set<Uuid> = emptySet(),
): Tool {
    require(settings.isImMode) { "my_environment is available only in IM mode" }
    return Tool(
        name = "my_environment",
        description = "Read your own identity and current app environment. Use this when the owner asks who you are, what you can do, which workspace or computer you use, or which groups you belong to. Returns your name, avatar type, model/provider display names, abilities available this turn, group member names, and authorized contact names. No prompts, other partners' configuration, paths, connection URLs or credentials.",
        parameters = { InputSchema.Obj(buildJsonObject {}) },
        execute = {
            listOf(UIMessagePart.Text(buildMyEnvironment(
                access, settings, model, providerName, toolNames(), skillNames,
                workspaceName, computerName, groups(), turnContactIds,
            ).toString()))
        },
    )
}

internal fun buildMyEnvironment(
    access: AgentAccess,
    settings: Settings,
    model: Model,
    providerName: String,
    toolNames: Set<String>,
    skillNames: List<String>,
    workspaceName: String?,
    computerName: String?,
    groups: List<PartnerChat>,
    turnContactIds: Set<Uuid> = emptySet(),
): JsonObject {
    val caller = requireNotNull(settings.getAssistantById(access.callerId))
    return buildJsonObject {
        put("name", partnerDisplayName(caller))
        val avatar = caller.avatar
        if (avatar is Avatar.Miffan) put("avatar", buildJsonObject {
            put("type", "miffan")
            put("kind", Json.encodeToJsonElement(avatar.appearance.kind))
            put("palette", Json.encodeToJsonElement(avatar.appearance.palette))
        }) else put("avatar", when (avatar) {
            is Avatar.Emoji -> "emoji"
            is Avatar.Image -> "image"
            is Avatar.WhaleGirl -> "whale_girl"
            is Avatar.Miffan, Avatar.Dummy -> "default"
        })
        put("model", buildJsonObject {
            put("name", model.displayName.ifBlank { model.modelId })
            put("provider", providerName)
        })
        put("abilities", buildJsonArray {
            if ("search_web" in toolNames || BuiltInTools.Search in model.tools) add("联网搜索")
            if (BuiltInTools.UrlContext in model.tools) add("网页内容读取")
            if (BuiltInTools.ImageGeneration in model.tools) add("图像生成")
            if (caller.enableMemory) {
                val scope = if (access.memoryOwnerId == me.ayuilos.miffan.data.repository.MemoryRepository.GLOBAL_MEMORY_ID)
                    "全局记忆" else "自己的记忆"
                add("记忆（$scope；" + if (access.ownerInitiated) "本回合可修改）" else "本回合只读）")
            }
            if ("recent_chats" in toolNames) add(if (access.ownerInitiated)
                "最近聊天参考（自己的主人私聊及参与的伙伴聊天）" else "最近聊天参考（仅参与的伙伴聊天）")
            localAbilityNames.forEach { (tool, label) -> if (tool in toolNames) add(label) }
            if (toolNames.any { it.startsWith("workspace_") }) add("工作空间文件操作")
            if ("workspace_shell" in toolNames) add("工作空间 shell")
            if (toolNames.any { it.startsWith("computer_") }) add("电脑操作")
            if ("extensions_catalog" in toolNames) add("扩展目录")
            if ("extensions_preview_changes" in toolNames) add("扩展管理（变更需主人审批）")
            if ("miffan_help" in toolNames) add("Miffan 使用帮助")
            if ("send_message" in toolNames) add("联系伙伴")
            if ("use_skill" in toolNames) skillNames.distinct().forEach { add("Skill：$it") }
            settings.mcpServers.filter { it.id in caller.mcpServers }.forEach {
                add("已绑定 MCP：${it.commonOptions.name}")
            }
        })
        workspaceName?.let { put("workspace", it) }
        computerName?.let { put("computer", it) }
        put("groups", buildJsonArray {
            groups.filter { it.kind == PartnerChatKind.GROUP && access.callerId in it.memberIds }.forEach { group ->
                add(buildJsonObject {
                    put("name", partnerChatTitle(group, settings))
                    put("members", buildJsonArray {
                        group.memberIds.forEach { id ->
                            add(partnerDisplayName(settings.getAssistantById(id)))
                        }
                    })
                })
            }
        })
        put("contacts_authorized", buildJsonArray {
            settings.assistants.filter { it.id != access.callerId &&
                (PartnerPair.of(access.callerId, it.id) in settings.partnerContactGrants || it.id in turnContactIds)
            }.forEach { add(partnerDisplayName(it)) }
        })
    }
}

private val localAbilityNames = linkedMapOf(
    "eval_javascript" to "执行 JavaScript",
    "get_time_info" to "查询时间",
    "clipboard_tool" to "读写剪贴板",
    "text_to_speech" to "朗读",
    "ask_user" to "向主人提问",
    "get_screen_time" to "查询屏幕使用时间",
    "calendar_query" to "查询日历",
    "calendar_create" to "创建日历事件",
    "app_diagnostics" to "App 诊断（脱敏报错摘要）",
    "draft_bug_report" to "反馈草稿（需主人审批）",
)
