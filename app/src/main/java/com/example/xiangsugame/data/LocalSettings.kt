package com.example.xiangsugame.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 设备本地设置(与账号无关):服务器地址 + 关卡全解锁开关。
 * Compose 可观察,保存后立即生效;服务器地址变更后 ApiClient 会重建实例。
 */
object LocalSettings {

    /** 演示默认:Android 模拟器访问宿主机的固定地址;真机需在设置页改成局域网 IP。 */
    const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000/"

    private lateinit var prefs: android.content.SharedPreferences

    var serverUrl by mutableStateOf(DEFAULT_SERVER_URL)
        private set

    /** 展示模式:一键放开全部内置关(答辩/演示用,本机开关)。 */
    var demoAllUnlocked by mutableStateOf(false)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("xiangsu_settings", Context.MODE_PRIVATE)
        serverUrl = prefs.getString(KEY_SERVER, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        demoAllUnlocked = prefs.getBoolean(KEY_DEMO_UNLOCK, false)
    }

    fun saveServerUrl(url: String) {
        val normalized = url.trim().let {
            when {
                it.isBlank() -> DEFAULT_SERVER_URL
                !it.endsWith("/") -> "$it/"
                else -> it
            }
        }
        if (normalized == serverUrl) return
        serverUrl = normalized
        prefs.edit().putString(KEY_SERVER, normalized).apply()
    }

    fun saveDemoAllUnlocked(enabled: Boolean) {
        if (enabled == demoAllUnlocked) return
        demoAllUnlocked = enabled
        prefs.edit().putBoolean(KEY_DEMO_UNLOCK, enabled).apply()
    }

    private const val KEY_SERVER = "server_url"
    private const val KEY_DEMO_UNLOCK = "demo_all_unlocked"
}
