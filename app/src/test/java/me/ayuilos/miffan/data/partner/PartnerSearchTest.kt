package me.ayuilos.miffan.data.partner

import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.*
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.fts.MessageFtsManager
import me.ayuilos.miffan.data.model.Conversation
import me.rerere.ai.ui.UIMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class PartnerSearchTest {
    @Before fun log() { mockkStatic(Log::class); every { Log.i(any(), any()) } returns 0 }
    @After fun cleanup() = unmockkStatic(Log::class)

    @Test fun searchExcludesPerspectivesOnlyInImAndIndexesThemForProfessionalMode() = runTest {
        val sqlite = mockk<SupportSQLiteDatabase>(relaxed = true)
        val database = mockk<AppDatabase>()
        val cursor = mockk<Cursor>(relaxed = true)
        every { database.openHelper.writableDatabase } returns sqlite
        every { sqlite.query(any<String>(), any<Array<out Any?>>()) } returns cursor
        every { cursor.moveToNext() } returns false
        var im = true
        val manager = MessageFtsManager(database) { im }
        manager.search("needle")
        verify { sqlite.query(match<String> { it.contains("chat_id IS NULL") }, match<Array<out Any?>> { it.contentEquals(arrayOf<Any>("needle", 1)) }) }
        im = false
        manager.search("needle")
        verify { sqlite.query(any<String>(), match<Array<out Any?>> { it.contentEquals(arrayOf<Any>("needle", 0)) }) }
        val perspective = Conversation(assistantId = Uuid.random(), chatId = Uuid.random(), messageNodes = emptyList())
            .appendMessage(UIMessage.user("[A] searchable in professional mode"))
        manager.indexConversation(perspective)
        verify { sqlite.execSQL(match { it.startsWith("INSERT INTO message_fts") }, any<Array<out Any?>>()) }
    }
}
