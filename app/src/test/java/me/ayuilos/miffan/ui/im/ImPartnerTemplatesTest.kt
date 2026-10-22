package me.ayuilos.miffan.ui.im

import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.MiffanHelpOverride
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ImPartnerTemplatesTest {
    @Test fun templatesKeepStableIdentityAndDefaultAbilitiesAcrossLanguages() {
        val chinese = imPartnerTemplates(Locale.SIMPLIFIED_CHINESE)
        val english = imPartnerTemplates(Locale.ENGLISH)
        assertEquals(5, chinese.size)
        val variants = listOf(chinese, english, imPartnerTemplates(Locale.TRADITIONAL_CHINESE),
            imPartnerTemplates(Locale.forLanguageTag("zh-Hant-HK")),
            imPartnerTemplates(Locale.forLanguageTag("zh-Hans")), imPartnerTemplates(Locale.forLanguageTag("ja")))
        variants.forEach { templates ->
            assertEquals(chinese.map { it.id }, templates.map { it.id })
            assertEquals(chinese.map { it.avatar }, templates.map { it.avatar })
            templates.dropLast(1).forEach {
                assertEquals(listOf(LocalToolOption.TimeInfo), it.assistant().localTools)
                assertEquals(MiffanHelpOverride.INHERIT, it.assistant().miffanHelpOverride)
            }
        }
        assertEquals("反馈助手", chinese.last().name); assertEquals("Bug reporter", english.last().name)
        for (template in variants.map { it.last() }) {
            val assistant = template.assistant()
            assertEquals(setOf(LocalToolOption.TimeInfo, LocalToolOption.BugReport), assistant.localTools.toSet())
            assertEquals(MiffanHelpOverride.ENABLED, assistant.miffanHelpOverride)
            assertTrue(assistant.useAssistantAvatar)
            assertTrue(template.description.isNotBlank())
            for (instruction in listOf("miffan_help", "app_diagnostics", "draft_bug_report", "logs", "GitHub")) {
                assertTrue(template.prompt.contains(instruction))
            }
        }
    }
    @Test fun traditionalChineseUsesLocalizedTextForEveryTemplate() {
        val traditional = imPartnerTemplates(Locale.TRADITIONAL_CHINESE)
        val simplified = imPartnerTemplates(Locale.SIMPLIFIED_CHINESE)
        assertEquals(listOf("翻譯員", "寫作夥伴", "聊聊日常", "學習教練", "反饋助手"), traditional.map { it.name })
        traditional.zip(simplified).forEach { (hant, hans) ->
            assertTrue(hant.description.isNotBlank()); assertNotEquals(hans.description, hant.description)
            assertTrue(hant.prompt.isNotBlank()); assertNotEquals(hans.prompt, hant.prompt)
        }
        for (tag in listOf("zh-Hant-HK", "zh-Hant-CN", "zh-HK", "zh-MO")) {
            assertEquals(traditional, imPartnerTemplates(Locale.forLanguageTag(tag)))
        }
        for (tag in listOf("zh-Hans", "zh-Hans-TW", "zh-CN", "zh")) {
            assertEquals(simplified, imPartnerTemplates(Locale.forLanguageTag(tag)))
        }
        assertEquals(imPartnerTemplates(Locale.ENGLISH), imPartnerTemplates(Locale.forLanguageTag("ja")))
        assertEquals(imPartnerTemplates(Locale.ENGLISH), imPartnerTemplates(Locale.forLanguageTag("en-Hant-TW")))
    }

    @Test fun taglineIsTheTemplateBlurbOrThePersonalitysFirstRealSentence() {
        val templates = imPartnerTemplates(Locale.SIMPLIFIED_CHINESE)
        assertEquals(templates.first().description, partnerTagline(templates.first().assistant(), templates))
        // An edited template personality no longer matches the blurb.
        assertEquals("温柔的翻译", partnerTagline(templates.first().assistant().copy(systemPrompt = "你是温柔的翻译。只说中文。"), templates))
        assertEquals("你的性格：温柔耐心、幽默",
            partnerTagline(Assistant(name = "小橘", systemPrompt = "你是小橘。你的性格：温柔耐心、幽默。\n回答尽量简短。"), templates))
        assertEquals("Patient coach", partnerTagline(Assistant(systemPrompt = "You are a patient coach. Ask one question."), templates))
        assertEquals(null, partnerTagline(Assistant(name = "Momo", systemPrompt = "You are Momo."), templates))
        assertEquals(null, partnerTagline(Assistant(systemPrompt = "  "), templates))
    }
}
