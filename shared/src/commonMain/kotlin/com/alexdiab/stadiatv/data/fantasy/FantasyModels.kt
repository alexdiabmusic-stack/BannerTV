package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.foundation.*
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** The ESPN Fantasy games the app can read. `code` is the game segment of ESPN's API path. */
enum class FantasySport(val code: String, val label: String, private val benchSlot: Int, private val slots: Map<Int, String>) {
    Football(
        "ffl", "NFL", 20,
        mapOf(0 to "QB", 2 to "RB", 4 to "WR", 6 to "TE", 16 to "DST", 17 to "K", 20 to "BN", 21 to "IR", 23 to "FLEX"),
    ),
    Hockey(
        "fhl", "NHL", 7,
        mapOf(0 to "C", 1 to "LW", 2 to "RW", 3 to "F", 4 to "D", 5 to "G", 6 to "UTIL", 7 to "BN", 8 to "IR"),
    ),
    Basketball(
        "fba", "NBA", 12,
        mapOf(0 to "PG", 1 to "SG", 2 to "SF", 3 to "PF", 4 to "C", 5 to "G", 6 to "F", 11 to "UTIL", 12 to "BN", 13 to "IR"),
    ),
    Baseball(
        "flb", "MLB", 13,
        mapOf(0 to "C", 1 to "1B", 2 to "2B", 3 to "3B", 4 to "SS", 5 to "OF", 10 to "UTIL", 11 to "P", 13 to "BN", 14 to "IL", 15 to "SP", 16 to "RP"),
    );

    fun slotLabel(slotId: Int?): String = slots[slotId ?: benchSlot] ?: "—"

    fun slotKind(slotId: Int?): SlotKind = when (slotLabel(slotId)) {
        "BN" -> SlotKind.Bench
        "IR", "IL" -> SlotKind.Reserve
        else -> SlotKind.Starter
    }

    /**
     * ESPN numbers a season by the year it ends for winter sports (the 2026-27 NHL and NBA seasons are 2027)
     * and by the year it starts for football and baseball.
     */
    fun currentSeason(at: Long = nowMillis()): Int {
        val year = SportsTime.format("yyyy", at).toInt()
        val month = SportsTime.format("MM", at).toInt() - 1 // 0-based
        return when (this) {
            Hockey, Basketball -> if (month >= 7) year + 1 else year
            Football -> if (month <= 1) year - 1 else year
            Baseball -> year
        }
    }

    companion object {
        fun fromCode(code: String?): FantasySport? = entries.firstOrNull { it.code == code }
    }
}

enum class SlotKind { Starter, Bench, Reserve }

/** Which league the user connected, and which team in it is theirs. Persisted; the cookies are kept separately and encrypted. */
@Immutable
@Serializable
data class FantasyConnection(
    val sportCode: String,
    val season: Int,
    val leagueId: String,
    val teamId: Int? = null,
    val leagueName: String? = null,
) {
    val sport: FantasySport? get() = FantasySport.fromCode(sportCode)
    val key: String get() = "espn:$sportCode:$season:$leagueId"
}

@Immutable
data class FantasyTeam(
    val id: Int,
    val name: String,
    val abbreviation: String,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val pointsFor: Double?,
    val pointsAgainst: Double?,
    val rank: Int?,
    val owners: List<String>,
) {
    val record: String get() = if (ties > 0) "$wins-$losses-$ties" else "$wins-$losses"
}

@Immutable
data class FantasyPlayer(
    val id: String,
    val name: String,
    val slot: String,
    val kind: SlotKind,
    val points: Double?,
    val injuryStatus: String?,
    val proTeamId: Int?,
)

@Immutable
data class FantasySide(val teamId: Int, val name: String, val points: Double)

@Immutable
data class FantasyMatchup(val week: Int, val me: FantasySide, val opponent: FantasySide?) {
    val isWinning: Boolean get() = opponent != null && me.points > opponent.points
    val margin: Double get() = opponent?.let { me.points - it.points } ?: 0.0
}

@Immutable
data class FantasySnapshot(
    val leagueName: String,
    val season: Int,
    val myTeam: FantasyTeam?,
    val standings: List<FantasyTeam>,
    val roster: List<FantasyPlayer>,
    val matchup: FantasyMatchup?,
    val refreshedAtMillis: Long,
)

/** Why a Fantasy request failed, in terms the connect screen can explain. */
sealed class FantasyException(message: String) : Exception(message) {
    class AuthRequired : FantasyException("This league is private. Add your espn_s2 and SWID cookies to connect it.")
    class LeagueNotFound : FantasyException("ESPN couldn't find that league. Check the league ID, sport and season.")
    class BadResponse : FantasyException("ESPN sent something unexpected. Try again in a moment.")
}
