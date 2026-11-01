# Android purchase integration — not activated

The phone source includes a Google Play Billing 9.1.0 client. Default builds use
`bannerUnlockAllFeatures=true`: all in-app features are accessible and no BillingClient connection or checkout is
started. There are no guessed products, hardcoded prices or invented free-trial offers. This is not a production billing
completion claim. The user has not created a Play Console app/products/signing configuration.

## Owner configuration (not secrets)

- `bannerSubscriptionIds`: comma-separated real subscription product IDs.
- `bannerLifetimeIds`: comma-separated real non-consumable one-time product IDs.
- `bannerBillingVerifyUrl`: owner-controlled HTTPS endpoint with no URL credentials/query/fragment.
- `bannerUnlockAllFeatures=false`: enables the configured paid mode; otherwise purchases remain bypassed/hidden.

IDs must be disjoint, lowercase Play product identifiers. The Gradle build rejects paid mode without product IDs and
an HTTPS URL, and the runtime validates the full configuration. Do not add a Play service-account credential, private
signing key or Apple product IDs without actually creating the corresponding Android products.

## Verification service contract — implementation/deployment still required

The client posts `packageName`, `purchaseToken`, `productIds` to the configured endpoint **only** for purchased,
non-suspended, recognized Play purchases for this exact package. No token is logged or persisted in the client.
The service must verify with Google Play Developer API, reconcile expiry/revocations/pending state, validate the exact
package/products, grant entitlement idempotently and acknowledge delivery with Google. Google service-account
credentials belong on that service, never in an APK. Rate limiting, authentication/anti-abuse, transport and RTDN/
Voided Purchases reconciliation need production design and validation; no endpoint was provisioned by this work.

An affirmative response must contain:

```json
{
  "verified": true,
  "entitled": true,
  "acknowledged": true,
  "packageName": "com.alexdiab.stadiatv.mobile",
  "purchaseTokenSha256": "<SHA-256 of the exact submitted token>",
  "productIds": ["<exact purchased product IDs>"],
  "validUntilMillis": 0
}
```

`validUntilMillis` above is a placeholder, **not a valid grant**. The real service returns a future Unix-millisecond
reverification deadline no more than 15 minutes ahead and no later than actual entitlement expiry. Client validation
requires exact token digest/package/product binding plus verified/entitled/acknowledged booleans. Pending, suspended,
unknown, expired, unacknowledged or mismatched responses never unlock access. A failed query retains an already
verified grant only until its deadline; a successful empty current-purchases query revokes it. Nothing is restored
from an editable Premium preference or the cloud-backup file. Backend verification must precede acknowledgement;
failed verification must follow Google's refund/revocation handling, not silently acknowledge a rejected purchase.

Only eligible Google Play offers and their actual localized pricing phases are displayed. ProductDetails expire after
one minute and are requeried rather than reused for old checkout. Active/pending purchases suppress duplicate
checkout. Subscription renewals, cancelled-but-still-active periods and suspended state are resolved by current Play
queries plus the verifier, not `isAutoRenewing` alone. Restore works independently of product-offer query success.
The client has one lifecycle-owned connection, bounded setup/query deadlines, automatic reconnect and foreground
rechecks. Additional billing retry/network-transition/device tests remain required.

## Still required before paid distribution

Real products/base plans/offers, legal/privacy/terms URLs, licensed Play install/testers, production signing,
verification/acknowledgement service, subscription-change flow, rejected/refunded/chargeback lifecycle tests, full
iOS premium-gate audit and device billing UI tests. No actual purchase has been attempted or tested here.
Current Stats/standings and Multiview access gates are present but bypassed by the default unlocked build.

Implementation references (official docs retrieved 2026-10-23):

- [Billing integration](https://developer.android.com/google/play/billing/integrate)
- [Billing release notes](https://developer.android.com/google/play/billing/release-notes)
- [Verification and security](https://developer.android.com/google/play/billing/security)
- [Purchase identity and suspension](https://developer.android.com/reference/com/android/billingclient/api/Purchase)
- [Suspended-purchase query parameter](https://developer.android.com/reference/com/android/billingclient/api/QueryPurchasesParams.Builder)

Tests cover configuration/endpoint rejection, receipt binding, acknowledgement/expiry checks, development override,
pricing-period presentation and the Stats premium section set. Passing JVM tests is not licensed purchase QA.
