package me.ayuilos.miffan.data.repository

import java.io.File
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.screen.RfbKeys
import org.junit.Assert.*
import org.junit.Test

class WindowsScreenHelperTest {
    @Test fun commandsKeepDataOutOfPowerShellCodeAndRetainPosixContract() {
        assertEquals("& \"\$env:LOCALAPPDATA\\Miffan\\bin\\miffan.ps1\" sunshine firewall check",
            screenHelperCommand(RemotePlatform.WINDOWS, "sunshine firewall check"))
        assertEquals("\"\$HOME/.miffan/bin/miffan\" clip", screenHelperCommand(RemotePlatform.POSIX, "clip"))
        assertTrue(runCatching { screenHelperCommand(RemotePlatform.WINDOWS, "clip; Write-Host secret") }.isFailure)
        assertEquals(screenHelperCommand(RemotePlatform.WINDOWS, "cua install"), RemoteScreenRepository.cuaDriverCommand("C:/old", RemotePlatform.WINDOWS))
        assertTrue(windowsHelperInstallCommand.contains("OpenStandardInput().CopyTo"))
        assertTrue(windowsHelperInstallCommand.contains("[IO.File]::Replace"))
        val asset = File("src/main/assets/remote/miffan.ps1").readBytes()
        assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()), asset.take(3).toByteArray())
        assertTrue(asset.toString(Charsets.UTF_8).contains("HelperVersion = 7"))
    }
    @Test fun windowsProbeAndSunshineReuseModelsWithoutClaimingCuaSupport() {
        val p = parseHelperProbe("""{"helper":2,"os":"windows","arch":"AMD64","session":{"present":true,"type":"console","desktop":"windows","id":1},"cua":{"ok":false,"min":"0.34.0"},"vnc":{"running":false},"clipboard":"interactive-task"}""")
        assertEquals(RemoteScreenPlatform.WINDOWS, RemoteScreenPlatform.parse(p.os))
        assertFalse(p.cua.ok)
        val stream = parseSunshineProbe("""{"installed":true,"running":true,"active_stream":false,"lan_encryption_mode":2,"wan_encryption_mode":2,"permissions":null,"permissions_from_log":null,"candidates":["100.64.0.8","192.168.31.61","bad"]}""")
        assertTrue(stream.encryptionEnforced)
        assertEquals(listOf("100.64.0.8", "192.168.31.61"), stream.candidates)
        assertNull(streamPreflightFallback(stream))
        assertTrue(parseWindowsClipboard("""{"success":true,"verified":true,"detail":"verified","session_id":1}""").verified)
        assertFalse(parseWindowsClipboard("""{"success":false,"detail":"locked"}""").verified)
    }
    @Test fun firewallCheckFailsClosedAndSuppliesReviewWithoutGrantingApproval() {
        val check = parseSunshineFirewallCheck(reviewJson)
        assertFalse(check.firewallModelOk)
        assertNotNull(check.suggestedPlan)
        val failure = runCatching { requireSunshineFirewall(check) }.exceptionOrNull() as RemoteScreenUnavailableException
        assertEquals(RemoteScreenProblem.FIREWALL_REVIEW_NEEDED, failure.problem)
        assertSame(check, failure.firewallCheck)
        assertEquals(RemoteScreenProblem.FIREWALL_CHECK_FAILED,
            (runCatching { requireSunshineFirewall(check.copy(success = false)) }.exceptionOrNull() as RemoteScreenUnavailableException).problem)
        requireSunshineFirewall(check.copy(firewallModelOk = true))
    }
    @Test fun approvalFreezesExactPlanAndEmptyPlanCannotBeApplied() {
        val check = parseSunshineFirewallCheck(reviewJson)
        val ids = mutableListOf("unsafe")
        val plan = check.suggestedPlan!!.copy(disableRules = ids)
        val approved = plan.approve()
        ids += "unreviewed"
        assertEquals(plan.copy(disableRules = listOf("unsafe")),
            kotlinx.serialization.json.Json.decodeFromString<RemoteSunshineFirewallPlan>(approved.json))
        assertFalse(approved.json.contains("unreviewed"))
        assertTrue(runCatching { plan.copy(disableRules = emptyList(), addRule = null).approve() }.isFailure)
        val result = parseSunshineFirewallResult("""{"success":false,"changed":true,"detail":"snapshot changed","backup":"C:\\backup","applied_changes":null}""")
        assertFalse(result.success); assertEquals("C:\\backup", result.backup)
    }
    @Test fun windowsFailsWithActionableStreamReasonAndNeverWakesMac() = runBlocking {
        val error = windowsStreamUnavailable(RemoteStreamFallback(RemoteStreamFallbackReason.UDP_UNREACHABLE, "100.64.0.8"))
        assertEquals(RemoteScreenProblem.STREAM_UNAVAILABLE, error.problem)
        assertTrue(error.detail!!.contains("47998–48000")); assertTrue(error.detail.contains("Tailscale"))
        val probe = SunshineProbe(true, running = true, activeStream = false, displayAsleep = true)
        prepareStreamProbe(RemoteScreenPlatform.WINDOWS, probe) { error("Windows must not wake") }
        assertEquals(0x5b, StreamKeys.virtualKey(RfbKeys.SUPER_L, RemoteScreenPlatform.WINDOWS))
    }
    companion object {
        val reviewJson = """{"success":true,"detail":"review","firewall_model_ok":false,"rules":[],"unsafe_rules":[{"id":"unsafe","reasons":["tcp_or_other_protocol"]}],"allowed_sources":["192.168.31.0/24","100.64.0.0/10"],"suggested_plan":{"disable_rules":["unsafe"],"allowed_sources":["192.168.31.0/24","100.64.0.0/10"],"add_rule":{"id":"Miffan-Sunshine-UDP-LAN","program":"C:\\Sunshine\\sunshine.exe","protocol":"UDP","ports":["47998-48000"],"sources":["192.168.31.0/24","100.64.0.0/10"],"replace":false}}}"""
    }
}
