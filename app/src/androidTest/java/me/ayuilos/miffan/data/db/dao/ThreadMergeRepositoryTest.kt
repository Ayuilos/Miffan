package me.ayuilos.miffan.data.db.dao

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.db.fts.MessageFtsManager
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.TopicStart
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.repository.FilesRepository
import me.ayuilos.miffan.data.thread.planSegmentMerge
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ThreadMergeRepositoryTest : ImDatabaseTestSupport() {
    @Test fun movingNodesAndRestoringSnapshotsIsAtomicAndKeepsAttachments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = AppScope()
        val attachment = File.createTempFile("merge-attachment", ".png", context.cacheDir)
        try {
            database.openHelper.writableDatabase.execSQL("""CREATE VIRTUAL TABLE message_fts USING fts5(
                text, node_id UNINDEXED, message_id UNINDEXED, conversation_id UNINDEXED,
                title UNINDEXED, update_at UNINDEXED)""")
            val repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
                database.favoriteDao(), database, FilesManager(context, FilesRepository(database.managedFileDao()), scope),
                MessageFtsManager(database))
            val assistant = Uuid.random()
            fun message(role: MessageRole, minute: Int) = UIMessage(role = role,
                createdAt = LocalDateTime(2026, 10, 1, 9, minute), parts = listOf(UIMessagePart.Text("$role $minute")))
            val target = Conversation.linear(assistantId = assistant,
                messages = listOf(message(MessageRole.USER, 0), message(MessageRole.ASSISTANT, 2))).copy(
                createAt = Instant.ofEpochMilli(1), updateAt = Instant.ofEpochMilli(4), threadClosedAt = 9, threadSummary = "old")
            val source = Conversation.linear(assistantId = assistant,
                messages = listOf(message(MessageRole.USER, 1).copy(parts = listOf(UIMessagePart.Image(Uri.fromFile(attachment).toString()))),
                    message(MessageRole.ASSISTANT, 3))).copy(
                createAt = Instant.ofEpochMilli(2), updateAt = Instant.ofEpochMilli(5), topicStart = TopicStart.OWNER)
            repository.insertConversation(target)
            repository.insertConversation(source)
            val merged = planSegmentMerge(source, target).merged
            repository.mergeThreadSegments(source, merged)
            assertNull(repository.getConversationById(source.id))
            assertEquals(merged, repository.getConversationById(target.id))
            assertTrue(attachment.exists())
            repository.updateConversation(merged)
            assertEquals(4, repository.getConversationById(target.id)!!.currentMessageNodes.size)
            repository.restoreThreadSegments(source, target)
            assertEquals(source, repository.getConversationById(source.id))
            assertEquals(target, repository.getConversationById(target.id))
            assertTrue(attachment.exists())
        } finally { scope.cancel(); attachment.delete() }
    }
}
