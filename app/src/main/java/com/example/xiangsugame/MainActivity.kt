package com.example.xiangsugame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.xiangsugame.api.ApiClient
import com.example.xiangsugame.auth.AuthManager
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.Levels
import com.example.xiangsugame.ui.BattleHomeScreen
import com.example.xiangsugame.ui.GameScreen
import com.example.xiangsugame.ui.HallScreen
import com.example.xiangsugame.ui.HomeScreen
import com.example.xiangsugame.ui.LeaderboardScreen
import com.example.xiangsugame.ui.LoginScreen
import com.example.xiangsugame.ui.SettingsScreen
import com.example.xiangsugame.ui.theme.XiangsuGameTheme
import kotlinx.coroutines.launch

/** 应用唯一入口 Activity。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext) // 单例容器:设置/会话/网络/仓库
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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

    // —— 登录态驱动:登录成功 → 首页;登出 → 登录页 ——
    val logged = AuthManager.session != null
    LaunchedEffect(logged) {
        if (logged && current is Screen.Login) stack = listOf(Screen.Main)
        if (!logged && current !is Screen.Login) stack = listOf(Screen.Login)
    }
    // 会话被服务端判失效(401)时 AuthManager 由各调用点 logout(),同上处理
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

    when (val s = current) {
        Screen.Login -> LoginScreen()

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
                    onLogout = { AuthManager.logout() },
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
}

/** 页面类型(栈式)。 */
private sealed interface Screen {
    data object Login : Screen
    data object Main : Screen
    data object Hall : Screen
    data object Leaderboard : Screen
    data object BattleHome : Screen
    data object Settings : Screen
    data class Game(val level: Level, val mode: GameMode) : Screen
}
