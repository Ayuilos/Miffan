package me.rerere.workspace

import com.jcraft.jsch.JSchException
import org.apache.sshd.server.SshServer
import org.apache.sshd.client.future.OpenFuture
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.channel.ChannelSessionFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeSshWorkspaceTransportTest {
    private lateinit var server: SshServer
    private lateinit var directory: Path
    private lateinit var root: Path
    private val fileReadDelayMillis = AtomicInteger()
    private val stallNextSftpRequest = AtomicBoolean(false)
    private val sftpRequestStalled = CountDownLatch(1)
    private val releaseSftpRequest = CountDownLatch(1)
    private val delayNextChannelOpen = AtomicBoolean(false)
    private val channelOpenRequested = CountDownLatch(1)
    private val releaseChannelOpen = CountDownLatch(1)
    private val startedCommand = AtomicReference<String?>()
    private val forwardedInputBytes = AtomicInteger()
    private val forwardedOutputBytes = AtomicInteger()
    private val commandFailure = AtomicReference<Throwable?>()

    @Before fun startServer() {
        directory = Files.createTempDirectory("miffan-ssh-test")
        root = Files.createDirectory(directory.resolve("workspace"))
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(directory.resolve("hostkey.ser"))
            passwordAuthenticator = { username, password, _ -> username == "test" && password == "secret" }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build().apply {
                addSftpEventListener(object : SftpEventListener {
                    override fun reading(
                        session: ServerSession, remoteHandle: String, localHandle: FileHandle,
                        offset: Long, data: ByteArray, dataOffset: Int, dataLen: Int,
                    ) {
                        val delay = fileReadDelayMillis.get()
                        if (delay > 0 && offset < Files.size(localHandle.file)) Thread.sleep(delay.toLong())
                    }
                    override fun received(session: ServerSession, type: Int, id: Int) {
                        if (stallNextSftpRequest.compareAndSet(true, false)) {
                            sftpRequestStalled.countDown()
                            releaseSftpRequest.await(10, TimeUnit.SECONDS)
                        }
                    }
                })
            })
            channelFactories = listOf(object : ChannelSessionFactory() {
                override fun createChannel(session: Session): Channel = object : ChannelSession() {
                    override fun open(
                        sender: Long,
                        initialWindowSize: Long,
                        maxPacketSize: Long,
                        buffer: Buffer,
                    ): OpenFuture {
                        if (delayNextChannelOpen.compareAndSet(true, false)) {
                            channelOpenRequested.countDown()
                            releaseChannelOpen.await(15, TimeUnit.SECONDS)
                        }
                        return super.open(sender, initialWindowSize, maxPacketSize, buffer)
                    }
                }
            })
            commandFactory = CommandFactory { _, command ->
                object : Command {
                    private lateinit var input: InputStream
                    private lateinit var output: OutputStream
                    private lateinit var error: OutputStream
                    private lateinit var callback: ExitCallback
                    private var process: Process? = null
                    override fun setInputStream(input: InputStream) { this.input = input }
                    override fun setOutputStream(output: OutputStream) { this.output = output }
                    override fun setErrorStream(error: OutputStream) { this.error = error }
                    override fun setExitCallback(callback: ExitCallback) { this.callback = callback }
                    override fun start(channel: ChannelSession, env: Environment) {
                        startedCommand.set(command)
                        val ptyRequested = env.env.containsKey(Environment.ENV_COLUMNS)
                        val launched = ProcessBuilder("/bin/sh", "-c", command)
                            .redirectErrorStream(ptyRequested)
                            .start()
                        process = launched
                        Thread {
                            runCatching {
                                input.use { source ->
                                    launched.outputStream.use { sink ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            val count = source.read(buffer)
                                            if (count < 0) break
                                            forwardedInputBytes.addAndGet(count)
                                            sink.write(buffer, 0, count)
                                            sink.flush()
                                        }
                                    }
                                }
                            }.onFailure(commandFailure::set)
                        }.apply { isDaemon = true; start() }
                        val stdoutPump = Thread {
                            runCatching {
                                launched.inputStream.use { source ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = source.read(buffer)
                                        if (count < 0) break
                                        forwardedOutputBytes.addAndGet(count)
                                        output.write(buffer, 0, count)
                                        output.flush()
                                    }
                                }
                            }.onFailure(commandFailure::set)
                        }
                        val stderrPump = if (ptyRequested) null
                            else Thread { launched.errorStream.use { it.copyTo(error) } }
                        stdoutPump.start()
                        stderrPump?.start()
                        Thread {
                            val exitCode = launched.waitFor()
                            stdoutPump.join()
                            stderrPump?.join()
                            runCatching { callback.onExit(exitCode) }
                        }.start()
                    }
                    override fun destroy(channel: ChannelSession) { process?.destroyForcibly() }
                }
            }
            start()
        }
    }

    @After fun stopServer() {
        releaseChannelOpen.countDown()
        releaseSftpRequest.countDown()
        server.stop(true)
        Files.walk(directory).use { files ->
            files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test fun slowDownloadKeepsConnectionWhileBytesContinueToArrive() {
        val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
        Files.write(root.resolve("slow.bin"), bytes)
        val key = NativeSshWorkspaceTransport.discoverHostKey("127.0.0.1", server.port)
        NativeSshWorkspaceTransport.open(config(key.sha256Fingerprint), sftpIdleTimeoutMillis = 500).use {
            fileReadDelayMillis.set(100)
            val started = System.nanoTime()
            assertTrue(bytes.contentEquals(it.readBytes("slow.bin")))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > 500)
            assertTrue(it.isConnected)
        }
    }

    @Test fun stalledFileRequestTimesOutEvenWhileSshIsAliveAndFreshConnectionWorks() {
        val key = NativeSshWorkspaceTransport.discoverHostKey("127.0.0.1", server.port)
        val workspace = NativeSshWorkspaceTransport.open(config(key.sha256Fingerprint), sftpIdleTimeoutMillis = 500)
        val failure = AtomicReference<Throwable?>()
        stallNextSftpRequest.set(true)
        val reader = Thread {
            try { workspace.list() } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(sftpRequestStalled.await(2, TimeUnit.SECONDS))
            reader.join(3_000)
            assertFalse("Stalled SFTP must not leave a blocked reader", reader.isAlive)
            assertTrue("Expected timeout, got ${failure.get()}", failure.get() is RemoteFileTimeoutException)
            assertFalse(workspace.isConnected)
        } finally {
            workspace.close()
            releaseSftpRequest.countDown()
            reader.join(2_000)
        }
        NativeSshWorkspaceTransport.open(config(key.sha256Fingerprint)).use {
            assertTrue(it.list().isEmpty())
        }
    }

    private fun config(fingerprint: String) = RemoteHostConfig(
        host = "127.0.0.1",
        port = server.port,
        username = "test",
        authentication = RemoteAuthentication.Password("secret"),
        trustedHostKeySha256 = fingerprint,
        remoteRoot = root.toString(),
    )

    @Test fun generatedPublicKeyAuthenticatesToKeyOnlyServer() {
        val accepted = SshKeyCodec.generate()
        server.passwordAuthenticator = { _, _, _ -> false }
        server.publickeyAuthenticator = { username, key, _ ->
            username == "test" && PublicKeyEntry.toString(key) == accepted.publicKey.substringBeforeLast(' ')
        }
        val hostKey = NativeSshWorkspaceTransport.discoverHostKey("127.0.0.1", server.port)
        val configuration = config(hostKey.sha256Fingerprint).copy(
            authentication = RemoteAuthentication.PrivateKey(accepted.privateKeyPem),
        )
        NativeSshWorkspaceTransport.open(configuration).use {
            assertEquals("authenticated", it.execute("printf authenticated").stdout)
        }
        val other = SshKeyCodec.generate()
        assertThrows(Exception::class.java) {
            NativeSshWorkspaceTransport.open(configuration.copy(
                authentication = RemoteAuthentication.PrivateKey(other.privateKeyPem),
            ))
        }
    }

    @Test fun discoversPinsAndUsesSshAndSftp() {
        val hostKey = NativeSshWorkspaceTransport.discoverHostKey("127.0.0.1", server.port)
        assertTrue(hostKey.sha256Fingerprint.startsWith("SHA256:"))
        assertTrue(hostKey.algorithm.isNotBlank())

        NativeSshWorkspaceTransport.open(config(hostKey.sha256Fingerprint)).use { workspace ->
            val command = workspace.execute("printf hello")
            assertEquals(0, command.exitCode)
            assertEquals("hello", command.stdout)
            assertFalse(command.timedOut)

            workspace.writeText("note.txt", "hello")
            assertEquals("hello", workspace.readText("/workspace/note.txt"))
            assertEquals("hello", workspace.execute("cat note.txt").stdout)
            assertEquals(0, workspace.execute("printf shell > shell.txt").exitCode)
            assertEquals("shell", workspace.readText("shell.txt"))
            assertEquals(5, workspace.fileSize(root.resolve("note.txt").toString()))
            assertTrue(workspace.list().any { it.path == "note.txt" })
            workspace.move("note.txt", "moved.txt")
            assertEquals("hello", workspace.readText("moved.txt"))
            assertTrue(workspace.delete("moved.txt"))
            assertFalse(workspace.delete("moved.txt"))
        }
    }

    @Test fun rejectsWrongPinAndPreservesFileAfterOversizedUpload() {
        val actual = NativeSshWorkspaceTransport.discoverHostKey("127.0.0.1", server.port)
        val wrong = actual.sha256Fingerprint.dropLast(1) +
            if (actual.sha256Fingerprint.last() == 'A') "B" else "A"
        assertThrows(Exception::class.java) { NativeSshWorkspaceTransport.open(config(wrong)) }

        NativeSshWorkspaceTransport.open(config(actual.sha256Fingerprint)).use { workspace ->
            workspace.writeText("important.txt", "original")
            assertThrows(Exception::class.java) {
                workspace.importFile(
                    "important.txt",
                    ByteArrayInputStream("too long".toByteArray()),
                    maxBytes = 3,
                )
            }
            assertEquals("original", workspace.readText("important.txt"))

            Files.setPosixFilePermissions(
                root.resolve("important.txt"),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
            workspace.writeText("important.txt", "replaced")
            assertEquals("replaced", workspace.readText("important.txt"))
            assertTrue(Files.getPosixFilePermissions(root.resolve("important.txt"))
                .contains(PosixFilePermission.OWNER_EXECUTE))
        }
    }

    @Test fun rejectsSymlinkInRemoteFilePath() {
        val actual = NativeSshWorkspaceTransport.discoverHostKey("127.0.0.1", server.port)
        Files.createSymbolicLink(root.resolve("link"), directory)
        NativeSshWorkspaceTransport.open(config(actual.sha256Fingerprint)).use { workspace ->
            assertThrows(IllegalArgumentException::class.java) { workspace.readText("link/hostkey.ser") }
        }
    }

    @Test fun boundsRemoteCommandDuration() {
        val actual = NativeSshWorkspaceTransport.discoverHostKey("127.0.0.1", server.port)
        NativeSshWorkspaceTransport.open(config(actual.sha256Fingerprint)).use { workspace ->
            val result = workspace.execute("sleep 3", timeoutMillis = 150)
            assertTrue(result.timedOut)
            assertEquals(-1, result.exitCode)
        }
    }

    @Test fun interactivePtyKeepsShellOpenAcrossCommandsAndClosesSession() {
        val fingerprint = NativeSshWorkspaceTransport
            .discoverHostKey("127.0.0.1", server.port).sha256Fingerprint
        Files.createDirectory(root.resolve("nested"))
        val workspace = NativeSshWorkspaceTransport.open(config(fingerprint))
        val terminal = workspace.openTerminal(columns = 80, rows = 24)
        val captured = StringBuilder()
        val firstOutput = CountDownLatch(1)
        val secondOutput = CountDownLatch(1)
        val readFailure = AtomicReference<Throwable?>()
        val reader = Thread {
            val buffer = ByteArray(1024)
            try {
                while (true) {
                    val count = terminal.input.read(buffer)
                    if (count < 0) break
                    synchronized(captured) {
                        captured.append(String(buffer, 0, count, StandardCharsets.UTF_8))
                        if (captured.contains("FIRST:${workspace.resolvedRoot}/nested")) firstOutput.countDown()
                        if (captured.contains("SECOND:alive")) secondOutput.countDown()
                    }
                }
            } catch (error: Exception) {
                readFailure.set(error)
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(terminal.isConnected)
            terminal.output.write(
                "export MIFFAN_SESSION_MARK=alive; cd nested; printf 'FIRST:%s\\n' \"\$PWD\"\n"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            terminal.output.flush()
            val gotFirst = firstOutput.await(5, TimeUnit.SECONDS)
            assertTrue(
                "first interactive command did not run in the remote root; output=${synchronized(captured) { captured.toString() }}; command=${startedCommand.get()}; inputBytes=${forwardedInputBytes.get()}; outputBytes=${forwardedOutputBytes.get()}; commandFailure=${commandFailure.get()}; readFailure=${readFailure.get()}; terminalConnected=${terminal.isConnected}",
                gotFirst,
            )

            terminal.resize(columns = 100, rows = 30)
            terminal.output.write("printf '%s%s%s\\n' SECOND : \"\$MIFFAN_SESSION_MARK\"\n".toByteArray(StandardCharsets.UTF_8))
            terminal.output.flush()
            val gotSecond = secondOutput.await(5, TimeUnit.SECONDS)
            assertTrue(
                "second command did not run in the same shell; output=${synchronized(captured) { captured.toString() }}",
                gotSecond,
            )
            terminal.sendCtrlC()
        } finally {
            terminal.close()
            reader.join(2_000)
        }
        assertFalse("Closing the terminal must unblock its output reader", reader.isAlive)
        assertFalse(terminal.isConnected)
        assertFalse(workspace.isConnected)
    }

    @Test fun failedTerminalStartupClosesOwningSshSession() {
        val fingerprint = NativeSshWorkspaceTransport
            .discoverHostKey("127.0.0.1", server.port).sha256Fingerprint
        val workspace = NativeSshWorkspaceTransport.open(config(fingerprint))
        delayNextChannelOpen.set(true)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                workspace.openTerminal().close()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        try {
            assertTrue("server did not receive terminal startup channel", channelOpenRequested.await(5, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(5_000)
            assertFalse("terminal startup did not finish", worker.isAlive)
            assertTrue("expected a channel-open failure, got ${failure.get()}", failure.get() is JSchException)
            assertFalse("failed terminal startup leaked the SSH session", workspace.isConnected)
        } finally {
            releaseChannelOpen.countDown()
            worker.join(5_000)
            workspace.close()
        }
    }

    @Test fun interruptedChannelHandshakeLooksLikeChannelOpenFailureAndFreshSessionWorks() {
        val fingerprint = NativeSshWorkspaceTransport
            .discoverHostKey("127.0.0.1", server.port).sha256Fingerprint
        delayNextChannelOpen.set(true)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                NativeSshWorkspaceTransport.open(config(fingerprint)).close()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        try {
            assertTrue("server did not receive the SFTP channel open", channelOpenRequested.await(5, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(5_000)
            assertFalse("interrupted channel open did not finish", worker.isAlive)
            assertTrue("expected a JSchException, got ${failure.get()}", failure.get() is JSchException)
            assertTrue(
                "expected JSch channel-open error, got ${failure.get()}",
                failure.get()?.message?.contains("channel is not opened") == true,
            )
        } finally {
            releaseChannelOpen.countDown()
            worker.join(5_000)
        }

        NativeSshWorkspaceTransport.open(config(fingerprint)).use { workspace ->
            assertTrue(workspace.list().isEmpty())
        }
    }
}
