package com.example.xiangsugame.model

/**
 * 实时逻辑校验（概要设计 3.2.2 节"逻辑校验子模块"）。
 *
 * 负责两类职责：
 *  1. 解谜过程中的"错误提示"（F2.3）：精确标红玩家**已提交但画错**的格子；
 *  2. 通关判定：比对玩家涂黑结果与答案像素矩阵是否完全一致。
 *
 * 错误检测 —— 为什么用"按答案逐格比对"而不是"线索约束矛盾"？
 * 解谜过程中绝大多数中间状态天然不满足全部数字约束（因为还没画完），
 * 例如线索为 9 的格子，玩家刚涂 3 格时约束"不满足"，但这是正常推进，不是错。
 * 若用"约束矛盾"判错，会整窗误报、漏报真错，体验很差。
 * 本版采用主流像素解谜 App 的做法：错误提示 = 玩家已提交（涂黑 / 标记空白）
 * 但与该格答案相反的格子。未决定的格子不报错 —— 你只是还没画完，不算错。
 */
class Validator(private val level: Level) {

    // 直接引用关卡数据，避免每次调用都解引用 level
    private val clues = level.clueGrid
    private val rows = level.rows
    private val cols = level.cols

    /**
     * 精确错误检测：返回所有"已提交但与答案冲突"的格子集合。
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
     * 说明该提示能推出的信息已全部用完 —— 这是像素解谜游戏的经典反馈
     * （对应 Picross 里"完成后的数字变暗/打勾"）。
     *
     * 注意它只表示"窗口已确定且数量达标"，不代表窗口内一定正确；
     * 若存在与答案冲突的涂黑，仍由 [wrongCells] 精确标红。
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
