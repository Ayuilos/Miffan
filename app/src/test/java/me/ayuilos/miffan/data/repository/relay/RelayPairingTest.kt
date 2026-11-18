package me.ayuilos.miffan.data.repository.relay

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import me.ayuilos.miffan.data.db.entity.RELAY_LAST_ADDRESS
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.RemoteHostRelay
import me.ayuilos.miffan.data.db.entity.encodeRemoteHostRelay
import me.ayuilos.miffan.ui.pages.extensions.workspace.connectionSummary
import me.rerere.workspace.relay.RelayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPairingTest {
    private val sampleHostId = "abcdefghijklmnopqrstuvwx23"
    private val sampleDevicePub = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE" + "A".repeat(88)
    private val sampleSshPub = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMiffanTestKeyPayload123456789012345678 miffan"
    private val sampleFp1 = "SHA256:" + "A".repeat(43)
    private val sampleFp2 = "SHA256:" + "B".repeat(43)

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))

    private fun locateHelperSh(): File = listOf(
        File("remote-helper/src/miffan.sh"),
        File("../remote-helper/src/miffan.sh"),
    ).first { it.isFile }

    @Test
    fun parsePairingUriWithUtf8NameCustomRelayAndFingerprints() {
        val raw = """
            Miffan pairing code:
            miffan-pair://v1?host=$sampleHostId&user64=${b64("alice")}&name64=${b64("张三的 MacBook")}&relay64=${b64("https://relay.example.com")}&fp=$sampleFp1,$sampleFp2
        """.trimIndent()
        val parsed = parseRelayPairingInput(raw)
        assertNotNull(parsed)
        assertEquals(sampleHostId, parsed!!.hostId)
        assertEquals("alice", parsed.username)
        assertEquals("张三的 MacBook", parsed.name)
        assertEquals("https://relay.example.com", parsed.relayUrl)
        assertEquals(listOf(sampleFp1, sampleFp2), parsed.fingerprints)
    }

    @Test
    fun parseMultiLineConnectorJsonOutput() {
        val terminalLog = """
            $ miffan-connector pair --device ...
            2026-11-18T12:00:00Z INFO paired device
            {"success":true,"hostId":"${sampleHostId.uppercase()}","deviceId":"dev123","relay":"https://relay.example.com/"}
        """.trimIndent()
        val parsed = parseRelayPairingInput(terminalLog)
        assertNotNull(parsed)
        assertEquals(sampleHostId, parsed!!.hostId)
        assertEquals("https://relay.example.com", parsed.relayUrl)
        assertTrue(parsed.fingerprints.isEmpty())
    }

    @Test
    fun parseBareHostIdAndRejectInvalidInputs() {
        val bare = parseRelayPairingInput("  ${sampleHostId.uppercase()}  ")
        assertNotNull(bare)
        assertEquals(sampleHostId, bare!!.hostId)

        assertNull(parseRelayPairingInput(""))
        assertNull(parseRelayPairingInput("not-a-host-id"))
        assertNull(parseRelayPairingInput("http://192.168.1.1"))
    }

    @Test
    fun parseSshHostFingerprintExtractsBareAndSshKeygenLines() {
        assertEquals(sampleFp1, parseSshHostFingerprint(sampleFp1))
        assertEquals(
            sampleFp2,
            parseSshHostFingerprint("256 $sampleFp2 root@server (ED25519)"),
        )
        assertNull(parseSshHostFingerprint("SHA256:short"))
        assertNull(parseSshHostFingerprint("MD5:aa:bb:cc"))
    }

    @Test
    fun manualConnectorCommandsNeverEmbedSecretToken() {
        val phone = RelayPhoneIdentity(sampleDevicePub, "dev1234567890", "Pixel 9")
        val publicCmds = manualConnectorCommands(phone, RelayEndpoint("https://relay.miffan.ayuilos.me"))
        assertTrue("--token-stdin" !in publicCmds)
        assertTrue(sampleDevicePub in publicCmds)

        val secretToken = "super-secret-relay-token-999"
        val privateCmds = manualConnectorCommands(phone, RelayEndpoint("https://relay.example.com", secretToken))
        assertTrue("--token-stdin" in privateCmds)
        assertTrue(secretToken !in privateCmds)
    }

    @Test
    fun relaySetupScriptHasSafeQuotingAndRunsUnderPosixSh() {
        val helperSh = locateHelperSh().readText(StandardCharsets.UTF_8)
        val trickyToken = "tok'1\"\\\$`line"
        val trickyDevice = "O'Brien's \"Pixel\" \$9"
        val phone = RelayPhoneIdentity(sampleDevicePub, "dev1234567890", trickyDevice)
        val endpoint = RelayEndpoint("https://relay.example.com", trickyToken)

        val command = RelaySetupScript.render(helperSh, sampleSshPub, phone, endpoint)
        assertTrue(command.startsWith("sh -c '") && command.endsWith("'"))
        val body = command.removePrefix("sh -c '").removeSuffix("'")
        assertTrue("Script body must contain no single quotes", '\'' !in body)
        assertTrue("Script body must contain no backslashes", '\\' !in body)

        val tempHome = createTempDirectory("miffan-relay-script-test").toFile()
        try {
            val binDir = File(tempHome, ".miffan/bin").apply { mkdirs() }
            val connectorStub = File(binDir, "miffan-connector")
            connectorStub.writeText(
                """
                #!/bin/sh
                sub=${'$'}1
                shift
                case "${'$'}sub" in
                  init)
                    cat > "${'$'}HOME/captured_token"
                    echo '{"success":true}'
                    ;;
                  pair)
                    while [ ${'$'}# -gt 0 ]; do
                      if [ "${'$'}1" = "--name" ]; then
                        printf '%s' "${'$'}2" > "${'$'}HOME/captured_name"
                      fi
                      shift
                    done
                    echo '{"success":true,"hostId":"$sampleHostId","relay":"https://relay.example.com"}'
                    ;;
                  status)
                    echo '{"installed":true,"running":true,"hostId":"$sampleHostId"}'
                    ;;
                esac
                """.trimIndent() + "\n",
                StandardCharsets.UTF_8,
            )
            assertTrue(connectorStub.setExecutable(true))

            val proc = ProcessBuilder("/bin/sh", "-c", body)
                .apply {
                    environment()["HOME"] = tempHome.absolutePath
                    redirectErrorStream(true)
                }
                .start()
            val finished = proc.waitFor(15, TimeUnit.SECONDS)
            val output = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue("Script timed out: $output", finished)
            assertEquals("Script failed: $output", 0, proc.exitValue())

            val authorizedKeys = File(tempHome, ".ssh/authorized_keys").readText(StandardCharsets.UTF_8)
            assertTrue(authorizedKeys.contains("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMiffanTestKeyPayload123456789012345678"))
            assertEquals(trickyToken, File(tempHome, "captured_token").readText(StandardCharsets.UTF_8))
            assertEquals(trickyDevice, File(tempHome, "captured_name").readText(StandardCharsets.UTF_8))

            val parsed = parseRelayPairingInput(output)
            assertNotNull("Expected miffan-pair:// output in:\n$output", parsed)
            assertEquals(sampleHostId, parsed!!.hostId)
            assertEquals("https://relay.example.com", parsed.relayUrl)
            assertTrue(!parsed.username.isNullOrBlank())
        } finally {
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun relayOnlyHostEntityHandlesBlankHostSafely() {
        val relay = RemoteHostRelay("https://relay.example.com", sampleHostId)
        val host = RemoteHostEntity(
            id = "h1",
            name = "Work Linux",
            host = "",
            port = 22,
            username = "alice",
            authType = "MANAGED_KEY",
            sshKeyId = "k1",
            trustedHostKeySha256 = sampleFp1,
            createdAt = 1L,
            updatedAt = 2L,
            lastAddressValue = RELAY_LAST_ADDRESS,
            relayValue = encodeRemoteHostRelay(relay),
        )
        assertTrue(host.addresses.isEmpty())
        assertTrue(host.connectionAddresses.isEmpty())
        assertEquals("alice@${sampleHostId.take(10)}", host.connectionSummary())
    }
}
