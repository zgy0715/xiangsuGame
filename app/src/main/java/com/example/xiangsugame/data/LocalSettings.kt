package com.example.xiangsugame.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 设备本地设置(与账号无关):服务器地址 + 关卡全解锁开关 + 音效/背景音乐/摇一摇。
 * Compose 可观察,保存后立即生效;服务器地址变更后 ApiClient 会重建实例。
 */
object LocalSettings {

    /**
     * 演示默认:开发机的局域网 IP(当前为手机热点网段,雷电跑在宿主机上、真机连同一热点,两者都直连得到)。
     * 换网络/换热点后 IP 会变,需在设置页改成电脑的新 IP(`ipconfig` 查)。
     * 注:模拟器专用别名 `http://10.0.2.2:8000/` 仍可用作雷电的兜底地址。
     */
    const val DEFAULT_SERVER_URL = "http://192.168.43.50:8000/"

    private lateinit var prefs: android.content.SharedPreferences

    var serverUrl by mutableStateOf(DEFAULT_SERVER_URL)
        private set

    /** 展示模式:一键放开全部内置关(答辩/演示用,本机开关)。 */
    var demoAllUnlocked by mutableStateOf(false)
        private set

    /** 音效开关:点击 / 通关 / 检查出错的提示音(SoundPool)。 */
    var soundEffectsEnabled by mutableStateOf(true)
        private set

    /** 背景音乐开关:循环播放 res/raw/bgm.mp3(MediaPlayer)。 */
    var backgroundMusicEnabled by mutableStateOf(true)
        private set

    /** 摇一摇重置棋盘开关(加速度传感器)。 */
    var shakeToResetEnabled by mutableStateOf(true)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("xiangsu_settings", Context.MODE_PRIVATE)
        serverUrl = prefs.getString(KEY_SERVER, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        demoAllUnlocked = prefs.getBoolean(KEY_DEMO_UNLOCK, false)
        // 迁移:旧版本只有一个 musicEnabled 总开关(实际控制的是音效),把它续到音效开关上
        soundEffectsEnabled = prefs.getBoolean(
            KEY_SOUND, prefs.getBoolean(KEY_MUSIC_LEGACY, true))
        backgroundMusicEnabled = prefs.getBoolean(KEY_BGM, true)
        shakeToResetEnabled = prefs.getBoolean(KEY_SHAKE, true)
    }

    fun saveSoundEffectsEnabled(enabled: Boolean) {
        if (enabled == soundEffectsEnabled) return
        soundEffectsEnabled = enabled
        prefs.edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    fun saveBackgroundMusicEnabled(enabled: Boolean) {
        if (enabled == backgroundMusicEnabled) return
        backgroundMusicEnabled = enabled
        prefs.edit().putBoolean(KEY_BGM, enabled).apply()
    }

    fun saveShakeEnabled(enabled: Boolean) {
        if (enabled == shakeToResetEnabled) return
        shakeToResetEnabled = enabled
        prefs.edit().putBoolean(KEY_SHAKE, enabled).apply()
    }

    /**
     * 服务器地址归一化 —— **唯一入口**,保存与"测试连接"必须共用同一规则。
     *
     * 以前保存只补尾斜杠、不补协议,于是填 `192.168.1.5:8000` 时:
     * 测试连接(会补 http://)通过 → 保存 → OkHttp 拿到无协议 baseUrl 直接失败,
     * 用户看到的是"测试成功但全部联网失败"。规则:去空白 → 空串回默认 →
     * 缺协议补 http:// → 补尾斜杠。
     */
    fun normalizeServerUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return DEFAULT_SERVER_URL
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    fun saveServerUrl(url: String) {
        val normalized = normalizeServerUrl(url)
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
    private const val KEY_SOUND = "sound_effects_enabled"
    private const val KEY_BGM = "bgm_enabled"
    private const val KEY_MUSIC_LEGACY = "music_enabled"  // 旧版总开关(迁移用,不再写入)
    private const val KEY_SHAKE = "shake_to_reset"
}
