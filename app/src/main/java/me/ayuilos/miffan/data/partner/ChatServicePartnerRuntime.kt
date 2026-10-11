package me.ayuilos.miffan.data.partner

import android.app.Application
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.thread.ThreadService
import me.ayuilos.miffan.service.ChatGenerationForegroundService
import me.ayuilos.miffan.service.ChatService
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

internal class ChatServicePartnerRuntime(
    private val context: Application,
    private val conversations: ConversationRepository,
    private val chatService: () -> ChatService,
    private val threadService: () -> ThreadService,
) : PartnerChatRuntime {
    override suspend fun loadSegment(id: Uuid): Conversation? = conversations.getConversationById(id)
    override suspend fun saveSegment(segment: Conversation) = chatService().saveConversation(segment.id, segment)
    override suspend fun generate(segment: Conversation): String = chatService().generatePartnerReply(segment.id)
    override suspend fun wake(origin: MessageOrigin.Event, assistantId: Uuid, text: String, segmentId: Uuid) {
        threadService().deliverFrom(origin, assistantId, listOf(UIMessagePart.Text(text)), segmentId)
        // Hold the handoff lease until the queued event has acquired its own generation lifetime.
        chatService().awaitDeliveredEvent(segmentId, origin)
    }
    override suspend fun ownerNote(assistantId: Uuid, origin: MessageOrigin.Event, text: String) =
        threadService().deliverNote(assistantId, origin, text)
    override suspend fun keepAlive(segmentId: Uuid): suspend () -> Unit {
        val token = Uuid.random()
        ChatGenerationForegroundService.acquire(context, token, segmentId)
        return { ChatGenerationForegroundService.release(context, token) }
    }
    override suspend fun stop(segmentId: Uuid) = chatService().discardPartnerSegment(segmentId)
    override fun reportFailure(error: Exception, segmentId: Uuid?) = chatService().addError(error, segmentId)
}
