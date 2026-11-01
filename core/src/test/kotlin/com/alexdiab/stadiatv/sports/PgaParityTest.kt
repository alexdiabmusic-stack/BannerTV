package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.sports.pga.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** iOS PGA assertions translated to JVM. All edge-case golfers below are constructed test inputs. */
class PgaParityTest {
    private fun gzip(text: String): String { val out = ByteArrayOutputStream(); GZIPOutputStream(out).use { it.write(text.toByteArray()) }; return out.toByteArray().toByteString().base64() }
    @Test fun gzipNotRawDeflateAndOutputLimit() {
        assertEquals("Test Golfer", PgaPayload.decode(gzip("""{"players":[{"player":{"id":"1","displayName":"Test Golfer"}}]}"""))["players"][0]["player"]["displayName"].string())
        for (bad in listOf("", "not-base64!!", byteArrayOf(0,1,2).toByteString().base64(), gzip("not json"))) assertThrows(Exception::class.java) { PgaPayload.decode(bad) }
        assertThrows(Exception::class.java) { PgaPayload.decode(gzip("{\"value\":\"${"a".repeat(10000)}\"}"), 1000) }
    }
    @Test fun nativeLeaderboardRetainsProviderOrderCutStatusAndUnknownScores() {
        val board = PgaMapper.leaderboard(parseJson("""{"players":[{"player":{"id":"39971","displayName":"Sungjae Im","country":"KOR"},"scoringData":{"position":"T5","total":"-7","score":"-7","thru":"F*","currentRound":1,"rounds":["64","-","-","-"],"playerState":"COMPLETE"}},{"player":{"id":"2"},"scoringData":{"position":"CUT","total":"+5"}},{"player":{"id":"3"},"scoringData":{"position":"WD"}}]}"""))
        assertEquals(listOf("39971", "2", "3"), board.map { it.player.id }); assertEquals("Finished", board[0].status); assertEquals("Cut", board[1].status); assertEquals("Withdrawn", board[2].status)
        assertEquals("64", board[0].rounds.first()); assertEquals("F*", board[0].thru); assertNull(board[2].total)
        assertEquals(board, PgaMapper.merge(board, emptyList()))
        val correction = board[0].copy(total = "-6")
        assertEquals(listOf(correction, board[1], board[2]), PgaMapper.merge(board, listOf(correction)))
    }
    @Test fun scorecardsPreserveBackNineSequenceAndUnknownHoleScores() {
        val rounds = PgaMapper.scorecard(parseJson("""{"roundScores":[{"roundNumber":1,"total":64,"scoreToPar":"-7","firstNine":{"holes":[{"holeNumber":1,"par":4,"score":3,"sequenceNumber":10}]},"secondNine":{"holes":[{"holeNumber":10,"par":4,"score":null,"sequenceNumber":1}]}}]}"""))
        assertEquals(listOf(1, 10), rounds[0].holes.map { it.hole }); assertEquals(10, rounds[0].holes.minBy { it.sequence!! }.hole)
        assertEquals(-1, rounds[0].holes[0].delta); assertNull(rounds[0].holes[1].delta)
    }
    @Test fun leaderboardMovementDoesNotLeakProviderEnumsOrInventChange() {
        val row = PgaMapper.leaderboard(parseJson("""{"players":[{"player":{"id":"1","displayName":"Test"},"scoringData":{"movementDirection":"CONSTANT","movementAmount":"0"}}]}"""))[0]
        assertNull(row.movementLabel)
        assertEquals("Up 3", row.copy(movement = "UP", movementAmount = "3").movementLabel)
        assertEquals("Down 2", row.copy(movement = "DOWN", movementAmount = "2").movementLabel)
        assertNull(row.copy(movement = "UP", movementAmount = "unknown").movementLabel)
        assertNull(row.copy(movement = "UNKNOWN", movementAmount = "4").movementLabel)
    }
    @Test fun realStrokeNestingAndMissingCoordinates() {
        val holes = PgaMapper.shots(parseJson("""{"holes":[{"holeNumber":14,"par":4,"strokes":[{"strokeNumber":1,"distance":312,"fromLocation":"Tee","toLocation":"Fairway","overview":{"leftToRightCoords":{"fromCoords":{"x":0.1,"y":0.2,"tourcastX":100},"toCoords":{"x":0.4,"y":0.5}}}},{"strokeNumber":2}]}]}"""))
        assertEquals(.1, holes[0].strokes[0].leftFrom!!.x, .0001); assertEquals(.4, holes[0].strokes[0].leftTo!!.x, .0001)
        assertEquals("312", holes[0].strokes[0].distance); assertNull(holes[0].strokes[1].leftFrom)
        assertTrue(PgaMapper.shots(parseJson("""{"holes":[{"holeNumber":1,"shots":[{"strokeNumber":1}]}]}"""))[0].strokes.isEmpty())
    }
    @Test fun latestCourseRoundAndMixedCupInfoRows() {
        val courses = PgaMapper.courses(parseJson("""{"courses":[{"courseId":"c","courseName":"Course","roundHoleStats":[{"holeStats":[{"__typename":"CourseHoleStats","courseHoleNum":1,"scoringAverage":4.1}]},{"holeStats":[{"__typename":"Info"},{"__typename":"CourseHoleStats","courseHoleNum":1,"scoringAverage":4.5}]}]}]}"""))
        assertEquals(4.5, courses[0].holes[0].average!!, .0001); assertEquals(1, courses[0].holes.size)
        val rows = PgaMapper.cup(parseJson("""{"projectedPlayers":[{"__typename":"TourCupCombinedInfo"}],"officialPlayers":[{"__typename":"TourCupCombinedPlayer","id":"1"},{"__typename":"TourCupCombinedInfo"}]}"""))
        assertEquals(1, rows.size); assertEquals("1", rows[0]["id"].string())
    }
    @Test fun suspendedAndNonStandardTournamentFormats() {
        assertEquals("Suspended", GolfTournament.status(null, "Suspended")); assertEquals("Final", GolfTournament.status("Official", null)); assertEquals("Live", GolfTournament.status(null, "IN_PROGRESS"))
        val t = PgaMapper.tournaments(parseJson("""{"tournaments":[{"id":"R2026475","tournamentName":"Test","formatType":"MATCH_PLAY","courses":[{"id":"1","courseName":"One"},{"id":"2","courseName":"Two"}]}]}"""))[0]
        assertFalse(t.strokePlay); assertEquals(2, t.courses.size)
    }
    @Test fun compressedShotOperationPreservesCaseAndNativeIds(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val payload = gzip("{\"holes\":[]}")
            server.enqueue(MockResponse().setBody("{\"data\":{\"shotDetailsV4Compressed\":{\"payload\":\"$payload\"}}}"))
            val url = server.url("/").toString().trimEnd('/')
            val pga = PgaRepository(HttpGateway(OkHttpClient()), PgaConfiguration(url, url, url, "public-test-key"))
            assertTrue(pga.shots("R2026475", "39971", 1).isEmpty())
            val request = server.takeRequest(); val body = parseJson(request.body.readUtf8())
            assertEquals("shotDetailsV4Compressed", body["operationName"].string()); assertEquals("39971", body["variables"]["playerId"].string())
            assertFalse(body["variables"]["includeRadar"].bool()); assertEquals("web", request.getHeader("x-pgat-platform"))
        } finally { server.shutdown() }
    }
}
