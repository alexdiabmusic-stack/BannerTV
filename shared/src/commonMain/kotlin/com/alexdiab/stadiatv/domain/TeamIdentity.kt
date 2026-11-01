package com.alexdiab.stadiatv.domain

/** Abbreviation compatibility is league-scoped. Native numeric IDs must not be compared to ESPN numbers. */
object TeamIdentity {
    fun abbreviation(leaguePath: String, value: String): String {
        val normalized = value.trim().uppercase()
        return if (leaguePath == "baseball/mlb") when (normalized) {
            "WSH" -> "WSN"; "CHW" -> "CWS"; "ATH", "OAK" -> "ATH"; "KC" -> "KCR"; "SD" -> "SDP"; "SF" -> "SFG"; "TB" -> "TBR"
            else -> normalized
        } else if (leaguePath == "hockey/nhl") when (normalized) {
            "NJ" -> "NJD"; "TB" -> "TBL"; "LA" -> "LAK"; "SJ" -> "SJS"; "MON" -> "MTL"; "WAS" -> "WSH"; "UTAH" -> "UTA"
            else -> normalized
        } else if (leaguePath == "football/nfl") when (normalized) {
            "AZ" -> "ARI"; "JAC" -> "JAX"; "WSH" -> "WAS"; "LA", "STL" -> "LAR"; "OAK" -> "LV"; "SD" -> "LAC"
            else -> normalized
        } else if (leaguePath == "basketball/nba") when (normalized) {
            "GS" -> "GSW"; "NY" -> "NYK"; "SA" -> "SAS"; "NO" -> "NOP"; "WSH" -> "WAS"; "PHO" -> "PHX"; "UTAH" -> "UTA"
            else -> normalized
        } else if (leaguePath == "basketball/wnba") when (normalized) {
            "NY", "NYL" -> "NYL"; "LV", "LVA" -> "LVA"; "CON", "CONN" -> "CON"; "LA", "LAS" -> "LAS"
            else -> normalized
        } else if (leaguePath == "soccer/usa.1") when (normalized) {
            "LAFC", "LAF" -> "LAFC"
            "RBN", "RBNY", "NYRB" -> "RBNY"
            else -> normalized
        } else normalized
    }
    fun sameAbbreviation(leaguePath: String, first: String, second: String): Boolean =
        first.isNotBlank() && second.isNotBlank() && abbreviation(leaguePath, first) == abbreviation(leaguePath, second)
}
