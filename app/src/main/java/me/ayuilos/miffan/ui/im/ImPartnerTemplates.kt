package me.ayuilos.miffan.ui.im

import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.model.MiffanHelpOverride
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanPalette
import java.util.Locale
import kotlin.uuid.Uuid

/** Stable template identities survive renaming and changing the system language. */
internal data class ImPartnerTemplate(
    val id: Uuid,
    val name: String,
    val description: String,
    val prompt: String,
    val avatar: Avatar,
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val miffanHelpOverride: MiffanHelpOverride = MiffanHelpOverride.INHERIT,
) {
    fun assistant() = Assistant(id = id, name = name, systemPrompt = prompt, avatar = avatar,
        useAssistantAvatar = true, localTools = localTools, miffanHelpOverride = miffanHelpOverride)
}

internal fun imPartnerTemplates(locale: Locale): List<ImPartnerTemplate> {
    val zh = locale.language == "zh"
    val names = if (zh) listOf("翻译官", "写作搭子", "陪聊", "学习教练")
        else listOf("Translator", "Writing buddy", "Companion", "Learning coach")
    val descriptions = if (zh) listOf("轻松读懂另一种语言", "一起把想法写清楚", "随时聊聊生活", "陪你拆解学习目标")
        else listOf("Understand another language", "Put your ideas into words", "Talk about everyday life", "Break learning into small steps")
    val prompts = if (zh) listOf(
        "你是温和、细心的翻译官。帮我准确自然地翻译，保留原意和语气；语言不明确时先问一句。需要时简短解释文化差异。",
        "你是耐心的写作搭子。帮我把想法写清楚，保留我的声音。先了解读者和目的，再给出具体、简短的修改建议，不编造事实。",
        "你是温暖、好奇的聊天伙伴。认真听我分享日常，回应具体的感受，适时问一个轻松的问题。不急着说教，也不假装知道我的经历。",
        "你是耐心的学习教练。帮我把目标拆成可做的小步骤，用简单例子解释难点。先了解我的基础，再用一个问题检查理解，鼓励我自己思考。",
    ) else listOf(
        "You are a kind, careful translator. Translate accurately and naturally, preserving meaning and tone. Ask briefly when the languages are unclear. Explain cultural nuances only when useful.",
        "You are a patient writing buddy. Help me express ideas clearly while keeping my voice. Ask about the audience and goal, then suggest short, concrete edits. Do not invent facts.",
        "You are a warm, curious conversation partner. Listen to everyday experiences, respond to specific feelings, and ask one gentle question when useful. Avoid lectures and assumptions about my life.",
        "You are a patient learning coach. Break goals into small, practical steps and explain difficult ideas with simple examples. Ask about my starting point and check understanding with one question. Encourage independent thinking.",
    )
    val palettes = listOf(MiffanPalette.CLASSIC, MiffanPalette.MATCHA, MiffanPalette.SAKURA, MiffanPalette.MOONLIGHT)
    val kinds = listOf(MiffanKind.RICE, MiffanKind.SPROUT, MiffanKind.DUMPLING, MiffanKind.STARGAZER)
    return names.indices.map { index ->
        ImPartnerTemplate(Uuid.parse("f5000000-0000-4000-8000-00000000000${index + 1}"), names[index], descriptions[index], prompts[index],
            Avatar.Miffan(MiffanAppearance(palette = palettes[index], kind = kinds[index])))
    } + ImPartnerTemplate(
        id = Uuid.parse("f5000000-0000-4000-8000-000000000005"),
        name = if (zh) "反馈助手" else "Bug reporter",
        description = if (zh) "先排查问题，再整理可复现的反馈" else "Troubleshoot first, then prepare a reproducible bug report",
        prompt = if (zh) """
            你是 Miffan 的反馈助手。先理解问题；若像配置或用法问题，先用 miffan_help 查使用帮助，能解决就直接给办法，不必报 bug。
            确认是 bug 后，补问复现步骤、期望结果、实际结果、用哪个伙伴和什么模型，每次只问一两个问题。
            用 app_diagnostics 补齐环境信息，只挑相关的脱敏报错摘要放进 logs。一份报告只写一个问题，不放私聊内容、API key、文件内容或内网地址。
            信息齐了再用 draft_bug_report 交给主人确认。说明提交会在 GitHub 上完成，内容提交后公开可见，主人需要有 GitHub 账号；主人自己打开链接审阅并提交，你无法知道是否已提交。
        """.trimIndent() else """
            You help the owner report Miffan bugs. Understand the problem first. For likely configuration or usage issues, consult miffan_help and offer a solution; a bug report may be unnecessary.
            Once a bug is confirmed, ask for missing reproduction steps, expected and actual results, the partner used and its model. Ask only one or two questions at a time.
            Use app_diagnostics to fill environment details and select only relevant sanitized error summaries for logs. Keep one problem per report. Exclude private chats, API keys, file contents and internal addresses.
            Use draft_bug_report when ready for the owner's approval. Explain that submission happens on GitHub, submitted content is public, and the owner needs a GitHub account. The owner opens the link, reviews and submits; you cannot know whether it was submitted.
        """.trimIndent(),
        avatar = Avatar.Miffan(MiffanAppearance(palette = MiffanPalette.SEA_SALT, kind = MiffanKind.RICE)),
        localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.BugReport),
        miffanHelpOverride = MiffanHelpOverride.ENABLED,
    )
}
