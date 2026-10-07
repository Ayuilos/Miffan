package me.rerere.workspace.screen

import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.workspace.NativeSshWorkspaceTransport
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteHostConfig
import me.rerere.workspace.RemoteWorkspaceSession
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * P0 feasibility probe against a real host. Skipped unless MIFFAN_SPIKE_HOST is set:
 * MIFFAN_SPIKE_HOST, _PORT, _USER, _KEY (private key path), _ROOT (an existing directory),
 * _VNC_PORT, _VNC_USER/_VNC_PASSWORD (only when the server needs auth), _CUA (driver command),
 * _OUT (directory for captured PNGs).
 */
class RemoteScreenSpikeTest {
    private fun env(name: String): String? = System.getenv("MIFFAN_SPIKE_$name")?.takeIf { it.isNotBlank() }

    private fun open(): RemoteWorkspaceSession {
        val host = env("HOST")!!
        val port = env("PORT")?.toInt() ?: 22
        val fingerprint = NativeSshWorkspaceTransport.discoverHostKey(host, port).sha256Fingerprint
        println("host key $fingerprint (trusted on first use: spike only)")
        return NativeSshWorkspaceTransport.open(
            RemoteHostConfig(
                host = host,
                port = port,
                username = env("USER")!!,
                authentication = RemoteAuthentication.PrivateKey(File(env("KEY")!!).readText()),
                trustedHostKeySha256 = fingerprint,
                remoteRoot = env("ROOT")!!,
            ),
        )
    }

    @Test
    fun vncOverSshTunnel() {
        assumeTrue(env("HOST") != null && env("VNC_PORT") != null)
        open().use { ssh ->
            val t0 = System.nanoTime()
            ssh.openLoopbackStream(env("VNC_PORT")!!.toInt()).use { stream ->
                val credentials = if (env("VNC_PASSWORD") != null) RfbCredentials(env("VNC_USER"), env("VNC_PASSWORD")) else null
                val jpeg = if (env("NO_JPEG") == null) RfbJpegDecoder { bytes, length, fb, x, y, w, h ->
                    val image = ImageIO.read(java.io.ByteArrayInputStream(bytes, 0, length))
                    image.getRGB(0, 0, w, h, fb.pixels, y * fb.width + x, fb.width)
                } else null
                val client = RfbClient(stream.input, stream.output, credentials, jpeg)
                val info = client.handshake()
                println("server ${info.protocolVersion} offered=${info.securityTypes} chose=${info.securityType} " +
                    "${info.width}x${info.height} '${info.name}' in ${ms(t0)} ms")

                val tFull = System.nanoTime()
                client.requestUpdate(incremental = false)
                var event = client.readMessage()
                while (event !is RfbEvent.FramebufferUpdated) event = client.readMessage()
                val fullBytes = client.bytesRead
                println("full frame: ${ms(tFull)} ms, decode ${client.decodeNanos / 1_000_000} ms, ${fullBytes / 1024} KiB, encodings=${event.encodings}")
                save(client.framebuffer, "vnc-full.png")

                val window = 5_000L
                val start = System.nanoTime()
                val before = client.bytesRead
                var updates = 0
                while ((System.nanoTime() - start) / 1_000_000 < window) {
                    client.requestUpdate(incremental = true)
                    var e = client.readMessage()
                    while (e !is RfbEvent.FramebufferUpdated && e !is RfbEvent.Resized) e = client.readMessage()
                    updates++
                }
                val kib = (client.bytesRead - before) / 1024
                println("incremental: $updates updates in ${window / 1000}s, $kib KiB (${kib * 1000 / window} KiB/s)")
                save(client.framebuffer, "vnc-last.png")
            }
        }
    }

