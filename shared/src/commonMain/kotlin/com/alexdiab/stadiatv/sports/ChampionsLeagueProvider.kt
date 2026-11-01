package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*

class ChampionsLeagueProvider(private val client: NativeFotMobClient) : SportsProvider {
    override val league = League.find("soccer/uefa.champions")!!
    override val providerId = "FotMob"
    override val capabilities = setOf(SportsCapability.Scores,SportsCapability.Schedule,SportsCapability.Teams,SportsCapability.Standings)
    private fun fixtures(raw: JsonElement) = raw.path("fixtures","allMatches") as? JsonArray ?: throw SportsIOException("Champions League fixtures unavailable")
    private fun team(row: JsonElement?): Team? {
        val id=row["id"].stringOrNull()?.takeIf(NativeFotMobClient::validId) ?: return null
        val name=row["name"].stringOrNull() ?: return null; val short=row["shortName"].string(name)
        return Team("fotmob:$id",name,short,short,"https://images.fotmob.com/image_resources/logo/teamlogo/$id.png",league)
    }
    override suspend fun teams() = fixtures(client.championsLeague()).flatMap { listOfNotNull(team(it["home"]),team(it["away"])) }
        .associateBy { it.id }.values.sortedBy { it.name }
    override suspend fun scores(date: Long?) = schedule(date ?: nowMillis(),1)
    override suspend fun schedule(start: Long, days: Int): List<Match> {
        if (days <= 0) return emptyList()
        val from=SportsTime.startOfDay(start); val until=SportsTime.addDays(from,days)
        return fixtures(client.championsLeague()).mapNotNull(::match).filter { it.startMillis in from until until }
            .associateBy { it.id }.values.sortedWith(compareBy({it.startMillis},{it.id}))
    }
    internal fun match(raw: JsonElement): Match? {
        val id=raw["id"].string().takeIf(NativeFotMobClient::validId) ?: return null
        val home=team(raw["home"]) ?: return null; val away=team(raw["away"]) ?: return null
        val s=raw["status"]; val at=SportsTime.parseIso(s["utcTime"].string()) ?: return null
        val reason=(s["reason"]["shortKey"].stringOrNull() ?: s["reason"]["longKey"].string()).lowercase()
        val status=when {
            s["cancelled"].bool() || "cancel" in reason -> "Cancelled"
            s["finished"].bool() || s["awarded"].bool() || reason in setOf("fulltime_short","finished") -> "Final"
            "postpon" in reason -> "Postponed"
            "abandon" in reason || "suspend" in reason -> "Suspended"
            "delay" in reason -> "Delayed"
            reason in setOf("halftime_short","halftime") -> "Half-time"
            reason in setOf("1st_half","firsthalf","2nd_half","secondhalf") || s["started"].bool() && reason.isEmpty() -> "Live"
            s["started"].bool() && "half" in reason -> if ("time" in reason) "Half-time" else "Live"
            else -> SportsTime.kickoff(at)
        }
        val state=when(status) { "Final" -> GameState.FINAL; "Live","Half-time" -> GameState.LIVE; else -> GameState.PRE }
        val scores=s["scoreStr"].string().split('-').map(String::trim).takeIf { state != GameState.PRE && it.size==2 && it.all { n -> n.toIntOrNull()!=null } }
        fun side(t:Team,i:Int)=TeamSide(t.name,t.shortName,t.abbreviation,t.logoUrl,scores?.get(i),teamId=t.id)
        return Match("fotmob:$id",league,at,"${away.name} at ${home.name}","${away.shortName} @ ${home.shortName}",state,
            if(state==GameState.LIVE) clock(s,status) else status,side(home,0),side(away,1),
            nativeReference=NativeGameReference(providerId,id))
    }
    private fun clock(s: JsonElement?, status: String): String {
        val minute=s["liveTime"]["minute"].string().toIntOrNull()
        val added=(s["liveTime"]["added"] ?: s["liveTime"]["overtime"]).string().toIntOrNull() ?: 0
        return when {
            minute != null -> if(added > 0) "$minute+$added'" else "$minute'"
            status == "Half-time" -> "HT"
            else -> s["reason"]["long"].string(status)
        }
    }
    override suspend fun standings() = client.championsLeague()["table"].array.mapNotNull { table ->
        val data=table["data"]; val rows=data.path("table","all").array.mapNotNull { r ->
            val t=team(r) ?: return@mapNotNull null
            val stats=listOf("wins","draws","losses","pts","played","goalConDiff").mapNotNull { k -> r[k].stringOrNull()?.let { k to it } }.toMap()
            StandingRow(t.id,t.name,t.abbreviation,t.logoUrl,listOfNotNull(stats["wins"],stats["draws"],stats["losses"]).joinToString("-"),stats)
        }
        if(rows.isEmpty()) null else StandingGroup(data["leagueName"].string(league.name),rows,"FotMob")
    }
}
