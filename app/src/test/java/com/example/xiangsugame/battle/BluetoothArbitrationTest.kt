package com.example.xiangsugame.battle

import com.example.xiangsugame.model.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 蓝牙对称仲裁的纯逻辑:答案掩码校验 + 本地结算排序。 */
class BluetoothArbitrationTest {

    private fun level(): Level = Level.fromAnswer(
        id = 1, name = "红心", difficulty = com.example.xiangsugame.model.Difficulty.EASY,
        answer = arrayOf(
            intArrayOf(1, 1, 0),
            intArrayOf(1, 0, 1),
            intArrayOf(0, 1, 1),
        ),
    )

    @Test
    fun `correct mask passes validation regardless of marked-empty cells`() {
        val lv = level()
        // 2 表示"标记留白",不算涂黑 → 与答案一致应通过
        val grid = listOf(
            listOf(1, 1, 2),
            listOf(1, 0, 1),
            listOf(2, 1, 1),
        )
        assertTrue(BluetoothBattleTransport.validateGrid(grid, lv))
    }

    @Test
    fun `wrong mask fails validation`() {
        val lv = level()
        assertFalse(BluetoothBattleTransport.validateGrid(
            listOf(listOf(0, 1, 0), listOf(1, 0, 1), listOf(0, 1, 1)), lv))
        // 非法格子值
        assertFalse(BluetoothBattleTransport.validateGrid(
            listOf(listOf(9, 1, 0), listOf(1, 0, 1), listOf(0, 1, 1)), lv))
        // 尺寸不符
        assertFalse(BluetoothBattleTransport.validateGrid(
            listOf(listOf(1, 1), listOf(1, 0)), lv))
    }

    @Test
    fun `settle ranks by duration ascending with nicknames`() {
        val entries = BluetoothBattleTransport.buildResultEntries(
            meUid = 11, meNick = "小甲", meMs = 30_000L,
            peerUid = 22, peerNick = "小乙", peerMs = 12_000L,
        )
        assertEquals(2, entries.size)
        assertEquals(1, entries[0].rank)
        assertEquals(22, entries[0].userId)
        assertEquals("小乙", entries[0].nickname)
        assertEquals(2, entries[1].rank)
        assertEquals(11, entries[1].userId)
    }

    @Test
    fun `tie goes to earlier uid deterministically`() {
        val entries = BluetoothBattleTransport.buildResultEntries(5, "a", 999L, 3, "b", 999L)
        assertEquals(listOf(3, 5), entries.map { it.userId })
        assertEquals(listOf(1, 2), entries.map { it.rank })
    }
}
