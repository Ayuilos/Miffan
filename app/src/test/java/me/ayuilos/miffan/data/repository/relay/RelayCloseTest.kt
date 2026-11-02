package me.ayuilos.miffan.data.repository.relay

import io.mockk.mockk
import java.io.IOException
import me.rerere.workspace.relay.RelayChannel
import me.rerere.workspace.relay.RelayClient
import me.rerere.workspace.relay.RelayReady
import me.rerere.workspace.relay.RelaySessionClosedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayCloseTest {
    private val access = RelayAccess(RelayClient(), RelayDeviceKeyStore(), mockk(relaxed = true))

    @Test fun closeIsRecordedUntilTheSameChannelReopens() {
        access.recordClose("host", RelaySessionClosedException("idle", RelayChannel.MEDIA))
        assertEquals("idle", access.lastClose.value["host"]?.reason)
        access.record("host", RelayReady(sid = "s", ch = "control"))
        assertEquals("idle", access.lastClose.value["host"]?.reason) // another channel
        access.record("host", RelayReady(sid = "s", ch = "media"))
        assertNull(access.lastClose.value["host"])
    }

    @Test fun reasonIsFoundInCausesAndSuppressed() {
        val closed = RelaySessionClosedException("quota_exhausted", RelayChannel.CONTROL)
        val suppressed = IOException("channel is not opened").apply { addSuppressed(closed) }
        assertEquals("quota_exhausted", RuntimeException(suppressed).relaySessionClose()?.reason)
        assertEquals("quota_exhausted", IOException("lost", closed).relaySessionClose()?.reason)
        assertNull(IOException("plain").relaySessionClose())
    }
}
