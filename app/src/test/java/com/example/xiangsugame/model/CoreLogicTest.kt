package com.example.xiangsugame.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核心逻辑单元测试。
 *
 * 测试不依赖 Android 框架，直接在 JVM 上运行（纯 Kotlin 逻辑层可独立测试），
 * 覆盖 model 层六个关键能力：
 *   1. 关卡唯一解（依赖 PuzzleSolver）—— 保证内置关卡"提示充足、能推出来"；
 *   2. 提示推导（PuzzleGenerator）—— 3×3 窗口计数规则；
 *   3. 状态循环（CellState）—— 三态点击循环；
 *   4. 胜利判定（Validator）—— 与答案逐格比对；
 *   5. 确定性矛盾检测（Validator）—— 只报"必错"，不误报中间态；
 *   6. 撤销/重做（GameBoard）—— 指令栈语义。
 */
class CoreLogicTest {

    // ———— 1. 关卡数据：唯一解 + 答案一致 ————

    @Test
    fun `every demo level has a unique solution that matches the answer`() {
        for (level in Levels.all) {
            val solver = PuzzleSolver(level.clueGrid, maxSolutions = 2)
            val solutions = solver.solveAll()
            assertEquals("关卡「${level.name}」应只有唯一解", 1, solutions.size)

            val solution = solutions.first()
            for (r in 0 until level.rows) {
                for (c in 0 until level.cols) {
                    assertEquals(
                        "关卡「${level.name}」($r,$c) 解应与答案一致",
                        level.answerGrid[r][c],
                        solution[r][c],
                    )
                }
            }
        }
    }

    // ———— 2. 提示推导 ————

    @Test
    fun `clue is count of filled cells in 3x3 window`() {
        val answer = arrayOf(
            intArrayOf(1, 0, 0),
            intArrayOf(0, 0, 0),
            intArrayOf(0, 0, 1),
        )
        val clues = PuzzleGenerator.cluesFromAnswer(answer)
        assertEquals(1, clues[0][0]) // 角格窗口被裁剪，含自身 = 1
        assertEquals(2, clues[1][1]) // 中心窗口含两个对角 1
        assertEquals(1, clues[2][2])
        assertEquals(0, clues[0][2]) // 上边窗（裁剪）无涂黑
    }

    // ———— 3. 状态循环 ————

    @Test
    fun `cell state cycles empty to filled to marked to empty`() {
        var state = CellState.UNDECIDED
        state = state.next()
        assertEquals(CellState.FILLED, state)
        state = state.next()
        assertEquals(CellState.MARKED_EMPTY, state)
        state = state.next()
        assertEquals(CellState.UNDECIDED, state)
    }

    // ———— 4. 胜利判定 ————

    @Test
    fun `empty board is not solved`() {
        val level = Levels.all.first()
        val validator = Validator(level)
        assertFalse(validator.isSolved(GameBoard(level.rows, level.cols).board))
    }

    @Test
    fun `board matching answer is solved`() {
        val level = Levels.all.first()
        val validator = Validator(level)
        val board = Array(level.rows) { r -> IntArray(level.cols) { c -> level.answerGrid[r][c] } }
        assertTrue(validator.isSolved(board))
    }

    @Test
    fun `board with one wrong cell is not solved`() {
        val level = Levels.all.first()
        val validator = Validator(level)
        val board = Array(level.rows) { r -> IntArray(level.cols) { c -> level.answerGrid[r][c] } }
        // 找一个空白格涂黑，破坏答案
        outer@ for (r in 0 until level.rows) {
            for (c in 0 until level.cols) {
                if (board[r][c] == 0) {
                    board[r][c] = 1
                    break@outer
                }
            }
        }
        assertFalse(validator.isSolved(board))
    }

    // ———— 5. 确定性矛盾检测 ————

    @Test
    fun `overfilled clue is a contradiction`() {
        // (0,0) 线索为 0，把其窗口内的 (0,1) 涂黑 → filled(1) > clue(0)，必报
        val level = sparseCenterLevel()
        val validator = Validator(level)
        val gameBoard = GameBoard(level.rows, level.cols)
        gameBoard.setCell(0, 1, CellState.FILLED)
        val contradictions = validator.contradictionCells(gameBoard.board)
        assertTrue("窗口涂黑数超过线索应被判定为矛盾", contradictions.isNotEmpty())
    }

    @Test
    fun `intermediate valid state is not a contradiction`() {
        // 涂中心答案格 (2,2)：不在任何线索 0 的窗口内，属于正常中间态，不报错
        val level = sparseCenterLevel()
        val validator = Validator(level)
        val gameBoard = GameBoard(level.rows, level.cols)
        gameBoard.setCell(2, 2, CellState.FILLED)
        val contradictions = validator.contradictionCells(gameBoard.board)
        assertFalse("推进答案的中间态不应报错", contradictions.isNotEmpty())
    }

    // ———— 6. 撤销 / 重做 ————

    @Test
    fun `undo and redo restore state correctly`() {
        val gameBoard = GameBoard(3, 3)
        gameBoard.cycleCell(0, 0) // UNDECIDED -> FILLED
        assertEquals(CellState.FILLED.value, gameBoard.board[0][0])
        assertTrue(gameBoard.undo())
        assertEquals(CellState.UNDECIDED.value, gameBoard.board[0][0])
        assertTrue(gameBoard.redo())
        assertEquals(CellState.FILLED.value, gameBoard.board[0][0])
        // 新操作清空重做栈
        gameBoard.cycleCell(0, 0) // FILLED -> MARKED_EMPTY
        assertFalse(gameBoard.canRedo)
        assertTrue(gameBoard.undo())
        assertEquals(CellState.FILLED.value, gameBoard.board[0][0])
    }

    // ———— 工具 ————

    /** 5×5 中心单格涂黑：角格 (0,0) 线索为 0，中心 (2,2) 线索为 1，便于确定性断言。 */
    private fun sparseCenterLevel(): Level = Level.fromAnswer(
        id = 99,
        name = "测试中心格",
        difficulty = Difficulty.MEDIUM,
        answer = arrayOf(
            intArrayOf(0, 0, 0, 0, 0),
            intArrayOf(0, 0, 0, 0, 0),
            intArrayOf(0, 0, 1, 0, 0),
            intArrayOf(0, 0, 0, 0, 0),
            intArrayOf(0, 0, 0, 0, 0),
        ),
    )
}
