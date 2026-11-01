package com.alexdiab.stadiatv.foundation

import com.alexdiab.stadiatv.addons.*
import com.alexdiab.stadiatv.data.fantasy.*
import com.alexdiab.stadiatv.data.odds.OddsService
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.sports.SharedSports
import com.alexdiab.stadiatv.sports.SportsBridge
import com.alexdiab.stadiatv.sports.f1.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.*
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.*

/** Swift entry point. Lifetime is the app, with a host-selected Library/Caches directory and Keychain vault. */
class AppleFoundation(cacheDirectory: String, vault: AddonVault) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun client(timing: Boolean = false) = HttpClient(Darwin) {
        expectSuccess = false
        followRedirects = false
        engine { configureSession { setURLCache(null); setHTTPCookieStorage(null); setHTTPShouldSetCookies(false) } }
        install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 20_000 }
        if (timing) {
            install(HttpCookies)
            install(WebSockets) { maxFrameSize = F1Wire.LIMIT.toLong() }
        }
    }
    private val sportsHttp = KtorSportsHttp(client())
    private val timingHttp = client(timing = true)
    private val addonHttp = client()
    val sports = SharedSports(sportsHttp, SportsFile(cacheDirectory), KtorF1Transport(timingHttp))
    val nhlDiscovery = com.alexdiab.stadiatv.sports.AppleNhlDiscovery(sports.nhl, scope)
    val sportsBridge = SportsBridge(sports.sports,scope)
    val espnFantasy = EspnFantasyApi(sportsHttp)
    val sleeperFantasy = SleeperFantasyApi(sportsHttp)
    val addons = AddonLibrary(StremioClient(AddonHttp { url ->
        require(safeWebURL(url))
        addonHttp.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) throw AddonProblem("The add-on could not be reached.")
            val bytes = response.bodyAsChannel().readRemaining(4L*1024*1024+1).readByteArray()
            if (bytes.size > 4*1024*1024) throw AddonProblem("The add-on response was too large.")
            bytes.decodeToString()
        }
    }), vault)
    fun odds(baseUrl: String, apiKey: String) = OddsService(sportsHttp, baseUrl, apiKey)
    fun tmdb(readToken: () -> String) = com.alexdiab.stadiatv.catalog.TmdbRepository(sportsHttp, readToken)
    fun close() { scope.cancel(); sportsHttp.close(); timingHttp.close(); addonHttp.close() }
}
