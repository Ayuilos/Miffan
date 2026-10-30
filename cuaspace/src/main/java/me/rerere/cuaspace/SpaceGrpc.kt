package me.rerere.cuaspace

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import java.io.IOException
import java.net.URLDecoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource

/** A non-OK gRPC status from cua-spacesd, or a transport failure mapped to UNAVAILABLE. */
class SpaceGrpcException(val status: Int, message: String) : IOException(message) {
    val isNotFound: Boolean get() = status == NOT_FOUND

    companion object {
        const val CANCELLED = 1
        const val INVALID_ARGUMENT = 3
        const val DEADLINE_EXCEEDED = 4
        const val NOT_FOUND = 5
        const val ALREADY_EXISTS = 6
        const val PERMISSION_DENIED = 7
        const val FAILED_PRECONDITION = 9
        const val UNIMPLEMENTED = 12
        const val UNAVAILABLE = 14
        const val UNAUTHENTICATED = 16
    }
}

/**
 * Minimal gRPC over HTTP/2 cleartext (prior knowledge) on the caller's OkHttp client: unary,
 * server-streaming, and client-streaming calls whose messages are all known up front. The messages
 * are Wire classes generated from the vendored cua protos. No compression.
 */
class SpaceGrpc(
    client: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
) {
    private val client = client.newBuilder()
        .protocols(listOf(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE))
        .build()

    suspend fun <Req : Message<Req, *>, Resp> unary(
        method: String,
        request: Req,
        responseAdapter: ProtoAdapter<Resp>,
    ): Resp = clientStream(method, listOf(request), responseAdapter)

    suspend fun <Req : Message<Req, *>, Resp> clientStream(
        method: String,
        requests: List<Req>,
        responseAdapter: ProtoAdapter<Resp>,
    ): Resp {
        val body = Buffer()
        requests.forEach { writeFrame(body, it.encode()) }
        return clientStream(method, body.readByteArray().toRequestBody(GRPC), responseAdapter)
    }

    /**
     * A client stream whose requests [next] produces one at a time while the body is sent (null ends it),
     * so a large upload never sits in memory whole.
     */
    suspend fun <Req : Message<Req, *>, Resp> clientStream(
        method: String,
        responseAdapter: ProtoAdapter<Resp>,
        next: () -> Req?,
    ): Resp = clientStream(method, object : RequestBody() {
        override fun contentType() = GRPC
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            while (true) {
                val message = next() ?: break
                val bytes = message.encode()
                sink.writeByte(0).writeInt(bytes.size).write(bytes)
            }
        }
    }, responseAdapter)

    private suspend fun <Resp> clientStream(
        method: String,
        body: RequestBody,
        responseAdapter: ProtoAdapter<Resp>,
    ): Resp = withContext(Dispatchers.IO) {
        newCall(method, body).await().use { response ->
            requireHttpOk(response)
            val source = response.body.source()
            val message = readFrame(source)
            while (readFrame(source) != null) Unit
            checkStatus(response)
            responseAdapter.decode(message ?: throw SpaceGrpcException(SpaceGrpcException.UNAVAILABLE, "$method returned no message"))
        }
    }

    /** Emits each response message; cancelling the collector cancels the call. */
    fun <Req : Message<Req, *>, Resp> serverStream(
        method: String,
        request: Req,
        responseAdapter: ProtoAdapter<Resp>,
    ): Flow<Resp> = flow {
        val bytes = Buffer().also { writeFrame(it, request.encode()) }.readByteArray()
        val call = newCall(method, bytes.toRequestBody(GRPC))
        val cancelOnCompletion = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                requireHttpOk(response)
                val source = response.body.source()
                while (true) {
                    val message = readFrame(source) ?: break
                    emit(responseAdapter.decode(message))
                }
                checkStatus(response)
            }
        } catch (error: IOException) {
            if (error is SpaceGrpcException || !call.isCanceled()) throw error.asGrpc(method)
            throw kotlinx.coroutines.CancellationException("$method cancelled")
        } finally {
            cancelOnCompletion?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    private fun newCall(method: String, body: RequestBody): Call = client.newCall(
        Request.Builder()
            .url("$baseUrl/$method")
            .header("authorization", "Bearer $token")
            .header("te", "trailers")
            .header("grpc-accept-encoding", "identity")
            .post(body)
            .build(),
    )

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e.asGrpc(call.request().url.encodedPath.removePrefix("/")))
            }
        })
    }

    private fun requireHttpOk(response: Response) {
        if (response.code != 200) {
            // A trailers-only error still arrives with HTTP 200; anything else is the transport.
            throw SpaceGrpcException(
                if (response.code == 401) SpaceGrpcException.UNAUTHENTICATED else SpaceGrpcException.UNAVAILABLE,
                "HTTP ${response.code}",
            )
        }
        statusOf(response.headers["grpc-status"], response.headers["grpc-message"])?.let { throw it }
    }

    private fun checkStatus(response: Response) {
        val trailers = response.trailers()
        statusOf(trailers["grpc-status"], trailers["grpc-message"])?.let { throw it }
    }

    private fun statusOf(code: String?, message: String?): SpaceGrpcException? {
        val status = code?.toIntOrNull() ?: return null
        if (status == 0) return null
        val text = message?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        return SpaceGrpcException(status, text ?: "gRPC status $status")
    }

    private fun IOException.asGrpc(method: String): IOException =
        this as? SpaceGrpcException ?: SpaceGrpcException(SpaceGrpcException.UNAVAILABLE, "$method: ${message ?: javaClass.simpleName}")
            .also { it.initCause(this) }

    companion object {
        private val GRPC = "application/grpc".toMediaType()

        internal fun writeFrame(sink: Buffer, message: ByteArray) {
            sink.writeByte(0).writeInt(message.size).write(message)
        }

        /** Reads one length-prefixed message, or null at the clean end of the body. */
        internal fun readFrame(source: BufferedSource): ByteArray? {
            if (source.exhausted()) return null
            val compressed = source.readByte().toInt()
            if (compressed != 0) throw SpaceGrpcException(SpaceGrpcException.UNIMPLEMENTED, "Compressed gRPC message")
            val length = source.readInt()
            if (length < 0 || length > MAX_MESSAGE_BYTES) {
                throw SpaceGrpcException(SpaceGrpcException.FAILED_PRECONDITION, "gRPC message of $length bytes")
            }
            return source.readByteArray(length.toLong())
        }

        private const val MAX_MESSAGE_BYTES = 64 * 1024 * 1024
    }
}
