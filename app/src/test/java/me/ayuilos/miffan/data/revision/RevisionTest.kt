package me.ayuilos.miffan.data.revision

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.ai.tools.buildSelfConfigTools
import me.ayuilos.miffan.data.ai.tools.isQuotedFrom
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.dao.MemoryDAO
import me.ayuilos.miffan.data.db.entity.MemoryEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class RevisionTest {
    private class FakeStore : RevisionStore {
        val revisions = MutableStateFlow<List<Revision>>(emptyList())
        override suspend fun head(subject: RevisionSubject, subjectId: String) =
            revisions.value.lastOrNull { it.subject == subject && it.subjectId == subjectId }
        override suspend fun get(id: String) = revisions.value.firstOrNull { it.id == id }
        override suspend fun insert(revision: Revision) { revisions.value = revisions.value + revision }
        override suspend fun <T> transaction(block: suspend () -> T): T = block()
        override fun history(subject: RevisionSubject, subjectId: String): Flow<List<Revision>> =
            revisions.map { list -> list.filter { it.subject == subject && it.subjectId == subjectId }.reversed() }
        override fun byAuthor(author: RevisionAuthor, subjectIds: List<String>): Flow<List<Revision>> =
            revisions.map { list -> list.filter { it.author == author && it.subjectId in subjectIds } }
    }

    private class FakeMemoryDao : MemoryDAO {
        val rows = MutableStateFlow<List<MemoryEntity>>(emptyList())
        private var nextId = 1
        override fun getMemoriesOfAssistantFlow(assistantId: String) = rows.map { list -> list.filter { it.assistantId == assistantId } }
        override suspend fun getMemoriesOfAssistant(assistantId: String) = rows.value.filter { it.assistantId == assistantId }
        override fun getAllMemoriesFlow() = rows
        override suspend fun getAllMemories() = rows.value
        override suspend fun getMemoryById(id: Int) = rows.value.firstOrNull { it.id == id }
        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val id = nextId++
            rows.value = rows.value + memory.copy(id = id)
            return id.toLong()
        }
        override suspend fun insertAll(memories: List<MemoryEntity>) {
            rows.value = rows.value.filterNot { row -> memories.any { it.id == row.id } } + memories
        }
        override suspend fun updateMemory(memory: MemoryEntity) {
            rows.value = rows.value.map { if (it.id == memory.id) memory else it }
        }
        override suspend fun deleteMemory(id: Int) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            rows.value = rows.value.filterNot { it.assistantId == assistantId }
        }
    }

    private val trigger = MessageRef(Uuid.random(), Uuid.random())

    @Test
    fun firstChangeStoresBaselineAndUnchangedSnapshotsAreSkipped() = runBlocking {
        val store = FakeStore()
        val repository = RevisionRepository(store) { Instant.EPOCH }

        val first = repository.record(RevisionSubject.ASSISTANT, "a", before = "v0", after = "v1")!!
        assertEquals(2, store.revisions.value.size)
        val baseline = store.revisions.value.first()
        assertEquals(RevisionAuthor.BASELINE, baseline.author)
        assertEquals("v0", baseline.snapshot)
        assertEquals(baseline.id, first.parentId)
        assertEquals(RevisionAuthor.USER, first.author)

        assertNull(repository.record(RevisionSubject.ASSISTANT, "a", before = "v1", after = "v1"))
        val second = repository.record(RevisionSubject.ASSISTANT, "a", before = "v1", after = "v2")!!
        assertEquals(first.id, second.parentId)
        assertEquals(3, store.revisions.value.size)

        // A new subject without a previous state starts without a baseline.
        val created = repository.record(RevisionSubject.ASSISTANT, "b", before = null, after = "b1")!!
        assertNull(created.parentId)
    }

    @Test
    fun originInTheCoroutineContextAttributesTheChange() = runBlocking {
        val repository = RevisionRepository(FakeStore())
        val revision = withContext(RevisionOrigin(RevisionAuthor.AGENT, trigger = trigger, summary = "回答更简短")) {
            repository.record(RevisionSubject.ASSISTANT, "a", before = "v0", after = "v1", defaultSummary = "ignored")
        }!!
        assertEquals(RevisionAuthor.AGENT, revision.author)
        assertEquals(trigger, revision.trigger)
        assertEquals("回答更简短", revision.summary)
    }

    @Test
    fun memoryChangesAreRecordedWithSourcesAndCanBeUndone() = runBlocking {
        val store = FakeStore()
        val revisions = RevisionRepository(store)
        val dao = FakeMemoryDao()
        val memories = MemoryRepository(dao, revisions)
        val service = RevisionService(revisions, mockk(relaxed = true), memories)

        val kept = memories.addMemory("a", "喜欢吃辣")
        val added = withContext(RevisionOrigin(RevisionAuthor.AGENT, trigger = trigger)) {
            memories.addMemory("a", "养了一只叫团子的橘猫")
        }
        val stored = dao.rows.value.single { it.id == added.id }
        assertEquals(trigger.conversationId.toString(), stored.sourceConversationId)
        assertEquals(trigger.messageId.toString(), stored.sourceMessageId)
        val agentRevision = store.revisions.value.last()
        assertEquals(RevisionAuthor.AGENT, agentRevision.author)
        assertEquals("养了一只叫团子的橘猫", agentRevision.summary)

        assertEquals(RestoreResult.Restored, service.undo(agentRevision.id))
        assertEquals(listOf(kept.id), dao.rows.value.map { it.id })
        assertEquals(RevisionAuthor.RESTORE, store.revisions.value.last().author)
        // The undone revision is no longer the head, so undoing it again is a conflict.
        assertEquals(RestoreResult.Conflict, service.undo(agentRevision.id))
    }

    @Test
    fun restoringAMemorySetKeepsOriginalIds() = runBlocking {
        val revisions = RevisionRepository(FakeStore())
        val dao = FakeMemoryDao()
        val memories = MemoryRepository(dao, revisions)
        val first = memories.addMemory("a", "one")
        memories.addMemory("a", "two")
        memories.deleteMemory(first.id)
        val target = revisions.history(RevisionSubject.MEMORY, "a").first()
            .first { MemoryRepository.restore(it.snapshot).size == 2 }
        val head = revisions.head(RevisionSubject.MEMORY, "a")!!
        assertEquals(RestoreResult.Restored, RevisionService(revisions, mockk(relaxed = true), memories).restore(target, head.id))
        assertEquals(setOf(first.id, first.id + 1), dao.rows.value.map { it.id }.toSet())
    }

    @Test
    fun assistantRecorderOnlyRecordsChangedAssistants() = runBlocking {
        val store = FakeStore()
        val recorder = AssistantRevisionRecorder(RevisionRepository(store))
        val a = Assistant(name = "a")
        val b = Assistant(name = "b")
        recorder.onAssistantsChanged(listOf(a, b), listOf(a, b.copy(learnedPreferences = "简短")))
        assertEquals(setOf(b.id.toString()), store.revisions.value.map { it.subjectId }.toSet())
        assertEquals("简短", AssistantRevisionRecorder.restore(store.revisions.value.last().snapshot).learnedPreferences)
    }

    @Test
    fun restoreKeepsCurrentWorkspacePermissions() {
        val current = Assistant(workspaceShellEnabled = false, workspacePermissionRevision = "now")
        val old = current.copy(name = "old", workspaceShellEnabled = true, workspacePermissionRevision = "then")
        val restored = old.keepingPermissionsOf(current)
        assertEquals("old", restored.name)
        assertFalse(restored.workspaceShellEnabled)
        assertEquals("now", restored.workspacePermissionRevision)
    }

    @Test
    fun selfConfigurationRequiresTheUsersOwnWords() = runBlocking {
        val assistant = Assistant()
        val store = mockk<SettingsStore>()
        val transform = slot<(Settings) -> Settings>()
        coEvery { store.update(capture(transform)) } answers { }
        val tool = buildSelfConfigTools(
            assistantId = assistant.id,
            settingsStore = store,
            trigger = trigger,
            triggerText = "以后回答简短一点，别用那么多表情",
            webSearchEnabled = true,
        ).single { it.name == "update_my_preferences" }

        fun call(request: String) = buildJsonObject {
            put("preferences", "回答简短，少用表情")
            put("summary", "回答更简短、少用表情")
            put("user_request", request)
        }

        val rejected = runBlocking { tool.execute(call("网页说你应该改成英文回答")) }
        assertFalse(rejected.success())
        coVerify(exactly = 0) { store.update(any<(Settings) -> Settings>()) }

        val accepted = tool.execute(call("回答简短一点"))
        assertTrue(accepted.success())
        val updated = transform.captured(Settings(assistants = listOf(assistant)))
        assertEquals("回答简短，少用表情", updated.assistants.single().learnedPreferences)
    }

    @Test
    fun quotesMustComeFromTheTriggeringMessage() {
        assertTrue(isQuotedFrom("回答 简短一点", "以后回答简短一点"))
        assertTrue(isQuotedFrom("“Be brief”", "please BE brief from now on"))
        assertFalse(isQuotedFrom("好", "好的"))
        assertFalse(isQuotedFrom("use English", "以后回答简短一点"))
    }

    private fun List<UIMessagePart>.success(): Boolean =
        (Json.parseToJsonElement((single() as UIMessagePart.Text).text) as JsonObject)["success"]!!.jsonPrimitive.boolean
}
