package me.ayuilos.miffan.ui.im

import me.ayuilos.miffan.data.ai.tools.local.DEFAULT_LOCAL_TOOLS
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
    val localTools: List<LocalToolOption> = DEFAULT_LOCAL_TOOLS,
    val miffanHelpOverride: MiffanHelpOverride = MiffanHelpOverride.INHERIT,
) {
    fun assistant() = Assistant(id = id, name = name, systemPrompt = prompt, avatar = avatar,
        useAssistantAvatar = true, localTools = localTools, miffanHelpOverride = miffanHelpOverride)
}

internal fun imPartnerTemplates(locale: Locale): List<ImPartnerTemplate> {
    val zh = locale.language == "zh"
    val hant = zh && (locale.script == "Hant" ||
        (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO")))
    val names = if (hant) listOf("翻譯員", "寫作夥伴", "聊聊日常", "學習教練")
        else if (zh) listOf("翻译官", "写作搭子", "陪聊", "学习教练")
        else listOf("Translator", "Writing buddy", "Companion", "Learning coach")
    val descriptions = if (hant) listOf("輕鬆理解不同語言", "一起把想法寫清楚", "隨時聊聊生活大小事", "陪你一步步達成學習目標")
        else if (zh) listOf("轻松读懂另一种语言", "一起把想法写清楚", "随时聊聊生活", "陪你拆解学习目标")
        else listOf("Understand another language", "Put your ideas into words", "Talk about everyday life", "Break learning into small steps")
    val prompts = if (hant) listOf(
        "你是溫和、細心的翻譯員。幫我翻得準確又自然，保留原意與語氣；不確定要翻成哪種語言時，先簡短詢問。有需要時，簡單說明文化上的差異。",
        "你是有耐心的寫作夥伴。幫我把想法表達清楚，保留我的風格。先了解讀者是誰、寫作目的是什麼，再提供具體、簡短的修改建議，不捏造事實。",
        "你是溫暖、樂於傾聽的聊天夥伴。認真聽我分享生活，回應我當下的感受，適時問一個輕鬆的問題。不急著說教，也不擅自揣測我的經歷。",
        "你是有耐心的學習教練。陪我把目標拆成做得到的小步驟，用簡單的例子說明難懂的觀念。先了解我的程度，再用一個問題確認我是否理解，鼓勵我自己思考。",
    ) else if (zh) listOf(
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
        name = if (hant) "反饋助手" else if (zh) "反馈助手" else "Bug reporter",
        description = if (hant) "先釐清問題，再整理能重現的錯誤回報" else if (zh) "先排查问题，再整理可复现的反馈" else "Troubleshoot first, then prepare a reproducible bug report",
        prompt = if (hant) """
            你是 Miffan 的反饋助手。先了解問題；如果可能是設定或使用方式的問題，先用 miffan_help 查閱使用說明，能解決就直接提供做法，不一定要回報錯誤。
            確認是程式錯誤後，再詢問重現步驟、預期結果、實際結果，以及使用哪位夥伴和哪個模型，每次只問一兩個問題。
            用 app_diagnostics 補齊環境資訊，只將相關且已去除敏感資訊的錯誤摘要放進 logs。一份回報只處理一個問題，不包含私人對話、API 金鑰、檔案內容或內部網路位址。
            資訊齊全後，再用 draft_bug_report 交給主人確認。說明回報要在 GitHub 上提交，提交後內容會公開，主人需要有 GitHub 帳號；由主人自行開啟連結、檢查並提交，你無法得知是否已提交。
        """.trimIndent() else if (zh) """
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
        localTools = DEFAULT_LOCAL_TOOLS + LocalToolOption.BugReport,
        miffanHelpOverride = MiffanHelpOverride.ENABLED,
    )
}

private val TAGLINE_PREFIXES = listOf("你是一位", "你是一个", "你是一個", "你是", "You are an ", "You are a ", "You are ", "You're ")

/**
 * One line about a partner for lists: its template's blurb while the personality is the template's own,
 * otherwise the personality's first sentence without the "You are" opener. Null when it has none.
 */
internal fun partnerTagline(assistant: Assistant, templates: List<ImPartnerTemplate>): String? {
    templates.firstOrNull { it.id == assistant.id && it.prompt == assistant.systemPrompt }?.let { return it.description }
    // "You are <name>." on its own says nothing about the partner, so the line is the next sentence.
    val line = assistant.systemPrompt.trim().split(Regex("(?<=[。！？!?])|(?<=\\.)\\s|\\n"))
        .map { sentence -> sentence.trim().let { text -> TAGLINE_PREFIXES.firstOrNull { text.startsWith(it) }?.let { text.removePrefix(it) } ?: text } }
        .firstOrNull { it.isNotBlank() && it.trimEnd('。', '.', '！', '!').trim() != assistant.name.trim() } ?: return null
    return line.trimEnd('。', '.', '！', '!').replaceFirstChar { it.uppercase() }.ifBlank { null }
}
