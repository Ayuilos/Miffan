package me.ayuilos.miffan.ui.im

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ImErrorsTest {
    @Test
    fun `provider answers map to what the owner can do`() {
        assertEquals(ImProblem.Connection, imProblemOf("HTTP 401 Unauthorized"))
        assertEquals(ImProblem.Balance, imProblemOf("429 insufficient_quota"))
        assertEquals(ImProblem.Busy, imProblemOf("429 Too Many Requests: rate limit"))
        assertEquals(ImProblem.ContentRule, imProblemOf("Your request was rejected by the safety system"))
        assertEquals(ImProblem.Network, imProblemOf(IOException("timeout")))
        assertEquals(ImProblem.Other, imProblemOf("something odd"))
        assertEquals(ImProblem.Other, imProblemOf(null as String?))
    }

    @Test
    fun `only service problems lead to the service page`() {
        assertTrue(ImProblem.Connection.opensService && ImProblem.Balance.opensService)
        assertEquals(listOf(ImProblem.Connection, ImProblem.Balance), ImProblem.entries.filter { it.opensService })
    }
}
