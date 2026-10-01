package com.ma.sms.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observe l'etat de connectivite reseau (avec acces Internet valide, pas juste "associe a un
 * reseau") en continu, pour piloter la banniere "hors ligne" et le declenchement de synchronisation
 * (cf. SmsApplication / SyncScheduler). L'etat initial est calcule de maniere synchrone a la
 * construction pour eviter un flash "en ligne" incorrect avant le premier callback.
 */
class ConnectivityObserver(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(currentlyConnected())
    val isConnected: StateFlow<Boolean> = _isConnected

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isConnected.value = currentlyConnected()
        }

        override fun onLost(network: Network) {
            _isConnected.value = currentlyConnected()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _isConnected.value = isUsable(networkCapabilities)
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    private fun currentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return isUsable(capabilities)
    }

    private fun isUsable(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
