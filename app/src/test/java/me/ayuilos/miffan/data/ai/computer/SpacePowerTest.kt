package me.ayuilos.miffan.data.ai.computer

import org.junit.Assert.assertEquals
import org.junit.Test

class SpacePowerTest {
    private fun probe(vararg spaces: Pair<String, String?>) =
        CuaSpaceProbe(spaces = spaces.map { (id, state) -> CuaSpaceProbe.Entry(id, state) })

    @Test fun aRunningContainerIsAwake() {
        assertEquals(SpacePower.AWAKE, spacePowerOf(probe("local:miffan-0123abcd" to "running"), "miffan-0123abcd"))
    }

    @Test fun stoppedPausedAndExitedContainersAreAsleep() {
        listOf("stopped", "paused", "exited", "Stopped").forEach {
            assertEquals(it, SpacePower.ASLEEP, spacePowerOf(probe("local:miffan-0123abcd" to it), "miffan-0123abcd"))
        }
    }

    @Test fun whatTheHostDoesNotListIsNotClaimedEitherWay() {
        // The daemon is off: the host lists nothing, and that says nothing about the container.
        assertEquals(SpacePower.UNKNOWN, spacePowerOf(probe(), "miffan-0123abcd"))
        assertEquals(SpacePower.UNKNOWN, spacePowerOf(probe("local:miffan-ffffffff" to "running"), "miffan-0123abcd"))
        assertEquals(SpacePower.UNKNOWN, spacePowerOf(probe("local:miffan-0123abcd" to null), "miffan-0123abcd"))
        assertEquals(SpacePower.UNKNOWN, spacePowerOf(probe("local:miffan-0123abcd" to "restarting"), "miffan-0123abcd"))
    }

    @Test fun anotherSpaceWhoseNameEndsTheSameIsNotMistaken() {
        assertEquals(SpacePower.UNKNOWN, spacePowerOf(probe("local:xmiffan-0123abcd" to "running"), "miffan-0123abcd"))
    }
}
