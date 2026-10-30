package me.ayuilos.miffan.data.ai.tools

import me.ayuilos.miffan.data.ai.computer.CuaSpaceNetcheck
import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceNetworkFixCommandTest {
    @Test fun sharedSpacesOpenOnlyTheSharePort() {
        val check = CuaSpaceNetcheck(hint = "ufw", via = "share", proxy = "http://172.17.0.1:32190", bridge = "docker0")
        assertEquals("sudo ufw allow in on docker0 to 172.17.0.1 port 32190 proto tcp", spaceNetworkFixCommand(check))
    }

    @Test fun anythingElseKeepsTheForwardRule() {
        assertEquals(SPACE_NETWORK_FIX_COMMAND, spaceNetworkFixCommand(null))
        assertEquals(SPACE_NETWORK_FIX_COMMAND, spaceNetworkFixCommand(CuaSpaceNetcheck(hint = "ufw", via = "direct")))
        // Nothing the host printed reaches the command unless it is a plain interface name and address.
        for ((proxy, bridge) in listOf(
            "http://172.17.0.1:32190" to "docker0; rm -rf ~",
            "http://172.17.0.1:32190 && id" to "docker0",
            "http://host:32190" to "docker0",
            "http://172.17.0.1:32190" to "a-very-long-interface",
        )) {
            assertEquals(SPACE_NETWORK_FIX_COMMAND,
                spaceNetworkFixCommand(CuaSpaceNetcheck(hint = "ufw", via = "share", proxy = proxy, bridge = bridge)))
        }
    }
}
