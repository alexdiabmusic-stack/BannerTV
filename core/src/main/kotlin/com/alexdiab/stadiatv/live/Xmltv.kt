package com.alexdiab.stadiatv.live

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Streaming XMLTV reader. It never builds a DOM and only materialises programmes the caller accepts
 * inside the time window, which is what keeps a 100 MB provider guide inside a Fire TV's heap.
 */
object XmltvParser {
    class ChannelInfo(val id: String, val names: List<String>, val iconUrl: String?)

    interface Sink {
        /**
         * Called for every `<channel>`; return true to keep programmes for this id. Returning false leaves the decision
         * to [acceptsUnannounced], asked once the first programme of that channel turns up, by which time [channelsDone]
         * has told the sink the whole channel list is known.
         */
        fun channel(info: ChannelInfo): Boolean
        /** Called when the channel list is complete: before the first programme, or at the end of a guide with none. */
        fun channelsDone() {}
        /** Called for a programme whose channel was not accepted above; return true to keep it. */
        fun acceptsUnannounced(channelId: String): Boolean
        fun programme(programme: Programme)
    }

    fun parse(raw: InputStream, windowStart: Long, windowEnd: Long, sink: Sink, isActive: () -> Boolean = { true }) {
        val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, 64 * 1024)
        buffered.mark(4)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val source: InputStream = if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(source, null)

        val accepted = HashSet<String>()
        val rejected = HashSet<String>()

        var channelId: String? = null
        var channelNames = ArrayList<String>(2)
        var channelIcon: String? = null

        var keep = false
        var guideId = ""
        var start = 0L
        var stop = 0L
        var title = ""
        var subtitle: String? = null
        var description: String? = null
        var category: String? = null
        var icon: String? = null
        var text: String? = null
        var inProgramme = false

        var listDone = false
        var event = parser.eventType
        var steps = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (++steps and 0x3FFF == 0 && !isActive()) return
            when (event) {
                XmlPullParser.START_TAG -> when (val name = parser.name) {
                    "channel" -> {
                        channelId = parser.getAttributeValue(null, "id")
                        channelNames = ArrayList(2)
                        channelIcon = null
                    }
                    "programme" -> {
                        if (!listDone) { listDone = true; sink.channelsDone() }
                        guideId = parser.getAttributeValue(null, "channel").orEmpty()
                        inProgramme = true
                        keep = false
                        if (guideId.isNotEmpty() && guideId !in rejected) {
                            val known = guideId in accepted || (guideId !in rejected && sink.acceptsUnannounced(guideId).also { if (it) accepted += guideId else rejected += guideId })
                            if (known) {
                                start = parseTime(parser.getAttributeValue(null, "start")) ?: 0L
                                stop = parseTime(parser.getAttributeValue(null, "stop")) ?: 0L
                                keep = start > 0 && stop > start && stop >= windowStart && start <= windowEnd
                            }
                        }
                        title = ""; subtitle = null; description = null; category = null; icon = null
                    }
                    "icon" -> {
                        val src = parser.getAttributeValue(null, "src")
                        if (inProgramme) icon = src else if (channelId != null) channelIcon = src
                    }
                    "display-name", "title", "sub-title", "desc", "category" -> text = name
                }
                XmlPullParser.TEXT -> if (text != null) {
                    val value = parser.text?.trim().orEmpty()
                    if (value.isNotEmpty()) when {
                        text == "display-name" -> if (channelNames.size < 4) channelNames += value
                        inProgramme && keep -> when (text) {
                            "title" -> if (title.isEmpty()) title = value.take(120)
                            "sub-title" -> if (subtitle == null) subtitle = value.take(120)
                            "desc" -> if (description == null) description = value.take(DESCRIPTION_LIMIT)
                            "category" -> if (category == null) category = value.take(40)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            val id = channelId
                            if (id != null) {
                                if (sink.channel(ChannelInfo(id, channelNames, channelIcon))) accepted += id
                            }
                            channelId = null
                        }
                        "programme" -> {
                            if (keep) sink.programme(Programme(guideId, start, stop, title.ifEmpty { "Untitled" }, subtitle, description, category, icon))
                            inProgramme = false
                            keep = false
                        }
                    }
                    if (parser.name == text) text = null
                }
            }
            event = parser.next()
        }
        if (!listDone) sink.channelsDone()
    }

    /** `20261014183000 +0000`, `202610141830 -0400`, or without an offset (treated as UTC). */
    fun parseTime(value: String?): Long? {
        val text = value?.trim() ?: return null
        if (text.length < 12) return null
        return runCatching {
            val year = text.substring(0, 4).toInt()
            val month = text.substring(4, 6).toInt()
            val day = text.substring(6, 8).toInt()
            val hour = text.substring(8, 10).toInt()
            val minute = text.substring(10, 12).toInt()
            val second = if (text.length >= 14 && text[12].isDigit()) text.substring(12, 14).toInt() else 0
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            calendar.clear()
            calendar.set(year, month - 1, day, hour, minute, second)
            var millis = calendar.timeInMillis
            val zoneStart = text.indexOfFirst { it == '+' || it == '-' }.takeIf { it >= 12 }
            if (zoneStart != null) {
                val sign = if (text[zoneStart] == '-') -1 else 1
                val digits = text.substring(zoneStart + 1).trim().replace(":", "")
                if (digits.length >= 4) millis -= sign * (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * 60_000L
            }
            millis
        }.getOrNull()
    }

    /** Programme descriptions are only ever read for fixture matching, so they are kept short. */
    const val DESCRIPTION_LIMIT = 300
}
