package com.example.xiangsugame.auth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.api.dto.LoginRequest
import com.example.xiangsugame.api.dto.LoginResponse
import com.example.xiangsugame.api.dto.NicknameRequest
import com.example.xiangsugame.api.dto.SendCodeRequest
import com.example.xiangsugame.api.dto.SendCodeResponse

/**
 * 会话管理:登录真实账号走「邮箱 + 6 位验证码」(服务端校验并签发 token);
 * 连不上服务器时可用游客身份直接进游戏。
 *
 * 与旧版(微信式 mock code)的关键区别:客户端 **不再生成/持久化任何 code**。
 * 真实验证码由服务端生成、单次使用、10 分钟过期;账号的稳定性来自服务端
 * users 表里那一行(邮箱唯一),客户端只持久化服务端下发的 token。
 * 会话持久化在 "xiangsu_auth" prefs,冷启动 restore() 静默续登。
 */
object AuthManager {

    data class Session(
        val userId: Int,
        val token: String,
        val nickname: String,
        /** 登录邮箱;游客会话为空串(离线身份)。 */
        val email: String = "",
    )

    /** 游客会话的本机固定 userId:负值,不会与服务端分配的真实 id 冲突。 */
    private const val GUEST_USER_ID = -99

    private lateinit var prefs: android.content.SharedPreferences

    /** 当前会话;null = 未登录(应用应展示登录页)。Compose 可观察。 */
    var session by mutableStateOf<Session?>(null)
        private set

