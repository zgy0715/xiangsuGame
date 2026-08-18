package com.example.xiangsugame.model

/**
 * 方格三态（概要设计 3.4 节）。
 *
 * 每个格子在解谜过程中有三种状态，玩家通过点击循环切换：
 *  0 = 未确定（UNDECIDED）—— 初始空白，尚未推理；
 *  1 = 已涂黑（FILLED）—— 玩家判断这里应该是像素画的一部分；
 *  2 = 标记为空白（MARKED_EMPTY）—— 玩家确定这里不涂黑（相当于"打叉"）。
 *
 * 第三个状态很关键：Fill-a-Pix 中判断"这里不该涂"同样重要，
 * 把它显式标记出来能帮助玩家避免重复纠结，也便于后续的推理辅助功能使用。
 *
 * 状态以 Int 存储（value），这样棋盘可以用 IntArray 紧凑存储、快速读写；
 * 这里提供 value ↔ 枚举的双向转换（fromValue / value）。
 */
enum class CellState(val value: Int) {
    UNDECIDED(0),
    FILLED(1),
    MARKED_EMPTY(2);

    /** 点击循环切换：空白 → 涂黑 → 标记空白 → 空白。 */
    fun next(): CellState = when (this) {
        UNDECIDED -> FILLED
        FILLED -> MARKED_EMPTY
        MARKED_EMPTY -> UNDECIDED
    }

    companion object {
        /** 由 Int 值还原枚举；未识别值一律归为 UNDECIDED（宽容处理脏数据）。 */
        fun fromValue(value: Int): CellState = when (value) {
            1 -> FILLED
            2 -> MARKED_EMPTY
            else -> UNDECIDED
        }
    }
}
