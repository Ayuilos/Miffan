package me.ayuilos.miffan.data.repository

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.cuaspace.SpaceWindow
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession

/**
 * Single windows of a Linux computer through the helper's `window` command (remote-helper/PROTOCOL.md
 * 「单个窗口」): the compositor records one window (KDE Plasma's only cropped from its output where KWin
 * keeps window recording to itself); the helper encodes it and applies input.
 */
internal object LinuxHostWindows {
    private val json = Json { ignoreUnknownKeys = true }
    /** Window ids (niri numbers, KDE UUIDs) and sizes: nothing a shell would read specially. */
    private val ARGUMENT = Regex("[0-9a-f-]{1,64}")

    /** The helper's `window` command with [args]: a fixed operation, then window ids and numbers only. */
    fun command(operation: String, vararg args: String): String {
        require(operation in setOf("probe", "list", "focus", "resize", "shots", "serve", "authorize", "install-extension"))
        require(args.all { it.matches(ARGUMENT) })
        return (listOf("\"\$HOME/.miffan/bin/miffan\" window", operation) + args).joinToString(" ")
    }

    /** Whether this computer can show single windows: a Linux desktop the helper supports, with an encoder. */
    fun available(ssh: RemoteWorkspaceSession): Boolean {
        if (ssh.platform != RemotePlatform.POSIX) return false
        val result = ssh.execute(command("probe"), timeoutMillis = 20_000)
        if (result.exitCode != 0 || result.timedOut) return false
        return runCatching { json.decodeFromString<Probe>(result.stdout.trim().lines().last()).supported }.getOrDefault(false)
    }

    /** The windows and the workspaces holding them, current workspace first. */
    fun list(ssh: RemoteWorkspaceSession): RemoteHostWindows {
        val result = ssh.execute(command("list"), timeoutMillis = 15_000)
        check(result.exitCode == 0 && !result.timedOut) { "Listing windows failed" }
        return parse(result.stdout.trim().lines().last())
    }

    internal fun parse(text: String): RemoteHostWindows {
        val listed = json.decodeFromString<Listed>(text)
        // GNOME without the window-list extension: nothing to list until it is installed and loaded.
        if (listed.error == "needs_extension") {
            return RemoteHostWindows(emptyList(), emptyList(),
                if (listed.extension == "installed") RemoteWindowSetup.RELOGIN else RemoteWindowSetup.INSTALL_EXTENSION,
                appsInBackground = false)
        }
        val spaces = listed.workspaces.associateBy { it.id }
        val windows = listed.windows
            .sortedWith(compareBy({ spaces[it.workspace]?.id != listed.current }, { !it.focused }, { spaces[it.workspace]?.index ?: Int.MAX_VALUE }))
            .map { window ->
                RemoteHostWindow(
                    window = SpaceWindow(
                        id = window.id, epoch = 0, app = window.app.ifBlank { window.appId }, appId = window.appId,
                        pid = window.pid, title = window.title, x = 0.0, y = 0.0, width = window.width, height = window.height,
                        state = if (window.minimized) SpaceWindow.State.MINIMIZED else SpaceWindow.State.NORMAL,
                        available = true, limitation = null, focused = window.focused, zOrder = 0,
                    ),
                    desktop = window.workspace,
                    // Every output shows its active workspace.
                    current = spaces[window.workspace]?.active == true,
                    hidden = false,
                )
            }
        val used = windows.mapNotNull { it.desktop }.toSet()
        val outputs = listed.outputs
        val desktops = listed.workspaces.filter { it.id in used }
            .sortedWith(compareBy({ it.id != listed.current }, { outputs.indexOf(it.output) }, { it.index ?: Int.MAX_VALUE }))
            .map { space ->
                RemoteHostDesktop(space.id, current = space.id == listed.current, number = space.index,
                    display = (outputs.indexOf(space.output) + 1).takeIf { outputs.size > 1 && it > 0 })
            }
        return RemoteHostWindows(windows, desktops, appsInBackground = false)
    }

    /** GNOME: installs the window-list extension; GNOME loads it at the next login. Whether it was written. */
    fun installExtension(ssh: RemoteWorkspaceSession): Boolean {
        val result = ssh.execute(command("install-extension"), timeoutMillis = 30_000)
        return result.exitCode == 0 && !result.timedOut
    }

