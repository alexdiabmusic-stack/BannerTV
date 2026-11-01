package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.JsonElement
import java.net.URI
import java.security.MessageDigest

data class PhoneBillingConfiguration(val unlockAll: Boolean, val subscriptions: Set<String>, val lifetime: Set<String>, val verificationUrl: String) {
    val productIds get() = subscriptions + lifetime
    val configured: Boolean get() = productIds.isNotEmpty() && productIds.all { it.matches(Regex("[a-z0-9][a-z0-9._]{0,148}")) } && subscriptions.intersect(lifetime).isEmpty() &&
        runCatching { URI(verificationUrl).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null && it.query == null } }.getOrDefault(false)
    val canPurchase: Boolean get() = configured && !unlockAll
    companion object { fun ids(raw: String) = raw.split(',').map(String::trim).filter(String::isNotBlank).toSet() }
}

/** A receipt is never an entitlement merely because Play returned a successful checkout callback. */
object PhoneBillingPolicy {
    fun period(raw: String): String {
        val parts = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?").matchEntire(raw)?.groupValues?.drop(1) ?: return raw
        return parts.mapIndexedNotNull { index, value -> value.toIntOrNull()?.takeIf { it > 0 }?.let { count -> "$count ${listOf("year", "month", "week", "day")[index]}${if (count == 1) "" else "s"}" } }.joinToString(", ").ifBlank { raw }
    }
    fun tokenHash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun verifiedUntil(response: JsonElement, packageName: String, token: String, products: Set<String>, allowed: Set<String>, now: Long): Long? {
        val until = response["validUntilMillis"].long()
        val returned = response["productIds"].array.mapNotNull { it.stringOrNull() }.toSet()
        return until.takeIf {
            token.isNotBlank() && products.isNotEmpty() && products.all { id -> id in allowed } && returned == products &&
                response["verified"].bool() && response["entitled"].bool() && response["acknowledged"].bool() &&
                response["packageName"].string() == packageName && response["purchaseTokenSha256"].string() == tokenHash(token) &&
                until > now && until <= now + MAX_VERIFICATION_AGE
        }
    }
    const val MAX_VERIFICATION_AGE = 15 * 60_000L
}

data class PhoneBillingOffer(val key: String, val productId: String, val title: String, val description: String, val terms: List<String>, val subscription: Boolean)
data class PhoneBillingState(val unlockedBuild: Boolean = true, val configured: Boolean = false, val entitled: Boolean = false,
    val loading: Boolean = false, val checkoutActive: Boolean = false, val pending: Boolean = false,
    val offers: List<PhoneBillingOffer> = emptyList(), val checkedAt: Long = 0, val verifiedUntil: Long = 0,
    val notice: String? = null) {
    val isPremium get() = unlockedBuild || entitled
}
