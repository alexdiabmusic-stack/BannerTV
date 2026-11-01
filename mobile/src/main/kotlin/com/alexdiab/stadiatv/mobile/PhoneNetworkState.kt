package com.alexdiab.stadiatv.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class PhoneConnection(val known: Boolean = false, val online: Boolean = false, val generation: Long = 0)

internal class PhoneConnectionRecovery {
    private var previous: PhoneConnection? = null
    fun accept(value: PhoneConnection): Boolean {
        val restored = value.known && value.online && value != previous
        previous = value
        return restored
    }
}

/** Callback arguments are authoritative; an obsolete network's loss cannot invalidate its replacement. */
internal class PhoneDefaultNetwork<T> {
    private var current: T? = null
    var state = PhoneConnection(); private set
    fun available(network: T): PhoneConnection {
        if (current != network) { current = network; state = state.copy(known = true, online = false) }
        return state
    }
    fun capabilities(network: T, internet: Boolean, validated: Boolean): PhoneConnection {
        if (network != current) return state
        val online = internet && validated
        state = PhoneConnection(true, online, state.generation + if (online && !state.online) 1 else 0)
        return state
    }
    fun lost(network: T): PhoneConnection {
        if (current == network) { current = null; state = state.copy(known = true, online = false) }
        return state
    }
    fun none(): PhoneConnection { current = null; state = state.copy(known = true, online = false); return state }
}

/** Collected only while phone UI is foregrounded; no sockets, SSIDs or addresses are retained/logged. */
internal fun phoneConnection(context: Context) = callbackFlow {
    val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    if (manager == null) { close(); return@callbackFlow }
    val tracker = PhoneDefaultNetwork<Network>()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(tracker.available(network)) }
        override fun onLost(network: Network) { trySend(tracker.lost(network)) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trySend(tracker.capabilities(network, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)))
        }
    }
    try { manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper())) }
    catch (_: RuntimeException) { close(); return@callbackFlow }
    // Outside callbacks, a single initial snapshot allows an already-offline cold screen to avoid a polling loop.
    val active = manager.activeNetwork
    if (active == null) trySend(tracker.none()) else {
        tracker.available(active)
        val capabilities = manager.getNetworkCapabilities(active)
        trySend(tracker.capabilities(active, capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true))
    }
    awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
}.distinctUntilChanged()
