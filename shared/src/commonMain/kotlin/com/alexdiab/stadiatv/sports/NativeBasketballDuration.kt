package com.alexdiab.stadiatv.sports


/** NBADuration, shared by NBA/WNBA: hours/minutes-only durations and sub-minute tenths are not lost. */
object NativeBasketballDuration {
    private val duration = Regex("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?")
    fun seconds(raw: String?): Double? {
        val value = raw?.let(duration::matchEntire) ?: return null
        if (value.groupValues.drop(1).all(String::isBlank)) return null
        return value.groupValues.drop(1).zip(listOf(3600.0, 60.0, 1.0)).sumOf { (number, multiplier) -> (number.toDoubleOrNull() ?: 0.0) * multiplier }
            .takeIf { it.isFinite() && it in 0.0..86_400.0 }
    }
    fun clockSeconds(raw: String?): Double? {
        val parts = raw?.split(':')?.takeIf { it.size == 2 } ?: return null
        val minutes = parts[0].toIntOrNull()?.takeIf { it in 0..1440 } ?: return null
        val seconds = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it < 60 } ?: return null
        return minutes * 60.0 + seconds
    }
    fun clock(raw: String?): String? {
        val value = seconds(raw) ?: clockSeconds(raw) ?: return null
        // Avoid the rounding artifact 0:60.0 at the minute boundary.
        return if (value < 59.95) "0:" + com.alexdiab.stadiatv.foundation.decimal(value, 1).padStart(4, '0')
            else if (value < 60) "1:00"
            else "${value.toInt() / 60}:${(value.toInt() % 60).toString().padStart(2, '0')}"
    }
    fun period(number: Int, regulation: Int = 4): String = when {
        number <= 0 -> "–"
        number <= regulation -> "Q$number"
        number == regulation + 1 -> "OT"
        else -> "${number - regulation}OT"
    }
}
