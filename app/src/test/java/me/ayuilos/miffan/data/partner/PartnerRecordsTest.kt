package me.ayuilos.miffan.data.partner

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.ai.transformers.PartnerRecordsTransformer
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.data.revision.AssistantRevisionRecorder
import me.ayuilos.miffan.data.revision.Revision
import me.ayuilos.miffan.data.revision.RevisionAuthor
import me.ayuilos.miffan.data.revision.RevisionOrigin
import me.ayuilos.miffan.data.revision.RevisionRepository
import me.ayuilos.miffan.data.revision.RevisionService
import me.ayuilos.miffan.data.revision.RevisionStore
import me.ayuilos.miffan.data.revision.RevisionSubject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid

class PartnerRecordsTest {
    private val ann = Assistant(name = "Ann", systemPrompt = "Be kind.\nSpeak briefly.", standingToolApprovals = setOf("calendar_create", "workspace_write_file"))
    private val bob = Assistant(name = "Bob")
    private val annChat = Uuid.random()
    private val bobChat = Uuid.random()

    @Test
    fun aProfileChangeIsToldFieldByFieldWithoutSecrets() {
        val after = ann.copy(systemPrompt = "Be kind.\nBe funny.", standingToolApprovals = setOf("workspace_write_file"),
            enableWebSearch = !ann.enableWebSearch)
        val changes = describeProfileChange(ann, after, Settings())
        val personality = changes.single { it["field"]?.jsonPrimitive?.content == "personality" }
        assertEquals(listOf("Speak briefly."), (personality["removed"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf("Be funny."), (personality["added"] as JsonArray).map { it.jsonPrimitive.content })
        val told = changes.joinToString("; ") { renderChange(it) }
        assertTrue(told, "personality: removed \"Speak briefly.\", added \"Be funny.\"" in told)
        assertTrue(told, "must ask again before calendar_create" in told)
        assertTrue(told, "web search" in told)
        // A header is named only as "other settings"; its value never reaches the partner.
        val headers = describeProfileChange(ann, ann.copy(customHeaders = listOf(CustomHeader("Authorization", "Bearer sk-SECRET"))), Settings())
        assertEquals("other settings changed", renderChange(headers.single()))
        assertFalse("sk-SECRET" in headers.toString())
    }

    private class FakeStore : RevisionStore {
        val revisions = MutableStateFlow<List<Revision>>(emptyList())
        override suspend fun head(subject: RevisionSubject, subjectId: String) =
            revisions.value.lastOrNull { it.subject == subject && it.subjectId == subjectId }
        override suspend fun get(id: String) = revisions.value.firstOrNull { it.id == id }
        override suspend fun insert(revision: Revision) { revisions.value = revisions.value + revision }
        override suspend fun <T> transaction(block: suspend () -> T): T = block()
        override fun history(subject: RevisionSubject, subjectId: String): Flow<List<Revision>> =
            revisions.map { list -> list.filter { it.subject == subject && it.subjectId == subjectId }.reversed() }
        override fun byToolCallId(toolCallId: String) = revisions.map { list -> list.filter { it.toolCallId == toolCallId } }
        override fun byAuthor(author: RevisionAuthor, subjectIds: List<String>): Flow<List<Revision>> =
            revisions.map { list -> list.filter { it.author == author && it.subjectId in subjectIds } }
    }

    @Test
    fun outsideTheChatMeansTheOwnerOthersAndUndoNotItsOwnChanges() = runBlocking {
        var now = Instant.parse("2026-11-12T00:00:00Z")
        val revisions = RevisionRepository(FakeStore()) { now }
        suspend fun change(from: Assistant, to: Assistant, at: String, origin: RevisionOrigin? = null) {
            now = Instant.parse(at)
            val record = suspend {
                revisions.record(RevisionSubject.ASSISTANT, ann.id.toString(), AssistantRevisionRecorder.snapshot(from), AssistantRevisionRecorder.snapshot(to))
            }
            if (origin != null) withContext(origin) { record() } else record()
        }
        val own = ann.copy(name = "Annie")
        val byOwner = own.copy(systemPrompt = "Be kind.")
        val byBob = byOwner.copy(enableMemory = !byOwner.enableMemory)
        change(ann, own, "2026-11-12T01:00:00Z", RevisionOrigin(RevisionAuthor.AGENT, MessageRef(annChat, Uuid.random())))
        change(own, byOwner, "2026-11-12T02:00:00Z")
        change(byOwner, byBob, "2026-11-12T03:00:00Z", RevisionOrigin(RevisionAuthor.AGENT, MessageRef(bobChat, Uuid.random())))
        val service = mockk<RevisionService>()
        coEvery { service.undoneAt("c1") } returns Instant.parse("2026-11-12T04:00:00Z")
        coEvery { service.undoneAt("c2") } returns null
        val records = PartnerRecords(revisions, service, mockk(relaxed = true),
            assistantOf = { mapOf(annChat to ann.id, bobChat to bob.id)[it] },
            settings = { Settings(assistants = listOf(byBob, bob)) }, zone = { ZoneId.of("UTC") })
        val call = UIMessagePart.Tool("c1", "change_app_settings", """{"reason":"Back to light"}""", output = listOf(UIMessagePart.Text("{}")))
        val kept = UIMessagePart.Tool("c2", "change_app_settings", "{}", output = listOf(UIMessagePart.Text("{}")))

        val events = records.outsideEvents(ann.id, listOf(call, kept), Instant.parse("2026-11-12T00:30:00Z"))

        // Its own rename is in the chat already; the owner's edit, Bob's change and the undo are not.
        assertEquals(listOf("02:00", "03:00", "04:00"), events.map { records.time(it.at).takeLast(5) })
        assertTrue(events[0].text, events[0].text.startsWith("The owner changed your profile: personality: removed \"Speak briefly.\""))
        assertTrue(events[1].text, events[1].text.startsWith("Partner Bob changed your profile: memory"))
        assertEquals("The owner undid your change_app_settings call (\"Back to light\"): none of it is in effect any more.", events[2].text)
        // The full history keeps all three, newest first, its own marked as such.
        assertEquals(listOf("partner Bob", PartnerRecords.BY_OWNER, PartnerRecords.BY_YOU), records.profileChanges(ann.id, 10).map { it.by })
    }

    private fun at(minute: Int) = Instant.parse("2026-11-12T10:%02d:00Z".format(minute)).toKotlinInstant()
        .toLocalDateTime(TimeZone.currentSystemDefault())

    @Test
    fun eachOwnerMessageIsToldWhatHappenedSinceTheReplyBeforeIt() = runBlocking {
        fun message(role: MessageRole, minute: Int) = UIMessage(role = role, parts = listOf(UIMessagePart.Text("m$minute")), createdAt = at(minute))
        val messages = listOf(message(MessageRole.USER, 10), message(MessageRole.ASSISTANT, 11), message(MessageRole.USER, 20),
            message(MessageRole.ASSISTANT, 21), message(MessageRole.USER, 30))
        fun event(minute: Int) = OutsideEvent(Instant.parse("2026-11-12T10:%02d:00Z".format(minute)), "event $minute")
        val transformer = PartnerRecordsTransformer({ _, _, _ -> listOf(event(5), event(15), event(25), event(31)) }, { "t" })

        val out = transformer.withOutsideEvents(ann.id, messages)

        val notes = out.filter { it.isSynthetic }.map { (it.parts.single() as UIMessagePart.Text).text }
        assertEquals(2, notes.size)
        assertTrue("event 15" in notes[0] && "event 25" !in notes[0])
        assertTrue("event 25" in notes[1])
        // Before the first reply or after the newest message: not told here.
        assertFalse(notes.any { "event 5" in it || "event 31" in it })
        // Each note sits right before the message it belongs to.
        assertEquals(messages[2], out[out.indexOfFirst { it.isSynthetic } + 1])
        assertEquals(messages[4], out.last())
    }
}
