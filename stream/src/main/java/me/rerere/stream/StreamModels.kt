package me.rerere.stream

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

fun interface StreamTcpConnector { fun open(remotePort: Int): StreamTcpChannel }
class StreamTcpChannel(val input: InputStream, val output: OutputStream, val closeable: Closeable)
data class StreamIdentity(val certificatePem: String, val privateKeyPem: String, val uniqueId: String)
object StreamIdentities {
    fun generate(): StreamIdentity {
        val pem = StreamNative.identity()
        return StreamIdentity(pem[0], pem[1], java.util.UUID.randomUUID().toString().replace("-", "").take(16))
    }
}
enum class StreamCodec { HEVC, H264 }
data class StreamApp(val id: Int, val name: String)
data class StreamServerInfo(val version: String, val paired: Boolean, val currentApp: Int,
    val codecModeSupport: Int, val gfeVersion: String = "3.23.0.74")
data class StreamConfig(val width: Int, val height: Int, val fps: Int, val bitrateKbps: Int,
    val codecs: Set<StreamCodec> = setOf(StreamCodec.HEVC, StreamCodec.H264), val connectTimeoutMillis: Long = 4000) {
    init {
        require(width in 1..8192 && height in 1..8192 && fps in 1..240 && bitrateKbps in 1..150000)
        require(codecs.isNotEmpty() && connectTimeoutMillis in 100..120000)
    }
}
enum class StreamFailureReason { CERTIFICATE_MISMATCH, ENCRYPTION_REQUIRED, UDP_UNREACHABLE, TCP_RTSP, HOST_REJECTED, DECODER, BUSY, CANCELLED }
sealed interface StreamState {
    data object Connecting : StreamState
    data object Streaming : StreamState
    data class Failed(val reason: StreamFailureReason, val stage: Int, val code: Int) : StreamState
    data object Closed : StreamState
}
class StreamException(val reason: StreamFailureReason, val stage: Int = 0, val code: Int = 0) :
    java.io.IOException("Stream failure: $reason (stage=$stage code=$code)")
internal fun streamFailure(stage: Int, code: Int): StreamState.Failed = StreamState.Failed(when {
    code == -110 -> StreamFailureReason.ENCRYPTION_REQUIRED
    code in 400..599 -> StreamFailureReason.HOST_REJECTED
    code == -200 -> StreamFailureReason.DECODER
    code == -100 || code == -101 || stage == 8 || stage == 9 -> StreamFailureReason.UDP_UNREACHABLE
    else -> StreamFailureReason.TCP_RTSP
}, stage, code)
enum class StreamMouseButton(val wire: Int) { LEFT(1), MIDDLE(2), RIGHT(3), X1(4), X2(5) }
data class StreamStats(
    val codec: StreamCodec? = null, val width: Int = 0, val height: Int = 0, val fps: Int = 0,
    val videoEncrypted: Boolean = false, val audioEncrypted: Boolean = false, val controlEncrypted: Boolean = false,
    val receivedFrames: Long = 0, val renderedFrames: Long = 0, val receivedFps: Double = 0.0,
    val renderedFps: Double = 0.0, val decodeMeanMs: Double = 0.0, val decodeMaxMs: Double = 0.0,
    val droppedFrames: Long = 0, val bitrateKbps: Double = 0.0, val rttMs: Int? = null,
    val decoderName: String? = null, val lowLatency: Boolean = false, val hardwareAccelerated: Boolean = false,
    val audioPackets: Long = 0, val retryAfterMillis: Long = 0,
    val fecFailureEvents: Long = 0, val idrRequestsSent: Long = 0,
)
