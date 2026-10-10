package me.rerere.stream

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.stream.StreamProtocol.value
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class StreamHost(internal val connector: StreamTcpConnector, internal val identity: StreamIdentity,
    pinnedCertificateSha256: String?) {
    @Volatile internal var pin: String? = pinnedCertificateSha256?.lowercase()?.also {
        require(it.length == 64); StreamProtocol.unhex(it)
    }
    @Volatile private var pendingUntil = 0L
    /** Sunshine's pending RTSP key cannot be replaced until its control connection or timeout. */
    val retryAfterMillis: Long get() = ((pendingUntil - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
    internal fun deferLaunch(httpCompletedAt: Long) { pendingUntil = maxOf(pendingUntil, httpCompletedAt + 11_000_000_000L) }
    internal fun controlEstablished() { pendingUntil = 0 }
    private suspend fun <T> io(block: (TcpBridge) -> T): T = withContext(Dispatchers.IO) {
        val bridge = TcpBridge(connector)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { bridge.close() } }
        try { block(bridge) } finally { cancellation.cancel(); bridge.close() }
    }
    suspend fun serverInfo(): StreamServerInfo = io { bridge ->
        StreamProtocol.server(request("serverinfo", secure = pin != null, bridge = bridge))
    }
    suspend fun apps(): List<StreamApp> = io { bridge -> StreamProtocol.apps(request("applist", bridge = bridge)) }
    internal fun request(route: String, params: Map<String, String> = emptyMap(), secure: Boolean = true,
        bridge: TcpBridge = TcpBridge(connector), timeout: Int = 20000, certificatePin: String? = pin): org.w3c.dom.Element {
        val path = StreamProtocol.path(route, mapOf("uniqueid" to identity.uniqueId, "uuid" to UUID.randomUUID().toString()) + params)
        if (secure && certificatePin == null) throw StreamException(StreamFailureReason.HOST_REJECTED)
        val fd = bridge.open(if (secure) 47984 else 47989)
        try {
            val bytes = StreamNative.http(fd, identity.certificatePem, identity.privateKeyPem,
                if (secure) certificatePin!! else "", "GET $path HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(), timeout)
            return StreamProtocol.xml(StreamProtocol.httpBody(bytes))
        } catch (e: java.io.IOException) {
            if (e is StreamException) throw e
            throw StreamException(if (e.message == "CERTIFICATE_MISMATCH") StreamFailureReason.CERTIFICATE_MISMATCH else StreamFailureReason.TCP_RTSP)
        } finally { bridge.closeChannel(fd) }
    }
    @Synchronized private fun acceptPin(value: String) { pin = value }
    suspend fun pair(pin: String, deviceName: String): String = io { bridge ->
        require(pin.length == 4 && pin.all { it in '0'..'9' }); require(deviceName.length in 1..64)
        val random = SecureRandom()
        fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        fun certificate(pem: ByteArray) = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem)) as X509Certificate
        val salt = bytes(16); val key = sha(salt + pin.toByteArray()).copyOf(16)
        fun aes(b: ByteArray, encrypt: Boolean): ByteArray = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(b)
        }
        fun step(params: Map<String, String>, secure: Boolean = false, certPin: String? = this@StreamHost.pin): org.w3c.dom.Element {
            val result = request("pair", mapOf("devicename" to deviceName, "updateState" to "1") + params,
                secure, bridge = bridge, timeout = 300000, certificatePin = certPin)
            if (result.value("paired") != "1") throw StreamException(StreamFailureReason.HOST_REJECTED)
            return result
        }
        val server = certificate(StreamProtocol.unhex(step(mapOf("phrase" to "getservercert", "salt" to StreamProtocol.hex(salt),
            "clientcert" to StreamProtocol.hex(identity.certificatePem.toByteArray()))).value("plaincert")))
        val actualPin = StreamProtocol.hex(sha(server.encoded))
        this@StreamHost.pin?.let { if (it != actualPin) throw StreamException(StreamFailureReason.CERTIFICATE_MISMATCH) }
        val challenge = bytes(16)
        val response = aes(StreamProtocol.unhex(step(mapOf("clientchallenge" to StreamProtocol.hex(aes(challenge, true)))).value("challengeresponse")), false)
        require(response.size == 48 || response.size == 64)
        val secret = bytes(16)
        val client = certificate(identity.certificatePem.toByteArray())
        val hash = sha(response.copyOfRange(32, 48) + client.signature + secret)
        val hostSecret = StreamProtocol.unhex(step(mapOf("serverchallengeresp" to StreamProtocol.hex(aes(hash, true)))).value("pairingsecret"))
        require(hostSecret.size > 16)
        val signatureValid = Signature.getInstance("SHA256withRSA").run {
            initVerify(server); update(hostSecret, 0, 16); verify(hostSecret.copyOfRange(16, hostSecret.size))
        }
        // Verify both the host signature AND its PIN challenge; signature alone accepts wrong PINs.
        if (!signatureValid || !MessageDigest.isEqual(response.copyOf(32), sha(challenge + server.signature + hostSecret.copyOf(16))))
            throw StreamException(StreamFailureReason.HOST_REJECTED)
        val der = Base64.getMimeDecoder().decode(identity.privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""))
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        val signed = Signature.getInstance("SHA256withRSA").run { initSign(privateKey); update(secret); sign() }
        step(mapOf("clientpairingsecret" to StreamProtocol.hex(secret + signed)))
        step(mapOf("phrase" to "pairchallenge"), true, actualPin)
        acceptPin(actualPin)
        actualPin
    }
}
