package com.example.xiangsugame.auth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.api.dto.LoginRequest
import com.example.xiangsugame.api.dto.LoginResponse
import kotlin.random.Random

/**
 * 会话管理:登录真实账号走一键"微信授权登录"(本机 mock code,服务端按
 * code2session 同构流程返回 token);连不上服务器时可用游客身份直接进游戏。
 * 会话持久化在 "xiangsu_auth" prefs,冷启动 restore() 静默续登。
 */
object AuthManager {

    data class Session(val userId: Int, val token: String, val nickname: String)

    /** 游客会话的本机固定 userId:负值,不会与服务端分配的真实 id 冲突。 */
    private const val GUEST_USER_ID = -99

    private lateinit var prefs: android.content.SharedPreferences

    /** 当前会话;null = 未登录(应用应展示登录页)。Compose 可观察。 */
    var session by mutableStateOf<Session?>(null)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("xiangsu_auth", Context.MODE_PRIVATE)
        restore()
    }

    val isLoggedIn: Boolean get() = session != null

    /** 是否为离线游客(无 token,只玩内置关,不联网)。 */
    val isGuest: Boolean get() = session?.userId == GUEST_USER_ID

    /** 冷启动静默续登:读上次持久化的会话(真实账号或游客)。 */
    private fun restore() {
        val userId = prefs.getInt(KEY_USER_ID, -1)
        if (userId == GUEST_USER_ID) {
            session = Session(GUEST_USER_ID, "", GUEST_NICKNAME)
            return
        }
        val token = prefs.getString(KEY_TOKEN, null)
        val nickname = prefs.getString(KEY_NICKNAME, null)
        if (userId >= 0 && !token.isNullOrBlank()) {
            session = Session(userId, token, nickname ?: "玩家$userId")
        }
    }

    /**
     * 游客进入(免登录/离线):不请求服务器,直接以游客身份玩内置关卡。
     * 之后可在首页「切换账号」回到登录页登录真实账号。
     */
    fun enterGuest() {
        persist(GUEST_USER_ID, "", GUEST_NICKNAME)
    }

    /**
     * 一键微信授权登录:本机一次性生成随机 code 并持久化,之后每次登录复用
     * —— 服务端由 code 确定性派生 openid,同一设备永远回到同一账号。
     * 接真实微信时无需改这里 —— code 由微信 SDK 提供,登录链路不变。
     */
    suspend fun loginMockWechat(): Result<Session> = runCatching {
        val resp: LoginResponse = ApiClient.api().login(LoginRequest(code = deviceMockCode()))
        persist(resp.userId, resp.token, resp.nickname)
    }

    /** 设备级固定 mock code:首次生成后存 prefs,保证同设备账号稳定。 */
    private fun deviceMockCode(): String {
        prefs.getString(KEY_MOCK_CODE, null)?.let { return it }
        val code = Random.nextBytes(16).joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_MOCK_CODE, code).apply()
        return code
    }

    /** 修改昵称后的本地会话同步(服务端改昵称成功后调用)。 */
    fun updateNickname(nickname: String) {
        session?.let { session = it.copy(nickname = nickname) }
        prefs.edit().putString(KEY_NICKNAME, nickname).apply()
    }

    /** 401 等场景:清除会话并回登录页(由导航层监听 session 变化驱动)。 */
    fun logout() {
        session = null
        prefs.edit().remove(KEY_USER_ID).remove(KEY_TOKEN).remove(KEY_NICKNAME).apply()
    }

    private fun persist(userId: Int, token: String, nickname: String): Session {
        val s = Session(userId, token, nickname)
        session = s
        prefs.edit()
            .putInt(KEY_USER_ID, userId)
            .putString(KEY_TOKEN, token)
            .putString(KEY_NICKNAME, nickname)
            .apply()
        return s
    }

    private const val GUEST_NICKNAME = "游客"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_TOKEN = "token"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_MOCK_CODE = "mock_wx_code"
}