    /**
     * KDE: lets the helper use Plasma's RemoteDesktop portal without a dialog (a hidden desktop entry and
     * a kde-authorized grant), which the user agreed to on the phone. Whether it took.
     */
    fun authorize(ssh: RemoteWorkspaceSession): Boolean {
        val result = ssh.execute(command("authorize"), timeoutMillis = 20_000)
        return result.exitCode == 0 && !result.timedOut
    }

    fun focus(ssh: RemoteWorkspaceSession, window: SpaceWindow) {
        ssh.execute(command("focus", window.id), timeoutMillis = 10_000)
    }

    /**
     * Sets [window]'s size in logical pixels; the size it got, or null when the computer does not say
     * (niri sizes windows without telling). Throws when it failed.
     */
    fun resize(ssh: RemoteWorkspaceSession, window: SpaceWindow, width: Double, height: Double): Pair<Double, Double>? {
        val result = ssh.execute(command("resize", window.id, width.toInt().coerceAtLeast(1).toString(),
            height.toInt().coerceAtLeast(1).toString()), timeoutMillis = 10_000)
        check(result.exitCode == 0 && !result.timedOut) { "Resizing the window failed" }
        return parseSize(result.stdout)
    }

    /** `resize`'s `W H`, from its last line; null when it printed none. */
    internal fun parseSize(stdout: String): Pair<Double, Double>? {
        val (w, h) = stdout.trim().lines().last().trim().split(' ').mapNotNull(String::toDoubleOrNull)
            .takeIf { it.size == 2 && it.all { size -> size > 0 } } ?: return null
        return w to h
    }

    /**
     * Small pictures of [windows] through one `window shots`, each as it lands ([onPicture] with null when
     * a window gave none). Stops early once [active] says so.
     */
    fun thumbnails(ssh: RemoteWorkspaceSession, windows: List<SpaceWindow>, maxDimension: Int, active: () -> Boolean,
        onPicture: (SpaceWindow, ByteArray?) -> Unit) {
        val process = ssh.openProcess(command("shots"))
        try {
            val reader = Replies(process.input)
            for (window in windows) {
                if (!active()) break
                require(window.id.matches(ARGUMENT))
                process.output.write("${window.id} $maxDimension 70\n".toByteArray(Charsets.US_ASCII))
                process.output.flush()
                onPicture(window, reader.picture())
            }
        } finally {
            runCatching { process.output.close() }
            process.close()
        }
    }

    /** `window shots` replies: `jpeg LENGTH` and the bytes, or `error CODE`. */
    internal class Replies(input: InputStream) {
        private val input = DataInputStream(BufferedInputStream(input, 64 * 1024))

        fun picture(): ByteArray? {
            val words = line().split(' ')
            return when (words[0]) {
                "jpeg" -> {
                    val length = words.getOrNull(1)?.toIntOrNull()
                    check(length != null && length in 1..MAX_PICTURE) { "Unexpected picture length" }
                    ByteArray(length).also(input::readFully)
                }
                "error" -> null
                else -> error("Unexpected window picture reply")
            }
        }

        private fun line(): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("window shots ended")
                if (b == '\n'.code) break
                check(line.size() < 4096) { "window reply too long" }
                line.write(b)
            }
            return line.toString(Charsets.UTF_8.name()).removeSuffix("\r")
        }
    }

    private const val MAX_PICTURE = 8 * 1024 * 1024

    @Serializable
    private data class Probe(val supported: Boolean = false)

    @Serializable
    private data class Listed(
        val windows: List<Window> = emptyList(),
        val workspaces: List<Workspace> = emptyList(),
        val current: Long? = null,
        val outputs: List<String> = emptyList(),
        val error: String? = null,
        val extension: String? = null,
    )

    @Serializable
    private data class Window(
        val id: String,
        val pid: Int = 0,
        val app: String = "",
        @SerialName("app_id") val appId: String = "",
        val title: String = "",
        val workspace: Long? = null,
        val focused: Boolean = false,
        val width: Double = 0.0,
        val height: Double = 0.0,
        val minimized: Boolean = false,
    )

    @Serializable
    private data class Workspace(
        val id: Long,
        val index: Int? = null,
        val output: String? = null,
        val active: Boolean = false,
    )
}
