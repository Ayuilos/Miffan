package me.ayuilos.miffan.data.repository

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.rerere.stream.StreamApp
import me.rerere.stream.StreamConfig
import me.rerere.stream.StreamException
import me.rerere.stream.StreamFailureReason
import me.rerere.stream.StreamHost
import me.rerere.stream.StreamSession
import me.rerere.stream.StreamState
import me.rerere.stream.StreamTcpChannel
import me.rerere.stream.StreamTcpConnector
import me.rerere.workspace.RemoteWorkspaceSession

internal data class StreamOpenResult(val session: StreamDesktopSession? = null, val fallback: RemoteStreamFallback? = null, val address: String? = null)

internal class RemoteStreamEngine(private val identities: RemoteStreamIdentityStore, private val routes: RemoteStreamRoutes, private val context: Context) {
    private val pendingUntil = ConcurrentHashMap<String, Long>()
    fun host(ssh: RemoteWorkspaceSession, pin: String?): StreamHost = StreamHost(StreamTcpConnector { port ->
        // :stream routes every HTTP/HTTPS/RTSP TCP port through this connector.
        ssh.openLoopbackStream(port, timeoutMillis = 3_000).let { StreamTcpChannel(it.input, it.output, it) }
    }, identities.get(), pin)

    fun verifyCertificate(stored: String?, probe: SunshineProbe) {
        verifyStreamCertificate(stored, probe.certificateSha256)
    }
    suspend fun status(ssh: RemoteWorkspaceSession, row: RemoteHostEntity, probe: SunshineProbe): RemoteStreamStatus {
        verifyCertificate(row.streamCertificateSha256, probe)
        val paired = if (probe.installed && probe.running) withTimeout(10_000) { host(ssh, row.streamCertificateSha256).serverInfo().paired } else false
        return RemoteStreamStatus(probe.installed, probe.version, probe.running, probe.encryptionEnforced, paired,
            probe.activeStream, candidates(row, probe).addresses, probe.displayAsleep,
            probe.permissions?.screenRecording, probe.permissions?.accessibility, probe.permissionsFromLog == true)
    }
    private suspend fun candidates(row: RemoteHostEntity, probe: SunshineProbe, network: StreamNetworkSnapshot = routes.snapshot()): StreamCandidates = withContext(Dispatchers.IO) {
        orderStreamCandidates(routes.cached(row.id, row.connectionRevision, network.key), probe.candidates,
            withTimeoutOrNull(3_000) { resolveStreamHost(row.host) }.orEmpty(), network)
    }