    /** Local-only probe for encoding negotiation; the SSH tunnel itself is covered above. */
    @Test
    fun vncDirectEncodingProbe() {
        assumeTrue(env("DIRECT_VNC") != null)
        java.net.Socket("127.0.0.1", env("DIRECT_VNC")!!.toInt()).use { socket ->
            val jpeg = RfbJpegDecoder { bytes, length, fb, x, y, w, h ->
                val image = ImageIO.read(java.io.ByteArrayInputStream(bytes, 0, length))
                image.getRGB(0, 0, w, h, fb.pixels, y * fb.width + x, fb.width)
            }
            val client = RfbClient(socket.getInputStream(), socket.getOutputStream(),
                RfbCredentials(env("VNC_USER"), env("VNC_PASSWORD")), jpeg)
            val info = client.handshake()
            println("server ${info.protocolVersion} ${info.width}x${info.height} security=${info.securityType}")
            val t = System.nanoTime()
            client.requestUpdate(incremental = false)
            var event = client.readMessage()
            while (event !is RfbEvent.FramebufferUpdated) event = client.readMessage()
            println("full frame: ${ms(t)} ms, decode ${client.decodeNanos / 1_000_000} ms, ${client.bytesRead / 1024} KiB, encodings=${event.encodings}")
            save(client.framebuffer, "vnc-direct.png")
            val before = client.bytesRead
            val start = System.nanoTime()
            var updates = 0
            val seen = mutableSetOf<Int>()
            while ((System.nanoTime() - start) / 1_000_000 < 5_000) {
                client.requestUpdate(incremental = true)
                var e = client.readMessage()
                while (e !is RfbEvent.FramebufferUpdated && e !is RfbEvent.Resized) e = client.readMessage()
                if (e is RfbEvent.FramebufferUpdated) seen += e.encodings
                updates++
            }
            println("incremental: $updates updates in 5s, ${(client.bytesRead - before) / 1024} KiB, encodings=$seen")
        }
    }

    @Test
    fun vncKeyboardInput() {
        assumeTrue(env("HOST") != null && env("VNC_PORT") != null && env("VNC_TYPE") != null)
        open().use { ssh ->
            ssh.openLoopbackStream(env("VNC_PORT")!!.toInt()).use { stream ->
                val client = RfbClient(stream.input, stream.output, null)
                client.handshake()
                for (ch in env("VNC_TYPE")!!) {
                    client.key(ch.code, down = true)
                    client.key(ch.code, down = false)
                }
                client.key(0xFF0D, down = true) // Return
                client.key(0xFF0D, down = false)
                Thread.sleep(500)
                println("sent ${env("VNC_TYPE")} + Return")
            }
        }
    }

    @Test
    fun cuaDriverMcpOverSsh() {
        assumeTrue(env("HOST") != null && env("CUA") != null)
        open().use { ssh ->
            ssh.openProcess("${env("CUA")} mcp").use { process ->
                val reader = BufferedReader(InputStreamReader(process.input, Charsets.UTF_8))
                var id = 0
                fun call(method: String, params: String): JsonObject {
                    id++
                    val line = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""" + "\n"
                    process.output.write(line.toByteArray())
                    process.output.flush()
                    val t = System.nanoTime()
                    val response = Json.parseToJsonElement(reader.readLine() ?: error("MCP closed")).jsonObject
                    println("$method ${ms(t)} ms")
                    return response
                }
                call("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"miffan-spike","version":"0"}}""")
                process.output.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n".toByteArray())
                process.output.flush()
                val tools = call("tools/list", "{}")["result"]!!.jsonObject["tools"]!!.jsonArray
                println("tools: ${tools.size}")
                println(call("tools/call", """{"name":"check_permissions","arguments":{}}""").toString().take(400))
                val desktop = call("tools/call", """{"name":"get_desktop_state","arguments":{"max_image_dimension":1280}}""")
                val content = desktop["result"]!!.jsonObject["content"]!!.jsonArray
                content.forEach { part ->
                    val obj = part.jsonObject
                    when (obj["type"]!!.jsonPrimitive.content) {
                        "image" -> {
                            val png = Base64.getDecoder().decode(obj["data"]!!.jsonPrimitive.content)
                            println("screenshot ${png.size / 1024} KiB ${obj["mimeType"]}")
                            outDir()?.let { File(it, "cua-desktop.png").writeBytes(png) }
                        }
                        else -> println(obj["text"]?.jsonPrimitive?.content?.take(300))
                    }
                }
                val structured = desktop["result"]!!.jsonObject["structuredContent"]?.jsonObject
                println("screen ${structured?.get("screen_width")?.jsonPrimitive?.int}x${structured?.get("screen_height")?.jsonPrimitive?.int}")
            }
        }
    }

    private fun outDir(): File? = env("OUT")?.let(::File)?.also { it.mkdirs() }

    private fun save(fb: Framebuffer, name: String) {
        val dir = outDir() ?: return
        val image = BufferedImage(fb.width, fb.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, fb.width, fb.height, fb.pixels, 0, fb.width)
        ImageIO.write(image, "png", File(dir, name))
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
}
