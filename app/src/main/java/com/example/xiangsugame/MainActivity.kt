package com.example.xiangsugame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.Levels
import com.example.xiangsugame.model.UserRole
import com.example.xiangsugame.ui.GameScreen
import com.example.xiangsugame.ui.HomeScreen
import com.example.xiangsugame.ui.LoginScreen
import com.example.xiangsugame.ui.theme.XiangsuGameTheme

/**
 * 应用唯一入口 Activity。
 * 职责很简单：开启 edge-to-edge 沉浸式显示，把 Compose 内容挂到窗口上，
 * 并包上一层应用主题（XiangsuGameTheme）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XiangsuGameTheme {
                // 用带主题背景色的 Surface 包住整棵 Compose 树，
                // edge-to-edge 下内容区域也始终是游戏底色，不会有系统默认白底漏出
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
 * 应用内简单页面导航（暂用状态切换，后续可替换为 Navigation 组件）。
 *
 * 流程：登录页（选身份）→ 首页（关卡网格 + 进度 + 模式选择）→ 游戏页（带关卡与模式）。
 *  - Screen.Login  → 登录页，选定身份后进入首页；
 *  - Screen.Home   → 首页（HomeScreen），关卡网格 + 进度 + 身份徽章 + 模式选择；
 *  - Screen.Game   → 游戏页（GameScreen），携带要玩的关卡与所选模式。
 * 进度（GameProgress）在应用生命周期内单例化，通关后持久化到 SharedPreferences，
 * 返回首页时关卡网格自动反映最新解锁/通关状态。
 */
@Composable
fun XiangsuGameApp() {
    val context = LocalContext.current
    // 关卡进度：应用生命周期内保持同一实例；内部是 Compose 可观察状态
    val progress = remember { GameProgress(context.applicationContext) }
    val levels = remember { Levels.all }
    // 当前登录身份：未登录前恒为 null（登录页），登录后传给首页/游戏页
    var role by remember { mutableStateOf<UserRole?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Login) }

    when (val s = screen) {
        Screen.Login -> LoginScreen(
            onLogin = {
                role = it
                screen = Screen.Home
            },
        )
        Screen.Home -> HomeScreen(
            levels = levels,
            progress = progress,
            role = role ?: UserRole.PLAYER,
            onPlayLevel = { level, mode -> screen = Screen.Game(level, mode) },
            onLogout = { screen = Screen.Login },
        )
        is Screen.Game -> {
            // 计算"下一关"：本关在关卡列表中之后还有一关即可跳转（顺序解锁）
            val index = levels.indexOfFirst { it.id == s.level.id }
            val nextLevel = if (index in 0 until levels.lastIndex) levels[index + 1] else null
            GameScreen(
                level = s.level,
                progress = progress,
                mode = s.mode,
                role = role ?: UserRole.PLAYER,
                onBack = { screen = Screen.Home },
                // 下一关沿用当前选择的模式（限时挑战不中断）
                onNextLevel = nextLevel?.let { next -> { screen = Screen.Game(next, s.mode) } },
            )
        }
    }
}

/** 页面类型：Login 为登录页；Home 为首页；Game 携带关卡与模式进入游戏页。 */
private sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    data class Game(val level: Level, val mode: GameMode) : Screen
}
