package me.ayuilos.miffan.ui.im

import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.model.MiffanHelpOverride
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ImPartnerTemplatesTest {
    @Test fun reporterTemplateHasStableIdentityAndDefaultAbilitiesInBothLanguages() {
        val chinese = imPartnerTemplates(Locale.SIMPLIFIED_CHINESE)
        val english = imPartnerTemplates(Locale.ENGLISH)
        assertEquals(5, chinese.size); assertEquals(chinese.map { it.id }, english.map { it.id })
        assertEquals("反馈助手", chinese.last().name); assertEquals("Bug reporter", english.last().name)
        for (template in listOf(chinese.last(), english.last())) {
            val assistant = template.assistant()
            assertEquals(setOf(LocalToolOption.TimeInfo, LocalToolOption.BugReport), assistant.localTools.toSet())
            assertEquals(MiffanHelpOverride.ENABLED, assistant.miffanHelpOverride)
            assertTrue(assistant.useAssistantAvatar)
            assertTrue(template.description.isNotBlank())
            for (instruction in listOf("miffan_help", "app_diagnostics", "draft_bug_report", "logs", "GitHub")) {
                assertTrue(template.prompt.contains(instruction))
            }
        }
        chinese.dropLast(1).forEach {
            assertEquals(listOf(LocalToolOption.TimeInfo), it.assistant().localTools)
            assertEquals(MiffanHelpOverride.INHERIT, it.assistant().miffanHelpOverride)
        }
    }
}
