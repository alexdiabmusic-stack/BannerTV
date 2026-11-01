package com.alexdiab.stadiatv.live

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.io.DataOutputStream

/**
 * Every programme of one guide channel as parallel arrays, sorted by start. A week of listings for a couple of
 * thousand channels is a few hundred thousand programmes; as objects with their own strings that is well over a Fire
 * TV's heap, as arrays over a shared string table it is a few megabytes.
 */
class Schedule(
    /** Start of each programme in minutes since the epoch (UTC). */
    val starts: IntArray,
    /** Length of each programme in minutes. */
    val lengths: CharArray,
    val titles: IntArray,
    /** -1 where the guide gave none. */
    val subtitles: IntArray,
    val categories: IntArray,
    val descriptions: IntArray,
) {
    val size: Int get() = starts.size

    /** Index of the programme on air at [minute] (shifted by [offset]), or -1. */
    fun indexAt(minute: Long, offset: Int = 0): Int {
        val target = minute - offset
        val next = firstStartingAfter(target)
        val candidate = next - 1
        return if (candidate >= 0 && starts[candidate] + lengths[candidate].code > target) candidate else -1
    }

    /** Index of the first programme that starts after [minute] (after shifting), or [size]. */
    fun firstStartingAfter(minute: Long): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= minute) low = mid + 1 else high = mid
        }
        return low
    }

    /** Written column by column in one block, so loading a week of programmes is a handful of bulk reads, not 700,000 small ones. */
    fun write(out: DataOutputStream) {
        out.writeInt(size)
        val buffer = ByteBuffer.allocate(size * BYTES_PER_PROGRAMME)
        val ints = buffer.asIntBuffer()
        ints.put(starts); ints.put(titles); ints.put(subtitles); ints.put(categories); ints.put(descriptions)
        buffer.position(size * 4 * 5)
        buffer.asCharBuffer().put(lengths)
        out.write(buffer.array())
    }

    companion object {
        private const val BYTES_PER_PROGRAMME = 5 * 4 + 2

        fun read(input: DataInputStream): Schedule {
            val size = input.readInt()
            val bytes = ByteArray(size * BYTES_PER_PROGRAMME)
            input.readFully(bytes)
            val buffer = ByteBuffer.wrap(bytes)
            val ints = buffer.asIntBuffer()
            val starts = IntArray(size).also(ints::get)
            val titles = IntArray(size).also(ints::get)
            val subtitles = IntArray(size).also(ints::get)
            val categories = IntArray(size).also(ints::get)
            val descriptions = IntArray(size).also(ints::get)
            buffer.position(size * 4 * 5)
            val lengths = CharArray(size).also(buffer.asCharBuffer()::get)
            return Schedule(starts, lengths, titles, subtitles, categories, descriptions)
        }
    }
}

/** Strings shared by every schedule: a title like "NHL Hockey" is stored once however many times it airs. */
class StringTable {
    private val index = HashMap<String, Int>()
    private val list = ArrayList<String>()

    fun intern(text: String?): Int {
        if (text.isNullOrEmpty()) return -1
        return index.getOrPut(text) { list.add(text); list.size - 1 }
    }

    fun toArray(): Array<String> = list.toTypedArray()

    companion object {
        fun write(out: DataOutputStream, strings: Array<String>) {
            out.writeInt(strings.size)
            strings.forEach { out.writeUTF(it) }
        }

        fun read(input: DataInputStream): Array<String> = Array(input.readInt()) { input.readUTF() }
    }
}

/** Collects one channel's programmes while a guide source is parsed. */
class ScheduleBuilder {
    private var starts = IntArray(32)
    private var lengths = CharArray(32)
    private var titles = IntArray(32)
    private var subtitles = IntArray(32)
    private var categories = IntArray(32)
    private var descriptions = IntArray(32)
    var size = 0
        private set

    fun add(start: Int, length: Int, title: Int, subtitle: Int, category: Int, description: Int) {
        if (size == starts.size) grow()
        starts[size] = start
        lengths[size] = length.coerceIn(1, Char.MAX_VALUE.code).toChar()
        titles[size] = title
        subtitles[size] = subtitle
        categories[size] = category
        descriptions[size] = description
        size++
    }

