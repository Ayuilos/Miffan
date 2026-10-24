package me.ayuilos.miffan.data.ai.computer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.ai.tools.busyRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteComputerControlTest {
    private val a = ComputerHolder("assistant-a", "conversation-a", "Ann")
    private val b = ComputerHolder("assistant-b", "conversation-b", "Bob")
    private var now = 1_000_000L
    private val control = RemoteComputerControl(clock = { now })

    @Test fun anUpdateWaitsForThePartnerTurnAndKeepsPartnersOffUntilItEnds() = runTest {
        assertEquals(PartnerActResult.Done("ok"), control.partnerActs("host", a) { "ok" })
        assertEquals("a turn using the computer refuses the update", a, control.beginUpdate("host"))
        assertEquals(a, control.partnerUsing("host"))

        control.release("conversation-a")
        assertEquals(null, control.partnerUsing("host"))
        assertEquals(null, control.beginUpdate("host"))
        assertEquals(PartnerActResult.Updating, control.partnerActs("host", b) { "blocked" })

        control.endUpdate("host")
        assertEquals(PartnerActResult.Done("after"), control.partnerActs("host", b) { "after" })
    }

    @Test fun anIdleHolderDoesNotHoldUpAnUpdate() = runTest {
        assertEquals(PartnerActResult.Done("ok"), control.partnerActs("host", a) { "ok" })
        now += 90_001L
        assertEquals(null, control.partnerUsing("host"))
        assertEquals(null, control.beginUpdate("host"))
    }

    @Test fun partnerRefusedWhileUserHoldsControlIsWaitingUntilHandBack() = runTest {
        assertEquals(PartnerActResult.Done("ok"), control.partnerActs("host", a) { "ok" })
        assertTrue(control.waiting.value.isEmpty())

        control.userTakesOver("host")
        assertTrue("taking over alone is not a reason to tell the user", control.waiting.value.isEmpty())
        assertEquals(PartnerActResult.UserHolds, control.partnerActs("host", a) { "blocked" })
        assertEquals(setOf("host"), control.waiting.value)

        control.userHandsBack("host")
        assertTrue(control.waiting.value.isEmpty())
        assertEquals(PartnerActResult.Done("again"), control.partnerActs("host", a) { "again" })
    }

    @Test fun anotherTurnIsRefusedForTheWholeTurnNotJustOneAction() = runTest {
        val clicking = CompletableDeferred<Unit>()
        val first = async { control.partnerActs("host", a) { clicking.await(); "clicked" } }
        runCurrent()
        assertEquals(RemoteController.PARTNER, control.controller("host"))
        assertEquals(PartnerActResult.Busy(a), control.partnerActs("host", b) { "typed" })

        clicking.complete(Unit)
        assertEquals(PartnerActResult.Done("clicked"), first.await())
        assertEquals("between actions nobody is acting", RemoteController.IDLE, control.controller("host"))
        assertEquals(mapOf("host" to a), control.holders.value)
        assertEquals("the first turn still holds the desktop", PartnerActResult.Busy(a), control.partnerActs("host", b) { "typed" })
        assertEquals(PartnerActResult.Done("typed"), control.partnerActs("other-host", b) { "typed" })

        control.release("conversation-a")
        assertEquals(PartnerActResult.Done("typed"), control.partnerActs("host", b) { "typed" })
        assertEquals(b, control.holders.value["host"])
    }

    @Test fun oneTurnMayRunParallelActions() = runTest {
        val gate = CompletableDeferred<Unit>()
        val first = async { control.partnerActs("host", a) { gate.await(); 1 } }
        runCurrent()
        assertEquals(PartnerActResult.Done(2), control.partnerActs("host", a.copy(name = "Renamed")) { 2 })
        assertEquals("the other action is still running", RemoteController.PARTNER, control.controller("host"))
        gate.complete(Unit)
        assertEquals(PartnerActResult.Done(1), first.await())
        assertEquals(RemoteController.IDLE, control.controller("host"))
    }

    @Test fun idleHolderGivesWayButNeverMidAction() = runTest {
        control.partnerActs("host", a) { }
        now += 89_000
        assertEquals(PartnerActResult.Busy(a), control.partnerActs("host", b) { })
        now += 1_000
        assertEquals(PartnerActResult.Done(Unit), control.partnerActs("host", b) { })
        assertEquals(b, control.holders.value["host"])

        val slow = CompletableDeferred<Unit>()
        val running = async { control.partnerActs("host", b) { slow.await() } }
        runCurrent()
        now += 10 * 60_000
        assertEquals("a long action is not idleness", PartnerActResult.Busy(b), control.partnerActs("host", a) { })
        slow.complete(Unit)
        running.await()
    }

    @Test fun handingBackResumesTheHolderEvenAfterALongTakeover() = runTest {
        control.partnerActs("host", a) { }
        control.userTakesOver("host")
        assertEquals(PartnerActResult.UserHolds, control.partnerActs("host", b) { })
        now += 10 * 60_000
        control.userHandsBack("host")
        assertEquals(PartnerActResult.Busy(a), control.partnerActs("host", b) { })
        assertEquals(PartnerActResult.Done(Unit), control.partnerActs("host", a) { })
    }

    @Test fun failedActionStillEndsAndSameAssistantElsewhereIsAnotherTurn() = runTest {
        runCatching { control.partnerActs("host", a) { error("driver died") } }
        assertEquals(RemoteController.IDLE, control.controller("host"))
        val elsewhere = a.copy(conversationId = "group-perspective")
        val refused = control.partnerActs("host", elsewhere) { } as PartnerActResult.Busy

        val reason = { holder: ComputerHolder, me: ComputerHolder ->
            Json.parseToJsonElement(busyRefusal(holder, me, "cachyos")).jsonObject["reason"]!!.jsonPrimitive.content
        }
        assertEquals("You are already operating cachyos in another conversation.", reason(refused.holder, elsewhere))
        assertEquals("Ann is operating cachyos right now.", reason(a, b))
        assertEquals("Another partner is operating cachyos right now.", reason(a.copy(name = ""), b))

        control.release("conversation-a")
        assertTrue(control.holders.value.isEmpty())
    }
}
