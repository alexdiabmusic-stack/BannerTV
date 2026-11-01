package com.alexdiab.stadiatv.data.news

import com.alexdiab.stadiatv.util.Dates
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader


/**
 * Streaming RSS 2.0 / Atom parser. Tolerant by design: a feed with a malformed tail still yields the
 * items parsed before the error, the same way the iOS parser treats partial results.
 */
object RssParser {
    private val IMG_SRC = Regex("""<img[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]+>")
    private val WHITESPACE = Regex("\\s+")
    private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|\\d+);")
    private val ENTITIES = listOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
        "&nbsp;" to " ", "&ndash;" to "–", "&mdash;" to "—", "&hellip;" to "…",
        "&rsquo;" to "’", "&lsquo;" to "‘", "&rdquo;" to "”", "&ldquo;" to "“",
    )

    private val HTML_ENTITIES = listOf(
        "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
    )

    private class Builder {
        var title: String? = null
        var link: String? = null
        var guid: String? = null
        var description: String? = null
        var content: String? = null
        var published: String? = null
        var image: String? = null
        var author: String? = null
        val categories = mutableListOf<String>()

        fun build(): RssItem? {
            val headline = title?.takeIf { it.isNotBlank() } ?: return null
            val summary = description ?: content?.take(300).orEmpty()
            return RssItem(
                title = headline,
                link = link,
                guid = guid,
                description = summary,
                publishedMillis = Dates.parseFeedDate(published),
                imageUrl = image,
                author = author,
                categories = categories.distinct(),
            )
        }
    }

    fun parse(xml: String, maxItems: Int = 60): List<RssItem> {
        val items = mutableListOf<RssItem>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            // A BOM or blank lines before the XML declaration make a strict parser reject the whole feed.
            parser.setInput(StringReader(xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')))
            // Feeds often leak HTML entities into plain XML text, which a strict parser rejects.
            runCatching { HTML_ENTITIES.forEach { (name, value) -> parser.defineEntityReplacementText(name, value) } }
            readItems(parser, items, maxItems)
        } catch (_: Exception) {
            // Partial results are still useful.
        }
        return items
    }

    private fun readItems(parser: XmlPullParser, out: MutableList<RssItem>, maxItems: Int) {
        var current: Builder? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.size < maxItems) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    val name = parser.name
                    if (name == "item" || name == "entry") {
                        current = Builder()
                    } else current?.let { readAttributes(parser, name, it) }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    val item = current
                    if (item != null) {
                        if (name == "item" || name == "entry") {
                            item.build()?.let(out::add)
                            current = null
                        } else {
                            readText(item, name, text.toString().trim())
                        }
                    }
                    text.setLength(0)
                }
            }
            event = parser.next()
        }
    }

    private fun readAttributes(parser: XmlPullParser, name: String, item: Builder) {
        fun attribute(key: String) = parser.getAttributeValue(null, key)
        when (name) {
            "enclosure" -> if (attribute("type")?.startsWith("image/") == true) attribute("url")?.let { item.image = item.image ?: it }
            "media:content" -> {
                val type = attribute("type").orEmpty()
                val medium = attribute("medium").orEmpty()
                if (!type.startsWith("video") && medium != "video") attribute("url")?.let { item.image = item.image ?: it }
            }
            "media:thumbnail" -> attribute("url")?.let { item.image = item.image ?: it }
            // Atom: <link href="…"/>, where rel="alternate" (or no rel) is the article itself.
            "link" -> {
                val rel = attribute("rel")
                val href = attribute("href")
                if (href != null && (rel == null || rel == "alternate") && item.link == null) item.link = href
            }
        }
    }

    private fun readText(item: Builder, name: String, raw: String) {
        if (raw.isEmpty()) return
        when (name) {
            "title" -> if (item.title == null) item.title = clean(raw)
            "link" -> if (item.link == null && raw.startsWith("http")) item.link = raw
            "guid", "id" -> item.guid = raw
            "description", "summary" -> {
                if (item.image == null) IMG_SRC.find(raw)?.groupValues?.get(1)?.let { item.image = it }
                if (item.description == null) item.description = clean(raw)
            }
            "content:encoded", "content" -> {
                if (item.image == null) IMG_SRC.find(raw)?.groupValues?.get(1)?.let { item.image = it }
                if (item.content == null) item.content = clean(raw)
            }
            "pubDate", "published", "updated", "dc:date", "date" -> if (item.published == null) item.published = raw
            "author", "dc:creator", "name" -> if (item.author == null) item.author = clean(raw)
            "category", "dc:subject" -> item.categories += raw
        }
    }

    fun clean(html: String): String {
        var result = html
        ENTITIES.forEach { (entity, replacement) -> result = result.replace(entity, replacement) }
        result = result.replace(NUMERIC_ENTITY) { match ->
            val digits = match.groupValues[1]
            val code = if (digits.startsWith("x")) digits.drop(1).toIntOrNull(16) else digits.toIntOrNull()
            code?.let { String(Character.toChars(it)) } ?: match.value
        }
        return result.replace(TAGS, " ").replace(WHITESPACE, " ").trim()
    }
}
