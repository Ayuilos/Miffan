package me.rerere.workspace.screen

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.workspace.NativeSshWorkspaceTransport
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteHostConfig
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P0 on-device decode/render cost. Skipped unless instrumentation args spikeHost etc. are set. */
@RunWith(AndroidJUnit4::class)
class RemoteScreenSpikeInstrumentedTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private fun arg(name: String): String? = args.getString("spike$name")?.takeIf { it.isNotBlank() }

    private fun report(line: String) {
        Log.i("MiffanSpike", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString(android.app.Instrumentation.REPORT_KEY_STREAMRESULT, "SPIKE $line\n")
        })
    }

    @Test
    fun vncDecodeAndRenderCost() {
        assumeTrue(arg("Host") != null)
        val host = arg("Host")!!
        val port = arg("Port")?.toInt() ?: 22
        val fingerprint = NativeSshWorkspaceTransport.discoverHostKey(host, port).sha256Fingerprint
        val key = String(Base64.decode(arg("KeyB64")!!, Base64.DEFAULT))
        NativeSshWorkspaceTransport.open(
            RemoteHostConfig(host, port, arg("User")!!, RemoteAuthentication.PrivateKey(key), fingerprint, arg("Root")!!),
        ).use { ssh ->
            ssh.openLoopbackStream(arg("VncPort")?.toInt() ?: 5900).use { stream ->
                val client = RfbClient(stream.input, stream.output, RfbCredentials(arg("VncUser"), arg("VncPassword")))
                val t0 = System.nanoTime()
                val info = client.handshake()
                report("handshake ${ms(t0)} ms ${info.width}x${info.height} security=${info.securityType}")

                val tFull = System.nanoTime()
                client.requestUpdate(incremental = false)
                while (client.readMessage() !is RfbEvent.FramebufferUpdated) Unit
                report("full frame total ${ms(tFull)} ms, decode ${client.decodeNanos / 1_000_000} ms, ${client.bytesRead / 1024} KiB")

                val fb = client.framebuffer
                val bitmap = Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888)
                val tUpload = System.nanoTime()
                bitmap.setPixels(fb.pixels, 0, fb.width, 0, 0, fb.width, fb.height)
                report("setPixels full ${ms(tUpload)} ms")

                val decodeBefore = client.decodeNanos
                val bytesBefore = client.bytesRead
                var uploadNanos = 0L
                var updates = 0
                val start = System.nanoTime()
                while (System.nanoTime() - start < 5_000_000_000L) {
                    client.requestUpdate(incremental = true)
                    while (client.readMessage() !is RfbEvent.FramebufferUpdated) Unit
                    val u = System.nanoTime()
                    bitmap.setPixels(fb.pixels, 0, fb.width, 0, 0, fb.width, fb.height)
                    uploadNanos += System.nanoTime() - u
                    updates++
                }
                val runtime = Runtime.getRuntime()
                report("incremental $updates updates/5s, ${(client.bytesRead - bytesBefore) / 1024} KiB, " +
                    "decode ${(client.decodeNanos - decodeBefore) / 1_000_000} ms, setPixels ${uploadNanos / 1_000_000} ms")
                report("heap used ${(runtime.totalMemory() - runtime.freeMemory()) / 1048576} MiB of max ${runtime.maxMemory() / 1048576} MiB")
                bitmap.recycle()
            }
        }
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
}
