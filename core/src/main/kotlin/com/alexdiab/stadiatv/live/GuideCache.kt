package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.lineup.GuideListing
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** What a refresh learned, saved so the next launch has a guide before the first byte is downloaded. */
class GuideCacheContent(
    val createdAt: Long,
    /** What the cached guide was built for: the sources, countries and streams. A different one means rebuild. */
    val signature: String,
    val listings: List<GuideListing>,
    val sporty: Set<String>,
    val snapshot: GuideSnapshot,
)

object GuideCache {
    private const val MAGIC = 0x47_55_49_44 // "GUID"
    // Version 4 invalidates caches that may contain programmes merged from third-party guide sources.
    private const val VERSION = 4

    fun write(file: File, content: GuideCacheContent) {
        val partial = File(file.parentFile, file.name + ".part")
        DataOutputStream(BufferedOutputStream(partial.outputStream(), 128 * 1024)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(content.createdAt)
            out.writeUTF(content.signature)
            out.writeInt(content.listings.size)
            for (listing in content.listings) {
                out.writeUTF(listing.id)
                out.writeInt(listing.programmeCount)
                out.writeByte(listing.names.size)
                listing.names.forEach { out.writeUTF(it) }
            }
            out.writeInt(content.sporty.size)
            content.sporty.forEach { out.writeUTF(it) }
            StringTable.write(out, content.snapshot.strings)
            out.writeInt(content.snapshot.schedules.size)
            for ((id, schedule) in content.snapshot.schedules) {
                out.writeUTF(id)
                schedule.write(out)
            }
        }
        if (file.exists()) file.delete()
        if (!partial.renameTo(file)) throw IOException("Couldn't save the TV guide.")
    }

    /** Null when there is no cache, or it was written by another version or is damaged. */
    fun read(file: File): GuideCacheContent? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream(), 128 * 1024)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("not a guide cache")
                val createdAt = input.readLong()
                val signature = input.readUTF()
                val listings = List(input.readInt()) {
                    val id = input.readUTF()
                    val count = input.readInt()
                    GuideListing(id, List(input.readUnsignedByte()) { input.readUTF() }, count)
                }
                val sporty = HashSet<String>().apply { repeat(input.readInt()) { add(input.readUTF()) } }
                val strings = StringTable.read(input)
                val schedules = HashMap<String, Schedule>()
                repeat(input.readInt()) { schedules[input.readUTF()] = Schedule.read(input) }
                GuideCacheContent(createdAt, signature, listings, sporty, GuideSnapshot(schedules, strings))
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }
}
