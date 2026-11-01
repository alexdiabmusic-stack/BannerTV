package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.serialization.json.*
import kotlin.math.abs

/** Exact, scoped cross-provider reconciliation. Scores never participate in identity matching. */
object NativeLaLigaReconciliation {
    data class Mapping(val officialId: String, val year: Int, val subscription: String, val officialHome: String, val officialAway: String,
        val kickoff: Long, val fotmobId: String, val fotmobHome: String, val fotmobAway: String) {
        fun matches(game: Match): Boolean = game.league == NativeLaLigaMapping.league && game.nativeReference?.let { reference -> reference.provider == "LaLiga" && reference.gameId == officialId &&
            reference.season == year && reference.seasonType == subscription && game.id == "laliga:$officialId" && game.home.teamId == officialHome && game.away.teamId == officialAway &&
            abs(game.startMillis - kickoff) <= WINDOW } == true && valid()
        fun valid() = officialHome != officialAway && fotmobHome != fotmobAway &&
            NativeFotMobClient.validId(fotmobId) && NativeFotMobClient.validId(fotmobHome) && NativeFotMobClient.validId(fotmobAway) &&
            NativeLaLigaMapping.rawId(officialId) == officialId && officialHome.startsWith("laliga:") && officialAway.startsWith("laliga:") &&
            NativeLaLigaMapping.rawId(officialHome) != null && NativeLaLigaMapping.rawId(officialAway) != null && year in 1900..2200 &&
            subscription.matches(Regex("[a-z0-9-]{1,120}")) && kickoff > 0
    }
    private data class Candidate(val id: String, val homeId: String, val awayId: String, val home: String, val away: String, val at: Long)
    private const val WINDOW = 6 * 3_600_000L
    fun normalize(name: String): String {
        if (name.length > 256) return ""
        val folded = decomposeText(name).replace(Regex("\\p{M}"), "").lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ").replace(Regex(" (?:sad|s a d)$"), "")
        return aliases[folded] ?: folded
    }
    private val aliases = mapOf("athletic club" to "athletic bilbao", "futbol club barcelona" to "barcelona", "fc barcelona" to "barcelona",
        "real madrid club de futbol" to "real madrid", "club atletico de madrid" to "atletico madrid", "atletico de madrid" to "atletico madrid",
        "real club deportivo espanyol de barcelona" to "espanyol", "rcd espanyol" to "espanyol", "real sociedad de futbol" to "real sociedad",
        "villarreal club de futbol" to "villarreal", "real club celta de vigo" to "celta vigo", "sevilla futbol club" to "sevilla",
        "real betis balompie" to "real betis", "real club deportivo de a coruna" to "deportivo", "rc deportivo" to "deportivo",
        "cadiz club de futbol" to "cadiz", "rayo vallecano de madrid" to "rayo vallecano", "getafe club de futbol" to "getafe",
        "levante union deportiva" to "levante", "real club deportivo mallorca" to "mallorca", "rcd mallorca" to "mallorca",
        "girona futbol club" to "girona", "elche club de futbol" to "elche", "club atletico osasuna" to "osasuna", "ca osasuna" to "osasuna",
        // Exact current official/FotMob pairs observed2026-10-24; no fuzzy word/score matching.
        "real racing club" to "racing santander", "rcd espanyol de barcelona" to "espanyol", "deportivo a coruna" to "deportivo",
        "valencia club de futbol" to "valencia", "malaga club de futbol" to "malaga")
    fun resolve(game: Match, days: List<JsonElement>): Mapping? {
        val reference = game.nativeReference?.takeIf { it.provider == "LaLiga" && game.id == "laliga:${it.gameId}" } ?: throw IOException("An official La Liga fixture is required")
        val year = reference.season ?: throw IOException("La Liga reconciliation season missing")
        val subscription = reference.seasonType ?: throw IOException("La Liga reconciliation subscription missing")
        if (days.size !in 1..3 || game.home.teamId == game.away.teamId) throw IOException("La Liga reconciliation scope invalid")
        val candidates = LinkedHashMap<String, Candidate>()
        for (raw in days) {
            val leagues = raw["leagues"] as? JsonArray ?: throw IOException("FotMob daily league directory missing")
            if (leagues.size > 256) throw IOException("FotMob daily league directory exceeds bounds")
            for (league in leagues.filter { it["id"].string() == "87" || it["primaryId"].string() == "87" }) {
                val rows = league["matches"] as? JsonArray ?: throw IOException("FotMob La Liga daily fixtures missing")
                if (rows.size > 100) throw IOException("FotMob daily La Liga fixtures exceed bounds")
                for (row in rows) {
                    val id = row["id"].string().takeIf(NativeFotMobClient::validId) ?: throw IOException("FotMob candidate match identity missing")
                    val h = row["home"]["id"].string().takeIf(NativeFotMobClient::validId) ?: throw IOException("FotMob candidate home identity missing")
                    val a = row["away"]["id"].string().takeIf(NativeFotMobClient::validId) ?: throw IOException("FotMob candidate away identity missing")
                    val home = normalize(row["home"]["name"].string()); val away = normalize(row["away"]["name"].string())
                    val at = NativeLaLigaMapping.iso(row["status"]["utcTime"].stringOrNull()) ?: throw IOException("FotMob candidate kickoff missing")
                    if (home.isBlank() || away.isBlank() || h == a) throw IOException("FotMob candidate club identity invalid")
                    val candidate = Candidate(id, h, a, home, away, at)
                    if (candidates.put(id, candidate)?.let { it != candidate } == true) throw IOException("FotMob candidate identity changed between days")
                }
            }
        }
        fun names(side: TeamSide) = setOf(normalize(side.displayName), normalize(side.shortName)).filter { it.length >= 3 }.toSet()
        val homes = names(game.home); val aways = names(game.away)
        val selected = candidates.values.filter { abs(it.at - game.startMillis) <= WINDOW && it.home in homes && it.away in aways }.singleOrNull() ?: return null
        return Mapping(reference.gameId, year, subscription, game.home.teamId ?: return null, game.away.teamId ?: return null,
            game.startMillis, selected.id, selected.homeId, selected.awayId).takeIf { it.matches(game) }
    }
    /** Revalidate every detail response, even after a stored mapping has been confirmed. */
    fun validate(raw: JsonElement, mapping: Mapping, game: Match) {
        val general = raw["general"]
        if (!mapping.matches(game) || general["matchId"].string() != mapping.fotmobId || general["leagueId"].string() != "87" ||
            general["homeTeam"]["id"].string() != mapping.fotmobHome || general["awayTeam"]["id"].string() != mapping.fotmobAway ||
            NativeLaLigaMapping.iso(raw["header"]["status"]["utcTime"].stringOrNull())?.let { abs(it - game.startMillis) <= WINDOW } != true) throw IOException("FotMob match/club/league/kickoff identity mismatch")
        val teams = raw["header"]["teams"] as? JsonArray ?: throw IOException("FotMob score header missing")
        if (teams.size != 2 || teams[0]["id"].string() != mapping.fotmobHome || teams[1]["id"].string() != mapping.fotmobAway) throw IOException("FotMob header club order mismatch")
    }
    suspend fun discover(client: NativeFotMobClient, game: Match): Mapping? {
        val days = (-1..1).map { offset -> client.day(Dates.format("yyyyMMdd", game.startMillis + offset * 86_400_000L, "UTC")) }
        return resolve(game, days)
    }
}
