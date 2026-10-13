package me.ayuilos.miffan.data.bugreport

import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.*
import org.junit.Assert.*
import org.junit.Test

class BugReportSuggestionTest {
    private val caller = Assistant(name = "Caller")
    private val reporter = Assistant(name = "自定义反馈伙伴", localTools = listOf(LocalToolOption.BugReport), systemPrompt = "private-prompt", learnedPreferences = "private-preferences")
    private val access = AgentAccess.forAssistant(caller, true)
    private val settings = Settings(assistants = listOf(caller, reporter)).withInterfaceMode(InterfaceMode.IM)

    @Test fun reporterPresenceInjectsOnlyNameAndIdAndRequiresOwnerConsent() {
        val hint = requireNotNull(bugReportSuggestionHint(access, settings))
        assertTrue(hint.contains("@自定义反馈伙伴")); assertTrue(hint.contains(reporter.id.toString()))
        assertFalse(hint.contains("private-prompt")); assertFalse(hint.contains("private-preferences"))
        assertFalse(hint.contains(reporter.chatModelId.toString()))
        assertTrue(hint.contains("只能先问")); assertTrue(hint.contains("主人明确同意后"))
        assertTrue(hint.contains("send_message")); assertTrue(hint.contains("报错原文"))
        assertTrue(hint.contains("你自己用的提供商和模型")); assertTrue(hint.contains("非主人回合始终逐条审批"))
        assertTrue(hint.contains("简短问一句"))
    }

    @Test fun absentDisabledReporterAndProfessionalModeNeverInject() {
        assertNull(bugReportSuggestionHint(access, settings.copy(assistants = listOf(caller))))
        assertNull(bugReportSuggestionHint(access, settings.copy(assistants = listOf(caller, reporter.copy(localTools = emptyList())))))
        assertNull(bugReportSuggestionHint(access, settings.withInterfaceMode(InterfaceMode.PROFESSIONAL)))
        assertNull(bugReportSuggestionHint(access, settings.copy(assistants = emptyList())))
    }

    @Test fun bugReportEnabledCallerNeverReceivesHintEvenWithAnotherReporter() {
        val enabled = caller.copy(localTools = listOf(LocalToolOption.BugReport))
        assertNull(bugReportSuggestionHint(access, settings.copy(assistants = listOf(enabled, reporter))))
        assertNull(bugReportSuggestionHint(AgentAccess.forAssistant(reporter, true), settings))
    }

    @Test fun partnerTurnsReceiveSuggestionButNoContactAuthorizationIsCreated() {
        val relay = access.copy(ownerInitiated = false, ownerThreadNonOwnerTurn = true)
        assertNotNull(bugReportSuggestionHint(relay, settings))
        assertTrue(settings.partnerContactGrants.isEmpty())
    }
}
