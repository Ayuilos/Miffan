package me.ayuilos.miffan.data.partner

import me.ayuilos.miffan.data.ai.AgentAccess
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

    @Test fun searchScopesPerspectivesByModeButAlwaysIndexesThem() = runTest {
        val sqlite = mockk<SupportSQLiteDatabase>(relaxed = true)
        val database = mockk<AppDatabase>()
        val cursor = mockk<Cursor>(relaxed = true)
        every { database.openHelper.writableDatabase } returns sqlite
        every { sqlite.query(any<String>(), any<Array<out Any?>>()) } returns cursor
        every { cursor.moveToNext() } returns false
        var im = true
        val manager = MessageFtsManager(database) { im }
        val caller = Uuid.random()
        val access = AgentAccess(caller, true, caller.toString())
        val queries = mutableListOf<Array<out Any?>>()
        every { sqlite.query(match<String> { "FROM message_fts" in it }, capture(queries)) } returns cursor
        // English keywords go through the simple extension's query builder first.
        val simpleQuery = mockk<Cursor>(relaxed = true)
        every { simpleQuery.moveToFirst() } returns true
        every { simpleQuery.getString(0) } returns "needle"
        every { sqlite.query(match<String> { it.startsWith("SELECT simple_query") }, any<Array<out Any?>>()) } returns simpleQuery
        manager.search("needle")
        org.junit.Assert.assertNull(queries.last()[1])
        for (mode in listOf(false, true)) {
            im = mode
            manager.search("needle", access = access)
            org.junit.Assert.assertEquals(caller.toString(), queries.last()[1])
            org.junit.Assert.assertEquals(caller.toString(), queries.last()[2])
            org.junit.Assert.assertEquals(1, queries.last()[3])
            org.junit.Assert.assertEquals(if (mode) 1 else 0, queries.last()[4])
            manager.search("needle", access = access.copy(ownerInitiated = false))
            org.junit.Assert.assertEquals(0, queries.last()[3])
        }
        val perspective = Conversation(assistantId = Uuid.random(), chatId = Uuid.random(), messageNodes = emptyList())
            .appendMessage(UIMessage.user("[A] searchable by this partner in IM"))
        manager.indexConversation(perspective)
        verify { sqlite.execSQL(match { it.startsWith("INSERT INTO message_fts") }, any<Array<out Any?>>()) }
    }
}
