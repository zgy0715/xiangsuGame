package com.example.xiangsugame.ui

import com.example.xiangsugame.Screen
import com.example.xiangsugame.model.Difficulty
import com.example.xiangsugame.model.GameMode
import com.example.xiangsugame.model.Level
import com.example.xiangsugame.model.Levels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI 导航冒烟测试(对应鸿蒙 harmony/test/ScreenStack.test.ets)。
 *
 * 存在的理由:真机上踩过的 UI 问题(**点「下一关」没反应**、最后一关留死按钮)
 * 根因都在导航逻辑里,而不是渲染。把导航抽成 ScreenStack 后,
 * 这类"只能靠手点发现"的问题变成了可自动跑的断言。
 */
class ScreenStackTest {

    /** 1) 下一关必须真的换关 —— 不能还是同一关(那正是"点了没反应"的表现)。 */
    @Test
    fun nextLevelAdvancesToADifferentLevel() {
        val first = Levels.all.first()
        val current = ScreenStack.gameScreen(first)
        val next = ScreenStack.nextLevelScreen(current)
        assertNotNull("第一关必须有下一关", next)
        next as Screen.Game
        assertEquals("下一关必须换关卡", first.id + 1, next.level.id)
    }

    /** 2) 最后一关:不给死按钮(返回 null,UI 隐藏按钮)。 */
    @Test
    fun lastLevelHasNoNext() {
        val last = Levels.all.last()
        val current = ScreenStack.gameScreen(last)
        assertFalse(ScreenStack.hasNextLevel(current))
        assertNull(ScreenStack.nextLevelScreen(current))
    }

    /** 3) 在线关(id < 0)没有顺序下一关。 */
    @Test
    fun onlineLevelHasNoSequentialNext() {
        val online = Level(
            id = -34,
            name = "今日一题",
            difficulty = Difficulty.EASY,
            rows = 1,
            cols = 1,
            clueGrid = arrayOf(intArrayOf(0)),
            answerGrid = arrayOf(intArrayOf(0)),
            timedLimitSeconds = 0,
        )
        val current = ScreenStack.gameScreen(online)
        assertFalse(ScreenStack.hasNextLevel(current))
        assertNull(ScreenStack.nextLevelScreen(current))
    }

    /** 4) 模式(自由/限时)要跟着带到下一关。 */
    @Test
    fun modeIsCarriedToNextLevel() {
        val current = ScreenStack.gameScreen(Levels.all.first(), GameMode.TIMED)
        val next = ScreenStack.nextLevelScreen(current)
        assertNotNull(next)
        assertEquals(GameMode.TIMED, (next as Screen.Game).mode)
    }

    /** 5) 关卡表不断链:除最后一关外都能前进,且目标就是下一关。 */
    @Test
    fun everyBuiltinLevelButLastCanAdvance() {
        val all = Levels.all
        all.forEachIndexed { i, level ->
            val current = ScreenStack.gameScreen(level)
            if (i + 1 < all.size) {
                val next = ScreenStack.nextLevelScreen(current)
                assertNotNull("第 ${level.id} 关应有下一关", next)
                assertEquals(all[i + 1].id, (next as Screen.Game).level.id)
            } else {
                assertNull(ScreenStack.nextLevelScreen(current))
            }
        }
    }

    /** 6) 按 id 查关:每关都能找到(重玩/跳关依赖它)。 */
    @Test
    fun levelByIdFindsEveryBuiltinLevel() {
        Levels.all.forEach { level ->
            val found = ScreenStack.levelById(level.id)
            assertNotNull("应能按 id ${level.id} 找到关卡", found)
            assertEquals(level.id, found!!.id)
        }
        assertNull(ScreenStack.levelById(9999))
    }

    /** 7) 关卡 id 连续:否则"下一关"会跳空,导航链断裂。 */
    @Test
    fun builtinLevelIdsAreContiguous() {
        val ids = Levels.all.map { it.id }
        assertTrue("内置关 id 应连续递增", ids == (1..ids.size).toList())
    }
}
