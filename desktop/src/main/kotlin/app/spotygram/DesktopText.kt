package app.spotygram

import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class DesktopText {
    private data class Resources(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)
    private val english by lazy { load("values/strings.xml") }
    private val russian by lazy { load("values-ru/strings.xml") }
    private fun resources(locale: Locale) = if (locale.language == "ru") russian else english
    private fun load(path: String): Resources {
        val document = checkNotNull(javaClass.classLoader.getResourceAsStream(path)).use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val nodes = document.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            if (element.tagName == "string") strings[name] = element.textContent.removeSurrounding("\"")
            else if (element.tagName == "plurals") {
                val items = element.getElementsByTagName("item")
                plurals[name] = (0 until items.length).associate { j ->
                    val item = items.item(j) as Element
                    item.getAttribute("quantity") to item.textContent.removeSurrounding("\"")
                }
            }
        }
        return Resources(strings, plurals)
    }
    fun string(id: Int, locale: Locale, vararg arguments: Any?): String {
        val name = checkNotNull(R.names[id])
        val text = resources(locale).strings[name] ?: checkNotNull(english.strings[name])
        return if (arguments.isEmpty()) text else String.format(locale, text, *arguments)
    }
    fun plural(id: Int, quantity: Int, locale: Locale, vararg arguments: Any?): String {
        val category = if (locale.language == "ru") when {
            quantity % 10 == 1 && quantity % 100 != 11 -> "one"
            quantity % 10 in 2..4 && quantity % 100 !in 12..14 -> "few"
            else -> "many"
        } else if (quantity == 1) "one" else "other"
        val values = checkNotNull(resources(locale).plurals[R.names[id]])
        return String.format(locale, values[category] ?: values.getValue("other"), *arguments)
    }
}
