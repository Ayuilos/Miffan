package me.ayuilos.miffan.data.bugreport

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.ai.withAgentRelayApproval
import me.ayuilos.miffan.data.model.Assistant
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class AppDiagnosticsTest {
    private fun tempFile(): File = File("build/bug-reporter-tests/${java.util.UUID.randomUUID()}/errors.json")

    @Test fun redactEachSensitiveCategoryAndStripUrlComponents() {
        val cases = mapOf(
            "https://user:password@example.com:8080/private?q=secret#x" to "https://example.com",
            "sk-abc123" to "[已隐藏]", "Bearer secret" to "[已隐藏]",
            "key=secret" to "[已隐藏]", "token='secret value'" to "[已隐藏]",
            "abcdef0123456789abcdef0123456789" to "[已隐藏]",
            "YWJjZGVmZ2hpamtsbW5vcHFyc3Q=" to "[已隐藏]",
            "me@example.com" to "[已隐藏]", "192.168.1.2" to "[IPv4]",
            "2001:db8::1" to "[IPv6]", "::1" to "[IPv6]",
            "http://127.0.0.1:8080/secrets" to "http://[IPv4]",
            "https://[2001:db8::1]/secret" to "https://[IPv6]",
            "api_key=short" to "[已隐藏]", "password=short" to "[已隐藏]",
            "\"api_key\": \"short\"" to "\"[已隐藏]",
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, redactDiagnosticText(input)) }
        assertEquals("HTTP failed at 12:34:56", redactDiagnosticText("HTTP failed at 12:34:56"))
    }

    @Test fun persistentRingSurvivesRestartAndSanitizesBeforeWriting() = runTest {
        val file = tempFile()
        try {
            val store = RecentErrorStore(file)
            repeat(25) { i -> store.record(IllegalStateException("$i sk-secret " + "短".repeat(600)), "token=secret", Instant.ofEpochSecond(i.toLong())) }
            assertFalse(file.readText().contains("sk-secret"))
            assertFalse(file.readText().contains("token=secret"))
            val restarted = RecentErrorStore(file)
            assertEquals(20, restarted.recent(20).size)
            assertEquals(10, restarted.recent().size)
            assertEquals(Instant.ofEpochSecond(24).toString(), restarted.recent().first().at)
            assertEquals(Instant.ofEpochSecond(5).toString(), restarted.recent(20).last().at)
            assertEquals(500, restarted.recent().first().message.length)
            assertEquals("IllegalStateException", restarted.recent().first().kind)
            store.record(IllegalArgumentException("failed"))
            assertEquals("IllegalArgumentException", store.recent().first().kind)
        } finally { file.parentFile?.deleteRecursively() }
    }

    @Test fun diagnosticsExposeOnlyDeviceMetadataAndErrorsInBothModesIncludingRelay() = runTest {
        val caller = Assistant(name = "private-name", systemPrompt = "private-prompt")
        val access = AgentAccess.forAssistant(caller, false).copy(ownerThreadNonOwnerTurn = true)
        for (mode in listOf("easy", "professional")) {
            val info = AppDiagnostics("1.0", 1, "debug", AndroidVersion("14", 34), DiagnosticDevice("maker", "phone"), mode, "zh-CN")
            val tool = listOf(createAppDiagnosticsTool(access) { info }).withAgentRelayApproval(access).single()
            assertFalse(tool.needsApproval(JsonObject(emptyMap())))
            val output = (tool.execute(JsonObject(emptyMap())).single() as UIMessagePart.Text).text
            val payload = Json.parseToJsonElement(output).jsonObject
            assertEquals(setOf("app_version", "version_code", "build_type", "android_version", "device", "interface_mode", "locale", "recent_errors"), payload.keys)
            assertFalse(output.contains("private-name")); assertFalse(output.contains("private-prompt"))
            assertFalse(output.contains("messages")); assertFalse(output.contains("assistant"))
        }
    }
}
