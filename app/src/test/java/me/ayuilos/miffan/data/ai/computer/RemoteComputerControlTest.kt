package me.ayuilos.miffan.data.ai.computer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteComputerControlTest {
    @Test
    fun recordsOnlyRealUserTransitions() = runBlocking {
        val events = mutableListOf<Pair<String, Boolean>>()
        val control = RemoteComputerControl { host, taken -> events += host to taken }
        control.userHandsBack("host")
        control.userTakesOver("host")
        control.userTakesOver("host")
        control.userHandsBack("host")
        control.userHandsBack("host")
        control.partnerActs("host") { control.userTakesOver("host") }
        control.userHandsBack("host")
        assertEquals(listOf("host" to true, "host" to false, "host" to true, "host" to false), events)
    }

    @Test
    fun partnerIsMarkedWhileActingAndReleasedAfter() = runBlocking {
        val control = RemoteComputerControl()
        val seen = control.partnerActs("host") { control.controller("host") }
        assertEquals(RemoteController.PARTNER, seen)
        assertEquals(RemoteController.IDLE, control.controller("host"))
    }

    @Test
    fun userTakeoverRefusesPartnerUntilHandedBack() = runBlocking {
        val control = RemoteComputerControl()
        control.userTakesOver("host")
        assertNull(control.partnerActs("host") { "acted" })
        control.userHandsBack("host")
        assertEquals("acted", control.partnerActs("host") { "acted" })
    }

    @Test
    fun takeoverDuringAnActionIsNotOverwrittenWhenItEnds() = runBlocking {
        val control = RemoteComputerControl()
        control.partnerActs("host") { control.userTakesOver("host") }
        assertEquals(RemoteController.USER, control.controller("host"))
        assertEquals(RemoteController.IDLE, control.controller("other"))
    }
}
