package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.Match

/** Presentation only: resource ownership, timestamps and retry policy still belong to the stores. */
internal data class PhoneMatchDataNotice(
    val resource: String,
    val message: String,
    val urgent: Boolean = false,
    val retry: String? = "game",
)

internal data class PhoneMatchDataStamp(val label: String, val time: Long? = null)

internal data class PhoneMatchDataStatus(
    val notices: List<PhoneMatchDataNotice>,
    val provenance: List<PhoneMatchDataStamp>,
    val freshness: PhoneMatchDataStamp? = null,
) {
    // A score/identity failure cannot disappear just because another tab is selected.
    val urgentNotice: PhoneMatchDataNotice? get() = notices.firstOrNull { it.urgent }
}

internal fun phoneMatchDataStatus(
    match: Match,
    snapshot: PhoneGameSnapshot,
    selection: String,
    offline: Boolean,
    nflDelayed: Boolean,
): PhoneMatchDataStatus {
    val data = snapshot.data
    val notices = buildList {
        snapshot.error?.takeIf(String::isNotBlank)?.let {
            add(PhoneMatchDataNotice("Match update", it, urgent = true))
        }
        data.baseball?.errors.orEmpty().forEach { (kind, message) ->
            val important = kind in setOf("overview", "linescore", "feed")
            if (important || selection == "At bats" && kind == "plays" || selection == "Box score" && kind == "boxscore")
                add(PhoneMatchDataNotice("Baseball · $kind", message, important))
        }
        data.football?.errors.orEmpty().forEach { (kind, message) ->
            if (kind in setOf("overview", "plays")) add(PhoneMatchDataNotice("Football · $kind", message, kind == "overview"))
        }
        val basketballErrors = data.basketball?.errors.orEmpty()
        val basketballRetry = "game".takeUnless {
            basketballErrors.isNotEmpty() && basketballErrors.values.all { "isn't available on this network" in it }
        }
        basketballErrors.forEach { (kind, message) ->
            if (kind in setOf("score", "plays") || kind == "box" && selection != "Plays")
                add(PhoneMatchDataNotice("Basketball · $kind", message, kind == "score", basketballRetry))
        }
        data.soccer?.errors.orEmpty().forEach { (kind, message) ->
            val important = kind in setOf("official", "mapping", "fotmob", "score")
            val relevant = important || selection == "Overview" && kind in setOf("events", "statistics", "officials", "pressure") ||
                selection in setOf("Events", "Timeline") && kind in setOf("events", "players") || selection == "Lineups" && kind == "lineups" ||
                selection == "Team stats" && kind in setOf("statistics", "playerStats") || selection == "Commentary" && kind == "commentary" || selection == "Shots" && kind == "shots"
            if (relevant) add(PhoneMatchDataNotice("Soccer · $kind", message, important))
        }
        data.hockey?.errors.orEmpty().forEach { (kind, message) ->
            val important = kind in setOf("score", "landing")
            if (important || selection in setOf("Overview", "Team stats") && kind == "right-rail" || selection == "Plays" && kind == "play-by-play" || selection == "Box score" && kind == "boxscore")
                add(PhoneMatchDataNotice("Hockey · $kind", message, important))
        }
        snapshot.baseballExtraErrors.forEach { (kind, message) ->
            if (kind == "probability" && selection == "Team stats" || kind == "highlights" && selection == "Overview")
                add(PhoneMatchDataNotice(if (kind == "probability") "Win probability" else "Highlights", message, retry = "baseball:$kind"))
        }
        if (data.soccer?.overviewNotPublished == true) add(PhoneMatchDataNotice("Match details",
            "Detailed match data isn't published yet. Kickoff is from the ${match.league.shortName} schedule.", retry = null))
    }.filter { it.message.isNotBlank() }.sortedByDescending { it.urgent }
    val saved = data.hockey?.savedSnapshot == true || data.baseball?.savedSnapshot == true || data.football?.savedSnapshot == true ||
        data.basketball?.savedSnapshot == true || data.soccer?.savedSnapshot == true || offline && data.providerStamp != null
    val savedTime = data.baseball?.surfaceUpdatedAt?.values?.minOrNull() ?: data.basketball?.surfaceUpdatedAt?.get("score") ?:
        data.soccer?.surfaceUpdatedAt?.get("overview") ?: data.football?.summaryUpdatedAt?.takeIf { it > 0 } ?: data.providerStamp?.requestedAt
    val freshness = when {
        saved -> PhoneMatchDataStamp(listOf(if (offline) "Offline" else "Saved", data.providerStamp?.provider.orEmpty(), "data").filter(String::isNotBlank).joinToString(" "), savedTime)
        nflDelayed -> PhoneMatchDataStamp("Live NFL data delayed", data.football?.summaryUpdatedAt?.takeIf { it > 0 })
        else -> null
    }
    val provenance = buildList {
        data.soccer?.let { soccer -> soccer.scoreSource?.let { add(PhoneMatchDataStamp("Score · $it", soccer.scoreUpdatedAt)) } }
        data.providerStamp?.let { add(PhoneMatchDataStamp(it.provider, it.requestedAt)) }
        if (snapshot.checkedAt > 0 && match.nativeReference == null) add(PhoneMatchDataStamp("ESPN summary", snapshot.checkedAt))
    }.distinct()
    return PhoneMatchDataStatus(notices, provenance, freshness)
}
