package me.ayuilos.miffan.data.repository

import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.cuaspace.SpaceClient
import me.rerere.cuaspace.SpaceStream
import me.rerere.cuaspace.SpaceStreamOpener
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession

/**
 * A computer's own desktop shared by cua-spacesd (`cua host setup --desktop`), which can stream a
 * single window. Experimental: only a Mac set up by hand has it for now.
 *
 * The service listens on the computer itself; the phone reaches it through the computer's SSH
 * session (direct-tcpip to loopback), like a partner's Space. Its token is read over SSH each time
 * and kept only in memory.
 */
internal object HostDesktopSpace {
    private const val MARKER = "--miffan-host-desktop--"

    /**
     * Where the service listens, its token, and the Mac's scroll direction setting; null when this
     * computer does not share its desktop.
     */
    fun endpoint(ssh: RemoteWorkspaceSession): HostEndpoint? {
        if (ssh.platform != RemotePlatform.POSIX) return null
        val result = ssh.execute(
            "cat ~/.cua/host/service.json 2>/dev/null; printf '\\n$MARKER\\n'; cat ~/.cua/host/env-token 2>/dev/null; " +
                "printf '\\n$MARKER\\n'; defaults read -g com.apple.swipescrolldirection 2>/dev/null",
            timeoutMillis = 10_000,
        )
        val parts = result.stdout.split(MARKER).map(String::trim).takeIf { it.size == 3 } ?: return null
        val (service, token) = parts
        if (token.isEmpty() || service.isEmpty()) return null
        val args = runCatching { Json.parseToJsonElement(service).jsonObject["args"] as? JsonArray }.getOrNull() ?: return null
        val values = args.mapNotNull { it.jsonPrimitive.contentOrNull }
        val listen = values.getOrNull(values.indexOf("--listen") + 1)?.takeIf { values.contains("--listen") } ?: return null
        val port = listen.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        // macOS scrolls "naturally" unless the user turned it off (the key is absent by default).
        return HostEndpoint(port, token, naturalScroll = parts[2] != "0")
    }

    /** The computer's on-screen windows on the desktop in front, from its cua-driver; null when it cannot tell. */
    fun onScreen(ssh: RemoteWorkspaceSession): HostDesktops? {
        if (ssh.platform != RemotePlatform.POSIX) return null
        return parseDesktops(ssh.execute("$DRIVER call list_windows '{\"on_screen_only\":true}' </dev/null", timeoutMillis = 10_000).stdout)
    }

    /**
     * Every window of the computer, on any desktop, from its cua-driver; null when it cannot tell. Its
     * z order counts the same way as cua-spacesd's, which [onScreen]'s does not.
     */
    fun allWindows(ssh: RemoteWorkspaceSession): HostDesktops? {
        if (ssh.platform != RemotePlatform.POSIX) return null
        return parseDesktops(ssh.execute("$DRIVER call list_windows '{\"on_screen_only\":false}' </dev/null", timeoutMillis = 10_000).stdout)
    }

    /**
     * Which desktop (macOS Space) each window is on, from the computer's cua-driver, and the desktops
     * of each display in Mission Control order, from one command. Either is null when it cannot be
     * read (cua-driver missing, not a Mac, or the private call changed). cua-spacesd knows windows but
     * not their desktops.
     */
    fun placement(ssh: RemoteWorkspaceSession): Pair<HostDesktops?, List<List<HostSpace>>?> {
        if (ssh.platform != RemotePlatform.POSIX) return null to null
        val result = ssh.execute(
            "{ $DRIVER call list_windows '{\"on_screen_only\":false}' </dev/null; printf '\\n$MARKER\\n'; osascript -l JavaScript; }",
            timeoutMillis = 15_000,
            stdin = SPACE_ORDER_SCRIPT.byteInputStream(),
        )
        val parts = result.stdout.split(MARKER)
        return parseDesktops(parts[0]) to parts.getOrNull(1)?.let(::parseSpaceOrder)
    }

    /**
     * Opens [pid]'s app the way a Dock click does, which shows a window of an app running without one,
     * and waits a few seconds for it on the computer itself. Returns the app's windows once one is on
     * screen (or as they are when none came up), null when [pid] is not an app.
     */
    fun reopenApp(ssh: RemoteWorkspaceSession, pid: Int): HostDesktops? {
        require(pid > 0)
        val result = ssh.execute(
            """
            p=${'$'}(ps -o comm= -p $pid); case "${'$'}p" in *.app/*) open -a "${'$'}{p%%.app/*}.app";; *) exit 3;; esac
            i=0; while [ ${'$'}i -lt 25 ]; do
              out=${'$'}($DRIVER call list_windows '{"pid":$pid}' </dev/null)
              case "${'$'}out" in *'"is_on_screen": true'*|*'"is_on_screen":true'*) break;; esac
              i=${'$'}((i+1)); sleep 0.2
            done; printf '%s' "${'$'}out"
            """.trimIndent(),
            timeoutMillis = 15_000,
        )
        return parseDesktops(result.stdout)
    }

