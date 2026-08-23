package me.rerere.ai.util

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class FileEncoderMediaTest {
    @Test
    fun `video URL preserves URL and infers webm mime type`() {
        val encoded = UIMessagePart.Video("https://example.com/clip.webm?token=abc")
            .encodeBase64Media()
            .getOrThrow()

        assertEquals("https://example.com/clip.webm?token=abc", encoded.data)
        assertEquals("video/webm", encoded.mimeType)
    }

    @Test
    fun `video data URL can return raw base64 for inline data providers`() {
        val encoded = UIMessagePart.Video("data:video/mov;base64,dGVzdA==")
            .encodeBase64Media(withPrefix = false)
            .getOrThrow()

        assertEquals("dGVzdA==", encoded.data)
        assertEquals("video/mov", encoded.mimeType)
    }
}
