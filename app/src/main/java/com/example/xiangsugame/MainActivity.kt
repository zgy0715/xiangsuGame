package com.example.xiangsugame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.data.LocalSettings
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.Levels
import com.example.xiangsugame.receiver.NetworkMonitor
import com.example.xiangsugame.service.BackgroundMusicManager
import com.example.xiangsugame.ui.BattleHomeScreen
import com.example.xiangsugame.ui.GameScreen
import com.example.xiangsugame.ui.HallScreen
import com.example.xiangsugame.ui.HomeScreen
import com.example.xiangsugame.ui.LeaderboardScreen
import com.example.xiangsugame.ui.LoginScreen
import com.example.xiangsugame.ui.NicknameDialog
import com.example.xiangsugame.ui.SettingsScreen
import com.example.xiangsugame.ui.theme.XiangsuGameTheme
import kotlinx.coroutines.launch

/** 应用唯一入口 Activity。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext) // 单例容器:设置/会话/网络/仓库/网络监听
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // 背景音乐走进程内单例(见 BackgroundMusicManager),在 onStart/onStop 里启停
        setContent {
            XiangsuGameTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    XiangsuGameApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 允许播放则开始/续播;开关关闭时内部直接返回
        BackgroundMusicManager.start(this)
    }

    override fun onStop() {
        // 退到后台暂停(保留进度),不在 onDestroy 释放,免得横竖屏重建把音乐掐断
        BackgroundMusicManager.pause()
        super.onStop()
    }
    // 不再在 onDestroy 注销网络监听:它是进程级单例,回调应与进程同生命周期,
    // 绑在 Activity 上反而会在重建时出现"旧实例注销、新实例没注册"的时序问题。
}

/**
 * 根导航 —— 简单栈式路由:
 * 未登录 → Login;登录后自动进入 Main(首页);各页 push / pop,系统返回键出栈;
 * 登出/401 由 session 置空驱动整体回登录页。
 */
@Composable
fun XiangsuGameApp() {
    val levels = remember { Levels.all }
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Login)) }
    val current = stack.last()
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    // —— 登录态驱动 ——
    // `entered` = 用户本次启动是否已明确进入(登录成功 / 游客进入 / 点「一键继续」)。
    // 启动固定停在登录页:本地有续登会话也**不**自动进游戏,否则一打开就是游戏界面,
    // 登录流程完全看不到(演示/答辩时尤其明显)。
    val logged = AuthManager.session != null
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(logged, entered) {
        when {
            !logged -> {
                entered = false
                if (current !is Screen.Login) stack = listOf(Screen.Login)
            }
            entered && current is Screen.Login -> stack = listOf(Screen.Main)
        }
    }
    // 401(令牌失效)由 ApiClient.safe 统一 logout() → logged 变 false → 自动回登录页
    BackHandler(enabled = stack.size > 1 && current !is Screen.Login) { pop() }

    val userId = AuthManager.session?.userId
    val account = if (userId != null) AppGraph.account(userId) else null
    val scope = rememberCoroutineScope()

    // —— 账号初始化:每日登录奖励 + 孤儿通关清洗 ——
    LaunchedEffect(userId) {
        if (userId != null) {
            AppGraph.account(userId).grantDailyBonusIfNewDay()
            AppGraph.account(userId).pruneCompleted(levels.map { it.id }.toSet())
        }
    }

    // —— 首次登录(邮箱注册)后引导设置昵称:进首页弹一次,可跳过 ——
    var pendingNickname by remember { mutableStateOf(false) }
    LaunchedEffect(logged) {
        pendingNickname = logged && !AuthManager.isGuest && AuthManager.consumeNewUserFlag()
    }

    // 网络断网提示条(由 NetworkMonitor 驱动;可点击关闭,恢复网络后自动复位)
    val offline = !NetworkMonitor.isConnected
    var offlineDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(offline) { if (!offline) offlineDismissed = false }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = current) {
            Screen.Login -> LoginScreen(onEnter = { entered = true })

            Screen.Main -> {
                if (account != null) {
                    HomeScreen(
                        levels = levels,
                        account = account,
                        onPlayLevel = { level, mode -> push(Screen.Game(level, mode)) },
                        onPlayDaily = { level -> push(Screen.Game(level, GameMode.FREE)) },
                        onOpenHall = { push(Screen.Hall) },
                        onOpenLeaderboard = { push(Screen.Leaderboard) },
                        onOpenBattle = { push(Screen.BattleHome) },
                        onOpenSettings = { push(Screen.Settings) },
                        onLogout = { scope.launch { AuthManager.logoutAndRevoke() } },
                    )
                }
            }

            Screen.Hall -> HallScreen(
                onPlay = { level -> push(Screen.Game(level, GameMode.FREE)) },
                onBack = pop,
            )

            Screen.Leaderboard -> LeaderboardScreen(onBack = pop)

            Screen.BattleHome -> BattleHomeScreen(onBack = pop)

            Screen.Settings -> SettingsScreen(onBack = pop)

            is Screen.Game -> {
                if (account != null) {
                    // 下一关仅对内置顺序关卡有意义(在线关 id 为负,无顺序链)
                    val index = levels.indexOfFirst { it.id == s.level.id }
                    val nextLevel = if (index in 0 until levels.lastIndex) levels[index + 1] else null
                    val online = s.level.id < 0
                    GameScreen(
                        level = s.level,
                        account = account,
                        mode = s.mode,
                        onBack = pop,
                        onNextLevel = nextLevel?.let { next -> { push(Screen.Game(next, s.mode)) } },
                        // 在线单人通关 → 上传成绩到排行榜(失败静默,离线可缓存后再玩)
                        onSolved = if (online) { _, elapsed ->
                            scope.launch {
                                ApiClient.safe {
                                    ApiClient.api().submitRecord(
                                        com.example.xiangsugame.api.dto.SubmitRecordRequest(
                                            puzzleId = s.level.id,
                                            durationMs = elapsed * 1000,
                                            source = "single",
                                        ),
                                    )
                                }
                            }
                        } else null,
                    )
                }
            }
        }

        // 断网提示:顶部红条,可点击关闭;恢复网络后自动复位,下次断网再提醒
        if (offline && !offlineDismissed) {
            Surface(
                onClick = { offlineDismissed = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .systemBarsPadding(),
                color = Color(0xFFE53935),
                contentColor = Color.White,
            ) {
                Text(
                    "⚠ 网络已断开,在线功能暂不可用(内置关可离线玩)· 点击关闭",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp),
                )
            }
        }
    }

    // 首次登录(服务端 isNew)后引导设置昵称;跳过则保留服务端默认昵称
    if (pendingNickname) {
        NicknameDialog(
            current = AuthManager.session?.nickname.orEmpty(),
            title = "设置昵称",
            hint = "首次登录已自动注册账号,昵称会展示在排行榜与对战房间;现在跳过也可以,之后可在设置页修改",
            onDismiss = {
                pendingNickname = false
                AuthManager.clearNicknameSetup()
            },
        )
    }
}

/**
 * 页面类型(栈式)。
 *
 * 从 `private` 放宽到 `internal`:导航计算被抽到了 ui/ScreenStack.kt,
 * 它需要能构造/读取 Screen(这样"下一关"这类逻辑才能被单测覆盖)。
 */
internal sealed interface Screen {
    data object Login : Screen
    data object Main : Screen
    data object Hall : Screen
    data object Leaderboard : Screen
    data object BattleHome : Screen
    data object Settings : Screen
    data class Game(val level: Level, val mode: GameMode) : Screen
}
