package me.rerere.stream

import java.io.ByteArrayInputStream
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal object StreamProtocol {
    fun path(route: String, values: Map<String, String>): String {
        require(route in setOf("serverinfo", "pair", "applist", "launch", "resume"))
        return "/$route?" + values.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
    }
    fun xml(bytes: ByteArray): Element {
        require(bytes.size <= 4 * 1024 * 1024)
        require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true))
        val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw java.io.IOException("External XML entity refused") }
        val root = builder.parse(ByteArrayInputStream(bytes)).documentElement
        val status = root.getAttribute("status_code").toIntOrNull() ?: error("Missing XML status")
        if (status != 200) throw StreamException(StreamFailureReason.HOST_REJECTED, code = status)
        return root
    }
    fun Element.value(key: String): String = getElementsByTagName(key).item(0)?.textContent ?: ""
    fun server(root: Element): StreamServerInfo = with(root) {
        StreamServerInfo(value("appversion").also { require(it.isNotEmpty()) }, value("PairStatus") == "1",
            value("currentgame").toIntOrNull() ?: 0, value("ServerCodecModeSupport").toIntOrNull() ?: 0,
            value("GfeVersion").ifEmpty { "3.23.0.74" })
    }
    fun apps(root: Element): List<StreamApp> {
        val nodes = root.getElementsByTagName("App")
        return (0 until nodes.length).map { val e = nodes.item(it) as Element; StreamApp(e.value("ID").toInt(), e.value("AppTitle")) }
    }
    fun httpBody(raw: ByteArray): ByteArray {
        val text = raw.toString(Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n"); require(split >= 0)
        val lines = text.substring(0, split).split("\r\n")
        val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull()
        if (status != 200) throw StreamException(StreamFailureReason.HOST_REJECTED, code = status ?: -1)
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val body = raw.copyOfRange(split + 4, raw.size)
        if (headers["transfer-encoding"]?.lowercase() == "chunked") {
            val out = java.io.ByteArrayOutputStream(); var offset = 0
            while (true) {
                val end = text.indexOf("\r\n", split + 4 + offset); require(end >= 0)
                val n = text.substring(split + 4 + offset, end).substringBefore(';').toInt(16)
                require(n >= 0); offset = end - split - 2
                if (n == 0) return out.toByteArray()
                require(n <= body.size - offset - 2)
                require(body[offset + n] == 13.toByte() && body[offset + n + 1] == 10.toByte())
                out.write(body, offset, n); offset += n + 2
            }
        }
        headers["content-length"]?.let { require(it.toInt() == body.size) }
        return body
    }
    fun numericAddress(address: String): Boolean {
        if (address.contains(':')) {
            if (!address.all { it in "0123456789abcdefABCDEF:." }) return false
            return runCatching { java.net.InetAddress.getByName(address) is java.net.Inet6Address }.getOrDefault(false)
        }
        val parts = address.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } &&
            (it.length == 1 || it[0] != '0') && it.toInt() in 0..255 }
    }
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun unhex(text: String): ByteArray {
        require(text.length % 2 == 0 && text.all { it in "0123456789abcdefABCDEF" })
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
