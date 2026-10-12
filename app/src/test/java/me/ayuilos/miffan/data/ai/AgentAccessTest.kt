package me.ayuilos.miffan.data.ai

import io.mockk.*
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.db.dao.MemoryDAO
import me.ayuilos.miffan.data.db.entity.MemoryEntity
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AgentAccessTest {
    @Test fun turnAuthorityUsesMessageOriginAndSelectsMemoryOwner() {
        val a = Assistant()
        val owner = Conversation(assistantId = a.id, messageNodes = emptyList()).appendMessage(UIMessage.user("owner"))
        assertTrue(AgentAccess.forTurn(a, owner).ownerInitiated)
        val partner = owner.appendMessage(UIMessage.user("partner"), origin = MessageOrigin.Partner(Uuid.random(), Uuid.random(), emptyList()))
        assertFalse(AgentAccess.forTurn(a, partner).ownerInitiated)
        assertEquals(a.id.toString(), AgentAccess.forTurn(a, partner).memoryOwnerId)
        assertEquals(MemoryRepository.GLOBAL_MEMORY_ID, AgentAccess.forTurn(a.copy(useGlobalMemory = true), partner).memoryOwnerId)
    }

    @Test fun memoryEditsAndDeletesHideForeignIdsAndRespectGlobalScopeWhileUiStaysUnrestricted() = runTest {
        val a = Assistant()
        val access = AgentAccess.forAssistant(a, true)
        val dao = mockk<MemoryDAO>()
        val repo = MemoryRepository(dao)
        val foreign = MemoryEntity(1, Uuid.random().toString(), "foreign")
        coEvery { dao.getMemoryById(1) } returns foreign
        coEvery { dao.getMemoryById(2) } returns null
        coEvery { dao.updateMemory(any()) } just Runs
        coEvery { dao.deleteMemory(any()) } just Runs
        fun failure(result: Result<*>): Pair<Class<out Throwable>?, String?> =
            result.exceptionOrNull()?.let { it.javaClass to it.message } ?: (null to null)
        val editForeign = failure(runCatching { repo.updateContent(1, "changed", access) })
        val editAbsent = failure(runCatching { repo.updateContent(2, "changed", access) })
        assertEquals(editForeign.first, editAbsent.first)
        assertEquals(editForeign.second?.replace("#1", "#id"), editAbsent.second?.replace("#2", "#id"))
        val deleteForeign = failure(runCatching { repo.deleteMemory(1, access) })
        val deleteAbsent = failure(runCatching { repo.deleteMemory(2, access) })
        assertEquals(deleteForeign.first, deleteAbsent.first)
        assertEquals(deleteForeign.second?.replace("#1", "#id"), deleteAbsent.second?.replace("#2", "#id"))
        coEvery { dao.getMemoryById(1) } returns null
        assertEquals(editForeign, failure(runCatching { repo.updateContent(1, "changed", access) }))
        assertEquals(deleteForeign, failure(runCatching { repo.deleteMemory(1, access) }))
        coEvery { dao.getMemoryById(1) } returns foreign
        coVerify(exactly = 0) { dao.updateMemory(any()); dao.deleteMemory(any()) }

        coEvery { dao.getMemoryById(3) } returns MemoryEntity(3, access.memoryOwnerId, "own")
        repo.updateContent(3, "own updated", access)
        repo.deleteMemory(3, access)
        val global = AgentAccess.forAssistant(a.copy(useGlobalMemory = true), true)
        coEvery { dao.getMemoryById(4) } returns MemoryEntity(4, MemoryRepository.GLOBAL_MEMORY_ID, "global")
        repo.updateContent(4, "global updated", global)
        repo.deleteMemory(4, global)
        assertTrue(runCatching { repo.updateContent(3, "denied", global) }.isFailure)
        assertTrue(runCatching { repo.deleteMemory(4, access) }.isFailure)
        // Direct UI ownership is intentionally unrestricted, including missing delete's old no-op.
        repo.updateContent(1, "owner edit")
        repo.deleteMemory(1)
        assertNull(repo.deleteMemory(2))
        coVerify { dao.updateMemory(foreign.copy(content = "owner edit")); dao.deleteMemory(1) }
    }
}
