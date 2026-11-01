package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.net.parseJson
import org.junit.Assert.*
import org.junit.Test

class PhoneBillingPolicyTest {
    private val allowed = setOf("example.premium")
    private fun response(token: String = "test-only-token", products: String = "[\"example.premium\"]", packageName: String = "example.app", verified: Boolean = true, entitled: Boolean = true, acknowledged: Boolean = true, until: Long = 2000) =
        parseJson("""{"verified":$verified,"entitled":$entitled,"acknowledged":$acknowledged,"packageName":"$packageName","purchaseTokenSha256":"${PhoneBillingPolicy.tokenHash(token)}","productIds":$products,"validUntilMillis":$until}""")
    private fun check(raw: kotlinx.serialization.json.JsonElement) = PhoneBillingPolicy.verifiedUntil(raw, "example.app", "test-only-token", allowed, allowed, 1000)

    @Test fun developmentOverrideIsExplicitAndUnconfiguredBuildNeverPurchases() {
        val config = PhoneBillingConfiguration(true, emptySet(), emptySet(), "")
        assertFalse(config.canPurchase); assertTrue(PhoneBillingState(unlockedBuild = true).isPremium)
        assertFalse(PhoneBillingState(unlockedBuild = false).isPremium)
        assertFalse(config.copy(unlockAll = false).canPurchase)
    }
    @Test fun configurationRequiresExactValidIdsNonoverlapAndOwnedHttpsEndpoint() {
        val config = PhoneBillingConfiguration(false, allowed, emptySet(), "https://billing.example.com/verify")
        assertTrue(config.canPurchase); assertFalse(config.copy(unlockAll = true).canPurchase)
        for (bad in listOf("http://example.com/verify", "https://user:password@example.com/verify", "https://example.com/verify?token=secret", "https://example.com/#data", "file:///verify")) assertFalse(config.copy(verificationUrl = bad).canPurchase)
        assertFalse(config.copy(lifetime = allowed).canPurchase)
        assertFalse(config.copy(subscriptions = setOf("invalid id")).canPurchase)
        assertEquals(setOf("a", "b"), PhoneBillingConfiguration.ids(" a, b, a, ,"))
    }
    @Test fun entitlementRequiresVerifiedAcknowledgedReceiptForExactTokenPackageAndProducts() {
        assertEquals(2000L, check(response()))
        for (raw in listOf(response(verified = false), response(entitled = false), response(acknowledged = false), response(token = "different-token"), response(packageName = "different.app"), response(products = "[]"), response(products = "[\"other.product\"]"))) assertNull(check(raw))
        assertNull(PhoneBillingPolicy.verifiedUntil(response(), "example.app", "test-only-token", setOf("other.product"), allowed, 1000))
        assertNull(PhoneBillingPolicy.verifiedUntil(response(), "example.app", "", allowed, allowed, 1000))
    }
    @Test fun expiredMissingOrUnboundedGrantsNeverUnlock() {
        assertNull(check(response(until = 1000))); assertNull(check(response(until = 999)))
        assertNull(check(response(until = 1001 + PhoneBillingPolicy.MAX_VERIFICATION_AGE)))
        assertNull(check(parseJson("{}")))
    }
    @Test fun billingPeriodsAndTrialPhasesAreFormattedWithoutInventingAnOffer() {
        assertEquals("1 month", PhoneBillingPolicy.period("P1M")); assertEquals("1 year", PhoneBillingPolicy.period("P1Y"))
        assertEquals("7 days", PhoneBillingPolicy.period("P7D")); assertEquals("2 weeks", PhoneBillingPolicy.period("P2W"))
        assertEquals("P0D", PhoneBillingPolicy.period("P0D")); assertEquals("unknown", PhoneBillingPolicy.period("unknown"))
    }
    @Test fun statsPremiumSectionsMatchIosWhileTeamAndFieldRemainAccessible() {
        assertEquals(setOf(PhoneStatsSection.Standings, PhoneStatsSection.Leaders, PhoneStatsSection.Injuries), PhoneStatsSection.entries.filter { it.requiresPremium }.toSet())
    }
}
