package com.example.xiangsugame.model

/**
 * 棋盘状态容器 + 撤销/重做指令栈（概要设计 3.2.2 节"交互处理子模块"）。
 *
 * 职责：
 *  - 保存当前棋盘上每个格子的三态状态（以 Int 存储，见 [CellState]）；
 *  - 提供对单个格子的状态修改入口（点击循环切换 / 直接设置）；
 *  - 用两条指令栈（undoStack / redoStack）实现标准撤销/重做语义。
 *
 * 数据布局：board[r][c] 存储第 r 行第 c 列格子的 [CellState.value]。
 * 用 IntArray 而非 Array<CellState> 是考虑到棋盘要频繁读写，
 * 原始数组更省内存、访问更快；需要语义化读取时再用 CellState.fromValue 转换。
 *
 * 撤销/重做语义：
 *  每次修改记录为一个 Move（改了哪格、改前值、改后值），压入撤销栈；
 *  撤销时弹出栈顶 Move 并回退到改前值，同时把该 Move 压入重做栈；
 *  产生任何"新操作"时清空重做栈 —— 这是标准做法，因为历史被新操作分叉后，
 *  旧的重做分支就失效了。
 */
class GameBoard(val rows: Int, val cols: Int) {

    /** 当前棋盘状态。board[r][c] 取值见 [CellState]（0 未确定 / 1 涂黑 / 2 标记空白）。 */
    val board = Array(rows) { IntArray(cols) { CellState.UNDECIDED.value } }

    /** 一次状态修改的记录，供撤销/重做回放使用。 */
    data class Move(val row: Int, val col: Int, val before: Int, val after: Int)

    private val undoStack = ArrayDeque<Move>()
    private val redoStack = ArrayDeque<Move>()

    /** 撤销栈非空 → 允许撤销。 */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** 重做栈非空 → 允许重做。 */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * 点击循环切换指定格：空白 → 涂黑 → 标记空白 → 空白（见 [CellState.next]）。
     *
     * 这是棋盘唯一的"玩家点击"入口。
     *
     * @return 是否真的产生了状态变化（三态循环永远会变，除非… 循环本身保证变化，
     *         因此主要用于区分是否需要触发 UI 刷新）
     */
    fun cycleCell(row: Int, col: Int): Boolean {
        val before = board[row][col]
        val after = CellState.fromValue(before).next().value
        if (after == before) return false
        board[row][col] = after
        // 记录改动并压栈；新操作会切断重做历史
        undoStack.addLast(Move(row, col, before, after))
        redoStack.clear()
        return true
    }

    /** 直接设置某格状态（供"标记空白"长按操作、提示功能、重置等使用）。 */
    fun setCell(row: Int, col: Int, state: CellState) {
        val before = board[row][col]
        if (before == state.value) return // 目标状态与当前一致，视为无操作
        board[row][col] = state.value
        undoStack.addLast(Move(row, col, before, state.value))
        redoStack.clear()
    }

    /** 撤销一步：回退最近一次修改，并把它转入重做栈。返回是否成功撤销。 */
    fun undo(): Boolean {
        val move = undoStack.removeLastOrNull() ?: return false
        board[move.row][move.col] = move.before
        redoStack.addLast(move)
        return true
    }

    /** 重做一步：重放最近一次被撤销的修改，并把它转回撤销栈。返回是否成功重做。 */
    fun redo(): Boolean {
        val move = redoStack.removeLastOrNull() ?: return false
        board[move.row][move.col] = move.after
        undoStack.addLast(move)
        return true
    }

    /** 清空棋盘全部格子并清除撤销/重做历史（"重置"功能调用）。 */
    fun reset() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                board[r][c] = CellState.UNDECIDED.value
            }
        }
        undoStack.clear()
        redoStack.clear()
    }
}
