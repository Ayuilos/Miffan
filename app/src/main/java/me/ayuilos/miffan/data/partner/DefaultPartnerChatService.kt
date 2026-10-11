package me.ayuilos.miffan.data.partner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.awaitLoadedSettings
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.model.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

internal interface PartnerChatRuntime {
    suspend fun loadSegment(id: Uuid): Conversation?
    suspend fun saveSegment(segment: Conversation)
    /** Waits across approval pauses; returns only the final assistant text. */
    suspend fun generate(segment: Conversation): String
    suspend fun wake(origin: MessageOrigin.Event, assistantId: Uuid, text: String, segmentId: Uuid)
    suspend fun ownerNote(assistantId: Uuid, origin: MessageOrigin.Event, text: String)
    /** Acquired before generation and released after writeback/handoff, with no lifetime gap. */
    suspend fun keepAlive(segmentId: Uuid): suspend () -> Unit
    suspend fun stop(segmentId: Uuid)
    fun reportFailure(error: Exception, segmentId: Uuid?)
}

internal class DefaultPartnerChatService(
    private val store: PartnerChatStore,
    private val settingsStore: SettingsStore,
    private val runtime: PartnerChatRuntime,
    private val scope: CoroutineScope,
    private val replyTimeoutMillis: Long = 90_000L,
) : PartnerChatService {
    private val typing = MutableStateFlow<Map<Uuid, Set<Uuid>>>(emptyMap())
    private val memberLocks = ConcurrentHashMap<Pair<Uuid, Uuid>, Mutex>()
    private val ownerLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val deleted = ConcurrentHashMap.newKeySet<Uuid>()
    private val ownerJobs = ConcurrentHashMap<Uuid, MutableSet<Job>>()
    private val replies = ConcurrentHashMap<Uuid, MutableSet<Deferred<PartnerChatMessage>>>()

    override fun observeSummaries(): Flow<List<PartnerChatSummary>> = combine(store.observeSnapshot(), typing) { snapshot, running ->
        snapshot.chats.map { chat ->
            val last = snapshot.messages.lastOrNull { it.message.chatId == chat.id }?.message
            PartnerChatSummary(chat, last, running[chat.id].orEmpty(), last?.createdAt ?: chat.createdAt)
        }.sortedByDescending { it.lastActivity }
    }.distinctUntilChanged()
    override fun observeChat(chatId: Uuid): Flow<PartnerChat?> = store.observeSnapshot()
        .map { it.chats.find { c -> c.id == chatId } }.distinctUntilChanged()
    override fun observeMessages(chatId: Uuid): Flow<List<PartnerChatMessage>> =
        store.observeMessages(chatId).map { messages -> messages.map { it.message } }.distinctUntilChanged()
    override fun observeTyping(chatId: Uuid): Flow<Set<Uuid>> = typing.map { it[chatId].orEmpty() }.distinctUntilChanged()
    override fun observeContacts(assistantId: Uuid): Flow<List<Uuid>> = settingsStore.settingsFlow.map { settings ->
        settings.partnerContactGrants.mapNotNull { pair -> when (assistantId) {
            pair.first -> pair.second
            pair.second -> pair.first
            else -> null
        } }.filter { settings.getAssistantById(it) != null }.distinct()
    }.distinctUntilChanged()
    override suspend fun revokeContact(a: Uuid, b: Uuid) {
        settingsStore.update { it.copy(partnerContactGrants = it.partnerContactGrants.filterNot { p -> p == PartnerPair.of(a, b) }) }
    }

    override suspend fun sendFromOwner(chatId: Uuid, text: String, mentions: List<Uuid>) {
        require(text.isNotBlank())
        settingsStore.settingsFlow.awaitLoadedSettings()
        val chat = requireNotNull(store.chat(chatId)) { "Chat not found" }
        val lastPartner = store.messages(chatId).lastOrNull { it.message.senderId in chat.memberIds }?.message?.senderId
        val targets = if (mentions.isEmpty()) listOfNotNull(lastPartner ?: chat.memberIds.firstOrNull())
            else mentions.filter { it in chat.memberIds }.distinct()
        val message = PartnerChatMessage(Uuid.random(), chatId, OWNER_PARTICIPANT_ID, text, Instant.now())
        check(store.append(ChatMessage(message)))
        // Store synchronously, then schedule sequential replies without making the UI wait.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            ownerLocks.getOrPut(chatId) { Mutex() }.withLock {
                for (target in targets) {
                    try { speak(chatId, target, message) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { runtime.reportFailure(e, null); break }
                }
            }
        }
        val jobs = ownerJobs.getOrPut(chatId) { ConcurrentHashMap.newKeySet() }
        jobs.add(job)
        job.invokeOnCompletion { jobs.remove(job) }
        job.start()
    }

    internal fun refusal(caller: Conversation, to: Uuid?): String? {
        val settings = settingsStore.settingsFlow.value
        val chain = caller.partnerTrigger()?.chain.orEmpty()
        return when {
            to == null || settings.getAssistantById(to) == null -> "Contact does not exist."
            settings.getAssistantById(caller.assistantId) == null -> "Sender does not exist."
            to == caller.assistantId -> "You cannot contact yourself."
            to in chain -> "Contact is already on the call chain (loop)."
            chain.size >= 2 -> "Call chain depth limit is 2 (A→B→C)."
            else -> null
        }
    }

    internal fun needsApproval(caller: Conversation, to: String): Boolean {
        if (to == PartnerMessageTool.OWNER) return false
        val id = runCatching { Uuid.parse(to) }.getOrNull()
        return refusal(caller, id) == null && PartnerPair.of(caller.assistantId, requireNotNull(id)) !in
            settingsStore.settingsFlow.value.partnerContactGrants
    }

    /** Called only by the tool executor, after the existing generation approval gate. */
    internal suspend fun send(caller: Conversation, to: String, text: String): JsonObject {
        if (text.isBlank()) return refused("Message text is empty.")
        settingsStore.settingsFlow.awaitLoadedSettings()
        if (to == PartnerMessageTool.OWNER) return sendToOwner(caller, text)
        val target = runCatching { Uuid.parse(to) }.getOrNull()
        refusal(caller, target)?.let { return refused(it) }
        val b = requireNotNull(target)
        val trigger = caller.partnerTrigger()
        val exchange = trigger?.exchangeId ?: Uuid.random()
        if (store.exchangeUsage(exchange) > 4) return refused("Exchange limit is 6 messages.")
        val pair = PartnerPair.of(caller.assistantId, b)
        settingsStore.update { settings ->
            // execute means the owner approved this particular pending call if a grant was missing.
            if (pair in settings.partnerContactGrants) settings
            else settings.copy(partnerContactGrants = settings.partnerContactGrants + pair)
        }
        val chat = store.direct(caller.assistantId, b)
        val chain = trigger?.chain.orEmpty() + caller.assistantId
        val outgoing = PartnerChatMessage(Uuid.random(), chat.id, caller.assistantId, text, Instant.now(), exchange)
        if (!store.append(ChatMessage(outgoing, chain), reserveReply = true)) return refused("Exchange limit is 6 messages.")
        // This lease is acquired while A's turn still owns its lease. B's job owns writeback too.
        val release = try {
            if (caller.chatId != chat.id) recordOutgoingPerspective(chat, outgoing)
            runtime.keepAlive(caller.id)
        } catch (e: Exception) {
            withContext(NonCancellable) { store.abandonReply(outgoing.id) }
            throw e
        }
        val task = scope.async(start = CoroutineStart.UNDISPATCHED) {
            try { speak(chat.id, b, outgoing) }
            catch (e: Exception) {
                withContext(NonCancellable) { store.abandonReply(outgoing.id) }
                if (e !is CancellationException) runtime.reportFailure(e, caller.id)
                throw e
            } finally { release() }
        }
        val tasks = replies.getOrPut(chat.id) { ConcurrentHashMap.newKeySet() }
        tasks.add(task)
        task.invokeOnCompletion { tasks.remove(task) }
        val reply = withTimeoutOrNull(replyTimeoutMillis) { task.await() }
        if (reply != null) return result(PartnerMessageTool.STATUS_REPLIED, chat.id, b, reply.text)
        if (caller.chatId == null) {
            // Acquire before B completes/releases its turn, including persistence and the queued wake.
            val releaseWake = runtime.keepAlive(caller.id)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val late = task.await()
                    if (chat.id !in deleted) {
                        val name = settingsStore.settingsFlow.value.getAssistantById(b)?.name?.ifBlank { "伙伴" } ?: "伙伴"
                        runtime.wake(MessageOrigin.Event("partner_replied", "$name 回复了", b, chat.id),
                            caller.assistantId, "$name 在你们的私聊中回复了：\n${late.text}\n请处理这条回复，并按需要向主人汇报。", caller.id)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { runtime.reportFailure(e, caller.id) }
                finally { releaseWake() }
            }
        }
        return result(PartnerMessageTool.STATUS_PENDING, chat.id, b)
    }

    private suspend fun sendToOwner(caller: Conversation, text: String): JsonObject {
        val chatId = caller.chatId ?: return refused("Owner contact is available only in a partner chat perspective.")
        val chat = store.chat(chatId) ?: return refused("Chat no longer exists.")
        if (caller.assistantId !in chat.memberIds) return refused("Sender is not a member of this chat.")
        val other = chat.memberIds.firstOrNull { it != caller.assistantId }
        val name = other?.let { settingsStore.settingsFlow.value.getAssistantById(it)?.name }?.ifBlank { "伙伴" } ?: "伙伴"
        runtime.ownerNote(caller.assistantId, MessageOrigin.Event("partner_note", "来自和 $name 的私聊", other, chatId), text)
        return result(PartnerMessageTool.STATUS_DELIVERED, chatId, caller.assistantId)
    }

    private suspend fun perspective(chat: PartnerChat, assistantId: Uuid): Pair<ChatMember, Conversation> {
        val member = store.member(chat.id, assistantId)
        val settings = settingsStore.settingsFlow.awaitLoadedSettings()
        val other = chat.memberIds.first { it != assistantId }
        val name = settings.getAssistantById(other)?.name?.ifBlank { "伙伴" } ?: "伙伴"
        val segment = member.segmentId?.let { runtime.loadSegment(it) } ?: Conversation(
            assistantId = assistantId, chatId = chat.id, title = "与 $name 的私聊", messageNodes = emptyList())
        return member.copy(segmentId = segment.id) to segment
    }

    private suspend fun recordOutgoingPerspective(chat: PartnerChat, message: PartnerChatMessage) {
        val mutex = memberLocks.getOrPut(chat.id to message.senderId) { Mutex() }
        // Never wait on the sender's other active turn: it may be delivering a note back to
        // this IM generation. A busy perspective mirrors the outbound send at its next sync.
        if (!mutex.tryLock()) return
        try {
            val (member, old) = perspective(chat, message.senderId)
            val messages = store.messages(chat.id)
            val segment = synchronizePerspective(old, messages, member.syncedMessageId,
                settingsStore.settingsFlow.value, mirrorOutgoing = true)
            runtime.saveSegment(segment)
            store.updateMember(member.copy(syncedMessageId = messages.lastOrNull()?.message?.id))
        } finally { mutex.unlock() }
    }

    private suspend fun speak(chatId: Uuid, assistantId: Uuid, replyTo: PartnerChatMessage): PartnerChatMessage =
        memberLocks.getOrPut(chatId to assistantId) { Mutex() }.withLock {
            check(chatId !in deleted) { "Chat was deleted" }
            val chat = requireNotNull(store.chat(chatId))
            val (member, old) = perspective(chat, assistantId)
            val release = runtime.keepAlive(old.id)
            typing.update { it + (chatId to (it[chatId].orEmpty() + assistantId)) }
            try {
                val messages = store.messages(chatId)
                val segment = synchronizePerspective(old, messages, member.syncedMessageId, settingsStore.settingsFlow.value, mirrorOutgoing = true)
                runtime.saveSegment(segment)
                store.updateMember(member.copy(syncedMessageId = messages.lastOrNull()?.message?.id ?: member.syncedMessageId))
                val text = runtime.generate(segment)
                check(chatId !in deleted)
                val source = messages.find { it.message.id == replyTo.id }
                val reply = PartnerChatMessage(Uuid.random(), chatId, assistantId, text, Instant.now(),
                    replyTo.exchangeId ?: Uuid.random(), replyTo.id)
                check(store.append(ChatMessage(reply, source?.chain.orEmpty() + assistantId))) { "Exchange limit is 6 messages." }
                // Another message can arrive while generating. Do not jump the cursor over it.
                val latest = store.messages(chatId)
                val tail = latest.dropWhile { it.message.id != messages.lastOrNull()?.message?.id }.drop(1)
                val cursor = if (tail.all { it.message.senderId == assistantId && it.message.replyTo != null }) reply.id else messages.lastOrNull()?.message?.id
                store.updateMember(member.copy(syncedMessageId = cursor))
                reply
            } finally {
                typing.update { it + (chatId to (it[chatId].orEmpty() - assistantId)) }
                release()
            }
        }

    internal suspend fun removeAssistant(assistantId: Uuid) {
        val ids = store.directChatsOf(assistantId)
        ids.forEach { id ->
            deleted.add(id)
            ownerJobs.remove(id)?.forEach { it.cancel() }
            replies.remove(id)?.forEach { it.cancel() }
            store.chat(id)?.memberIds?.forEach { memberId ->
                store.member(id, memberId).segmentId?.let { runtime.stop(it) }
            }
            // Await in-flight member writebacks before deleting their perspectives.
            store.chat(id)?.memberIds?.forEach { memberId ->
                memberLocks.getOrPut(id to memberId) { Mutex() }.withLock { }
            }
            store.delete(id)
            typing.update { it - id }
        }
        val remainingChatIds = store.ids()
        settingsStore.update { settings ->
            settings.copy(partnerContactGrants = settings.partnerContactGrants.filterNot { it.first == assistantId || it.second == assistantId },
                chatOrganization = settings.chatOrganization.retainChats((settings.assistants.map { it.id }.toSet() - assistantId) + remainingChatIds))
        }
    }
}

private fun refused(reason: String): JsonObject = buildJsonObject {
    put(PartnerMessageTool.RESULT_STATUS, PartnerMessageTool.STATUS_REFUSED)
    put("reason", reason)
}
private fun result(status: String, chatId: Uuid, partnerId: Uuid, reply: String? = null): JsonObject = buildJsonObject {
    put(PartnerMessageTool.RESULT_STATUS, status)
    put(PartnerMessageTool.RESULT_CHAT_ID, chatId.toString())
    put(PartnerMessageTool.RESULT_PARTNER_ID, partnerId.toString())
    if (reply != null) put(PartnerMessageTool.RESULT_REPLY, reply)
}
