package me.ayuilos.miffan.data.ai.computer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteComputerControlTest {
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
