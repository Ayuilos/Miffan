package me.ayuilos.miffan.ui.im.welcome

import org.junit.Assert.assertEquals
import org.junit.Test

class WelcomeTextTest {
    @Test
    fun keysAreEchoedOnlyByTheirEnds() {
        assertEquals("sk-7f3••••••0c21", maskKey("sk-7f3a9c0e1b2d4f6a8e5b0c21"))
        assertEquals("••••••", maskKey("short-key"))
    }

    @Test
    fun aServiceTypedInByHandIsNamedAfterItsHost() {
        assertEquals("example.com", serviceNameOf("https://api.example.com/v1"))
        assertEquals("127.0.0.1", serviceNameOf("http://127.0.0.1:8080/v1"))
    }
}
