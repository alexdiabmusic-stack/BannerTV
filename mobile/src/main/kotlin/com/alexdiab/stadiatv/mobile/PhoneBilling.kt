package com.alexdiab.stadiatv.mobile

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.alexdiab.stadiatv.net.HttpGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One connection owned by the phone ViewModel. No receipt/token is logged, backed up or persisted.
 * The owner-configured HTTPS service must validate with Google and acknowledge before granting.
 * Unconfigured/development builds do not contact billing or expose a checkout button. */
class PhoneBilling(private val context: Context, private val scope: CoroutineScope, private val http: HttpGateway,
    val configuration: PhoneBillingConfiguration = PhoneBillingConfiguration(BuildConfig.UNLOCK_ALL_FEATURES,
        PhoneBillingConfiguration.ids(BuildConfig.PLAY_SUBSCRIPTION_IDS), PhoneBillingConfiguration.ids(BuildConfig.PLAY_LIFETIME_IDS), BuildConfig.PLAY_VERIFY_URL)) {
    private val mutable = MutableStateFlow(PhoneBillingState(unlockedBuild = configuration.unlockAll, configured = configuration.canPurchase))
    val state = mutable.asStateFlow()
    private var client: BillingClient? = null
    private val connection = Mutex()
    private var refreshJob: Job? = null
    private var expiryJob: Job? = null
    private var refreshAgain = false
    private val offers = mutableMapOf<String, Pair<ProductDetails, String?>>()
    private var productsAt = 0L

    fun refresh(restore: Boolean = false) {
        if (!configuration.canPurchase) return
        if (refreshJob?.isActive == true) { refreshAgain = true; return }
        refreshJob = scope.launch {
            do {
                refreshAgain = false
                mutable.update { it.copy(loading = true, notice = null) }
                try {
                    val billing = ready()
                    var productWarning: String? = null
                    if (restore || System.currentTimeMillis() - productsAt > 60_000 || offers.isEmpty()) {
                        try { loadProducts(billing) }
                        catch (cancel: CancellationException) { if (cancel !is TimeoutCancellationException) throw cancel; productWarning = "Offers did not load. Restore was checked separately." }
                        catch (_: Exception) { productWarning = "Offers are unavailable. Restore was checked separately." }
                    }
                    val purchases = mutableListOf<Purchase>()
                    for (type in types()) purchases += queryPurchases(billing, type)
                    val active = purchases.distinctBy { it.purchaseToken }.filter { purchase ->
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isSuspended &&
                            purchase.packageName == context.packageName && purchase.products.isNotEmpty() && purchase.products.all { it in configuration.productIds }
                    }
                    var until = 0L
                    var verificationFailed = false
                    for (purchase in active) {
                        try {
                            val response = withContext(Dispatchers.IO) { withTimeout(12_000) {
                                http.postJson(configuration.verificationUrl, buildJsonObject {
                                    put("packageName", context.packageName); put("purchaseToken", purchase.purchaseToken)
                                    put("productIds", JsonArray(purchase.products.map(::JsonPrimitive)))
                                }.toString())
                            } }
                            val valid = PhoneBillingPolicy.verifiedUntil(com.alexdiab.stadiatv.net.parseJson(response), context.packageName,
                                purchase.purchaseToken, purchase.products.toSet(), configuration.productIds, System.currentTimeMillis())
                            if (valid != null) until = maxOf(until, valid)
                        } catch (cancel: CancellationException) { if (cancel !is TimeoutCancellationException) throw cancel; verificationFailed = true }
                        catch (_: Exception) { verificationFailed = true }
                    }
                    val now = System.currentTimeMillis()
                    val retained = mutable.value.verifiedUntil.takeIf { verificationFailed && active.isNotEmpty() && it > now } ?: 0
                    until = maxOf(until, retained)
                    val pending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING && it.products.any(configuration.productIds::contains) }
                    mutable.update { it.copy(entitled = until > now, verifiedUntil = until, loading = false, pending = pending, checkedAt = now,
                        notice = when { until > now && !verificationFailed -> if (restore) "Purchases restored. Premium is active." else "Premium is active."
                            verificationFailed -> "Purchase verification is unavailable. Retry restore; no unverified purchase is unlocked."
                            pending -> "Payment is pending. Premium unlocks after payment completes and is verified."
                            active.isNotEmpty() -> "This purchase could not be verified. No entitlement was granted."
                            restore -> "No active BannerTV purchases were found for this Google Play account."
                            else -> productWarning }) }
                    expiryJob?.cancel()
                    if (until > now) expiryJob = scope.launch { delay(until - now); mutable.update { it.copy(entitled = false, verifiedUntil = 0) } }
                } catch (cancel: CancellationException) {
                    if (cancel !is TimeoutCancellationException) throw cancel
                    mutable.update { it.copy(loading = false, notice = "Google Play did not respond in time. Try again.") }
                } catch (error: BillingFailure) { mutable.update { it.copy(loading = false, notice = billingNotice(error.code)) } }
                catch (_: Exception) { mutable.update { it.copy(loading = false, notice = "Google Play is unavailable. Check your connection and try again.") } }
            } while (refreshAgain && isActive)
        }
    }

    fun purchase(activity: Activity, key: String) {
        if (!configuration.canPurchase || mutable.value.checkoutActive || mutable.value.loading || mutable.value.entitled || mutable.value.pending) return
        val (product, token) = offers[key] ?: return
        // Refresh expired ProductDetails rather than launch with a long-lived cached offer.
        if (System.currentTimeMillis() - productsAt > 60_000 || client?.isReady != true) { refresh(); return }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).apply { if (token != null) setOfferToken(token) }.build()
        mutable.update { it.copy(checkoutActive = true, notice = null) }
        val result = try { client!!.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build()) }
        catch (_: Exception) { mutable.update { it.copy(checkoutActive = false, notice = "Google Play couldn't open checkout. No purchase was started.") }; return }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            mutable.update { it.copy(checkoutActive = false, notice = billingNotice(result.responseCode)) }
            if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) refresh(restore = true)
        }
    }

    private suspend fun ready(): BillingClient = connection.withLock {
        val billing = client ?: BillingClient.newBuilder(context.applicationContext).setListener { result, _ ->
            scope.launch {
                mutable.update { it.copy(checkoutActive = false, notice = if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) null else if (result.responseCode != BillingClient.BillingResponseCode.OK) billingNotice(result.responseCode) else it.notice) }
                if (result.responseCode in setOf(BillingClient.BillingResponseCode.OK, BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)) refresh(restore = true)
            }
        }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
            .enableAutoServiceReconnection().build().also { client = it }
        if (!billing.isReady) try {
            withTimeout(12_000) { suspendCancellableCoroutine<Unit> { continuation ->
                billing.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (!continuation.isActive) return
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(Unit)
                        else continuation.resumeWithException(BillingFailure(result.responseCode))
                    }
                    override fun onBillingServiceDisconnected() { /* Library reconnects on the next API call. */ }
                })
            } }
        } catch (error: Exception) { billing.endConnection(); if (client === billing) client = null; throw error }
        billing
    }

    private fun types() = listOfNotNull(BillingClient.ProductType.SUBS.takeIf { configuration.subscriptions.isNotEmpty() }, BillingClient.ProductType.INAPP.takeIf { configuration.lifetime.isNotEmpty() })

    private suspend fun loadProducts(billing: BillingClient) {
        val found = mutableMapOf<String, Pair<ProductDetails, String?>>()
        val display = mutableListOf<PhoneBillingOffer>()
        for (type in types()) {
            val ids = if (type == BillingClient.ProductType.SUBS) configuration.subscriptions else configuration.lifetime
            val params = QueryProductDetailsParams.newBuilder().setProductList(ids.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build() }).build()
            val products = withTimeout(12_000) { suspendCancellableCoroutine { continuation ->
                billing.queryProductDetailsAsync(params) { result, data -> if (continuation.isActive) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(data.productDetailsList)
                    else continuation.resumeWithException(BillingFailure(result.responseCode))
                } }
            } }
            products.filter { it.productId in ids }.forEach { product ->
                if (type == BillingClient.ProductType.SUBS) product.subscriptionOfferDetails.orEmpty().forEach { offer ->
                    val key = "${product.productId}:${offer.basePlanId}:${offer.offerId.orEmpty()}"
                    found[key] = product to offer.offerToken
                    val terms = offer.pricingPhases.pricingPhaseList.map { phase ->
                        val cycles = phase.billingCycleCount.takeIf { it > 0 }?.let { " · $it ${if (it == 1) "cycle" else "cycles"}" }.orEmpty()
                        "${phase.formattedPrice} / ${PhoneBillingPolicy.period(phase.billingPeriod)}$cycles${if (phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING) " · auto-renews" else ""}"
                    }
                    display += PhoneBillingOffer(key, product.productId, product.name, product.description, terms, subscription = true)
                } else product.oneTimePurchaseOfferDetailsList.orEmpty().forEachIndexed { index, offer ->
                    val key = "${product.productId}:$index"; found[key] = product to offer.offerToken
                    display += PhoneBillingOffer(key, product.productId, product.name, product.description, listOf("${offer.formattedPrice} · one-time purchase"), subscription = false)
                }
            }
        }
        offers.clear(); offers.putAll(found); productsAt = System.currentTimeMillis()
        mutable.update { it.copy(offers = display) }
    }

    private suspend fun queryPurchases(billing: BillingClient, type: String): List<Purchase> = withTimeout(12_000) { suspendCancellableCoroutine { continuation ->
        val params = QueryPurchasesParams.newBuilder().setProductType(type).apply { if (type == BillingClient.ProductType.SUBS) includeSuspendedSubscriptions(true) }.build()
        billing.queryPurchasesAsync(params) { result, purchases -> if (continuation.isActive) {
            if (result.responseCode == BillingClient.BillingResponseCode.OK) continuation.resume(purchases)
            else continuation.resumeWithException(BillingFailure(result.responseCode))
        } }
    } }

    fun close() { refreshJob?.cancel(); expiryJob?.cancel(); client?.endConnection(); client = null; offers.clear() }
    private class BillingFailure(val code: Int) : Exception()
    private fun billingNotice(code: Int) = when (code) {
        BillingClient.BillingResponseCode.USER_CANCELED -> "Purchase cancelled."
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "Google Play purchases aren't available on this device or account."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "This product is not available. Refresh the current Google Play offers."
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "This item is already owned. Restore purchases to refresh access."
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "Purchases are not configured for this app build."
        else -> "Google Play is temporarily unavailable. Try again shortly."
    }
}
