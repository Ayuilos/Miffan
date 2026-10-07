package me.rerere.workspace.screen

import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object RfbAuth {
    private val random = SecureRandom()

    /** Classic VNC auth: DES-encrypt the 16-byte challenge with the bit-reversed password. */
    fun vnc(input: DataInputStream, output: DataOutputStream, password: String) {
        val challenge = ByteArray(16).also(input::readFully)
        val key = ByteArray(8)
        password.toByteArray(StandardCharsets.ISO_8859_1).copyInto(key, endIndex = minOf(8, password.length))
        for (i in key.indices) key[i] = (Integer.reverse(key[i].toInt() and 0xFF) ushr 24).toByte()
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        output.write(cipher.doFinal(challenge))
        output.flush()
    }

    /**
     * Apple Remote Desktop auth (security type 30): anonymous Diffie-Hellman, then the macOS
     * username/password AES-128-ECB encrypted with MD5(shared secret).
     */
    fun ard(input: DataInputStream, output: DataOutputStream, username: String, password: String) {
        val generator = BigInteger.valueOf(input.readUnsignedShort().toLong())
        val keyLength = input.readUnsignedShort()
        val prime = BigInteger(1, ByteArray(keyLength).also(input::readFully))
        val serverPublic = BigInteger(1, ByteArray(keyLength).also(input::readFully))

        val private = BigInteger(keyLength * 8 - 1, random)
        val clientPublic = generator.modPow(private, prime)
        val shared = serverPublic.modPow(private, prime)
        val aesKey = MessageDigest.getInstance("MD5").digest(fixedLength(shared, keyLength))

        val credentials = ByteArray(128).also(random::nextBytes)
        writeCString(credentials, 0, username)
        writeCString(credentials, 64, password)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        output.write(cipher.doFinal(credentials))
        output.write(fixedLength(clientPublic, keyLength))
        output.flush()
        credentials.fill(0)
    }

    private fun writeCString(target: ByteArray, offset: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size < 64) { "Credential is too long" }
        bytes.copyInto(target, offset)
        target[offset + bytes.size] = 0
    }

    private fun fixedLength(value: BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
            else -> ByteArray(length - bytes.size) + bytes
        }
    }
}
