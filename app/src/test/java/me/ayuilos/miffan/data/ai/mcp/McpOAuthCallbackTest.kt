package me.ayuilos.miffan.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpOAuthCallbackTest {
    @Test
    fun `accept language keeps a valid app locale`() {
        assertEquals("zh-Hans-CN", normalizeOAuthAcceptLanguage(" zh-Hans-CN "))
    }

    @Test
    fun `accept language rejects injected header content`() {
        assertNull(normalizeOAuthAcceptLanguage("zh-CN\r\nX-Test"))
        assertNull(normalizeOAuthAcceptLanguage(""))
    }
}
