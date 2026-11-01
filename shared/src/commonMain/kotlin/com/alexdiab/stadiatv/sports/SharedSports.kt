package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.foundation.SportsFile
import com.alexdiab.stadiatv.net.SportsHttp
import com.alexdiab.stadiatv.sports.f1.*
import com.alexdiab.stadiatv.sports.pga.*

/** A single lazy provider graph for every host. Hosts supply transport and a disposable cache directory.
 * Provider identities, fallback/circuit breakers and Game Centre reducers are shared, never reassembled by UI. */
class SharedSports(http: SportsHttp, private val directory: SportsFile, timing: F1Transport) {
    private fun cache(name: String) = SportsFile(directory, name)
    val espn: EspnApi by lazy { EspnApi(http) }
    val f1: F1Repository by lazy { F1Repository(http, timing, cache("f1-sessions")) }
    val pga: PgaRepository by lazy { PgaRepository(http) }
    val nfl: NativeFootball by lazy { NativeFootball(http,
        snapshots = NativeFootballSnapshotCache(cache("nfl-games-v1"))) }
    val nflProvider: NativeFootballProvider by lazy { NativeFootballProvider(nfl) }
    val hockeyDetails: NativeHockey by lazy { NativeHockey(http,
        snapshots = NativeHockeySnapshotCache(cache("nhl-games-v1"))) }
    val nhl: NativeHockeyProvider by lazy { NativeHockeyProvider(http, hockeyDetails) }
    val baseballDetails: NativeBaseball by lazy { NativeBaseball(http,
        snapshots = NativeBaseballSnapshotCache(cache("mlb-games-v1"))) }
    val mlb: NativeBaseballProvider by lazy { NativeBaseballProvider(http, baseballDetails) }
    val baseballExtras: NativeBaseballExtras by lazy { NativeBaseballExtras(http,
        snapshots = NativeBaseballExtrasCache(cache("mlb-extras-v1"))) }
    val nbaClient by lazy { NativeBasketballClient(NativeBasketballLeague.NBA, http) }
    val wnbaClient by lazy { NativeBasketballClient(NativeBasketballLeague.WNBA, http) }
    val basketballDetails by lazy { NativeBasketball(http, nbaClient, wnbaClient,
        NativeBasketballSnapshotCache(cache("basketball-games-v1"))) }
    val nba by lazy { NativeBasketballProvider(nbaClient, details = { basketballDetails.load(it, null) },
        cachedMatch = { basketballDetails.cachedMatch(NativeBasketballLeague.NBA, it) }) }
    val wnba by lazy { NativeBasketballProvider(wnbaClient, details = { basketballDetails.load(it, null) },
        cachedMatch = { basketballDetails.cachedMatch(NativeBasketballLeague.WNBA, it) }) }
    val eplClient by lazy { NativePremierLeagueClient(http) }
    val soccerDetails by lazy { NativeSoccer(http, client = eplClient,
        snapshots = NativeSoccerSnapshotCache(cache("soccer-epl-v1"))) }
    val epl by lazy { NativePremierLeagueProvider(eplClient, details = { soccerDetails.load(it, null) }, cachedMatch = soccerDetails::cachedMatch) }
    val mlsClient by lazy { NativeMlsClient(http) }
    val mlsDetails by lazy { NativeMls(mlsClient,
        snapshots = NativeMlsSnapshotCache(cache("soccer-mls-v1")),
        upcoming = NativeMlsUpcomingCache(cache("soccer-mls-upcoming-v1"))) }
    val mls by lazy { NativeMlsProvider(mlsClient, details = { mlsDetails.load(it, null) }, cachedMatch = mlsDetails::cachedMatch, rememberSchedule = mlsDetails::rememberSchedule) }
    val laLigaClient by lazy { NativeLaLigaClient(http) }
    val fotMobClient by lazy { NativeFotMobClient(http) }
    val laLiga: NativeLaLigaProvider by lazy {
        NativeLaLigaProvider(laLigaClient, details = { laLigaDetails.load(it) }, cachedMatch = { laLigaDetails.cachedMatch(it) })
    }
    val laLigaDetails by lazy { NativeLaLiga(laLiga, fotMobClient,
        NativeLaLigaSnapshotCache(cache("soccer-laliga-v1")),
        NativeLaLigaMappingCache(cache("soccer-laliga-mappings-v1"))) }
    val sports: SportsRepository by lazy { SportsRepository(native = mapOf("racing/f1" to f1, "golf/pga" to pga, "hockey/nhl" to nhl, "baseball/mlb" to mlb, "football/nfl" to nflProvider,
        "football/cfl" to CflProvider(http), "soccer/uefa.champions" to ChampionsLeagueProvider(fotMobClient), "basketball/nba" to nba, "basketball/wnba" to wnba, "soccer/eng.1" to epl, "soccer/usa.1" to mls, "soccer/esp.1" to laLiga), espn = espn) }
}
