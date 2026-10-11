package me.ayuilos.miffan.data.partner

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.tools.buildPartnerMessageTools
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.data.thread.withMigratedChatOrganization
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class PartnerChatTest {
    private val a = Assistant(name = "小A", systemPrompt = "A introduction\nmore")
    private val b = Assistant(name = "小B")
    private val c = Assistant(name = "小C")
    private fun caller(origin: MessageOrigin? = null, chatId: Uuid? = null) =
        Conversation(assistantId = a.id, chatId = chatId, messageNodes = emptyList())
            .appendMessage(UIMessage.user("trigger"), origin = origin)
    private fun JsonObject.status() = getValue(PartnerMessageTool.RESULT_STATUS).jsonPrimitive.content

    private inner class Fixture(scope: CoroutineScope, timeout: Long = 90_000) {
        val store = FakePartnerStore()
        val settings = MutableStateFlow(Settings(assistants = listOf(a, b, c)))
        val settingsStore = mockk<SettingsStore>()
        val segments = mutableMapOf<Uuid, Conversation>()
        val runtime = mockk<PartnerChatRuntime>()
        val generated = mutableListOf<Conversation>()
        val service: DefaultPartnerChatService
        init {
            every { settingsStore.settingsFlow } returns settings
            coEvery { settingsStore.update(any<(Settings) -> Settings>()) } coAnswers {
                settings.value = firstArg<(Settings) -> Settings>()(settings.value)
            }
            coEvery { runtime.loadSegment(any()) } coAnswers { segments[firstArg()] }
            coEvery { runtime.saveSegment(any()) } coAnswers { segments[firstArg<Conversation>().id] = firstArg() }
            coEvery { runtime.generate(any()) } coAnswers {
                val segment = firstArg<Conversation>(); generated += segment
                segments[segment.id] = segment.appendMessage(UIMessage.assistant("reply"))
                "reply"
            }
            coEvery { runtime.keepAlive(any()) } returns {}
            coEvery { runtime.wake(any(), any(), any(), any()) } just Runs
            coEvery { runtime.ownerNote(any(), any(), any()) } just Runs
            coEvery { runtime.stop(any()) } just Runs
            every { runtime.reportFailure(any(), any()) } just Runs
            service = DefaultPartnerChatService(store, settingsStore, runtime, scope, timeout)
        }
    }

    @Test fun perspectiveKeepsPrefixesOriginsAndOwnerAuthoritySeparateAndSkipsOwnMessages() {
        val chat = Uuid.random(); val exchange = Uuid.random()
        val segment = Conversation(assistantId = b.id, chatId = chat, messageNodes = emptyList())
            .appendMessage(UIMessage.assistant("own"))
        fun msg(sender: Uuid, text: String) = ChatMessage(PartnerChatMessage(Uuid.random(), chat, sender, text,
            Instant.now(), if (sender == OWNER_PARTICIPANT_ID) null else exchange), listOf(a.id))
        val messages = listOf(msg(a.id, "hello"), msg(b.id, "own"), msg(OWNER_PARTICIPANT_ID, "owner says"))
        val synced = synchronizePerspective(segment, messages, null, Settings(assistants = listOf(a, b)))
        assertEquals(listOf("own", "[小A] hello", "[主人] owner says"), synced.currentMessages.map { it.toText() })
        assertEquals(MessageOrigin.Partner(a.id, exchange, listOf(a.id)), synced.currentMessageNodes[1].origin)
        assertNull(synced.currentMessageNodes[2].origin)
        assertNotNull(synced.ownerTrigger())
        val partnerLast = synchronizePerspective(segment, listOf(messages[2], messages[0]), null, Settings(assistants = listOf(a, b)))
        assertNull(partnerLast.ownerTrigger())
        assertEquals(synced, synchronizePerspective(synced, messages, messages.last().message.id, Settings()))
        assertEquals(synced, synchronizePerspective(synced, messages, null, Settings()))
    }

    @Test fun nicknameAndPrivateContextAreUsed() {
        val chat = PartnerChat(Uuid.random(), PartnerChatKind.DIRECT, listOf(a.id, b.id), createdAt = Instant.now())
        val settings = Settings(assistants = listOf(a, b)).let { it.copy(displaySetting = it.displaySetting.copy(userNickname = "饭主")) }
        val message = ChatMessage(PartnerChatMessage(Uuid.random(), chat.id, OWNER_PARTICIPANT_ID, "hello", Instant.now()))
        val synced = synchronizePerspective(Conversation(assistantId = a.id, chatId = chat.id, messageNodes = emptyList()), listOf(message), null, settings)
        assertEquals("[饭主] hello", synced.currentMessages.single().toText())
        val context = perspectiveContext(chat, a.id, settings)
        assertTrue(context.contains("小B")); assertTrue(context.contains("[饭主]")); assertTrue(context.contains("只能回复文字"))
    }

    @Test fun unauthorizedToolNeedsApprovalAndExecutionPersistsNormalizedGrantAndReturnsReply() = runTest {
        val f = Fixture(this)
        val args = buildJsonObject { put("to", b.id.toString()); put("text", "hello") }
        val tool = buildPartnerMessageTools(caller(), f.settingsStore, f.service).last()
        assertTrue(tool.needsApproval(args)); assertTrue(f.settings.value.partnerContactGrants.isEmpty())
        val result = Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("replied", result.status()); assertEquals("reply", result.getValue("reply").jsonPrimitive.content)
        assertEquals(listOf(PartnerPair.of(a.id, b.id)), f.settings.value.partnerContactGrants)
        assertFalse(tool.needsApproval(args))
        assertEquals(2, f.store.allMessages.size)
        val chat = f.store.chats.values.single()
        assertEquals(chat.id.toString(), result.getValue("chat_id").jsonPrimitive.content)
        assertEquals(b.id.toString(), result.getValue("partner_id").jsonPrimitive.content)
        val member = f.store.member(chat.id, b.id)
        assertEquals(f.store.allMessages.last().message.id, member.syncedMessageId)
        assertEquals(chat.id, f.generated.single().chatId)
        assertNull(f.generated.single().ownerTrigger())
        assertEquals("[小A] hello", f.generated.single().currentMessages.single().toText())
        f.service.revokeContact(b.id, a.id)
        assertTrue(tool.needsApproval(args))
    }

    @Test fun invalidSelfLoopAndDepthCallsRefuseWithoutApprovalOrCreatingChat() = runTest {
        val f = Fixture(this)
        val cases = listOf(caller() to "invalid", caller() to Uuid.random().toString(), caller() to a.id.toString(),
            caller(MessageOrigin.Partner(b.id, Uuid.random(), listOf(b.id))) to b.id.toString(),
            caller(MessageOrigin.Partner(b.id, Uuid.random(), listOf(b.id, c.id))) to Uuid.random().toString(),
            caller(MessageOrigin.Partner(b.id, Uuid.random(), listOf(b.id, a.id))) to c.id.toString())
        for ((caller, to) in cases) {
            assertFalse(f.service.needsApproval(caller, to))
            assertEquals("refused", f.service.send(caller, to, "hi").status())
        }
        assertTrue(f.store.chats.isEmpty()); assertTrue(f.settings.value.partnerContactGrants.isEmpty())
    }

    @Test fun secondHopCarriesTheRootExchangeAndChain() = runTest {
        val f = Fixture(this)
        val exchange = Uuid.random()
        val fromB = Conversation(assistantId = b.id, chatId = Uuid.random(), messageNodes = emptyList())
            .appendMessage(UIMessage.user("from A"), origin = MessageOrigin.Partner(a.id, exchange, listOf(a.id)))
        assertEquals("replied", f.service.send(fromB, c.id.toString(), "hello C").status())
        val user = f.generated.single().currentMessageNodes.single()
        assertEquals(MessageOrigin.Partner(b.id, exchange, listOf(a.id, b.id)), user.origin)
        assertEquals(exchange, f.store.allMessages.last().message.exchangeId)
    }

    @Test fun timeoutReturnsPendingThenWritesReplyAndWakesOnlyImInitiator() = runTest {
        val f = Fixture(this, 100)
        val gate = CompletableDeferred<String>()
        coEvery { f.runtime.generate(any()) } coAnswers { gate.await() }
        val initiating = caller()
        val send = async { f.service.send(initiating, b.id.toString(), "hello") }
        runCurrent(); assertEquals(setOf(b.id), f.service.observeTyping(f.store.chats.values.single().id).first())
        advanceTimeBy(100); runCurrent()
        assertEquals("pending", send.await().status())
        assertEquals(1, f.store.allMessages.size)
        coVerify(exactly = 0) { f.runtime.wake(any(), any(), any(), any()) }
        gate.complete("late reply"); runCurrent()
        val chatId = f.store.chats.values.single().id
        assertEquals("late reply", f.store.allMessages.last().message.text)
        assertTrue(f.service.observeTyping(chatId).first().isEmpty())
        coVerify { f.runtime.wake(MessageOrigin.Event("partner_replied", "小B 回复了", b.id, chatId), a.id,
            match { it.contains("late reply") }, initiating.id) }
    }

    @Test fun secondHopTimeoutDoesNotWakeInitiator() = runTest {
        val f = Fixture(this, 100)
        val gate = CompletableDeferred<String>()
        coEvery { f.runtime.generate(any()) } coAnswers { gate.await() }
        val initiating = caller(MessageOrigin.Partner(c.id, Uuid.random(), listOf(c.id)), Uuid.random())
        val send = async { f.service.send(initiating, b.id.toString(), "hello") }
        advanceTimeBy(100); runCurrent(); assertEquals("pending", send.await().status())
        gate.complete("late"); runCurrent()
        assertEquals(2, f.store.allMessages.size)
        coVerify(exactly = 0) { f.runtime.wake(any(), any(), any(), any()) }
    }

    @Test fun exchangeBudgetRefusesFurtherMessages() = runTest {
        val f = Fixture(this)
        val exchange = Uuid.random()
        val initiating = caller(MessageOrigin.Partner(c.id, exchange, listOf(c.id)), Uuid.random())
        repeat(3) { assertEquals("replied", f.service.send(initiating, b.id.toString(), "hi").status()) }
        assertEquals("refused", f.service.send(initiating, b.id.toString(), "seventh").status())
        assertEquals(6, f.store.allMessages.size)
    }

    @Test fun ownerSendReturnsAfterStorageAndMentionsSpeakInOrder() = runTest {
        val f = Fixture(this)
        val chat = f.store.direct(a.id, b.id)
        val gate = CompletableDeferred<String>()
        coEvery { f.runtime.generate(any()) } coAnswers { val segment = firstArg<Conversation>(); f.generated += segment; gate.await() }
        f.service.sendFromOwner(chat.id, "hello", listOf(b.id, a.id))
        assertEquals(OWNER_PARTICIPANT_ID, f.store.allMessages.single().message.senderId)
        assertTrue(f.generated.isEmpty())
        runCurrent(); assertEquals(listOf(b.id), f.generated.map { it.assistantId })
        assertNotNull(f.generated.first().ownerTrigger())
        gate.complete("answer"); runCurrent()
        assertEquals(listOf(b.id, a.id), f.generated.map { it.assistantId })
        assertEquals(3, f.store.allMessages.size)
        f.generated.clear()
        f.service.sendFromOwner(chat.id, "no mentions")
        runCurrent(); assertEquals(a.id, f.generated.single().assistantId)
    }

    @Test fun cursorDoesNotSkipMessageArrivingDuringGeneration() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<String>()
        coEvery { f.runtime.generate(any()) } coAnswers { gate.await() }
        val send = async { f.service.send(caller(), b.id.toString(), "hello") }
        runCurrent()
        val chat = f.store.chats.values.single(); val inputId = f.store.allMessages.single().message.id
        val later = ChatMessage(PartnerChatMessage(Uuid.random(), chat.id, OWNER_PARTICIPANT_ID, "later", Instant.now()))
        f.store.append(later)
        gate.complete("reply"); send.await()
        assertEquals(inputId, f.store.member(chat.id, b.id).syncedMessageId)
        val segment = f.segments.getValue(f.store.member(chat.id, b.id).segmentId!!)
        assertEquals("[主人] later", synchronizePerspective(segment, f.store.messages(chat.id), inputId, f.settings.value).currentMessages.last().toText())
    }

    @Test fun ownerContactIsOnlyAvailableInPerspectivesAndDoesNotGenerate() = runTest {
        val f = Fixture(this)
        assertEquals("refused", f.service.send(caller(), "owner", "note").status())
        val chat = f.store.direct(a.id, b.id)
        assertEquals("delivered", f.service.send(caller(chatId = chat.id), "owner", "note").status())
        coVerify(exactly = 1) { f.runtime.ownerNote(a.id, MessageOrigin.Event("partner_note", "来自和 小B 的私聊", b.id, chat.id), "note") }
        coVerify(exactly = 0) { f.runtime.generate(any()) }
        val contactsTool = buildPartnerMessageTools(caller(chatId = chat.id), f.settingsStore, f.service).first()
        val contacts = Json.parseToJsonElement((contactsTool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text)
            .jsonObject.getValue("contacts").jsonArray
        assertEquals("owner", contacts.first().jsonObject.getValue("id").jsonPrimitive.content)
        assertFalse(contacts.any { it.jsonObject.getValue("id").jsonPrimitive.content == a.id.toString() })
    }

    @Test fun summariesContactsCleanupAndRetainChatsIncludePartnerChats() = runTest {
        val f = Fixture(this)
        f.service.send(caller(), b.id.toString(), "hello")
        val chat = f.store.chats.values.single()
        f.settings.value = f.settings.value.copy(chatOrganization = ChatOrganization(legacyPinsMigrated = true).pin(chat.id).pin(a.id).pin(b.id))
        assertEquals(listOf(b.id), f.service.observeContacts(a.id).first())
        assertEquals("reply", f.service.observeSummaries().first().single().last?.text)
        assertEquals(f.settings.value.chatOrganization, f.settings.value.withMigratedChatOrganization(emptySet(), setOf(chat.id)).chatOrganization)
        f.service.removeAssistant(a.id)
        assertTrue(f.store.chats.isEmpty()); assertTrue(f.store.members.isEmpty()); assertTrue(f.store.allMessages.isEmpty())
        assertTrue(f.settings.value.partnerContactGrants.isEmpty())
        assertEquals(listOf(b.id), f.settings.value.chatOrganization.pinned)
        coVerify(exactly = 2) { f.runtime.stop(any()) }
    }

    @Test fun deletingPartnerCancelsAnOwnerTurnWaitingForApproval() = runTest {
        val f = Fixture(this)
        val chat = f.store.direct(a.id, b.id)
        coEvery { f.runtime.generate(any()) } coAnswers { awaitCancellation() }
        f.service.sendFromOwner(chat.id, "hello", listOf(b.id))
        runCurrent()
        assertEquals(setOf(b.id), f.service.observeTyping(chat.id).first())
        f.service.removeAssistant(a.id)
        assertTrue(f.store.chats.isEmpty())
        assertTrue(f.service.observeTyping(chat.id).first().isEmpty())
    }

    @Test fun imSendDoesNotWaitOnSendersBusyPerspectiveAndMirrorsItAtNextSync() = runTest {
        val f = Fixture(this)
        val chat = f.store.direct(a.id, b.id)
        val gate = CompletableDeferred<String>()
        var busy = true
        coEvery { f.runtime.generate(any()) } coAnswers {
            val segment = firstArg<Conversation>(); f.generated += segment
            val text = if (segment.assistantId == a.id && busy) gate.await() else "reply"
            f.segments[segment.id] = segment.appendMessage(UIMessage.assistant(text))
            text
        }
        f.service.sendFromOwner(chat.id, "owner", listOf(a.id)); runCurrent()
        val send = async { f.service.send(caller(), b.id.toString(), "parallel IM send") }
        runCurrent()
        assertTrue(send.isCompleted); assertEquals("replied", send.await().status())
        busy = false; gate.complete("reply"); runCurrent()
        f.service.sendFromOwner(chat.id, "next", listOf(a.id)); runCurrent()
        val newest = f.generated.last()
        assertEquals(1, newest.currentMessages.count { it.role == MessageRole.ASSISTANT && it.toText() == "parallel IM send" })
    }
}

