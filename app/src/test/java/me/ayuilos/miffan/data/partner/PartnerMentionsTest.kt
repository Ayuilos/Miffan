package me.ayuilos.miffan.data.partner

import me.ayuilos.miffan.data.ai.transformers.PartnerMentionTransformer
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PartnerMentionsTest {
    private val a = Assistant(name = "小A")
    private val b = Assistant(name = "小B", systemPrompt = "B 的简介\n不应注入的第二行")
    private val c = Assistant(name = "小BB", systemPrompt = "长".repeat(180))
    private val settings = Settings(assistants = listOf(a, b, c)).withInterfaceMode(InterfaceMode.IM)
    private fun conversation() = Conversation(assistantId = a.id, messageNodes = emptyList())

    @Test fun longestNameWinsAndChineseSuffixDoesNotNeedWhitespace() {
        val text = "请@小BB帮我问问"
        assertEquals(listOf(PartnerMention(c.id, 1..4)),
            findPartnerMentions(text, listOf(b.name to b.id, c.name to c.id)))
        assertEquals(listOf(PartnerMention(b.id, 0..2)), findPartnerMentions("@小B帮我问问", listOf(b.name to b.id)))
    }

    @Test fun everyAtMatchesOnceInTextOrderIncludingRepeatedContacts() {
        assertEquals(listOf(PartnerMention(b.id, 0..2), PartnerMention(c.id, 4..7), PartnerMention(b.id, 9..11)),
            findPartnerMentions("@小B @小BB @小B", listOf(b.name to b.id, c.name to c.id)))
    }

    @Test fun blankNamesAndTextWithoutAtNeverMatch() {
        assertTrue(findPartnerMentions("@ @\t@小B", listOf("" to b.id, " " to c.id, "\t" to a.id)).isEmpty())
        assertTrue(findPartnerMentions("小B帮我问问", listOf(b.name to b.id)).isEmpty())
        assertTrue(findPartnerMentions("@小B", emptyList()).isEmpty())
    }

    @Test fun requestHintsCoverAllOwnerHistoryAndNeverChangeStoredMessages() {
        val original = conversation().appendMessage(UIMessage.user("@小B帮我问问"))
            .appendMessage(UIMessage.assistant("@小B助手输出"))
            .appendMessage(UIMessage.user("@小BB @小B @小B"))
        val stored = original.currentMessages.toList()
        val transformer = PartnerMentionTransformer(original, settings)
        val request = transformer.apply(stored)
        assertEquals(stored, original.currentMessages)
        assertEquals(stored[1], request[1])
        assertEquals(stored[0].parts, request[0].parts.dropLast(1))
        val hint = (request[0].parts.last() as UIMessagePart.Text).text
        assertTrue(hint.contains("小B（联系人 id: ${b.id}）"))
        assertFalse(hint.contains("B 的简介"))
        assertTrue(hint.contains("send_message")); assertTrue(hint.contains("list_contacts"))
        assertTrue(hint.contains("已经同意你联系谁")); assertFalse(hint.contains("第二行"))
        val latest = (request[2].parts.last() as UIMessagePart.Text).text
        assertFalse(latest.contains("长".repeat(160)))
        assertEquals(1, latest.lines().count { it.contains(b.id.toString()) })
    }

    @Test fun repeatedRequestCopiesAreIdenticalEvenAfterGrantChanges() {
        val original = conversation().appendMessage(UIMessage.user("@小B"))
        val first = PartnerMentionTransformer(original, settings).apply(original.currentMessages)
        val granted = settings.copy(partnerContactGrants = listOf(PartnerPair.of(a.id, b.id)))
        assertEquals(first, PartnerMentionTransformer(original, granted).apply(original.currentMessages))
        assertEquals(first, PartnerMentionTransformer(original, settings).apply(original.currentMessages))
    }

    @Test fun perspectivesProfessionalModeAndNonOwnerNodesAreSkipped() {
        val owner = conversation().appendMessage(UIMessage.user("@小B"))
        for (excluded in listOf(owner.copy(chatId = Uuid.random()),
            conversation().appendMessage(UIMessage.user("@小B"), origin = MessageOrigin.Event("test", "event")),
            conversation().appendMessage(UIMessage.user("@小B"), origin = MessageOrigin.Partner(b.id, Uuid.random())))) {
            assertEquals(excluded.currentMessages, PartnerMentionTransformer(excluded, settings).apply(excluded.currentMessages))
            assertTrue(excluded.ownerMentionedPartnerIds(settings).isEmpty())
        }
        val professional = settings.withInterfaceMode(InterfaceMode.PROFESSIONAL)
        assertEquals(owner.currentMessages, PartnerMentionTransformer(owner, professional).apply(owner.currentMessages))
        assertTrue(owner.ownerMentionedPartnerIds(professional).isEmpty())
    }

    @Test fun selfUnknownAndUnmentionedMessagesAreUnchangedIncludingSelfNamePrefixes() {
        val self = a.copy(name = "小BB")
        val config = settings.copy(assistants = listOf(self, b))
        for (text in listOf("@小BB", "@陌生人", "小B")) {
            val original = conversation().appendMessage(UIMessage.user(text))
            assertEquals(original.currentMessages, PartnerMentionTransformer(original, config).apply(original.currentMessages))
            assertTrue(original.ownerMentionedPartnerIds(config).isEmpty())
        }
    }

    @Test fun onlyCurrentOwnerTriggerAuthorizesWhileHistoryKeepsHints() {
        val history = conversation().appendMessage(UIMessage.user("@小B"))
            .appendMessage(UIMessage.assistant("ok"))
        val current = history.appendMessage(UIMessage.user("@小BB"))
        assertEquals(listOf(c.id), current.ownerMentionedPartnerIds(settings))
        val event = history.appendMessage(UIMessage.user("@小BB"), origin = MessageOrigin.Event("test", "event"))
        assertTrue(event.ownerMentionedPartnerIds(settings).isEmpty())
        assertEquals(2, PartnerMentionTransformer(event, settings).apply(event.currentMessages).first().parts.size)
        assertEquals(event.currentMessages.last(), PartnerMentionTransformer(event, settings).apply(event.currentMessages).last())
    }

    @Test fun blankNamesMatchTheDefaultAssistantStringInEveryLanguage() {
        val shown = java.io.File("src/main/res").listFiles { f -> f.name.startsWith("values") }.orEmpty().mapNotNull { dir ->
            java.io.File(dir, "strings.xml").takeIf { it.exists() }?.readText()
                ?.let { Regex("""<string name="assistant_page_default_assistant">([^<]*)</string>""").find(it)?.groupValues?.get(1) }
        }
        assertEquals(shown.toSet(), BLANK_PARTNER_NAMES.toSet())
    }

    @Test fun aBlankNamedPartnerIsMentionedByItsShownNameInAnyLanguage() {
        val blank = Assistant(name = "")
        val config = Settings(assistants = listOf(a, blank)).withInterfaceMode(InterfaceMode.IM)
        assertEquals(listOf(blank.id), mentionedOtherPartners("@默认助手 帮我问问", a.id, config))
        assertEquals(listOf(blank.id), mentionedOtherPartners("ask @Default Assistant", a.id, config))
        assertTrue(mentionedOtherPartners("@ 帮我问问", a.id, config).isEmpty())
    }
}
