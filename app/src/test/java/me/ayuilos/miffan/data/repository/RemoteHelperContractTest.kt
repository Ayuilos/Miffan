package me.ayuilos.miffan.data.repository

import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import me.rerere.workspace.RemoteChannelStream
import org.junit.Assert.*
import org.junit.Test

/** Every shared output sample must have an explicit parser and semantic expectations. */
class RemoteHelperContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `all helper fixtures satisfy Kotlin consumers`() {
        val root = File(requireNotNull(System.getProperty("remoteHelper.fixtures")))
        val files = root.walkTopDown().filter { it.extension == "json" }.toList()
        val expected = mapOf(
            "sh" to "probe-wayland probe-x11 probe-macos rdp-gnome rdp-kde rdp-already-configured " +
                "rdp-keyring-locked rdp-credential-unavailable rdp-failed rdp-no-server " +
                "vnc-unavailable vnc-no-session vnc-x11 vnc-wayland vnc-macos " +
                "sunshine-stopped sunshine-running sunshine-busy sunshine-missing sunshine-macos " +
                "sunshine-macos-locked sunshine-start sunshine-encryption sunshine-refused sunshine-wake " +
                "sunshine-settings sunshine-unpair sunshine-unpair-none " +
                "sunshine-pair sunshine-pair-replaced sunshine-pair-invalid sunshine-pair-no-permission " +
                "sunshine-pair-restart-failed sunshine-pair-state-unrecognized",
            "ps1" to "probe sunshine-running sunshine-busy sunshine-missing sunshine-start sunshine-encryption " +
                "sunshine-pair sunshine-pair-replaced sunshine-unpair sunshine-unpair-none firewall-check firewall-review firewall-result " +
                "clipboard clipboard-failed clipboard-serve clipboard-serve-failed",
        ).flatMap { (platform, names) -> names.split(" ").map { "$platform/$it.json" } }.toSet()
        assertEquals("Shared fixture inventory changed", expected, files.map { it.relativeTo(root).invariantSeparatorsPath }.toSet())
        for (file in files) {
            val name = file.nameWithoutExtension
            val windows = file.parentFile?.name == "ps1"
            val output = file.readText()
            when {
                name.startsWith("probe") -> parseHelperProbe(output).let {
                    assertEquals(file.path, if (windows) 13 else 31, it.helper)
                    assertEquals(if (windows) "windows" else if (name == "probe-macos") "macos" else "linux", it.os)
                    assertEquals("Fixture Desktop", it.hostname)
                    assertTrue(it.session.present)
                    assertEquals("0.34.0", it.cua.min)
                    assertEquals(!windows, it.cua.ok)
                    assertEquals(if (windows) "console" else if (name == "probe-macos") "quartz" else if (name == "probe-x11") "x11" else "wayland", it.session.type)
                }
                name.startsWith("rdp-") -> {
                    val error = when (name) {
                        "rdp-already-configured" -> RemoteScreenProblem.RDP_ALREADY_CONFIGURED
                        "rdp-keyring-locked" -> RemoteScreenProblem.RDP_KEYRING_LOCKED
                        "rdp-credential-unavailable" -> RemoteScreenProblem.RDP_CREDENTIAL_SETUP_UNAVAILABLE
                        "rdp-failed" -> RemoteScreenProblem.RDP_START_FAILED
                        "rdp-no-server" -> RemoteScreenProblem.NO_RDP_SERVER
                        else -> null
                    }
                    if (error != null) {
                        assertEquals(error, assertThrows(RemoteScreenUnavailableException::class.java) { parseRdpStart(output) }.problem)
                    } else parseRdpStart(output).let {
                        assertEquals(if (name == "rdp-kde") "krdp" else "gnome-remote-desktop", it.server)
                        assertEquals(45891, it.port)
                        assertEquals("miffan-1001", it.username)
                        assertEquals("ab".repeat(32), it.certificateSha256)
                        assertEquals(if (name == "rdp-kde") "user" else "headless", it.mode)
                    }
                }
                name.startsWith("vnc-") -> json.decodeFromString<RemoteMachineProbe.Vnc>(output).let {
                    if (name == "vnc-macos") {
                        assertEquals("macos-screen-sharing", it.server); assertTrue(it.running); assertEquals("tcp:5900", it.endpoint)
                    } else if (name == "vnc-x11") {
                        assertEquals("x11vnc", it.server); assertTrue(it.running)
                        assertEquals("tcp:45891", it.endpoint); assertEquals("fixture8", it.password)
                    } else if (name == "vnc-wayland") {
                        assertEquals("wayvnc", it.server); assertTrue(it.running)
                        assertEquals("unix:/fixture/miffan/vnc.sock", it.endpoint)
                    } else if (name == "vnc-no-session") assertEquals("no_graphical_session", it.error)
                    else { assertEquals("no_vnc_server", it.error); assertEquals("wayland", it.session) }
                }
                name == "sunshine-pair" || name == "sunshine-pair-replaced" -> Json.decodeFromString<RemoteSunshineUnpair.PairResult>(output).let {
                    assertTrue(it.added); assertEquals(if (name.endsWith("replaced")) 2 else 0, it.replaced)
                    assertEquals(name.endsWith("replaced"), it.restarted)
                    assertEquals("fd53831c65e15ec620f371bc771dfe082703aa8c3444902fb8d25094f4d4cfd1", it.clientSha256)
                }
                name.startsWith("sunshine-pair-") -> parseSunshineCommand(output).let {
                    assertFalse(it.success); assertTrue(it.detail.isNotBlank())
                }
                name.startsWith("sunshine-unpair") -> Json.decodeFromString<RemoteSunshineUnpair.Result>(output).let {
                    assertEquals(if (name.endsWith("none")) 0 else 1, it.removed); assertFalse(it.restarted)
                }
                name in setOf("sunshine-start", "sunshine-encryption", "sunshine-refused", "sunshine-wake", "sunshine-settings") -> parseSunshineCommand(output).let {
                    assertEquals(name != "sunshine-refused", it.success)
                    assertTrue(it.detail.isNotBlank())
                    if (name == "sunshine-wake") assertEquals(false, it.displayAsleep)
                }
                name.startsWith("sunshine-") -> parseSunshineProbe(output).let {
                    assertEquals(file.path, name != "sunshine-missing", it.installed)
                    assertEquals(file.path, name in setOf("sunshine-running", "sunshine-busy", "sunshine-macos", "sunshine-macos-locked"), it.running)
                    assertEquals(name == "sunshine-busy", it.activeStream)
                    assertEquals(if (windows) listOf("192.168.31.61") else listOf("192.168.31.61", "100.64.0.5"), it.candidates)
                    if (name.startsWith("sunshine-macos")) {
                        assertEquals(name.endsWith("locked"), it.screenLocked)
                        assertEquals(name.endsWith("locked"), it.displayAsleep)
                    }
                    if (windows && it.installed) assertTrue(it.encryptionEnforced)
                }
                name == "firewall-check" || name == "firewall-review" -> parseSunshineFirewallCheck(output).let {
                    assertTrue(it.success); assertEquals(name == "firewall-check", it.firewallModelOk)
                    assertEquals(listOf("192.168.31.0/24"), it.allowedSources)
                    if (name == "firewall-review") {
                        assertNotNull(it.suggestedPlan)
                        assertEquals(listOf("47998-48000"), it.suggestedPlan!!.addRule!!.ports)
                    } else assertNull(it.suggestedPlan)
                }
                name == "firewall-result" -> parseSunshineFirewallResult(output).let {
                    assertTrue(it.success); assertTrue(it.firewallModelOk); assertTrue(it.changed)
                    assertNotNull(it.appliedChanges); assertEquals("""C:\fixture\backup""", it.backup)
                }
                name == "clipboard" || name == "clipboard-failed" -> parseWindowsClipboard(output).let {
                    assertEquals(name == "clipboard", it.success); assertEquals(name == "clipboard", it.verified)
                    assertTrue(it.detail.isNotBlank())
                }
                name.startsWith("clipboard-serve") -> {
                    val stream = mockk<RemoteChannelStream>(relaxed = true)
                    every { stream.input } returns (output.trim() + "\n").byteInputStream()
                    every { stream.output } returns ByteArrayOutputStream()
                    every { stream.isConnected } returns true
                    val channel = WindowsClipboardChannel { stream }
                    assertEquals(name == "clipboard-serve", channel.write("契约"))
                    channel.close()
                }
                else -> fail("No Kotlin consumer registered for ${file.path}")
            }
        }
    }
}
