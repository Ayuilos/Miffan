package me.ayuilos.miffan.data.repository

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.computer.RemoteComputerRegistry
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Controller supplies an already independently verified owned HWND; never observe another window. */
@RunWith(AndroidJUnit4::class)
class WindowsComputerAcceptanceTest {
    @Test fun cleanupAudit() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(args.getString("p7c") == "true" && android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val repository = GlobalContext.get().get<WorkspaceRepository>()
        assertFalse(File(context.cacheDir, "p7c-test-key").exists())
        assertTrue(repository.listFlow().first().none { it.name.startsWith("miffan-p7c-test") })
        assertTrue(repository.listHostsFlow().first().none { it.name.startsWith("miffan-p7c-test") })
        assertTrue(repository.listSshKeysFlow().first().none { it.name.startsWith("miffan-p7c-test") })
    }

    @Test fun registryOnOwnedNotepad() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        check(args.getString("p7c") == "true" && android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val readOnly = args.getString("readOnly") == "true"
        if (!readOnly) check(args.getString("clipboardNotified") == "true")
        val pid = args.getString("pid", "0").toInt()
        val window = args.getString("windowId", "0").toLong()
        val marker = args.getString("marker", "p7c-owned-readonly")
        check(marker.startsWith("p7c-owned-"))
        val koin = GlobalContext.get()
        val workspaces = koin.get<WorkspaceRepository>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val keyFile = File(context.cacheDir, "p7c-test-key")
        var keyId: String? = null; var hostId: String? = null; var workspaceId: String? = null
        var registry: RemoteComputerRegistry? = null
        try {
            val key = try { workspaces.importSshKey("miffan-p7c-test", keyFile.readText()) }
                finally { check(keyFile.delete() || !keyFile.exists()) }
            keyId = key.id
            val host = workspaces.createHost("miffan-p7c-test", "10.0.2.2", 58022, requireNotNull(args.getString("user")), sshKeyId = key.id)
            hostId = host.id
            assertTrue(workspaces.trustHostKey(host.id, requireNotNull(args.getString("sshPin"))))
            assertTrue(workspaces.testHost(host.id))
            val workspace = workspaces.createRemoteWorkspace("miffan-p7c-test", host.id, workspaces.remoteHome(host.id))
            workspaceId = workspace.id
            val screens = RemoteScreenRepository(workspaces, koin.get<WorkspaceDAO>(), koin.get<RemoteHostDAO>(), koin.get<RemoteScreenCredentialStore>(), context.assets, context)
            val probe = screens.probe(workspace.id)
            assertEquals("windows", probe.os); assertEquals(4, probe.helper)
            assertTrue(probe.cua.ok); assertEquals("Limited", probe.cua.autostartRunLevel)
            registry = RemoteComputerRegistry(screens, scope)
            val caps = registry.capabilities(workspace.id)
            assertTrue(registry.ensureSession(workspace.id).startsWith("miffan-"))
            assertTrue(caps.windows)
            assertTrue(caps.tools.any { it.name == "clipboard_write" })
            assertNotNull(caps.guide)
            assertTrue(caps.resources.any { it.endsWith("/WINDOWS.md") })
            val guide = registry.readResource(workspace.id, caps.resources.first { it.endsWith("/WINDOWS.md") })
            assertTrue(guide.contains("Windows"))
            suspend fun call(name: String, params: JsonObject) = registry.call(workspace.id, name, params).also {
                assertFalse("$name refused", it.isError == true)
            }
            if (readOnly) {
                call("list_windows", JsonObject(emptyMap()))
                File(context.getExternalFilesDir(null), "p7c-readonly.txt").writeText("Registry initialize, schema/resources + Windows guide, list_windows passed.\n")
                return@runBlocking
            }
            call("list_windows", buildJsonObject { put("pid", pid) })
            fun stateArgs() = buildJsonObject { put("pid", pid); put("window_id", window); put("max_image_dimension", 1280) }
            fun payload(result: CallToolResult): JsonObject = result.structuredContent ?: result.content.filterIsInstance<TextContent>()
                .firstNotNullOf { runCatching { Json.parseToJsonElement(it.text).jsonObject }.getOrNull() }
            val before = call("get_window_state", stateArgs())
            val state = payload(before)
            assertTrue("owned title", state.toString().contains(marker))
            val elements = state["elements"]!!.jsonArray.map { it.jsonObject }
            val editor = elements.single { it["role"]?.jsonPrimitive?.content in setOf("Document", "Edit") &&
                it["element_token"] != null && it["value"]?.jsonPrimitive?.content?.contains("P7c owned fixture") == true }
            val token = editor["element_token"]!!.jsonPrimitive.content
            val text = "P7c registry English\n米饭 Windows 伙伴中文输入验收"
            call("type_text", buildJsonObject { put("pid", pid); put("window_id", window); put("element_token", token); put("text", text); put("delivery_mode", "background") })
            delay(500)
            val typed = call("get_window_state", stateArgs())
            assertTrue("English readback", payload(typed).toString().contains("P7c registry English"))
            assertTrue("Chinese readback", payload(typed).toString().contains("米饭 Windows 伙伴中文输入验收"))
            val freshEditor = payload(typed)["elements"]!!.jsonArray.map { it.jsonObject }.single { it["value"]?.jsonPrimitive?.content?.contains("P7c registry English") == true && it["element_token"] != null }
            // Exact target token, background focus click, then fresh observation and readback.
            call("click", buildJsonObject { put("pid", pid); put("window_id", window); put("element_token", freshEditor["element_token"]!!); put("delivery_mode", "background") })
            val final = call("get_window_state", stateArgs())
            assertTrue(payload(final).toString().contains("米饭 Windows 伙伴中文输入验收"))
            call("clipboard_write", buildJsonObject { put("text", "P7c clipboard 米饭桥验收") })
            val clipboard = call("clipboard_read", buildJsonObject { put("include_text", true) })
            assertTrue(payload(clipboard).toString().contains("P7c clipboard 米饭桥验收"))
            val image = final.content.filterIsInstance<ImageContent>().last()
            File(context.getExternalFilesDir(null), "p7c-registry.png").writeBytes(Base64.decode(image.data, Base64.DEFAULT))
            File(context.getExternalFilesDir(null), "p7c-registry.txt").writeText("Windows Registry: schema/resources, initialize, list_windows, owned screenshot, background English/Chinese type_text + readback, click, clipboard_write/read passed.\n")
            // Concurrent clip connection exercises bridge reuse while Registry MCP stays alive.
            check(args.getString("helperClipboardNotified") == "true")
            val clipped = workspaces.executeCommand(workspace.id,
                "& (Join-Path \$env:LOCALAPPDATA 'Miffan\\bin\\miffan.ps1') clip",
                stdin = "P7c helper 米饭剪贴板加速".toByteArray())
            assertEquals(0, clipped.exitCode)
            assertTrue(clipped.stdout.contains("cua-bridge"))
            assertTrue(clipped.stdout.contains("\"verified\":true"))
        } finally {
            withContext(NonCancellable) {
                workspaceId?.let { registry?.closeWorkspace(it) }
                scope.cancel()
                workspaceId?.let { workspaces.delete(it) }
                hostId?.let { workspaces.deleteHost(it) }
                keyId?.let { workspaces.deleteSshKey(it) }
                keyFile.delete()
            }
        }
    }
}
