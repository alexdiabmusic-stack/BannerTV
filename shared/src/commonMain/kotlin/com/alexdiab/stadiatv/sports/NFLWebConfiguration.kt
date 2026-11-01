package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
/** Public WEB_DESKTOP application configuration from the iOS NFL client, not a user's NFL account.
 * Short-lived bearer tokens are minted on demand and never persisted or logged. */
internal object NFLWebConfiguration {
    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    fun fields(deviceId: String): Map<String, String> = mapOf(
        "clientKey" to com.alexdiab.stadiatv.foundation.ProviderDefaults.NFL_WEB_CLIENT_KEY,
        "clientSecret" to com.alexdiab.stadiatv.foundation.ProviderDefaults.NFL_WEB_CLIENT_SECRET,
        "deviceInfo" to "eyJtb2RlbCI6ImRlc2t0b3AiLCJvc05hbWUiOiJXaW5kb3dzIiwib3NWZXJzaW9uIjoiMTAiLCJ2ZXJzaW9uIjoiQ2hyb21lIn0=",
        "deviceId" to deviceId,
        "networkType" to "other",
    )
}

