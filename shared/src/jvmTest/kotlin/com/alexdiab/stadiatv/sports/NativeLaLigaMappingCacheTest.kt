package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeLaLigaMappingCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val at = Dates.parseIso("2026-10-23T20:00:00Z")!!
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/laliga-$name.source.json")!!.readText())
    private fun game() = NativeLaLigaMapping.match(fixture("official-matches-finished")["matches"].array.first(), 2026, "verified-2026")!!
    private fun mapping() = NativeLaLigaReconciliation.resolve(game(), listOf(fixture("fotmob-day-matches")))!!
    @Test fun confirmedMappingSurvivesColdStartOnlyForTheSameOfficialFixtureScope(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeLaLigaMappingCache(folder, { at })
        cache.save(game(), mapping()); val cold = NativeLaLigaMappingCache(folder, { at + 1000 })
        assertEquals(mapping(), cold.load(game()))
        assertNull(cold.load(game().copy(home = game().away, away = game().home)))
        assertNull(cold.load(game().copy(nativeReference = game().nativeReference!!.copy(season = 2025))))
        assertNull(cold.load(game().copy(nativeReference = game().nativeReference!!.copy(seasonType = "other-year"))))
        assertFalse(File(folder, "mapping.pending").exists())
    }
    @Test fun rescheduledKickoffBeyondToleranceInvalidatesPriorMatchMapping(): Unit = runBlocking {
        val cache = NativeLaLigaMappingCache(temporary.newFolder(), { at }); cache.save(game(), mapping())
        assertNotNull(cache.load(game().copy(startMillis = game().startMillis + 6 * 3_600_000)))
        assertNull(cache.load(game().copy(startMillis = game().startMillis + 6 * 3_600_000 + 1)))
    }
    @Test fun foreignFutureExpiredAndCorruptPersistedMappingsAreRejected(): Unit = runBlocking {
        var clock = at; val folder = temporary.newFolder(); val cache = NativeLaLigaMappingCache(folder, { clock })
        cache.save(game(), mapping()); clock = at - 300_001; assertNull(cache.load(game()))
        clock = at + 31L * 86_400_000; assertNull(cache.load(game())); clock = at
        val file = File(folder, "mapping.json")
        file.writeText(file.readText().replace("LaLiga-FotMob", "ESPN")); assertNull(cache.load(game()))
        file.writeText("{malformed"); assertNull(cache.load(game()))
    }
    @Test fun invalidForeignMappingsNeverOverwriteConfirmedIdentity(): Unit = runBlocking {
        val cache = NativeLaLigaMappingCache(temporary.newFolder(), { at }); cache.save(game(), mapping())
        for (invalid in listOf(mapping().copy(officialId = "../102249"), mapping().copy(officialHome = "epl:21"), mapping().copy(fotmobAway = mapping().fotmobHome),
            mapping().copy(fotmobId = "../5868011"), mapping().copy(subscription = "../season"))) cache.save(game(), invalid)
        assertEquals(mapping(), cache.load(game()))
    }
    @Test fun cacheContainsAtMost128SmallPublicIdentityRecords(): Unit = runBlocking {
        var clock = at; val folder = temporary.newFolder(); val cache = NativeLaLigaMappingCache(folder, { clock })
        val original = game(); val confirmed = mapping()
        for (index in 1..140) {
            val id = "${100000 + index}"; val next = original.copy(id = "laliga:$id", nativeReference = original.nativeReference!!.copy(gameId = id))
            cache.save(next, confirmed.copy(officialId = id, fotmobId = "${5868011 + index}")); clock++
        }
        val file = File(folder, "mapping.json"); assertTrue(file.length() in 1..128L * 1024)
        val raw = parseJson(file.readText()); assertEquals(128, raw["entries"]!!.jsonObject.size)
        assertTrue(raw["entries"]["100001"] == null); assertNotNull(raw["entries"]["100140"])
        assertFalse(raw.toString().contains("password", true))
    }
}