internal class FakePartnerStore : PartnerChatStore {
    val chats = linkedMapOf<Uuid, PartnerChat>()
    val members = mutableMapOf<Pair<Uuid, Uuid>, ChatMember>()
    val allMessages = mutableListOf<ChatMessage>()
    private val reserved = mutableMapOf<Uuid, Uuid>()
    private val snapshots = MutableStateFlow(ChatSnapshot(emptyList(), emptyList()))
    private fun publish() { snapshots.value = ChatSnapshot(chats.values.toList(), allMessages.toList()) }
    override fun observeSnapshot() = snapshots
    override fun observeMessages(chatId: Uuid) = snapshots.map { it.messages.filter { m -> m.message.chatId == chatId } }
    override suspend fun chat(chatId: Uuid) = chats[chatId]
    override suspend fun direct(a: Uuid, b: Uuid): PartnerChat {
        chats.values.firstOrNull { it.memberIds.toSet() == setOf(a, b) }?.let { return it }
        val chat = PartnerChat(Uuid.random(), PartnerChatKind.DIRECT, listOf(a, b), createdAt = Instant.now())
        chats[chat.id] = chat
        chat.memberIds.forEach { members[chat.id to it] = ChatMember(chat.id, it) }
        publish(); return chat
    }
    override suspend fun member(chatId: Uuid, assistantId: Uuid) = members.getValue(chatId to assistantId)
    override suspend fun updateMember(member: ChatMember) { members[member.chatId to member.assistantId] = member }
    override suspend fun messages(chatId: Uuid) = allMessages.filter { it.message.chatId == chatId }
    override suspend fun append(message: ChatMessage, reserveReply: Boolean): Boolean {
        val m = message.message; val consumes = m.replyTo in reserved
        if (m.exchangeId != null && allMessages.count { it.message.exchangeId == m.exchangeId } +
            reserved.values.count { it == m.exchangeId } + (if (reserveReply) 2 else if (consumes) 0 else 1) > 6) return false
        allMessages.add(message)
        if (reserveReply) reserved[m.id] = m.exchangeId!!
        if (consumes) reserved.remove(m.replyTo)
        publish(); return true
    }
    override suspend fun exchangeUsage(exchangeId: Uuid) = allMessages.count { it.message.exchangeId == exchangeId } + reserved.values.count { it == exchangeId }
    override suspend fun abandonReply(messageId: Uuid) { reserved.remove(messageId) }
    override suspend fun ids() = chats.keys.toSet()
    override suspend fun directChatsOf(assistantId: Uuid) = chats.values.filter { assistantId in it.memberIds }.map { it.id }
    override suspend fun delete(chatId: Uuid) {
        chats.remove(chatId); members.keys.removeAll { it.first == chatId }; allMessages.removeAll { it.message.chatId == chatId }; publish()
    }
}
