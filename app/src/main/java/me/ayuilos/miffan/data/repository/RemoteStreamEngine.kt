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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import me.rerere.stream.StreamUdpTransport
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.relay.RelayDatagramSession
import me.rerere.workspace.relay.RelayException

internal data class StreamOpenResult(
    val session: StreamDesktopSession? = null,
    val fallback: RemoteStreamFallback? = null,
    val address: String? = null,
    val relayMediaKbps: Int? = null,
)

internal class RemoteStreamEngine(private val identities: RemoteStreamIdentityStore, private val routes: RemoteStreamRoutes, private val context: Context) {
    /** Debug instrumentation can configure an emulator codec without weakening release policy. */
    internal var configureSessionForTest: ((StreamSession) -> Unit)? = null
        set(value) { check(me.ayuilos.miffan.BuildConfig.DEBUG); field = value }
    private val pendingUntil = ConcurrentHashMap<String, Long>()
    fun host(ssh: RemoteWorkspaceSession, pin: String?, streamTimeoutMillis: Int = 3_000): StreamHost = StreamHost(StreamTcpConnector { port ->
        // :stream routes every HTTP/HTTPS/RTSP TCP port through this connector.
        ssh.openLoopbackStream(port, timeoutMillis = streamTimeoutMillis).let { StreamTcpChannel(it.input, it.output, it) }
    }, identities.get(), pin)

