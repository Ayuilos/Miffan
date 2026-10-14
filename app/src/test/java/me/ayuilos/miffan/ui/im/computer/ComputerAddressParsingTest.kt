package me.ayuilos.miffan.ui.im.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerAddressParsingTest {
    @Test
    fun `plain hosts are left alone`() {
        assertNull(parseSshTarget("192.168.1.5"))
        assertNull(parseSshTarget("macbook.local"))
        assertNull(parseSshTarget("fe80::1"))
        assertNull(parseSshTarget("my host"))
        assertNull(parseSshTarget(""))
    }

    @Test
    fun `user host and port are split`() {
        assertEquals(SshTarget("macbook.local", "ekk"), parseSshTarget("ekk@macbook.local"))
        assertEquals(SshTarget("10.0.0.2", "ekk", 2222), parseSshTarget(" ekk@10.0.0.2:2222 "))
        assertEquals(SshTarget("server", null, 2222), parseSshTarget("server:2222"))
        assertEquals(SshTarget("fd7a::1", "ekk", 22), parseSshTarget("ekk@[fd7a::1]:22"))
        assertEquals(SshTarget("nas", "admin", 2200), parseSshTarget("ssh://admin@nas:2200/"))
    }

    @Test
    fun `ssh command lines are read`() {
        assertEquals(SshTarget("box.ts.net", "ekk", 2222), parseSshTarget("ssh -p 2222 ekk@box.ts.net"))
        assertEquals(SshTarget("box", "root", 22), parseSshTarget("ssh -i ~/.ssh/id -l root -p22 box"))
        assertEquals(SshTarget("box", "ekk"), parseSshTarget("ssh -A ekk@box"))
        assertNull(parseSshTarget("ssh box"))
    }

    @Test
    fun `default names drop the domain but keep addresses`() {
        assertEquals("macbook", defaultComputerName("macbook.local"))
        assertEquals("box", defaultComputerName("box.tail1234.ts.net"))
        assertEquals("192.168.1.5", defaultComputerName("192.168.1.5"))
        assertEquals("fd7a::1", defaultComputerName("fd7a::1"))
    }

    @Test
    fun `the key command appends only once and prints the user`() {
        val command = computerPublicKeyCommand("ssh-ed25519 AAAA miffan")
        assertEquals(true, command.contains("grep -qxF 'ssh-ed25519 AAAA miffan' ~/.ssh/authorized_keys ||"))
        assertEquals(true, command.endsWith("&& whoami"))
    }

    @Test
    fun `full-width punctuation from Chinese keyboards becomes ASCII`() {
        assertEquals("10.0.2.2", normalizeAddressInput("10。0。2。2"))
        assertEquals("ekk@box:22", normalizeAddressInput("ekk＠box：２２"))
    }
}
