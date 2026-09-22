package com.example.xiangsugame

import android.content.Context
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.data.AccountStore
import com.example.xiangsugame.data.LocalSettings
import com.example.xiangsugame.data.PuzzleRepository
import com.example.xiangsugame.receiver.NetworkMonitor
import com.example.xiangsugame.service.SoundEffectManager

/**
 * 轻量依赖容器:进程级单例初始化 + 账号级 Store 注册表。
 * MainActivity.onCreate 时 init 一次;各 UI 页经此取依赖。
 */
object AppGraph {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        LocalSettings.init(appContext!!)
        AuthManager.init(appContext!!)
        ApiClient.init(appContext!!)
        PuzzleRepository.init(appContext!!)
        SoundEffectManager.init(appContext!!) // UI 音效(点击/通关/错误)
        // 网络状态是进程级关注点:在这里注册一次,不随 Activity 生命周期注销
        // (绑在 onDestroy 上时,旋转/多窗口重建会出现"旧实例注销、新实例没注册"的时序问题)
        NetworkMonitor.init(appContext!!)
    }

    private val accounts = HashMap<Int, AccountStore>()

    /** 当前登录账号的进度 Store;切换账号互不干扰。 */
    fun account(userId: Int): AccountStore =
        accounts.getOrPut(userId) { AccountStore(requireNotNull(appContext), userId) }
}
