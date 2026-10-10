package me.ayuilos.miffan.data.ai.computer

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteComputerControlTest {
    @Test fun partnerRefusedWhileUserHoldsControlIsWaitingUntilHandBack() = runTest {
        val control = RemoteComputerControl()
        assertEquals("ok", control.partnerActs("host") { "ok" })
        assertTrue(control.waiting.value.isEmpty())

        control.userTakesOver("host")
        assertTrue("taking over alone is not a reason to tell the user", control.waiting.value.isEmpty())
        assertNull(control.partnerActs("host") { "blocked" })
        assertEquals(setOf("host"), control.waiting.value)
        assertTrue(control.waiting.value.contains("host") && "other" !in control.waiting.value)

        control.userHandsBack("host")
        assertTrue(control.waiting.value.isEmpty())
        assertEquals("again", control.partnerActs("host") { "again" })
    }
}