    /** 上次登录用的邮箱(登录页预填,免去重复输入)。 */
    var lastEmail by mutableStateOf("")
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("xiangsu_auth", Context.MODE_PRIVATE)
        lastEmail = prefs.getString(KEY_LAST_EMAIL, "").orEmpty()
        restore()
    }

    val isLoggedIn: Boolean get() = session != null

    /** 是否为离线游客(无 token,只玩内置关,不联网)。 */
    val isGuest: Boolean get() = session?.userId == GUEST_USER_ID

    /**
     * 当前是否持有服务端令牌。
     *
     * 401 处理必须看这个:**游客本来就没有 token**,把它当成"令牌失效"去全局登出,
     * 会导致游客点一下对战/排行榜就被踢回登录页(两端都踩过这个坑)。
     */
    val hasToken: Boolean get() = !session?.token.isNullOrBlank()

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
            session = Session(userId, token, nickname ?: "玩家$userId", lastEmail)
        }
    }

    /**
     * 游客进入(免登录/离线):不请求服务器,直接以游客身份玩内置关卡。
     * 之后可在首页「切换账号」回到登录页登录真实账号。
     */
    fun enterGuest() {
        persist(GUEST_USER_ID, "", GUEST_NICKNAME)
    }

    // ---------------- 邮箱验证码登录 ----------------

    /**
     * 第一步:请求向邮箱发送 6 位验证码。
     * 失败文案由服务端下发(邮箱格式 / 发送太频繁 / 次数超限),可直接展示。
     */
    suspend fun requestEmailCode(email: String): Result<SendCodeResponse> = runCatching {
        LoginRules.validateEmail(email)?.let { error(it) }
        val normalized = LoginRules.normalizeEmail(email)
        val resp = ApiClient.safe { ApiClient.api().sendCode(SendCodeRequest(normalized)) }
            .getOrThrow()
        prefs.edit().putString(KEY_LAST_EMAIL, resp.email).apply()
        lastEmail = resp.email
        resp
    }

    /**
     * 第二步:提交验证码登录。成功后持久化会话,由根导航驱动进首页。
     * 返回 isNew:首次注册的账号需要引导设置昵称。
     */
    suspend fun loginWithEmailCode(email: String, code: String): Result<Boolean> = runCatching {
        LoginRules.validateEmail(email)?.let { error(it) }
        val normalized = LoginRules.normalizeEmail(email)
        val trimmedCode = code.trim()
        if (!LoginRules.canSubmit(trimmedCode)) error("请输入 6 位数字验证码")

        val resp: LoginResponse = ApiClient.safe {
            ApiClient.api().login(LoginRequest(email = normalized, code = trimmedCode))
        }.getOrThrow()
        prefs.edit().putString(KEY_LAST_EMAIL, normalized).apply()
        lastEmail = normalized
        persist(resp.userId, resp.token, resp.nickname)
        pendingNicknameSetup = resp.isNew
        resp.isNew
    }

    /** 客户端本地邮箱校验(委托 [LoginRules],供登录页即时提示使用)。 */
    fun validateEmail(email: String): String? = LoginRules.validateEmail(email)

    /** 上次登录是否为"新注册账号"(首次登录需要引导设置昵称),由导航层消费一次。 */
    var pendingNicknameSetup by mutableStateOf(false)
        private set

    /**
     * 取用"是否为新账号"标记(读后即清)。
     * 仅在账号确实新建时(服务端 isNew)置位,避免老用户每次登录都被弹窗打扰。
     */
    fun consumeNewUserFlag(): Boolean {
        if (!pendingNicknameSetup) return false
        pendingNicknameSetup = false
        return true
    }

    /** 跳过/完成首次昵称引导。 */
    fun clearNicknameSetup() {
        pendingNicknameSetup = false
    }

    /** 由登录页/设置页调用:首次登录引导设置昵称(服务端持久化)。 */
    suspend fun changeNickname(newName: String): Result<String> = runCatching {
        if (isGuest) error("游客为离线身份,登录后即可修改昵称")
        val resp = ApiClient.safe { ApiClient.api().updateNickname(NicknameRequest(newName)) }
            .getOrThrow()
        updateNickname(resp.nickname)
        resp.nickname
    }

    /** 修改昵称后的本地会话同步(服务端改昵称成功后调用)。 */
    fun updateNickname(nickname: String) {
        session?.let { session = it.copy(nickname = nickname) }
        prefs.edit().putString(KEY_NICKNAME, nickname).apply()
    }

    /** 401 等场景:清除会话并回登录页(由导航层监听 session 变化驱动)。 */
    fun logout() {
        session = null
        pendingNicknameSetup = false
        prefs.edit().remove(KEY_USER_ID).remove(KEY_TOKEN).remove(KEY_NICKNAME).apply()
    }

    /**
     * 用户主动「退出登录」:先请服务端作废这张令牌,再清本地会话。
     *
     * 与 [logout] 的分工很重要:
     *  - [logout] 只清本地 —— 401 自动登出走它(否则"401 → 请求注销 → 又 401"会递归);
     *  - 本方法走网络 —— 不然那张 30 天有效的令牌会一直留在服务端,谁拿到都还能用。
     * 服务端不可达时也照样清本地:退出登录不该被网络问题挡住。
     */
    suspend fun logoutAndRevoke(): Boolean {
        val token = session?.token
        val shouldRevoke = !isGuest && !token.isNullOrBlank()
        val revoked = if (shouldRevoke) {
            ApiClient.safe { ApiClient.api().logout() }.isSuccess
        } else {
            false
        }
        logout()
        return revoked
    }

    /**
     * 校验本地续登的会话是否仍然有效(点「继续」时调用)。
     *
     * 返回 true = 会话可用;false = 令牌已被服务端判失效(内部已登出)。
     * 网络不通时返回 true(离线容忍:局域网演示时服务器没开也不该卡住入口),
     * 真正的失效会在后续在线请求的 401 上由 [ApiClient.safe] 统一处理。
     */
    suspend fun verifyRestoredSession(): Boolean {
        val current = session ?: return false
        if (current.userId == GUEST_USER_ID) return true  // 游客是纯离线身份,无需校验
        val result = ApiClient.safe { ApiClient.api().me() }
        return result.fold(
            onSuccess = { true },
            onFailure = { it !is ApiClient.UnauthorizedException },
        )
    }

    private fun persist(userId: Int, token: String, nickname: String): Session {
        val s = Session(userId, token, nickname, if (userId == GUEST_USER_ID) "" else lastEmail)
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
    private const val KEY_LAST_EMAIL = "last_email"
}
