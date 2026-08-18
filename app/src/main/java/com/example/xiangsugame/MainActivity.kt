package com.example.xiangsugame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.Levels
import com.example.xiangsugame.ui.GameScreen
import com.example.xiangsugame.ui.HomeScreen
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
                XiangsuGameApp()
            }
        }
    }
}

/**
 * 应用内简单页面导航（暂用状态切换，后续可替换为 Navigation 组件）。
 *
 * 用 Compose 状态 screen 记录当前页面：
 *  - Screen.Home  → 首页（HomeScreen），关卡网格 + 进度；
 *  - Screen.Game  → 游戏页（GameScreen），携带要玩的关卡数据。
 * 进度（GameProgress）在应用生命周期内单例化，通关后持久化到 SharedPreferences，
 * 返回首页时关卡网格自动反映最新解锁/通关状态。
 */
@Composable
fun XiangsuGameApp() {
    val context = LocalContext.current
    // 关卡进度：应用生命周期内保持同一实例；内部是 Compose 可观察状态
    val progress = remember { GameProgress(context.applicationContext) }
    val levels = remember { Levels.all }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    when (val s = screen) {
        Screen.Home -> HomeScreen(
            levels = levels,
            progress = progress,
            onPlayLevel = { screen = Screen.Game(it) },
        )
        is Screen.Game -> {
            // 计算"下一关"：本关在关卡列表中之后还有一关即可跳转（顺序解锁）
            val index = levels.indexOfFirst { it.id == s.level.id }
            val nextLevel = if (index in 0 until levels.lastIndex) levels[index + 1] else null
            GameScreen(
                level = s.level,
                progress = progress,
                onBack = { screen = Screen.Home },
                onNextLevel = nextLevel?.let { next -> { screen = Screen.Game(next) } },
            )
        }
    }
}

/** 页面类型：Home 为首页；Game 携带关卡数据进入游戏页。 */
private sealed interface Screen {
    data object Home : Screen
    data class Game(val level: Level) : Screen
}
