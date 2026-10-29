package me.rerere.workspace

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import me.rerere.cuaspace.SpaceInput
import me.rerere.cuaspace.SpaceMediaEvent
import me.rerere.cuaspace.SpaceClient
import me.rerere.cuaspace.SpaceStream
import me.rerere.cuaspace.SpaceStreamOpener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Against a real cua Space over SSH. Skipped unless MIFFAN_SPACE_TEST_HOST, _USER, _KEY (a private key
 * file) and _NAME (an existing Space on that host) are set. Leaves its files under ~/miffan-live-test
 * in the Space and removes them at the end.
 */
class SpaceWorkspaceFilesLiveTest {
    private val host = System.getenv("MIFFAN_SPACE_TEST_HOST")
    private val user = System.getenv("MIFFAN_SPACE_TEST_USER")
    private val key = System.getenv("MIFFAN_SPACE_TEST_KEY")
    private val name = System.getenv("MIFFAN_SPACE_TEST_NAME")
    private lateinit var ssh: Session
    private lateinit var client: SpaceClient
    private lateinit var files: SpaceWorkspaceFiles

    @Before fun connect() {
        assumeTrue(listOf(host, user, key, name).all { !it.isNullOrBlank() })
        ssh = JSch().apply { addIdentity(key) }.getSession(user, host, 22).apply {
            setConfig("StrictHostKeyChecking", "no")
            connect(10_000)
        }
        val credentials = exec("cat ~/.cua/spaces-credentials.json")
        val token = Regex("\"local:$name\"\\s*:\\s*\\{[^}]*\"token\"\\s*:\\s*\"([^\"]+)\"").find(credentials)!!.groupValues[1]
        val port = Regex(":(\\d+)\"").find(exec("~/.local/bin/cua --json sb info $name"))!!.groupValues[1].toInt()
        client = SpaceClient(SpaceStreamOpener { p, timeout ->
            val channel = ssh.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost("127.0.0.1")
            channel.setPort(p)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(timeout)
            object : SpaceStream {
                override val input: InputStream = input
                override val output: OutputStream = output
                override val isConnected: Boolean get() = channel.isConnected
                override fun close() = channel.disconnect()
            }
        }, port, token)
        files = SpaceWorkspaceFiles(client, "/home/cua/miffan-live-test")
        runBlocking { client.mkdir("/home/cua/miffan-live-test") }
    }

    @After fun disconnect() {
        if (!::ssh.isInitialized) return
        runCatching { runBlocking { client.remove("/home/cua/miffan-live-test", recursive = true, missingOk = true) } }
        client.close()
        ssh.disconnect()
    }

    private fun exec(command: String): String {
        val channel = ssh.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val input = channel.inputStream
        channel.connect(10_000)
        return input.readBytes().toString(Charsets.UTF_8).also { channel.disconnect() }
    }

    @Test fun filesCommandsAndShell() {
        val written = files.writeText("notes/交接.txt", "你好 Space\n")
        assertEquals("notes/交接.txt", written.path)
        assertEquals("你好 Space\n", files.readText("/workspace/notes/交接.txt"))
        assertEquals(listOf("notes"), files.list().map { it.path })
        assertEquals(listOf("notes/交接.txt"), files.list("notes").map { it.path })
        assertTrue(runCatching { files.writeText("notes/交接.txt", "x", overwrite = false) }.isFailure)

        files.move("notes/交接.txt", "done/交接.txt")
        assertEquals(13L, files.fileSize("done/交接.txt"))
        assertTrue(runCatching { files.readText("../../etc/passwd") }.isFailure)

        val result = files.execute("printf '%s' \"\$PWD\"; cat; echo oops >&2; exit 3", "done", 20_000, 1_048_576,
            "piped".byteInputStream())
        assertEquals(3, result.exitCode)
        assertEquals("/home/cua/miffan-live-test/donepiped", result.stdout)
        assertEquals("oops\n", result.stderr)

        val slow = files.execute("sleep 30", "", 1_000, 1_048_576, null)
        assertTrue(slow.timedOut)

        // The persistent shell protocol the conversation shells use, over a Space PTY.
        val terminal = files.openTerminal(80, 24, null, persistentCommands = true)
        val shell = PersistentRemoteShell(terminal.input, terminal.output, { terminal.close() })
        try {
            val first = runBlocking { shell.execute("cd done && export GREETING=hi", null, 10_000) }
            assertEquals(0, first.exitCode)
            val second = runBlocking { shell.execute("printf '%s %s' \"\$GREETING\" \"\$(basename \"\$PWD\")\"", null, 10_000) }
            assertEquals("hi done", second.stdout.trim())
        } finally {
            shell.close()
        }

        assertTrue(files.delete("done", recursive = true))
        assertFalse(files.delete("done", recursive = true))
    }

    /** The desktop as video: the session opens, a keyframe arrives first, and input is accepted. */
    @Test fun desktopMedia() = runBlocking {
        val media = client.openMedia(maxDimension = 1280, maxFps = 15)
        val seen = mutableListOf<SpaceMediaEvent>()
        val collector = launch {
            media.events.takeWhile { seen.add(it); seen.count { e -> e is SpaceMediaEvent.Frame } < 20 && it !is SpaceMediaEvent.Closed }
                .collect {}
        }
        delay(1500)
        media.send(listOf(SpaceInput.Pointer("move", null, 0.5, 0.5), SpaceInput.Pointer("move", null, 0.25, 0.75)))
        withTimeoutOrNull(6_000) { collector.join() }
        collector.cancel()
        for (event in seen.take(30)) when (event) {
            is SpaceMediaEvent.Frame -> println("frame seq=${event.sequence} epoch=${event.codecEpoch} ${event.width}x${event.height} key=${event.keyframe} bytes=${event.data.size}")
            else -> println(event)
        }
        val frames = seen.filterIsInstance<SpaceMediaEvent.Frame>()
        assertTrue("an Opened event", seen.any { it is SpaceMediaEvent.Opened })
        assertTrue("frames arrive", frames.isNotEmpty())
        assertTrue("the first frame is a keyframe", frames.first().keyframe)
        media.closeSession()
    }
}
