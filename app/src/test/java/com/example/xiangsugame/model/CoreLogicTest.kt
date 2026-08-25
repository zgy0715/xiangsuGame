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
 *   5. 精确错误检测（Validator）—— 按答案逐格比对，只报"画错的格子"；
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

    // ———— 5. 精确错误检测（按答案逐格比对）————

    @Test
    fun `filled cell that should be blank is flagged exactly`() {
        // (0,1) 答案应为空白，玩家却涂黑 → 精确标出 (0,1) 一格，不误伤同窗其它格
        val level = sparseCenterLevel()
        val validator = Validator(level)
        val gameBoard = GameBoard(level.rows, level.cols)
        gameBoard.setCell(0, 1, CellState.FILLED)
        val wrong = validator.wrongCells(gameBoard.board)
        assertTrue("错涂的格子应被标出", (0 to 1) in wrong)
        assertEquals("只标出真正画错的那一格", setOf(0 to 1), wrong)
    }

    @Test
    fun `correct fill is not flagged`() {
        // 涂中心答案格 (2,2)：与答案一致，属于正常推进，不报错
        val level = sparseCenterLevel()
        val validator = Validator(level)
        val gameBoard = GameBoard(level.rows, level.cols)
        gameBoard.setCell(2, 2, CellState.FILLED)
        val wrong = validator.wrongCells(gameBoard.board)
        assertTrue("与答案一致不应报错", wrong.isEmpty())
    }

    @Test
    fun `marked-empty cell that should be filled is flagged`() {
        // (2,2) 答案应涂黑，玩家却标记空白 → 标出该格
        val level = sparseCenterLevel()
        val validator = Validator(level)
        val gameBoard = GameBoard(level.rows, level.cols)
        gameBoard.setCell(2, 2, CellState.MARKED_EMPTY)
        val wrong = validator.wrongCells(gameBoard.board)
        assertTrue("错标的空白格应被标出", (2 to 2) in wrong)
    }

    @Test
    fun `undecided cells are never flagged`() {
        // 完全空盘（全是未决定）没有任何错误
        val level = sparseCenterLevel()
        val validator = Validator(level)
        val wrong = validator.wrongCells(GameBoard(level.rows, level.cols).board)
        assertTrue("未决定的格子不算错", wrong.isEmpty())
    }

    // ———— 5.1 已满足提示检测 ————

    @Test
    fun `fully determined zero window is a satisfied clue`() {
        // (0,0) 提示值为 0：把其窗口内 4 格都标为空白后，窗口已确定且涂黑数=0 → 提示满足
        val level = sparseCenterLevel()
        val validator = Validator(level)
        val gameBoard = GameBoard(level.rows, level.cols)
        gameBoard.setCell(0, 0, CellState.MARKED_EMPTY)
        gameBoard.setCell(0, 1, CellState.MARKED_EMPTY)
        gameBoard.setCell(1, 0, CellState.MARKED_EMPTY)
        gameBoard.setCell(1, 1, CellState.MARKED_EMPTY)
        val satisfied = validator.satisfiedClues(gameBoard.board)
        assertTrue("窗口已确定且数量达标的提示应被标记为满足", (0 to 0) in satisfied)
    }

    @Test
    fun `undetermined clue window is not satisfied`() {
        // 空盘：没有任何窗口已确定 → 没有已满足的提示
        val level = sparseCenterLevel()
        val validator = Validator(level)
        val satisfied = validator.satisfiedClues(GameBoard(level.rows, level.cols).board)
        assertTrue("空盘不应有已满足的提示", satisfied.isEmpty())
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
