package me.rerere.workspace

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** PowerShell 5.1 can serialize its error stream when invoked with EncodedCommand. */
internal object PowerShellOutput {
    fun readable(value: String): String {
        val marker = value.indexOf("#< CLIXML")
        if (marker < 0) return value
        val payload = value.substring(marker + "#< CLIXML".length).trimStart('\r', '\n')
        val start = payload.indexOf("<Objs")
        if (start < 0) return value.substring(0, marker) + decode(payload)
        // Console.Error can write plain text after the serialization header, before <Objs>.
        val prefix = value.substring(0, marker) + payload.substring(0, start)
        val xml = payload.substring(start)
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                    override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
                })
            }
            val doc = builder.parse(InputSource(StringReader(xml)))
            val nodes = doc.getElementsByTagName("S")
            prefix + (0 until nodes.length).mapNotNull { index ->
                val node = nodes.item(index) as org.w3c.dom.Element
                // Ignore progress metadata and nested object properties.
                if (node.parentNode == doc.documentElement) decode(node.textContent) else null
            }.joinToString("")
        } catch (_: Exception) {
            // Truncation may cut XML mid-element. Still return readable completed strings.
            prefix + Regex("<S\\s+S=\"(?:Error|Warning|Verbose|Debug|Information)\"[^>]*>(.*?)</S>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(xml).joinToString("") { decode(it.groupValues[1]) }
        }
    }

    private fun decode(text: String): String = Regex("_x([0-9A-Fa-f]{4})_")
        .replace(text) { it.groupValues[1].toInt(16).toChar().toString() }
}
