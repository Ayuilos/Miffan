package me.ayuilos.miffan.data.ai.computer

import android.util.Base64
import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool as McpTool
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.repository.LeasedRemote
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.rerere.workspace.RemoteChannelStream

private const val TAG = "RemoteComputer"

/** macOS privacy grants cua-driver needs to read and operate the desktop. */
data class ComputerPermissions(val accessibility: Boolean, val screenRecording: Boolean) {
    val complete: Boolean get() = accessibility && screenRecording
}

/**
 * One cua-driver MCP connection per remote workspace, running `cua-driver mcp` inside the remote
 * graphical session over the workspace's SSH lease. Connections are opened on first use,
 * replaced when they die, and closed after [idleMillis] without calls.
 */
class RemoteComputerRegistry(
    private val screens: RemoteScreenRepository,
    private val scope: CoroutineScope,
    private val idleMillis: Long = 10 * 60_000L,
) {
    /** What a connected cua-driver offers; cached for the life of the connection. */
    class Capabilities(
        val hostId: String,
        val tools: List<McpTool>,
        /** cua-driver's own SKILL.md, when the driver serves its skill as MCP resources. */
        val guide: String?,
        val resources: List<String>,
    )

    private class Connection(
        val workspaceId: String,
        val handle: LeasedRemote<RemoteChannelStream>,
        val client: Client,
        /** A cua-driver session label unique to this connection (labels are daemon-global on macOS). */
        val label: String,
        val capabilities: Capabilities,
    ) {
        @Volatile var lastUsed = System.currentTimeMillis()
        val alive: Boolean get() = handle.value.isConnected
    }

    private val lock = Mutex()
    private val connections = mutableMapOf<String, Connection>()

    init {
        scope.launch {
            while (true) {
                delay(60_000)
                val expired = lock.withLock {
                    val now = System.currentTimeMillis()
                    connections.values.filter { now - it.lastUsed > idleMillis || !it.alive }
                        .onEach { connections.remove(it.workspaceId) }
                }
                expired.forEach { close(it) }
            }
        }
    }

    suspend fun capabilities(workspaceId: String): Capabilities = connection(workspaceId).capabilities

    /**
     * Calls a cua-driver tool with this connection's session label. A connection that died is
     * replaced once; a call that already reached the remote side is never replayed.
     */
    suspend fun call(workspaceId: String, name: String, arguments: JsonObject): CallToolResult {
        val current = connection(workspaceId)
        current.lastUsed = System.currentTimeMillis()
        val withSession = JsonObject(arguments + ("session" to JsonPrimitive(current.label)))
        return current.client.callTool(
            request = CallToolRequest(params = CallToolRequestParams(name = name, arguments = withSession)),
            options = RequestOptions(timeout = 120.seconds),
        ).also { current.lastUsed = System.currentTimeMillis() }
    }

    /** Reads one of cua-driver's skill resources (e.g. `skill://cua-driver/WORKFLOW.md`). */
    suspend fun readResource(workspaceId: String, uri: String): String {
        val current = connection(workspaceId)
        current.lastUsed = System.currentTimeMillis()
        return readText(current.client, uri)
    }

    /**
     * The remote cua-driver daemon's macOS grants. Null when the driver has no such check (Linux).
     * [prompt] raises the system dialogs on the remote Mac for missing grants; the user answers
     * them on that screen.
     */
    suspend fun permissions(workspaceId: String, prompt: Boolean): ComputerPermissions? {
        if (capabilities(workspaceId).tools.none { it.name == "check_permissions" }) return null
        val result = call(workspaceId, "check_permissions", buildJsonObject {
            put("prompt", prompt)
            if (prompt) put("probe_direct_capture", false)
        })
        val report = result.structuredContent ?: result.content.filterIsInstance<TextContent>()
            .firstNotNullOfOrNull { runCatching { Json.parseToJsonElement(it.text).jsonObject }.getOrNull() }
            ?: error("cua-driver returned no permission report")
        return ComputerPermissions(
            accessibility = report["accessibility"]?.jsonPrimitive?.booleanOrNull == true,
            screenRecording = report["screen_recording"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /**
     * A read-only screenshot of the whole desktop through cua-driver, proving the partner can
     * see the screen. Null when the driver returned no image.
     */
    suspend fun desktopScreenshot(workspaceId: String): ByteArray? {
        val result = call(workspaceId, "get_desktop_state", buildJsonObject { put("max_image_dimension", 1280) })
        check(result.isError != true) {
            result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }.ifBlank { "cua-driver error" }
        }
        return result.content.filterIsInstance<ImageContent>().lastOrNull()
            ?.let { Base64.decode(it.data, Base64.DEFAULT) }
    }

    suspend fun closeWorkspace(workspaceId: String) {
        val removed = lock.withLock { connections.remove(workspaceId) }
        removed?.let { close(it) }
    }

    private suspend fun connection(workspaceId: String): Connection = lock.withLock {
        connections[workspaceId]?.takeIf { it.alive }?.let { return@withLock it }
        connections.remove(workspaceId)?.let { close(it) }
        open(workspaceId).also { connections[workspaceId] = it }
    }

    private suspend fun open(workspaceId: String): Connection {
        val hostId = requireNotNull(screens.hostIdOf(workspaceId)) { "Not a remote workspace" }
        val handle = screens.openCuaProcess(workspaceId)
        val client = Client(clientInfo = Implementation(name = "miffan", version = "1.0"))
        try {
            val stream = handle.value
            val transport = StdioClientTransport(
                input = stream.input.asSource().buffered(),
                output = stream.output.asSink().buffered(),
                error = stream.errors?.asSource()?.buffered(),
            )
            withTimeout(30.seconds) { client.connect(transport) }
            val tools = client.listTools().tools
            val resources = runCatching { client.listResources().resources.map { it.uri } }.getOrDefault(emptyList())
            val guide = buildGuide(client, resources)
            val label = "miffan-" + UUID.randomUUID().toString().take(8)
            Log.i(TAG, "cua-driver connected for $workspaceId: ${tools.size} tools, guide=${guide?.length}")
            return Connection(workspaceId, handle, client, label, Capabilities(hostId, tools, guide, resources))
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { client.close() }
                runCatching { handle.close() }
            }
            throw error
        }
    }

    /** SKILL.md goes into the system prompt; other guides are read on demand. Older drivers serve none. */
    private suspend fun buildGuide(client: Client, resources: List<String>): String? =
        resources.firstOrNull { it.endsWith("/SKILL.md") }?.let { readText(client, it) }

    private suspend fun readText(client: Client, uri: String): String =
        client.readResource(ReadResourceRequest(ReadResourceRequestParams(uri = uri)))
            .contents.filterIsInstance<TextResourceContents>().joinToString("\n") { it.text }

    private suspend fun close(connection: Connection) = withContext(NonCancellable) {
        runCatching { connection.client.callTool(name = "end_session", arguments = mapOf("session" to connection.label)) }
        runCatching { connection.client.close() }
        runCatching { connection.handle.close() }
    }
}
