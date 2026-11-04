package me.ayuilos.miffan.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.cuaspace.SpaceInput
import me.rerere.cuaspace.SpaceMediaEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxWindowMediaTest {
    private fun media(stdout: ByteArray, stdin: ByteArrayOutputStream = ByteArrayOutputStream(), onClose: () -> Unit = {}) =
        LinuxWindowMedia(ByteArrayInputStream(stdout), stdin, onClose)

    /** Every event up to and including the stream's end. */
    private fun events(media: LinuxWindowMedia): List<SpaceMediaEvent> = runBlocking {
        withTimeout(5_000) {
            val seen = mutableListOf<SpaceMediaEvent>()
            media.events.takeWhile { seen += it; it !is SpaceMediaEvent.Closed }.toList()
            seen
        }
    }

    @Test fun `headers and frames become events, the first ones included`() {
        val stdout = ByteArrayOutputStream().apply {
            write("opened 800 600 x264\ngeometry 1024 768\nframe 3 1 1 1024 768 5000 4\n".toByteArray())
            write(byteArrayOf(0, 0, 1, 0x65))
            write("suspended locked\nresumed\ninfo {\"located\":true}\nclosed window_closed\n".toByteArray())
        }.toByteArray()
        val media = media(stdout)
        val events = events(media)
        assertEquals(SpaceMediaEvent.Opened("linux", 800, 600), events[0])
        assertEquals(SpaceMediaEvent.Geometry(1024, 768), events[1])
        val frame = events[2] as SpaceMediaEvent.Frame
        assertEquals(listOf(3L, 1L, 1024L, 768L, 5000L), listOf(frame.sequence, frame.codecEpoch, frame.width.toLong(), frame.height.toLong(), frame.timestampUs))
        assertTrue(frame.keyframe)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x65), frame.data)
        assertEquals(SpaceMediaEvent.Suspended("locked"), events[3])
        assertEquals(SpaceMediaEvent.Resumed, events[4])
        assertEquals("info", (events[5] as SpaceMediaEvent.Other).type)
        // The window closed: the code a Space gives for a target that is gone.
        val closed = events[6] as SpaceMediaEvent.Closed
        assertEquals(4410, closed.code)
        assertEquals("window_closed", closed.reason)
        assertEquals(stdout.size.toLong(), media.bytesRead)
    }

    @Test fun `another closing reason has no code`() {
        val closed = events(media("closed capture_failed\n".toByteArray())).single() as SpaceMediaEvent.Closed
        assertNull(closed.code)
        assertEquals("capture_failed", closed.reason)
    }

    @Test fun `the stream ending without a word is an ended close`() {
        val closed = events(media("opened 10 10 x\n".toByteArray())).last() as SpaceMediaEvent.Closed
        assertNull(closed.code)
        assertEquals("ended", closed.reason)
        assertTrue(closed.error is EOFException)
    }

    @Test fun `a frame cut short ends the stream`() {
        val stdout = "frame 1 0 1 10 10 0 100\n".toByteArray() + ByteArray(10)
        val closed = events(media(stdout)).single() as SpaceMediaEvent.Closed
        assertEquals("ended", closed.reason)
    }

    @Test fun `a malformed header ends the stream with its reason`() {
        val bad = events(media("opened wide 10 x\n".toByteArray())).single() as SpaceMediaEvent.Closed
        assertEquals("Unexpected window header", bad.reason)
        val short = events(media("frame 1 0 1\n".toByteArray())).single() as SpaceMediaEvent.Closed
        assertEquals("Unexpected frame header", short.reason)
        val huge = events(media("frame 1 0 1 10 10 0 999999999\n".toByteArray())).single() as SpaceMediaEvent.Closed
        assertEquals("Unexpected frame length", huge.reason)
    }

    @Test fun `input is written in order and nothing after closing`() = runBlocking {
        val stdin = ByteArrayOutputStream()
        var closedChannel = false
        val media = media(ByteArray(0), stdin) { closedChannel = true }
        assertTrue(media.send(listOf(SpaceInput.Key("a", true), SpaceInput.Key("a", false), SpaceInput.Text("hi"))))
        media.closeSession()
        assertTrue(closedChannel)
        assertFalse(media.send(listOf(SpaceInput.Key("b", true))))
        val expected = "key a 1\nkey a 0\ntext aGk=\n"
        withTimeout(5_000) { while (stdin.toString() != expected) kotlinx.coroutines.delay(10) }
        assertEquals(expected, stdin.toString())
    }

    @Test fun `queued moves keep only the latest`() {
        val queue = LinuxInputQueue()
        queue.add("move 0.1 0.1\n")
        queue.add("move 0.2 0.2\n")
        queue.add("down left 0.2 0.2\n")
        queue.add("move 0.3 0.3\n")
        queue.add("move 0.4 0.4\n")
        assertEquals(listOf("move 0.2 0.2\n", "down left 0.2 0.2\n", "move 0.4 0.4\n"), queue.take())
    }

    @Test fun `stale pointer input is dropped but releases, keys and text are not`() {
        var now = 0L
        val queue = LinuxInputQueue(maxAgeNanos = 1_000, clock = { now })
        queue.add("down left 0.5 0.5\n")
        queue.add("scroll 0.5 0.5 0.0 100.0\n")
        queue.add("up left 0.5 0.5\n")
        queue.add("key enter 1\n")
        queue.add("key enter 0\n")
        queue.add("text aGk=\n")
        queue.add("kf\n")
        now = 1_500
        queue.add("move 0.6 0.6\n")
        assertEquals(listOf("up left 0.5 0.5\n", "key enter 1\n", "key enter 0\n", "text aGk=\n", "kf\n", "move 0.6 0.6\n"), queue.take())
    }

    @Test fun `a full queue drops its oldest pointer input first`() {
        val queue = LinuxInputQueue(capacity = 3)
        queue.add("key a 1\n")
        queue.add("down left 0.1 0.1\n")
        queue.add("key a 0\n")
        queue.add("up left 0.1 0.1\n")
        assertEquals(listOf("key a 1\n", "key a 0\n", "up left 0.1 0.1\n"), queue.take())
    }

    @Test fun `a stopped queue hands over what waits, then ends`() {
        val queue = LinuxInputQueue()
        queue.add("key a 1\n")
        queue.stop()
        queue.add("key b 1\n")
        assertEquals(listOf("key a 1\n"), queue.take())
        assertNull(queue.take())
    }

    @Test fun `picture replies read pictures and errors, and refuse the rest`() {
        val reply = ByteArrayOutputStream().apply {
            write("jpeg 3\n".toByteArray()); write(byteArrayOf(1, 2, 3))
            write("error no_picture\n".toByteArray())
        }.toByteArray()
        val replies = LinuxHostWindows.Replies(ByteArrayInputStream(reply))
        assertArrayEquals(byteArrayOf(1, 2, 3), replies.picture())
        assertNull(replies.picture())
        assertThrows(EOFException::class.java) { replies.picture() }
        assertThrows(IllegalStateException::class.java) {
            LinuxHostWindows.Replies(ByteArrayInputStream("jpeg 999999999\n".toByteArray())).picture()
        }
        assertThrows(IllegalStateException::class.java) {
            LinuxHostWindows.Replies(ByteArrayInputStream("jpeg 0\n".toByteArray())).picture()
        }
        assertThrows(IllegalStateException::class.java) {
            LinuxHostWindows.Replies(ByteArrayInputStream("png 3\n".toByteArray())).picture()
        }
        // Cut off in the middle of the picture.
        assertThrows(EOFException::class.java) {
            LinuxHostWindows.Replies(ByteArrayInputStream("jpeg 10\n".toByteArray() + ByteArray(4))).picture()
        }
    }
}
