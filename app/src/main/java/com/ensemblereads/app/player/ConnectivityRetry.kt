package com.ensemblereads.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * 网络恢复监听：断网期间合成 FAILED 的段，网络恢复后回调触发补合成。
 * 监听 NET_CAPABILITY_INTERNET + 校验通过的 onAvailable（断网→恢复切换时触发）。
 */
class ConnectivityRetry(
    private val context: Context,
    private val onNetworkAvailable: () -> Unit,
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                onNetworkAvailable()
            }
        }
    }

    fun register() {
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }
    }

    fun unregister() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