    internal fun parseDesktops(text: String): HostDesktops? {
        val root = runCatching { Json.parseToJsonElement(text.trim()).jsonObject }.getOrNull() ?: return null
        val windows = (root["windows"] as? JsonArray)?.mapNotNull { element ->
            val window = element as? JsonObject ?: return@mapNotNull null
            val bounds = window["bounds"] as? JsonObject ?: return@mapNotNull null
            fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
            HostDesktops.Placed(
                pid = window["pid"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                app = window["app_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                layer = window["layer"]?.jsonPrimitive?.intOrNull ?: 0,
                z = window["z_index"]?.jsonPrimitive?.intOrNull ?: 0,
                title = window["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                x = bounds.number("x"), y = bounds.number("y"), width = bounds.number("width"), height = bounds.number("height"),
                spaces = (window["space_ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty(),
                onScreen = window["is_on_screen"]?.jsonPrimitive?.booleanOrNull == true,
                windowId = window["window_id"]?.jsonPrimitive?.longOrNull,
            )
        } ?: return null
        return HostDesktops(root["current_space_id"]?.jsonPrimitive?.longOrNull, windows)
    }

    /** SkyLight's CGSCopyManagedDisplaySpaces, read by [SPACE_ORDER_SCRIPT]; type 4 is an app's full-screen space. */
    internal fun parseSpaceOrder(text: String): List<List<HostSpace>>? {
        val displays = runCatching { Json.parseToJsonElement(text.trim()) as? JsonArray }.getOrNull() ?: return null
        return displays.mapNotNull { display ->
            (display as? JsonArray)?.mapNotNull { space ->
                val item = space as? JsonObject ?: return@mapNotNull null
                HostSpace(
                    id = item["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    fullscreen = item["type"]?.jsonPrimitive?.intOrNull == 4,
                    pid = item["pid"]?.jsonPrimitive?.intOrNull,
                )
            }
        }.takeIf { it.isNotEmpty() }
    }

    private const val DRIVER = "PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\" cua-driver"

    /**
     * The desktops (macOS Spaces) of each display in Mission Control order, through SkyLight's
     * CGSCopyManagedDisplaySpaces from JavaScript for Automation; no permission needed.
     */
    private val SPACE_ORDER_SCRIPT = """
        ObjC.import('Cocoa');
        ObjC.bindFunction('CGSMainConnectionID', ['int', []]);
        ObjC.bindFunction('CGSCopyManagedDisplaySpaces', ['id', ['int']]);
        const displays = ObjC.deepUnwrap(${'$'}.CGSCopyManagedDisplaySpaces(${'$'}.CGSMainConnectionID())) || [];
        JSON.stringify(displays.map(d => (d.Spaces || []).map(s => ({ id: s.ManagedSpaceID, type: s.type, pid: s.pid }))));
    """.trimIndent()

    fun client(ssh: RemoteWorkspaceSession, port: Int, token: String): SpaceClient =
        SpaceClient(opener(ssh), port, token)

    private fun opener(ssh: RemoteWorkspaceSession) = SpaceStreamOpener { port, timeoutMillis ->
        val channel = ssh.openLoopbackStream(port, timeoutMillis)
        object : SpaceStream {
            override val input: InputStream get() = channel.input
            override val output: OutputStream get() = channel.output
            override val isConnected: Boolean get() = channel.isConnected
            override fun close() = channel.close()
        }
    }
}

/** The computer's desktops (macOS Spaces) and the windows on each, as cua-driver reports them. */
data class HostDesktops(val current: Long?, val windows: List<Placed>) {
    /** [windowId]: macOS's own window number (CGWindowID), the same for the window's whole life. */
    data class Placed(val pid: Int, val app: String = "", val layer: Int = 0, val z: Int = 0, val title: String, val x: Double, val y: Double, val width: Double, val height: Double,
        val spaces: List<Long>, val onScreen: Boolean, val windowId: Long? = null)

    /** The desktops [window] is on: matched by process, title and frame, since the two tools name windows differently. */
    fun find(pid: Int, title: String, x: Double, y: Double, width: Double, height: Double): Placed? =
        windows.filter { it.pid == pid && it.title == title }
            .minByOrNull { kotlin.math.abs(it.x - x) + kotlin.math.abs(it.y - y) + kotlin.math.abs(it.width - width) + kotlin.math.abs(it.height - height) }
}

/** A computer's own cua-spacesd: its port and token, and whether the Mac scrolls naturally. */
data class HostEndpoint(val port: Int, val token: String, val naturalScroll: Boolean) {
    /** Without the token, which would otherwise end up in any log line that prints this. */
    override fun toString() = "HostEndpoint(port=$port, naturalScroll=$naturalScroll)"
}

/** One desktop (macOS Space): a plain desktop, or one app's full-screen space ([pid]). */
data class HostSpace(val id: Long, val fullscreen: Boolean, val pid: Int?)
