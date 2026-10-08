package me.ayuilos.miffan.data.db.dao

import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.entity.AuditEventEntity
import org.junit.Assert.*
import org.junit.Test

class AuditDAOTest : ImDatabaseTestSupport() {
    private fun event(id: String, partner: String? = null, host: String? = null, kind: String = "APPROVAL", at: Long = 1) =
        AuditEventEntity(id, at, kind, assistantId = partner, toolCallId = if (kind == "APPROVAL") id else null, hostId = host, summary = id)

    @Test fun filtersSharedScreensAndClearDoesNotDeleteOtherPartners() = runBlocking {
        val dao = database.auditDao()
        dao.record(event("a", "p", at = 1))
        dao.record(event("b", "other", at = 2))
        dao.record(event("s", host = "h", kind = "SCREEN_TAKEN_OVER", at = 3))
        dao.record(event("t", host = "other", kind = "SCREEN_HANDED_BACK", at = 4))
        assertEquals(listOf("s", "a"), dao.observe("p", setOf("h"), 10).awaitValue().map { it.id })
        assertEquals(listOf("a"), dao.observe("p", emptySet(), 10).awaitValue().map { it.id })
        assertEquals(listOf("t", "s"), dao.observe(null, emptySet(), 2).awaitValue().map { it.id })
        dao.clear("p")
        assertEquals(listOf("t", "s", "b"), dao.observe(null, emptySet(), 10).awaitValue().map { it.id })
        dao.clear(null)
        assertTrue(dao.observe(null, emptySet(), 10).awaitValue().isEmpty())
    }

    @Test fun duplicateDecisionDoesNotReplaceFirst() = runBlocking {
        val dao = database.auditDao()
        dao.record(event("a", "p").copy(toolCallId = "same", decision = "DECLINED"))
        dao.record(event("b", "p", at = 2).copy(toolCallId = "same", decision = "ALLOWED"))
        val rows = dao.observe(null, emptySet(), 10).awaitValue()
        assertEquals(1, rows.size)
        assertEquals("DECLINED", rows.single().decision)
    }
}
