package com.example.xiangsugame.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 网络状态监控 —— 结合 BroadcastReceiver(兼容旧版)与 NetworkCallback(Android 7+)。
 *
 * 监听网络连接/断开,状态存 [isConnected] (Compose 可观察)。
 * 在 [init] 时注册,在 [release] 时注销;断网时 UI 可据此弹出提示条。
 */
object NetworkMonitor {

    var isConnected by mutableStateOf(true)
        private set

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var receiverRegistered = false

    /** 广播接收器:接收系统网络变化广播(作为 NetworkCallback 的补充)。 */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                refresh(context)
            }
        }
    }

    fun init(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        refresh(context)

        // Android 7+ 用 NetworkCallback 监听(更可靠)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { isConnected = true }
                override fun onLost(network: Network) { isConnected = false }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        } else {
            // 旧版本用广播
            context.registerReceiver(receiver, android.content.IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            receiverRegistered = true
        }
    }

    fun release(context: Context) {
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        networkCallback = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        connectivityManager = null
    }

    /** 主动刷新一次网络状态。 */
    private fun refresh(context: Context?) {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        isConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected ?: false
        }
    }
}
