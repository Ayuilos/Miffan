package me.rerere.workspace

import java.io.Closeable
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import me.rerere.workspace.relay.RelayChannel
import me.rerere.workspace.relay.RelayDeviceSigner
import me.rerere.workspace.relay.RelayEndpoint
import me.rerere.workspace.relay.RelaySocket
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory

/** A minimal sshd for relay tests: any public key signs in, SFTP, and commands run through /bin/sh. */
internal class TestSshd : Closeable {
    val directory: Path = Files.createTempDirectory("miffan-relay-ssh")
    val root: Path = Files.createDirectory(directory.resolve("workspace"))
    val key: SshKeyMaterial = SshKeyCodec.generate()
    val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider(directory.resolve("hostkey.ser"))
        publickeyAuthenticator = AcceptAllPublickeyAuthenticator.INSTANCE
        subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
        commandFactory = ProcessShellCommandFactory.INSTANCE
        start()
    }
    val fingerprint: String = NativeSshWorkspaceTransport.discoverHostKey("127.0.0.1", server.port).sha256Fingerprint

    fun config() = RemoteHostConfig(
        host = "relay-host", port = 22, username = "test",
        authentication = RemoteAuthentication.PrivateKey(key.privateKeyPem),
        trustedHostKeySha256 = fingerprint, remoteRoot = root.toString(),
    )

    override fun close() {
        server.stop(true)
        Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

/**
 * Plays the relay and the connector on one WebSocket: `ready` with [idleTimeoutS], a challenge,
 * `ok` for any device, then SSH bytes to and from the test sshd. Records what the phone sent.
 */
internal class FakeRelayBridge(private val sshdPort: Int, private val idleTimeoutS: Int = 300) : Closeable {
    val web = MockWebServer().apply { start() }
    /** Text frames the phone sent after `ok`, other than `ping`. */
    val textAfterOk = CopyOnWriteArrayList<String>()
    val pings = AtomicInteger()
    val binaryFromPhone = AtomicInteger()
    @Volatile var relaySide: WebSocket? = null

    val endpoint get() = RelayEndpoint(web.url("/").toString().trimEnd('/'))

    fun expectSession() {
        web.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            @Volatile private var tcp: Socket? = null

            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                relaySide = webSocket
                webSocket.send("""{"t":"ready","sid":"0123456789abcdef0123456789abcdef","ch":"control",""" +
                    """"pace":{"kbps":0,"burstKb":0},"policy":{"limits":{"idleTimeoutS":$idleTimeoutS}}}""")
                webSocket.send("""{"t":"challenge","v":1,"nonce":"${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))}"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "ping") { pings.incrementAndGet(); webSocket.send("pong"); return }
                if (tcp != null) { textAfterOk += text; return }
                if (!text.contains("\"auth\"")) return
                val socket = Socket("127.0.0.1", sshdPort)
                tcp = socket
                webSocket.send("""{"t":"ok"}""")
                Thread {
                    runCatching {
                        val buffer = ByteArray(32 * 1024)
                        val input = socket.getInputStream()
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            webSocket.send(buffer.toByteString(0, n))
                        }
                    }
                    webSocket.close(1000, "peer_closed")
                }.apply { isDaemon = true; start() }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                binaryFromPhone.incrementAndGet()
                runCatching { tcp?.getOutputStream()?.apply { write(bytes.toByteArray()); flush() } }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runCatching { tcp?.close() }
                webSocket.close(1000, null)
            }
        }).build())
    }

    override fun close() {
        runCatching { relaySide?.cancel() }
        web.close()
    }
}

internal object AnyDevice : RelayDeviceSigner {
    override val publicKeySpki: ByteArray = ByteArray(1)
    override fun sign(data: ByteArray): ByteArray = ByteArray(1)
}

/** Opens [sshd]'s workspace through [bridge], as the app's relay route does. */
internal fun openThroughBridge(sshd: TestSshd, bridge: FakeRelayBridge, client: OkHttpClient = OkHttpClient(),
    configure: (RemoteHostConfig) -> RemoteHostConfig = { it }): Pair<RemoteWorkspaceSession, RelaySocket> {
    bridge.expectSession()
    var socket: RelaySocket? = null
    val factory = object : com.jcraft.jsch.SocketFactory {
        override fun createSocket(host: String, port: Int): Socket =
            RelaySocket.open(client, bridge.endpoint.baseUrl, null, "abcdefghijklmnopqrstuvwxyz", RelayChannel.CONTROL,
                AnyDevice).also { socket = it }
        override fun getInputStream(socket: Socket) = socket.getInputStream()
        override fun getOutputStream(socket: Socket) = socket.getOutputStream()
    }
    val session = NativeSshWorkspaceTransport.open(configure(sshd.config()), socketFactory = factory, keepAlive = false,
        route = { RemoteRoute.Relay(bridge.endpoint, "abcdefghijklmnopqrstuvwxyz", requireNotNull(socket)) })
    return session to requireNotNull(socket)
}
