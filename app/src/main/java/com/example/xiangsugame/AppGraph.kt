package com.example.xiangsugame

import android.content.Context
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.data.AccountStore
import com.example.xiangsugame.data.LocalSettings
import com.example.xiangsugame.data.PuzzleRepository

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
    }

    private val accounts = HashMap<Int, AccountStore>()

    /** 当前登录账号的进度 Store;切换账号互不干扰。 */
    fun account(userId: Int): AccountStore =
        accounts.getOrPut(userId) { AccountStore(requireNotNull(appContext), userId) }
}
