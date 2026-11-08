package me.ayuilos.miffan.data.db.fts

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.model.Conversation
import me.rerere.ai.ui.UIMessage
import org.junit.Test
import kotlin.uuid.Uuid

class MessageFtsManagerIncrementalTest {
    private val sqlite = mockk<SupportSQLiteDatabase>(relaxed = true)
    private val manager: MessageFtsManager

    init {
        val database = mockk<AppDatabase>()
        every { database.openHelper.writableDatabase } returns sqlite
        manager = MessageFtsManager(database)
    }

    /** Makes the stored rows of the conversation look like [rows] (rowid, node, message, text). */
    private fun stored(rows: List<List<Any>>) {
        val cursor = mockk<Cursor>(relaxed = true)
        var next = -1
        every { cursor.moveToNext() } answers { ++next < rows.size }
        every { cursor.getLong(0) } answers { (rows[next][0] as Number).toLong() }
        every { cursor.getString(1) } answers { rows[next][1] as String }
        every { cursor.getString(2) } answers { rows[next][2] as String }
        every { cursor.getString(3) } answers { rows[next][3] as String }
        every { sqlite.query(match<String> { it.startsWith("SELECT rowid") }, any<Array<out Any?>>()) } returns cursor
    }

    private fun rowOf(conversation: Conversation, index: Int): List<Any> {
        val node = conversation.messageNodes[index]
        return listOf(index + 1L, node.id.toString(), node.message.id.toString(), node.message.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().joinToString("\n") { it.text })
    }

    private fun conversation() = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        .appendMessage(UIMessage.user("first"))
        .appendMessage(UIMessage.assistant("second"))

    @Test fun savingAnUnchangedConversationWritesNothing() = runTest {
        val c = conversation()
        stored(listOf(rowOf(c, 0), rowOf(c, 1)))
        manager.indexConversation(c)
        verify(exactly = 0) { sqlite.execSQL(any<String>(), any<Array<out Any?>>()) }
        verify { sqlite.setTransactionSuccessful() }
    }

    @Test fun anAppendedMessageInsertsOnlyThatMessage() = runTest {
        val before = conversation()
        stored(listOf(rowOf(before, 0), rowOf(before, 1)))
        val after = before.appendMessage(UIMessage.user("third"))
        manager.indexConversation(after)
        verify(exactly = 1) { sqlite.execSQL(match { it.startsWith("INSERT INTO message_fts") }, any<Array<out Any?>>()) }
        verify(exactly = 0) { sqlite.execSQL(match { it.startsWith("DELETE") }, any<Array<out Any?>>()) }
    }

    @Test fun aRemovedNodeIsDeletedByRowId() = runTest {
        val before = conversation()
        stored(listOf(rowOf(before, 0), rowOf(before, 1)))
        val after = before.copy(messageNodes = before.messageNodes.take(1))
        manager.indexConversation(after)
        verify(exactly = 1) { sqlite.execSQL("DELETE FROM message_fts WHERE rowid = ?", arrayOf(2L)) }
        verify(exactly = 0) { sqlite.execSQL(match { it.startsWith("INSERT") }, any<Array<out Any?>>()) }
    }

    @Test fun anIndexMissingItsRowsIsRepairedOnTheNextSave() = runTest {
        stored(emptyList())
        manager.indexConversation(conversation())
        verify(exactly = 2) { sqlite.execSQL(match { it.startsWith("INSERT INTO message_fts") }, any<Array<out Any?>>()) }
    }
}
