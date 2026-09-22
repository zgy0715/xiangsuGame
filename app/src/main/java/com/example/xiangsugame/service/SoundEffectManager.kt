package com.example.xiangsugame.service

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.example.xiangsugame.data.LocalSettings

/**
 * UI 音效管理器 —— 用 SoundPool 播放短促音效(点击/通关/错误)。
 *
 * 比 MediaPlayer 更低延迟,适合按钮点击即时反馈。
 * 开关是独立的 [LocalSettings.soundEffectsEnabled](背景音乐见 [BackgroundMusicManager])。
 *
 * 用法:
 * ```
 * SoundEffectManager.init(context)    // AppGraph.init 时
 * SoundEffectManager.click()          // 按钮点击时
 * SoundEffectManager.win()            // 通关时
 * SoundEffectManager.error()          // 检查出错时
 * ```
 */
object SoundEffectManager {

    private var soundPool: SoundPool? = null
    private var clickId = 0
    private var winId = 0
    private var errorId = 0
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()

        val res = context.resources
        clickId = res.getIdentifier("click", "raw", context.packageName)
            .let { if (it != 0) soundPool?.load(context, it, 1) ?: 0 else 0 }
        winId = res.getIdentifier("win", "raw", context.packageName)
            .let { if (it != 0) soundPool?.load(context, it, 1) ?: 0 else 0 }
        errorId = res.getIdentifier("error", "raw", context.packageName)
            .let { if (it != 0) soundPool?.load(context, it, 1) ?: 0 else 0 }
    }

    /** 按钮点击音效:短促清脆。 */
    fun click() {
        if (!LocalSettings.soundEffectsEnabled) return
        if (clickId != 0) soundPool?.play(clickId, 0.3f, 0.3f, 1, 0, 1f)
    }

    /** 通关音效:上升琶音。 */
    fun win() {
        if (!LocalSettings.soundEffectsEnabled) return
        if (winId != 0) soundPool?.play(winId, 0.5f, 0.5f, 2, 0, 1f)
    }

    /** 错误音效:低沉提示。 */
    fun error() {
        if (!LocalSettings.soundEffectsEnabled) return
        if (errorId != 0) soundPool?.play(errorId, 0.4f, 0.4f, 1, 0, 1f)
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        initialized = false
    }
}
