package me.ayuilos.miffan.data.repository

import android.content.res.AssetManager
import io.mockk.*
import java.io.File
import java.io.InputStream
import kotlinx.serialization.json.*
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.*
import org.junit.Test

class RemoteSunshinePairTest {
    private val pem get() = File(System.getProperty("remoteHelper.windowsTests"), "certificate.pem").readText()
    private val fingerprint get() = streamClientCertificateSha256(pem)
    private val success get() = """{"added":true,"replaced":2,"restarted":true,"client_sha256":"$fingerprint"}"""

    private fun pair(result: WorkspaceCommandResult, platform: RemotePlatform = RemotePlatform.POSIX,
        inspect: (String, InputStream?) -> Unit = { _, _ -> }): RemoteStreamManualPinReason? {
        val assets = mockk<AssetManager>()
        val ssh = mockk<RemoteWorkspaceSession>()
        every { ssh.platform } returns platform
        every { assets.open(any()) } answers {
            File(System.getProperty("remoteHelper.assets"), firstArg<String>().removePrefix("remote/")).inputStream()
        }
        every { ssh.execute(any(), any(), any(), any(), any()) } answers {
            val command = firstArg<String>()
            assertFalse(command.contains(pem)); assertFalse(command.contains("BEGIN CERTIFICATE"))
            assertFalse(command.contains("private-name"))
            if (command.endsWith("sunshine pair")) {
                assertEquals(45_000L, arg<Long>(2))
                inspect(command, arg(4))
                result
            } else WorkspaceCommandResult(0, "old-version", "")
        }
        return RemoteSunshineUnpair(assets) { pem }.pair(ssh, "private-name 手机")
    }

    @Test fun `both platforms send name and certificate only in JSON stdin`() {
        for (platform in listOf(RemotePlatform.POSIX, RemotePlatform.WINDOWS)) {
            var called = false
            assertNull(pair(WorkspaceCommandResult(0, success, ""), platform) { _, input ->
                called = true
                val body = requireNotNull(input).readBytes().toString(Charsets.UTF_8)
                assertTrue(body.endsWith("\n")); assertEquals(1, body.count { it == '\n' })
                val request = Json.parseToJsonElement(body).jsonObject
                assertEquals(setOf("name", "cert"), request.keys)
                assertEquals("private-name 手机", request.getValue("name").jsonPrimitive.content)
                assertEquals(pem, request.getValue("cert").jsonPrimitive.content)
            })
            assertTrue(called)
        }
    }

    @Test fun `strict successful response requires exact shape valid values and complete stdout`() {
        for (invalid in listOf("", "null", "[]", "invalid", "log\n$success", "$success\n$success",
            success.replace("\"added\":true", "\"added\":false"),
            success.replace("\"replaced\":2", "\"replaced\":-1"),
            success.replace("\"replaced\":2", "\"replaced\":\"2\""),
            success.replace("\"restarted\":true", "\"restarted\":\"true\""),
            success.replace("\"restarted\":true,", ""),
            success.replace("\"added\":true", "\"added\":true,\"extra\":1"),
            success.replace(fingerprint, fingerprint.uppercase()),
            success.replace(fingerprint, "ab"))) {
            assertThrows(invalid, IllegalStateException::class.java) { pair(WorkspaceCommandResult(0, invalid, "")) }
        }
    }

    @Test fun `client certificate hash must match local identity`() {
        val error = assertThrows(IllegalStateException::class.java) {
            pair(WorkspaceCommandResult(0, success.replace(fingerprint, "00".repeat(32)), ""))
        }
        assertTrue(error.message.orEmpty().contains("fingerprint mismatch"))
    }

    @Test fun `only recognized helper failures offer manual PIN`() {
        for ((detail, reason) in mapOf(
            "Unrecognized Sunshine state format" to RemoteStreamManualPinReason.STATE_UNRECOGNIZED,
            "Could not stop Sunshine to change pairing" to RemoteStreamManualPinReason.SUNSHINE_DID_NOT_STOP,
            "Cannot read Sunshine state or configuration; check administrator rights" to RemoteStreamManualPinReason.NO_PERMISSION,
            "Cannot edit Sunshine state or control its service; check administrator rights" to RemoteStreamManualPinReason.NO_PERMISSION)) {
            // Windows includes diagnostic fields in its outer failure object.
            val output = """{"success":false,"detail":"$detail","error_type":"UnauthorizedAccessException","backup":null}"""
            assertEquals(reason, pair(WorkspaceCommandResult(1, output, "")))
        }
        for (detail in listOf("Sunshine pair attempted; could not restart Sunshine", "Cannot verify the Sunshine service model", "unknown failure")) {
            val error = assertThrows(IllegalStateException::class.java) {
                pair(WorkspaceCommandResult(1, """{"success":false,"detail":"$detail"}""", ""))
            }
            assertTrue(error.message.orEmpty().contains(detail))
        }
    }

    @Test fun `timeout malformed error or nonzero success never reports a fallback or pairing`() {
        for (result in listOf(WorkspaceCommandResult(0, success, "", timedOut = true),
            WorkspaceCommandResult(1, "invalid", ""), WorkspaceCommandResult(1, success, ""),
            WorkspaceCommandResult(1, """{"success":true,"detail":"Unrecognized Sunshine state format"}""", ""))) {
            assertThrows(IllegalStateException::class.java) { pair(result) }
        }
    }

    @Test fun `preflight returns before writing for missing certificate or active stream`() {
        val probe = SunshineProbe(installed = true, running = true, activeStream = true, certificateSha256 = fingerprint)
        assertEquals(RemoteStreamDirectPairing.ActiveStream, streamDirectPairingPreflight(probe, false))
        assertNull(streamDirectPairingPreflight(probe, true))
        assertNull(streamDirectPairingPreflight(probe.copy(activeStream = false), false))
        for (interrupt in listOf(false, true)) assertEquals(
            RemoteStreamDirectPairing.ManualPinNeeded(RemoteStreamManualPinReason.CERTIFICATE_UNAVAILABLE),
            streamDirectPairingPreflight(probe.copy(certificateSha256 = null), interrupt))
        assertThrows(IllegalStateException::class.java) { streamDirectPairingPreflight(probe.copy(running = false), false) }
        assertThrows(IllegalStateException::class.java) { streamDirectPairingPreflight(probe.copy(installed = false), false) }
    }
}
