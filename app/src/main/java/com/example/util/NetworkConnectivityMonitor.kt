package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ConnectivityObserver {
  val isOnline: StateFlow<Boolean>
  fun checkOnlineNow(): Boolean
  fun release() {}
}

class NetworkConnectivityMonitor(context: Context) : ConnectivityObserver {
  private val connectivityManager =
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

  private val _isOnline = MutableStateFlow(readCurrentConnectivity())
  override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      _isOnline.value = readCurrentConnectivity()
    }

    override fun onLost(network: Network) {
      _isOnline.value = readCurrentConnectivity()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
      _isOnline.value = hasUsableInternet(networkCapabilities) || readCurrentConnectivity()
    }
  }

  init {
    runCatching {
      val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
      connectivityManager?.registerNetworkCallback(request, networkCallback)
    }
  }

  override fun checkOnlineNow(): Boolean {
    val online = readCurrentConnectivity()
    _isOnline.value = online
    return online
  }

  override fun release() {
    runCatching {
      connectivityManager?.unregisterNetworkCallback(networkCallback)
    }
  }

  private fun readCurrentConnectivity(): Boolean {
    val cm = connectivityManager ?: return false
    return runCatching {
      val activeNetwork = cm.activeNetwork ?: return false
      val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
      hasUsableInternet(caps)
    }.getOrDefault(false)
  }

  private fun hasUsableInternet(caps: NetworkCapabilities): Boolean {
    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
      caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    return hasInternet && (validated || hasTransport)
  }
}
