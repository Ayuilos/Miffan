package me.ayuilos.miffan.testutils

import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import me.ayuilos.miffan.R

/** Real XML text with a mocked Android boundary, so formatter tests run on the JVM. */
fun workspaceTestResources(locale: String = "values-zh"): Resources {
    val names = R.string::class.java.fields.associate { it.name to it.getInt(null) }
    val values = mutableMapOf<Int, String>()
    for (directory in listOf("values", locale).distinct()) {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$directory/strings.xml"))
        val strings = xml.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val item = strings.item(i)
            val name = item.attributes.getNamedItem("name").nodeValue
            val id = names[name] ?: continue
            values[id] = item.textContent.removeSurrounding("\"")
                .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
    }
    val resources = mockk<Resources>()
    val configuration = mockk<android.content.res.Configuration>()
    val locales = mockk<android.os.LocaleList>()
    every { resources.configuration } returns configuration
    every { configuration.locales } returns locales
    every { locales[0] } returns Locale.forLanguageTag(when (locale) {
        "values" -> "en"
        "values-zh-rTW" -> "zh-TW"
        "values-ko-rKR" -> "ko-KR"
        else -> locale.removePrefix("values-")
    })
    every { resources.getString(any()) } answers { values.getValue(firstArg()) }
    every { resources.getString(any(), *anyVararg()) } answers {
        String.format(Locale.ROOT, values.getValue(firstArg()), *secondArg<Array<Any>>())
    }
    return resources
}