    suspend fun open(ssh: RemoteWorkspaceSession, row: RemoteHostEntity, probe: SunshineProbe,
        writeClipboard: (String) -> Unit, budgetMillis: Long = 20_000, config: StreamConfig = RemoteScreenQuality.BALANCED.streamConfig(),
        platform: RemoteScreenPlatform = RemoteScreenPlatform.LINUX): StreamOpenResult {
        verifyCertificate(row.streamCertificateSha256, probe)
        streamPreflightFallback(probe)?.let { return StreamOpenResult(fallback = RemoteStreamFallback(it)) }
        val attempted = mutableListOf<String>()
        var permissionSkipped = false
        var host: StreamHost? = null
        var accepted: StreamDesktopSession? = null
        val identityKey = "${row.id}:${row.connectionRevision}"
        try {
            return withTimeout(budgetMillis.coerceAtLeast(1)) {
                // HTTP discovery, DNS, pending-session backoff and both candidates share this budget.
                host = host(ssh, row.streamCertificateSha256)
                val info = host!!.serverInfo()
                streamPreflightFallback(probe, info.paired)?.let { return@withTimeout StreamOpenResult(fallback = RemoteStreamFallback(it)) }
                // A lost/reinstalled device identity can leave the host paired but this installation unpinned.
                if (row.streamCertificateSha256 == null)
                    return@withTimeout StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.NOT_PAIRED))
                val network = routes.snapshot()
                val ordered = candidates(row, probe, network)
                permissionSkipped = ordered.permissionSkipped
                val app = if (info.currentApp != 0) StreamApp(info.currentApp, "Desktop") else
                    host!!.apps().firstOrNull { it.name.equals("Desktop", ignoreCase = true) }
                        ?: return@withTimeout StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.HOST_REJECTED, "Desktop application unavailable"))
                pendingUntil[identityKey]?.let { until -> delay(((until - System.nanoTime()) / 1_000_000).coerceAtLeast(0)) }
                for (address in ordered.addresses) {
                    attempted += address
                    val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    var delegate: StreamSession? = null
                    var handedOff = false
                    val drain = HandlerThread("StreamPreflightSurface").apply { start() }
                    var reader: ImageReader? = null
                    try {
                        reader = ImageReader.newInstance(config.width, config.height, ImageFormat.PRIVATE, 2)
                        reader.setOnImageAvailableListener({ source ->
                            runCatching { source.acquireLatestImage()?.close() }
                        }, Handler(drain.looper))
                        delegate = StreamSession(host!!, app, address, config, reader.surface, context)
                        delegate.start(lifetime)
                        when (val state = delegate.state.first { it !is StreamState.Connecting }) {
                            StreamState.Streaming -> {
                                delegate.setSurface(null)
                                val session = StreamDesktopSession(delegate, lifetime, row.streamCertificateSha256,
                                    row.name.ifBlank { app.name }, writeClipboard, platform)
                                if (routes.snapshot().key == network.key)
                                    routes.success(row.id, row.connectionRevision, network.key, address)
                                accepted = session
                                handedOff = true
                                return@withTimeout StreamOpenResult(session = session, address = address)
                            }
                            is StreamState.Failed -> {
                                if (state.reason == StreamFailureReason.CERTIFICATE_MISMATCH)
                                    throw streamCertificateFailure(row.streamCertificateSha256, probe.certificateSha256)
                                // UDP failures can improve with another route. Decoder/host failures cannot.
                                if (state.reason != StreamFailureReason.UDP_UNREACHABLE)
                                    return@withTimeout StreamOpenResult(fallback = RemoteStreamFallback(streamFailureFallback(state.reason)))
                            }
                            else -> return@withTimeout StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.OTHER))
                        }
                    } finally {
                        try {
                            if (!handedOff) {
                                delegate?.close(); lifetime.cancel()
                                val stopped = withContext(NonCancellable + Dispatchers.IO) { delegate?.awaitStopped(5_000) ?: true }
                                // Never start another native session while this one still owns processLock.
                                check(stopped) { "Stream cleanup did not finish" }
                            }
                        } finally { reader?.close(); drain.quitSafely() }
                    }
                }
                unreachable(attempted, permissionSkipped)
            }
        } catch (error: RemoteStreamCertificateChangedException) { throw error }
        catch (_: TimeoutCancellationException) { accepted?.close(); return unreachable(attempted, permissionSkipped) }
        catch (error: CancellationException) { accepted?.close(); throw error }
        catch (error: StreamException) {
            if (error.reason == StreamFailureReason.CERTIFICATE_MISMATCH)
                throw streamCertificateFailure(row.streamCertificateSha256, probe.certificateSha256)
            return StreamOpenResult(fallback = RemoteStreamFallback(streamFailureFallback(error.reason)))
        } catch (_: Exception) {
            accepted?.close()
            return StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.OTHER))
        } finally {
            host?.retryAfterMillis?.takeIf { it > 0 }?.let { pendingUntil[identityKey] = System.nanoTime() + it * 1_000_000 }
        }
    }
    private fun unreachable(attempted: List<String>, permissionSkipped: Boolean) = StreamOpenResult(fallback = RemoteStreamFallback(
        if (permissionSkipped) RemoteStreamFallbackReason.LOCAL_NETWORK_PERMISSION else RemoteStreamFallbackReason.UDP_UNREACHABLE,
        "Attempted: ${attempted.joinToString(", ")}"))
}
