package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.Athlete
import com.alexdiab.stadiatv.domain.AthleteOverview
import com.alexdiab.stadiatv.domain.GameCentre
import com.alexdiab.stadiatv.domain.Injury
import com.alexdiab.stadiatv.domain.LeaderBoard
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.Racer
import com.alexdiab.stadiatv.domain.RosterGroup
import com.alexdiab.stadiatv.domain.SportsCapability
import com.alexdiab.stadiatv.domain.StandingGroup
import com.alexdiab.stadiatv.domain.Team

class UnsupportedCapabilityException(capability: SportsCapability, league: String) :
    UnsupportedOperationException("$league has no source for ${capability.name.lowercase()}")

/**
 * One league's data source. Capabilities a source does not have throw
 * [UnsupportedCapabilityException]; they never return fabricated empty results, so callers can tell
 * "nothing today" from "can't serve this" (the same contract the iOS native providers follow).
 */
interface SportsProvider {
    val league: League
    /** Provider namespace for IDs (for example nhl:9); null means no native-identity route. */
    val providerId: String? get() = null
    val capabilities: Set<SportsCapability>

    suspend fun scores(date: Long? = null): List<Match>
    suspend fun schedule(start: Long, days: Int): List<Match>

    suspend fun teams(): List<Team> = unsupported(SportsCapability.Teams)
    suspend fun standings(): List<StandingGroup> = unsupported(SportsCapability.Standings)
    suspend fun roster(teamId: String): List<RosterGroup> = unsupported(SportsCapability.Rosters)
    suspend fun athlete(athlete: Athlete): AthleteOverview = unsupported(SportsCapability.Players)
    suspend fun leaders(): List<LeaderBoard> = unsupported(SportsCapability.Leaders)
    suspend fun injuries(): List<Injury> = unsupported(SportsCapability.Injuries)
    suspend fun field(): List<Racer> = unsupported(SportsCapability.Field)
    suspend fun gameCentre(match: Match): GameCentre = unsupported(SportsCapability.GameDetails)

    /** Adds live detail (clock, situation) to a match from a richer endpoint, when available. */
    suspend fun enrich(match: Match): Match = match
}

fun SportsProvider.unsupported(capability: SportsCapability): Nothing =
    throw UnsupportedCapabilityException(capability, league.name)

/** ESPN-backed provider usable for every league. */
class EspnProvider(override val league: League, private val api: EspnApi) : SportsProvider {
    override val capabilities: Set<SportsCapability> = buildSet {
        add(SportsCapability.Scores)
        add(SportsCapability.Schedule)
        add(SportsCapability.Standings)
        add(SportsCapability.Teams)
        add(SportsCapability.Rosters)
        add(SportsCapability.Players)
        add(SportsCapability.Leaders)
        add(SportsCapability.Injuries)
        add(SportsCapability.GameDetails)
        if (league.group == com.alexdiab.stadiatv.domain.SportGroup.Racing ||
            league.group == com.alexdiab.stadiatv.domain.SportGroup.Golf
        ) add(SportsCapability.Field)
    }

    override suspend fun scores(date: Long?) = api.scoreboard(league, date)
    override suspend fun schedule(start: Long, days: Int) = api.schedule(league, start, days)
    override suspend fun teams() = api.teams(league)
    override suspend fun standings() = api.standings(league)
    override suspend fun roster(teamId: String) = api.roster(league, teamId)
    override suspend fun athlete(athlete: Athlete) = api.athleteOverview(league, athlete)
    override suspend fun leaders() = api.leaders(league)
    override suspend fun injuries() = api.injuries(league)
    override suspend fun field() = api.field(league)
    override suspend fun gameCentre(match: Match) = api.summary(match)
}
