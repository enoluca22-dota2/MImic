package com.example.mimic.downloader.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Connectivity state used for the offline check and the "Wi-Fi only" download setting. */
class NetworkMonitor(context: Context) {

    enum class State { OFFLINE, METERED, UNMETERED }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun currentState(): State = stateOf(connectivity?.getNetworkCapabilities(connectivity.activeNetwork))

    fun isOnline(): Boolean = currentState() != State.OFFLINE

    /** Emits the current state immediately and again whenever the default network changes. */
    val state: Flow<State> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(State.UNMETERED)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(stateOf(caps))
            }

            override fun onLost(network: Network) {
                trySend(currentState())
            }
        }
        trySend(currentState())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun stateOf(caps: NetworkCapabilities?): State = when {
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> State.OFFLINE
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> State.UNMETERED
        else -> State.METERED
    }
}
