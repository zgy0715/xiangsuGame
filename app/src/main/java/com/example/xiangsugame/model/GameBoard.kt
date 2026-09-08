package com.example.xiangsugame.model

/**
 * 棋盘状态容器 + 撤销/重做指令栈（概要设计 3.2.2 节"交互处理子模块"）。
 *
 * 职责：
 *  - 保存当前棋盘上每个格子的三态状态（以 Int 存储，见 [CellState]）；
 *  - 提供"笔划涂刷"入口：一次按住拖动/单击 = 一条笔划（start → stroke ×N → commit），
 *    整条笔划作为一个可撤销步骤，适合工具笔连续填色；
 *  - 用两条指令栈（undoStack / redoStack）实现标准撤销/重做语义。
 *
 * 数据布局：board[r][c] 存储第 r 行第 c 列格子的 [CellState.value]。
 * 用 IntArray 而非 Array<CellState> 是考虑到棋盘要频繁读写，
 * 原始数组更省内存、访问更快；需要语义化读取时再用 CellState.fromValue 转换。
 *
 * 撤销/重做语义：
 *  每次修改最终都以一个 Move（单格或 Batch）压入撤销栈；
 *  撤销时弹出栈顶 Move 并回退到改前值，同时把该 Move 压入重做栈；
 *  产生任何"新操作"时清空重做栈 —— 历史被新操作分叉后旧分支失效（标准做法）。
 *  Move 分两种：Single 记录单个格子变化；Batch 记录一批格子同时变化
 *  （一条拖拽笔划 / 一键补齐整盘都压成一个 Batch，实现"一步撤销"）。
 */
class GameBoard(val rows: Int, val cols: Int) {

    /** 当前棋盘状态。board[r][c] 取值见 [CellState]（0 未确定 / 1 涂黑 / 2 标记空白）。 */
    val board = Array(rows) { IntArray(cols) { CellState.UNDECIDED.value } }

    /** 一次状态修改的记录，供撤销/重做回放使用。 */
    sealed interface Move {
        /** 单个格子的状态变化。 */
        data class Single(val row: Int, val col: Int, val before: Int, val after: Int) : Move

        /** 一批格子同时变化（一条笔划 / 一键补齐），整批作为一个可撤销步骤。 */
        data class Batch(val moves: List<Single>) : Move
    }

    private val undoStack = ArrayDeque<Move>()
    private val redoStack = ArrayDeque<Move>()

    /** 撤销栈非空 → 允许撤销。 */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** 重做栈非空 → 允许重做。 */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // ———— 笔划涂刷（工具笔拖动连涂的核心）————

    private var strokeActive = false
    private val strokeMoves = mutableListOf<Move.Single>()

    /** 开始一条笔划：清空本次收集的改动（通常在手指落下时调用）。 */
    fun startStroke() {
        strokeActive = true
        strokeMoves.clear()
    }

    /**
     * 在笔划中给单格上当前工具的目标状态。
     * 与目标状态一致或不在笔划中时返回 false（无改动）。
     * @return 是否真的把该格改成了目标状态
     */
    fun strokeCell(row: Int, col: Int, target: CellState): Boolean {
        if (!strokeActive) return false
        val before = board[row][col]
        if (before == target.value) return false
        board[row][col] = target.value
        strokeMoves.add(Move.Single(row, col, before, target.value))
        return true
    }

    /**
     * 结束笔划：把本次收集的所有单格改动压成一个 Batch 提交到撤销栈。
     * 空笔划（没画出任何变化）返回 false，不产生撤销步骤。
     * @return 是否真的产生了一次可撤销改动
     */
    fun commitStroke(): Boolean {
        strokeActive = false
        if (strokeMoves.isEmpty()) return false
        undoStack.addLast(Move.Batch(strokeMoves.toList()))
        strokeMoves.clear()
        redoStack.clear()
        return true
    }

    /** 放弃当前笔划：回滚这次画出的所有改动（手势被取消等场景）。 */
    fun cancelStroke() {
        if (strokeActive) {
            for (m in strokeMoves) board[m.row][m.col] = m.before
            strokeMoves.clear()
            strokeActive = false
        }
    }

    /** 便利方法：一条单格笔划（单击落笔即走 start/commit 的路径）。 */
    fun paintCell(row: Int, col: Int, target: CellState): Boolean {
        startStroke()
        strokeCell(row, col, target)
        return commitStroke()
    }

    /**
     * 一键补齐：把整盘改成与答案完全一致 —— 答案需要涂黑的格子补成涂黑，
     * 其余格子清回未确定（同时清掉错涂）。整批改动压成一个 Batch，可一步撤销。
     *
     * @return 是否真的产生了变化（棋盘已与答案一致时返回 false）
     */
    fun applySolution(answer: Array<IntArray>): Boolean {
        val moves = mutableListOf<Move.Single>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val target =
                    if (answer[r][c] == 1) CellState.FILLED.value else CellState.UNDECIDED.value
                val before = board[r][c]
                if (before != target) {
                    board[r][c] = target
                    moves.add(Move.Single(r, c, before, target))
                }
            }
        }
        if (moves.isEmpty()) return false
        undoStack.addLast(Move.Batch(moves))
        redoStack.clear()
        return true
    }

    /** 撤销一步：回退最近一次修改（单格或整批），并把它转入重做栈。返回是否成功撤销。 */
    fun undo(): Boolean {
        val move = undoStack.removeLastOrNull() ?: return false
        when (move) {
            is Move.Single -> board[move.row][move.col] = move.before
            is Move.Batch -> for (m in move.moves) board[m.row][m.col] = m.before
        }
        redoStack.addLast(move)
        return true
    }

    /** 重做一步：重放最近一次被撤销的修改（单格或整批），并把它转回撤销栈。返回是否成功重做。 */
    fun redo(): Boolean {
        val move = redoStack.removeLastOrNull() ?: return false
        when (move) {
            is Move.Single -> board[move.row][move.col] = move.after
            is Move.Batch -> for (m in move.moves) board[m.row][m.col] = m.after
        }
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