    private fun grow() {
        val capacity = size * 2
        starts = starts.copyOf(capacity); lengths = lengths.copyOf(capacity); titles = titles.copyOf(capacity)
        subtitles = subtitles.copyOf(capacity); categories = categories.copyOf(capacity); descriptions = descriptions.copyOf(capacity)
    }

    /** Sorted by start, with a programme that repeats an earlier start (the same listing from two sources) dropped. */
    fun build(): Schedule {
        val order = (0 until size).sortedWith(compareBy({ starts[it] }, { it }))
        val keep = ArrayList<Int>(size)
        var previous = Int.MIN_VALUE
        for (i in order) {
            if (starts[i] == previous) continue
            previous = starts[i]
            keep += i
        }
        return Schedule(
            IntArray(keep.size) { starts[keep[it]] }, CharArray(keep.size) { lengths[keep[it]] }, IntArray(keep.size) { titles[keep[it]] },
            IntArray(keep.size) { subtitles[keep[it]] }, IntArray(keep.size) { categories[keep[it]] }, IntArray(keep.size) { descriptions[keep[it]] },
        )
    }
}

/**
 * The programmes the guide holds, by guide channel id (lower case). Immutable once built; replaced wholesale when a
 * refresh finishes, so readers never see a half-loaded guide.
 */
class GuideSnapshot(val schedules: Map<String, Schedule>, val strings: Array<String>) {
    val programmeCount: Int = schedules.values.sumOf { it.size }

    private fun text(index: Int): String? = if (index < 0 || index >= strings.size) null else strings[index]

    fun programme(id: String, schedule: Schedule, i: Int, offsetMinutes: Int = 0): Programme {
        val start = (schedule.starts[i] + offsetMinutes) * 60_000L
        return Programme(
            guideId = id,
            startMillis = start,
            endMillis = start + schedule.lengths[i].code * 60_000L,
            title = text(schedule.titles[i]) ?: "Untitled",
            subtitle = text(schedule.subtitles[i]),
            description = text(schedule.descriptions[i]),
            category = text(schedule.categories[i]),
        )
    }

    /** A guide channel's programmes in order, built one at a time as a screen scrolls to them. */
    fun programmes(id: String, offsetMinutes: Int = 0): List<Programme> {
        val key = id.lowercase()
        val schedule = schedules[key] ?: return emptyList()
        return object : AbstractList<Programme>() {
            override val size: Int get() = schedule.size
            override fun get(index: Int): Programme = programme(key, schedule, index, offsetMinutes)
        }
    }

    fun nowAndNext(id: String, now: Long, offsetMinutes: Int = 0): Pair<Programme?, Programme?> {
        val key = id.lowercase()
        val schedule = schedules[key] ?: return null to null
        val minute = now / 60_000L
        val current = schedule.indexAt(minute, offsetMinutes)
        val next = schedule.firstStartingAfter(minute - offsetMinutes)
        return (if (current >= 0) programme(key, schedule, current, offsetMinutes) else null) to
            (if (next < schedule.size) programme(key, schedule, next, offsetMinutes) else null)
    }

    fun has(id: String): Boolean = schedules[id.lowercase()]?.size?.let { it > 0 } == true

    /** Only the channels in [ids], with a string table that holds just the text they use. */
    fun compact(ids: Set<String>): GuideSnapshot {
        val table = StringTable()
        fun remap(index: Int) = if (index < 0 || index >= strings.size) -1 else table.intern(strings[index])
        val kept = HashMap<String, Schedule>()
        for ((id, schedule) in schedules) {
            if (id !in ids) continue
            kept[id] = Schedule(
                schedule.starts, schedule.lengths,
                IntArray(schedule.size) { remap(schedule.titles[it]) }, IntArray(schedule.size) { remap(schedule.subtitles[it]) },
                IntArray(schedule.size) { remap(schedule.categories[it]) }, IntArray(schedule.size) { remap(schedule.descriptions[it]) },
            )
        }
        return GuideSnapshot(kept, table.toArray())
    }

    companion object {
        val EMPTY = GuideSnapshot(emptyMap(), emptyArray())
    }
}
