package com.example.xiangsugame.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.example.xiangsugame.R
import com.example.xiangsugame.data.LocalSettings

/**
 * 背景音乐管理器:循环播放 `res/raw/bgm.mp3`(进程内单例 MediaPlayer)。
 *
 * 为什么不是前台 Service:
 *   本项目的 BGM 只在游戏处于前台时播放,切到后台立刻暂停(见 [pause]),
 *   不需要"锁屏/后台继续放"的能力,所以进程内单例最省事 —— 不涉及通知、
 *   前台服务权限与 Android 14+ 的前台服务类型声明。
 *
 * 生命周期约定(MainActivity):
 * ```
 * onStart  -> BackgroundMusicManager.start(this)   // 允许播放则开始/续播
 * onStop   -> BackgroundMusicManager.pause()       // 退到后台暂停,不释放
 * ```
 * 设置页切换开关时调用 [onEnabledChanged] 即时生效。
 *
 * 所有播放调用都做了 try/catch:音频资源解码失败/设备占用只会静默降级,
 * 绝不让"放不出音乐"变成崩溃或影响正常游戏。
 *
 * 素材:爱给网(aigei.com)免费素材 `Event_BGM_MiniGamePar`,见 README「素材来源」。
 */
object BackgroundMusicManager {

    private const val TAG = "BackgroundMusic"
    private const val VOLUME = 0.4f

    private var player: MediaPlayer? = null
    private var startFailed = false

    /** 开始(或在暂停后继续)播放;开关关闭时什么都不做。 */
    fun start(context: Context) {
        if (!LocalSettings.backgroundMusicEnabled) return
        val app = context.applicationContext
        val current = player
        if (current != null) {
            // 已准备好但处于暂停状态(从后台回来)→ 续播
            runCatching { if (!current.isPlaying) current.start() }
                .onFailure { Log.w(TAG, "背景音乐续播失败", it) }
            return
        }
        if (startFailed) return  // 之前创建失败过,不反复重试刷日志
        val created = runCatching { createPlayer(app) }
            .onFailure {
                startFailed = true
                Log.w(TAG, "背景音乐初始化失败,已静默跳过", it)
            }
            .getOrNull()
        if (created == null) {
            startFailed = true
            Log.w(TAG, "背景音乐创建失败(bgm.mp3 缺失或解码失败),本次运行不再重试")
        }
        player = created
    }

    private fun createPlayer(context: Context): MediaPlayer? {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        // 用带 AudioAttributes 的 create 重载:MediaPlayer 已 prepare 之后再调
        // setAudioAttributes 在某些状态会抛 IllegalStateException。
        return MediaPlayer.create(
            context, R.raw.bgm, attrs, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )?.apply {
            isLooping = true
            setVolume(VOLUME, VOLUME)
            start()
        }
    }

    /** 退到后台:暂停但保留播放进度,回来时接着放。 */
    fun pause() {
        val current = player ?: return
        runCatching { if (current.isPlaying) current.pause() }
            .onFailure { Log.w(TAG, "背景音乐暂停失败", it) }
    }

    /** 设置页切换开关:开则立即播放,关则立即暂停。 */
    fun onEnabledChanged(context: Context, enabled: Boolean) {
        if (enabled) start(context) else pause()
    }

    /** 释放播放器(目前只在进程退出时由系统回收,保留此接口便于测试/调试)。 */
    fun release() {
        runCatching { player?.release() }
        player = null
        startFailed = false
    }
}
