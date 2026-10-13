package me.ayuilos.miffan.data.partner

import me.ayuilos.miffan.data.ai.AgentAccess

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import me.ayuilos.miffan.data.thread.ThreadListPrefs
import me.rerere.ai.ui.UIMessagePart
import me.ayuilos.miffan.service.pendingApprovalTools
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    fun observeSegment(id: Uuid): Flow<Conversation?>
    suspend fun answerApproval(segment: Conversation, toolCallId: String, approved: Boolean)
    suspend fun saveSegment(segment: Conversation)
    /** Waits across approval pauses; returns only the final assistant text. */
    suspend fun generate(segment: Conversation): String
    suspend fun wake(origin: MessageOrigin.Event, assistantId: Uuid, text: String, segmentId: Uuid)
    suspend fun ownerNote(assistantId: Uuid, origin: MessageOrigin.Event, text: String)
    /** Acquired before generation and released after writeback/handoff, with no lifetime gap. */
    suspend fun keepAlive(segmentId: Uuid): suspend () -> Unit
    suspend fun keepAliveChat(chatId: Uuid): suspend () -> Unit
    suspend fun stop(segmentId: Uuid)
    fun reportFailure(error: Exception, segmentId: Uuid?)
}

internal class DefaultPartnerChatService(
    private val store: PartnerChatStore,
    private val settingsStore: SettingsStore,
    private val runtime: PartnerChatRuntime,
    private val scope: CoroutineScope,
    private val replyTimeoutMillis: Long = 90_000L,
    private val listPrefs: Flow<ThreadListPrefs> = MutableStateFlow(ThreadListPrefs()),
    private val notifications: PartnerGroupNotifications = PartnerGroupNotifications { _, _, _ -> },
) : PartnerChatService {
    private val typing = MutableStateFlow<Map<Uuid, Set<Uuid>>>(emptyMap())
    private val memberLocks = ConcurrentHashMap<Pair<Uuid, Uuid>, Mutex>()
    private val ownerLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val deleted = ConcurrentHashMap.newKeySet<Uuid>()
    private val ownerJobs = ConcurrentHashMap<Uuid, MutableSet<Job>>()
    private val memberJobs = ConcurrentHashMap<Pair<Uuid, Uuid>, MutableSet<Job>>()
    private val managementLocks = ConcurrentHashMap<Uuid, Mutex>()
    private val replies = ConcurrentHashMap<Uuid, MutableSet<Deferred<PartnerChatMessage>>>()

    override fun observeSummaries(): Flow<List<PartnerChatSummary>> = combine(store.observeSnapshot(), typing, listPrefs) { snapshot, running, prefs ->
        snapshot.chats.map { chat ->
            val last = snapshot.messages.lastOrNull { it.message.chatId == chat.id }?.message
            PartnerChatSummary(chat, last, running[chat.id].orEmpty(), last?.createdAt ?: chat.createdAt,
                if (chat.kind == PartnerChatKind.GROUP) snapshot.messages.count {
                    it.message.chatId == chat.id && it.message.senderId != OWNER_PARTICIPANT_ID &&
                        it.message.createdAt.toEpochMilli() > (prefs.readAt[chat.id.toString()] ?: 0L)
                } else 0)
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
        val targets = if (mentions.isEmpty()) listOfNotNull(if (chat.kind == PartnerChatKind.GROUP) chat.hostId else lastPartner ?: chat.memberIds.firstOrNull())
            else mentions.filter { it in chat.memberIds }.distinct()
        val message = PartnerChatMessage(Uuid.random(), chatId, OWNER_PARTICIPANT_ID, text, Instant.now(), mentions = mentions.distinct())
        check(store.append(ChatMessage(message)))
        // Store synchronously, then schedule the turn(s) without making the UI wait.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (chat.kind == PartnerChatKind.GROUP) {
                try {
                    val release = runtime.keepAliveChat(chatId)
                    try {
                        coroutineScope {
                            val round = GroupRound()
                            targets.forEach { scheduleGroup(this, round, chatId, it, message) }
                        }
                    } finally { release() }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { runtime.reportFailure(e, null) }
            } else ownerLocks.getOrPut(chatId) { Mutex() }.withLock {
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

    internal suspend fun groupMemberIds(caller: Conversation): Set<Uuid> = caller.chatId?.let { store.chat(it) }
        ?.takeIf { it.kind == PartnerChatKind.GROUP }?.memberIds.orEmpty().toSet()

    internal fun refusal(caller: Conversation, to: Uuid?, groupMemberIds: Set<Uuid> = emptySet()): String? {
        val settings = settingsStore.settingsFlow.value
        val chain = caller.partnerTrigger()?.chain.orEmpty()
        return when {
            to == null || settings.getAssistantById(to) == null -> "Contact does not exist."
            settings.getAssistantById(caller.assistantId) == null -> "Sender does not exist."
            to == caller.assistantId -> "You cannot contact yourself."
            to in groupMemberIds -> "对方就在这个群里，请在群里用 @名字。"
            to in chain -> "Contact is already on the call chain (loop)."
            chain.size >= 2 -> "Call chain depth limit is 2 (A→B→C)."
            else -> null
        }
    }

    internal fun needsApproval(caller: Conversation, access: AgentAccess, to: String, groupMemberIds: Set<Uuid>): Boolean {
        if (to == PartnerMessageTool.OWNER) return false
        val id = runCatching { Uuid.parse(to) }.getOrNull()
        val settings = settingsStore.settingsFlow.value
        return refusal(caller, id, groupMemberIds) == null &&
            (access.ownerThreadNonOwnerTurn ||
                (id !in caller.ownerMentionedPartnerIds(settings) &&
                    PartnerPair.of(access.callerId, requireNotNull(id)) !in settings.partnerContactGrants))
    }

    /** Called only by the tool executor, after the existing generation approval gate. */
    internal suspend fun send(caller: Conversation, access: AgentAccess, to: String, text: String, roundGroupMemberIds: Set<Uuid> = emptySet()): JsonObject {
        if (text.isBlank()) return refused("Message text is empty.")
        settingsStore.settingsFlow.awaitLoadedSettings()
        if (to == PartnerMessageTool.OWNER) return sendToOwner(caller, text)
        val target = runCatching { Uuid.parse(to) }.getOrNull()
        // Retain the tool's round snapshot too: removal must not turn a skipped approval into a grant.
        refusal(caller, target, roundGroupMemberIds + groupMemberIds(caller))?.let { return refused(it) }
        val b = requireNotNull(target)
        // Group @ routing is separate from private send_message call chains and their budget.
        val trigger = if (caller.chatId?.let { store.chat(it)?.kind } == PartnerChatKind.GROUP) null else caller.partnerTrigger()
        val exchange = trigger?.exchangeId ?: Uuid.random()
        if (store.exchangeUsage(exchange) > 4) return refused("Exchange limit is 6 messages.")
        val pair = PartnerPair.of(caller.assistantId, b)
        if (!access.ownerThreadNonOwnerTurn) settingsStore.update { settings ->
            // Owner-thread partner/event approvals allow only this message, never a lasting grant.
            // Other turns passed the approval gate (including the owner's current-turn @mention).
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
                        val name = partnerDisplayName(settingsStore.settingsFlow.value.getAssistantById(b))
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
        val name = partnerDisplayName(other?.let { settingsStore.settingsFlow.value.getAssistantById(it) })
        runtime.ownerNote(caller.assistantId, MessageOrigin.Event("partner_note", "来自和 $name 的私聊", other, chatId), text)
        return result(PartnerMessageTool.STATUS_DELIVERED, chatId, caller.assistantId)
    }

    private suspend fun perspective(chat: PartnerChat, assistantId: Uuid): Pair<ChatMember, Conversation> {
        val member = store.member(chat.id, assistantId)
        val settings = settingsStore.settingsFlow.awaitLoadedSettings()
        val other = chat.memberIds.first { it != assistantId }
        val name = partnerDisplayName(settings.getAssistantById(other))
        val segment = member.segmentId?.let { runtime.loadSegment(it) } ?: Conversation(
            assistantId = assistantId, chatId = chat.id, title = if (chat.kind == PartnerChatKind.GROUP) "群聊：${partnerChatTitle(chat, settings)}" else "与 $name 的私聊", messageNodes = emptyList())
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
            check(assistantId in chat.memberIds) { "Member was removed" }
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
                    replyTo.exchangeId ?: Uuid.random(), replyTo.id,
                    if (chat.kind == PartnerChatKind.GROUP) parseGroupMentions(text, chat, assistantId, settingsStore.settingsFlow.value) else emptyList())
                check(store.append(ChatMessage(reply, if (chat.kind == PartnerChatKind.GROUP) emptyList() else source?.chain.orEmpty() + assistantId))) { "Exchange limit is 6 messages." }
                // Another message can arrive while generating. Do not jump the cursor over it.
                val latest = store.messages(chatId)
                val tail = latest.dropWhile { it.message.id != messages.lastOrNull()?.message?.id }.drop(1)
                val cursor = if (tail.all { it.message.senderId == assistantId && it.message.replyTo != null }) reply.id else messages.lastOrNull()?.message?.id
                store.updateMember(member.copy(syncedMessageId = cursor))
                if (chat.kind == PartnerChatKind.GROUP) notifications.onMessage(chat, reply, replyTo)
                reply
            } finally {
                typing.update { it + (chatId to (it[chatId].orEmpty() - assistantId)) }
                release()
            }
        }

    private class GroupRound {
        private var scheduled = 0
        private val turns = mutableMapOf<Uuid, Int>()
        @Synchronized fun reserve(id: Uuid): Boolean {
            val used = turns[id] ?: 0
            if (scheduled >= PartnerGroupLimits.MESSAGES_PER_ROUND || used >= PartnerGroupLimits.TURNS_PER_MEMBER) return false
            scheduled++
            turns[id] = used + 1
            return true
        }
    }

    private fun scheduleGroup(parent: CoroutineScope, round: GroupRound, chatId: Uuid, id: Uuid, trigger: PartnerChatMessage) {
        if (!round.reserve(id)) return
        val job = parent.launch(start = CoroutineStart.LAZY) {
            try {
                val chat = store.chat(chatId) ?: return@launch
                if (id !in chat.memberIds || chatId in deleted) return@launch
                val reply = speak(chatId, id, trigger)
                reply.mentions.filter { it != OWNER_PARTICIPANT_ID && it != id }.forEach {
                    scheduleGroup(parent, round, chatId, it, reply)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { runtime.reportFailure(e, null) }
        }
        val jobs = memberJobs.getOrPut(chatId to id) { ConcurrentHashMap.newKeySet() }
        jobs.add(job)
        job.invokeOnCompletion { jobs.remove(job) }
        job.start()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeApprovals(chatId: Uuid): Flow<List<PartnerChatApproval>> = store.observeSnapshot()
        .map { snapshot -> snapshot.members.filter { it.chatId == chatId && it.segmentId != null } }
        .distinctUntilChanged().flatMapLatest { members ->
            if (members.isEmpty()) flowOf(emptyList()) else combine(members.map { runtime.observeSegment(requireNotNull(it.segmentId)) }) { segments ->
                segments.filterNotNull().flatMap { segment ->
                    segment.currentMessages.flatMap { message ->
                        message.parts.filterIsInstance<UIMessagePart.Tool>().filter { it.isPending }
                            .map { message.createdAt to PartnerChatApproval(segment.assistantId, it) }
                    }
                }.sortedBy { it.first }.map { it.second }
            }
        }.distinctUntilChanged()

    override suspend fun answerApproval(chatId: Uuid, memberId: Uuid, toolCallId: String, approved: Boolean) {
        val chat = requireNotNull(store.chat(chatId))
        require(memberId in chat.memberIds)
        val id = requireNotNull(store.member(chatId, memberId).segmentId)
        val segment = requireNotNull(runtime.loadSegment(id))
        require(segment.chatId == chatId && segment.assistantId == memberId)
        if (segment.pendingApprovalTools().none { it.toolCallId == toolCallId }) return
        runtime.answerApproval(segment, toolCallId, approved)
    }

    private suspend fun validateMembers(ids: List<Uuid>) {
        val settings = settingsStore.settingsFlow.awaitLoadedSettings()
        require(ids.size in PartnerGroupLimits.MIN_PARTNERS..PartnerGroupLimits.MAX_PARTNERS && ids.distinct().size == ids.size)
        require(ids.all { it != OWNER_PARTICIPANT_ID && settings.getAssistantById(it) != null })
    }
    private fun groupTitle(title: String): String = title.trim().let { it.substring(0, it.offsetByCodePoints(0, minOf(24, it.codePointCount(0, it.length)))) }
    private suspend fun group(id: Uuid): PartnerChat = requireNotNull(store.chat(id)).also { require(it.kind == PartnerChatKind.GROUP) }

    override suspend fun createGroup(memberIds: List<Uuid>, title: String): Uuid {
        validateMembers(memberIds)
        val chat = PartnerChat(Uuid.random(), PartnerChatKind.GROUP, memberIds.toList(), groupTitle(title), Instant.now(), memberIds.first())
        store.saveGroup(chat)
        return chat.id
    }
    override suspend fun renameGroup(chatId: Uuid, title: String) = managementLocks.getOrPut(chatId) { Mutex() }.withLock {
        val chat = group(chatId)
        store.saveGroup(chat.copy(title = groupTitle(title)))
    }
    override suspend fun setMembers(chatId: Uuid, memberIds: List<Uuid>) = managementLocks.getOrPut(chatId) { Mutex() }.withLock {
        replaceGroupMembers(group(chatId), memberIds)
    }

    override suspend fun setMembers(chatId: Uuid, memberIds: List<Uuid>, access: AgentAccess) =
        managementLocks.getOrPut(chatId) { Mutex() }.withLock {
            require(access.ownerInitiated) { "Only an owner-initiated turn may invite partners." }
            // Check membership before validating requested ids: inaccessible and absent chats are indistinguishable.
            val chat = store.chat(chatId)?.takeIf { it.kind == PartnerChatKind.GROUP && access.callerId in it.memberIds }
                ?: throw IllegalArgumentException("Group chat does not exist.")
            replaceGroupMembers(chat, (chat.memberIds + memberIds).distinct())
        }

    private suspend fun replaceGroupMembers(chat: PartnerChat, memberIds: List<Uuid>) {
        validateMembers(memberIds)
        val removed = (chat.memberIds - memberIds.toSet()).associateWith { store.member(chat.id, it).segmentId }
        // Publish removal before stopping jobs: queued turns must no longer start for this member.
        store.replaceMembers(chat.copy(memberIds = memberIds.toList(), hostId = chat.hostId?.takeIf { it in memberIds } ?: memberIds.first()))
        removed.forEach { (id, segment) -> removeMember(chat.id, id, segment) }
    }
    private suspend fun removeMember(chatId: Uuid, id: Uuid, segmentId: Uuid?) {
        memberJobs.remove(chatId to id)?.forEach { it.cancel() }
        segmentId?.let { runtime.stop(it) }
        // Pointer may already be gone; load all segments for cleanup via the store.
        memberLocks.getOrPut(chatId to id) { Mutex() }.withLock {
            store.removePerspective(chatId, id)
        }
        typing.update { it + (chatId to (it[chatId].orEmpty() - id)) }
    }
    override suspend fun setHost(chatId: Uuid, assistantId: Uuid) = managementLocks.getOrPut(chatId) { Mutex() }.withLock {
        val chat = group(chatId)
        require(assistantId in chat.memberIds)
        store.saveGroup(chat.copy(hostId = assistantId))
    }
    override suspend fun deleteGroup(chatId: Uuid) = managementLocks.getOrPut(chatId) { Mutex() }.withLock {
        group(chatId)
        deleteChat(chatId)
        retainOrganization()
    }
    private suspend fun deleteChat(id: Uuid) {
        deleted.add(id)
        ownerJobs.remove(id)?.forEach { it.cancel() }
        replies.remove(id)?.forEach { it.cancel() }
        val members = store.chat(id)?.memberIds.orEmpty()
        members.forEach { memberId ->
            memberJobs.remove(id to memberId)?.forEach { it.cancel() }
            store.member(id, memberId).segmentId?.let { runtime.stop(it) }
        }
        members.forEach { memberLocks.getOrPut(id to it) { Mutex() }.withLock { } }
        store.delete(id)
        typing.update { it - id }
    }
    private suspend fun retainOrganization() {
        val ids = store.ids()
        settingsStore.update { settings -> settings.copy(chatOrganization = settings.chatOrganization.retainChats(
            settings.assistants.map { it.id }.toSet() + ids)) }
    }

    internal suspend fun removeAssistant(assistantId: Uuid) {
        store.chatsOf(assistantId).forEach { id ->
            val chat = store.chat(id) ?: return@forEach
            val remaining = chat.memberIds - assistantId
            if (chat.kind == PartnerChatKind.DIRECT) deleteChat(id)
            else if (remaining.size < PartnerGroupLimits.MIN_PARTNERS) deleteGroup(id)
            else setMembers(id, remaining)
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
