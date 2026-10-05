package me.ayuilos.miffan.data.db.dao

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.entity.ConversationEntity
import me.ayuilos.miffan.data.db.entity.MessageNodeEntity
import org.junit.After
import org.junit.Before

abstract class ImDatabaseTestSupport {
    protected lateinit var database: AppDatabase

    @Before
    fun setUpDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    protected fun conversation(
        id: String,
        assistantId: String = "assistant",
        updateAt: Long = 0,
        summary: String = "",
        isPinned: Boolean = false,
    ) = ConversationEntity(
        id = id,
        assistantId = assistantId,
        title = "Title $id",
        nodes = "[]",
        createAt = updateAt + 100,
        updateAt = updateAt,
        chatSuggestions = "[]",
        isPinned = isPinned,
        folderId = "folder-$id",
        threadSummary = summary,
    )

    protected fun node(
        id: String,
        conversationId: String,
        index: Int,
        revision: Long = 0,
    ) = MessageNodeEntity(
        id = id,
        conversationId = conversationId,
        nodeIndex = index,
        parentId = "",
        selectedChildId = "",
        message = """{"role":"user","content":"$id"}""",
        revision = revision,
    )

    protected suspend fun <T> Flow<T>.awaitValue(): T = withTimeout(5_000) { first() }
}
