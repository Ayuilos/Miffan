package me.ayuilos.miffan.ui.im

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
) {
    fun assistant() = Assistant(id = id, name = name, systemPrompt = prompt, avatar = avatar, useAssistantAvatar = true)
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
    }
}