    fun verifyCertificate(stored: String?, probe: SunshineProbe) {
        verifyStreamCertificate(stored, probe.certificateSha256)
    }
    suspend fun status(ssh: RemoteWorkspaceSession, row: RemoteHostEntity, probe: SunshineProbe): RemoteStreamStatus {
        verifyCertificate(row.streamCertificateSha256, probe)
        val paired = if (!probe.installed || !probe.running) false
            else if (row.streamCertificateSha256 != null) withTimeout(10_000) { host(ssh, row.streamCertificateSha256).serverInfo().paired }
            else trustedPairing(ssh, probe) != null

        return RemoteStreamStatus(probe.installed, probe.version, probe.running, probe.encryptionEnforced, paired,
            probe.activeStream, candidates(row, probe).addresses, probe.displayAsleep,
            probe.permissions?.screenRecording, probe.permissions?.accessibility, probe.permissionsFromLog == true)
    }
    /**
     * Sunshine's certificate when Sunshine already trusts this phone although this installation has not
     * recorded it (pairing confirmed on the computer, then failed or was interrupted here). The certificate
     * comes from the helper over the verified SSH session, and the HTTPS check pins it, so a pairing seen
     * this way is as trustworthy as one made here. Null when not paired, or when Sunshine refuses this
     * phone's certificate.
     */
    suspend fun trustedPairing(ssh: RemoteWorkspaceSession, probe: SunshineProbe): String? {
        val certificate = probe.certificateSha256 ?: return null
        return try {
            certificate.takeIf { withTimeout(10_000) { host(ssh, certificate).serverInfo().paired } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) null else throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun candidates(row: RemoteHostEntity, probe: SunshineProbe, network: StreamNetworkSnapshot = routes.snapshot()): StreamCandidates = withContext(Dispatchers.IO) {
        orderStreamCandidates(routes.cached(row.id, row.connectionRevision, network.key), probe.candidates,
            resolveSavedAddresses(row), network)
    }

    // Every saved address, the last one that worked first; names resolve in parallel, each within its own wait.
    private suspend fun resolveSavedAddresses(row: RemoteHostEntity): List<String> = coroutineScope {
        row.connectionAddresses.map { address -> async { withTimeoutOrNull(3_000) { resolveStreamHost(address.host) }.orEmpty() } }
            .awaitAll().flatten()
    }

    suspend fun open(ssh: RemoteWorkspaceSession, row: RemoteHostEntity, probe: SunshineProbe,
        writeClipboard: (String) -> Unit, budgetMillis: Long = STREAM_OPEN_BUDGET_MILLIS, config: StreamConfig = RemoteScreenQuality.BALANCED.streamConfig(),
        platform: RemoteScreenPlatform = RemoteScreenPlatform.LINUX): StreamOpenResult =
        runPreflightAndOpen(ssh, row, probe, budgetMillis, streamTimeoutMillis = 3_000) { host, app ->
            val network = routes.snapshot()
            val ordered = candidates(row, probe, network)
            val attempted = mutableListOf<String>()
            for (address in ordered.addresses) {
                attempted += address
                val outcome = tryCandidate(host, row, probe, app, address, config, writeClipboard, platform, udpTransport = null)
                if (outcome.session != null) {
                    if (routes.snapshot().key == network.key) {
                        routes.success(row.id, row.connectionRevision, network.key, address)
                    }
                    return@runPreflightAndOpen outcome
                }
                if (outcome.fallback?.reason != RemoteStreamFallbackReason.UDP_UNREACHABLE) {
                    return@runPreflightAndOpen outcome
                }
            }
            unreachable(attempted, ordered.permissionSkipped)
        }

    suspend fun openRelayed(
        ssh: RemoteWorkspaceSession,
        row: RemoteHostEntity,
        probe: SunshineProbe,
        openDatagram: suspend () -> RelayDatagramSession,
        writeClipboard: (String) -> Unit,
        budgetMillis: Long = maxOf(STREAM_OPEN_BUDGET_MILLIS, 15_000L),
        config: StreamConfig = RemoteScreenQuality.BALANCED.streamConfig(),
        platform: RemoteScreenPlatform = RemoteScreenPlatform.LINUX,
    ): StreamOpenResult = runPreflightAndOpen(ssh, row, probe, budgetMillis, streamTimeoutMillis = 5_000) { host, app ->
        val dgram = try {
            openDatagram()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val relayError = generateSequence(error) { it.cause }.take(16)
                .filterIsInstance<RelayException>().firstOrNull() ?: throw error
            return@runPreflightAndOpen when (relayError.reason) {
                "dgram_disabled", "dgram_unsupported" ->
                    StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.RELAY))
                RelayException.UNREACHABLE, "udp_unreachable" ->
                    StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.UDP_UNREACHABLE, "Relay UDP unreachable"))
                else -> throw relayError
            }
        }
        val transport = object : StreamUdpTransport {
            override fun send(flow: Byte, payload: ByteArray, offset: Int, length: Int): Boolean =
                dgram.send(flow, payload, offset, length)
            override fun receive(buffer: ByteArray, flowOut: ByteArray): Int =
                dgram.receive(buffer, flowOut)
            override fun close() = dgram.close()
        }
        val effectiveConfig = config.cappedForRelay(dgram.ready.pace.kbps)
        val outcome = try {
            tryCandidate(host, row, probe, app, "127.0.0.1", effectiveConfig, writeClipboard, platform, udpTransport = transport)
        } catch (error: Throwable) {
            runCatching { dgram.close() }
            throw error
        }
        if (outcome.session != null) {
            outcome.copy(address = "${dgram.udpHost}:${dgram.udpPort}", relayMediaKbps = dgram.ready.pace.kbps)
        } else {
            runCatching { dgram.close() }
            outcome
        }
    }

    private suspend fun runPreflightAndOpen(
        ssh: RemoteWorkspaceSession,
        row: RemoteHostEntity,
        probe: SunshineProbe,
        budgetMillis: Long,
        streamTimeoutMillis: Int,
        block: suspend (host: StreamHost, app: StreamApp) -> StreamOpenResult,
    ): StreamOpenResult {
        verifyCertificate(row.streamCertificateSha256, probe)
        streamPreflightFallback(probe)?.let { return StreamOpenResult(fallback = RemoteStreamFallback(it)) }
        var host: StreamHost? = null
        val identityKey = "${row.id}:${row.connectionRevision}"
        try {
            return withTimeout(budgetMillis.coerceAtLeast(1)) {
                val created = host(ssh, row.streamCertificateSha256, streamTimeoutMillis)
                host = created
                val info = created.serverInfo()
                streamPreflightFallback(probe, info.paired)?.let { return@withTimeout StreamOpenResult(fallback = RemoteStreamFallback(it)) }
                if (row.streamCertificateSha256 == null) {
                    return@withTimeout StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.NOT_PAIRED))
                }
                val app = if (info.currentApp != 0) StreamApp(info.currentApp, "Desktop") else
                    created.apps().firstOrNull { it.name.equals("Desktop", ignoreCase = true) }
                        ?: return@withTimeout StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.HOST_REJECTED, "Desktop application unavailable"))
                pendingUntil[identityKey]?.let { until -> delay(((until - System.nanoTime()) / 1_000_000).coerceAtLeast(0)) }
                block(created, app)
            }
        } catch (error: RemoteStreamCertificateChangedException) { throw error }
        catch (error: RelayException) { throw error }
        catch (_: TimeoutCancellationException) { return StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.UDP_UNREACHABLE)) }
        catch (error: CancellationException) { throw error }
        catch (error: StreamException) {
            if (error.reason == StreamFailureReason.CERTIFICATE_MISMATCH)
                throw streamCertificateFailure(row.streamCertificateSha256, probe.certificateSha256)
            return StreamOpenResult(fallback = RemoteStreamFallback(streamFailureFallback(error.reason)))
        } catch (_: Exception) {
            return StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.OTHER))
        } finally {
            host?.retryAfterMillis?.takeIf { it > 0 }?.let { pendingUntil[identityKey] = System.nanoTime() + it * 1_000_000 }
        }
    }

    private suspend fun tryCandidate(
        host: StreamHost,
        row: RemoteHostEntity,
        probe: SunshineProbe,
        app: StreamApp,
        address: String,
        config: StreamConfig,
        writeClipboard: (String) -> Unit,
        platform: RemoteScreenPlatform,
        udpTransport: StreamUdpTransport?,
    ): StreamOpenResult {
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
            delegate = StreamSession(host, app, address, config, reader.surface, context, udpTransport)
            configureSessionForTest?.invoke(delegate)
            delegate.start(lifetime)
            return when (val state = delegate.state.first { it !is StreamState.Connecting }) {
                StreamState.Streaming -> {
                    delegate.setSurface(null)
                    val session = StreamDesktopSession(delegate, lifetime, row.streamCertificateSha256!!,
                        row.name.ifBlank { app.name }, writeClipboard, platform)
                    handedOff = true
                    StreamOpenResult(session = session, address = address)
                }
                is StreamState.Failed -> {
                    if (state.reason == StreamFailureReason.CERTIFICATE_MISMATCH)
                        throw streamCertificateFailure(row.streamCertificateSha256, probe.certificateSha256)
                    StreamOpenResult(fallback = RemoteStreamFallback(streamFailureFallback(state.reason)))
                }
                else -> StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.OTHER))
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
    private fun unreachable(attempted: List<String>, permissionSkipped: Boolean) = StreamOpenResult(fallback = RemoteStreamFallback(
        if (permissionSkipped) RemoteStreamFallbackReason.LOCAL_NETWORK_PERMISSION else RemoteStreamFallbackReason.UDP_UNREACHABLE,
        "Attempted: ${attempted.joinToString(", ")}"))
}
