package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.HttpStatusException
import kotlinx.coroutines.*

/** Swift owns and cancels a request when its view/task disappears. No exception crosses the ObjC boundary. */
class SportsRequest internal constructor(private val job: Job) { fun cancel() = job.cancel() }
data class SportsFailure(val message: String, val httpStatus: Int?)

/** Main-thread callbacks are selected by the host-provided scope, not by shared business logic. */
class SportsBridge internal constructor(private val sports: SportsRepository, private val scope: CoroutineScope) {
    private fun <T> request(work: suspend () -> T, completion: (T?, SportsFailure?) -> Unit) = SportsRequest(scope.launch {
        val result = try { work() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            completion(null,SportsFailure("Sports data is currently unavailable. Try again shortly.",(error as? HttpStatusException)?.code))
            return@launch
        }
        ensureActive()
        completion(result,null)
    })
    fun scores(league: League, dateMillis: Long?, completion: (List<Match>?, SportsFailure?) -> Unit) = request({ sports.scoreboard(league,dateMillis) },completion)
    fun schedule(league: League, startMillis: Long, days: Int, completion: (List<Match>?, SportsFailure?) -> Unit) = request({ sports.schedule(league,startMillis,days) },completion)
    fun teams(league: League, completion: (List<Team>?, SportsFailure?) -> Unit) = request({ sports.teams(league) },completion)
    fun standings(league: League, completion: (List<StandingGroup>?, SportsFailure?) -> Unit) = request({ sports.standings(league) },completion)
    fun roster(league: League, teamId: String, completion: (List<RosterGroup>?, SportsFailure?) -> Unit) = request({ sports.roster(league,teamId) },completion)
    fun athlete(league: League, athlete: Athlete, completion: (AthleteOverview?, SportsFailure?) -> Unit) = request({ sports.athlete(league,athlete) },completion)
    fun leaders(league: League, completion: (List<LeaderBoard>?, SportsFailure?) -> Unit) = request({ sports.leaders(league) },completion)
    fun injuries(league: League, completion: (List<Injury>?, SportsFailure?) -> Unit) = request({ sports.injuries(league) },completion)
    fun gameCentre(match: Match, completion: (GameCentre?, SportsFailure?) -> Unit) = request({ sports.gameCentre(match) },completion)
}
