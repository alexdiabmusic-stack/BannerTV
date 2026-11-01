package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*

internal fun phoneSoccerAthlete(player: SportPlayer, nativeEpl: Boolean): Athlete? {
    if (player.id.startsWith("laliga:") && com.alexdiab.stadiatv.sports.NativeLaLigaMapping.rawId(player.id, true) != null && player.name.isNotBlank())
        return Athlete(player.id, player.name, player.number, player.position)
    if (player.id.startsWith("mls:") && com.alexdiab.stadiatv.sports.NativeMlsMapping.rawId(player.id, "OBJ") != null && player.name.isNotBlank())
        return Athlete(player.id, player.name, player.number, player.position)
    if (!player.id.matches(Regex("[0-9]{1,20}")) || player.name.isBlank()) return null
    return Athlete(if (nativeEpl) "epl:${player.id}" else player.id, player.name, player.number, player.position)
}

internal fun phoneRosterEmptyMessage(snapshot: PhoneSnapshot<List<RosterGroup>>): String? =
    if (!snapshot.loading && !snapshot.failed && snapshot.value?.all { it.athletes.isEmpty() } == true) "The provider returned no players for this roster." else null

internal fun phonePlayerSource(league: League, person: Athlete): String? {
    if (':' !in person.id) return "ESPN"
    val prefix = when (league.path) { "basketball/nba" -> "nba"; "basketball/wnba" -> "wnba"; "hockey/nhl" -> "nhl"; "baseball/mlb" -> "mlb"; "football/nfl" -> "nfl"; "soccer/eng.1" -> "epl"; "soccer/usa.1" -> "mls"; "soccer/esp.1" -> "laliga"; else -> return null }
    return prefix.takeIf { person.id.startsWith("$it:") }?.uppercase(java.util.Locale.US)
}

fun phoneSeasonLabel(raw: String): String {
    if (raw.matches(Regex("[0-9]{8}"))) {
        val a = raw.take(4).toInt(); val b = raw.takeLast(4).toInt()
        if (a in 1900..2200 && b == a + 1) return "$a–${raw.takeLast(2)}"
    }
    return raw
}

/** NFL's native roster overview contains biography, not a fabricated season performance feed. */
fun phonePlayerStatsTitle(label: String?): String = when (label) {
    "Player", "Player information" -> "Player information"
    null -> "Statistics"
    else -> "${phoneSeasonLabel(label)} statistics"
}

fun phonePlayerFacts(person: Athlete, overview: AthleteOverview?): Map<String, String> = linkedMapOf<String, String>().apply {
    person.height?.let { put("Height", it) }; person.weight?.let { put("Weight", it) }
    person.age?.let { put("Age", it.toString()) }; person.experienceYears?.let { put("Experience", if (it == 0) "Rookie" else "$it yr") }
    person.college?.let { put("College", it) }; person.birthPlace?.let { put("Birthplace", it) }
    overview?.bio?.forEach { (key, value) -> if (key != "Season" && value.isNotBlank()) put(key, value) }
}

/** Enriched headers may add a headshot/bio but never change the selected provider/player identity. */
fun phoneProfileAthlete(selected: Athlete, loaded: Athlete?): Athlete {
    if (loaded == null || loaded.id != selected.id) return selected
    return loaded.copy(name = loaded.name.takeUnless { it.isBlank() || it == "Unknown player" } ?: selected.name,
        headshotUrl = loaded.headshotUrl ?: selected.headshotUrl, jersey = loaded.jersey.ifBlank { selected.jersey },
        position = loaded.position.ifBlank { selected.position }, positionName = loaded.positionName ?: selected.positionName,
        height = loaded.height ?: selected.height, weight = loaded.weight ?: selected.weight, age = loaded.age ?: selected.age,
        college = loaded.college ?: selected.college, experienceYears = loaded.experienceYears ?: selected.experienceYears,
        birthPlace = loaded.birthPlace ?: selected.birthPlace, injured = loaded.injured || selected.injured)
}
