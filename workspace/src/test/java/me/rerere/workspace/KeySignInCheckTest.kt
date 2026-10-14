package me.rerere.workspace

import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchChangedHostKeyException
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.util.concurrent.CancellationException

class KeySignInCheckTest {
    @Test fun `only remote authentication refusal counts as already gone`() {
        assertTrue(isRemoteKeyAuthenticationRefused(RemoteConnectException("host", JSchException("Auth fail for methods 'publickey'"))))
        assertTrue(isRemoteKeyAuthenticationRefused(JSchException("Auth cancel")))
        assertFalse(isRemoteKeyAuthenticationRefused(ConnectException("Connection refused")))
        assertFalse(isRemoteKeyAuthenticationRefused(JSchException("invalid privatekey")))
        assertFalse(isRemoteKeyAuthenticationRefused(JSchChangedHostKeyException::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance("Auth fail")))
        assertFalse(isRemoteKeyAuthenticationRefused(JSchException("Auth fail", CancellationException())))
    }
}
