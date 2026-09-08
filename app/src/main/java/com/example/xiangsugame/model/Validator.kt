package com.example.xiangsugame.model

/**
 * 实时逻辑校验。
 *
 * 负责两类职责：
 *  1. 通关判定：比对玩家涂黑结果与答案像素矩阵是否完全一致；
 *  2. 显式"检查"（F2.3）：当玩家主动点击"检查错误"时，精确标出**已提交但画错**的
 *     格子（误填的涂黑 / 误标的空白），并提供一键清除；
 *  3. 已满足提示（satisfiedClues）：某提示周围窗口已确定且数量达标 → 数字变绿。
 *
 * 错误检测策略 —— 平时**不做任何实时报错**（避免"偷看答案"的假感、不打断推理），
 * 只有在玩家主动发起"检查"时才按答案逐格比对并精确标红。中间态（没画完）不算错；
 * 未决定的格子也永远不算错 —— 你只是还没画完。
 */
class Validator(private val level: Level) {

    // 直接引用关卡数据，避免每次调用都解引用 level
    private val clues = level.clueGrid
    private val rows = level.rows
    private val cols = level.cols

    /**
     * 精确错误检测：返回所有"已提交但与答案冲突"的格子集合（显式检查用）。
     *
     * 判定逻辑：对每个格子：
     *  - 未决定（UNDECIDED）→ 跳过，不报错（还在推进，不是错）；
     *  - 已涂黑（FILLED）但答案该格应为空白 → 错；
     *  - 已标记空白（MARKED_EMPTY）但答案该格应为涂黑 → 错；
     *  - 与答案一致 → 不报错。
     *
     * @param board 当前棋盘状态（由 GameBoard.board 传入）
     * @return 错误格坐标集合；无错误时返回空集合
     */
    fun wrongCells(board: Array<IntArray>): Set<Pair<Int, Int>> {
        val result = mutableSetOf<Pair<Int, Int>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val state = CellState.fromValue(board[r][c])
                if (state == CellState.UNDECIDED) continue
                val isFilled = state == CellState.FILLED
                val answerFilled = level.answerGrid[r][c] == 1
                if (isFilled != answerFilled) result.add(r to c)
            }
        }
        return result
    }

    /**
     * 已满足的提示：某提示格周围 3×3 窗口内"没有未决定的格子"且"涂黑数恰好等于提示值"，
     * 说明该提示能推出的信息已全部用完 —— 像素解谜游戏的经典反馈
     * （对应 Picross 里"完成后的数字变暗/打勾"）。
     *
     * 注意它只表示"窗口已确定且数量达标"，不代表窗口内一定正确；
     * 若存在与答案冲突的涂黑，仍由显式"检查"标出。
     *
     * @param board 当前棋盘状态
     * @return 已满足的提示格坐标集合
     */
    fun satisfiedClues(board: Array<IntArray>): Set<Pair<Int, Int>> {
        val result = mutableSetOf<Pair<Int, Int>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val target = clues[r][c]
                if (target < 0) continue
                var filled = 0
                var undecided = 0
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val nr = r + dr
                        val nc = c + dc
                        if (nr in 0 until rows && nc in 0 until cols) {
                            when (board[nr][nc]) {
                                CellState.FILLED.value -> filled++
                                CellState.UNDECIDED.value -> undecided++
                            }
                        }
                    }
                }
                if (undecided == 0 && filled == target) result.add(r to c)
            }
        }
        return result
    }

    /**
     * 胜利判定：玩家涂黑结果与答案像素矩阵逐格比对。
     *
     * 规则：未确定 (UNDECIDED) 与标记空白 (MARKED_EMPTY) 都视为"非涂黑"，
     * 即玩家"标了叉"的格子算作空白。只有当每个格的涂黑状态（涂黑/非涂黑）
     * 都与答案完全一致时才算通关 —— 这也是 Fill-a-Pix 的完成标准：
     * 不要求玩家标记出每个空白格，只要涂黑的格都对即可。
     *
     * @param board 当前棋盘状态
     * @return true 表示与答案完全一致，可以判定通关
     */
    fun isSolved(board: Array<IntArray>): Boolean {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val isFilled = board[r][c] == CellState.FILLED.value
                val answerFilled = level.answerGrid[r][c] == 1
                if (isFilled != answerFilled) return false
            }
        }
        return true
    }
}
