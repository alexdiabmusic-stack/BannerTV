package com.alexdiab.stadiatv.foundation

import kotlin.math.floor
import kotlin.math.pow

/** Only OS calendar/URL details cross this boundary. Provider rules stay in commonMain. */
expect object SportsTime {
    fun compact(at: Long): String
    fun addDays(at: Long, days: Int): Long
    fun startOfDay(at: Long): Long
    fun kickoff(at: Long): String
    fun parseIso(raw: String?): Long?
    fun format(pattern: String, at: Long, zone: String? = null): String
    fun addLocalDays(at: Long, days: Int, zone: String): Long
    fun parseLocal(raw: String, pattern: String, zone: String): Long?
}
internal expect fun basketballAsset(prefix: String, code: String): String
internal expect val sportsIO: kotlinx.coroutines.CoroutineDispatcher
internal expect val sportsDisk: okio.FileSystem
internal expect fun decomposeText(value: String): String
internal expect fun decomposeCompatibilityText(value: String): String
internal fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

internal fun plainNumber(raw: String): String {
    val pieces = raw.lowercase().removePrefix("-").split('e')
    val exp = pieces.getOrNull(1)?.toIntOrNull() ?: 0
    if (exp !in -400..400) return raw
    val mantissa = pieces.first()
    val digits = mantissa.replace(".", "")
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return raw
    val point = mantissa.substringBefore('.').length + exp
    val adjusted = when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits.take(point) + "." + digits.drop(point)
    }
    val whole = adjusted.substringBefore('.').trimStart('0').ifEmpty { "0" }
    val fraction = adjusted.substringAfter('.', "").trimEnd('0')
    val result = whole + if (fraction.isEmpty()) "" else "." + fraction
    return if (raw.startsWith('-') && result != "0") "-" + result else result
}

expect fun safeWebURL(raw: String, httpsOnly: Boolean = true): Boolean
expect open class SportsIOException : Exception {
    constructor(message: String)
    constructor(message: String, cause: Throwable?)
}
internal expect class SportsLock() {
    fun <T> locked(block: () -> T): T
}

internal fun decimal(number: Double, places: Int): String {
    require(places in 1..6 && number.isFinite())
    val scale = 10.0.pow(places).toLong()
    val scaled = floor(kotlin.math.abs(number) * scale + 0.5).toLong()
    return (if (number < 0) "-" else "") + (scaled / scale) + "." + (scaled % scale).toString().padStart(places, '0')
}

/** RFC 3986 path/query component; never reinterpret '%' or '/' sent by an add-on. */
fun encodeComponent(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    for (b in value.encodeToByteArray()) {
        val n = b.toInt() and 255
        val c = n.toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~") append(c)
        else { append('%'); append(hex[n shr 4]); append(hex[n and 15]) }
    }
}

internal fun requestURL(base: String, query: Map<String, String>): String =
    if (query.isEmpty()) base else base + "?" + query.entries.sortedBy { it.key }
        .joinToString("&") { encodeComponent(it.key) + "=" + encodeComponent(it.value) }

internal class SportsMemo<K, V>(initial: Map<K, V> = emptyMap()) {
    private val lock = SportsLock()
    private val data = initial.toMutableMap()
    fun <T> transaction(action: () -> T): T = lock.locked(action)
    fun containsKey(key: K) = lock.locked { key in data }
    fun removeWhere(check: (V) -> Boolean) = lock.locked { data.entries.removeAll { check(it.value) } }
    fun putIfAbsent(key: K, value: V): V? = lock.locked { val old = data[key]; if (old == null) data[key] = value; old }
    val size get() = lock.locked { data.size }
    val keys get() = lock.locked { data.keys.toSet() }
    fun snapshot(): Map<K,V> = lock.locked { data.toMap() }
    fun remove(key: K) = lock.locked { data.remove(key) }
    fun remove(key: K, value: V) = lock.locked { if (data[key] == value) data.remove(key) else null }
    operator fun get(key: K): V? = lock.locked { data[key] }
    operator fun set(key: K, value: V) = lock.locked { data[key] = value }
}
