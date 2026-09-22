package com.example.xiangsugame.ui

import com.example.xiangsugame.Screen
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.Levels

/**
 * 页面导航的**纯逻辑**(对应鸿蒙 harmony/ets/ui/ScreenStack.ets)。
 *
 * 为什么单独抽出来:两端都踩过"点「下一关」没反应"的坑 —— 根因不在渲染,而在导航:
 * 鸿蒙端曾经用"替换栈顶"切关,栈长度不变 → UI 框架复用同一个 GameScreen 实例 →
 * 新关卡的棋盘根本没重建,看起来就是按钮坏了。这类问题以前只能靠真机手点发现,
 * 抽成纯函数后就能被单测覆盖:
 *   · 下一关必须给出**另一关**(而不是同一关或 null);
 *   · 已是最后一关 / 在线关时返回 null,UI 据此隐藏按钮,不留死键;
 *   · 关卡表不断链(每一关都能查到、能前进)。
 *
 * 声明为 internal:它签名里用到 internal 的 Screen 类型。
 */
internal object ScreenStack {

    /**
     * 计算"下一关"的目标页面;null 表示没有下一关
     * (已是最后一关,或当前是在线关 id < 0 —— 在线关没有顺序下一关)。
     */
    fun nextLevelScreen(current: Screen): Screen? {
        val game = current as? Screen.Game ?: return null
        val level = game.level
        if (level.id <= 0) return null
        val all = Levels.all
        for (i in all.indices) {
            if (all[i].id == level.id) {
                // 保持当前模式(自由/限时)
                return if (i + 1 < all.size) Screen.Game(all[i + 1], game.mode) else null
            }
        }
        return null
    }

    /** 当前关是否还有下一关(UI 用它决定按钮显不显示)。 */
    fun hasNextLevel(current: Screen): Boolean = nextLevelScreen(current) != null

    /** 按 id 查内置关(重玩/跳关用)。 */
    fun levelById(id: Int): Level? = Levels.byId(id)

    /** 供测试/调用方构造 GAME 页面。 */
    fun gameScreen(level: Level, mode: GameMode = GameMode.FREE): Screen = Screen.Game(level, mode)
}
